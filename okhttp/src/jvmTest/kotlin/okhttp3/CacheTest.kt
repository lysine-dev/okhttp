/*
 * Copyright (C) 2011 The Android Open Source Project
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
import assertk.assertions.containsExactly
import assertk.assertions.isCloseTo
import assertk.assertions.isEmpty
import assertk.assertions.isEqualTo
import assertk.assertions.isFalse
import assertk.assertions.isNotNull
import assertk.assertions.isNull
import assertk.assertions.isTrue
import java.io.IOException
import java.net.CookieManager
import java.net.HttpURLConnection
import java.net.ResponseCache
import java.text.DateFormat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import java.util.stream.Stream
import javax.net.ssl.HostnameVerifier
import kotlin.test.assertFailsWith
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import mockwebserver3.SocketEffect.ShutdownConnection
import mockwebserver3.junit5.StartStop
import okhttp3.Cache.Companion.key
import okhttp3.Headers.Companion.headersOf
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.internal.addHeaderLenient
import okhttp3.internal.cacheGet
import okhttp3.internal.platform.Platform.Companion.get
import okhttp3.java.net.cookiejar.JavaNetCookieJar
import okhttp3.testing.PlatformRule
import okio.Buffer
import okio.BufferedSink
import okio.FileSystem
import okio.ForwardingFileSystem
import okio.GzipSink
import okio.Path
import okio.Path.Companion.toPath
import okio.buffer
import okio.fakefilesystem.FakeFileSystem
import okio.use
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.RepeatedTest
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.RegisterExtension

@Tag("Slow")
class CacheTest {
  val fileSystem = FakeFileSystem()

  @RegisterExtension
  val clientTestRule = OkHttpClientTestRule()

  @RegisterExtension
  val platform = PlatformRule()

  @StartStop
  private val server = MockWebServer()

  @StartStop
  private val server2 = MockWebServer()

  private val handshakeCertificates = platform.localhostHandshakeCertificates()
  private lateinit var client: OkHttpClient
  private lateinit var cache: Cache
  private val cookieManager = CookieManager()

  @BeforeEach
  fun setUp() {
    platform.assumeNotOpenJSSE()
    server.protocolNegotiationEnabled = false
    fileSystem.emulateUnix()
    cache = Cache(fileSystem, "/cache/".toPath(), Long.MAX_VALUE)
    client =
      clientTestRule
        .newClientBuilder()
        .cache(cache)
        .cookieJar(JavaNetCookieJar(cookieManager))
        .build()
  }

  @AfterEach
  fun tearDown() {
    ResponseCache.setDefault(null)
    if (this::cache.isInitialized) {
      cache.delete()
    }
  }

  /**
   * Test that response caching is consistent with the RI and the spec.
   * http://www.w3.org/Protocols/rfc2616/rfc2616-sec13.html#sec13.4
   */
  @Test
  fun responseCachingByResponseCode() {
    // Test each documented HTTP/1.1 code, plus the first unused value in each range.
    // http://www.w3.org/Protocols/rfc2616/rfc2616-sec10.html

    // We can't test 100 because it's not really a response.
    // assertCached(false, 100);
    assertCached(false, 101)
    assertCached(true, 200)
    assertCached(false, 201)
    assertCached(false, 202)
    assertCached(true, 203)
    assertCached(true, 204)
    assertCached(false, 205)
    assertCached(false, 206) // Electing to not cache partial responses
    assertCached(false, 207)
    assertCached(true, 300)
    assertCached(true, 301)
    assertCached(true, 302)
    assertCached(false, 303)
    assertCached(false, 304)
    assertCached(false, 305)
    assertCached(false, 306)
    assertCached(true, 307)
    assertCached(true, 308)
    assertCached(false, 400)
    assertCached(false, 401)
    assertCached(false, 402)
    assertCached(false, 403)
    assertCached(true, 404)
    assertCached(true, 405)
    assertCached(false, 406)
    assertCached(false, 408)
    assertCached(false, 409)
    // the HTTP spec permits caching 410s, but the RI doesn't.
    assertCached(true, 410)
    assertCached(false, 411)
    assertCached(false, 412)
    assertCached(false, 413)
    assertCached(true, 414)
    assertCached(false, 415)
    assertCached(false, 416)
    assertCached(false, 417)
    assertCached(false, 418)
    assertCached(false, 500)
    assertCached(true, 501)
    assertCached(false, 502)
    assertCached(false, 503)
    assertCached(false, 504)
    assertCached(false, 505)
    assertCached(false, 506)
  }

  @Test
  fun responseCachingWith1xxInformationalResponse() {
    assertSubsequentResponseCached(102, 200)
    assertSubsequentResponseCached(103, 200)
  }

  private fun assertCached(
    shouldWriteToCache: Boolean,
    responseCode: Int,
    method: String = "GET",
  ) {
    var expectedResponseCode = responseCode
    val server = MockWebServer()
    val builder =
      MockResponse
        .Builder()
        .addHeader("Last-Modified: " + formatDate(-1, TimeUnit.HOURS))
        .addHeader("Expires: " + formatDate(1, TimeUnit.HOURS))
        .code(responseCode)
        .body("ABCDE")
        .addHeader("WWW-Authenticate: challenge")
    when (responseCode) {
      HttpURLConnection.HTTP_PROXY_AUTH -> {
        builder.addHeader("Proxy-Authenticate: Basic realm=\"protected area\"")
      }

      HttpURLConnection.HTTP_UNAUTHORIZED -> {
        builder.addHeader("WWW-Authenticate: Basic realm=\"protected area\"")
      }

      HttpURLConnection.HTTP_NO_CONTENT, HttpURLConnection.HTTP_RESET -> {
        builder.body("") // We forbid bodies for 204 and 205.
      }
    }
    server.enqueue(builder.build())
    if (responseCode == HttpURLConnection.HTTP_CLIENT_TIMEOUT) {
      // 408's are a bit of an outlier because we may repeat the request if we encounter this
      // response code. In this scenario, there are 2 responses: the initial 408 and then the 200
      // because of the retry. We just want to ensure the initial 408 isn't cached.
      expectedResponseCode = 200
      server.enqueue(
        MockResponse
          .Builder()
          .setHeader("Cache-Control", "no-store")
          .body("FGHIJ")
          .build(),
      )
    }
    server.start()
    val request =
      Request
        .Builder()
        .url(server.url("/"))
        .method(method, null)
        .build()
    val response = client.newCall(request).execute()
    assertThat(response.code).isEqualTo(expectedResponseCode)

    // Exhaust the content stream.
    response.body.string()
    val cached = cacheGet(cache, request)
    if (shouldWriteToCache) {
      assertThat(cached).isNotNull()
      cached!!.body.close()
    } else {
      assertThat(cached).isNull()
    }
    server.close() // tearDown() isn't sufficient; this test starts multiple servers
  }

  private fun assertSubsequentResponseCached(
    initialResponseCode: Int,
    finalResponseCode: Int,
  ) {
    val server = MockWebServer()
    val builder =
      MockResponse
        .Builder()
        .addHeader("Last-Modified: " + formatDate(-1, TimeUnit.HOURS))
        .addHeader("Expires: " + formatDate(1, TimeUnit.HOURS))
        .code(finalResponseCode)
        .body("ABCDE")
        .addInformationalResponse(MockResponse(initialResponseCode))
    server.enqueue(builder.build())
    server.start()
    val request =
      Request
        .Builder()
        .url(server.url("/"))
        .build()
    val response = client.newCall(request).execute()
    assertThat(response.code).isEqualTo(finalResponseCode)

    // Exhaust the content stream.
    response.body.string()
    val cached = cacheGet(cache, request)
    assertThat(cached).isNotNull()
    cached!!.body.close()
    server.close() // tearDown() isn't sufficient; this test starts multiple servers
  }

  @Test
  fun responseCachingAndInputStreamSkipWithFixedLength() {
    testResponseCaching(TransferKind.FIXED_LENGTH)
  }

  @Test
  fun responseCachingAndInputStreamSkipWithChunkedEncoding() {
    testResponseCaching(TransferKind.CHUNKED)
  }

  @Test
  fun responseCachingAndInputStreamSkipWithNoLengthHeaders() {
    testResponseCaching(TransferKind.END_OF_STREAM)
  }

  /**
   * Skipping bytes in the input stream caused ResponseCache corruption.
   * http://code.google.com/p/android/issues/detail?id=8175
   */
  private fun testResponseCaching(transferKind: TransferKind) {
    val mockResponse =
      MockResponse
        .Builder()
        .addHeader("Last-Modified: " + formatDate(-1, TimeUnit.HOURS))
        .addHeader("Expires: " + formatDate(1, TimeUnit.HOURS))
        .status("HTTP/1.1 200 Fantastic")
    transferKind.setBody(mockResponse, "I love puppies but hate spiders", 1)
    server.enqueue(mockResponse.build())

    // Make sure that calling skip() doesn't omit bytes from the cache.
    val request = Request.Builder().url(server.url("/")).build()
    val response1 = client.newCall(request).execute()
    val in1 = response1.body.source()
    assertThat(in1.readUtf8("I love ".length.toLong())).isEqualTo("I love ")
    in1.skip("puppies but hate ".length.toLong())
    assertThat(in1.readUtf8("spiders".length.toLong())).isEqualTo("spiders")
    assertThat(in1.exhausted()).isTrue()
    in1.close()
    assertThat(cache.writeSuccessCount()).isEqualTo(1)
    assertThat(cache.writeAbortCount()).isEqualTo(0)
    val response2 = client.newCall(request).execute()
    val in2 = response2.body.source()
    assertThat(in2.readUtf8("I love puppies but hate spiders".length.toLong()))
      .isEqualTo(
        "I love puppies but hate spiders",
      )
    assertThat(response2.code).isEqualTo(200)
    assertThat(response2.message).isEqualTo("Fantastic")
    assertThat(in2.exhausted()).isTrue()
    in2.close()
    assertThat(cache.writeSuccessCount()).isEqualTo(1)
    assertThat(cache.writeAbortCount()).isEqualTo(0)
    assertThat(cache.requestCount()).isEqualTo(2)
    assertThat(cache.hitCount()).isEqualTo(1)
  }

  @Test
  fun secureResponseCaching() {
    server.useHttps(handshakeCertificates.sslSocketFactory())
    server.enqueue(
      MockResponse
        .Builder()
        .addHeader("Last-Modified: " + formatDate(-1, TimeUnit.HOURS))
        .addHeader("Expires: " + formatDate(1, TimeUnit.HOURS))
        .body("ABC")
        .build(),
    )
    client =
      client
        .newBuilder()
        .sslSocketFactory(
          handshakeCertificates.sslSocketFactory(),
          handshakeCertificates.trustManager,
        ).hostnameVerifier(NULL_HOSTNAME_VERIFIER)
        .build()
    val request = Request.Builder().url(server.url("/")).build()
    val response1 = client.newCall(request).execute()
    val source = response1.body.source()
    assertThat(source.readUtf8()).isEqualTo("ABC")

    // OpenJDK 6 fails on this line, complaining that the connection isn't open yet
    val cipherSuite = response1.handshake!!.cipherSuite
    val localCerts = response1.handshake!!.localCertificates
    val serverCerts = response1.handshake!!.peerCertificates
    val peerPrincipal = response1.handshake!!.peerPrincipal
    val localPrincipal = response1.handshake!!.localPrincipal
    val response2 = client.newCall(request).execute() // Cached!
    assertThat(response2.body.string()).isEqualTo("ABC")
    assertThat(cache.requestCount()).isEqualTo(2)
    assertThat(cache.networkCount()).isEqualTo(1)
    assertThat(cache.hitCount()).isEqualTo(1)
    assertThat(response2.handshake!!.cipherSuite).isEqualTo(cipherSuite)
    assertThat(response2.handshake!!.localCertificates).isEqualTo(localCerts)
    assertThat(response2.handshake!!.peerCertificates).isEqualTo(serverCerts)
    assertThat(response2.handshake!!.peerPrincipal).isEqualTo(peerPrincipal)
    assertThat(response2.handshake!!.localPrincipal).isEqualTo(localPrincipal)
  }

  @Test
  fun secureResponseCachingWithCorruption() {
    server.useHttps(handshakeCertificates.sslSocketFactory())
    server.enqueue(
      MockResponse
        .Builder()
        .addHeader("Last-Modified: " + formatDate(-1, TimeUnit.HOURS))
        .addHeader("Expires: " + formatDate(1, TimeUnit.HOURS))
        .body("ABC")
        .build(),
    )
    server.enqueue(
      MockResponse
        .Builder()
        .addHeader("Last-Modified: " + formatDate(-5, TimeUnit.MINUTES))
        .addHeader("Expires: " + formatDate(2, TimeUnit.HOURS))
        .body("DEF")
        .build(),
    )
    client =
      client
        .newBuilder()
        .sslSocketFactory(
          handshakeCertificates.sslSocketFactory(),
          handshakeCertificates.trustManager,
        ).hostnameVerifier(NULL_HOSTNAME_VERIFIER)
        .build()
    val request = Request.Builder().url(server.url("/")).build()
    val response1 = client.newCall(request).execute()
    assertThat(response1.body.string()).isEqualTo("ABC")
    val cacheEntry =
      fileSystem.allPaths
        .stream()
        .filter { e: Path -> e.name.endsWith(".0") }
        .findFirst()
        .orElseThrow { NoSuchElementException() }
    corruptCertificate(cacheEntry)
    val response2 = client.newCall(request).execute() // Not Cached!
    assertThat(response2.body.string()).isEqualTo("DEF")
    assertThat(cache.requestCount()).isEqualTo(2)
    assertThat(cache.networkCount()).isEqualTo(2)
    assertThat(cache.hitCount()).isEqualTo(0)
  }

  private fun corruptCertificate(cacheEntry: Path) {
    var content = fileSystem.source(cacheEntry).buffer().readUtf8()
    content = content.replace("MII", "!!!")
    fileSystem
      .sink(cacheEntry)
      .buffer()
      .writeUtf8(content)
      .close()
  }

  /**
   * A network interceptor strips the handshake from a real HTTPS response before the
   * CacheInterceptor writes it to disk. This creates the bug condition: url.isHttps=true
   * but handshake=null. Before the fix, `handshake!!` in writeTo() threw NPE.
   *
   * https://github.com/lysine-dev/okhttp/issues/8962
   */
  @Test
  fun httpsResponseWithNullHandshakeDoesNotCrashWriteTo() {
    server.useHttps(handshakeCertificates.sslSocketFactory())
    server.enqueue(
      MockResponse
        .Builder()
        .body("secure content")
        .addHeader("Cache-Control", "max-age=3600")
        .build(),
    )

    client =
      client
        .newBuilder()
        .sslSocketFactory(
          handshakeCertificates.sslSocketFactory(),
          handshakeCertificates.trustManager,
        ).hostnameVerifier(NULL_HOSTNAME_VERIFIER)
        .addNetworkInterceptor { chain ->
          chain
            .proceed(chain.request())
            .newBuilder()
            .handshake(null)
            .build()
        }.build()

    val response = client.newCall(Request(server.url("/"))).execute()
    assertThat(response.code).isEqualTo(200)
    assertThat(response.body.string()).isEqualTo("secure content")
  }

  /**
   * Verifies the null-handshake fix holds across multiple sequential cache writes, confirming
   * it is not a one-time race condition.
   *
   * https://github.com/lysine-dev/okhttp/issues/8962
   */
  @Test
  fun multipleHttpsRequestsWithNullHandshakeAllSucceed() {
    server.useHttps(handshakeCertificates.sslSocketFactory())
    repeat(3) {
      server.enqueue(
        MockResponse
          .Builder()
          .body("response $it")
          .addHeader("Cache-Control", "max-age=3600")
          .build(),
      )
    }

    client =
      client
        .newBuilder()
        .sslSocketFactory(
          handshakeCertificates.sslSocketFactory(),
          handshakeCertificates.trustManager,
        ).hostnameVerifier(NULL_HOSTNAME_VERIFIER)
        .addNetworkInterceptor { chain ->
          chain
            .proceed(chain.request())
            .newBuilder()
            .handshake(null)
            .build()
        }.build()

    repeat(3) { i ->
      val response = client.newCall(Request(server.url("/path$i"))).execute()
      assertThat(response.code).isEqualTo(200)
      assertThat(response.body.string()).isEqualTo("response $i")
    }
  }

  /**
   * When handshake is null for an HTTPS URL, the TLS block is skipped making the entry
   * unreadable on re-read. The response should still succeed but won't be served from cache
   * on subsequent requests.
   *
   * https://github.com/lysine-dev/okhttp/issues/8962
   */
  @Test
  fun httpsResponseWithNullHandshakeIsNotServedFromCache() {
    server.useHttps(handshakeCertificates.sslSocketFactory())
    server.enqueue(
      MockResponse
        .Builder()
        .body("first")
        .addHeader("Cache-Control", "max-age=3600")
        .build(),
    )
    server.enqueue(
      MockResponse
        .Builder()
        .body("second")
        .addHeader("Cache-Control", "max-age=3600")
        .build(),
    )

    client =
      client
        .newBuilder()
        .sslSocketFactory(
          handshakeCertificates.sslSocketFactory(),
          handshakeCertificates.trustManager,
        ).hostnameVerifier(NULL_HOSTNAME_VERIFIER)
        .addNetworkInterceptor { chain ->
          chain
            .proceed(chain.request())
            .newBuilder()
            .handshake(null)
            .build()
        }.build()

    val response1 = client.newCall(Request(server.url("/"))).execute()
    assertThat(response1.body.string()).isEqualTo("first")

    // Second request hits the network again because the first entry was not cacheable
    val response2 = client.newCall(Request(server.url("/"))).execute()
    assertThat(response2.body.string()).isEqualTo("second")
    assertThat(response2.cacheResponse).isNull()
  }

  @Test
  fun responseCachingAndRedirects() {
    server.enqueue(
      MockResponse
        .Builder()
        .addHeader("Last-Modified: " + formatDate(-1, TimeUnit.HOURS))
        .addHeader("Expires: " + formatDate(1, TimeUnit.HOURS))
        .code(HttpURLConnection.HTTP_MOVED_PERM)
        .addHeader("Location: /foo")
        .build(),
    )
    server.enqueue(
      MockResponse
        .Builder()
        .addHeader("Last-Modified: " + formatDate(-1, TimeUnit.HOURS))
        .addHeader("Expires: " + formatDate(1, TimeUnit.HOURS))
        .body("ABC")
        .build(),
    )
    server.enqueue(
      MockResponse
        .Builder()
        .body("DEF")
        .build(),
    )
    val request = Request.Builder().url(server.url("/")).build()
    val response1 = client.newCall(request).execute()
    assertThat(response1.body.string()).isEqualTo("ABC")
    val response2 = client.newCall(request).execute() // Cached!
    assertThat(response2.body.string()).isEqualTo("ABC")

    // 2 requests + 2 redirects
    assertThat(cache.requestCount()).isEqualTo(4)
    assertThat(cache.networkCount()).isEqualTo(2)
    assertThat(cache.hitCount()).isEqualTo(2)
  }

  @Test
  fun redirectToCachedResult() {
    server.enqueue(
      MockResponse
        .Builder()
        .addHeader("Cache-Control: max-age=60")
        .body("ABC")
        .build(),
    )
    server.enqueue(
      MockResponse
        .Builder()
        .code(HttpURLConnection.HTTP_MOVED_PERM)
        .addHeader("Location: /foo")
        .build(),
    )
    server.enqueue(
      MockResponse
        .Builder()
        .body("DEF")
        .build(),
    )
    val request1 = Request.Builder().url(server.url("/foo")).build()
    val response1 = client.newCall(request1).execute()
    assertThat(response1.body.string()).isEqualTo("ABC")
    val recordedRequest1 = server.takeRequest()
    assertThat(recordedRequest1.requestLine).isEqualTo("GET /foo HTTP/1.1")
    assertThat(recordedRequest1.connectionIndex).isEqualTo(0)
    assertThat(recordedRequest1.exchangeIndex).isEqualTo(0)
    val request2 = Request.Builder().url(server.url("/bar")).build()
    val response2 = client.newCall(request2).execute()
    assertThat(response2.body.string()).isEqualTo("ABC")
    val recordedRequest2 = server.takeRequest()
    assertThat(recordedRequest2.requestLine).isEqualTo("GET /bar HTTP/1.1")
    assertThat(recordedRequest2.connectionIndex).isEqualTo(0)
    assertThat(recordedRequest2.exchangeIndex).isEqualTo(1)

    // an unrelated request should reuse the pooled connection
    val request3 = Request.Builder().url(server.url("/baz")).build()
    val response3 = client.newCall(request3).execute()
    assertThat(response3.body.string()).isEqualTo("DEF")
    val recordedRequest3 = server.takeRequest()
    assertThat(recordedRequest3.requestLine).isEqualTo("GET /baz HTTP/1.1")
    assertThat(recordedRequest3.connectionIndex).isEqualTo(0)
    assertThat(recordedRequest3.exchangeIndex).isEqualTo(2)
  }

  @Test
  fun getAndQueryRedirectToCachedResultIndependently() {
    // GET responses
    server.enqueue(
      MockResponse
        .Builder()
        .addHeader("Cache-Control: max-age=60")
        .body("ABC")
        .build(),
    )
    // QUERY responses
    server.enqueue(
      MockResponse
        .Builder()
        .addHeader("Cache-Control: max-age=60")
        .body("DEF")
        .build(),
    )

    val requestGet1 =
      Request
        .Builder()
        .url(server.url("/foo"))
        .get()
        .build()
    val response1 = client.newCall(requestGet1).execute()
    assertThat(response1.body.string()).isEqualTo("ABC")
    val recordedRequest1 = server.takeRequest()
    assertThat(recordedRequest1.requestLine).isEqualTo("GET /foo HTTP/1.1")

    val requestQuery1 =
      Request
        .Builder()
        .url(server.url("/foo"))
        .query(RequestBody.EMPTY)
        .build()
    val response2 = client.newCall(requestQuery1).execute()
    assertThat(response2.body.string()).isEqualTo("DEF")
    val recordedRequest2 = server.takeRequest()
    assertThat(recordedRequest2.requestLine).isEqualTo("QUERY /foo HTTP/1.1")
  }

  @Test
  fun queryRequestsCacheTheBodyWithCacheUrlOverride() {
    server.enqueue(
      MockResponse
        .Builder()
        .addHeader("Cache-Control: max-age=60")
        .body("ABC")
        .build(),
    )
    server.enqueue(
      MockResponse
        .Builder()
        .addHeader("Cache-Control: max-age=60")
        .body("DEF")
        .build(),
    )
    server.enqueue(
      MockResponse
        .Builder()
        .addHeader("Cache-Control: max-age=60")
        .body("DEFa")
        .build(),
    )

    val url = server.url("/same")

    // First QUERY request with body "foo"
    val request1 =
      Request
        .Builder()
        .url(url)
        .query("foo".toRequestBody())
        .cacheUrlOverride(url.newBuilder().addQueryParameter("body", "foo").build())
        .build()
    val response1 = client.newCall(request1).execute()
    assertThat(response1.body.string()).isEqualTo("ABC")

    // Second QUERY request with body "bar"
    val request2 =
      Request
        .Builder()
        .url(url)
        .query("bar".toRequestBody())
        .cacheUrlOverride(url.newBuilder().addQueryParameter("body", "bar").build())
        .build()
    val response2 = client.newCall(request2).execute()
    assertThat(response2.body.string()).isEqualTo("DEF")

    // Third QUERY request with body "bar" but not cached
    val request3 =
      Request
        .Builder()
        .url(url)
        .query("bar".toRequestBody())
        .build()
    val response3 = client.newCall(request3).execute()
    assertThat(response3.body.string()).isEqualTo("DEFa")

    // Fourth QUERY request with body "foo" again, should be cached and return "ABC"
    val response1a = client.newCall(request1).execute()
    assertThat(response1a.body.string()).isEqualTo("ABC")

    // Fifth QUERY request with body "bar" again, should be cached and return "DEF"
    val response2a = client.newCall(request2).execute()
    assertThat(response2a.body.string()).isEqualTo("DEF")
  }

  @Test
  fun oneshotBodyIsNotCachedForQueryRequest() {
    server.enqueue(
      MockResponse
        .Builder()
        .addHeader("Cache-Control: max-age=60")
        .body("ABC1")
        .build(),
    )
    server.enqueue(
      MockResponse
        .Builder()
        .addHeader("Cache-Control: max-age=60")
        .body("ABC2")
        .build(),
    )

    val url = server.url("/same")

    // QUERY request with body "foo"
    val body = "foo"

    val request1 =
      Request
        .Builder()
        .url(url)
        .query(body.toOneShotRequestBody())
        .build()
    val response1 = client.newCall(request1).execute()
    assertThat(response1.body.string()).isEqualTo("ABC1")

    // QUERY request with body "foo" again, should not be cached
    val request2 =
      Request
        .Builder()
        .url(url)
        .query(body.toOneShotRequestBody())
        .build()
    val response2 = client.newCall(request2).execute()
    assertThat(response2.body.string()).isEqualTo("ABC2")

    // Check that the cache did not store the response
    assertThat(cache.requestCount()).isEqualTo(2)
    assertThat(cache.hitCount()).isEqualTo(0)
  }

  private fun String.toOneShotRequestBody(): RequestBody =
    object : RequestBody() {
      val internalBody = Stream.of(this)

      override fun isOneShot(): Boolean = true

      override fun contentType(): MediaType? = "application/text-plain".toMediaTypeOrNull()

      override fun writeTo(sink: BufferedSink) {
        internalBody.forEach { item ->
          sink.writeUtf8(this@toOneShotRequestBody)
        }
      }
    }

  @Test
  fun secureResponseCachingAndRedirects() {
    server.useHttps(handshakeCertificates.sslSocketFactory())
    server.enqueue(
      MockResponse
        .Builder()
        .addHeader("Last-Modified: " + formatDate(-1, TimeUnit.HOURS))
        .addHeader("Expires: " + formatDate(1, TimeUnit.HOURS))
        .code(HttpURLConnection.HTTP_MOVED_PERM)
        .addHeader("Location: /foo")
        .build(),
    )
    server.enqueue(
      MockResponse
        .Builder()
        .addHeader("Last-Modified: " + formatDate(-1, TimeUnit.HOURS))
        .addHeader("Expires: " + formatDate(1, TimeUnit.HOURS))
        .body("ABC")
        .build(),
    )
    server.enqueue(
      MockResponse
        .Builder()
        .body("DEF")
        .build(),
    )
    client =
      client
        .newBuilder()
        .sslSocketFactory(
          handshakeCertificates.sslSocketFactory(),
          handshakeCertificates.trustManager,
        ).hostnameVerifier(NULL_HOSTNAME_VERIFIER)
        .build()
    val response1 = get(server.url("/"))
    assertThat(response1.body.string()).isEqualTo("ABC")
    assertThat(response1.handshake!!.cipherSuite).isNotNull()

    // Cached!
    val response2 = get(server.url("/"))
    assertThat(response2.body.string()).isEqualTo("ABC")
    assertThat(response2.handshake!!.cipherSuite).isNotNull()

    // 2 direct + 2 redirect = 4
    assertThat(cache.requestCount()).isEqualTo(4)
    assertThat(cache.hitCount()).isEqualTo(2)
    assertThat(response2.handshake!!.cipherSuite).isEqualTo(
      response1.handshake!!.cipherSuite,
    )
  }

  /**
   * We've had bugs where caching and cross-protocol redirects yield class cast exceptions internal
   * to the cache because we incorrectly assumed that HttpsURLConnection was always HTTPS and
   * HttpURLConnection was always HTTP; in practice redirects mean that each can do either.
   *
   * https://github.com/lysine-dev/okhttp/issues/214
   */
  @Test
  fun secureResponseCachingAndProtocolRedirects() {
    server2.useHttps(handshakeCertificates.sslSocketFactory())
    server2.enqueue(
      MockResponse
        .Builder()
        .addHeader("Last-Modified: " + formatDate(-1, TimeUnit.HOURS))
        .addHeader("Expires: " + formatDate(1, TimeUnit.HOURS))
        .body("ABC")
        .build(),
    )
    server2.enqueue(
      MockResponse
        .Builder()
        .body("DEF")
        .build(),
    )
    server.enqueue(
      MockResponse
        .Builder()
        .addHeader("Last-Modified: " + formatDate(-1, TimeUnit.HOURS))
        .addHeader("Expires: " + formatDate(1, TimeUnit.HOURS))
        .code(HttpURLConnection.HTTP_MOVED_PERM)
        .addHeader("Location: " + server2.url("/"))
        .build(),
    )
    client =
      client
        .newBuilder()
        .sslSocketFactory(
          handshakeCertificates.sslSocketFactory(),
          handshakeCertificates.trustManager,
        ).hostnameVerifier(NULL_HOSTNAME_VERIFIER)
        .build()
    val response1 = get(server.url("/"))
    assertThat(response1.body.string()).isEqualTo("ABC")

    // Cached!
    val response2 = get(server.url("/"))
    assertThat(response2.body.string()).isEqualTo("ABC")

    // 2 direct + 2 redirect = 4
    assertThat(cache.requestCount()).isEqualTo(4)
    assertThat(cache.hitCount()).isEqualTo(2)
  }

  @Test
  fun foundCachedWithExpiresHeader() {
    temporaryRedirectCachedWithCachingHeader(302, "Expires", formatDate(1, TimeUnit.HOURS))
  }

  @Test
  fun foundCachedWithCacheControlHeader() {
    temporaryRedirectCachedWithCachingHeader(302, "Cache-Control", "max-age=60")
  }

  @Test
  fun temporaryRedirectCachedWithExpiresHeader() {
    temporaryRedirectCachedWithCachingHeader(307, "Expires", formatDate(1, TimeUnit.HOURS))
  }

  @Test
  fun temporaryRedirectCachedWithCacheControlHeader() {
    temporaryRedirectCachedWithCachingHeader(307, "Cache-Control", "max-age=60")
  }

  @Test
  fun foundNotCachedWithoutCacheHeader() {
    temporaryRedirectNotCachedWithoutCachingHeader(302)
  }

  @Test
  fun temporaryRedirectNotCachedWithoutCacheHeader() {
    temporaryRedirectNotCachedWithoutCachingHeader(307)
  }

  private fun temporaryRedirectCachedWithCachingHeader(
    responseCode: Int,
    headerName: String,
    headerValue: String,
  ) {
    server.enqueue(
      MockResponse
        .Builder()
        .code(responseCode)
        .addHeader(headerName, headerValue)
        .addHeader("Location", "/a")
        .build(),
    )
    server.enqueue(
      MockResponse
        .Builder()
        .addHeader(headerName, headerValue)
        .body("a")
        .build(),
    )
    server.enqueue(
      MockResponse
        .Builder()
        .body("b")
        .build(),
    )
    server.enqueue(
      MockResponse
        .Builder()
        .body("c")
        .build(),
    )
    val url = server.url("/")
    assertThat(get(url).body.string()).isEqualTo("a")
    assertThat(get(url).body.string()).isEqualTo("a")
  }

  private fun temporaryRedirectNotCachedWithoutCachingHeader(responseCode: Int) {
    server.enqueue(
      MockResponse
        .Builder()
        .code(responseCode)
        .addHeader("Location", "/a")
        .build(),
    )
    server.enqueue(
      MockResponse
        .Builder()
        .body("a")
        .build(),
    )
    server.enqueue(
      MockResponse
        .Builder()
        .body("b")
        .build(),
    )
    val url = server.url("/")
    assertThat(get(url).body.string()).isEqualTo("a")
    assertThat(get(url).body.string()).isEqualTo("b")
  }

  /** https://github.com/lysine-dev/okhttp/issues/2198  */
  @Test
  fun cachedRedirect() {
    server.enqueue(
      MockResponse
        .Builder()
        .code(301)
        .addHeader("Cache-Control: max-age=60")
        .addHeader("Location: /bar")
        .build(),
    )
    server.enqueue(
      MockResponse
        .Builder()
        .body("ABC")
        .build(),
    )
    server.enqueue(
      MockResponse
        .Builder()
        .body("ABC")
        .build(),
    )
    val request1 = Request.Builder().url(server.url("/")).build()
    val response1 = client.newCall(request1).execute()
    assertThat(response1.body.string()).isEqualTo("ABC")
    val request2 = Request.Builder().url(server.url("/")).build()
    val response2 = client.newCall(request2).execute()
    assertThat(response2.body.string()).isEqualTo("ABC")
  }

  @Test
  fun serverDisconnectsPrematurelyWithContentLengthHeader() {
    testServerPrematureDisconnect(TransferKind.FIXED_LENGTH)
  }

  @Test
  fun serverDisconnectsPrematurelyWithChunkedEncoding() {
    testServerPrematureDisconnect(TransferKind.CHUNKED)
  }

  @Test
  fun serverDisconnectsPrematurelyWithNoLengthHeaders() {
    // Intentionally empty. This case doesn't make sense because there's no
    // such thing as a premature disconnect when the disconnect itself
    // indicates the end of the data stream.
  }

  private fun testServerPrematureDisconnect(transferKind: TransferKind) {
    val mockResponse = MockResponse.Builder()
    transferKind.setBody(mockResponse, "ABCDE\nFGHIJKLMNOPQRSTUVWXYZ", 16)
    server.enqueue(truncateViolently(mockResponse, 16).build())
    server.enqueue(
      MockResponse
        .Builder()
        .body("Request #2")
        .build(),
    )
    val bodySource = get(server.url("/")).body.source()
    assertThat(bodySource.readUtf8Line()).isEqualTo("ABCDE")
    bodySource.use {
      assertFailsWith<IOException> {
        bodySource.readUtf8(21)
      }
    }
    assertThat(cache.writeAbortCount()).isEqualTo(1)
    assertThat(cache.writeSuccessCount()).isEqualTo(0)
    val response = get(server.url("/"))
    assertThat(response.body.string()).isEqualTo("Request #2")
    assertThat(cache.writeAbortCount()).isEqualTo(1)
    assertThat(cache.writeSuccessCount()).isEqualTo(1)
  }

  @Test
  fun clientPrematureDisconnectWithContentLengthHeader() {
    testClientPrematureDisconnect(TransferKind.FIXED_LENGTH)
  }

  @Test
  fun clientPrematureDisconnectWithChunkedEncoding() {
    testClientPrematureDisconnect(TransferKind.CHUNKED)
  }

  @Test
  fun clientPrematureDisconnectWithNoLengthHeaders() {
    testClientPrematureDisconnect(TransferKind.END_OF_STREAM)
  }

  private fun testClientPrematureDisconnect(transferKind: TransferKind) {
    // Setting a low transfer speed ensures that stream discarding will time out.
    val builder =
      MockResponse
        .Builder()
        .throttleBody(6, 1, TimeUnit.SECONDS)
    transferKind.setBody(builder, "ABCDE\nFGHIJKLMNOPQRSTUVWXYZ", 1024)
    server.enqueue(builder.build())
    server.enqueue(
      MockResponse
        .Builder()
        .body("Request #2")
        .build(),
    )
    val response1 = get(server.url("/"))
    val source = response1.body.source()
    assertThat(source.readUtf8(5)).isEqualTo("ABCDE")
    source.close()
    assertFailsWith<IllegalStateException> {
      source.readByte()
    }
    assertThat(cache.writeAbortCount()).isEqualTo(1)
    assertThat(cache.writeSuccessCount()).isEqualTo(0)
    val response2 = get(server.url("/"))
    assertThat(response2.body.string()).isEqualTo("Request #2")
    assertThat(cache.writeAbortCount()).isEqualTo(1)
    assertThat(cache.writeSuccessCount()).isEqualTo(1)
  }

  @Test
  fun defaultExpirationDateFullyCachedForLessThan24Hours() {
    //      last modified: 105 seconds ago
    //             served:   5 seconds ago
    //   default lifetime: (105 - 5) / 10 = 10 seconds
    //            expires:  10 seconds from served date = 5 seconds from now
    server.enqueue(
      MockResponse
        .Builder()
        .addHeader("Last-Modified: " + formatDate(-105, TimeUnit.SECONDS))
        .addHeader("Date: " + formatDate(-5, TimeUnit.SECONDS))
        .body("A")
        .build(),
    )
    val url = server.url("/")
    val response1 = get(url)
    assertThat(response1.body.string()).isEqualTo("A")
    val response2 = get(url)
    assertThat(response2.body.string()).isEqualTo("A")
    assertThat(response2.header("Warning")).isNull()
  }

  @Test
  fun defaultExpirationDateConditionallyCached() {
    //      last modified: 115 seconds ago
    //             served:  15 seconds ago
    //   default lifetime: (115 - 15) / 10 = 10 seconds
    //            expires:  10 seconds from served date = 5 seconds ago
    val lastModifiedDate = formatDate(-115, TimeUnit.SECONDS)
    val conditionalRequest =
      assertConditionallyCached(
        MockResponse
          .Builder()
          .addHeader("Last-Modified: $lastModifiedDate")
          .addHeader("Date: " + formatDate(-15, TimeUnit.SECONDS))
          .build(),
      )
    assertThat(conditionalRequest.headers["If-Modified-Since"])
      .isEqualTo(lastModifiedDate)
  }

  @Test
  fun defaultExpirationDateFullyCachedForMoreThan24Hours() {
    //      last modified: 105 days ago
    //             served:   5 days ago
    //   default lifetime: (105 - 5) / 10 = 10 days
    //            expires:  10 days from served date = 5 days from now
    server.enqueue(
      MockResponse
        .Builder()
        .addHeader("Last-Modified: " + formatDate(-105, TimeUnit.DAYS))
        .addHeader("Date: " + formatDate(-5, TimeUnit.DAYS))
        .body("A")
        .build(),
    )
    assertThat(get(server.url("/")).body.string()).isEqualTo("A")
    val response = get(server.url("/"))
    assertThat(response.body.string()).isEqualTo("A")
    assertThat(response.header("Warning")).isEqualTo(
      "113 HttpURLConnection \"Heuristic expiration\"",
    )
  }

  @Test
  fun noDefaultExpirationForUrlsWithQueryString() {
    server.enqueue(
      MockResponse
        .Builder()
        .addHeader("Last-Modified: " + formatDate(-105, TimeUnit.SECONDS))
        .addHeader("Date: " + formatDate(-5, TimeUnit.SECONDS))
        .body("A")
        .build(),
    )
    server.enqueue(
      MockResponse
        .Builder()
        .body("B")
        .build(),
    )
    val url =
      server
        .url("/")
        .newBuilder()
        .addQueryParameter("foo", "bar")
        .build()
    assertThat(get(url).body.string()).isEqualTo("A")
    assertThat(get(url).body.string()).isEqualTo("B")
  }

  @Test
  fun expirationDateInThePastWithLastModifiedHeader() {
    val lastModifiedDate = formatDate(-2, TimeUnit.HOURS)
    val conditionalRequest =
      assertConditionallyCached(
        MockResponse
          .Builder()
          .addHeader("Last-Modified: $lastModifiedDate")
          .addHeader("Expires: " + formatDate(-1, TimeUnit.HOURS))
          .build(),
      )
    assertThat(conditionalRequest.headers["If-Modified-Since"])
      .isEqualTo(lastModifiedDate)
  }

  @Test
  fun expirationDateInThePastWithNoLastModifiedHeader() {
    assertNotCached(
      MockResponse
        .Builder()
        .addHeader("Expires: " + formatDate(-1, TimeUnit.HOURS))
        .build(),
    )
  }

  @Test
  fun expirationDateInTheFuture() {
    assertFullyCached(
      MockResponse
        .Builder()
        .addHeader("Expires: " + formatDate(1, TimeUnit.HOURS))
        .build(),
    )
  }

  @Test
  fun maxAgePreferredWithMaxAgeAndExpires() {
    assertFullyCached(
      MockResponse
        .Builder()
        .addHeader("Date: " + formatDate(0, TimeUnit.HOURS))
        .addHeader("Expires: " + formatDate(-1, TimeUnit.HOURS))
        .addHeader("Cache-Control: max-age=60")
        .build(),
    )
  }

  @Test
  fun maxAgeInThePastWithDateAndLastModifiedHeaders() {
    val lastModifiedDate = formatDate(-2, TimeUnit.HOURS)
    val conditionalRequest =
      assertConditionallyCached(
        MockResponse
          .Builder()
          .addHeader("Date: " + formatDate(-120, TimeUnit.SECONDS))
          .addHeader("Last-Modified: $lastModifiedDate")
          .addHeader("Cache-Control: max-age=60")
          .build(),
      )
    assertThat(conditionalRequest.headers["If-Modified-Since"])
      .isEqualTo(lastModifiedDate)
  }

  @Test
  fun maxAgeInThePastWithDateHeaderButNoLastModifiedHeader() {
    // Chrome interprets max-age relative to the local clock. Both our cache
    // and Firefox both use the earlier of the local and server's clock.
    assertNotCached(
      MockResponse
        .Builder()
        .addHeader("Date: " + formatDate(-120, TimeUnit.SECONDS))
        .addHeader("Cache-Control: max-age=60")
        .build(),
    )
  }

  @Test
  fun maxAgeInTheFutureWithDateHeader() {
    assertFullyCached(
      MockResponse
        .Builder()
        .addHeader("Date: " + formatDate(0, TimeUnit.HOURS))
        .addHeader("Cache-Control: max-age=60")
        .build(),
    )
  }

  @Test
  fun maxAgeInTheFutureWithNoDateHeader() {
    assertFullyCached(
      MockResponse
        .Builder()
        .addHeader("Cache-Control: max-age=60")
        .build(),
    )
  }

  @Test
  fun maxAgeWithLastModifiedButNoServedDate() {
    assertFullyCached(
      MockResponse
        .Builder()
        .addHeader("Last-Modified: " + formatDate(-120, TimeUnit.SECONDS))
        .addHeader("Cache-Control: max-age=60")
        .build(),
    )
  }

  @Test
  fun maxAgeInTheFutureWithDateAndLastModifiedHeaders() {
    assertFullyCached(
      MockResponse
        .Builder()
        .addHeader("Last-Modified: " + formatDate(-120, TimeUnit.SECONDS))
        .addHeader("Date: " + formatDate(0, TimeUnit.SECONDS))
        .addHeader("Cache-Control: max-age=60")
        .build(),
    )
  }

  @Test
  fun maxAgePreferredOverLowerSharedMaxAge() {
    assertFullyCached(
      MockResponse
        .Builder()
        .addHeader("Date: " + formatDate(-2, TimeUnit.MINUTES))
        .addHeader("Cache-Control: s-maxage=60")
        .addHeader("Cache-Control: max-age=180")
        .build(),
    )
  }

  @Test
  fun maxAgePreferredOverHigherMaxAge() {
    assertNotCached(
      MockResponse
        .Builder()
        .addHeader("Date: " + formatDate(-2, TimeUnit.MINUTES))
        .addHeader("Cache-Control: s-maxage=180")
        .addHeader("Cache-Control: max-age=60")
        .build(),
    )
  }

  @Test
  fun requestMethodOptionsIsNotCached() {
    testRequestMethod("OPTIONS", false)
  }

  @Test
  fun requestMethodGetIsCached() {
    testRequestMethod("GET", true)
  }

  @Test
  fun requestMethodQueryIsCached() {
    testRequestMethod("QUERY", false)
  }

  @Test
  fun requestMethodQueryIsCachedWithOverride() {
    testRequestMethod("QUERY", true, withOverride = true)
  }

  @Test
  fun requestMethodHeadIsNotCached() {
    // We could support this but choose not to for implementation simplicity
    testRequestMethod("HEAD", false)
  }

  @Test
  fun requestMethodPostIsNotCached() {
    // We could support this but choose not to for implementation simplicity
    testRequestMethod("POST", false)
  }

  @Test
  fun requestMethodPostIsNotCachedUnlessOverridden() {
    // Supported via cacheUrlOverride
    testRequestMethod("POST", true, withOverride = true)
  }

  @Test
  fun requestMethodPutIsNotCached() {
    testRequestMethod("PUT", false)
  }

  @Test
  fun requestMethodPutIsNotCachedEvenWithOverride() {
    testRequestMethod("PUT", false, withOverride = true)
  }

  @Test
  fun requestMethodDeleteIsNotCached() {
    testRequestMethod("DELETE", false)
  }

  @Test
  fun requestMethodTraceIsNotCached() {
    testRequestMethod("TRACE", false)
  }

  private fun testRequestMethod(
    requestMethod: String,
    expectCached: Boolean,
    withOverride: Boolean = false,
  ) {
    // 1. Seed the cache (potentially).
    // 2. Expect a cache hit or miss.
    server.enqueue(
      MockResponse
        .Builder()
        .addHeader("Expires: " + formatDate(1, TimeUnit.HOURS))
        .addHeader("X-Response-ID: 1")
        .build(),
    )
    server.enqueue(
      MockResponse
        .Builder()
        .addHeader("X-Response-ID: 2")
        .build(),
    )
    val url = server.url("/")
    val request =
      Request
        .Builder()
        .url(url)
        .apply {
          if (withOverride) {
            cacheUrlOverride(url)
          }
        }.method(requestMethod, requestBodyOrNull(requestMethod))
        .build()
    val response1 = client.newCall(request).execute()
    response1.body.close()
    assertThat(response1.header("X-Response-ID")).isEqualTo("1")
    val response2 = client.newCall(request).execute()
    response2.body.close()
    if (expectCached) {
      assertThat(response2.header("X-Response-ID")).isEqualTo("1")
    } else {
      assertThat(response2.header("X-Response-ID")).isEqualTo("2")
    }
    if (!expectCached) {
      server.enqueue(
        MockResponse
          .Builder()
          .addHeader("X-Response-ID: 3")
          .build(),
      )
      val response3 = get(url)
      response3.body.close()
      assertThat(response3.header("X-Response-ID")).isEqualTo("3")
    }
  }

  private fun requestBodyOrNull(requestMethod: String): RequestBody? =
    if (requestMethod == "POST" ||
      requestMethod == "PUT" ||
      requestMethod == "QUERY"
    ) {
      "foo".toRequestBody("text/plain".toMediaType())
    } else {
      null
    }

  @Test
  fun postInvalidatesCache() {
    testMethodInvalidates("POST")
  }

  @Test
  fun putInvalidatesCache() {
    testMethodInvalidates("PUT")
  }

  @Test
  fun deleteMethodInvalidatesCache() {
    testMethodInvalidates("DELETE")
  }

  private fun testMethodInvalidates(requestMethod: String) {
    // 1. Seed the cache.
    // 2. Invalidate it.
    // 3. Expect a cache miss.
    server.enqueue(
      MockResponse
        .Builder()
        .body("A")
        .addHeader("Expires: " + formatDate(1, TimeUnit.HOURS))
        .build(),
    )
    server.enqueue(
      MockResponse
        .Builder()
        .body("B")
        .build(),
    )
    server.enqueue(
      MockResponse
        .Builder()
        .body("C")
        .build(),
    )
    val url = server.url("/")
    assertThat(get(url).body.string()).isEqualTo("A")
    val request =
      Request
        .Builder()
        .url(url)
        .method(requestMethod, requestBodyOrNull(requestMethod))
        .build()
    val invalidate = client.newCall(request).execute()
    assertThat(invalidate.body.string()).isEqualTo("B")
    assertThat(get(url).body.string()).isEqualTo("C")
  }

  @Test
  fun postInvalidatesCacheWithUncacheableResponse() {
    // 1. Seed the cache.
    // 2. Invalidate it with an uncacheable response.
    // 3. Expect a cache miss.
    server.enqueue(
      MockResponse
        .Builder()
        .body("A")
        .addHeader("Expires: " + formatDate(1, TimeUnit.HOURS))
        .build(),
    )
    server.enqueue(
      MockResponse
        .Builder()
        .body("B")
        .code(500)
        .build(),
    )
    server.enqueue(
      MockResponse
        .Builder()
        .body("C")
        .build(),
    )
    val url = server.url("/")
    assertThat(get(url).body.string()).isEqualTo("A")
    val request =
      Request
        .Builder()
        .url(url)
        .method("POST", requestBodyOrNull("POST"))
        .build()
    val invalidate = client.newCall(request).execute()
    assertThat(invalidate.body.string()).isEqualTo("B")
    assertThat(get(url).body.string()).isEqualTo("C")
  }

  @Test
  fun putInvalidatesWithNoContentResponse() {
    // 1. Seed the cache.
    // 2. Invalidate it.
    // 3. Expect a cache miss.
    server.enqueue(
      MockResponse
        .Builder()
        .body("A")
        .addHeader("Expires: " + formatDate(1, TimeUnit.HOURS))
        .build(),
    )
    server.enqueue(
      MockResponse
        .Builder()
        .clearHeaders()
        .code(HttpURLConnection.HTTP_NO_CONTENT)
        .build(),
    )
    server.enqueue(
      MockResponse
        .Builder()
        .body("C")
        .build(),
    )
    val url = server.url("/")
    assertThat(get(url).body.string()).isEqualTo("A")
    val request =
      Request
        .Builder()
        .url(url)
        .put("foo".toRequestBody("text/plain".toMediaType()))
        .build()
    val invalidate = client.newCall(request).execute()
    assertThat(invalidate.body.string()).isEqualTo("")
    assertThat(get(url).body.string()).isEqualTo("C")
  }

  @Test
  fun etag() {
    val conditionalRequest =
      assertConditionallyCached(
        MockResponse
          .Builder()
          .addHeader("ETag: v1")
          .build(),
      )
    assertThat(conditionalRequest.headers["If-None-Match"]).isEqualTo("v1")
  }

  /** If both If-Modified-Since and If-None-Match conditions apply, send only If-None-Match.  */
  @Test
  fun etagAndExpirationDateInThePast() {
    val lastModifiedDate = formatDate(-2, TimeUnit.HOURS)
    val conditionalRequest =
      assertConditionallyCached(
        MockResponse
          .Builder()
          .addHeader("ETag: v1")
          .addHeader("Last-Modified: $lastModifiedDate")
          .addHeader("Expires: " + formatDate(-1, TimeUnit.HOURS))
          .build(),
      )
    assertThat(conditionalRequest.headers["If-None-Match"]).isEqualTo("v1")
    assertThat(conditionalRequest.headers["If-Modified-Since"]).isNull()
  }

  @Test
  fun etagAndExpirationDateInTheFuture() {
    assertFullyCached(
      MockResponse
        .Builder()
        .addHeader("ETag: v1")
        .addHeader("Last-Modified: " + formatDate(-2, TimeUnit.HOURS))
        .addHeader("Expires: " + formatDate(1, TimeUnit.HOURS))
        .build(),
    )
  }

  @Test
  fun cacheControlNoCache() {
    assertNotCached(
      MockResponse
        .Builder()
        .addHeader("Cache-Control: no-cache")
        .build(),
    )
  }

  @Test
  fun cacheControlNoCacheAndExpirationDateInTheFuture() {
    val lastModifiedDate = formatDate(-2, TimeUnit.HOURS)
    val conditionalRequest =
      assertConditionallyCached(
        MockResponse
          .Builder()
          .addHeader("Last-Modified: $lastModifiedDate")
          .addHeader("Expires: " + formatDate(1, TimeUnit.HOURS))
          .addHeader("Cache-Control: no-cache")
          .build(),
      )
    assertThat(conditionalRequest.headers["If-Modified-Since"])
      .isEqualTo(lastModifiedDate)
  }

  @Test
  fun pragmaNoCache() {
    assertNotCached(
      MockResponse
        .Builder()
        .addHeader("Pragma: no-cache")
        .build(),
    )
  }

  @Test
  fun pragmaNoCacheAndExpirationDateInTheFuture() {
    val lastModifiedDate = formatDate(-2, TimeUnit.HOURS)
    val conditionalRequest =
      assertConditionallyCached(
        MockResponse
          .Builder()
          .addHeader("Last-Modified: $lastModifiedDate")
          .addHeader("Expires: " + formatDate(1, TimeUnit.HOURS))
          .addHeader("Pragma: no-cache")
          .build(),
      )
    assertThat(conditionalRequest.headers["If-Modified-Since"])
      .isEqualTo(lastModifiedDate)
  }

  @Test
  fun cacheControlNoStore() {
    assertNotCached(
      MockResponse
        .Builder()
        .addHeader("Cache-Control: no-store")
        .build(),
    )
  }

  @Test
  fun cacheControlNoStoreAndExpirationDateInTheFuture() {
    assertNotCached(
      MockResponse
        .Builder()
        .addHeader("Last-Modified: " + formatDate(-2, TimeUnit.HOURS))
        .addHeader("Expires: " + formatDate(1, TimeUnit.HOURS))
        .addHeader("Cache-Control: no-store")
        .build(),
    )
  }

  @Test
  fun partialRangeResponsesDoNotCorruptCache() {
    // 1. Request a range.
    // 2. Request a full document, expecting a cache miss.
    server.enqueue(
      MockResponse
        .Builder()
        .body("AA")
        .code(HttpURLConnection.HTTP_PARTIAL)
        .addHeader("Expires: " + formatDate(1, TimeUnit.HOURS))
        .addHeader("Content-Range: bytes 1000-1001/2000")
        .build(),
    )
    server.enqueue(
      MockResponse
        .Builder()
        .body("BB")
        .build(),
    )
    val url = server.url("/")
    val request =
      Request
        .Builder()
        .url(url)
        .header("Range", "bytes=1000-1001")
        .build()
    val range = client.newCall(request).execute()
    assertThat(range.body.string()).isEqualTo("AA")
    assertThat(get(url).body.string()).isEqualTo("BB")
  }

  /**
   * When the server returns a full response body we will store it and return it regardless of what
   * its Last-Modified date is. This behavior was different prior to OkHttp 3.5 when we would prefer
   * the response with the later Last-Modified date.
   *
   * https://github.com/lysine-dev/okhttp/issues/2886
   */
  @Test
  fun serverReturnsDocumentOlderThanCache() {
    server.enqueue(
      MockResponse
        .Builder()
        .body("A")
        .addHeader("Last-Modified: " + formatDate(-2, TimeUnit.HOURS))
        .addHeader("Expires: " + formatDate(-1, TimeUnit.HOURS))
        .build(),
    )
    server.enqueue(
      MockResponse
        .Builder()
        .body("B")
        .addHeader("Last-Modified: " + formatDate(-4, TimeUnit.HOURS))
        .build(),
    )
    server.enqueue(
      MockResponse
        .Builder()
        .code(HttpURLConnection.HTTP_NOT_MODIFIED)
        .build(),
    )
    val url = server.url("/")
    assertThat(get(url).body.string()).isEqualTo("A")
    assertThat(get(url).body.string()).isEqualTo("B")
    assertThat(get(url).body.string()).isEqualTo("B")
  }

  @Test
  fun clientSideNoStore() {
    server.enqueue(
      MockResponse
        .Builder()
        .addHeader("Cache-Control: max-age=60")
        .body("A")
        .build(),
    )
    server.enqueue(
      MockResponse
        .Builder()
        .addHeader("Cache-Control: max-age=60")
        .body("B")
        .build(),
    )
    val request1 =
      Request
        .Builder()
        .url(server.url("/"))
        .cacheControl(CacheControl.Builder().noStore().build())
        .build()
    val response1 = client.newCall(request1).execute()
    assertThat(response1.body.string()).isEqualTo("A")
    val request2 =
      Request
        .Builder()
        .url(server.url("/"))
        .build()
    val response2 = client.newCall(request2).execute()
    assertThat(response2.body.string()).isEqualTo("B")
  }

  @Test
  fun nonIdentityEncodingAndConditionalCache() {
    assertNonIdentityEncodingCached(
      MockResponse
        .Builder()
        .addHeader("Last-Modified: " + formatDate(-2, TimeUnit.HOURS))
        .addHeader("Expires: " + formatDate(-1, TimeUnit.HOURS))
        .build(),
    )
  }

  @Test
  fun nonIdentityEncodingAndFullCache() {
    assertNonIdentityEncodingCached(
      MockResponse
        .Builder()
        .addHeader("Last-Modified: " + formatDate(-2, TimeUnit.HOURS))
        .addHeader("Expires: " + formatDate(1, TimeUnit.HOURS))
        .build(),
    )
  }

  private fun assertNonIdentityEncodingCached(response: MockResponse) {
    server.enqueue(
      response
        .newBuilder()
        .body(gzip("ABCABCABC"))
        .addHeader("Content-Encoding: gzip")
        .build(),
    )
    server.enqueue(
      MockResponse
        .Builder()
        .code(HttpURLConnection.HTTP_NOT_MODIFIED)
        .build(),
    )
    server.enqueue(
      MockResponse
        .Builder()
        .code(HttpURLConnection.HTTP_NOT_MODIFIED)
        .build(),
    )

    // At least three request/response pairs are required because after the first request is cached
    // a different execution path might be taken. Thus modifications to the cache applied during
    // the second request might not be visible until another request is performed.
    assertThat(get(server.url("/")).body.string()).isEqualTo("ABCABCABC")
    assertThat(get(server.url("/")).body.string()).isEqualTo("ABCABCABC")
    assertThat(get(server.url("/")).body.string()).isEqualTo("ABCABCABC")
  }

  @Test
  fun previouslyNotGzippedContentIsNotModifiedAndSpecifiesGzipEncoding() {
    server.enqueue(
      MockResponse
        .Builder()
        .body("ABCABCABC")
        .addHeader("Content-Type: text/plain")
        .addHeader("Last-Modified: " + formatDate(-2, TimeUnit.HOURS))
        .addHeader("Expires: " + formatDate(-1, TimeUnit.HOURS))
        .build(),
    )
    server.enqueue(
      MockResponse
        .Builder()
        .code(HttpURLConnection.HTTP_NOT_MODIFIED)
        .addHeader("Content-Type: text/plain")
        .addHeader("Content-Encoding: gzip")
        .build(),
    )
    server.enqueue(
      MockResponse
        .Builder()
        .body("DEFDEFDEF")
        .build(),
    )
    assertThat(get(server.url("/")).body.string()).isEqualTo("ABCABCABC")
    assertThat(get(server.url("/")).body.string()).isEqualTo("ABCABCABC")
    assertThat(get(server.url("/")).body.string()).isEqualTo("DEFDEFDEF")
  }

  @Test
  fun changedGzippedContentIsNotModifiedAndSpecifiesNewEncoding() {
    server.enqueue(
      MockResponse
        .Builder()
        .body(gzip("ABCABCABC"))
        .addHeader("Content-Type: text/plain")
        .addHeader("Last-Modified: " + formatDate(-2, TimeUnit.HOURS))
        .addHeader("Expires: " + formatDate(-1, TimeUnit.HOURS))
        .addHeader("Content-Encoding: gzip")
        .build(),
    )
    server.enqueue(
      MockResponse
        .Builder()
        .code(HttpURLConnection.HTTP_NOT_MODIFIED)
        .addHeader("Content-Type: text/plain")
        .addHeader("Content-Encoding: identity")
        .build(),
    )
    server.enqueue(
      MockResponse
        .Builder()
        .body("DEFDEFDEF")
        .build(),
    )
    assertThat(get(server.url("/")).body.string()).isEqualTo("ABCABCABC")
    assertThat(get(server.url("/")).body.string()).isEqualTo("ABCABCABC")
    assertThat(get(server.url("/")).body.string()).isEqualTo("DEFDEFDEF")
  }

  @Test
  fun notModifiedSpecifiesEncoding() {
    server.enqueue(
      MockResponse
        .Builder()
        .body(gzip("ABCABCABC"))
        .addHeader("Content-Encoding: gzip")
        .addHeader("Last-Modified: " + formatDate(-2, TimeUnit.HOURS))
        .addHeader("Expires: " + formatDate(-1, TimeUnit.HOURS))
        .build(),
    )
    server.enqueue(
      MockResponse
        .Builder()
        .code(HttpURLConnection.HTTP_NOT_MODIFIED)
        .addHeader("Content-Encoding: gzip")
        .build(),
    )
    server.enqueue(
      MockResponse
        .Builder()
        .body("DEFDEFDEF")
        .build(),
    )
    assertThat(get(server.url("/")).body.string()).isEqualTo("ABCABCABC")
    assertThat(get(server.url("/")).body.string()).isEqualTo("ABCABCABC")
    assertThat(get(server.url("/")).body.string()).isEqualTo("DEFDEFDEF")
  }

  /** https://github.com/lysine-dev/okhttp/issues/947  */
  @Test
  fun gzipAndVaryOnAcceptEncoding() {
    server.enqueue(
      MockResponse
        .Builder()
        .body(gzip("ABCABCABC"))
        .addHeader("Content-Encoding: gzip")
        .addHeader("Vary: Accept-Encoding")
        .addHeader("Cache-Control: max-age=60")
        .build(),
    )
    server.enqueue(
      MockResponse
        .Builder()
        .body("FAIL")
        .build(),
    )
    assertThat(get(server.url("/")).body.string()).isEqualTo("ABCABCABC")
    assertThat(get(server.url("/")).body.string()).isEqualTo("ABCABCABC")
  }

  @Test
  fun conditionalCacheHitIsNotDoublePooled() {
    clientTestRule.ensureAllConnectionsReleased()
    server.enqueue(
      MockResponse
        .Builder()
        .addHeader("ETag: v1")
        .body("A")
        .build(),
    )
    server.enqueue(
      MockResponse
        .Builder()
        .clearHeaders()
        .code(HttpURLConnection.HTTP_NOT_MODIFIED)
        .build(),
    )
    assertThat(get(server.url("/")).body.string()).isEqualTo("A")
    assertThat(get(server.url("/")).body.string()).isEqualTo("A")
    assertThat(client.connectionPool.idleConnectionCount()).isEqualTo(1)
  }

  @Test
  fun expiresDateBeforeModifiedDate() {
    assertConditionallyCached(
      MockResponse
        .Builder()
        .addHeader("Last-Modified: " + formatDate(-1, TimeUnit.HOURS))
        .addHeader("Expires: " + formatDate(-2, TimeUnit.HOURS))
        .build(),
    )
  }

  @Test
  fun requestMaxAge() {
    server.enqueue(
      MockResponse
        .Builder()
        .body("A")
        .addHeader("Last-Modified: " + formatDate(-2, TimeUnit.HOURS))
        .addHeader("Date: " + formatDate(-1, TimeUnit.MINUTES))
        .addHeader("Expires: " + formatDate(1, TimeUnit.HOURS))
        .build(),
    )
    server.enqueue(
      MockResponse
        .Builder()
        .body("B")
        .build(),
    )
    assertThat(get(server.url("/")).body.string()).isEqualTo("A")
    val request =
      Request
        .Builder()
        .url(server.url("/"))
        .header("Cache-Control", "max-age=30")
        .build()
    val response = client.newCall(request).execute()
    assertThat(response.body.string()).isEqualTo("B")
  }

  @Test
  fun requestMinFresh() {
    server.enqueue(
      MockResponse
        .Builder()
        .body("A")
        .addHeader("Cache-Control: max-age=60")
        .addHeader("Date: " + formatDate(0, TimeUnit.MINUTES))
        .build(),
    )
    server.enqueue(
      MockResponse
        .Builder()
        .body("B")
        .build(),
    )
    assertThat(get(server.url("/")).body.string()).isEqualTo("A")
    val request =
      Request
        .Builder()
        .url(server.url("/"))
        .header("Cache-Control", "min-fresh=120")
        .build()
    val response = client.newCall(request).execute()
    assertThat(response.body.string()).isEqualTo("B")
  }

  @Test
  fun requestMaxStale() {
    server.enqueue(
      MockResponse
        .Builder()
        .body("A")
        .addHeader("Cache-Control: max-age=120")
        .addHeader("Date: " + formatDate(-4, TimeUnit.MINUTES))
        .build(),
    )
    server.enqueue(
      MockResponse
        .Builder()
        .body("B")
        .build(),
    )
    assertThat(get(server.url("/")).body.string()).isEqualTo("A")
    val request =
      Request
        .Builder()
        .url(server.url("/"))
        .header("Cache-Control", "max-stale=180")
        .build()
    val response = client.newCall(request).execute()
    assertThat(response.body.string()).isEqualTo("A")
    assertThat(response.header("Warning")).isEqualTo(
      "110 HttpURLConnection \"Response is stale\"",
    )
  }

  @Test
  fun requestMaxStaleDirectiveWithNoValue() {
    // Add a stale response to the cache.
    server.enqueue(
      MockResponse
        .Builder()
        .body("A")
        .addHeader("Cache-Control: max-age=120")
        .addHeader("Date: " + formatDate(-4, TimeUnit.MINUTES))
        .build(),
    )
    server.enqueue(
      MockResponse
        .Builder()
        .body("B")
        .build(),
    )
    assertThat(get(server.url("/")).body.string()).isEqualTo("A")

    // With max-stale, we'll return that stale response.
    val request =
      Request
        .Builder()
        .url(server.url("/"))
        .header("Cache-Control", "max-stale")
        .build()
    val response = client.newCall(request).execute()
    assertThat(response.body.string()).isEqualTo("A")
    assertThat(response.header("Warning")).isEqualTo(
      "110 HttpURLConnection \"Response is stale\"",
    )
  }

  @Test
  fun requestMaxStaleNotHonoredWithMustRevalidate() {
    server.enqueue(
      MockResponse
        .Builder()
        .body("A")
        .addHeader("Cache-Control: max-age=120, must-revalidate")
        .addHeader("Date: " + formatDate(-4, TimeUnit.MINUTES))
        .build(),
    )
    server.enqueue(
      MockResponse
        .Builder()
        .body("B")
        .build(),
    )
    assertThat(get(server.url("/")).body.string()).isEqualTo("A")
    val request =
      Request
        .Builder()
        .url(server.url("/"))
        .header("Cache-Control", "max-stale=180")
        .build()
    val response = client.newCall(request).execute()
    assertThat(response.body.string()).isEqualTo("B")
  }

  @Test
  fun requestOnlyIfCachedWithNoResponseCached() {
    // (no responses enqueued)
    val request =
      Request
        .Builder()
        .url(server.url("/"))
        .header("Cache-Control", "only-if-cached")
        .build()
    val response = client.newCall(request).execute()
    assertThat(response.body.source().exhausted()).isTrue()
    assertThat(response.code).isEqualTo(504)
    assertThat(cache.requestCount()).isEqualTo(1)
    assertThat(cache.networkCount()).isEqualTo(0)
    assertThat(cache.hitCount()).isEqualTo(0)
  }

  @Test
  fun requestOnlyIfCachedWithFullResponseCached() {
    server.enqueue(
      MockResponse
        .Builder()
        .body("A")
        .addHeader("Cache-Control: max-age=30")
        .addHeader("Date: " + formatDate(0, TimeUnit.MINUTES))
        .build(),
    )
    assertThat(get(server.url("/")).body.string()).isEqualTo("A")
    val request =
      Request
        .Builder()
        .url(server.url("/"))
        .header("Cache-Control", "only-if-cached")
        .build()
    val response = client.newCall(request).execute()
    assertThat(response.body.string()).isEqualTo("A")
    assertThat(cache.requestCount()).isEqualTo(2)
    assertThat(cache.networkCount()).isEqualTo(1)
    assertThat(cache.hitCount()).isEqualTo(1)
  }

  @Test
  fun requestOnlyIfCachedWithConditionalResponseCached() {
    server.enqueue(
      MockResponse
        .Builder()
        .body("A")
        .addHeader("Cache-Control: max-age=30")
        .addHeader("Date: " + formatDate(-1, TimeUnit.MINUTES))
        .build(),
    )
    assertThat(get(server.url("/")).body.string()).isEqualTo("A")
    val request =
      Request
        .Builder()
        .url(server.url("/"))
        .header("Cache-Control", "only-if-cached")
        .build()
    val response = client.newCall(request).execute()
    assertThat(response.body.source().exhausted()).isTrue()
    assertThat(response.code).isEqualTo(504)
    assertThat(cache.requestCount()).isEqualTo(2)
    assertThat(cache.networkCount()).isEqualTo(1)
    assertThat(cache.hitCount()).isEqualTo(0)
  }

  @Test
  fun requestOnlyIfCachedWithUnhelpfulResponseCached() {
    server.enqueue(
      MockResponse
        .Builder()
        .body("A")
        .build(),
    )
    assertThat(get(server.url("/")).body.string()).isEqualTo("A")
    val request =
      Request
        .Builder()
        .url(server.url("/"))
        .header("Cache-Control", "only-if-cached")
        .build()
    val response = client.newCall(request).execute()
    assertThat(response.body.source().exhausted()).isTrue()
    assertThat(response.code).isEqualTo(504)
    assertThat(cache.requestCount()).isEqualTo(2)
    assertThat(cache.networkCount()).isEqualTo(1)
    assertThat(cache.hitCount()).isEqualTo(0)
  }

  @Test
  fun requestCacheControlNoCache() {
    server.enqueue(
      MockResponse
        .Builder()
        .addHeader("Last-Modified: " + formatDate(-120, TimeUnit.SECONDS))
        .addHeader("Date: " + formatDate(0, TimeUnit.SECONDS))
        .addHeader("Cache-Control: max-age=60")
        .body("A")
        .build(),
    )
    server.enqueue(
      MockResponse
        .Builder()
        .body("B")
        .build(),
    )
    val url = server.url("/")
    assertThat(get(url).body.string()).isEqualTo("A")
    val request =
      Request
        .Builder()
        .url(url)
        .header("Cache-Control", "no-cache")
        .build()
    val response = client.newCall(request).execute()
    assertThat(response.body.string()).isEqualTo("B")
  }

  @Test
  fun requestPragmaNoCache() {
    server.enqueue(
      MockResponse
        .Builder()
        .addHeader("Last-Modified: " + formatDate(-120, TimeUnit.SECONDS))
        .addHeader("Date: " + formatDate(0, TimeUnit.SECONDS))
        .addHeader("Cache-Control: max-age=60")
        .body("A")
        .build(),
    )
    server.enqueue(
      MockResponse
        .Builder()
        .body("B")
        .build(),
    )
    val url = server.url("/")
    assertThat(get(url).body.string()).isEqualTo("A")
    val request =
      Request
        .Builder()
        .url(url)
        .header("Pragma", "no-cache")
        .build()
    val response = client.newCall(request).execute()
    assertThat(response.body.string()).isEqualTo("B")
  }

  @Test
  fun clientSuppliedIfModifiedSinceWithCachedResult() {
    val response =
      MockResponse
        .Builder()
        .addHeader("ETag: v3")
        .addHeader("Cache-Control: max-age=0")
        .build()
    val ifModifiedSinceDate = formatDate(-24, TimeUnit.HOURS)
    val request =
      assertClientSuppliedCondition(response, "If-Modified-Since", ifModifiedSinceDate)
    assertThat(request.headers["If-Modified-Since"]).isEqualTo(ifModifiedSinceDate)
    assertThat(request.headers["If-None-Match"]).isNull()
  }

  @Test
  fun clientSuppliedIfNoneMatchSinceWithCachedResult() {
    val lastModifiedDate = formatDate(-3, TimeUnit.MINUTES)
    val response =
      MockResponse
        .Builder()
        .addHeader("Last-Modified: $lastModifiedDate")
        .addHeader("Date: " + formatDate(-2, TimeUnit.MINUTES))
        .addHeader("Cache-Control: max-age=0")
        .build()
    val request = assertClientSuppliedCondition(response, "If-None-Match", "v1")
    assertThat(request.headers["If-None-Match"]).isEqualTo("v1")
    assertThat(request.headers["If-Modified-Since"]).isNull()
  }

  private fun assertClientSuppliedCondition(
    seed: MockResponse,
    conditionName: String,
    conditionValue: String,
  ): RecordedRequest {
    server.enqueue(
      seed
        .newBuilder()
        .body("A")
        .build(),
    )
    server.enqueue(
      MockResponse
        .Builder()
        .code(HttpURLConnection.HTTP_NOT_MODIFIED)
        .build(),
    )
    val url = server.url("/")
    assertThat(get(url).body.string()).isEqualTo("A")
    val request =
      Request
        .Builder()
        .url(url)
        .header(conditionName, conditionValue)
        .build()
    val response = client.newCall(request).execute()
    assertThat(response.code).isEqualTo(HttpURLConnection.HTTP_NOT_MODIFIED)
    assertThat(response.body.string()).isEqualTo("")
    server.takeRequest() // seed
    return server.takeRequest()
  }

  /**
   * For Last-Modified and Date headers, we should echo the date back in the exact format we were
   * served.
   */
  @Test
  fun retainServedDateFormat() {
    // Serve a response with a non-standard date format that OkHttp supports.
    val lastModifiedDate = Date(System.currentTimeMillis() + TimeUnit.HOURS.toMillis(-1))
    val servedDate = Date(System.currentTimeMillis() + TimeUnit.HOURS.toMillis(-2))
    val dateFormat: DateFormat = SimpleDateFormat("EEE dd-MMM-yyyy HH:mm:ss z", Locale.US)
    dateFormat.timeZone = TimeZone.getTimeZone("America/New_York")
    val lastModifiedString = dateFormat.format(lastModifiedDate)
    val servedString = dateFormat.format(servedDate)

    // This response should be conditionally cached.
    server.enqueue(
      MockResponse
        .Builder()
        .addHeader("Last-Modified: $lastModifiedString")
        .addHeader("Expires: $servedString")
        .body("A")
        .build(),
    )
    server.enqueue(
      MockResponse
        .Builder()
        .code(HttpURLConnection.HTTP_NOT_MODIFIED)
        .build(),
    )
    assertThat(get(server.url("/")).body.string()).isEqualTo("A")
    assertThat(get(server.url("/")).body.string()).isEqualTo("A")

    // The first request has no conditions.
    val request1 = server.takeRequest()
    assertThat(request1.headers["If-Modified-Since"]).isNull()

    // The 2nd request uses the server's date format.
    val request2 = server.takeRequest()
    assertThat(request2.headers["If-Modified-Since"]).isEqualTo(lastModifiedString)
  }

  @Test
  fun clientSuppliedConditionWithoutCachedResult() {
    server.enqueue(
      MockResponse
        .Builder()
        .code(HttpURLConnection.HTTP_NOT_MODIFIED)
        .build(),
    )
    val request =
      Request
        .Builder()
        .url(server.url("/"))
        .header("If-Modified-Since", formatDate(-24, TimeUnit.HOURS))
        .build()
    val response = client.newCall(request).execute()
    assertThat(response.code).isEqualTo(HttpURLConnection.HTTP_NOT_MODIFIED)
    assertThat(response.body.string()).isEqualTo("")
  }

  @Test
  fun authorizationRequestFullyCached() {
    server.enqueue(
      MockResponse
        .Builder()
        .addHeader("Cache-Control: max-age=60")
        .body("A")
        .build(),
    )
    server.enqueue(
      MockResponse
        .Builder()
        .body("B")
        .build(),
    )
    val url = server.url("/")
    val request =
      Request
        .Builder()
        .url(url)
        .header("Authorization", "password")
        .build()
    val response = client.newCall(request).execute()
    assertThat(response.body.string()).isEqualTo("A")
    assertThat(get(url).body.string()).isEqualTo("A")
  }

  @Test
  fun contentLocationDoesNotPopulateCache() {
    server.enqueue(
      MockResponse
        .Builder()
        .addHeader("Cache-Control: max-age=60")
        .addHeader("Content-Location: /bar")
        .body("A")
        .build(),
    )
    server.enqueue(
      MockResponse
        .Builder()
        .body("B")
        .build(),
    )
    assertThat(get(server.url("/foo")).body.string()).isEqualTo("A")
    assertThat(get(server.url("/bar")).body.string()).isEqualTo("B")
  }

  @Test
  fun connectionIsReturnedToPoolAfterConditionalSuccess() {
    server.enqueue(
      MockResponse
        .Builder()
        .addHeader("Last-Modified: " + formatDate(-1, TimeUnit.HOURS))
        .addHeader("Cache-Control: max-age=0")
        .body("A")
        .build(),
    )
    server.enqueue(
      MockResponse
        .Builder()
        .code(HttpURLConnection.HTTP_NOT_MODIFIED)
        .build(),
    )
    server.enqueue(
      MockResponse
        .Builder()
        .body("B")
        .build(),
    )
    assertThat(get(server.url("/a")).body.string()).isEqualTo("A")
    assertThat(get(server.url("/a")).body.string()).isEqualTo("A")
    assertThat(get(server.url("/b")).body.string()).isEqualTo("B")
    val c0e0 = server.takeRequest()
    assertThat(c0e0.connectionIndex).isEqualTo(0)
    assertThat(c0e0.exchangeIndex).isEqualTo(0)
    val c0e1 = server.takeRequest()
    assertThat(c0e1.connectionIndex).isEqualTo(0)
    assertThat(c0e1.exchangeIndex).isEqualTo(1)
    val c0e2 = server.takeRequest()
    assertThat(c0e2.connectionIndex).isEqualTo(0)
    assertThat(c0e2.exchangeIndex).isEqualTo(2)
  }

  @Test
  fun statisticsConditionalCacheMiss() {
    server.enqueue(
      MockResponse
        .Builder()
        .addHeader("Last-Modified: " + formatDate(-1, TimeUnit.HOURS))
        .addHeader("Cache-Control: max-age=0")
        .body("A")
        .build(),
    )
    server.enqueue(
      MockResponse
        .Builder()
        .body("B")
        .build(),
    )
    server.enqueue(
      MockResponse
        .Builder()
        .body("C")
        .build(),
    )
    assertThat(get(server.url("/")).body.string()).isEqualTo("A")
    assertThat(cache.requestCount()).isEqualTo(1)
    assertThat(cache.networkCount()).isEqualTo(1)
    assertThat(cache.hitCount()).isEqualTo(0)
    assertThat(get(server.url("/")).body.string()).isEqualTo("B")
    assertThat(get(server.url("/")).body.string()).isEqualTo("C")
    assertThat(cache.requestCount()).isEqualTo(3)
    assertThat(cache.networkCount()).isEqualTo(3)
    assertThat(cache.hitCount()).isEqualTo(0)
  }

  @Test
  fun statisticsConditionalCacheHit() {
    server.enqueue(
      MockResponse
        .Builder()
        .addHeader("Last-Modified: " + formatDate(-1, TimeUnit.HOURS))
        .addHeader("Cache-Control: max-age=0")
        .body("A")
        .build(),
    )
    server.enqueue(
      MockResponse
        .Builder()
        .code(HttpURLConnection.HTTP_NOT_MODIFIED)
        .build(),
    )
    server.enqueue(
      MockResponse
        .Builder()
        .code(HttpURLConnection.HTTP_NOT_MODIFIED)
        .build(),
    )
    assertThat(get(server.url("/")).body.string()).isEqualTo("A")
    assertThat(cache.requestCount()).isEqualTo(1)
    assertThat(cache.networkCount()).isEqualTo(1)
    assertThat(cache.hitCount()).isEqualTo(0)
    assertThat(get(server.url("/")).body.string()).isEqualTo("A")
    assertThat(get(server.url("/")).body.string()).isEqualTo("A")
    assertThat(cache.requestCount()).isEqualTo(3)
    assertThat(cache.networkCount()).isEqualTo(3)
    assertThat(cache.hitCount()).isEqualTo(2)
  }

  @Test
  fun statisticsFullCacheHit() {
    server.enqueue(
      MockResponse
        .Builder()
        .addHeader("Cache-Control: max-age=60")
        .body("A")
        .build(),
    )
    assertThat(get(server.url("/")).body.string()).isEqualTo("A")
    assertThat(cache.requestCount()).isEqualTo(1)
    assertThat(cache.networkCount()).isEqualTo(1)
    assertThat(cache.hitCount()).isEqualTo(0)
    assertThat(get(server.url("/")).body.string()).isEqualTo("A")
    assertThat(get(server.url("/")).body.string()).isEqualTo("A")
    assertThat(cache.requestCount()).isEqualTo(3)
    assertThat(cache.networkCount()).isEqualTo(1)
    assertThat(cache.hitCount()).isEqualTo(2)
  }

  @Test
  fun varyMatchesChangedRequestHeaderField() {
    server.enqueue(
      MockResponse
        .Builder()
        .addHeader("Cache-Control: max-age=60")
        .addHeader("Vary: Accept-Language")
        .body("A")
        .build(),
    )
    server.enqueue(
      MockResponse
        .Builder()
        .body("B")
        .build(),
    )
    val url = server.url("/")
    val frRequest =
      Request
        .Builder()
        .url(url)
        .header("Accept-Language", "fr-CA")
        .build()
    val frResponse = client.newCall(frRequest).execute()
    assertThat(frResponse.body.string()).isEqualTo("A")
    val enRequest =
      Request
        .Builder()
        .url(url)
        .header("Accept-Language", "en-US")
        .build()
    val enResponse = client.newCall(enRequest).execute()
    assertThat(enResponse.body.string()).isEqualTo("B")
  }

  @Test
  fun varyMatchesUnchangedRequestHeaderField() {
    server.enqueue(
      MockResponse
        .Builder()
        .addHeader("Cache-Control: max-age=60")
        .addHeader("Vary: Accept-Language")
        .body("A")
        .build(),
    )
    server.enqueue(
      MockResponse
        .Builder()
        .body("B")
        .build(),
    )
    val url = server.url("/")
    val request =
      Request
        .Builder()
        .url(url)
        .header("Accept-Language", "fr-CA")
        .build()
    val response1 = client.newCall(request).execute()
    assertThat(response1.body.string()).isEqualTo("A")
    val request1 =
      Request
        .Builder()
        .url(url)
        .header("Accept-Language", "fr-CA")
        .build()
    val response2 = client.newCall(request1).execute()
    assertThat(response2.body.string()).isEqualTo("A")
  }

  @Test
  fun varyMatchesAbsentRequestHeaderField() {
    server.enqueue(
      MockResponse
        .Builder()
        .addHeader("Cache-Control: max-age=60")
        .addHeader("Vary: Foo")
        .body("A")
        .build(),
    )
    server.enqueue(
      MockResponse
        .Builder()
        .body("B")
        .build(),
    )
    assertThat(get(server.url("/")).body.string()).isEqualTo("A")
    assertThat(get(server.url("/")).body.string()).isEqualTo("A")
  }

  @Test
  fun varyMatchesAddedRequestHeaderField() {
    server.enqueue(
      MockResponse
        .Builder()
        .addHeader("Cache-Control: max-age=60")
        .addHeader("Vary: Foo")
        .body("A")
        .build(),
    )
    server.enqueue(
      MockResponse
        .Builder()
        .body("B")
        .build(),
    )
    assertThat(get(server.url("/")).body.string()).isEqualTo("A")
    val request =
      Request
        .Builder()
        .url(server.url("/"))
        .header("Foo", "bar")
        .build()
    val response = client.newCall(request).execute()
    assertThat(response.body.string()).isEqualTo("B")
  }

  @Test
  fun varyMatchesRemovedRequestHeaderField() {
    server.enqueue(
      MockResponse
        .Builder()
        .addHeader("Cache-Control: max-age=60")
        .addHeader("Vary: Foo")
        .body("A")
        .build(),
    )
    server.enqueue(
      MockResponse
        .Builder()
        .body("B")
        .build(),
    )
    val request =
      Request
        .Builder()
        .url(server.url("/"))
        .header("Foo", "bar")
        .build()
    val fooresponse = client.newCall(request).execute()
    assertThat(fooresponse.body.string()).isEqualTo("A")
    assertThat(get(server.url("/")).body.string()).isEqualTo("B")
  }

  @Test
  fun varyFieldsAreCaseInsensitive() {
    server.enqueue(
      MockResponse
        .Builder()
        .addHeader("Cache-Control: max-age=60")
        .addHeader("Vary: ACCEPT-LANGUAGE")
        .body("A")
        .build(),
    )
    server.enqueue(
      MockResponse
        .Builder()
        .body("B")
        .build(),
    )
    val url = server.url("/")
    val request =
      Request
        .Builder()
        .url(url)
        .header("Accept-Language", "fr-CA")
        .build()
    val response1 = client.newCall(request).execute()
    assertThat(response1.body.string()).isEqualTo("A")
    val request1 =
      Request
        .Builder()
        .url(url)
        .header("accept-language", "fr-CA")
        .build()
    val response2 = client.newCall(request1).execute()
    assertThat(response2.body.string()).isEqualTo("A")
  }

  @Test
  fun varyMultipleFieldsWithMatch() {
    server.enqueue(
      MockResponse
        .Builder()
        .addHeader("Cache-Control: max-age=60")
        .addHeader("Vary: Accept-Language, Accept-Charset")
        .addHeader("Vary: Accept-Encoding")
        .body("A")
        .build(),
    )
    server.enqueue(
      MockResponse
        .Builder()
        .body("B")
        .build(),
    )
    val url = server.url("/")
    val request =
      Request
        .Builder()
        .url(url)
        .header("Accept-Language", "fr-CA")
        .header("Accept-Charset", "UTF-8")
        .header("Accept-Encoding", "identity")
        .build()
    val response1 = client.newCall(request).execute()
    assertThat(response1.body.string()).isEqualTo("A")
    val request1 =
      Request
        .Builder()
        .url(url)
        .header("Accept-Language", "fr-CA")
        .header("Accept-Charset", "UTF-8")
        .header("Accept-Encoding", "identity")
        .build()
    val response2 = client.newCall(request1).execute()
    assertThat(response2.body.string()).isEqualTo("A")
  }

  @Test
  fun varyMultipleFieldsWithNoMatch() {
    server.enqueue(
      MockResponse
        .Builder()
        .addHeader("Cache-Control: max-age=60")
        .addHeader("Vary: Accept-Language, Accept-Charset")
        .addHeader("Vary: Accept-Encoding")
        .body("A")
        .build(),
    )
    server.enqueue(
      MockResponse
        .Builder()
        .body("B")
        .build(),
    )
    val url = server.url("/")
    val frRequest =
      Request
        .Builder()
        .url(url)
        .header("Accept-Language", "fr-CA")
        .header("Accept-Charset", "UTF-8")
        .header("Accept-Encoding", "identity")
        .build()
    val frResponse = client.newCall(frRequest).execute()
    assertThat(frResponse.body.string()).isEqualTo("A")
    val enRequest =
      Request
        .Builder()
        .url(url)
        .header("Accept-Language", "en-CA")
        .header("Accept-Charset", "UTF-8")
        .header("Accept-Encoding", "identity")
        .build()
    val enResponse = client.newCall(enRequest).execute()
    assertThat(enResponse.body.string()).isEqualTo("B")
  }

  @Test
  fun varyMultipleFieldValuesWithMatch() {
    server.enqueue(
      MockResponse
        .Builder()
        .addHeader("Cache-Control: max-age=60")
        .addHeader("Vary: Accept-Language")
        .body("A")
        .build(),
    )
    server.enqueue(
      MockResponse
        .Builder()
        .body("B")
        .build(),
    )
    val url = server.url("/")
    val request1 =
      Request
        .Builder()
        .url(url)
        .addHeader("Accept-Language", "fr-CA, fr-FR")
        .addHeader("Accept-Language", "en-US")
        .build()
    val response1 = client.newCall(request1).execute()
    assertThat(response1.body.string()).isEqualTo("A")
    val request2 =
      Request
        .Builder()
        .url(url)
        .addHeader("Accept-Language", "fr-CA, fr-FR")
        .addHeader("Accept-Language", "en-US")
        .build()
    val response2 = client.newCall(request2).execute()
    assertThat(response2.body.string()).isEqualTo("A")
  }

  @Test
  fun varyMultipleFieldValuesWithNoMatch() {
    server.enqueue(
      MockResponse
        .Builder()
        .addHeader("Cache-Control: max-age=60")
        .addHeader("Vary: Accept-Language")
        .body("A")
        .build(),
    )
    server.enqueue(
      MockResponse
        .Builder()
        .body("B")
        .build(),
    )
    val url = server.url("/")
    val request1 =
      Request
        .Builder()
        .url(url)
        .addHeader("Accept-Language", "fr-CA, fr-FR")
        .addHeader("Accept-Language", "en-US")
        .build()
    val response1 = client.newCall(request1).execute()
    assertThat(response1.body.string()).isEqualTo("A")
    val request2 =
      Request
        .Builder()
        .url(url)
        .addHeader("Accept-Language", "fr-CA")
        .addHeader("Accept-Language", "en-US")
        .build()
    val response2 = client.newCall(request2).execute()
    assertThat(response2.body.string()).isEqualTo("B")
  }

  @Test
  fun varyAsterisk() {
    server.enqueue(
      MockResponse
        .Builder()
        .addHeader("Cache-Control: max-age=60")
        .addHeader("Vary: *")
        .body("A")
        .build(),
    )
    server.enqueue(
      MockResponse
        .Builder()
        .body("B")
        .build(),
    )
    assertThat(get(server.url("/")).body.string()).isEqualTo("A")
    assertThat(get(server.url("/")).body.string()).isEqualTo("B")
  }

  @Test
  fun varyAndHttps() {
    server.useHttps(handshakeCertificates.sslSocketFactory())
    server.enqueue(
      MockResponse
        .Builder()
        .addHeader("Cache-Control: max-age=60")
        .addHeader("Vary: Accept-Language")
        .body("A")
        .build(),
    )
    server.enqueue(
      MockResponse
        .Builder()
        .body("B")
        .build(),
    )
    client =
      client
        .newBuilder()
        .sslSocketFactory(
          handshakeCertificates.sslSocketFactory(),
          handshakeCertificates.trustManager,
        ).hostnameVerifier(NULL_HOSTNAME_VERIFIER)
        .build()
    val url = server.url("/")
    val request1 =
      Request
        .Builder()
        .url(url)
        .header("Accept-Language", "en-US")
        .build()
    val response1 = client.newCall(request1).execute()
    assertThat(response1.body.string()).isEqualTo("A")
    val request2 =
      Request
        .Builder()
        .url(url)
        .header("Accept-Language", "en-US")
        .build()
    val response2 = client.newCall(request2).execute()
    assertThat(response2.body.string()).isEqualTo("A")
  }

  @Test
  fun cachePlusCookies() {
    val cookieJar = RecordingCookieJar()
    client =
      client
        .newBuilder()
        .cookieJar(cookieJar)
        .build()
    server.enqueue(
      MockResponse
        .Builder()
        .addHeader("Set-Cookie: a=FIRST")
        .addHeader("Last-Modified: " + formatDate(-1, TimeUnit.HOURS))
        .addHeader("Cache-Control: max-age=0")
        .body("A")
        .build(),
    )
    server.enqueue(
      MockResponse
        .Builder()
        .addHeader("Set-Cookie: a=SECOND")
        .code(HttpURLConnection.HTTP_NOT_MODIFIED)
        .build(),
    )
    val url = server.url("/")
    assertThat(get(url).body.string()).isEqualTo("A")
    cookieJar.assertResponseCookies("a=FIRST; path=/")
    assertThat(get(url).body.string()).isEqualTo("A")
    cookieJar.assertResponseCookies("a=SECOND; path=/")
  }

  @Test
  fun getHeadersReturnsNetworkEndToEndHeaders() {
    server.enqueue(
      MockResponse
        .Builder()
        .addHeader("Allow: GET, HEAD")
        .addHeader("Last-Modified: " + formatDate(-1, TimeUnit.HOURS))
        .addHeader("Cache-Control: max-age=0")
        .body("A")
        .build(),
    )
    server.enqueue(
      MockResponse
        .Builder()
        .addHeader("Allow: GET, HEAD, PUT")
        .code(HttpURLConnection.HTTP_NOT_MODIFIED)
        .build(),
    )
    val response1 = get(server.url("/"))
    assertThat(response1.body.string()).isEqualTo("A")
    assertThat(response1.header("Allow")).isEqualTo("GET, HEAD")
    val response2 = get(server.url("/"))
    assertThat(response2.body.string()).isEqualTo("A")
    assertThat(response2.header("Allow")).isEqualTo("GET, HEAD, PUT")
  }

  @Test
  fun getHeadersReturnsCachedHopByHopHeaders() {
    server.enqueue(
      MockResponse
        .Builder()
        .addHeader("Transfer-Encoding: identity")
        .addHeader("Last-Modified: " + formatDate(-1, TimeUnit.HOURS))
        .addHeader("Cache-Control: max-age=0")
        .body("A")
        .build(),
    )
    server.enqueue(
      MockResponse
        .Builder()
        .addHeader("Transfer-Encoding: none")
        .code(HttpURLConnection.HTTP_NOT_MODIFIED)
        .build(),
    )
    val response1 = get(server.url("/"))
    assertThat(response1.body.string()).isEqualTo("A")
    assertThat(response1.header("Transfer-Encoding")).isEqualTo("identity")
    val response2 = get(server.url("/"))
    assertThat(response2.body.string()).isEqualTo("A")
    assertThat(response2.header("Transfer-Encoding")).isEqualTo("identity")
  }

  @Test
  fun getHeadersDeletesCached100LevelWarnings() {
    server.enqueue(
      MockResponse
        .Builder()
        .addHeader("Warning: 199 test danger")
        .addHeader("Last-Modified: " + formatDate(-1, TimeUnit.HOURS))
        .addHeader("Cache-Control: max-age=0")
        .body("A")
        .build(),
    )
    server.enqueue(
      MockResponse
        .Builder()
        .code(HttpURLConnection.HTTP_NOT_MODIFIED)
        .build(),
    )
    val response1 = get(server.url("/"))
    assertThat(response1.body.string()).isEqualTo("A")
    assertThat(response1.header("Warning")).isEqualTo("199 test danger")
    val response2 = get(server.url("/"))
    assertThat(response2.body.string()).isEqualTo("A")
    assertThat(response2.header("Warning")).isNull()
  }

  @Test
  fun getHeadersRetainsCached200LevelWarnings() {
    server.enqueue(
      MockResponse
        .Builder()
        .addHeader("Warning: 299 test danger")
        .addHeader("Last-Modified: " + formatDate(-1, TimeUnit.HOURS))
        .addHeader("Cache-Control: max-age=0")
        .body("A")
        .build(),
    )
    server.enqueue(
      MockResponse
        .Builder()
        .code(HttpURLConnection.HTTP_NOT_MODIFIED)
        .build(),
    )
    val response1 = get(server.url("/"))
    assertThat(response1.body.string()).isEqualTo("A")
    assertThat(response1.header("Warning")).isEqualTo("299 test danger")
    val response2 = get(server.url("/"))
    assertThat(response2.body.string()).isEqualTo("A")
    assertThat(response2.header("Warning")).isEqualTo("299 test danger")
  }

  @Test
  fun doNotCachePartialResponse() {
    assertNotCached(
      MockResponse
        .Builder()
        .code(HttpURLConnection.HTTP_PARTIAL)
        .addHeader("Date: " + formatDate(0, TimeUnit.HOURS))
        .addHeader("Content-Range: bytes 100-100/200")
        .addHeader("Cache-Control: max-age=60")
        .build(),
    )
  }

  @Test
  fun conditionalHitUpdatesCache() {
    server.enqueue(
      MockResponse
        .Builder()
        .addHeader("Last-Modified: " + formatDate(0, TimeUnit.SECONDS))
        .addHeader("Cache-Control: max-age=0")
        .body("A")
        .build(),
    )
    server.enqueue(
      MockResponse
        .Builder()
        .addHeader("Cache-Control: max-age=30")
        .addHeader("Allow: GET, HEAD")
        .code(HttpURLConnection.HTTP_NOT_MODIFIED)
        .build(),
    )
    server.enqueue(
      MockResponse
        .Builder()
        .body("B")
        .build(),
    )

    // A cache miss writes the cache.
    val t0 = System.currentTimeMillis()
    val response1 = get(server.url("/a"))
    assertThat(response1.body.string()).isEqualTo("A")
    assertThat(response1.header("Allow")).isNull()
    assertThat((response1.receivedResponseAtMillis - t0).toDouble()).isCloseTo(0.0, 250.0)

    // A conditional cache hit updates the cache.
    Thread.sleep(500) // Make sure t0 and t1 are distinct.
    val t1 = System.currentTimeMillis()
    val response2 = get(server.url("/a"))
    assertThat(response2.code).isEqualTo(HttpURLConnection.HTTP_OK)
    assertThat(response2.body.string()).isEqualTo("A")
    assertThat(response2.header("Allow")).isEqualTo("GET, HEAD")
    val updatedTimestamp = response2.receivedResponseAtMillis
    assertThat((updatedTimestamp - t1).toDouble()).isCloseTo(0.0, 250.0)

    // A full cache hit reads the cache.
    Thread.sleep(10)
    val response3 = get(server.url("/a"))
    assertThat(response3.body.string()).isEqualTo("A")
    assertThat(response3.header("Allow")).isEqualTo("GET, HEAD")
    assertThat(response3.receivedResponseAtMillis).isEqualTo(updatedTimestamp)
    assertThat(server.requestCount).isEqualTo(2)
  }

  @Test
  fun responseSourceHeaderCached() {
    server.enqueue(
      MockResponse
        .Builder()
        .body("A")
        .addHeader("Cache-Control: max-age=30")
        .addHeader("Date: " + formatDate(0, TimeUnit.MINUTES))
        .build(),
    )
    assertThat(get(server.url("/")).body.string()).isEqualTo("A")
    val request =
      Request
        .Builder()
        .url(server.url("/"))
        .header("Cache-Control", "only-if-cached")
        .build()
    val response = client.newCall(request).execute()
    assertThat(response.body.string()).isEqualTo("A")
  }

  @Test
  fun responseSourceHeaderConditionalCacheFetched() {
    server.enqueue(
      MockResponse
        .Builder()
        .body("A")
        .addHeader("Cache-Control: max-age=30")
        .addHeader("Date: " + formatDate(-31, TimeUnit.MINUTES))
        .build(),
    )
    server.enqueue(
      MockResponse
        .Builder()
        .body("B")
        .addHeader("Cache-Control: max-age=30")
        .addHeader("Date: " + formatDate(0, TimeUnit.MINUTES))
        .build(),
    )
    assertThat(get(server.url("/")).body.string()).isEqualTo("A")
    val response = get(server.url("/"))
    assertThat(response.body.string()).isEqualTo("B")
  }

  @Test
  fun responseSourceHeaderConditionalCacheNotFetched() {
    server.enqueue(
      MockResponse
        .Builder()
        .body("A")
        .addHeader("Cache-Control: max-age=0")
        .addHeader("Date: " + formatDate(0, TimeUnit.MINUTES))
        .build(),
    )
    server.enqueue(
      MockResponse
        .Builder()
        .code(304)
        .build(),
    )
    assertThat(get(server.url("/")).body.string()).isEqualTo("A")
    val response = get(server.url("/"))
    assertThat(response.body.string()).isEqualTo("A")
  }

  @Test
  fun responseSourceHeaderFetched() {
    server.enqueue(
      MockResponse
        .Builder()
        .body("A")
        .build(),
    )
    val response = get(server.url("/"))
    assertThat(response.body.string()).isEqualTo("A")
  }

  @Test
  fun emptyResponseHeaderNameFromCacheIsLenient() {
    val headers =
      Headers
        .Builder()
        .add("Cache-Control: max-age=120")
    addHeaderLenient(headers, ": A")
    server.enqueue(
      MockResponse
        .Builder()
        .headers(headers.build())
        .body("body")
        .build(),
    )
    val response = get(server.url("/"))
    assertThat(response.header("")).isEqualTo("A")
    assertThat(response.body.string()).isEqualTo("body")
  }

  /**
   * Old implementations of OkHttp's response cache wrote header fields like ":status: 200 OK". This
   * broke our cached response parser because it split on the first colon. This regression test
   * exists to help us read these old bad cache entries.
   *
   * https://github.com/lysine-dev/okhttp/issues/227
   */
  @RepeatedTest(100)
  fun testGoldenCacheResponse() {
    cache.close()
    server.enqueue(
      MockResponse
        .Builder()
        .clearHeaders()
        .code(HttpURLConnection.HTTP_NOT_MODIFIED)
        .build(),
    )
    addFinalFailingResponse()
    val url = server.url("/")
    val urlKey = key(url)
    val entryMetadata =
      """
      $url
      GET
      0
      HTTP/1.1 200 OK
      7
      :status: 200 OK
      :version: HTTP/1.1
      etag: foo
      content-length: 3
      OkHttp-Received-Millis: ${System.currentTimeMillis()}
      X-Android-Response-Source: NETWORK 200
      OkHttp-Sent-Millis: ${System.currentTimeMillis()}

      TLS_ECDHE_RSA_WITH_AES_256_CBC_SHA
      1
      MIIBpDCCAQ2gAwIBAgIBATANBgkqhkiG9w0BAQsFADAYMRYwFAYDVQQDEw1qd2lsc29uLmxvY2FsMB4XDTEzMDgyOTA1MDE1OVoXDTEzMDgzMDA1MDE1OVowGDEWMBQGA1UEAxMNandpbHNvbi5sb2NhbDCBnzANBgkqhkiG9w0BAQEFAAOBjQAwgYkCgYEAlFW+rGo/YikCcRghOyKkJanmVmJSce/p2/jH1QvNIFKizZdh8AKNwojt3ywRWaDULA/RlCUcltF3HGNsCyjQI/+Lf40x7JpxXF8oim1E6EtDoYtGWAseelawus3IQ13nmo6nWzfyCA55KhAWf4VipelEy8DjcuFKv6L0xwXnI0ECAwEAATANBgkqhkiG9w0BAQsFAAOBgQAuluNyPo1HksU3+Mr/PyRQIQS4BI7pRXN8mcejXmqyscdP7S6J21FBFeRR8/XNjVOp4HT9uSc2hrRtTEHEZCmpyoxixbnM706ikTmC7SN/GgM+SmcoJ1ipJcNcl8N0X6zym4dmyFfXKHu2PkTo7QFdpOJFvP3lIigcSZXozfmEDg==
      -1

      """.trimIndent()
    val entryBody = "abc"
    val journalBody = """libcore.io.DiskLruCache
1
201105
2

CLEAN $urlKey ${entryMetadata.length} ${entryBody.length}
"""
    fileSystem.createDirectory(cache.directoryPath)
    writeFile(cache.directoryPath, "$urlKey.0", entryMetadata)
    writeFile(cache.directoryPath, "$urlKey.1", entryBody)
    writeFile(cache.directoryPath, "journal", journalBody)
    cache = Cache(fileSystem, cache.directoryPath, Int.MAX_VALUE.toLong())
    client =
      client
        .newBuilder()
        .cache(cache)
        .build()
    val response = get(url)
    assertThat(response.body.string()).isEqualTo(entryBody)
    assertThat(response.header("Content-Length")).isEqualTo("3")
    assertThat(response.header("etag")).isEqualTo("foo")
  }

  /** Exercise the cache format in OkHttp 2.7 and all earlier releases.  */
  @RepeatedTest(100)
  fun testGoldenCacheHttpsResponseOkHttp27() {
    addFinalFailingResponse()

    val url = server.url("/")
    val urlKey = key(url)
    val prefix = get().getPrefix()
    val entryMetadata =
      """
      $url
      GET
      0
      HTTP/1.1 200 OK
      4
      Content-Length: 3
      $prefix-Received-Millis: ${System.currentTimeMillis()}
      $prefix-Sent-Millis: ${System.currentTimeMillis()}
      Cache-Control: max-age=60

      TLS_ECDHE_RSA_WITH_AES_128_GCM_SHA256
      1
      MIIBnDCCAQWgAwIBAgIBATANBgkqhkiG9w0BAQsFADAUMRIwEAYDVQQDEwlsb2NhbGhvc3QwHhcNMTUxMjIyMDExMTQwWhcNMTUxMjIzMDExMTQwWjAUMRIwEAYDVQQDEwlsb2NhbGhvc3QwgZ8wDQYJKoZIhvcNAQEBBQADgY0AMIGJAoGBAJTn2Dh8xYmegvpOSmsKb2Os6Cxf1L4fYbnHr/turInUD5r1P7ZAuxurY880q3GT5bUDoirS3IfucddrT1AcAmUzEmk/FDjggiP8DlxFkY/XwXBlhRDVIp/mRuASPMGInckc0ZaixOkRFyrxADj+r1eaSmXCIvV5yTY6IaIokLj1AgMBAAEwDQYJKoZIhvcNAQELBQADgYEAFblnedqtfRqI9j2WDyPPoG0NTZf9xwjeUu+ju+Ktty8u9k7Lgrrd/DH2mQEtBD1Ctvp91MJfAClNg3faZzwClUyu5pd0QXRZEUwSwZQNen2QWDHRlVsItclBJ4t+AJLqTbwofWi4m4K8REOl593hD55E4+lY22JZiVQyjsQhe6I=
      0

      """.trimIndent()
    val entryBody = "abc"
    val journalBody = """libcore.io.DiskLruCache
1
201105
2

DIRTY $urlKey
CLEAN $urlKey ${entryMetadata.length} ${entryBody.length}
"""
    fileSystem.createDirectory(cache.directoryPath)
    writeFile(cache.directoryPath, "$urlKey.0", entryMetadata)
    writeFile(cache.directoryPath, "$urlKey.1", entryBody)
    writeFile(cache.directoryPath, "journal", journalBody)
    cache.close()
    cache = Cache(fileSystem, cache.directoryPath, Int.MAX_VALUE.toLong())
    client =
      client
        .newBuilder()
        .cache(cache)
        .build()
    val response = get(url)
    assertThat(response.body.string()).isEqualTo(entryBody)
    assertThat(response.header("Content-Length")).isEqualTo("3")
  }

  /** The TLS version is present in OkHttp 3.0 and beyond.  */
  @RepeatedTest(100)
  fun testGoldenCacheHttpsResponseOkHttp30() {
    addFinalFailingResponse()

    val url = server.url("/")
    val urlKey = key(url)
    val prefix = get().getPrefix()
    val entryMetadata =
      """
      |$url
      |GET
      |0
      |HTTP/1.1 200 OK
      |4
      |Content-Length: 3
      |$prefix-Received-Millis: ${System.currentTimeMillis()}
      |$prefix-Sent-Millis: ${System.currentTimeMillis()}
      |Cache-Control: max-age=60
      |
      |TLS_ECDHE_RSA_WITH_AES_128_GCM_SHA256
      |1
      |MIIBnDCCAQWgAwIBAgIBATANBgkqhkiG9w0BAQsFADAUMRIwEAYDVQQDEwlsb2NhbGhvc3QwHhcNMTUxMjIyMDExMTQwWhcNMTUxMjIzMDExMTQwWjAUMRIwEAYDVQQDEwlsb2NhbGhvc3QwgZ8wDQYJKoZIhvcNAQEBBQADgY0AMIGJAoGBAJTn2Dh8xYmegvpOSmsKb2Os6Cxf1L4fYbnHr/turInUD5r1P7ZAuxurY880q3GT5bUDoirS3IfucddrT1AcAmUzEmk/FDjggiP8DlxFkY/XwXBlhRDVIp/mRuASPMGInckc0ZaixOkRFyrxADj+r1eaSmXCIvV5yTY6IaIokLj1AgMBAAEwDQYJKoZIhvcNAQELBQADgYEAFblnedqtfRqI9j2WDyPPoG0NTZf9xwjeUu+ju+Ktty8u9k7Lgrrd/DH2mQEtBD1Ctvp91MJfAClNg3faZzwClUyu5pd0QXRZEUwSwZQNen2QWDHRlVsItclBJ4t+AJLqTbwofWi4m4K8REOl593hD55E4+lY22JZiVQyjsQhe6I=
      |0
      |TLSv1.2
      |
      |
      """.trimMargin()
    val entryBody = "abc"
    val journalBody =
      """
      |libcore.io.DiskLruCache
      |1
      |201105
      |2
      |
      |DIRTY $urlKey
      |CLEAN $urlKey ${entryMetadata.length} ${entryBody.length}
      |
      """.trimMargin()
    fileSystem.createDirectory(cache.directoryPath)
    writeFile(cache.directoryPath, "$urlKey.0", entryMetadata)
    writeFile(cache.directoryPath, "$urlKey.1", entryBody)
    writeFile(cache.directoryPath, "journal", journalBody)
    cache.close()
    cache = Cache(fileSystem, cache.directoryPath, Int.MAX_VALUE.toLong())
    client =
      client
        .newBuilder()
        .cache(cache)
        .build()
    val response = get(url)
    assertThat(response.body.string()).isEqualTo(entryBody)
    assertThat(response.header("Content-Length")).isEqualTo("3")
  }

  @RepeatedTest(100)
  fun testGoldenCacheHttpResponseOkHttp30() {
    addFinalFailingResponse()

    val url = server.url("/")
    val urlKey = key(url)
    val prefix = get().getPrefix()
    val entryMetadata =
      """
      |$url
      |GET
      |0
      |HTTP/1.1 200 OK
      |4
      |Cache-Control: max-age=60
      |Content-Length: 3
      |$prefix-Received-Millis: ${System.currentTimeMillis()}
      |$prefix-Sent-Millis: ${System.currentTimeMillis()}
      |
      """.trimMargin()
    val entryBody = "abc"
    val journalBody =
      """
      |libcore.io.DiskLruCache
      |1
      |201105
      |2
      |
      |DIRTY $urlKey
      |CLEAN $urlKey ${entryMetadata.length} ${entryBody.length}
      |
      """.trimMargin()
    fileSystem.createDirectory(cache.directoryPath)
    writeFile(cache.directoryPath, "$urlKey.0", entryMetadata)
    writeFile(cache.directoryPath, "$urlKey.1", entryBody)
    writeFile(cache.directoryPath, "journal", journalBody)
    cache.close()
    cache = Cache(fileSystem, cache.directoryPath, Int.MAX_VALUE.toLong())
    client =
      client
        .newBuilder()
        .cache(cache)
        .build()
    val response = get(url)
    assertThat(response.body.string()).isEqualTo(entryBody)
    assertThat(response.header("Content-Length")).isEqualTo("3")
  }

  private fun addFinalFailingResponse() {
    // Should not get to this response, so fail if so.
    // Avoids timeout on error
    server.enqueue(MockResponse(code = 420, body = "Enhance Your Calm"))
  }

  @Test
  fun evictAll() {
    server.enqueue(
      MockResponse
        .Builder()
        .addHeader("Cache-Control: max-age=60")
        .body("A")
        .build(),
    )
    server.enqueue(
      MockResponse
        .Builder()
        .body("B")
        .build(),
    )
    val url = server.url("/")
    assertThat(get(url).body.string()).isEqualTo("A")
    client.cache!!.evictAll()
    assertThat(client.cache!!.size()).isEqualTo(0)
    assertThat(get(url).body.string()).isEqualTo("B")
  }

  @Test
  fun networkInterceptorInvokedForConditionalGet() {
    server.enqueue(
      MockResponse
        .Builder()
        .addHeader("ETag: v1")
        .body("A")
        .build(),
    )
    server.enqueue(
      MockResponse
        .Builder()
        .code(HttpURLConnection.HTTP_NOT_MODIFIED)
        .build(),
    )

    // Seed the cache.
    val url = server.url("/")
    assertThat(get(url).body.string()).isEqualTo("A")
    val ifNoneMatch = AtomicReference<String?>()
    client =
      client
        .newBuilder()
        .addNetworkInterceptor(
          Interceptor { chain: Interceptor.Chain ->
            ifNoneMatch.compareAndSet(null, chain.request().header("If-None-Match"))
            chain.proceed(chain.request())
          },
        ).build()

    // Confirm the value is cached and intercepted.
    assertThat(get(url).body.string()).isEqualTo("A")
    assertThat(ifNoneMatch.get()).isEqualTo("v1")
  }

  @Test
  fun networkInterceptorNotInvokedForFullyCached() {
    server.enqueue(
      MockResponse
        .Builder()
        .addHeader("Cache-Control: max-age=60")
        .body("A")
        .build(),
    )

    // Seed the cache.
    val url = server.url("/")
    assertThat(get(url).body.string()).isEqualTo("A")

    // Confirm the interceptor isn't exercised.
    client =
      client
        .newBuilder()
        .addNetworkInterceptor(Interceptor { chain: Interceptor.Chain? -> throw AssertionError() })
        .build()
    assertThat(get(url).body.string()).isEqualTo("A")
  }

  @Test
  fun iterateCache() {
    // Put some responses in the cache.
    server.enqueue(
      MockResponse
        .Builder()
        .body("a")
        .build(),
    )
    val urlA = server.url("/a")
    assertThat(get(urlA).body.string()).isEqualTo("a")
    server.enqueue(
      MockResponse
        .Builder()
        .body("b")
        .build(),
    )
    val urlB = server.url("/b")
    assertThat(get(urlB).body.string()).isEqualTo("b")
    server.enqueue(
      MockResponse
        .Builder()
        .body("c")
        .build(),
    )
    val urlC = server.url("/c")
    assertThat(get(urlC).body.string()).isEqualTo("c")

    // Confirm the iterator returns those responses...
    val i: Iterator<String> = cache.urls()
    assertThat(i.hasNext()).isTrue()
    assertThat(i.next()).isEqualTo(urlA.toString())
    assertThat(i.hasNext()).isTrue()
    assertThat(i.next()).isEqualTo(urlB.toString())
    assertThat(i.hasNext()).isTrue()
    assertThat(i.next()).isEqualTo(urlC.toString())

    // ... and nothing else.
    assertThat(i.hasNext()).isFalse()
    assertFailsWith<NoSuchElementException> {
      i.next()
    }
  }

  @Test
  fun iteratorRemoveFromCache() {
    // Put a response in the cache.
    server.enqueue(
      MockResponse
        .Builder()
        .addHeader("Cache-Control: max-age=60")
        .body("a")
        .build(),
    )
    val url = server.url("/a")
    assertThat(get(url).body.string()).isEqualTo("a")

    // Remove it with iteration.
    val i = cache.urls()
    assertThat(i.next()).isEqualTo(url.toString())
    i.remove()

    // Confirm that subsequent requests suffer a cache miss.
    server.enqueue(
      MockResponse
        .Builder()
        .body("b")
        .build(),
    )
    assertThat(get(url).body.string()).isEqualTo("b")
  }

  @Test
  fun iteratorRemoveWithoutNextThrows() {
    // Put a response in the cache.
    server.enqueue(
      MockResponse
        .Builder()
        .body("a")
        .build(),
    )
    val url = server.url("/a")
    assertThat(get(url).body.string()).isEqualTo("a")
    val i = cache.urls()
    assertThat(i.hasNext()).isTrue()
    assertFailsWith<IllegalStateException> {
      i.remove()
    }
  }

  @Test
  fun iteratorRemoveOncePerCallToNext() {
    // Put a response in the cache.
    server.enqueue(
      MockResponse
        .Builder()
        .body("a")
        .build(),
    )
    val url = server.url("/a")
    assertThat(get(url).body.string()).isEqualTo("a")
    val i = cache.urls()
    assertThat(i.next()).isEqualTo(url.toString())
    i.remove()

    // Too many calls to remove().
    assertFailsWith<IllegalStateException> {
      i.remove()
    }
  }

  @Test
  fun elementEvictedBetweenHasNextAndNext() {
    // Put a response in the cache.
    server.enqueue(
      MockResponse
        .Builder()
        .body("a")
        .build(),
    )
    val url = server.url("/a")
    assertThat(get(url).body.string()).isEqualTo("a")

    // The URL will remain available if hasNext() returned true...
    val i = cache.urls()
    assertThat(i.hasNext()).isTrue()

    // ...so even when we evict the element, we still get something back.
    cache.evictAll()
    assertThat(i.next()).isEqualTo(url.toString())

    // Remove does nothing. But most importantly, it doesn't throw!
    i.remove()
  }

  @Test
  fun elementEvictedBeforeHasNextIsOmitted() {
    // Put a response in the cache.
    server.enqueue(
      MockResponse
        .Builder()
        .body("a")
        .build(),
    )
    val url = server.url("/a")
    assertThat(get(url).body.string()).isEqualTo("a")
    val i: Iterator<String> = cache.urls()
    cache.evictAll()

    // The URL was evicted before hasNext() made any promises.
    assertThat(i.hasNext()).isFalse()
    assertFailsWith<NoSuchElementException> {
      i.next()
    }
  }

  /** Test https://github.com/lysine-dev/okhttp/issues/1712.  */
  @Test
  fun conditionalMissUpdatesCache() {
    server.enqueue(
      MockResponse
        .Builder()
        .addHeader("ETag: v1")
        .body("A")
        .build(),
    )
    server.enqueue(
      MockResponse
        .Builder()
        .code(HttpURLConnection.HTTP_NOT_MODIFIED)
        .build(),
    )
    server.enqueue(
      MockResponse
        .Builder()
        .addHeader("ETag: v2")
        .body("B")
        .build(),
    )
    server.enqueue(
      MockResponse
        .Builder()
        .code(HttpURLConnection.HTTP_NOT_MODIFIED)
        .build(),
    )
    val url = server.url("/")
    assertThat(get(url).body.string()).isEqualTo("A")
    assertThat(get(url).body.string()).isEqualTo("A")
    assertThat(get(url).body.string()).isEqualTo("B")
    assertThat(get(url).body.string()).isEqualTo("B")
    assertThat(server.takeRequest().headers["If-None-Match"]).isNull()
    assertThat(server.takeRequest().headers["If-None-Match"]).isEqualTo("v1")
    assertThat(server.takeRequest().headers["If-None-Match"]).isEqualTo("v1")
    assertThat(server.takeRequest().headers["If-None-Match"]).isEqualTo("v2")
  }

  @Test
  fun combinedCacheHeadersCanBeNonAscii() {
    server.enqueue(
      MockResponse
        .Builder()
        .addHeader("Last-Modified: " + formatDate(-1, TimeUnit.HOURS))
        .addHeader("Cache-Control: max-age=0")
        .addHeaderLenient("Alpha", "α")
        .addHeaderLenient("β", "Beta")
        .body("abcd")
        .build(),
    )
    server.enqueue(
      MockResponse
        .Builder()
        .addHeader("Transfer-Encoding: none")
        .addHeaderLenient("Gamma", "Γ")
        .addHeaderLenient("Δ", "Delta")
        .code(HttpURLConnection.HTTP_NOT_MODIFIED)
        .build(),
    )
    val response1 = get(server.url("/"))
    assertThat(response1.header("Alpha")).isEqualTo("α")
    assertThat(response1.header("β")).isEqualTo("Beta")
    assertThat(response1.body.string()).isEqualTo("abcd")
    val response2 = get(server.url("/"))
    assertThat(response2.header("Alpha")).isEqualTo("α")
    assertThat(response2.header("β")).isEqualTo("Beta")
    assertThat(response2.header("Gamma")).isEqualTo("Γ")
    assertThat(response2.header("Δ")).isEqualTo("Delta")
    assertThat(response2.body.string()).isEqualTo("abcd")
  }

  @Test
  fun etagConditionCanBeNonAscii() {
    server.enqueue(
      MockResponse
        .Builder()
        .addHeaderLenient("Etag", "α")
        .addHeader("Cache-Control: max-age=0")
        .body("abcd")
        .build(),
    )
    server.enqueue(
      MockResponse
        .Builder()
        .code(HttpURLConnection.HTTP_NOT_MODIFIED)
        .build(),
    )
    val response1 = get(server.url("/"))
    assertThat(response1.body.string()).isEqualTo("abcd")
    val response2 = get(server.url("/"))
    assertThat(response2.body.string()).isEqualTo("abcd")
    assertThat(server.takeRequest().headers["If-None-Match"]).isNull()
    assertThat(server.takeRequest().headers["If-None-Match"]).isEqualTo("α")
  }

  @Test
  fun conditionalHitHeadersCombined() {
    server.enqueue(
      MockResponse
        .Builder()
        .addHeader("Etag", "a")
        .addHeader("Cache-Control: max-age=0")
        .addHeader("A: a1")
        .addHeader("B: b2")
        .addHeader("B: b3")
        .body("abcd")
        .build(),
    )
    server.enqueue(
      MockResponse
        .Builder()
        .code(HttpURLConnection.HTTP_NOT_MODIFIED)
        .addHeader("B: b4")
        .addHeader("B: b5")
        .addHeader("C: c6")
        .build(),
    )
    val response1 = get(server.url("/"))
    assertThat(response1.body.string()).isEqualTo("abcd")
    assertThat(response1.headers).isEqualTo(
      headersOf(
        "Etag",
        "a",
        "Cache-Control",
        "max-age=0",
        "A",
        "a1",
        "B",
        "b2",
        "B",
        "b3",
        "Content-Length",
        "4",
      ),
    )

    // The original 'A' header is retained because the network response doesn't have one.
    // The original 'B' headers are replaced by the network response.
    // The network's 'C' header is added.
    val response2 = get(server.url("/"))
    assertThat(response2.body.string()).isEqualTo("abcd")
    assertThat(response2.headers).isEqualTo(
      headersOf(
        "Etag",
        "a",
        "Cache-Control",
        "max-age=0",
        "A",
        "a1",
        "Content-Length",
        "4",
        "B",
        "b4",
        "B",
        "b5",
        "C",
        "c6",
      ),
    )
  }

  @Test
  fun getHasCorrectResponse() {
    val request = Request(server.url("/abc"))

    val response = testBasicCachingRules(request)

    assertThat(response.request.url).isEqualTo(request.url)
    assertThat(response.cacheResponse!!.request.url).isEqualTo(request.url)
  }

  @Test
  fun postWithOverrideResponse() {
    val url = server.url("/abc?token=123")
    val cacheUrlOverride = url.newBuilder().removeAllQueryParameters("token").build()

    val request =
      Request
        .Builder()
        .url(url)
        .method("POST", "XYZ".toRequestBody())
        .cacheUrlOverride(cacheUrlOverride)
        .build()

    val response = testBasicCachingRules(request)

    assertThat(response.request.url).isEqualTo(request.url)
    assertThat(response.cacheResponse!!.request.url).isEqualTo(cacheUrlOverride)
  }

  private fun testBasicCachingRules(request: Request): Response {
    val mockResponse =
      MockResponse
        .Builder()
        .addHeader("Last-Modified: " + formatDate(-1, TimeUnit.HOURS))
        .addHeader("Expires: " + formatDate(1, TimeUnit.HOURS))
        .status("HTTP/1.1 200 Fantastic")
    server.enqueue(mockResponse.build())

    client.newCall(request).execute().use {
      it.body.bytes()
    }
    return client.newCall(request).execute()
  }

  private operator fun get(url: HttpUrl): Response {
    val request =
      Request
        .Builder()
        .url(url)
        .build()
    return client.newCall(request).execute()
  }

  private fun writeFile(
    directory: Path,
    file: String,
    content: String,
  ) {
    val sink = fileSystem.sink(directory / file).buffer()
    sink.writeUtf8(content)
    sink.close()
  }

  /**
   * @param delta the offset from the current date to use. Negative values yield dates in the past;
   * positive values yield dates in the future.
   */
  private fun formatDate(
    delta: Long,
    timeUnit: TimeUnit,
  ): String = formatDate(Date(System.currentTimeMillis() + timeUnit.toMillis(delta)))

  private fun formatDate(date: Date): String {
    val rfc1123: DateFormat = SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss zzz", Locale.US)
    rfc1123.timeZone = TimeZone.getTimeZone("GMT")
    return rfc1123.format(date)
  }

  private fun assertNotCached(response: MockResponse) {
    server.enqueue(
      response
        .newBuilder()
        .body("A")
        .build(),
    )
    server.enqueue(
      MockResponse
        .Builder()
        .body("B")
        .build(),
    )
    val url = server.url("/")
    assertThat(get(url).body.string()).isEqualTo("A")
    assertThat(get(url).body.string()).isEqualTo("B")
  }

  /** @return the request with the conditional get headers. */
  private fun assertConditionallyCached(response: MockResponse): RecordedRequest {
    // scenario 1: condition succeeds
    server.enqueue(
      response
        .newBuilder()
        .body("A")
        .status("HTTP/1.1 200 A-OK")
        .build(),
    )
    server.enqueue(
      MockResponse
        .Builder()
        .code(HttpURLConnection.HTTP_NOT_MODIFIED)
        .build(),
    )

    // scenario 2: condition fails
    server.enqueue(
      response
        .newBuilder()
        .body("B")
        .status("HTTP/1.1 200 B-OK")
        .build(),
    )
    server.enqueue(
      MockResponse
        .Builder()
        .status("HTTP/1.1 200 C-OK")
        .body("C")
        .build(),
    )
    val valid = server.url("/valid")
    val response1 = get(valid)
    assertThat(response1.body.string()).isEqualTo("A")
    assertThat(response1.code).isEqualTo(HttpURLConnection.HTTP_OK)
    assertThat(response1.message).isEqualTo("A-OK")
    val response2 = get(valid)
    assertThat(response2.body.string()).isEqualTo("A")
    assertThat(response2.code).isEqualTo(HttpURLConnection.HTTP_OK)
    assertThat(response2.message).isEqualTo("A-OK")
    val invalid = server.url("/invalid")
    val response3 = get(invalid)
    assertThat(response3.body.string()).isEqualTo("B")
    assertThat(response3.code).isEqualTo(HttpURLConnection.HTTP_OK)
    assertThat(response3.message).isEqualTo("B-OK")
    val response4 = get(invalid)
    assertThat(response4.body.string()).isEqualTo("C")
    assertThat(response4.code).isEqualTo(HttpURLConnection.HTTP_OK)
    assertThat(response4.message).isEqualTo("C-OK")
    server.takeRequest() // regular get
    return server.takeRequest() // conditional get
  }

  @Test
  fun immutableIsCached() {
    server.enqueue(
      MockResponse
        .Builder()
        .addHeader("Cache-Control", "immutable, max-age=10")
        .body("A")
        .build(),
    )
    server.enqueue(
      MockResponse
        .Builder()
        .body("B")
        .build(),
    )
    val url = server.url("/")
    assertThat(get(url).body.string()).isEqualTo("A")
    assertThat(get(url).body.string()).isEqualTo("A")
  }

  @Test
  fun immutableIsCachedAfterMultipleCalls() {
    server.enqueue(
      MockResponse
        .Builder()
        .body("A")
        .build(),
    )
    server.enqueue(
      MockResponse
        .Builder()
        .addHeader("Cache-Control", "immutable, max-age=10")
        .body("B")
        .build(),
    )
    server.enqueue(
      MockResponse
        .Builder()
        .body("C")
        .build(),
    )
    val url = server.url("/")
    assertThat(get(url).body.string()).isEqualTo("A")
    assertThat(get(url).body.string()).isEqualTo("B")
    assertThat(get(url).body.string()).isEqualTo("B")
  }

  @Test
  fun immutableIsNotCachedBeyondFreshnessLifetime() {
    //      last modified: 115 seconds ago
    //             served:  15 seconds ago
    //   default lifetime: (115 - 15) / 10 = 10 seconds
    //            expires:  10 seconds from served date = 5 seconds ago
    val lastModifiedDate = formatDate(-115, TimeUnit.SECONDS)
    val conditionalRequest =
      assertConditionallyCached(
        MockResponse
          .Builder()
          .addHeader("Cache-Control: immutable")
          .addHeader("Last-Modified: $lastModifiedDate")
          .addHeader("Date: " + formatDate(-15, TimeUnit.SECONDS))
          .build(),
      )
    assertThat(conditionalRequest.headers["If-Modified-Since"])
      .isEqualTo(lastModifiedDate)
  }

  @Test
  fun testPublicPathConstructor() {
    val events: MutableList<String> = ArrayList()
    fileSystem.createDirectories(cache.directoryPath)
    fileSystem.createDirectories(cache.directoryPath)
    val loggingFileSystem: FileSystem =
      object : ForwardingFileSystem(fileSystem) {
        override fun onPathParameter(
          path: Path,
          functionName: String,
          parameterName: String,
        ): Path {
          events.add("$functionName:$path")
          return path
        }

        override fun onPathResult(
          path: Path,
          functionName: String,
        ): Path {
          events.add("$functionName:$path")
          return path
        }
      }
    val path: Path = "/cache".toPath()
    val c = Cache(loggingFileSystem, path, 100000L)
    assertThat(c.directoryPath).isEqualTo(path)
    c.size()
    assertThat(events).containsExactly(
      "metadataOrNull:/cache/journal.bkp",
      "metadataOrNull:/cache",
      "sink:/cache/journal.bkp",
      "delete:/cache/journal.bkp",
      "metadataOrNull:/cache/journal",
      "metadataOrNull:/cache",
      "sink:/cache/journal.tmp",
      "metadataOrNull:/cache/journal",
      "atomicMove:/cache/journal.tmp",
      "atomicMove:/cache/journal",
      "appendingSink:/cache/journal",
    )
    events.clear()
    c.size()
    assertThat(events).isEmpty()
  }

  private fun assertFullyCached(response: MockResponse) {
    server.enqueue(response.newBuilder().body("A").build())
    server.enqueue(response.newBuilder().body("B").build())
    val url = server.url("/")
    assertThat(get(url).body.string()).isEqualTo("A")
    assertThat(get(url).body.string()).isEqualTo("A")
  }

  /**
   * Shortens the body of `response` but not the corresponding headers. Only useful to test
   * how clients respond to the premature conclusion of the HTTP body.
   */
  private fun truncateViolently(
    builder: MockResponse.Builder,
    numBytesToKeep: Int,
  ): MockResponse.Builder {
    val response = builder.build()
    builder.onResponseEnd(ShutdownConnection)
    val headers = response.headers
    val fullBody = Buffer()
    response.body!!.writeTo(fullBody)
    val truncatedBody = Buffer()
    truncatedBody.write(fullBody, numBytesToKeep.toLong())
    builder.body(truncatedBody)
    builder.headers(headers)
    return builder
  }

  internal enum class TransferKind {
    CHUNKED {
      override fun setBody(
        response: MockResponse.Builder,
        content: Buffer,
        chunkSize: Int,
      ) {
        response.chunkedBody(content, chunkSize)
      }
    },
    FIXED_LENGTH {
      override fun setBody(
        response: MockResponse.Builder,
        content: Buffer,
        chunkSize: Int,
      ) {
        response.body(content)
      }
    },
    END_OF_STREAM {
      override fun setBody(
        response: MockResponse.Builder,
        content: Buffer,
        chunkSize: Int,
      ) {
        response.body(content)
        response.onResponseEnd(ShutdownConnection)
        response.removeHeader("Content-Length")
      }
    }, ;

    abstract fun setBody(
      response: MockResponse.Builder,
      content: Buffer,
      chunkSize: Int,
    )

    fun setBody(
      response: MockResponse.Builder,
      content: String,
      chunkSize: Int,
    ) {
      setBody(response, Buffer().writeUtf8(content), chunkSize)
    }
  }

  /** Returns a gzipped copy of `bytes`.  */
  fun gzip(data: String): Buffer {
    val result = Buffer()
    val sink = GzipSink(result).buffer()
    sink.writeUtf8(data)
    sink.close()
    return result
  }

  companion object {
    private val NULL_HOSTNAME_VERIFIER = HostnameVerifier { hostname, session -> true }
  }
}
