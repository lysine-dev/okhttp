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

package okhttp3.internal.dns

import app.cash.burst.Burst
import assertk.assertThat
import assertk.assertions.isEqualTo
import java.net.InetAddress
import kotlin.test.Test
import kotlin.time.Duration.Companion.seconds
import okhttp3.Dns
import okhttp3.Protocol
import okhttp3.internal.OkHttpInternalApi

@Burst
class StateMachineDnsCallTest {
  /** Arbitrary sample values. */
  private val blueIpv6s = listOf(InetAddress.getByName("1:2::3:4"))
  private val blueIpv4s = listOf(InetAddress.getByName("10.20.30.40"))
  private val greenIpv6s = listOf(InetAddress.getByName("5:6::7:8"))
  private val greenIpv4s = listOf(InetAddress.getByName("50.60.70.80"))

  @Test
  fun `happy path`(caching: Boolean = true) {
    testStateMachineDnsCall {
      val call =
        newCall(
          request = Dns.Request(hostname = "lysine.dev"),
          caching = caching,
        )
      call.enqueue()

      val query0 = queryFactory.takeQuery("lysine.dev", TYPE_HTTPS)
      val query1 = queryFactory.takeQuery("lysine.dev", TYPE_AAAA)
      val query2 = queryFactory.takeQuery("lysine.dev", TYPE_A)

      query1.respondIpAddresses(
        addresses = blueIpv6s,
      )
      call.takeOnRecordsIpAddresses(
        addresses = blueIpv6s,
      )

      query2.respondIpAddresses(
        addresses = blueIpv4s,
      )
      call.takeOnRecordsIpAddresses(
        addresses = blueIpv4s,
      )

      query0.respondServiceMetadata(
        alpnIds = listOf("h2"),
      )
      call.takeOnRecordsServiceMetadata(
        last = true,
        alpnIds = listOf(Protocol.HTTP_2),
      )
    }
  }

  @Test
  fun `caches are independent per hostname`() {
    testStateMachineDnsCall {
      val lysineCall0 =
        newCall(
          request = Dns.Request(hostname = "lysine.dev"),
          includeIPv6 = false,
          includeServiceMetadata = false,
          caching = true,
        )
      lysineCall0.enqueue()
      queryFactory.respondToQuery(
        hostname = "lysine.dev",
        type = TYPE_A,
        addresses = blueIpv4s,
      )
      assertThat(lysineCall0.takeAllRecords().addresses())
        .isEqualTo(blueIpv4s)

      val commonhausCall0 =
        newCall(
          request = Dns.Request(hostname = "commonhaus.org"),
          includeIPv6 = false,
          includeServiceMetadata = false,
          caching = true,
        )
      commonhausCall0.enqueue()
      queryFactory.respondToQuery(
        hostname = "commonhaus.org",
        type = TYPE_A,
        addresses = greenIpv4s,
      )
      assertThat(commonhausCall0.takeAllRecords().addresses())
        .isEqualTo(greenIpv4s)

      val lysineCall1 =
        newCall(
          request = Dns.Request(hostname = "lysine.dev"),
          includeIPv6 = false,
          includeServiceMetadata = false,
          caching = true,
        )
      lysineCall1.enqueue()
      assertThat(lysineCall1.takeAllRecords().addresses())
        .isEqualTo(blueIpv4s)

      val commonhausCall1 =
        newCall(
          request = Dns.Request(hostname = "commonhaus.org"),
          includeIPv6 = false,
          includeServiceMetadata = false,
          caching = true,
        )
      commonhausCall1.enqueue()
      assertThat(commonhausCall1.takeAllRecords().addresses())
        .isEqualTo(greenIpv4s)
    }
  }

