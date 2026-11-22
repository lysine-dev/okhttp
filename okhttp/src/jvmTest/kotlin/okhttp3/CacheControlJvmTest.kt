/*
 * Copyright (C) 2014 Square, Inc.
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
import assertk.assertions.isEqualTo
import assertk.assertions.isFalse
import assertk.assertions.isSameInstanceAs
import assertk.assertions.isTrue
import java.util.concurrent.TimeUnit
import kotlin.test.assertFailsWith
import okhttp3.CacheControl.Companion.parse
import okhttp3.Headers.Companion.headersOf
import org.junit.jupiter.api.Test

class CacheControlJvmTest {
  @Test
  @Throws(Exception::class)
  fun completeBuilder() {
    val cacheControl =
      CacheControl
        .Builder()
        .noCache()
        .noStore()
        .maxAge(1, TimeUnit.SECONDS)
        .maxStale(2, TimeUnit.SECONDS)
        .minFresh(3, TimeUnit.SECONDS)
        .onlyIfCached()
        .noTransform()
        .immutable()
        .build()
    assertThat(cacheControl.toString()).isEqualTo(
      "no-cache, no-store, max-age=1, max-stale=2, min-fresh=3, only-if-cached, " +
        "no-transform, immutable",
    )
    assertThat(cacheControl.noCache).isTrue()
    assertThat(cacheControl.noStore).isTrue()
    assertThat(cacheControl.maxAgeSeconds).isEqualTo(1)
    assertThat(cacheControl.maxStaleSeconds).isEqualTo(2)
    assertThat(cacheControl.minFreshSeconds).isEqualTo(3)
    assertThat(cacheControl.onlyIfCached).isTrue()
    assertThat(cacheControl.noTransform).isTrue()
    assertThat(cacheControl.immutable).isTrue()

    // These members are accessible to response headers only.
    assertThat(cacheControl.sMaxAgeSeconds).isEqualTo(-1)
    assertThat(cacheControl.isPrivate).isFalse()
    assertThat(cacheControl.isPublic).isFalse()
    assertThat(cacheControl.mustRevalidate).isFalse()
  }

  @Test
  @Throws(Exception::class)
  fun parseEmpty() {
    val cacheControl =
      parse(
        Headers.Builder().set("Cache-Control", "").build(),
      )
    assertThat(cacheControl.toString()).isEqualTo("")
    assertThat(cacheControl.noCache).isFalse()
    assertThat(cacheControl.noStore).isFalse()
    assertThat(cacheControl.maxAgeSeconds).isEqualTo(-1)
    assertThat(cacheControl.sMaxAgeSeconds).isEqualTo(-1)
    assertThat(cacheControl.isPublic).isFalse()
    assertThat(cacheControl.mustRevalidate).isFalse()
    assertThat(cacheControl.maxStaleSeconds).isEqualTo(-1)
    assertThat(cacheControl.minFreshSeconds).isEqualTo(-1)
    assertThat(cacheControl.onlyIfCached).isFalse()
    assertThat(cacheControl.mustRevalidate).isFalse()
  }

  @Test
  @Throws(Exception::class)
  fun parse() {
    val header = (
      "no-cache, no-store, max-age=1, s-maxage=2, private, public, must-revalidate, " +
        "max-stale=3, min-fresh=4, only-if-cached, no-transform"
    )
    val cacheControl =
      parse(
        Headers
          .Builder()
          .set("Cache-Control", header)
          .build(),
      )
    assertThat(cacheControl.noCache).isTrue()
    assertThat(cacheControl.noStore).isTrue()
    assertThat(cacheControl.maxAgeSeconds).isEqualTo(1)
    assertThat(cacheControl.sMaxAgeSeconds).isEqualTo(2)
    assertThat(cacheControl.isPrivate).isTrue()
    assertThat(cacheControl.isPublic).isTrue()
    assertThat(cacheControl.mustRevalidate).isTrue()
    assertThat(cacheControl.maxStaleSeconds).isEqualTo(3)
    assertThat(cacheControl.minFreshSeconds).isEqualTo(4)
    assertThat(cacheControl.onlyIfCached).isTrue()
    assertThat(cacheControl.noTransform).isTrue()
    assertThat(cacheControl.toString()).isEqualTo(header)
  }

  @Test
  @Throws(Exception::class)
  fun parseIgnoreCacheControlExtensions() {
    // Example from http://www.w3.org/Protocols/rfc2616/rfc2616-sec14.html#sec14.9.6
    val header = "private, community=\"UCI\""
    val cacheControl =
      parse(
        Headers
          .Builder()
          .set("Cache-Control", header)
          .build(),
      )
    assertThat(cacheControl.noCache).isFalse()
    assertThat(cacheControl.noStore).isFalse()
    assertThat(cacheControl.maxAgeSeconds).isEqualTo(-1)
    assertThat(cacheControl.sMaxAgeSeconds).isEqualTo(-1)
    assertThat(cacheControl.isPrivate).isTrue()
    assertThat(cacheControl.isPublic).isFalse()
    assertThat(cacheControl.mustRevalidate).isFalse()
    assertThat(cacheControl.maxStaleSeconds).isEqualTo(-1)
    assertThat(cacheControl.minFreshSeconds).isEqualTo(-1)
    assertThat(cacheControl.onlyIfCached).isFalse()
    assertThat(cacheControl.noTransform).isFalse()
    assertThat(cacheControl.immutable).isFalse()
    assertThat(cacheControl.toString()).isEqualTo(header)
  }

  @Test
  fun parseCacheControlAndPragmaAreCombined() {
    val headers = headersOf("Cache-Control", "max-age=12", "Pragma", "must-revalidate", "Pragma", "public")
    val cacheControl = parse(headers)
    assertThat(cacheControl.toString()).isEqualTo("max-age=12, public, must-revalidate")
  }

  @Test
  fun parseCacheControlHeaderValueIsRetained() {
    val value = "max-age=12"
    val headers = headersOf("Cache-Control", value)
    val cacheControl = parse(headers)
    assertThat(cacheControl.toString()).isSameInstanceAs(value)
  }

  @Test
  fun parseCacheControlHeaderValueInvalidatedByPragma() {
    val headers =
      headersOf(
        "Cache-Control",
        "max-age=12",
        "Pragma",
        "must-revalidate",
      )
    val cacheControl = parse(headers)
    assertThat(cacheControl.toString()).isEqualTo("max-age=12, must-revalidate")
  }

  @Test
  fun parseCacheControlHeaderValueInvalidatedByTwoValues() {
    val headers =
      headersOf(
        "Cache-Control",
        "max-age=12",
        "Cache-Control",
        "must-revalidate",
      )
    val cacheControl = parse(headers)
    assertThat(cacheControl.toString()).isEqualTo("max-age=12, must-revalidate")
  }

  @Test
  fun parsePragmaHeaderValueIsNotRetained() {
    val headers = headersOf("Pragma", "must-revalidate")
    val cacheControl = parse(headers)
    assertThat(cacheControl.toString()).isEqualTo("must-revalidate")
  }

  @Test
  fun computedHeaderValueIsCached() {
    val cacheControl =
      CacheControl
        .Builder()
        .maxAge(2, TimeUnit.DAYS)
        .build()
    assertThat(cacheControl.toString()).isEqualTo("max-age=172800")
    assertThat(cacheControl.toString()).isSameInstanceAs(cacheControl.toString())
  }

  @Test
  @Throws(Exception::class)
  fun timeDurationTruncatedToMaxValue() {
    val cacheControl =
      CacheControl
        .Builder()
        .maxAge(365 * 100, TimeUnit.DAYS) // Longer than Integer.MAX_VALUE seconds.
        .build()
    assertThat(cacheControl.maxAgeSeconds).isEqualTo(Int.MAX_VALUE)
  }

  @Test
  @Throws(Exception::class)
  fun secondsMustBeNonNegative() {
    val builder = CacheControl.Builder()
    assertFailsWith<IllegalArgumentException> {
      builder.maxAge(-1, TimeUnit.SECONDS)
    }
  }

  @Test
  @Throws(Exception::class)
  fun timePrecisionIsTruncatedToSeconds() {
    val cacheControl =
      CacheControl
        .Builder()
        .maxAge(4999, TimeUnit.MILLISECONDS)
        .build()
    assertThat(cacheControl.maxAgeSeconds).isEqualTo(4)
  }
}
