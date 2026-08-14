/*
 * Copyright (C) 2013 Square, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
@file:OptIn(OkHttpInternalApi::class)

package okhttp3

import java.util.ArrayDeque
import java.util.concurrent.ExecutorService
import java.util.concurrent.SynchronousQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import okhttp3.internal.OkHttpInternalApi
import okhttp3.internal.assertLockNotHeld
import okhttp3.internal.connection.RealCall
import okhttp3.internal.connection.RealCall.AsyncCall
import okhttp3.internal.okHttpName
import okhttp3.internal.threadFactory
import okhttp3.internal.unmodifiable

/**
 * Policy on when async requests are executed.
 *
 * Each dispatcher uses an [ExecutorService] to run calls internally. If you supply your own
 * executor, it must accept new tasks while up to [the configured maximum][maxRequests] calls
 * are already running. A pool sized exactly to [maxRequests] is not sufficient with a
 * [SynchronousQueue], because the dispatcher may submit the next call from a worker that has
 * not yet returned to the queue. Prefer the default pattern (`corePoolSize=0`,
 * `maxPoolSize=Int.MAX_VALUE`, [SynchronousQueue]), a bound of about `2 * maxRequests`, or a
 * queueing executor.
 */
class Dispatcher() {
  /**
   * The maximum number of requests to execute concurrently. Above this requests queue in memory,
   * waiting for the running calls to complete.
   *
   * If more than [maxRequests] requests are in flight when this is invoked, those requests will
   * remain in flight.
   */
  @get:Synchronized
  var maxRequests = 64
    set(maxRequests) {
      require(maxRequests >= 1) { "max < 1: $maxRequests" }
      synchronized(this) {
        field = maxRequests
      }
      promoteAndExecute()
    }

  /**
   * The maximum number of requests for each host to execute concurrently. This limits requests by
   * the URL's host name. Note that concurrent requests to a single IP address may still exceed this
   * limit: multiple hostnames may share an IP address or be routed through the same HTTP proxy.
   *
   * If more than [maxRequestsPerHost] requests are in flight when this is invoked, those requests
   * will remain in flight.
   *
   * WebSocket connections to hosts **do not** count against this limit.
   */
  @get:Synchronized
  var maxRequestsPerHost = 5
    set(maxRequestsPerHost) {
      require(maxRequestsPerHost >= 1) { "max < 1: $maxRequestsPerHost" }
      synchronized(this) {
        field = maxRequestsPerHost
      }
      promoteAndExecute()
    }

  /**
   * A callback to be invoked each time the dispatcher becomes idle (when the number of running
   * calls returns to zero).
   *
   * Note: The time at which a [call][Call] is considered idle is different depending on whether it
   * was run [asynchronously][Call.enqueue] or [synchronously][Call.execute]. Asynchronous calls
   * become idle after the [onResponse][Callback.onResponse] or [onFailure][Callback.onFailure]
   * callback has returned. Synchronous calls become idle once [execute()][Call.execute] returns.
   * This means that if you are doing synchronous calls the network layer will not truly be idle
   * until every returned [Response] has been closed.
   */
  @get:Synchronized
  @set:Synchronized
  var idleCallback: Runnable? = null

  private var executorServiceOrNull: ExecutorService? = null

  @get:JvmName("executorService")
  @get:Synchronized
  val executorService: ExecutorService
    get() {
      if (executorServiceOrNull == null) {
        executorServiceOrNull =
          ThreadPoolExecutor(
            0,
            Int.MAX_VALUE,
            60,
            TimeUnit.SECONDS,
            SynchronousQueue(),
            threadFactory("$okHttpName Dispatcher", false),
          )
      }
      return executorServiceOrNull!!
    }

  /** Ready async calls in the order they'll be run. */
  private val readyAsyncCalls = ArrayDeque<AsyncCall>()

  /** Running asynchronous calls. Includes canceled calls that haven't finished yet. */
  private val runningAsyncCalls = ArrayDeque<AsyncCall>()

  /** Running synchronous calls. Includes canceled calls that haven't finished yet. */
  private val runningSyncCalls = ArrayDeque<RealCall>()

  constructor(executorService: ExecutorService?) : this() {
    this.executorServiceOrNull = executorService
  }

  internal fun enqueue(call: AsyncCall) {
    promoteAndExecute(enqueuedCall = call)
  }

  private fun findExistingCallWithHost(host: String): AsyncCall? {
    for (existingCall in runningAsyncCalls) {
      if (existingCall.host == host) return existingCall
    }
    for (existingCall in readyAsyncCalls) {
      if (existingCall.host == host) return existingCall
    }
    return null
  }

  /**
   * Cancel all calls currently enqueued or executing. Includes calls executed both
   * [synchronously][Call.execute] and [asynchronously][Call.enqueue].
   */
  @Synchronized
  fun cancelAll() {
    for (call in readyAsyncCalls) {
      call.call.cancel()
    }
    for (call in runningAsyncCalls) {
      call.call.cancel()
    }
    for (call in runningSyncCalls) {
      call.cancel()
    }
  }