  @Test
  fun `cache already completed values`() =
    testStateMachineDnsCall {
      val call0 =
        newCall(
          request = Dns.Request(hostname = "lysine.dev"),
          includeServiceMetadata = false,
          caching = true,
        )
      call0.enqueue()

      val call0QueryIpv6 = queryFactory.takeQuery("lysine.dev", TYPE_AAAA)
      val call0QueryIpv4 = queryFactory.takeQuery("lysine.dev", TYPE_A)

      call0QueryIpv6.respondIpAddresses(
        addresses = blueIpv6s,
      )
      call0QueryIpv4.respondIpAddresses(
        addresses = blueIpv4s,
      )
      assertThat(call0.takeAllRecords().addresses())
        .isEqualTo(blueIpv6s + blueIpv4s)

      val call1 =
        newCall(
          request = Dns.Request(hostname = "lysine.dev"),
          includeServiceMetadata = false,
          caching = true,
        )
      call1.enqueue()
      assertThat(call1.takeAllRecords().addresses())
        .isEqualTo(blueIpv6s + blueIpv4s)
    }

  @Test
  fun `server time to live is honored`() =
    testStateMachineDnsCall {
      val call0 =
        newCall(
          request = Dns.Request(hostname = "lysine.dev"),
          includeIPv6 = false,
          includeServiceMetadata = false,
          caching = true,
        )
      call0.enqueue()
      queryFactory.respondToQuery(
        hostname = "lysine.dev",
        type = TYPE_A,
        timeToLive = 30.seconds,
        addresses = blueIpv4s,
      )
      assertThat(call0.takeAllRecords().addresses())
        .isEqualTo(blueIpv4s)

      sleep(27.seconds)
      val call1 =
        newCall(
          request = Dns.Request(hostname = "lysine.dev"),
          includeIPv6 = false,
          includeServiceMetadata = false,
          caching = true,
        )
      call1.enqueue()
      assertThat(call1.takeAllRecords().addresses())
        .isEqualTo(blueIpv4s)

      // The blueIp4s response is expired after 30 seconds.
      sleep(3.seconds)
      val call2 =
        newCall(
          request = Dns.Request(hostname = "lysine.dev"),
          includeIPv6 = false,
          includeServiceMetadata = false,
          caching = true,
        )
      call2.enqueue()
      queryFactory.respondToQuery(
        hostname = "lysine.dev",
        type = TYPE_A,
        addresses = greenIpv4s,
      )
      assertThat(call2.takeAllRecords().addresses())
        .isEqualTo(greenIpv4s)
    }

  /**
   * We compute expiration time from when the request is made, not from when it is received. This is
   * the most conservative policy, but moderated by the minimum time to live configuration.
   */
  @Test
  fun `time to live is measured from call send time`() =
    testStateMachineDnsCall {
      val call0 =
        newCall(
          request = Dns.Request(hostname = "lysine.dev"),
          includeIPv6 = false,
          includeServiceMetadata = false,
          caching = true,
        )
      call0.enqueue()
      sleep(30.seconds)
      queryFactory.respondToQuery(
        hostname = "lysine.dev",
        type = TYPE_A,
        timeToLive = 30.seconds,
        addresses = blueIpv4s,
      )
      assertThat(call0.takeAllRecords().addresses())
        .isEqualTo(blueIpv4s)

      // The first call's cache is already expired because it took 30 seconds to be returned.
      val call1 =
        newCall(
          request = Dns.Request(hostname = "lysine.dev"),
          includeIPv6 = false,
          includeServiceMetadata = false,
          caching = true,
        )
      call1.enqueue()
      queryFactory.respondToQuery(
        hostname = "lysine.dev",
        type = TYPE_A,
        timeToLive = 30.seconds,
        addresses = greenIpv4s,
      )
      assertThat(call1.takeAllRecords().addresses())
        .isEqualTo(greenIpv4s)
    }

  @Test
  fun `server time to live is clamped to at least configured minimum`() =
    testStateMachineDnsCall {
      val call0 =
        newCall(
          request = Dns.Request(hostname = "lysine.dev"),
          includeIPv6 = false,
          includeServiceMetadata = false,
          caching = true,
        )
      call0.enqueue()
      queryFactory.respondToQuery(
        hostname = "lysine.dev",
        type = TYPE_A,
        timeToLive = 1.seconds,
        addresses = blueIpv4s,
      )
      assertThat(call0.takeAllRecords().addresses())
        .isEqualTo(blueIpv4s)

      // The test cache's configured minimum TTL is 10 seconds, so the first response is served.
      sleep(2.seconds)
      val call1 =
        newCall(
          request = Dns.Request(hostname = "lysine.dev"),
          includeIPv6 = false,
          includeServiceMetadata = false,
          caching = true,
        )
      call1.enqueue()
      assertThat(call1.takeAllRecords().addresses())
        .isEqualTo(blueIpv4s)
    }

