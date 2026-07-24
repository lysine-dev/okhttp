/*
 * Copyright (C) 2025 Square, Inc.
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

import java.util.concurrent.atomic.AtomicInteger

/**
 * A special [EventListener] for testing the mechanics of event listeners.
 *
 * Each instance processes a single event on [call], and then adds a successor [EventListenerRelay]
 * on the same [call] to process the next event.
 *
 * By forcing the list of listeners to change after every event, we can detect if buggy code caches
 * a stale [EventListener] in a field or local variable.
 */
class EventListenerRelay(
  val call: Call,
  val eventRecorder: EventRecorder,
) {
  private val eventListenerAdapter =
    EventListenerAdapter()
      .apply {
        listeners += ::onEvent
      }

  val eventListener: EventListener
    get() = eventListenerAdapter

  var eventCount = AtomicInteger()

  private fun onEvent(callEvent: CallEvent) {
    if (eventCount.getAndIncrement() == 0) {
      eventRecorder.logEvent(callEvent)
      val next = EventListenerRelay(call, eventRecorder)
      call.addEventListener(next.eventListener)
    }
  }
}
