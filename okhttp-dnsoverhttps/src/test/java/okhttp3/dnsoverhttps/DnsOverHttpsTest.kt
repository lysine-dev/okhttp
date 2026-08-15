/*
 * Copyright (C) 2018 Square, Inc.
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
package okhttp3.dnsoverhttps

import app.cash.burst.Burst
import assertk.assertThat
import assertk.assertions.containsExactly
import assertk.assertions.hasMessage
import assertk.assertions.hasSize
import assertk.assertions.isEqualTo
import assertk.assertions.isGreaterThan
import assertk.assertions.isInstanceOf
import assertk.assertions.isNotNull
import assertk.assertions.isNull
import assertk.assertions.isTrue
import java.io.EOFException
import java.io.File
import java.net.InetAddress
import java.net.UnknownHostException
import java.util.concurrent.CompletableFuture
import java.util.concurrent.LinkedBlockingDeque
import java.util.concurrent.SynchronousQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import kotlin.reflect.KClass
import kotlin.test.assertFailsWith
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.junit5.StartStop
import okhttp3.Cache
import okhttp3.CallEvent
import okhttp3.CallEvent.CacheHit
import okhttp3.CallEvent.CacheMiss
import okhttp3.Dispatcher
import okhttp3.Dns
import okhttp3.DnsCache
import okhttp3.EventRecorder
import okhttp3.FakeDns
import okhttp3.FakeDns.Request.DnsRequest
import okhttp3.Headers.Companion.headersOf
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.internal.dns.CLASS_IN
import okhttp3.internal.dns.DnsEvent
import okhttp3.internal.dns.DnsMessage
import okhttp3.internal.dns.EntryPoint
import okhttp3.internal.dns.Question
import okhttp3.internal.dns.ResourceRecord
import okhttp3.internal.dns.TYPE_A
import okhttp3.internal.dns.TYPE_AAAA
import okhttp3.internal.dns.invoke
import okhttp3.internal.dns.toEventsQueue
import okhttp3.testing.PlatformRule
import okio.Buffer
import okio.ByteString.Companion.decodeHex
import okio.ByteString.Companion.encodeUtf8
import okio.IOException
import okio.Path.Companion.toPath
import okio.fakefilesystem.FakeFileSystem
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.RegisterExtension

/**
 * This test takes advantage of the fact that the new DNS API always requests records in this order:
 *
 *  * [TYPE_HTTPS]
 *  * [TYPE_AAAA]
 *  * [TYPE_A]
 */