  @Test
  fun `server time to live is clamped to at most configured maximum`() =
    testStateMachineDnsCall {
      val call0 =
        newCall(
          request = Dns.Request(hostname = "lysine.dev"),
          includeIPv6 = false,
          includeServiceMetadata = false,
          caching = true,
        )
      call0.enqueue()
      queryFactory.respondToQuery(
        hostname = "lysine.dev",
        type = TYPE_A,
        timeToLive = 100.seconds,
        addresses = blueIpv4s,
      )
      assertThat(call0.takeAllRecords().addresses())
        .isEqualTo(blueIpv4s)

      // The test cache's configured maximum TTL is 60 seconds, so the first response is not served.
      sleep(62.seconds)
      val call1 =
        newCall(
          request = Dns.Request(hostname = "lysine.dev"),
          includeIPv6 = false,
          includeServiceMetadata = false,
          caching = true,
        )
      call1.enqueue()
      queryFactory.respondToQuery(
        hostname = "lysine.dev",
        type = TYPE_A,
        timeToLive = 1.seconds,
        addresses = greenIpv4s,
      )
      assertThat(call1.takeAllRecords().addresses())
        .isEqualTo(greenIpv4s)
    }

  /** Confirm that two queries to the cache yield a single query to the underlying transport. */
  @Test
  fun `cache in flight calls`() =
    testStateMachineDnsCall {
      val call0 =
        newCall(
          request = Dns.Request(hostname = "lysine.dev"),
          includeServiceMetadata = false,
          caching = true,
        )
      call0.enqueue()

      val call0QueryIpv6 = queryFactory.takeQuery("lysine.dev", TYPE_AAAA)
      val call0QueryIpv4 = queryFactory.takeQuery("lysine.dev", TYPE_A)

      val call1 =
        newCall(
          request = Dns.Request(hostname = "lysine.dev"),
          includeServiceMetadata = false,
          caching = true,
        )
      call1.enqueue()

      call0QueryIpv6.respondIpAddresses(
        addresses = blueIpv6s,
      )
      call0.takeOnRecordsIpAddresses(
        addresses = blueIpv6s,
      )
      call1.takeOnRecordsIpAddresses(
        addresses = blueIpv6s,
      )

      call0QueryIpv4.respondIpAddresses(
        addresses = blueIpv4s,
      )
      call0.takeOnRecordsIpAddresses(
        last = true,
        addresses = blueIpv4s,
      )
      call1.takeOnRecordsIpAddresses(
        last = true,
        addresses = blueIpv4s,
      )
    }

  @Test
  fun `cache revalidate returns cached result and also makes request`() =
    testStateMachineDnsCall {
      // Seed the cache.
      val call0 =
        newCall(
          request = Dns.Request(hostname = "lysine.dev"),
          includeServiceMetadata = false,
          caching = true,
        )
      call0.enqueue()

      queryFactory.respondToQuery(
        hostname = "lysine.dev",
        type = TYPE_AAAA,
        timeToLive = 10.seconds,
        addresses = blueIpv6s,
      )
      queryFactory.respondToQuery(
        hostname = "lysine.dev",
        type = TYPE_A,
        timeToLive = 10.seconds,
        addresses = blueIpv4s,
      )
      assertThat(call0.takeAllRecords().addresses())
        .isEqualTo(blueIpv6s + blueIpv4s)

      // After 8 seconds, the cached response is returned immediately and a revalidating call is
      // also made. (10 seconds minus 2 seconds for revalidateBeforeExpire.)
      sleep(8.seconds)
      val call1 =
        newCall(
          request = Dns.Request(hostname = "lysine.dev"),
          includeServiceMetadata = false,
          caching = true,
        )
      call1.enqueue()
      assertThat(call1.takeAllRecords().addresses())
        .isEqualTo(blueIpv6s + blueIpv4s)
      queryFactory.respondToQuery(
        hostname = "lysine.dev",
        type = TYPE_AAAA,
        addresses = greenIpv6s,
      )
      queryFactory.respondToQuery(
        hostname = "lysine.dev",
        type = TYPE_A,
        addresses = greenIpv4s,
      )

      // After the revalidating queries return, new queries return that data immediately.
      val call2 =
        newCall(
          request = Dns.Request(hostname = "lysine.dev"),
          includeServiceMetadata = false,
          caching = true,
        )
      call2.enqueue()
      assertThat(call2.takeAllRecords().addresses())
        .isEqualTo(greenIpv6s + greenIpv4s)
    }

