/*
 * Copyright (c) 2026 OkHttp Authors
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
package okhttp3.internal.dns

import java.util.PriorityQueue
import java.util.concurrent.ConcurrentHashMap
import kotlin.time.ComparableTimeMark as Time
import kotlin.time.TimeSource

/**
 * This cache is configured with a target [maxSize], but it will not evict entries until it
 * has twice as many entries.
 *
 * This retains entries in least-recently-used order.
 *
 * This evicts in big batches because each eviction must traverse the entire cache.
 */
abstract class MemoryCache<K : Any, V : Any>(
  private val timeSource: TimeSource.WithComparableMarks,
  val maxSize: Int,
) {
  private val entries = ConcurrentHashMap<K, V>()

  init {
    require(maxSize >= 0)
  }

  /**
   * Returns the time this value was most recently used, in order to make an eviction decision. This
   * should return null if this element should be evicted immediately.
   */
  abstract fun lastRequestedAt(
    now: Time,
    value: V,
  ): Time?

  /**
   * Similar to [ConcurrentHashMap.computeIfAbsent], but this will also prune the cache to size if
   * this function grows the cache to double [maxSize].
   */
  fun computeIfAbsent(
    key: K,
    computeValue: () -> V,
  ): V {
    if (maxSize == 0) return computeValue()

    val result = entries[key]
    if (result != null) return result

    val created = computeValue()
    val existing = entries.putIfAbsent(key, created)
    if (existing != null) return existing

    // If there's more than 2x as many entries as the max, automatically evict.
    val toEvict = entries.size - maxSize
    if (toEvict >= maxSize) {
      evict(toEvict)
    }

    return created
  }

  /**
   * Manually evict [count] entries from this cache.
   *
   * If the cache is smaller than [count], this evicts everything.
   *
   * Entries that are expired are evicted unconditionally, even if this causes more than [count]
   * entries to be evicted. If [count] or more expired entries are evicted, non-expired entries will
   * not be evicted.
   */
  fun evict(count: Int) {
    // Note that we call lastRequestedAt() exactly once for each entry. If we called it on-demand in
    // the comparator, we'd break the PriorityQueue's invariant that the sort order is stable.
    var count = count
    val now = timeSource.markNow()
    val entriesToEvict = PriorityQueue<Pair<Time, Map.Entry<K, V>>>(count + 1, NewestFirst)
    val i = entries.entries.iterator()
    while (i.hasNext()) {
      val entry = i.next()

      when (val lastRequestedAt = lastRequestedAt(now, entry.value)) {
        // It's expired, evict unconditionally.
        null -> {
          i.remove()
          count--
        }

        // It's not expired. Make it a candidate for eviction.
        else -> {
          entriesToEvict.add(lastRequestedAt to entry)
        }
      }

      // If we're above our target eviction count, save the newest entry from eviction.
      if (entriesToEvict.size > count) {
        entriesToEvict.poll()
      }
    }

    for ((_, entry) in entriesToEvict) {
      entries.remove(entry.key, entry.value)
    }
  }

  private object NewestFirst : Comparator<Pair<Time, *>> {
    override fun compare(
      o1: Pair<Time, *>,
      o2: Pair<Time, *>,
    ) = o2.first.compareTo(o1.first)
  }
}
