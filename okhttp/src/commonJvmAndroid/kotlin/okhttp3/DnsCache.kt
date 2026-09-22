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
@file:OptIn(OkHttpInternalApi::class)
@file:Suppress("PropertyName") // Hide the '-delegate' field from Java language users.

package okhttp3

import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource
import okhttp3.internal.OkHttpInternalApi
import okhttp3.internal.concurrent.TaskRunner
import okhttp3.internal.dns.RealDnsCache

/**
 * An in-memory DNS query cache to save time and bandwidth.
 *
 * Cache Lifetime and Expiration
 * -----------------------------
 *
 * DNS resource records include a time to live (TTL) lifetime for returned records. This class
 * honors that setting, but with bounds:
 *
 *  * Records with a time to live lower than [minimumTimeToLive] will be used for
 *    [minimumTimeToLive]. The default value of this is 10 seconds.
 *
 *  * Records with a time to live greater than [maximumTimeToLive] will be used for
 *    [maximumTimeToLive]. The default value of this is 300 seconds (5 minutes).
 *
 * If there is a failure fetching DNS records, or if no DNS records are returned, this failure is
 * also cached for [failureTimeToLive]. The default value of this is 10 seconds.
 *
 * Revalidation
 * ------------
 *
 * If a cached record will expire soon, the cached record is returned immediately, but this cache
 * will also asynchronously update the value for the benefit of future requests.
 *
 * This way if a steady stream of requests are made for the same hostname, none of the requests will
 * have to wait for DNS.
 *
 * The default value of [revalidateBeforeExpire] is 2 seconds.
 *
 * Request Merging
 * ---------------
 *
 * If the cache receives multiple equivalent queries, it combines them into a single query on the
 * underlying transport.
 *
 * Memory Usage
 * ------------
 *
 * This cache retains [maxSize] entries. It prefers to evict expired entries, and when that is
 * insufficient, it evicts the least recently requested entries.
 *
 * The default [maxSize] is 1,000 entries. Most hostnames will require 3 entries for DNS record
 * types `A` (IPv4), `AAAA` (IPv6), and `HTTPS` (service metadata).
 *
 * Between evictions, the cache may grow to double this configured limit. Each entry consumes about
 * 400 bytes of memory. In total, the default cache will use about 800 KiB of memory when it is
 * full.
 */
class DnsCache internal constructor(
  @OkHttpInternalApi
  @JvmField
  internal val `-delegate`: RealDnsCache,
) {
  @get:JvmName("minimumTimeToLive")
  val minimumTimeToLive: Duration
    get() = `-delegate`.minimumTimeToLive

  @get:JvmName("maximumTimeToLive")
  val maximumTimeToLive: Duration
    get() = `-delegate`.maximumTimeToLive

  @get:JvmName("failureTimeToLive")
  val failureTimeToLive: Duration
    get() = `-delegate`.failureTimeToLive

  @get:JvmName("revalidateBeforeExpire")
  val revalidateBeforeExpire: Duration
    get() = `-delegate`.revalidateBeforeExpire

  @get:JvmName("maxSize")
  val maxSize: Int
    get() = `-delegate`.maxSize

  @get:JvmName("size")
  val size: Int
    get() = `-delegate`.size

  /** Returns the number of calls made to the DNS server. */
  @get:JvmName("networkCount")
  val networkCount: Int
    get() = `-delegate`.networkCount

  /** Returns the number of DNS calls served by the cache. */
  @get:JvmName("hitCount")
  val hitCount: Int
    get() = `-delegate`.hitCount

  /**
   * Returns the total number of DNS calls handled by this cache. This may be greater than the
   * sum of [networkCount] and [hitCount] because revalidation calls do both.
   */
  @get:JvmName("requestCount")
  val requestCount: Int
    get() = `-delegate`.requestCount

  constructor(
    minimumTimeToLive: Duration = 10.seconds,
    maximumTimeToLive: Duration = 300.seconds,
    failureTimeToLive: Duration = 10.seconds,
    revalidateBeforeExpire: Duration = 2.seconds,
    maxEntryCount: Int = 1000,
  ) : this(
    `-delegate` =
      RealDnsCache(
        taskRunner = TaskRunner.INSTANCE,
        timeSource = TimeSource.Monotonic,
        minimumTimeToLive = minimumTimeToLive,
        maximumTimeToLive = maximumTimeToLive,
        failureTimeToLive = failureTimeToLive,
        revalidateBeforeExpire = revalidateBeforeExpire,
        maxEntryCount = maxEntryCount,
      ),
  )

  /**
   * Deletes all values stored in the cache. In-flight writes to the cache will complete normally,
   * but the corresponding responses will not be stored.
   */
  fun evictAll() {
    `-delegate`.evictAll()
  }
}