  @Test
  fun `new call joins incomplete revalidate call`() =
    testStateMachineDnsCall {
      // Seed the cache.
      val call0 =
        newCall(
          request = Dns.Request(hostname = "lysine.dev"),
          includeServiceMetadata = false,
          caching = true,
        )
      call0.enqueue()

      queryFactory.respondToQuery(
        hostname = "lysine.dev",
        type = TYPE_AAAA,
        timeToLive = 10.seconds,
        addresses = blueIpv6s,
      )
      queryFactory.respondToQuery(
        hostname = "lysine.dev",
        type = TYPE_A,
        timeToLive = 10.seconds,
        addresses = blueIpv4s,
      )
      assertThat(call0.takeAllRecords().addresses())
        .isEqualTo(blueIpv6s + blueIpv4s)

      // After 8 seconds, the cached response is returned immediately and a revalidating call is
      // also made. (10 seconds minus 2 seconds for revalidateBeforeExpire.)
      sleep(8.seconds)
      val call1 =
        newCall(
          request = Dns.Request(hostname = "lysine.dev"),
          includeServiceMetadata = false,
          caching = true,
        )
      call1.enqueue()
      assertThat(call1.takeAllRecords().addresses())
        .isEqualTo(blueIpv6s + blueIpv4s)
      // Note this doesn't respond to TYPE_AAAA yet.
      val revalidateQuery0 =
        queryFactory.takeQuery(
          hostname = "lysine.dev",
          type = TYPE_AAAA,
        )
      val revalidateQuery1 =
        queryFactory.takeQuery(
          hostname = "lysine.dev",
          type = TYPE_A,
        )
      revalidateQuery1.respondIpAddresses(addresses = greenIpv4s)

      // A later query can use the revalidated IPv4 records, but must wait for the revalidated
      // IPv6 records.
      sleep(2.seconds)
      val call2 =
        newCall(
          request = Dns.Request(hostname = "lysine.dev"),
          includeServiceMetadata = false,
          caching = true,
        )
      call2.enqueue()
      call2.takeOnRecordsIpAddresses(
        addresses = greenIpv4s,
      )
      revalidateQuery0.respondIpAddresses(
        addresses = greenIpv6s,
      )
      call2.takeOnRecordsIpAddresses(
        last = true,
        addresses = greenIpv6s,
      )
    }

  @Test
  fun `failure returned last`(caching: Boolean = true) =
    testStateMachineDnsCall {
      val call =
        newCall(
          request = Dns.Request(hostname = "lysine.dev"),
          caching = caching,
        )
      call.enqueue()

      val query0 = queryFactory.takeQuery("lysine.dev", TYPE_HTTPS)
      val query1 = queryFactory.takeQuery("lysine.dev", TYPE_AAAA)
      val query2 = queryFactory.takeQuery("lysine.dev", TYPE_A)

      query1.respondFailure("boom!")

      query2.respondIpAddresses(
        addresses = blueIpv4s,
      )
      call.takeOnRecordsIpAddresses(
        addresses = blueIpv4s,
      )

      query0.respondServiceMetadata(
        alpnIds = listOf("h2"),
      )
      call.takeOnRecordsServiceMetadata(
        alpnIds = listOf(Protocol.HTTP_2),
      )

      call.takeOnFailure("boom!")
    }