@Tag("Slowish")
@Burst
class DnsOverHttpsTest(
  private val entryPoint: EntryPoint = EntryPoint.NewCall,
) {
  @RegisterExtension
  val platform = PlatformRule()

  private val server = FakeDns()

  @StartStop
  private val mockWebServer =
    MockWebServer()
      .apply {
        dispatcher = server.dispatcher
      }

  private val completedRunnables = LinkedBlockingDeque<Runnable>()
  private val executorService =
    object : ThreadPoolExecutor(
      0,
      Int.MAX_VALUE,
      1,
      TimeUnit.SECONDS,
      SynchronousQueue(),
    ) {
      override fun afterExecute(
        r: Runnable,
        t: Throwable?,
      ) {
        completedRunnables.add(r)
      }
    }

  private lateinit var dns: DnsOverHttps
  private val dispatcher =
    Dispatcher(executorService)
      .apply {
        // Force calls to run sequentially for determinism.
        this.maxRequestsPerHost = 1
      }
  private val cacheFs = FakeFileSystem()
  private val eventRecorder = EventRecorder()
  private val bootstrapClient =
    OkHttpClient
      .Builder()
      .protocols(listOf(Protocol.HTTP_2, Protocol.HTTP_1_1))
      .dispatcher(dispatcher)
      .eventListener(eventRecorder.eventListener)
      .addInterceptor(Interceptor { chain -> interceptor.intercept(chain) })
      .build()

  private var interceptor =
    Interceptor { chain ->
      chain.proceed(chain.request())
    }

  @BeforeEach
  fun setUp() {
    mockWebServer.protocols = bootstrapClient.protocols
    dns = buildLocalhost(bootstrapClient)
  }

  @Test
  fun getOne() {
    server["lysine.dev"] = listOf(InetAddress.getByName("10.20.30.40"))
    val result = dns.invoke(entryPoint, "lysine.dev")
    assertThat(result).isEqualTo(listOf(address("10.20.30.40")))
    val (dnsRequest, httpsRequest) = server.takeRequest() as DnsRequest
    assertThat(httpsRequest!!.method).isEqualTo("GET")
    assertThat(dnsRequest)
      .isEqualTo(queryRequest("lysine.dev", TYPE_A))
  }

  @Test
  fun getWithCache() {
    val dnsCache = DnsCache()
    dns = buildLocalhost(bootstrapClient, dnsCache = dnsCache)

    // Put a sample record in for the first query.
    server["lysine.dev"] = listOf(InetAddress.getByName("10.20.30.40"))
    val result0 = dns.invoke(entryPoint, "lysine.dev")
    assertThat(result0).isEqualTo(listOf(address("10.20.30.40")))
    val (dnsRequest, httpsRequest) = server.takeRequest() as DnsRequest
    assertThat(httpsRequest!!.method).isEqualTo("GET")
    assertThat(dnsRequest)
      .isEqualTo(queryRequest("lysine.dev", TYPE_A))

    // Put a different record in for the second query. We'll get the first record because that's
    // what is in the cache.
    server["lysine.dev"] = listOf(InetAddress.getByName("55.66.77.88"))
    val result1 = dns.invoke(entryPoint, "lysine.dev")
    assertThat(result1).isEqualTo(listOf(address("10.20.30.40"))) // Stale! Cache worked!
    assertThat(server.pollRequest()).isNull() // No request.
  }

  @Test
  fun getIpv6() {
    server["lysine.dev"] =
      listOf(
        InetAddress.getByName("10.20.30.40"),
        InetAddress.getByName("1:2::3:4"),
      )
    dns = buildLocalhost(bootstrapClient, includeIPv6 = true)
    val result = dns(entryPoint, "lysine.dev")
    assertThat(result).containsExactly(
      address("1:2::3:4"),
      address("10.20.30.40"),
    )

    val (dnsRequest1, httpsRequest1) = server.takeRequest() as DnsRequest
    assertThat(httpsRequest1!!.method).isEqualTo("GET")
    assertThat(dnsRequest1).isEqualTo(queryRequest("lysine.dev", TYPE_AAAA))

    val (dnsRequest2, httpsRequest2) = server.takeRequest() as DnsRequest
    assertThat(httpsRequest2!!.method).isEqualTo("GET")
    assertThat(dnsRequest2).isEqualTo(queryRequest("lysine.dev", TYPE_A))
  }

  @Test
  fun lookupDoesNotRequestServiceMetadata() {
    assumeTrue(entryPoint == EntryPoint.Lookup)

    server["lysine.dev"] =
      listOf(
        InetAddress.getByName("10.20.30.40"),
        InetAddress.getByName("1:2::3:4"),
      )
    dns = buildLocalhost(bootstrapClient, includeIPv6 = true, includeServiceMetadata = true)
    val result = dns(entryPoint, "lysine.dev")
    assertThat(result).containsExactly(
      address("1:2::3:4"),
      address("10.20.30.40"),
    )

    val (dnsRequest1, _) = server.takeRequest() as DnsRequest
    assertThat(dnsRequest1).isEqualTo(queryRequest("lysine.dev", TYPE_AAAA))

    val (dnsRequest2, _) = server.takeRequest() as DnsRequest
    assertThat(dnsRequest2).isEqualTo(queryRequest("lysine.dev", TYPE_A))

    assertThat(server.pollRequest()).isNull()
  }

  @Test
  fun failsBecauseNoRecords() {
    assertFailsWith<UnknownHostException> {
      dns(entryPoint, "lysine.dev")
    }
    val (dnsRequest, httpsRequest) = server.takeRequest() as DnsRequest
    assertThat(httpsRequest!!.method).isEqualTo("GET")
    assertThat(dnsRequest)
      .isEqualTo(queryRequest("lysine.dev", TYPE_A))
  }

  @Test
  fun lookupReturnsNormallyIfIpv4FailsAndIpv6Succeeds() {
    assumeTrue(entryPoint == EntryPoint.Lookup)

    dns = buildLocalhost(bootstrapClient, includeIPv6 = true)
    server["lysine.dev"] = listOf(InetAddress.getByName("11:22::33:44"))
    server.sequenceIndexToOverride[1] = overrideResponse("")

    val results = dns(entryPoint, "lysine.dev")
    assertThat(results).containsExactly(InetAddress.getByName("11:22::33:44"))
  }

  @Test
  fun lookupReturnsNormallyIfIpv6FailsAndIpv6Succeeds() {
    assumeTrue(entryPoint == EntryPoint.Lookup)

    dns = buildLocalhost(bootstrapClient, includeIPv6 = true)
    server["lysine.dev"] = listOf(InetAddress.getByName("10.20.30.40"))
    server.sequenceIndexToOverride[0] = overrideResponse("")

    val results = dns(entryPoint, "lysine.dev")
    assertThat(results).containsExactly(InetAddress.getByName("10.20.30.40"))
  }

  @Test
  fun resolveForbiddenPrivateDomain() {
    dns = buildLocalhost(bootstrapClient, resolvePrivateAddresses = false)

    val e =
      assertFailsWith<UnknownHostException> {
        dns(entryPoint, "dev")
      }
    assertThat(e).hasMessage("private hosts not resolved")
    assertThat(server.pollRequest()).isNull()
  }

  @Test
  fun resolveForbiddenPublicDomain() {
    dns = buildLocalhost(bootstrapClient, resolvePublicAddresses = false)

    val e =
      assertFailsWith<UnknownHostException> {
        dns(entryPoint, "lysine.dev")
      }
    assertThat(e).hasMessage("public hosts not resolved")
    assertThat(server.pollRequest()).isNull()
  }

  @Test
  fun failOnExcessiveResponse() {
    val array = CharArray(128 * 1024 + 2) { '0' }
    server.sequenceIndexToOverride[0] = overrideResponse(String(array))
    val e =
      assertFailsWith<IOException> {
        dns(entryPoint, "lysine.dev")
      }
    val rootCause = e.cause ?: e
    assertThat(rootCause).hasMessage("response size exceeds limit (65536 bytes): 65537 bytes")
  }

  @Test
  fun failOnBadResponse() {
    server.sequenceIndexToOverride[0] = overrideResponse("00")
    val e =
      assertFailsWith<IOException> {
        dns(entryPoint, "lysine.dev")
      }
    val rootCause = e.cause ?: e
    assertThat(rootCause).isInstanceOf<EOFException>()
  }

  // TODO GET preferred order - with tests to confirm this
  // 1. successful fresh cached GET response
  // 2. unsuccessful (404, 500) fresh cached GET response
  // 3. successful network response
  // 4. successful stale cached GET response
  // 5. unsuccessful response
  @Test
  fun usesHttpCache() {
    val cache = Cache(cacheFs, "cache".toPath(), (100 * 1024).toLong())
    val cachedClient = bootstrapClient.newBuilder().cache(cache).build()
    val cachedDns =
      buildLocalhost(
        bootstrapClient = cachedClient,
        dnsCache = DnsCache(maxEntryCount = 0),
      )

    server.extraHeaders =
      headersOf(
        "cache-control",
        "private, max-age=298",
      )
    server["lysine.dev"] = listOf(InetAddress.getByName("10.20.30.40"))
    server["alternate.lysine.dev"] = listOf(InetAddress.getByName("55.66.77.88"))

    val result1 = cachedDns(entryPoint, "lysine.dev")
    assertThat(result1).containsExactly(address("10.20.30.40"))
    val (dnsRequest1, httpsRequest1) = server.takeRequest() as DnsRequest
    assertThat(httpsRequest1!!.method).isEqualTo("GET")
    assertThat(dnsRequest1)
      .isEqualTo(queryRequest("lysine.dev", TYPE_A))

    assertThat(cacheEvents()).containsExactly(CacheMiss::class)

    val result2 = cachedDns(entryPoint, "lysine.dev")
    assertThat(server.pollRequest()).isNull()
    assertThat(result2).isEqualTo(listOf(address("10.20.30.40")))

    assertThat(cacheEvents()).containsExactly(CacheHit::class)

    val result3 = cachedDns(entryPoint, "alternate.lysine.dev")
    assertThat(result3).containsExactly(address("55.66.77.88"))
    val (dnsRequest2, httpsRequest2) = server.takeRequest() as DnsRequest
    assertThat(httpsRequest2!!.method).isEqualTo("GET")
    assertThat(dnsRequest2)
      .isEqualTo(queryRequest("alternate.lysine.dev", TYPE_A))

    assertThat(cacheEvents()).containsExactly(CacheMiss::class)
  }

  @Test
  fun usesHttpCacheEvenForPost() {
    val cache = Cache(cacheFs, "cache".toPath(), (100 * 1024).toLong())
    val cachedClient = bootstrapClient.newBuilder().cache(cache).build()
    val cachedDns =
      buildLocalhost(
        bootstrapClient = cachedClient,
        post = true,
        dnsCache = DnsCache(maxEntryCount = 0),
      )
    server.extraHeaders =
      headersOf(
        "cache-control",
        "private, max-age=298",
      )
    server["lysine.dev"] = listOf(InetAddress.getByName("10.20.30.40"))
    server["alternate.lysine.dev"] = listOf(InetAddress.getByName("55.66.77.88"))

    val result1 = cachedDns(entryPoint, "lysine.dev")
    assertThat(result1).containsExactly(address("10.20.30.40"))
    val (_, httpsRequest1) = server.takeRequest() as DnsRequest
    assertThat(httpsRequest1!!.method).isEqualTo("POST")
    assertThat(httpsRequest1.url.encodedQuery)
      .isEqualTo("ct")
    // Cloudflare fails on chunked encoding
    assertThat(httpsRequest1.headers["Content-Length"]?.toLong())
      .isNotNull()
      .isGreaterThan(12L) // A DNS message has a 12-byte header followed by its questions.
    assertThat(httpsRequest1.chunkSizes).isNull()

    assertThat(cacheEvents()).containsExactly(CacheMiss::class)

    val result2 = cachedDns(entryPoint, "lysine.dev")
    assertThat(server.pollRequest()).isNull()
    assertThat(result2).isEqualTo(listOf(address("10.20.30.40")))

    assertThat(cacheEvents()).containsExactly(CacheHit::class)

    val result3 = cachedDns(entryPoint, "alternate.lysine.dev")
    assertThat(result3).containsExactly(address("55.66.77.88"))
    val (_, httpsRequest2) = server.takeRequest() as DnsRequest
    assertThat(httpsRequest2!!.method).isEqualTo("POST")
    assertThat(httpsRequest2.url.encodedQuery)
      .isEqualTo("ct")

    assertThat(cacheEvents()).containsExactly(CacheMiss::class)
  }

  @Test
  fun usesHttpCacheOnlyIfFresh() {
    val cache = Cache(File("./target/DnsOverHttpsTest.cache"), 100 * 1024L)
    val cachedClient = bootstrapClient.newBuilder().cache(cache).build()
    val cachedDns =
      buildLocalhost(
        bootstrapClient = cachedClient,
        dnsCache = DnsCache(maxEntryCount = 0),
      )
    server.extraHeaders =
      headersOf(
        "cache-control",
        "no-store",
      )
    server["lysine.dev"] = listOf(InetAddress.getByName("10.20.30.40"))
    val result1 = cachedDns(entryPoint, "lysine.dev")
    assertThat(result1).containsExactly(address("10.20.30.40"))
    val (dnsRequest1, httpsRequest1) = server.takeRequest() as DnsRequest
    assertThat(httpsRequest1!!.method).isEqualTo("GET")
    assertThat(dnsRequest1)
      .isEqualTo(queryRequest("lysine.dev", TYPE_A))

    assertThat(cacheEvents()).containsExactly(CacheMiss::class)

    val result2 = cachedDns(entryPoint, "lysine.dev")
    assertThat(result2).isEqualTo(listOf(address("10.20.30.40")))
    val (dnsRequest2, httpsRequest2) = server.takeRequest() as DnsRequest
    assertThat(httpsRequest2!!.method).isEqualTo("GET")
    assertThat(dnsRequest2)
      .isEqualTo(queryRequest("lysine.dev", TYPE_A))

    assertThat(cacheEvents()).containsExactly(CacheMiss::class)
  }

  @Test
  fun completeHttpsRecordsReturned() {
    assumeTrue(entryPoint == EntryPoint.NewCall)

    dns = buildLocalhost(bootstrapClient, includeIPv6 = true, includeServiceMetadata = true)
    server["lysine.dev"] =
      listOf(
        ResourceRecord.IpAddress(
          name = "lysine.dev",
          timeToLive = 5,
          address = InetAddress.getByName("10.20.30.40"),
        ),
        ResourceRecord.IpAddress(
          name = "lysine.dev",
          timeToLive = 5,
          address = InetAddress.getByName("1:2::3:4"),
        ),
        ResourceRecord.Https(
          name = "lysine.dev",
          timeToLive = 5,
          targetName = "cdn.lysine.dev",
          alpnIds = listOf(Protocol.HTTP_2.toString()),
          port = 8843,
          ipAddressHints =
            listOf(
              InetAddress.getByName("55.66.77.88"),
              InetAddress.getByName("aa:bb::cc:dd"),
            ),
          echConfigList = "this is an encrypted client hello".encodeUtf8(),
        ),
      )

    val call = dns.newCall(Dns.Request("lysine.dev"))
    val dnsEvents = call.toEventsQueue()

    assertThat(dnsEvents.take()).isEqualTo(
      DnsEvent.Records(
        last = false,
        records =
          listOf(
            Dns.Record.ServiceMetadata(
              hostname = "cdn.lysine.dev",
              alpnIds = listOf(Protocol.HTTP_2),
              port = 8843,
              ipAddressHints =
                listOf(
                  InetAddress.getByName("55.66.77.88"),
                  InetAddress.getByName("aa:bb::cc:dd"),
                ),
              echConfigList = "this is an encrypted client hello".encodeUtf8(),
            ),
          ),
      ),
    )
    assertThat(dnsEvents.take()).isEqualTo(
      DnsEvent.Records(
        last = false,
        records =
          listOf(
            Dns.Record.IpAddress(
              hostname = "lysine.dev",
              address = InetAddress.getByName("1:2::3:4"),
            ),
          ),
      ),
    )
    assertThat(dnsEvents.take()).isEqualTo(
      DnsEvent.Records(
        last = true,
        records =
          listOf(
            Dns.Record.IpAddress(
              hostname = "lysine.dev",
              address = InetAddress.getByName("10.20.30.40"),
            ),
          ),
      ),
    )
  }

  @Test
  fun serviceMetadataEmptyTargetNameAliasesToRequestHostname() {
    assumeTrue(entryPoint == EntryPoint.NewCall)

    dns = buildLocalhost(bootstrapClient, includeIPv6 = true, includeServiceMetadata = true)
    server["lysine.dev"] =
      listOf(
        ResourceRecord.IpAddress(
          name = "lysine.dev",
          timeToLive = 5,
          address = InetAddress.getByName("10.20.30.40"),
        ),
        ResourceRecord.Https(
          name = "lysine.dev",
          timeToLive = 5,
          targetName = "",
          alpnIds = listOf(Protocol.HTTP_2.toString()),
        ),
      )

    val call = dns.newCall(Dns.Request("lysine.dev"))
    val dnsEvents = call.toEventsQueue()

    assertThat(dnsEvents.take()).isEqualTo(
      DnsEvent.Records(
        last = false,
        records =
          listOf(
            Dns.Record.ServiceMetadata(
              hostname = "lysine.dev",
              alpnIds = listOf(Protocol.HTTP_2),
            ),
          ),
      ),
    )
    assertThat(dnsEvents.take()).isEqualTo(
      DnsEvent.Records(
        last = true,
        records =
          listOf(
            Dns.Record.IpAddress(
              hostname = "lysine.dev",
              address = InetAddress.getByName("10.20.30.40"),
            ),
          ),
      ),
    )
  }

  /** An HTTPS error is received before the IPv4 results, but the error is delivered last. */
  @Test
  fun httpsFailureIsDeliveredAfterIpv6AndIpv4Records() {
    assumeTrue(entryPoint == EntryPoint.NewCall)

    dns = buildLocalhost(bootstrapClient, includeIPv6 = true, includeServiceMetadata = true)

    // Fail the HTTPS call, which should have index 0.
    server.sequenceIndexToOverride[0] = overrideResponse("")
    server["lysine.dev"] =
      listOf(
        ResourceRecord.IpAddress(
          name = "lysine.dev",
          timeToLive = 5,
          address = InetAddress.getByName("11:22::33:44"),
        ),
        ResourceRecord.IpAddress(
          name = "lysine.dev",
          timeToLive = 5,
          address = InetAddress.getByName("10.20.30.40"),
        ),
      )

    val call = dns.newCall(Dns.Request("lysine.dev"))
    val dnsEvents = call.toEventsQueue()

    assertThat(dnsEvents.take()).isEqualTo(
      DnsEvent.Records(
        last = false,
        records =
          listOf(
            Dns.Record.IpAddress(
              hostname = "lysine.dev",
              address = InetAddress.getByName("11:22::33:44"),
            ),
          ),
      ),
    )
    assertThat(dnsEvents.take()).isEqualTo(
      DnsEvent.Records(
        last = false,
        records =
          listOf(
            Dns.Record.IpAddress(
              hostname = "lysine.dev",
              address = InetAddress.getByName("10.20.30.40"),
            ),
          ),
      ),
    )
    assertThat(dnsEvents.take()).isInstanceOf<DnsEvent.Failure>()
  }

  /** An IPv6 error is received before the IPv4 results, but the error is delivered last. */
  @Test
  fun ipv6FailureIsDeliveredAfterIpv4Records() {
    assumeTrue(entryPoint == EntryPoint.NewCall)

    dns = buildLocalhost(bootstrapClient, includeIPv6 = true, includeServiceMetadata = true)

    // Fail the IPv6 call, which should have index 1.
    server.sequenceIndexToOverride[1] = overrideResponse("")
    server["lysine.dev"] =
      listOf(
        ResourceRecord.IpAddress(
          name = "lysine.dev",
          timeToLive = 5,
          address = InetAddress.getByName("10.20.30.40"),
        ),
      )

    val call = dns.newCall(Dns.Request("lysine.dev"))
    val dnsEvents = call.toEventsQueue()

    assertThat(dnsEvents.take()).isEqualTo(
      DnsEvent.Records(
        last = false,
        records =
          listOf(
            Dns.Record.IpAddress(
              hostname = "lysine.dev",
              address = InetAddress.getByName("10.20.30.40"),
            ),
          ),
      ),
    )
    assertThat(dnsEvents.take()).isInstanceOf<DnsEvent.Failure>()
  }

  /** There's no IPv6 event because there's no IPv6 results. */
  @Test
  fun emptyResultsAreSkipped() {
    assumeTrue(entryPoint == EntryPoint.NewCall)

    dns = buildLocalhost(bootstrapClient, includeIPv6 = true, includeServiceMetadata = true)
    server["lysine.dev"] =
      listOf(
        ResourceRecord.IpAddress(
          name = "lysine.dev",
          timeToLive = 5,
          address = InetAddress.getByName("10.20.30.40"),
        ),
      )

    val call = dns.newCall(Dns.Request("lysine.dev"))
    val dnsEvents = call.toEventsQueue()

    assertThat(dnsEvents.take()).isEqualTo(
      DnsEvent.Records(
        last = true,
        records =
          listOf(
            Dns.Record.IpAddress(
              hostname = "lysine.dev",
              address = InetAddress.getByName("10.20.30.40"),
            ),
          ),
      ),
    )
  }

  @Test
  fun lastEventIsDeliveredEventIfItIsEmpty() {
    assumeTrue(entryPoint == EntryPoint.NewCall)

    dns = buildLocalhost(bootstrapClient, includeIPv6 = true, includeServiceMetadata = true)

    val call = dns.newCall(Dns.Request("lysine.dev"))
    val dnsEvents = call.toEventsQueue()

    assertThat(dnsEvents.take()).isEqualTo(
      DnsEvent.Records(
        last = true,
        records = listOf(),
      ),
    )
  }

  @Test
  fun callIsCanceledBeforeItIsStarted() {
    assumeTrue(entryPoint == EntryPoint.NewCall)

    dns = buildLocalhost(bootstrapClient, includeIPv6 = true, includeServiceMetadata = true)

    val call = dns.newCall(Dns.Request("lysine.dev"))
    call.cancel()
    assertThat(call.isCanceled()).isTrue()
    val dnsEvents = call.toEventsQueue()

    assertThat(dnsEvents.take()).isInstanceOf<DnsEvent.Failure>()
  }

  @Test
  fun callIsCanceledBeforeItReachesTheNetwork() {
    assumeTrue(entryPoint == EntryPoint.NewCall)

    dns = buildLocalhost(bootstrapClient, includeIPv6 = true, includeServiceMetadata = true)
    val call = dns.newCall(Dns.Request("lysine.dev"))

    interceptor =
      Interceptor { chain ->
        call.cancel()
        assertThat(call.isCanceled()).isTrue()
        chain.proceed(chain.request())
      }

    val dnsEvents = call.toEventsQueue()
    assertThat(dnsEvents.take()).isInstanceOf<DnsEvent.Failure>()
  }

  @Test
  fun callIsCanceledAfterPartialSuccess() {
    assumeTrue(entryPoint == EntryPoint.NewCall)

    dns = buildLocalhost(bootstrapClient, includeIPv6 = true)
    server["lysine.dev"] =
      listOf(
        ResourceRecord.IpAddress(
          name = "lysine.dev",
          timeToLive = 5,
          address = InetAddress.getByName("11:22::33:44"),
        ),
      )

    val call = dns.newCall(Dns.Request("lysine.dev"))

    interceptor =
      object : Interceptor {
        var requestCount = 0

        override fun intercept(chain: Interceptor.Chain): Response {
          if (requestCount++ == 1) {
            call.cancel()
            assertThat(call.isCanceled()).isTrue()
          }
          return chain.proceed(chain.request())
        }
      }

    val dnsEvents = call.toEventsQueue()
    assertThat(dnsEvents.take()).isEqualTo(
      DnsEvent.Records(
        last = false,
        records =
          listOf(
            Dns.Record.IpAddress(
              hostname = "lysine.dev",
              address = InetAddress.getByName("11:22::33:44"),
            ),
          ),
      ),
    )
    assertThat(dnsEvents.take()).isInstanceOf<DnsEvent.Failure>()
  }

  @Test
  fun callbackIsCalledSequentiallyWhenHttpCallsAreParallel() {
    callbackIsCalledSequentially()
  }

  @Test
  fun callbackIsCalledSequentiallyWhenCallsFail() {
    server.sequenceIndexToOverride[0] = overrideResponse("")
    callbackIsCalledSequentially()
  }

  /**
   * We'd like to confirm that calls into [Dns.Callback] are serialized.
   *
   * Asserting that is awkward, so we're asserting something stronger, advised by the
   * implementation: if the first call to `onRecords()` waits for the other 2 HTTP calls to
   * finish, then all 3 calls will happen on the same thread.
   *
   * (This works because we know the other 2 HTTP calls will finish before their data is emitted.)
   */
  private fun callbackIsCalledSequentially() {
    assumeTrue(entryPoint == EntryPoint.NewCall)

    dns = buildLocalhost(bootstrapClient, includeIPv6 = true, includeServiceMetadata = true)
    server["lysine.dev"] =
      listOf(
        ResourceRecord.IpAddress(
          name = "lysine.dev",
          timeToLive = 5,
          address = InetAddress.getByName("10.20.30.40"),
        ),
        ResourceRecord.IpAddress(
          name = "lysine.dev",
          timeToLive = 5,
          address = InetAddress.getByName("1:2::3:4"),
        ),
        ResourceRecord.Https(
          name = "lysine.dev",
          timeToLive = 5,
          alpnIds = listOf(Protocol.HTTP_2.toString()),
        ),
      )

    // Track which threads receive callbacks.
    val threadsFuture = CompletableFuture<Set<Thread>>()

    dispatcher.maxRequestsPerHost = 3

    val call = dns.newCall(Dns.Request("lysine.dev"))
    call.enqueue(
      object : Dns.Callback {
        private val threads = mutableSetOf<Thread>()

        override fun onRecords(
          call: Dns.Call,
          last: Boolean,
          records: List<Dns.Record>,
        ) {
          onCallback(last)
        }

        override fun onFailure(
          call: Dns.Call,
          e: java.io.IOException,
        ) {
          onCallback(true)
        }

        private fun onCallback(last: Boolean) {
          if (threads.isEmpty()) {
            completedRunnables.take()
            completedRunnables.take()
          }
          threads += Thread.currentThread()
          if (last) {
            threadsFuture.complete(threads)
          }
        }
      },
    )

    assertThat(threadsFuture.get()).hasSize(1)
  }

  private fun cacheEvents(): List<KClass<out CallEvent>> =
    eventRecorder
      .recordedEventTypes()
      .filter { "Cache" in it.simpleName!! }
      .also { eventRecorder.clearAllEvents() }

  private fun buildLocalhost(
    bootstrapClient: OkHttpClient,
    dnsCache: DnsCache = DnsCache(),
    includeIPv6: Boolean = false,
    includeServiceMetadata: Boolean = false,
    post: Boolean = false,
    resolvePrivateAddresses: Boolean = true,
    resolvePublicAddresses: Boolean = true,
  ): DnsOverHttps {
    val url = mockWebServer.url("/lookup?ct")
    return DnsOverHttps
      .Builder()
      .client(bootstrapClient)
      .cache(dnsCache)
      .includeIPv6(includeIPv6)
      .includeServiceMetadata(includeServiceMetadata)
      .resolvePrivateAddresses(resolvePrivateAddresses)
      .resolvePublicAddresses(resolvePublicAddresses)
      .url(url)
      .post(post)
      .build()
  }

  private fun overrideResponse(hexBody: String = ""): MockResponse =
    MockResponse
      .Builder()
      .body(Buffer().write(hexBody.decodeHex()))
      .addHeader("content-type", "application/dns-message")
      .addHeader("content-length", hexBody.length / 2)
      .build()

  private fun queryRequest(
    host: String,
    type: Int,
  ): DnsMessage =
    DnsMessage(
      id = 0,
      flags = 256,
      questions =
        listOf(
          Question(
            name = host,
            type = type,
            `class` = CLASS_IN,
          ),
        ),
    )

  companion object {
    private fun address(host: String) = InetAddress.getByName(host)
  }
}
