/*
 * Copyright (C) 2017 Square, Inc.
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
@file:Suppress(
  "CANNOT_OVERRIDE_INVISIBLE_MEMBER",
  "INVISIBLE_MEMBER",
  "INVISIBLE_REFERENCE",
)

package okhttp3

import assertk.assertThat
import assertk.assertions.isCloseTo
import assertk.assertions.isFalse
import assertk.assertions.isInstanceOf
import assertk.assertions.matchesPredicate
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import okhttp3.ConnectionEvent.NoNewExchanges
import okhttp3.internal.connection.ConnectionListener
import okhttp3.internal.connection.RealConnection
import okio.IOException
import org.junit.jupiter.api.Assertions

internal open class RecordingConnectionListener(
  /**
   * An override to ignore the normal order that is enforced.
   * EventListeners added by Interceptors will not see all events.
   */
  private val enforceOrder: Boolean = true,
) : ConnectionListener() {
  val eventSequence = LinkedBlockingQueue<ConnectionEvent>()

  private val forbiddenLocks = mutableSetOf<Any>()

  /** The timestamp of the last taken event, used to measure elapsed time between events. */
  private var lastTimestampNs: Long? = null

  /** Confirm that the thread does not hold a lock on `lock` during the callback. */
  fun forbidLock(lock: Any) {
    forbiddenLocks.add(lock)
  }

  /**
   * Removes recorded events up to (and including) an event is found whose class equals [eventClass]
   * and returns it.
   */
  fun <T : ConnectionEvent> removeUpToEvent(eventClass: Class<T>): T {
    val fullEventSequence = eventSequence.toList()
    try {
      while (true) {
        val event = takeEvent()
        if (eventClass.isInstance(event)) {
          return eventClass.cast(event)
        }
      }
    } catch (e: NoSuchElementException) {
      throw AssertionError("full event sequence: $fullEventSequence", e)
    }
  }

  /**
   * Remove and return the next event from the recorded sequence.
   *
   * @param eventClass a class to assert that the returned event is an instance of, or null to
   *     take any event class.
   * @param elapsedMs the time in milliseconds elapsed since the immediately-preceding event, or
   *     -1L to take any duration.
   */
  fun takeEvent(
    eventClass: Class<out ConnectionEvent>? = null,
    elapsedMs: Long = -1L,
  ): ConnectionEvent {
    val result = eventSequence.take()
    val actualElapsedNs = result.timestampNs - (lastTimestampNs ?: result.timestampNs)
    lastTimestampNs = result.timestampNs

    if (eventClass != null) {
      assertThat(result).isInstanceOf(eventClass)
    }

    if (elapsedMs != -1L) {
      assertThat(
        TimeUnit.NANOSECONDS
          .toMillis(actualElapsedNs)
          .toDouble(),
      ).isCloseTo(elapsedMs.toDouble(), 100.0)
    }

    return result
  }

  fun recordedEventTypes() = eventSequence.map { it.name }

  fun clearAllEvents() {
    while (eventSequence.isNotEmpty()) {
      takeEvent()
    }
  }

  private fun logEvent(e: ConnectionEvent) {
    if (e.connection != null) {
      assertThat(Thread.holdsLock(e.connection), "Called with lock $${e.connection}")
        .isFalse()
    }
    for (lock in forbiddenLocks) {
      assertThat(Thread.holdsLock(lock), "Called with lock $lock")
        .isFalse()
    }

    if (enforceOrder) {
      checkForStartEvent(e)
    }

    eventSequence.offer(e)
  }

  private fun checkForStartEvent(e: ConnectionEvent) {
    if (eventSequence.isEmpty()) {
      assertThat(e).isInstanceOf(ConnectionEvent.ConnectStart::class.java)
    } else {
      eventSequence.forEach loop@{
        when (e.closes(it)) {
          null -> return

          // no open event
          true -> return

          // found open event
          false -> return@loop // this is not the open event so continue
        }
      }
      Assertions.fail<Any>("event $e without matching start event")
    }
  }

  override fun connectStart(
    route: Route,
    call: Call,
  ) = logEvent(ConnectionEvent.ConnectStart(System.nanoTime(), route, call))

  override fun connectFailed(
    route: Route,
    call: Call,
    failure: IOException,
  ) = logEvent(
    ConnectionEvent.ConnectFailed(System.nanoTime(), route, call, failure),
  )

  override fun connectEnd(
    connection: Connection,
    route: Route,
    call: Call,
  ) {
    logEvent(ConnectionEvent.ConnectEnd(System.nanoTime(), connection, route, call))
  }

  override fun connectionClosed(connection: Connection) = logEvent(ConnectionEvent.ConnectionClosed(System.nanoTime(), connection))

  override fun connectionAcquired(
    connection: Connection,
    call: Call,
  ) {
    logEvent(ConnectionEvent.ConnectionAcquired(System.nanoTime(), connection, call))
  }

  override fun connectionReleased(
    connection: Connection,
    call: Call,
  ) {
    if (eventSequence.find { it is ConnectionEvent.ConnectStart && it.connection == connection } != null && connection is RealConnection) {
      if (connection.noNewExchanges) {
        assertThat(eventSequence).matchesPredicate { deque ->
          deque.any { it is NoNewExchanges && it.connection == connection }
        }
      }
    }

    logEvent(ConnectionEvent.ConnectionReleased(System.nanoTime(), connection, call))
  }

  override fun noNewExchanges(connection: Connection) = logEvent(NoNewExchanges(System.nanoTime(), connection))
}