  @Test
  fun `partial failure is cached`() =
    testStateMachineDnsCall {
      val call0 =
        newCall(
          request = Dns.Request(hostname = "lysine.dev"),
          caching = true,
          includeServiceMetadata = false,
        )
      call0.enqueue()

      val queryIpv6 = queryFactory.takeQuery("lysine.dev", TYPE_AAAA)
      val queryIpv4 = queryFactory.takeQuery("lysine.dev", TYPE_A)

      queryIpv6.respondFailure("boom!")
      queryIpv4.respondIpAddresses(
        addresses = blueIpv4s,
      )

      call0.takeOnRecordsIpAddresses(
        addresses = blueIpv4s,
      )
      call0.takeOnFailure("boom!")

      val call1 =
        newCall(
          request = Dns.Request(hostname = "lysine.dev"),
          caching = true,
          includeServiceMetadata = false,
        )
      call1.enqueue()

      call1.takeOnRecordsIpAddresses(
        addresses = blueIpv4s,
      )
      call1.takeOnFailure("boom!")
    }

  @Test
  fun `failure expires`() =
    testStateMachineDnsCall {
      val call0 =
        newCall(
          request = Dns.Request(hostname = "lysine.dev"),
          caching = true,
          includeIPv6 = false,
          includeServiceMetadata = false,
        )
      call0.enqueue()
      queryFactory
        .takeQuery("lysine.dev", TYPE_A)
        .respondFailure("boom!")
      call0.takeOnFailure("boom!")

      // The test cache expires failures after 5 seconds.
      sleep(5.seconds)
      val call1 =
        newCall(
          request = Dns.Request(hostname = "lysine.dev"),
          caching = true,
          includeIPv6 = false,
          includeServiceMetadata = false,
        )
      call1.enqueue()
      queryFactory.respondToQuery(
        hostname = "lysine.dev",
        type = TYPE_A,
        addresses = greenIpv4s,
      )
      assertThat(call1.takeAllRecords().addresses())
        .isEqualTo(greenIpv4s)
    }

  @Test
  fun `failure is revalidated`() =
    testStateMachineDnsCall {
      val call0 =
        newCall(
          request = Dns.Request(hostname = "lysine.dev"),
          caching = true,
          includeIPv6 = false,
          includeServiceMetadata = false,
        )
      call0.enqueue()
      queryFactory
        .takeQuery("lysine.dev", TYPE_A)
        .respondFailure("boom!")
      call0.takeOnFailure("boom!")

      // The failure expires after 5 seconds, but we start revalidating it 2 seconds before that.
      sleep(3.seconds)
      val call1 =
        newCall(
          request = Dns.Request(hostname = "lysine.dev"),
          caching = true,
          includeIPv6 = false,
          includeServiceMetadata = false,
        )
      call1.enqueue()
      call1.takeOnFailure("boom!")
      queryFactory.respondToQuery(
        hostname = "lysine.dev",
        type = TYPE_A,
        addresses = blueIpv4s,
      )

      // After the revalidation returns, that result is used.
      val call2 =
        newCall(
          request = Dns.Request(hostname = "lysine.dev"),
          caching = true,
          includeIPv6 = false,
          includeServiceMetadata = false,
        )
      call2.enqueue()
      assertThat(call2.takeAllRecords().addresses())
        .isEqualTo(blueIpv4s)
    }

  /**
   * Confirm that the state machine calls doesn't call any [Dns.Callback] methods until the previous
   * call to a [Dns.Callback] method has returned.
   *
   * Usually this will be a concurrency problem, but we can exercise it just as well by making a
   * re-entrant call on a single thread.
   */
  @Test
  fun `calls to onRecords are serialized`(caching: Boolean = true) =
    testStateMachineDnsCall {
      val call =
        newCall(
          request = Dns.Request(hostname = "lysine.dev"),
          caching = caching,
        )
      call.enqueue()

      val query0 = queryFactory.takeQuery("lysine.dev", TYPE_HTTPS)
      val query1 = queryFactory.takeQuery("lysine.dev", TYPE_AAAA)
      val query2 = queryFactory.takeQuery("lysine.dev", TYPE_A)

      onNextEvent = {
        query2.respondIpAddresses(
          addresses = blueIpv4s,
        )
        query1.respondIpAddresses(
          addresses = blueIpv6s,
        )
      }
      query0.respondServiceMetadata(
        alpnIds = listOf("h2"),
      )
      call.takeOnRecordsServiceMetadata(
        alpnIds = listOf(Protocol.HTTP_2),
      )
      call.takeOnRecordsIpAddresses(
        last = true,
        addresses =
          listOf(
            InetAddress.getByName("10.20.30.40"),
            InetAddress.getByName("1:2::3:4"),
          ),
      )
    }

