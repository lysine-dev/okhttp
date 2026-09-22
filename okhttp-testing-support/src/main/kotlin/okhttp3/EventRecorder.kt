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
package okhttp3

import assertk.assertThat
import assertk.assertions.isCloseTo
import assertk.assertions.isFalse
import assertk.assertions.isInstanceOf
import assertk.assertions.matchesPredicate
import java.util.concurrent.ConcurrentLinkedDeque
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import okhttp3.CallEvent.CallStart
import okhttp3.CallEvent.Canceled
import org.junit.jupiter.api.Assertions.fail

open class EventRecorder(
  /**
   * An override to ignore the normal order that is enforced.
   * EventListeners added by Interceptors will not see all events.
   */
  private val enforceOrder: Boolean = true,
) {
  private val eventListenerAdapter =
    EventListenerAdapter()
      .apply {
        listeners += ::logEvent
      }

  val eventListener: EventListener
    get() = eventListenerAdapter

  /** Events that haven't yet been removed. */
  val eventSequence = LinkedBlockingQueue<CallEvent>()

  /** The full set of events, used to match starts with ends. */
  private val eventsForMatching = ConcurrentLinkedDeque<CallEvent>()

  private val forbiddenLocks = mutableListOf<Any>()

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
  fun <T : CallEvent> removeUpToEvent(eventClass: Class<T>): T {
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

  inline fun <reified T : CallEvent> removeUpToEvent(): T = removeUpToEvent(T::class.java)

  inline fun <reified T : CallEvent> findEvent(): T = eventSequence.first { it is T } as T

  /**
   * Remove and return the next event from the recorded sequence.
   *
   * @param eventClass a class to assert that the returned event is an instance of, or null to
   *     take any event class.
   * @param elapsedMs the time in milliseconds elapsed since the immediately-preceding event, or
   *     -1L to take any duration.
   */
  fun takeEvent(
    eventClass: Class<out CallEvent>? = null,
    elapsedMs: Long = -1L,
  ): CallEvent {
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

  fun recordedEventTypes() = eventSequence.map { it::class }

  fun clearAllEvents() {
    while (eventSequence.isNotEmpty()) {
      takeEvent()
    }
  }

  internal fun logEvent(e: CallEvent) {
    for (lock in forbiddenLocks) {
      assertThat(Thread.holdsLock(lock), lock.toString()).isFalse()
    }

    if (enforceOrder) {
      checkForStartEvent(e)
    }

    eventsForMatching.offer(e)
    eventSequence.offer(e)
  }

  private fun checkForStartEvent(e: CallEvent) {
    if (eventsForMatching.isEmpty()) {
      assertThat(e).matchesPredicate { it is CallStart || it is Canceled }
    } else {
      eventsForMatching.forEach loop@{
        when (e.closes(it)) {
          null -> return

          // no open event
          true -> return

          // found open event
          false -> return@loop // this is not the open event so continue
        }
      }
      fail<Any>("event $e without matching start event")
    }
  }
}
