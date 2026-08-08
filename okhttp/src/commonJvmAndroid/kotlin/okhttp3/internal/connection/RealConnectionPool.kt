/*
 *  Licensed to the Apache Software Foundation (ASF) under one or more
 *  contributor license agreements.  See the NOTICE file distributed with
 *  this work for additional information regarding copyright ownership.
 *  The ASF licenses this file to You under the Apache License, Version 2.0
 *  (the "License"); you may not use this file except in compliance with
 *  the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */
package okhttp3.internal.connection

import java.net.Socket
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.TimeUnit
import okhttp3.Address
import okhttp3.ConnectionPool
import okhttp3.Route
import okhttp3.internal.closeQuietly
import okhttp3.internal.concurrent.Task
import okhttp3.internal.concurrent.TaskQueue
import okhttp3.internal.concurrent.TaskRunner
import okhttp3.internal.concurrent.assertLockHeld
import okhttp3.internal.concurrent.withLock
import okhttp3.internal.connection.RealCall.CallReference
import okhttp3.internal.okHttpName
import okhttp3.internal.platform.Platform

class RealConnectionPool internal constructor(
  taskRunner: TaskRunner,
  /** The maximum number of idle connections across all addresses. */
  private val maxIdleConnections: Int,
  keepAliveDuration: Long,
  timeUnit: TimeUnit,
  internal val connectionListener: ConnectionListener,
) {
  internal val keepAliveDurationNs: Long = timeUnit.toNanos(keepAliveDuration)

  private val cleanupQueue: TaskQueue = taskRunner.newQueue()
  private val cleanupTask =
    object : Task("$okHttpName ConnectionPool connection closer") {
      override fun runOnce(): Long = closeConnections(System.nanoTime())
    }

  /**
   * Holding the lock of the connection being added or removed when mutating this, and check its
   * [RealConnection.noNewExchanges] property. This defends against races where a connection is
   * simultaneously adopted and removed.
   */
  private val connections = ConcurrentLinkedQueue<RealConnection>()

  init {
    // Put a floor on the keep alive duration, otherwise cleanup will spin loop.
    require(keepAliveDuration > 0L) { "keepAliveDuration <= 0: $keepAliveDuration" }
  }

  fun idleConnectionCount(): Int =
    connections.count {
      it.withLock { it.calls.isEmpty() }
    }

  fun connectionCount(): Int = connections.size

  /**
   * Attempts to acquire a recycled connection to [address] for [call]. Returns the connection if it
   * was acquired, or null if no connection was acquired. The acquired connection will also be
   * given to [call] who may (for example) assign it to a [RealCall.connection].
   *
   * This confirms the returned connection is healthy before returning it. If this encounters any
   * unhealthy connections in its search, this will clean them up.
   *
   * If [routes] is non-null these are the resolved routes (ie. IP addresses) for the connection.
   * This is used to coalesce related domains to the same HTTP/2 connection, such as `square.com`
   * and `square.ca`.
   */
  internal fun callAcquirePooledConnection(
    doExtensiveHealthChecks: Boolean,
    address: Address,
    call: RealCall,
    routes: List<Route>?,
    requireMultiplexed: Boolean,
  ): RealConnection? {
    for (connection in connections) {
      // In the first synchronized block, acquire the connection if it can satisfy this call.
      val acquired =
        connection.withLock {
          when {
            requireMultiplexed && !connection.isMultiplexed -> {
              false
            }

            !connection.isEligible(address, routes) -> {
              false
            }

            else -> {
              call.acquireConnectionNoEvents(connection)
              true
            }
          }
        }
      if (!acquired) continue

      // Confirm the connection is healthy and return it.
      if (connection.isHealthy(doExtensiveHealthChecks)) return connection

      // In the second synchronized block, release the unhealthy acquired connection. We're also on
      // the hook to close this connection if it's no longer in use.
      val noNewExchangesEvent: Boolean
      val toClose: Socket? =
        connection.withLock {
          noNewExchangesEvent = !connection.noNewExchanges
          connection.noNewExchanges = true
          call.releaseConnectionNoEvents()
        }
      if (toClose != null) {
        toClose.closeQuietly()
        connectionListener.connectionClosed(connection)
      } else if (noNewExchangesEvent) {
        connectionListener.noNewExchanges(connection)
      }
    }
    return null
  }

  fun put(connection: RealConnection) {
    connection.assertLockHeld()

    connections.add(connection)
//    connection.queueEvent { connectionListener.connectEnd(connection) }
    scheduleCloser()
  }

  /**
   * Notify this pool that [connection] has become idle. Returns true if the connection has been
   * removed from the pool and should be closed.
   */
  fun connectionBecameIdle(connection: RealConnection): Boolean {
    connection.assertLockHeld()

    return if (connection.noNewExchanges || maxIdleConnections == 0) {
      connection.noNewExchanges = true
      connections.remove(connection)
      if (connections.isEmpty()) cleanupQueue.cancelAll()
      true
    } else {
      scheduleCloser()
      false
    }
  }

  fun evictAll() {
    val i = connections.iterator()
    while (i.hasNext()) {
      val connection = i.next()
      val socketToClose =
        connection.withLock {
          if (connection.calls.isEmpty()) {
            i.remove()
            connection.noNewExchanges = true
            return@withLock connection.socket()
          } else {
            return@withLock null
          }
        }
      if (socketToClose != null) {
        socketToClose.closeQuietly()
        connectionListener.connectionClosed(connection)
      }
    }

    if (connections.isEmpty()) cleanupQueue.cancelAll()
  }

  /**
   * Performs maintenance on this pool, evicting the connection that has been idle the longest if
   * either it has exceeded the keep alive limit or the idle connections limit.
   *
   * Returns the duration in nanoseconds to sleep until the next scheduled call to this method.
   * Returns -1 if no further cleanups are required.
   */
  fun closeConnections(now: Long): Long {
    // Find the longest-idle connections in 2 categories:
    //
    //  1. OLD: Connections that have been idle for at least keepAliveDurationNs. We close these if
    //     we find them, regardless of what the address policies need.
    //
    //  2. EVICTABLE: Connections not required by any address policy. This matches connections that
    //     don't participate in any policy, plus connections whose policies won't be violated if the
    //     connection is closed. We only close these if the idle connection limit is exceeded.
    //
    // Also count the evictable connections to find out if we must close an EVICTABLE connection
    // before its keepAliveDurationNs is reached.
    var earliestOldIdleAtNs = (now - keepAliveDurationNs) + 1
    var earliestOldConnection: RealConnection? = null
    var earliestEvictableIdleAtNs = Long.MAX_VALUE
    var earliestEvictableConnection: RealConnection? = null
    var inUseConnectionCount = 0
    var evictableConnectionCount = 0
    for (connection in connections) {
      connection.withLock {
        // If the connection is in use, keep searching.
        if (pruneAndGetAllocationCount(connection, now) > 0) {
          inUseConnectionCount++
          return@withLock
        }

        val idleAtNs = connection.idleAtNs

        if (idleAtNs < earliestOldIdleAtNs) {
          earliestOldIdleAtNs = idleAtNs
          earliestOldConnection = connection
        }

        evictableConnectionCount++
        if (idleAtNs < earliestEvictableIdleAtNs) {
          earliestEvictableIdleAtNs = idleAtNs
          earliestEvictableConnection = connection
        }
      }
    }

    val toEvict: RealConnection?
    val toEvictIdleAtNs: Long
    when {
      // We had at least one OLD connection. Close the oldest one.
      earliestOldConnection != null -> {
        toEvict = earliestOldConnection
        toEvictIdleAtNs = earliestOldIdleAtNs
      }

      // We have too many EVICTABLE connections. Close the oldest one.
      evictableConnectionCount > maxIdleConnections -> {
        toEvict = earliestEvictableConnection
        toEvictIdleAtNs = earliestEvictableIdleAtNs
      }

      else -> {
        toEvict = null
        toEvictIdleAtNs = -1L
      }
    }

    when {
      toEvict != null -> {
        // We've chosen a connection to evict. Confirm it's still okay to be evicted, then close it.
        toEvict.withLock {
          if (toEvict.calls.isNotEmpty()) return 0L // No longer idle.
          if (toEvict.idleAtNs != toEvictIdleAtNs) return 0L // No longer oldest.
          toEvict.noNewExchanges = true
          connections.remove(toEvict)
        }
        toEvict.socket().closeQuietly()
        connectionListener.connectionClosed(toEvict)
        if (connections.isEmpty()) cleanupQueue.cancelAll()

        // Clean up again immediately.
        return 0L
      }

      earliestEvictableConnection != null -> {
        // A connection will be ready to evict soon.
        return earliestEvictableIdleAtNs + keepAliveDurationNs - now
      }

      inUseConnectionCount > 0 -> {
        // All connections are in use. It'll be at least the keep alive duration 'til we run again.
        return keepAliveDurationNs
      }

      else -> {
        // No connections, idle or in use.
        return -1
      }
    }
  }

  /**
   * Prunes any leaked calls and then returns the number of remaining live calls on [connection].
   * Calls are leaked if the connection is tracking them but the application code has abandoned
   * them. Leak detection is imprecise and relies on garbage collection.
   */
  private fun pruneAndGetAllocationCount(
    connection: RealConnection,
    now: Long,
  ): Int {
    connection.assertLockHeld()

    val references = connection.calls
    var i = 0
    while (i < references.size) {
      val reference = references[i]

      if (reference.get() != null) {
        i++
        continue
      }

      // We've discovered a leaked call. This is an application bug.
      val callReference = reference as CallReference
      val message =
        "A connection to ${connection.route().address.url} was leaked. " +
          "Did you forget to close a response body?"
      Platform.get().logCloseableLeak(message, callReference.callStackTrace)

      references.removeAt(i)

      // If this was the last allocation, the connection is eligible for immediate eviction.
      if (references.isEmpty()) {
        connection.idleAtNs = now - keepAliveDurationNs
        connection.idleAtEpochMillis = System.currentTimeMillis()
        return 0
      }
    }

    return references.size
  }

  fun scheduleCloser() {
    cleanupQueue.schedule(cleanupTask)
  }

  companion object {
    fun get(connectionPool: ConnectionPool): RealConnectionPool = connectionPool.delegate
  }
}