  /**
   * The implementation still enqueues canceled queries, because that's an easy way to jump to a
   * dispatcher thread to post the failures back to the callback.
   */
  @Test
  fun `cancel before enqueue`() =
    testStateMachineDnsCall {
      val call =
        newCall(
          request = Dns.Request(hostname = "lysine.dev"),
          caching = false,
        )
      call.cancel()
      call.enqueue()

      queryFactory.takeCancel("lysine.dev", TYPE_HTTPS)
      val query0 = queryFactory.takeQuery("lysine.dev", TYPE_HTTPS)
      queryFactory.takeCancel("lysine.dev", TYPE_AAAA)
      val query1 = queryFactory.takeQuery("lysine.dev", TYPE_AAAA)
      queryFactory.takeCancel("lysine.dev", TYPE_A)
      val query2 = queryFactory.takeQuery("lysine.dev", TYPE_A)

      query0.respondFailure("canceled")
      query1.respondFailure("canceled")
      query2.respondFailure("canceled")

      call.takeOnFailure("canceled")
    }

  @Test
  fun `cancel before enqueue with caching`() =
    testStateMachineDnsCall {
      val call =
        newCall(
          request = Dns.Request(hostname = "lysine.dev"),
          caching = true,
        )
      call.cancel()
      call.enqueue()

      val query0 = queryFactory.takeQuery("lysine.dev", TYPE_HTTPS)
      val query1 = queryFactory.takeQuery("lysine.dev", TYPE_AAAA)
      val query2 = queryFactory.takeQuery("lysine.dev", TYPE_A)

      query0.respondFailure("canceled")
      query1.respondFailure("canceled")
      query2.respondFailure("canceled")

      call.takeOnFailure("canceled")
    }

  /** Cancels are asynchronous and if the canceled query completes anyway, that's fine. */
  @Test
  fun `cancel ignored if canceled query completes`() =
    testStateMachineDnsCall {
      val call =
        newCall(
          request = Dns.Request(hostname = "lysine.dev"),
          caching = false,
        )
      call.enqueue()

      val query0 = queryFactory.takeQuery("lysine.dev", TYPE_HTTPS)
      val query1 = queryFactory.takeQuery("lysine.dev", TYPE_AAAA)
      val query2 = queryFactory.takeQuery("lysine.dev", TYPE_A)

      query1.respondIpAddresses(
        addresses = blueIpv6s,
      )
      call.takeOnRecordsIpAddresses(
        addresses = blueIpv6s,
      )

      call.cancel()

      queryFactory.takeCancel("lysine.dev", TYPE_HTTPS)
      queryFactory.takeCancel("lysine.dev", TYPE_A)

      query2.respondIpAddresses(
        addresses = blueIpv4s,
      )
      call.takeOnRecordsIpAddresses(
        addresses = blueIpv4s,
      )

      query0.respondServiceMetadata(
        alpnIds = listOf("h2"),
      )
      call.takeOnRecordsServiceMetadata(
        last = true,
        alpnIds = listOf(Protocol.HTTP_2),
      )
    }

  /** When caching, cancels aren't applied to the transport. */
  @Test
  fun `cancel ignored if canceled query completes with caching`() =
    testStateMachineDnsCall {
      val call =
        newCall(
          request = Dns.Request(hostname = "lysine.dev"),
          caching = true,
        )
      call.enqueue()

      val query0 = queryFactory.takeQuery("lysine.dev", TYPE_HTTPS)
      val query1 = queryFactory.takeQuery("lysine.dev", TYPE_AAAA)
      val query2 = queryFactory.takeQuery("lysine.dev", TYPE_A)

      query1.respondIpAddresses(
        addresses = blueIpv6s,
      )
      call.takeOnRecordsIpAddresses(
        addresses = blueIpv6s,
      )

      call.cancel()

      query2.respondIpAddresses(
        addresses = blueIpv4s,
      )

      query0.respondServiceMetadata(
        alpnIds = listOf("h2"),
      )
      call.takeOnFailure("canceled")
    }
}
