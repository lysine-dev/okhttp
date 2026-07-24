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

import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isNull
import assertk.assertions.isSameInstanceAs
import kotlin.test.Test
import kotlin.time.AbstractLongTimeSource
import kotlin.time.ComparableTimeMark as Time
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.DurationUnit

class MemoryCacheTest {
  private var time = 0.seconds
  private val timeSource =
    object : AbstractLongTimeSource(DurationUnit.SECONDS) {
      override fun read() = time.inWholeSeconds
    }

  private val cache =
    object : MemoryCache<String, Record>(
      timeSource = timeSource,
      maxSize = 4,
    ) {
      override fun lastRequestedAt(
        now: Time,
        value: Record,
      ): Time? {
        val evictAt = value.evictAt ?: return null
        if (now >= evictAt) return null
        return value.lastRequestedAt
      }
    }

  @Test
  fun `computeIfAbsent remembers values`() {
    val a0 = cache.access("a")
    val b0 = cache.access("b")
    val c0 = cache.access("c")

    cache.assertPresent("a", a0)
    cache.assertPresent("b", b0)
    cache.assertPresent("c", c0)
  }

  /** Use a re-entrant call to simulate a concurrent call.*/
  @Test
  fun `computeIfAbsent race`() {
    val now = timeSource.markNow()
    val r0 = Record("r0", now, now)
    val r1 = Record("r1", now, now)
    val a0 =
      cache.computeIfAbsent("a") {
        val a1 = cache.computeIfAbsent("a") { r1 }
        assertThat(a1).isSameInstanceAs(r1)
        r0
      }
    assertThat(a0).isSameInstanceAs(r1)
  }

  @Test
  fun `evict removes expired records`() {
    cache.access("b")
    sleep(7.seconds)
    val a0 = cache.access("a")
    val c0 = cache.access("c")

    // All elements are in the cache and have up-to-date lastRequestedAt times.
    sleep(1.seconds)
    cache.access("a")
    sleep(1.seconds)
    cache.access("b")
    sleep(1.seconds)
    cache.access("c")

    // Evict removes b, which has reached its evictAt time.
    cache.evict(1)
    cache.assertPresent("a", a0)
    cache.assertAbsent("b")
    cache.assertPresent("c", c0)
  }

  @Test
  fun `evict removes all expired records`() {
    cache.access("a")
    cache.access("b")
    cache.access("c")
    sleep(10.seconds)

    cache.evict(1)
    cache.assertAbsent("a")
    cache.assertAbsent("b")
    cache.assertAbsent("c")
  }

  @Test
  fun `evict removes least recently used element`() {
    val a0 = cache.access("a")
    cache.access("b")
    val c0 = cache.access("c")

    sleep(1.seconds)
    cache.access("b")
    sleep(1.seconds)
    cache.access("a")
    sleep(1.seconds)
    cache.access("c")

    cache.evict(1)
    cache.assertPresent("a", a0)
    cache.assertAbsent("b")
    cache.assertPresent("c", c0)
  }

  @Test
  fun `evict removes expired element and least recently used element`() {
    // Make 'd' expired.
    cache.access("d")
    sleep(10.seconds)
    val a0 = cache.access("a")
    cache.access("b")
    val c0 = cache.access("c")
    val e0 = cache.access("e")

    // Make 'b' the least recently used.
    sleep(1.seconds)
    cache.access("b")
    sleep(1.seconds)
    cache.access("a")
    sleep(1.seconds)
    cache.access("c")
    sleep(1.seconds)
    cache.access("d")
    sleep(1.seconds)
    cache.access("e")

    cache.evict(2)
    cache.assertPresent("a", a0)
    cache.assertAbsent("b") // Least recently used.
    cache.assertPresent("c", c0)
    cache.assertAbsent("d") // Expired.
    cache.assertPresent("e", e0)
  }

  /**
   * The test's configured with a max size of 4, so adding the 8th element should automatically
   * resize it to 4 elements.
   */
  @Test
  fun `automatic eviction when size is double max`() {
    sleep(1.seconds)
    val a0 = cache.access("a")
    sleep(1.seconds)
    val b0 = cache.access("b")
    sleep(1.seconds)
    val c0 = cache.access("c")
    sleep(1.seconds)
    val d0 = cache.access("d")
    sleep(1.seconds)
    val e0 = cache.access("e")
    sleep(1.seconds)
    val f0 = cache.access("f")
    sleep(1.seconds)
    val g0 = cache.access("g")

    // Nothing evicted after 7 inserts.
    cache.assertPresent("a", a0)
    cache.assertPresent("b", b0)
    cache.assertPresent("c", c0)
    cache.assertPresent("d", d0)
    cache.assertPresent("e", e0)
    cache.assertPresent("f", f0)
    cache.assertPresent("g", g0)

    // After the 8th insert, it evicts down to size 4.
    sleep(1.seconds)
    val h0 = cache.access("h")
    cache.assertAbsent("a")
    cache.assertAbsent("b")
    cache.assertAbsent("c")
    cache.assertAbsent("d")
    cache.assertPresent("e", e0)
    cache.assertPresent("f", f0)
    cache.assertPresent("g", g0)
    cache.assertPresent("h", h0)
  }

  class Record(
    val name: String,
    var lastRequestedAt: Time?,
    var evictAt: Time?,
  ) {
    override fun toString() = name
  }

  private fun sleep(duration: Duration) {
    time += duration
  }

  fun MemoryCache<String, Record>.access(key: String): Record {
    val now = timeSource.markNow()
    val result = computeIfAbsent(key) { Record(key, now, now + 10.seconds) }
    result.lastRequestedAt = now
    return result
  }

  fun MemoryCache<String, Record>.assertAbsent(key: String) {
    val actual =
      try {
        computeIfAbsent(key) { throw Exception("absent!") }
      } catch (_: Exception) {
        null
      }
    assertThat(actual).isNull()
  }

  fun MemoryCache<String, Record>.assertPresent(
    key: String,
    expected: Record,
  ) {
    val actual = computeIfAbsent(key) { throw Exception("absent!") }
    assertThat(actual).isEqualTo(expected)
  }
}