  /**
   * Promotes eligible calls from [readyAsyncCalls] to [runningAsyncCalls] and runs them on the
   * executor service. Must not be called with synchronization because executing calls can call
   * into user code.
   *
   * @param enqueuedCall a call to enqueue in the synchronized block
   * @param finishedCall a call to finish in the synchronized block
   * @param finishedAsyncCall an async call to finish in the synchronized block
   */
  private fun promoteAndExecute(
    enqueuedCall: AsyncCall? = null,
    finishedCall: RealCall? = null,
    finishedAsyncCall: AsyncCall? = null,
  ) {
    assertLockNotHeld()
    val executorIsShutdown = executorService.isShutdown

    // Actions to take outside the synchronized block.
    class Effects(
      val callsToExecute: List<AsyncCall>,
      val idleCallbackToRun: Runnable?,
    )

    val effects =
      synchronized(this) {
        if (finishedCall != null) {
          check(runningSyncCalls.remove(finishedCall)) { "Call wasn't in-flight!" }
        }

        if (finishedAsyncCall != null) {
          finishedAsyncCall.callsPerHost.decrementAndGet()
          check(runningAsyncCalls.remove(finishedAsyncCall)) { "Call wasn't in-flight!" }
        }

        if (enqueuedCall != null) {
          readyAsyncCalls.add(enqueuedCall)

          // Mutate the AsyncCall so that it shares the AtomicInteger of an existing running call to
          // the same host.
          if (!enqueuedCall.call.forWebSocket) {
            val existingCall = findExistingCallWithHost(enqueuedCall.host)
            if (existingCall != null) enqueuedCall.reuseCallsPerHostFrom(existingCall)
          }
        }

        val becameIdle =
          (finishedCall != null || finishedAsyncCall != null) &&
            (executorIsShutdown || runningAsyncCalls.isEmpty()) &&
            runningSyncCalls.isEmpty()
        val idleCallbackToRun = if (becameIdle) idleCallback else null

        if (executorIsShutdown) {
          return@synchronized Effects(
            callsToExecute =
              readyAsyncCalls
                .toList()
                .also { readyAsyncCalls.clear() },
            idleCallbackToRun = idleCallbackToRun,
          )
        }

        val callsToExecute = mutableListOf<AsyncCall>()
        val i = readyAsyncCalls.iterator()
        while (i.hasNext()) {
          val asyncCall = i.next()

          if (runningAsyncCalls.size >= this.maxRequests) break // Max capacity.
          if (asyncCall.callsPerHost.get() >= this.maxRequestsPerHost) continue // Host max capacity.

          i.remove()

          asyncCall.callsPerHost.incrementAndGet()
          callsToExecute.add(asyncCall)
          runningAsyncCalls.add(asyncCall)
        }

        return@synchronized Effects(
          callsToExecute = callsToExecute,
          idleCallbackToRun = idleCallbackToRun,
        )
      }

    var callDispatcherQueueStart = true

    for (i in 0 until effects.callsToExecute.size) {
      val call = effects.callsToExecute[i]

      // If the newly-enqueued call is already out, skip its dispatcher queue events. We only
      // publish those events for calls that have to wait.
      if (call === enqueuedCall) {
        callDispatcherQueueStart = false
      } else {
        call.call.eventListener.dispatcherQueueEnd(call.call, this)
      }

      if (executorIsShutdown) {
        call.failRejected()
      } else {
        call.executeOn(executorService)
      }
    }

    if (callDispatcherQueueStart && enqueuedCall != null) {
      enqueuedCall.call.eventListener.dispatcherQueueStart(enqueuedCall.call, this)
    }

    effects.idleCallbackToRun?.run()
  }

  /** Used by [Call.execute] to signal it is in-flight. */
  @Synchronized
  internal fun executed(call: RealCall) = runningSyncCalls.add(call)

  /** Used by [AsyncCall.run] to signal completion. */
  internal fun finished(call: AsyncCall) {
    promoteAndExecute(finishedAsyncCall = call)
  }

  /** Used by [Call.execute] to signal completion. */
  internal fun finished(call: RealCall) {
    promoteAndExecute(finishedCall = call)
  }

  /** Returns a snapshot of the calls currently awaiting execution. */
  @Synchronized
  fun queuedCalls(): List<Call> = readyAsyncCalls.map { it.call }.unmodifiable()

  /** Returns a snapshot of the calls currently being executed. */
  @Synchronized
  fun runningCalls(): List<Call> = (runningSyncCalls + runningAsyncCalls.map { it.call }).unmodifiable()

  @Synchronized
  fun queuedCallsCount(): Int = readyAsyncCalls.size

  @Synchronized
  fun runningCallsCount(): Int = runningAsyncCalls.size + runningSyncCalls.size

  @JvmName("-deprecated_executorService")
  @Deprecated(
    message = "moved to val",
    replaceWith = ReplaceWith(expression = "executorService"),
    level = DeprecationLevel.ERROR,
  )
  fun executorService(): ExecutorService = executorService
}
