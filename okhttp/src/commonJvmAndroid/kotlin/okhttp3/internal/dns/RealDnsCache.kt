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
@file:Suppress("ktlint:standard:filename")

package okhttp3.internal.dns

import java.io.IOException
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.time.ComparableTimeMark as Time
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.ExperimentalTime
import kotlin.time.TimeSource
import okhttp3.internal.OkHttpInternalApi
import okhttp3.internal.concurrent.TaskRunner

@OkHttpInternalApi
@OptIn(ExperimentalTime::class) // We know Clock and Instant will be stable in Kotlin 2.3.
class RealDnsCache(
  private val taskRunner: TaskRunner,
  timeSource: TimeSource.WithComparableMarks,
  internal val minimumTimeToLive: Duration,
  internal val maximumTimeToLive: Duration,
  internal val failureTimeToLive: Duration,
  internal val revalidateBeforeExpire: Duration,
  maxEntryCount: Int,
) {
  private val cache =
    object : MemoryCache<Question, Entry>(
      timeSource = timeSource,
      maxSize = maxEntryCount,
    ) {
      override fun lastRequestedAt(
        now: Time,
        value: Entry,
      ): Time? {
        val state = value.state.get()

        // If it's already expired, evict immediately.
        if (state.inFlightCall == null) {
          val expireAt = state.result?.expireAt ?: return null
          if (expireAt <= now) return null
        }

        return state.lastRequestedAt
      }
    }

  private val atomicNetworkCount = AtomicInteger()
  private val atomicHitCount = AtomicInteger()
  private val atomicRequestCount = AtomicInteger()

  internal val size: Int
    get() = cache.size
  internal val maxSize: Int
    get() = cache.maxSize
  internal val networkCount: Int
    get() = atomicNetworkCount.get()
  internal val hitCount: Int
    get() = atomicHitCount.get()
  internal val requestCount: Int
    get() = atomicRequestCount.get()

  init {
    require(failureTimeToLive >= 0.seconds)
    require(minimumTimeToLive >= 0.seconds)
    require(maximumTimeToLive >= minimumTimeToLive)
    require(revalidateBeforeExpire >= 0.seconds)
  }

  fun evictAll() {
    cache.evictAll()
  }

  fun wrap(delegate: DnsQuery.Factory) =
    DnsQuery.Factory { question ->
      val entry = cache.computeIfAbsent(question) { Entry() }
      CacheQuery(question, delegate, entry)
    }

  /**
   * An application-layer DNS query that is served by cached data in [entry] or by a new call to the
   * underlying transport via [delegate]. If a new call is made, its result is stored in [entry].
   */
  private inner class CacheQuery(
    val question: Question,
    val delegate: DnsQuery.Factory,
    val entry: Entry,
  ) : DnsQuery,
    DnsQuery.Callback {
    var callback: DnsQuery.Callback? = null

    override fun enqueue(callback: DnsQuery.Callback) {
      check(this.callback == null) { "already enqueued" }
      this.callback = callback

      val now = cache.timeSource.markNow()
      while (true) {
        val previous = entry.state.get()
        val result = previous.result
        val inFlightCall = previous.inFlightCall

        // We use a cached value unless it's expired.
        val useCached = result != null && now < result.expireAt

        // Revalidate the cache if necessary. Note that we might revalidate the cache without any
        // particular callback waiting for that response.
        val next =
          previous.copy(
            lastRequestedAt = now,
            inFlightCall =
              when {
                inFlightCall != null && useCached -> {
                  inFlightCall
                }

                inFlightCall != null -> {
                  inFlightCall.copy(queries = inFlightCall.queries + this)
                }

                result == null || now >= result.revalidateAt -> {
                  InFlightCall(
                    query = delegate.newQuery(question),
                    sentAt = now,
                    queries = if (useCached) listOf() else listOf(this),
                  )
                }

                else -> {
                  null
                }
              },
          )

        if (!entry.state.compareAndSet(previous, next)) continue // Lost a race, retry.

        atomicRequestCount.incrementAndGet()

        if (inFlightCall == null && next.inFlightCall != null) {
          atomicNetworkCount.incrementAndGet()
          next.inFlightCall.query.enqueue(this)
        }

        if (useCached) {
          atomicHitCount.incrementAndGet()
          taskRunner.newQueue().execute("${question.name} dns") {
            when (result) {
              is Result.Success -> callback.onResponse(result.message)
              is Result.Failure -> callback.onFailure(result.exception)
            }
          }
        }

        return
      }
    }

    /**
     * Note that we don't cancel the query even if nothing is waiting on it. We assume there's still
     * value in updating the cache!
     */
    override fun cancel() {
      while (true) {
        val previous = entry.state.get()
        val inFlightCall = previous.inFlightCall ?: return

        // If we've already called the callback, there's nothing to do.
        val newQueries = inFlightCall.queries - this
        if (newQueries.size == inFlightCall.queries.size) return

        val next =
          previous.copy(
            inFlightCall =
              inFlightCall.copy(
                queries = newQueries,
              ),
          )

        if (!entry.state.compareAndSet(previous, next)) continue // Lost a race, retry.

        taskRunner.newQueue().execute("${question.name} dns") {
          callback!!.onFailure(IOException("canceled"))
        }

        return
      }
    }

    override fun onResponse(dnsResponse: DnsMessage) {
      while (true) {
        val previous = entry.state.get()
        val sentAt = previous.inFlightCall!!.sentAt
        val timeToLive =
          when {
            dnsResponse.answers.isEmpty() -> {
              failureTimeToLive
            }

            else -> {
              dnsResponse.answers
                .minOf { it.timeToLive }
                .seconds
                .coerceIn(minimumTimeToLive, maximumTimeToLive)
            }
          }
        val revalidateDelay = (timeToLive - revalidateBeforeExpire).coerceAtLeast(0.seconds)

        val next =
          previous.copy(
            inFlightCall = null,
            result =
              Result.Success(
                message = dnsResponse,
                revalidateAt = sentAt + revalidateDelay,
                expireAt = sentAt + timeToLive,
              ),
          )

        if (!entry.state.compareAndSet(previous, next)) continue // Lost a race, retry.

        val queries = previous.inFlightCall.queries
        for (query in queries) {
          query.callback!!.onResponse(dnsResponse)
        }

        return
      }
    }

    override fun onFailure(e: IOException) {
      while (true) {
        val previous = entry.state.get()
        val sentAt = previous.inFlightCall!!.sentAt
        val revalidateDelay = (failureTimeToLive - revalidateBeforeExpire).coerceAtLeast(0.seconds)

        val next =
          previous.copy(
            inFlightCall = null,
            result =
              Result.Failure(
                exception = e,
                revalidateAt = sentAt + revalidateDelay,
                expireAt = sentAt + failureTimeToLive,
              ),
          )

        if (!entry.state.compareAndSet(previous, next)) continue // Lost a race, retry.

        val queries = previous.inFlightCall.queries
        for (query in queries) {
          query.callback!!.onFailure(e)
        }

        return
      }
    }
  }

  private class Entry {
    val state = AtomicReference(State())
  }

  /** A snapshot of the state of a single entry. */
  private data class State(
    val lastRequestedAt: Time? = null,
    val inFlightCall: InFlightCall? = null,
    val result: Result? = null,
  )

  /** A call to the underlying transport. */
  private data class InFlightCall(
    val query: DnsQuery,
    val sentAt: Time,
    /** The possibly-empty set of queries to notify when this call is complete. */
    val queries: List<CacheQuery>,
  )

  /** A cached result. */
  private sealed interface Result {
    val revalidateAt: Time
    val expireAt: Time

    class Failure(
      override val revalidateAt: Time,
      override val expireAt: Time,
      val exception: IOException,
    ) : Result

    class Success(
      override val revalidateAt: Time,
      override val expireAt: Time,
      val message: DnsMessage,
    ) : Result
  }
}
