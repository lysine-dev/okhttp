/*
 * Copyright (C) 2009 The Android Open Source Project
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

package okhttp3

import assertk.assertThat
import assertk.assertions.contains
import assertk.assertions.isEqualTo
import assertk.assertions.isFalse
import assertk.assertions.isGreaterThanOrEqualTo
import assertk.assertions.isIn
import assertk.assertions.isInstanceOf
import assertk.assertions.isLessThan
import assertk.assertions.isNotEmpty
import assertk.assertions.isNull
import assertk.assertions.isTrue
import assertk.fail
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.net.ConnectException
import java.net.CookieManager
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.PasswordAuthentication
import java.net.ProtocolException
import java.net.Proxy
import java.net.ProxySelector
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketAddress
import java.net.SocketException
import java.net.SocketTimeoutException
import java.net.URI
import java.net.URLConnection
import java.net.UnknownHostException
import java.security.KeyStore
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import java.time.Duration
import java.util.Arrays
import java.util.EnumSet
import java.util.Locale
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import java.util.zip.GZIPInputStream
import javax.net.SocketFactory
import javax.net.ssl.SSLException
import javax.net.ssl.SSLHandshakeException
import javax.net.ssl.SSLProtocolException
import javax.net.ssl.TrustManager
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager
import kotlin.test.assertFailsWith
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.SocketEffect.CloseSocket
import mockwebserver3.SocketEffect.ShutdownConnection
import mockwebserver3.junit5.StartStop
import okhttp3.Credentials.basic
import okhttp3.Headers.Companion.headersOf
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.TestUtil.assertSuppressed
import okhttp3.internal.RecordingAuthenticator
import okhttp3.internal.RecordingOkAuthenticator
import okhttp3.internal.USER_AGENT
import okhttp3.internal.addHeaderLenient
import okhttp3.internal.authenticator.JavaNetAuthenticator
import okhttp3.internal.http.HTTP_PERM_REDIRECT
import okhttp3.internal.http.HTTP_TEMP_REDIRECT
import okhttp3.internal.platform.Platform
import okhttp3.java.net.cookiejar.JavaNetCookieJar
import okhttp3.sockets.DelegatingServerSocketFactory
import okhttp3.sockets.DelegatingSocketFactory
import okhttp3.testing.Flaky
import okhttp3.testing.PlatformRule
import okio.Buffer
import okio.BufferedSink
import okio.GzipSink
import okio.buffer
import okio.utf8Size
import org.bouncycastle.tls.TlsFatalAlert
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Disabled
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.RegisterExtension
import org.junit.jupiter.api.io.TempDir
import org.opentest4j.TestAbortedException

/** Android's URLConnectionTest, ported to exercise OkHttp's Call API.  */
@Tag("Slow")
class URLConnectionTest {
  @RegisterExtension
  val platform = PlatformRule()

  @RegisterExtension
  val clientTestRule = OkHttpClientTestRule()

  @TempDir
  lateinit var tempDir: File

  @StartStop
  val server = MockWebServer()

  @StartStop
  val server2 = MockWebServer()

  private val handshakeCertificates = platform.localhostHandshakeCertificates()
  private var client = clientTestRule.newClient()
  private var cache: Cache? = null

  @BeforeEach
  fun setUp() {
    server.protocolNegotiationEnabled = false
  }

  @AfterEach
  fun tearDown() {
    java.net.Authenticator.setDefault(null)
    System.clearProperty("proxyHost")
    System.clearProperty("proxyPort")
    System.clearProperty("http.proxyHost")
    System.clearProperty("http.proxyPort")
    System.clearProperty("https.proxyHost")
    System.clearProperty("https.proxyPort")
    if (cache != null) {
      cache!!.delete()
    }
  }

  @Test
  fun requestHeaders() {
    server.enqueue(MockResponse())
    val request =
      Request
        .Builder()
        .url(server.url("/"))
        .addHeader("D", "e")
        .addHeader("D", "f")
        .build()
    assertThat(request.header("D")).isEqualTo("f")
    assertThat(request.header("d")).isEqualTo("f")
    val requestHeaders = request.headers
    assertThat(LinkedHashSet(requestHeaders.values("D"))).isEqualTo(newSet("e", "f"))
    assertThat(LinkedHashSet(requestHeaders.values("d"))).isEqualTo(newSet("e", "f"))
    val response = getResponse(request)
    response.close()
    val recordedRequest = server.takeRequest()
    assertThat(recordedRequest.headers.values("D")).isEqualTo(listOf("e", "f"))
    assertThat(recordedRequest.headers["G"]).isNull()
    assertThat(recordedRequest.headers["null"]).isNull()
  }

  @Test
  fun getRequestPropertyReturnsLastValue() {
    val request =
      Request
        .Builder()
        .url(server.url("/"))
        .addHeader("A", "value1")
        .addHeader("A", "value2")
        .build()
    assertThat(request.header("A")).isEqualTo("value2")
  }

  @Test
  fun responseHeaders() {
    server.enqueue(
      MockResponse
        .Builder()
        .status("HTTP/1.0 200 Fantastic")
        .addHeader("A: c")
        .addHeader("B: d")
        .addHeader("A: e")
        .chunkedBody("ABCDE\nFGHIJ\nKLMNO\nPQR", 8)
        .build(),
    )
    val request = newRequest("/")
    val response = getResponse(request)
    assertThat(response.code).isEqualTo(200)
    assertThat(response.message).isEqualTo("Fantastic")
    val responseHeaders = response.headers
    assertThat(LinkedHashSet(responseHeaders.values("A"))).isEqualTo(newSet("c", "e"))
    assertThat(LinkedHashSet(responseHeaders.values("a"))).isEqualTo(newSet("c", "e"))
    assertThat(responseHeaders.name(0)).isEqualTo("A")
    assertThat(responseHeaders.value(0)).isEqualTo("c")
    assertThat(responseHeaders.name(1)).isEqualTo("B")
    assertThat(responseHeaders.value(1)).isEqualTo("d")
    assertThat(responseHeaders.name(2)).isEqualTo("A")
    assertThat(responseHeaders.value(2)).isEqualTo("e")
    response.body.close()
  }

  @Test
  fun serverSendsInvalidStatusLine() {
    server.enqueue(
      MockResponse
        .Builder()
        .status("HTP/1.1 200 OK")
        .build(),
    )
    val request = newRequest("/")
    assertFailsWith<IOException> {
      getResponse(request)
    }
  }

  @Test
  fun serverSendsInvalidCodeTooLarge() {
    server.enqueue(
      MockResponse
        .Builder()
        .status("HTTP/1.1 2147483648 OK")
        .build(),
    )
    val request = newRequest("/")
    assertFailsWith<IOException> {
      getResponse(request)
    }
  }

  @Test
  fun serverSendsInvalidCodeNotANumber() {
    server.enqueue(
      MockResponse
        .Builder()
        .status("HTTP/1.1 00a OK")
        .build(),
    )
    val request = newRequest("/")
    assertFailsWith<IOException> {
      getResponse(request)
    }
  }

  @Test
  fun serverSendsUnnecessaryWhitespace() {
    server.enqueue(
      MockResponse
        .Builder()
        .status(" HTTP/1.1 2147483648 OK")
        .build(),
    )
    val request = newRequest("/")
    assertFailsWith<IOException> {
      getResponse(request)
    }
  }

  @Test
  fun connectRetriesUntilConnectedOrFailed() {
    val request = newRequest("/foo")
    server.close()
    assertFailsWith<IOException> {
      getResponse(request)
    }
  }

  @Test
  fun requestBodySurvivesRetriesWithFixedLength() {
    testRequestBodySurvivesRetries(TransferKind.FIXED_LENGTH)
  }

  @Test
  fun requestBodySurvivesRetriesWithChunkedStreaming() {
    testRequestBodySurvivesRetries(TransferKind.CHUNKED)
  }

  private fun testRequestBodySurvivesRetries(transferKind: TransferKind) {
    server.enqueue(MockResponse(body = "abc"))

    // Use a misconfigured proxy to guarantee that the request is retried.
    client =
      client
        .newBuilder()
        .proxySelector(
          FakeProxySelector()
            .addProxy(server2.proxyAddress)
            .addProxy(Proxy.NO_PROXY),
        ).build()
    server2.close()
    val request =
      Request(
        url = server.url("/def"),
        body = transferKind.newRequestBody("body"),
      )
    val response = getResponse(request)
    assertContent("abc", response)
    assertThat(server.takeRequest().body?.utf8()).isEqualTo("body")
  }

  // Check that if we don't read to the end of a response, the next request on the
  // recycled connection doesn't get the unread tail of the first request's response.
  // http://code.google.com/p/android/issues/detail?id=2939
  @Test
  fun bug2939() {
    val response =
      MockResponse
        .Builder()
        .chunkedBody("ABCDE\nFGHIJ\nKLMNO\nPQR", 8)
        .build()
    server.enqueue(response)
    server.enqueue(response)
    val request = newRequest("/")
    val c1 = getResponse(request)
    assertContent("ABCDE", c1, 5)
    val c2 = getResponse(request)
    assertContent("ABCDE", c2, 5)
    c1.close()
    c2.close()
  }

  @Test
  fun connectionsArePooled() {
    val response =
      MockResponse(
        body = "ABCDEFGHIJKLMNOPQR",
      )
    server.enqueue(response)
    server.enqueue(response)
    server.enqueue(response)
    assertContent("ABCDEFGHIJKLMNOPQR", getResponse(newRequest("/foo")))
    assertThat(server.takeRequest().exchangeIndex).isEqualTo(0)
    assertContent("ABCDEFGHIJKLMNOPQR", getResponse(newRequest("/bar?baz=quux")))
    assertThat(server.takeRequest().exchangeIndex).isEqualTo(1)
    assertContent("ABCDEFGHIJKLMNOPQR", getResponse(newRequest("/z")))
    assertThat(server.takeRequest().exchangeIndex).isEqualTo(2)
  }

  @Test
  fun chunkedConnectionsArePooled() {
    val response =
      MockResponse
        .Builder()
        .chunkedBody("ABCDEFGHIJKLMNOPQR", 5)
        .build()
    server.enqueue(response)
    server.enqueue(response)
    server.enqueue(response)
    assertContent("ABCDEFGHIJKLMNOPQR", getResponse(newRequest("/foo")))
    assertThat(server.takeRequest().exchangeIndex).isEqualTo(0)
    assertContent("ABCDEFGHIJKLMNOPQR", getResponse(newRequest("/bar?baz=quux")))
    assertThat(server.takeRequest().exchangeIndex).isEqualTo(1)
    assertContent("ABCDEFGHIJKLMNOPQR", getResponse(newRequest("/z")))
    assertThat(server.takeRequest().exchangeIndex).isEqualTo(2)
  }

  @Test
  fun serverClosesSocket() {
    testServerClosesOutput(
      MockResponse
        .Builder()
        .body("This connection won't pool properly")
        .onResponseEnd(ShutdownConnection)
        .build(),
    )
  }

  @Test
  fun serverShutdownInput() {
    testServerClosesOutput(
      MockResponse
        .Builder()
        .body("This connection won't pool properly")
        .onResponseEnd(
          CloseSocket(
            closeSocket = false,
            shutdownInput = true,
          ),
        ).build(),
    )
  }

  @Test
  fun serverShutdownOutput() {
    testServerClosesOutput(
      MockResponse
        .Builder()
        .body("This connection won't pool properly")
        .onResponseEnd(
          CloseSocket(
            closeSocket = false,
            shutdownOutput = true,
          ),
        ).build(),
    )
  }

  @Test
  fun invalidHost() {
    // Note that 1234.1.1.1 is an invalid host in a URI, but URL isn't as strict.
    client =
      client
        .newBuilder()
        .dns(FakeDns())
        .build()
    assertFailsWith<UnknownHostException> {
      getResponse(
        Request
          .Builder()
          .url("http://1234.1.1.1/index.html".toHttpUrl())
          .build(),
      )
    }
  }

  private fun testServerClosesOutput(mockResponse: MockResponse) {
    server.enqueue(mockResponse)
    val responseAfter = MockResponse(body = "This comes after a busted connection")
    server.enqueue(responseAfter)
    server.enqueue(responseAfter) // Enqueue 2x because the broken connection may be reused.
    val response1 = getResponse(newRequest("/a"))
    response1.body
      .source()
      .timeout()
      .timeout(100, TimeUnit.MILLISECONDS)
    assertContent("This connection won't pool properly", response1)
    assertThat(server.takeRequest().exchangeIndex).isEqualTo(0)

    // Give the server time to enact the socket policy if it's one that could happen after the
    // client has received the response.
    Thread.sleep(500)
    val response2 = getResponse(newRequest("/b"))
    response1.body
      .source()
      .timeout()
      .timeout(100, TimeUnit.MILLISECONDS)
    assertContent("This comes after a busted connection", response2)

    // Check that a fresh connection was created, either immediately or after attempting reuse.
    // We know that a fresh connection was created if the server recorded a request with sequence
    // number 0. Since the client may have attempted to reuse the broken connection just before
    // creating a fresh connection, the server may have recorded 2 requests at this point. The order
    // of recording is non-deterministic.
    val requestAfter = server.takeRequest()
    assertThat(
      requestAfter.exchangeIndex == 0 ||
        server.requestCount == 3 &&
        server.takeRequest().exchangeIndex == 0,
    ).isTrue()
  }

  internal enum class WriteKind {
    BYTE_BY_BYTE,
    SMALL_BUFFERS,
    LARGE_BUFFERS,
  }

  @Test
  fun chunkedUpload_byteByByte() {
    doUpload(TransferKind.CHUNKED, WriteKind.BYTE_BY_BYTE)
  }

  @Test
  fun chunkedUpload_smallBuffers() {
    doUpload(TransferKind.CHUNKED, WriteKind.SMALL_BUFFERS)
  }

  @Test
  fun chunkedUpload_largeBuffers() {
    doUpload(TransferKind.CHUNKED, WriteKind.LARGE_BUFFERS)
  }

  @Test
  fun fixedLengthUpload_byteByByte() {
    doUpload(TransferKind.FIXED_LENGTH, WriteKind.BYTE_BY_BYTE)
  }

  @Test
  fun fixedLengthUpload_smallBuffers() {
    doUpload(TransferKind.FIXED_LENGTH, WriteKind.SMALL_BUFFERS)
  }

  @Test
  fun fixedLengthUpload_largeBuffers() {
    doUpload(TransferKind.FIXED_LENGTH, WriteKind.LARGE_BUFFERS)
  }

  private fun doUpload(
    uploadKind: TransferKind,
    writeKind: WriteKind,
  ) {
    val n = 512 * 1024
    server.bodyLimit = 0
    server.enqueue(MockResponse())
    val requestBody: RequestBody =
      object : RequestBody() {
        override fun contentType(): MediaType? = null

        override fun contentLength(): Long = if (uploadKind === TransferKind.CHUNKED) -1L else n.toLong()

        override fun writeTo(sink: BufferedSink) {
          if (writeKind == WriteKind.BYTE_BY_BYTE) {
            for (i in 0 until n) {
              sink.writeByte('x'.code)
            }
          } else {
            val buf = ByteArray(if (writeKind == WriteKind.SMALL_BUFFERS) 256 else 64 * 1024)
            Arrays.fill(buf, 'x'.code.toByte())
            var i = 0
            while (i < n) {
              sink.write(buf, 0, Math.min(buf.size, n - i))
              i += buf.size
            }
          }
        }
      }
    val response =
      getResponse(
        Request(
          url = server.url("/"),
          body = requestBody,
        ),
      )
    assertThat(response.code).isEqualTo(200)
    val request = server.takeRequest()
    assertThat(request.bodySize).isEqualTo(n.toLong())
    if (uploadKind === TransferKind.CHUNKED) {
      assertThat(request.chunkSizes!!).isNotEmpty()
    } else {
      assertThat(request.chunkSizes).isNull()
    }
  }

  @Test
  fun connectViaHttps() {
    server.useHttps(handshakeCertificates.sslSocketFactory())
    server.enqueue(MockResponse(body = "this response comes via HTTPS"))
    client =
      client
        .newBuilder()
        .sslSocketFactory(
          handshakeCertificates.sslSocketFactory(),
          handshakeCertificates.trustManager,
        ).hostnameVerifier(RecordingHostnameVerifier())
        .build()
    val response = getResponse(newRequest("/foo"))
    assertContent("this response comes via HTTPS", response)
    val request = server.takeRequest()
    assertThat(request.requestLine).isEqualTo("GET /foo HTTP/1.1")
  }

  @Test
  fun connectViaHttpsReusingConnections() {
    connectViaHttpsReusingConnections(false)
  }

  @Test
  fun connectViaHttpsReusingConnectionsAfterRebuildingClient() {
    connectViaHttpsReusingConnections(true)
  }

  private fun connectViaHttpsReusingConnections(rebuildClient: Boolean) {
    server.useHttps(handshakeCertificates.sslSocketFactory())
    server.enqueue(MockResponse(body = "this response comes via HTTPS"))
    server.enqueue(MockResponse(body = "another response via HTTPS"))

    // The pool will only reuse sockets if the SSL socket factories are the same.
    val clientSocketFactory = handshakeCertificates.sslSocketFactory()
    val hostnameVerifier = RecordingHostnameVerifier()
    val cookieJar: CookieJar = JavaNetCookieJar(CookieManager())
    val connectionPool = ConnectionPool()
    client =
      OkHttpClient
        .Builder()
        .cache(cache)
        .connectionPool(connectionPool)
        .cookieJar(cookieJar)
        .sslSocketFactory(clientSocketFactory, handshakeCertificates.trustManager)
        .hostnameVerifier(hostnameVerifier)
        .build()
    val response1 = getResponse(newRequest("/"))
    assertContent("this response comes via HTTPS", response1)
    if (rebuildClient) {
      client =
        OkHttpClient
          .Builder()
          .cache(cache)
          .connectionPool(connectionPool)
          .cookieJar(cookieJar)
          .sslSocketFactory(clientSocketFactory, handshakeCertificates.trustManager)
          .hostnameVerifier(hostnameVerifier)
          .build()
    }
    val response2 = getResponse(newRequest("/"))
    assertContent("another response via HTTPS", response2)
    assertThat(server.takeRequest().exchangeIndex).isEqualTo(0)
    assertThat(server.takeRequest().exchangeIndex).isEqualTo(1)
  }

  @Test
  fun connectViaHttpsReusingConnectionsDifferentFactories() {
    server.useHttps(handshakeCertificates.sslSocketFactory())
    server.enqueue(MockResponse(body = "this response comes via HTTPS"))
    server.enqueue(MockResponse(body = "another response via HTTPS"))

    // install a custom SSL socket factory so the server can be authorized
    client =
      client
        .newBuilder()
        .sslSocketFactory(
          handshakeCertificates.sslSocketFactory(),
          handshakeCertificates.trustManager,
        ).hostnameVerifier(RecordingHostnameVerifier())
        .build()
    val response1 = getResponse(newRequest("/"))
    assertContent("this response comes via HTTPS", response1)
    val sslContext2 = Platform.get().newSSLContext()
    sslContext2.init(null, null, null)
    val sslSocketFactory2 = sslContext2.socketFactory
    val trustManagerFactory =
      TrustManagerFactory.getInstance(
        TrustManagerFactory.getDefaultAlgorithm(),
      )
    trustManagerFactory.init(null as KeyStore?)
    val trustManager = trustManagerFactory.trustManagers[0] as X509TrustManager
    client =
      client
        .newBuilder()
        .sslSocketFactory(sslSocketFactory2, trustManager)
        .build()
    assertFailsWith<IOException> {
      getResponse(newRequest("/"))
    }.also { expected ->
      when (expected) {
        is SSLException, is TlsFatalAlert -> {}

        else -> {
          throw expected
        }
      }
    }
  }

  // TODO(jwilson): tests below this marker need to be migrated to OkHttp's request/response API.
  @Test
  fun connectViaHttpsWithSSLFallback() {
    platform.assumeNotBouncyCastle()

    server.useHttps(handshakeCertificates.sslSocketFactory())
    server.enqueue(MockResponse.Builder().failHandshake().build())
    server.enqueue(MockResponse(body = "this response comes via SSL"))
    client =
      client
        .newBuilder()
        .hostnameVerifier(
          RecordingHostnameVerifier(),
        ) // Attempt RESTRICTED_TLS then fall back to MODERN_TLS.
        .connectionSpecs(Arrays.asList(ConnectionSpec.RESTRICTED_TLS, ConnectionSpec.MODERN_TLS))
        .sslSocketFactory(
          suppressTlsFallbackClientSocketFactory(),
          handshakeCertificates.trustManager,
        ).build()
    val response = getResponse(newRequest("/foo"))
    assertContent("this response comes via SSL", response)
    val failHandshakeRequest = server.takeRequest()
    assertThat(failHandshakeRequest.requestLine).isEqualTo("GET / HTTP/1.1")
    val fallbackRequest = server.takeRequest()
    assertThat(fallbackRequest.requestLine).isEqualTo("GET /foo HTTP/1.1")
    assertThat(fallbackRequest.handshake?.tlsVersion).isIn(TlsVersion.TLS_1_2, TlsVersion.TLS_1_3)
  }

  @Test
  fun connectViaHttpsWithSSLFallbackFailuresRecorded() {
    platform.assumeNotBouncyCastle()

    server.useHttps(handshakeCertificates.sslSocketFactory())
    server.enqueue(MockResponse.Builder().failHandshake().build())
    server.enqueue(MockResponse.Builder().failHandshake().build())
    client =
      client
        .newBuilder()
        .connectionSpecs(Arrays.asList(ConnectionSpec.MODERN_TLS, ConnectionSpec.COMPATIBLE_TLS))
        .hostnameVerifier(RecordingHostnameVerifier())
        .sslSocketFactory(
          suppressTlsFallbackClientSocketFactory(),
          handshakeCertificates.trustManager,
        ).build()
    assertFailsWith<IOException> {
      getResponse(newRequest("/foo"))
    }.also { expected ->
      expected.assertSuppressed { throwables: List<Throwable>? ->
        assertThat(throwables!!.size).isEqualTo(1)
      }
    }
  }

  /**
   * When a pooled connection fails, don't blame the route. Otherwise pooled connection failures can
   * cause unnecessary SSL fallbacks.
   *
   * https://github.com/lysine-dev/okhttp/issues/515
   */
  @Test
  fun sslFallbackNotUsedWhenRecycledConnectionFails() {
    server.useHttps(handshakeCertificates.sslSocketFactory())
    server.enqueue(
      MockResponse
        .Builder()
        .body("abc")
        .onResponseEnd(ShutdownConnection)
        .build(),
    )
    server.enqueue(MockResponse(body = "def"))
    client =
      client
        .newBuilder()
        .hostnameVerifier(RecordingHostnameVerifier())
        .sslSocketFactory(
          suppressTlsFallbackClientSocketFactory(),
          handshakeCertificates.trustManager,
        ).build()
    assertContent("abc", getResponse(newRequest("/")))

    // Give the server time to disconnect.
    Thread.sleep(500)
    assertContent("def", getResponse(newRequest("/")))
    val tlsVersions: Set<TlsVersion?> =
      EnumSet.of(
        TlsVersion.TLS_1_0,
        TlsVersion.TLS_1_2,
        TlsVersion.TLS_1_3,
      ) // v1.2 on OpenJDK 8.
    val request1 = server.takeRequest()
    assertThat(tlsVersions).contains(request1.handshake?.tlsVersion)
    val request2 = server.takeRequest()
    assertThat(tlsVersions).contains(request2.handshake?.tlsVersion)
  }

  /**
   * Verify that we don't retry connections on certificate verification errors.
   *
   * http://code.google.com/p/android/issues/detail?id=13178
   */
  @Flaky
  @Test
  fun connectViaHttpsToUntrustedServer() {
    // Flaky https://github.com/lysine-dev/okhttp/issues/5222
    server.useHttps(handshakeCertificates.sslSocketFactory())
    server.enqueue(MockResponse()) // unused
    assertFailsWith<IOException> {
      getResponse(newRequest("/foo"))
    }.also { expected ->
      when (expected) {
        is SSLHandshakeException -> {
          // Allow conscrypt to fail in different ways
          if (!platform.isConscrypt()) {
            assertThat(expected.cause!!).isInstanceOf<CertificateException>()
          }
        }

        is TlsFatalAlert -> {}

        else -> {
          throw expected
        }
      }
    }
    assertThat(server.requestCount).isEqualTo(0)
  }

  @Test
  fun connectViaProxyUsingProxyArg() {
    testConnectViaProxy(ProxyConfig.CREATE_ARG)
  }

  @Test
  fun connectViaProxyUsingProxySystemProperty() {
    testConnectViaProxy(ProxyConfig.PROXY_SYSTEM_PROPERTY)
  }

  @Test
  fun connectViaProxyUsingHttpProxySystemProperty() {
    testConnectViaProxy(ProxyConfig.HTTP_PROXY_SYSTEM_PROPERTY)
  }

  private fun testConnectViaProxy(proxyConfig: ProxyConfig) {
    server.enqueue(
      MockResponse(body = "this response comes via a proxy"),
    )
    val url = "http://android.com/foo".toHttpUrl()
    val response = proxyConfig.connect(server, client, url).execute()
    assertContent("this response comes via a proxy", response)
    val request = server.takeRequest()
    assertThat(request.requestLine).isEqualTo(
      "GET http://android.com/foo HTTP/1.1",
    )
    assertThat(request.headers["Host"]).isEqualTo("android.com")
  }

  @Test
  fun contentDisagreesWithContentLengthHeaderBodyTooLong() {
    server.enqueue(
      MockResponse
        .Builder()
        .body("abc\r\nYOU SHOULD NOT SEE THIS")
        .clearHeaders()
        .addHeader("Content-Length: 3")
        .build(),
    )
    assertContent("abc", getResponse(newRequest("/")))
  }

  @Test
  fun contentDisagreesWithContentLengthHeaderBodyTooShort() {
    server.enqueue(
      MockResponse
        .Builder()
        .body("abc")
        .setHeader("Content-Length", "5")
        .onResponseEnd(ShutdownConnection)
        .build(),
    )
    assertFailsWith<ProtocolException> {
      val response = getResponse(newRequest("/"))
      response.body.source().readUtf8(5)
    }
  }

  private fun testConnectViaSocketFactory(useHttps: Boolean) {
    val uselessSocketFactory: SocketFactory =
      object : SocketFactory() {
        override fun createSocket(): Socket = throw IllegalArgumentException("useless")

        override fun createSocket(
          host: InetAddress,
          port: Int,
        ): Socket? = null

        override fun createSocket(
          address: InetAddress,
          port: Int,
          localAddress: InetAddress,
          localPort: Int,
        ): Socket? = null

        override fun createSocket(
          host: String,
          port: Int,
        ): Socket? = null

        override fun createSocket(
          host: String,
          port: Int,
          localHost: InetAddress,
          localPort: Int,
        ): Socket? = null
      }
    if (useHttps) {
      server.useHttps(handshakeCertificates.sslSocketFactory())
      client =
        client
          .newBuilder()
          .sslSocketFactory(
            handshakeCertificates.sslSocketFactory(),
            handshakeCertificates.trustManager,
          ).hostnameVerifier(RecordingHostnameVerifier())
          .build()
    }
    server.enqueue(MockResponse())
    client =
      client
        .newBuilder()
        .socketFactory(uselessSocketFactory)
        .build()
    assertFailsWith<IllegalArgumentException> {
      getResponse(newRequest("/"))
    }
    client =
      client
        .newBuilder()
        .socketFactory(Platform.get().socketFactory)
        .build()
    val response = getResponse(newRequest("/"))
    assertThat(response.code).isEqualTo(200)
  }

  @Test
  fun connectHttpViaSocketFactory() {
    testConnectViaSocketFactory(false)
  }

  @Test
  fun connectHttpsViaSocketFactory() {
    testConnectViaSocketFactory(true)
  }

  @Test
  fun contentDisagreesWithChunkedHeaderBodyTooLong() {
    val builder =
      MockResponse
        .Builder()
        .chunkedBody("abc", 3)
    val buffer = Buffer()
    builder.body!!.writeTo(buffer)
    buffer.writeUtf8("\r\nYOU SHOULD NOT SEE THIS")
    builder.body(buffer)
    builder.clearHeaders()
    builder.addHeader("Transfer-encoding: chunked")
    server.enqueue(builder.build())
    assertContent("abc", getResponse(newRequest("/")))
  }

  @Test
  fun contentDisagreesWithChunkedHeaderBodyTooShort() {
    val builder =
      MockResponse
        .Builder()
        .chunkedBody("abcdefg", 5)
    val fullBody = Buffer()
    builder.body!!.writeTo(fullBody)
    val truncatedBody = Buffer()
    truncatedBody.write(fullBody, 4)
    builder.body(truncatedBody)
    builder.clearHeaders()
    builder.addHeader("Transfer-encoding: chunked")
    builder.onResponseEnd(ShutdownConnection)
    server.enqueue(builder.build())
    assertFailsWith<IOException> {
      val response = getResponse(newRequest("/"))
      response.body.source().readUtf8(7)
    }
  }

  @Test
  fun connectViaHttpProxyToHttpsUsingProxyArgWithNoProxy() {
    testConnectViaDirectProxyToHttps(ProxyConfig.NO_PROXY)
  }

  @Test
  fun connectViaHttpProxyToHttpsUsingHttpProxySystemProperty() {
    // https should not use http proxy
    testConnectViaDirectProxyToHttps(ProxyConfig.HTTP_PROXY_SYSTEM_PROPERTY)
  }

  private fun testConnectViaDirectProxyToHttps(proxyConfig: ProxyConfig) {
    server.useHttps(handshakeCertificates.sslSocketFactory())
    server.enqueue(
      MockResponse(body = "this response comes via HTTPS"),
    )
    val url = server.url("/foo")
    client =
      client
        .newBuilder()
        .sslSocketFactory(
          handshakeCertificates.sslSocketFactory(),
          handshakeCertificates.trustManager,
        ).hostnameVerifier(RecordingHostnameVerifier())
        .build()
    val call = proxyConfig.connect(server, client, url)
    assertContent("this response comes via HTTPS", call.execute())
    val request = server.takeRequest()
    assertThat(request.requestLine).isEqualTo("GET /foo HTTP/1.1")
  }

  @Test
  fun connectViaHttpProxyToHttpsUsingProxyArg() {
    testConnectViaHttpProxyToHttps(ProxyConfig.CREATE_ARG)
  }

  /**
   * We weren't honoring all of the appropriate proxy system properties when connecting via HTTPS.
   * http://b/3097518
   */
  @Test
  fun connectViaHttpProxyToHttpsUsingProxySystemProperty() {
    testConnectViaHttpProxyToHttps(ProxyConfig.PROXY_SYSTEM_PROPERTY)
  }

  @Test
  fun connectViaHttpProxyToHttpsUsingHttpsProxySystemProperty() {
    testConnectViaHttpProxyToHttps(ProxyConfig.HTTPS_PROXY_SYSTEM_PROPERTY)
  }

  /**
   * We were verifying the wrong hostname when connecting to an HTTPS site through a proxy.
   * http://b/3097277
   */
  private fun testConnectViaHttpProxyToHttps(proxyConfig: ProxyConfig) {
    val hostnameVerifier = RecordingHostnameVerifier()
    server.useHttps(handshakeCertificates.sslSocketFactory())
    server.enqueue(
      MockResponse
        .Builder()
        .inTunnel()
        .build(),
    )
    server.enqueue(MockResponse(body = "this response comes via a secure proxy"))
    val url = "https://android.com/foo".toHttpUrl()
    client =
      client
        .newBuilder()
        .sslSocketFactory(
          handshakeCertificates.sslSocketFactory(),
          handshakeCertificates.trustManager,
        ).hostnameVerifier(hostnameVerifier)
        .build()
    val call = proxyConfig.connect(server, client, url)
    assertContent("this response comes via a secure proxy", call.execute())
    val connect = server.takeRequest()
    assertThat(connect.requestLine, "Connect line failure on proxy")
      .isEqualTo("CONNECT android.com:443 HTTP/1.1")
    assertThat(connect.headers["Host"]).isEqualTo("android.com:443")
    val get = server.takeRequest()
    assertThat(get.requestLine).isEqualTo("GET /foo HTTP/1.1")
    assertThat(get.headers["Host"]).isEqualTo("android.com")
    assertThat(hostnameVerifier.calls).isEqualTo(
      Arrays.asList("verify android.com"),
    )
  }

  /** Tolerate bad https proxy response when using HttpResponseCache. Android bug 6754912.  */
  @Test
  fun connectViaHttpProxyToHttpsUsingBadProxyAndHttpResponseCache() {
    initResponseCache()
    server.useHttps(handshakeCertificates.sslSocketFactory())
    // The inclusion of a body in the response to a CONNECT is key to reproducing b/6754912.
    server.enqueue(
      MockResponse
        .Builder()
        .inTunnel()
        .body("bogus proxy connect response content")
        .build(),
    )
    server.enqueue(MockResponse(body = "response"))

    // Configure a single IP address for the host and a single configuration, so we only need one
    // failure to fail permanently.
    client =
      client
        .newBuilder()
        .sslSocketFactory(
          handshakeCertificates.sslSocketFactory(),
          handshakeCertificates.trustManager,
        ).connectionSpecs(listOf(ConnectionSpec.MODERN_TLS))
        .hostnameVerifier(RecordingHostnameVerifier())
        .proxy(server.proxyAddress)
        .build()
    val response =
      getResponse(
        Request
          .Builder()
          .url("https://android.com/foo".toHttpUrl())
          .build(),
      )
    assertContent("response", response)
    val connect = server.takeRequest()
    assertThat(connect.requestLine).isEqualTo(
      "CONNECT android.com:443 HTTP/1.1",
    )
    assertThat(connect.headers["Host"]).isEqualTo("android.com:443")
  }

  private fun initResponseCache() {
    cache = Cache(tempDir, Int.MAX_VALUE.toLong())
    client =
      client
        .newBuilder()
        .cache(cache)
        .build()
  }

  /** Test which headers are sent unencrypted to the HTTP proxy.  */
  @Test
  fun proxyConnectIncludesProxyHeadersOnly() {
    val hostnameVerifier = RecordingHostnameVerifier()
    server.useHttps(handshakeCertificates.sslSocketFactory())
    server.enqueue(
      MockResponse
        .Builder()
        .inTunnel()
        .build(),
    )
    server.enqueue(MockResponse(body = "encrypted response from the origin server"))
    client =
      client
        .newBuilder()
        .proxy(server.proxyAddress)
        .sslSocketFactory(
          handshakeCertificates.sslSocketFactory(),
          handshakeCertificates.trustManager,
        ).hostnameVerifier(hostnameVerifier)
        .build()
    val response =
      getResponse(
        Request
          .Builder()
          .url("https://android.com/foo".toHttpUrl())
          .header("Private", "Secret")
          .header("Proxy-Authorization", "bar")
          .header("User-Agent", "baz")
          .build(),
      )
    assertContent("encrypted response from the origin server", response)
    val connect = server.takeRequest()
    assertThat(connect.headers["Private"]).isNull()
    assertThat(connect.headers["Proxy-Authorization"]).isNull()
    assertThat(connect.headers["User-Agent"]).isEqualTo(USER_AGENT)
    assertThat(connect.headers["Host"]).isEqualTo("android.com:443")
    assertThat(connect.headers["Proxy-Connection"]).isEqualTo("Keep-Alive")
    val get = server.takeRequest()
    assertThat(get.headers["Private"]).isEqualTo("Secret")
    assertThat(hostnameVerifier.calls).isEqualTo(listOf("verify android.com"))
  }

  @Test
  fun proxyAuthenticateOnConnect() {
    java.net.Authenticator.setDefault(RecordingAuthenticator())
    server.useHttps(handshakeCertificates.sslSocketFactory())
    server.enqueue(
      MockResponse
        .Builder()
        .code(407)
        .headers(headersOf("Proxy-Authenticate", "Basic realm=\"localhost\""))
        .inTunnel()
        .build(),
    )
    server.enqueue(
      MockResponse
        .Builder()
        .inTunnel()
        .build(),
    )
    server.enqueue(MockResponse(body = "A"))
    client =
      client
        .newBuilder()
        .proxyAuthenticator(Authenticator.JAVA_NET_AUTHENTICATOR)
        .proxy(server.proxyAddress)
        .sslSocketFactory(
          handshakeCertificates.sslSocketFactory(),
          handshakeCertificates.trustManager,
        ).hostnameVerifier(RecordingHostnameVerifier())
        .build()
    val response =
      getResponse(
        Request
          .Builder()
          .url("https://android.com/foo".toHttpUrlOrNull()!!)
          .build(),
      )
    assertContent("A", response)
    val connect1 = server.takeRequest()
    assertThat(connect1.requestLine).isEqualTo("CONNECT android.com:443 HTTP/1.1")
    assertThat(connect1.headers["Proxy-Authorization"]).isNull()
    val connect2 = server.takeRequest()
    assertThat(connect2.requestLine).isEqualTo("CONNECT android.com:443 HTTP/1.1")
    assertThat(connect2.headers["Proxy-Authorization"])
      .isEqualTo("Basic ${RecordingAuthenticator.BASE_64_CREDENTIALS}")
    val get = server.takeRequest()
    assertThat(get.requestLine).isEqualTo("GET /foo HTTP/1.1")
    assertThat(get.headers["Proxy-Authorization"]).isNull()
  }

  // Don't disconnect after building a tunnel with CONNECT
  // http://code.google.com/p/android/issues/detail?id=37221
  @Test
  fun proxyWithConnectionClose() {
    server.useHttps(handshakeCertificates.sslSocketFactory())
    server.enqueue(
      MockResponse
        .Builder()
        .inTunnel()
        .build(),
    )
    server.enqueue(MockResponse(body = "this response comes via a proxy"))
    client =
      client
        .newBuilder()
        .proxy(server.proxyAddress)
        .sslSocketFactory(
          handshakeCertificates.sslSocketFactory(),
          handshakeCertificates.trustManager,
        ).hostnameVerifier(RecordingHostnameVerifier())
        .build()
    val response =
      getResponse(
        Request
          .Builder()
          .url("https://android.com/foo")
          .header("Connection", "close")
          .build(),
      )
    assertContent("this response comes via a proxy", response)
  }

  @Test
  fun proxyWithConnectionReuse() {
    val socketFactory = handshakeCertificates.sslSocketFactory()
    val hostnameVerifier = RecordingHostnameVerifier()
    server.useHttps(socketFactory)
    server.enqueue(
      MockResponse
        .Builder()
        .inTunnel()
        .build(),
    )
    server.enqueue(MockResponse(body = "response 1"))
    server.enqueue(MockResponse(body = "response 2"))
    client =
      client
        .newBuilder()
        .proxy(server.proxyAddress)
        .sslSocketFactory(socketFactory, handshakeCertificates.trustManager)
        .hostnameVerifier(hostnameVerifier)
        .build()
    assertContent("response 1", getResponse(Request("https://android.com/foo".toHttpUrl())))
    assertContent("response 2", getResponse(Request("https://android.com/foo".toHttpUrl())))
  }

  @Test
  fun proxySelectorHttpWithConnectionReuse() {
    server.enqueue(
      MockResponse(body = "response 1"),
    )
    server.enqueue(
      MockResponse(code = 407),
    )
    client =
      client
        .newBuilder()
        .proxySelector(
          object : ProxySelector() {
            override fun select(uri: URI): List<Proxy> = listOf(server.proxyAddress)

            override fun connectFailed(
              uri: URI,
              socketAddress: SocketAddress,
              e: IOException,
            ) {
            }
          },
        ).build()
    val url = "http://android.com/foo".toHttpUrl()
    assertContent("response 1", getResponse(Request(url)))
    assertThat(getResponse(Request(url)).code).isEqualTo(407)
  }

  @Test
  fun disconnectedConnection() {
    server.enqueue(
      MockResponse
        .Builder()
        .throttleBody(2, 100, TimeUnit.MILLISECONDS)
        .body("ABCD")
        .build(),
    )
    val call = client.newCall(newRequest("/"))
    val response = call.execute()
    val inputStream = response.body.byteStream()
    assertThat(inputStream.read().toChar()).isEqualTo('A')
    call.cancel()
    assertFailsWith<IOException> {
      // Reading 'B' may succeed if it's buffered.
      inputStream.read()

      // But 'C' shouldn't be buffered (the response is throttled) and this should fail.
      inputStream.read()
    }
    inputStream.close()
  }

  @Test
  fun disconnectDuringConnect_cookieJar() {
    val callReference = AtomicReference<Call>()

    class DisconnectingCookieJar : CookieJar {
      override fun saveFromResponse(
        url: HttpUrl,
        cookies: List<Cookie>,
      ) {}

      override fun loadForRequest(url: HttpUrl): List<Cookie> {
        callReference.get().cancel()
        return emptyList()
      }
    }
    client =
      client
        .newBuilder()
        .cookieJar(DisconnectingCookieJar())
        .build()
    val call = client.newCall(newRequest("/"))
    callReference.set(call)
    assertFailsWith<IOException> {
      call.execute()
    }.also { expected ->
      assertThat(expected.message).isEqualTo("Canceled")
    }
  }

  @Test
  fun disconnectBeforeConnect() {
    server.enqueue(
      MockResponse(body = "A"),
    )
    val call = client.newCall(newRequest("/"))
    call.cancel()
    assertFailsWith<IOException> {
      call.execute()
    }
  }

  @Test
  fun defaultRequestProperty() {
    URLConnection.setDefaultRequestProperty("X-testSetDefaultRequestProperty", "A")
    assertThat(URLConnection.getDefaultRequestProperty("X-setDefaultRequestProperty")).isNull()
  }

  /**
   * Reads `count` characters from the stream. If the stream is exhausted before `count`
   * characters can be read, the remaining characters are returned and the stream is closed.
   */
  private fun readAscii(
    inputStream: InputStream,
    count: Int,
  ): String {
    val result = StringBuilder()
    for (i in 0 until count) {
      val value = inputStream.read()
      if (value == -1) {
        inputStream.close()
        break
      }
      result.append(value.toChar())
    }
    return result.toString()
  }

  @Test
  fun markAndResetWithContentLengthHeader() {
    testMarkAndReset(TransferKind.FIXED_LENGTH)
  }

  @Test
  fun markAndResetWithChunkedEncoding() {
    testMarkAndReset(TransferKind.CHUNKED)
  }

  @Test
  fun markAndResetWithNoLengthHeaders() {
    testMarkAndReset(TransferKind.END_OF_STREAM)
  }

  private fun testMarkAndReset(transferKind: TransferKind) {
    val builder = MockResponse.Builder()
    transferKind.setBody(builder, "ABCDEFGHIJKLMNOPQRSTUVWXYZ", 1024)
    server.enqueue(builder.build())
    server.enqueue(builder.build())
    val inputStream = getResponse(newRequest("/")).body.byteStream()
    assertThat(inputStream.markSupported(), "This implementation claims to support mark().")
      .isFalse()
    inputStream.mark(5)
    assertThat(readAscii(inputStream, 5)).isEqualTo("ABCDE")
    assertFailsWith<IOException> {
      inputStream.reset()
    }
    assertThat(readAscii(inputStream, Int.MAX_VALUE)).isEqualTo(
      "FGHIJKLMNOPQRSTUVWXYZ",
    )
    inputStream.close()
    assertContent("ABCDEFGHIJKLMNOPQRSTUVWXYZ", getResponse(newRequest("/")))
  }

  /**
   * We've had a bug where we forget the HTTP response when we see response code 401. This causes a
   * new HTTP request to be issued for every call into the URLConnection.
   */
  @Test
  fun unauthorizedResponseHandling() {
    val mockResponse =
      MockResponse(
        code = HttpURLConnection.HTTP_UNAUTHORIZED,
        headers = headersOf("WWW-Authenticate", "challenge"),
        body = "Unauthorized",
      )
    server.enqueue(mockResponse)
    server.enqueue(mockResponse)
    server.enqueue(mockResponse)
    val response = getResponse(newRequest("/"))
    assertThat(response.code).isEqualTo(401)
    assertThat(response.code).isEqualTo(401)
    assertThat(response.code).isEqualTo(401)
    assertThat(server.requestCount).isEqualTo(1)
    response.body.close()
  }

  @Test
  fun nonHexChunkSize() {
    server.enqueue(
      MockResponse
        .Builder()
        .body("5\r\nABCDE\r\nG\r\nFGHIJKLMNOPQRSTU\r\n0\r\n\r\n")
        .clearHeaders()
        .addHeader("Transfer-encoding: chunked")
        .build(),
    )
    assertFailsWith<IOException> {
      getResponse(newRequest("/")).use { response ->
        response.body.string()
      }
    }
  }

  @Test
  fun malformedChunkSize() {
    server.enqueue(
      MockResponse
        .Builder()
        .body("5:x\r\nABCDE\r\n0\r\n\r\n")
        .clearHeaders()
        .addHeader("Transfer-encoding: chunked")
        .build(),
    )
    assertFailsWith<IOException> {
      getResponse(newRequest("/")).use { response ->
        readAscii(response.body.byteStream(), Int.MAX_VALUE)
      }
    }
  }

  @Test
  fun extensionAfterChunkSize() {
    server.enqueue(
      MockResponse
        .Builder()
        .body("5;x\r\nABCDE\r\n0\r\n\r\n")
        .clearHeaders()
        .addHeader("Transfer-encoding: chunked")
        .build(),
    )
    getResponse(newRequest("/")).use { response -> assertContent("ABCDE", response) }
  }

  @Test
  fun missingChunkBody() {
    server.enqueue(
      MockResponse
        .Builder()
        .body("5")
        .clearHeaders()
        .addHeader("Transfer-encoding: chunked")
        .onResponseEnd(ShutdownConnection)
        .build(),
    )
    assertFailsWith<IOException> {
      getResponse(newRequest("/")).use { response ->
        readAscii(response.body.byteStream(), Int.MAX_VALUE)
      }
    }
  }

  /**
   * This test checks whether connections are gzipped by default. This behavior in not required by
   * the API, so a failure of this test does not imply a bug in the implementation.
   */
  @Test
  fun gzipEncodingEnabledByDefault() {
    server.enqueue(
      MockResponse
        .Builder()
        .body(gzip("ABCABCABC"))
        .addHeader("Content-Encoding: gzip")
        .build(),
    )
    val response = getResponse(newRequest("/"))
    assertThat(readAscii(response.body.byteStream(), Int.MAX_VALUE)).isEqualTo(
      "ABCABCABC",
    )
    assertThat(response.header("Content-Encoding")).isNull()
    assertThat(response.body.contentLength()).isEqualTo(-1L)
    val request = server.takeRequest()
    assertThat(request.headers["Accept-Encoding"]).isEqualTo("gzip")
  }

  @Test
  fun clientConfiguredGzipContentEncoding() {
    val bodyBytes = gzip("ABCDEFGHIJKLMNOPQRSTUVWXYZ")
    server.enqueue(
      MockResponse
        .Builder()
        .body(bodyBytes)
        .addHeader("Content-Encoding: gzip")
        .build(),
    )
    val response =
      getResponse(
        Request
          .Builder()
          .url(server.url("/"))
          .header("Accept-Encoding", "gzip")
          .build(),
      )
    val gunzippedIn: InputStream = GZIPInputStream(response.body.byteStream())
    assertThat(readAscii(gunzippedIn, Int.MAX_VALUE)).isEqualTo("ABCDEFGHIJKLMNOPQRSTUVWXYZ")
    assertThat(response.body.contentLength()).isEqualTo(bodyBytes.size)
    val request = server.takeRequest()
    assertThat(request.headers["Accept-Encoding"]).isEqualTo("gzip")
  }

  @Test
  fun gzipAndConnectionReuseWithFixedLength() {
    testClientConfiguredGzipContentEncodingAndConnectionReuse(TransferKind.FIXED_LENGTH, false)
  }

  @Test
  fun gzipAndConnectionReuseWithChunkedEncoding() {
    testClientConfiguredGzipContentEncodingAndConnectionReuse(TransferKind.CHUNKED, false)
  }

  @Test
  fun gzipAndConnectionReuseWithFixedLengthAndTls() {
    testClientConfiguredGzipContentEncodingAndConnectionReuse(TransferKind.FIXED_LENGTH, true)
  }

  @Test
  fun gzipAndConnectionReuseWithChunkedEncodingAndTls() {
    testClientConfiguredGzipContentEncodingAndConnectionReuse(TransferKind.CHUNKED, true)
  }

  @Test
  fun clientConfiguredCustomContentEncoding() {
    server.enqueue(
      MockResponse(
        headers = headersOf("Content-Encoding", "custom"),
        body = "ABCDE",
      ),
    )
    val response =
      getResponse(
        Request
          .Builder()
          .url(server.url("/"))
          .header("Accept-Encoding", "custom")
          .build(),
      )
    assertThat(readAscii(response.body.byteStream(), Int.MAX_VALUE)).isEqualTo("ABCDE")
    val request = server.takeRequest()
    assertThat(request.headers["Accept-Encoding"]).isEqualTo("custom")
  }

  /**
   * Test a bug where gzip input streams weren't exhausting the input stream, which corrupted the
   * request that followed or prevented connection reuse. http://code.google.com/p/android/issues/detail?id=7059
   * http://code.google.com/p/android/issues/detail?id=38817
   */
  private fun testClientConfiguredGzipContentEncodingAndConnectionReuse(
    transferKind: TransferKind,
    tls: Boolean,
  ) {
    if (tls) {
      val socketFactory = handshakeCertificates.sslSocketFactory()
      val hostnameVerifier = RecordingHostnameVerifier()
      server.useHttps(socketFactory)
      client =
        client
          .newBuilder()
          .sslSocketFactory(socketFactory, handshakeCertificates.trustManager)
          .hostnameVerifier(hostnameVerifier)
          .build()
    }
    val responseOne =
      MockResponse
        .Builder()
        .addHeader("Content-Encoding: gzip")
    transferKind.setBody(responseOne, gzip("one (gzipped)"), 5)
    server.enqueue(responseOne.build())
    val responseTwo = MockResponse.Builder()
    transferKind.setBody(responseTwo, "two (identity)", 5)
    server.enqueue(responseTwo.build())
    val response1 =
      getResponse(
        Request
          .Builder()
          .header("Accept-Encoding", "gzip")
          .url(server.url("/"))
          .build(),
      )
    val gunzippedIn: InputStream = GZIPInputStream(response1.body.byteStream())
    assertThat(readAscii(gunzippedIn, Int.MAX_VALUE)).isEqualTo("one (gzipped)")
    assertThat(server.takeRequest().exchangeIndex).isEqualTo(0)
    val response2 =
      getResponse(
        Request
          .Builder()
          .url(server.url("/"))
          .build(),
      )
    assertThat(readAscii(response2.body.byteStream(), Int.MAX_VALUE)).isEqualTo("two (identity)")
    assertThat(server.takeRequest().exchangeIndex).isEqualTo(1)
  }

  @Test
  fun transparentGzipWorksAfterExceptionRecovery() {
    server.enqueue(
      MockResponse
        .Builder()
        .body("a")
        .onResponseEnd(
          CloseSocket(
            closeSocket = false,
            shutdownInput = true,
          ),
        ).build(),
    )
    server.enqueue(
      MockResponse
        .Builder()
        .addHeader("Content-Encoding: gzip")
        .body(gzip("b"))
        .build(),
    )

    // Seed the pool with a bad connection.
    assertContent("a", getResponse(newRequest("/")))

    // Give the server time to disconnect.
    Thread.sleep(500)

    // This connection will need to be recovered. When it is, transparent gzip should still work!
    assertContent("b", getResponse(newRequest("/")))
    assertThat(server.takeRequest().exchangeIndex).isEqualTo(0)
    // Connection is not pooled.
    assertThat(server.takeRequest().exchangeIndex).isEqualTo(0)
  }

  @Test
  fun endOfStreamResponseIsNotPooled() {
    client.connectionPool.evictAll()
    server.enqueue(
      MockResponse
        .Builder()
        .body("{}")
        .clearHeaders()
        .onResponseEnd(ShutdownConnection)
        .build(),
    )
    val response = getResponse(newRequest("/"))
    assertContent("{}", response)
    assertThat(client.connectionPool.idleConnectionCount()).isEqualTo(0)
  }

  @Test
  fun earlyDisconnectDoesntHarmPoolingWithChunkedEncoding() {
    testEarlyDisconnectDoesntHarmPooling(TransferKind.CHUNKED)
  }

  @Test
  fun earlyDisconnectDoesntHarmPoolingWithFixedLengthEncoding() {
    testEarlyDisconnectDoesntHarmPooling(TransferKind.FIXED_LENGTH)
  }

  private fun testEarlyDisconnectDoesntHarmPooling(transferKind: TransferKind) {
    val mockResponse1 = MockResponse.Builder()
    transferKind.setBody(mockResponse1, "ABCDEFGHIJK", 1024)
    server.enqueue(mockResponse1.build())
    val mockResponse2 = MockResponse.Builder()
    transferKind.setBody(mockResponse2, "LMNOPQRSTUV", 1024)
    server.enqueue(mockResponse2.build())
    val call1 = client.newCall(newRequest("/"))
    val response1 = call1.execute()
    val in1 = response1.body.byteStream()
    assertThat(readAscii(in1, 5)).isEqualTo("ABCDE")
    in1.close()
    call1.cancel()
    val call2 = client.newCall(newRequest("/"))
    val response2 = call2.execute()
    val in2 = response2.body.byteStream()
    assertThat(readAscii(in2, 5)).isEqualTo("LMNOP")
    in2.close()
    call2.cancel()
    assertThat(server.takeRequest().exchangeIndex).isEqualTo(0)
    // Connection is pooled!
    assertThat(server.takeRequest().exchangeIndex).isEqualTo(1)
  }

  @Test
  fun streamDiscardingIsTimely() {
    // This response takes at least a full second to serve: 10,000 bytes served 100 bytes at a time.
    server.enqueue(
      MockResponse
        .Builder()
        .body(Buffer().write(ByteArray(10000)))
        .throttleBody(100, 10, TimeUnit.MILLISECONDS)
        .build(),
    )
    server.enqueue(
      MockResponse(body = "A"),
    )
    val startNanos = System.nanoTime()
    val connection1 = getResponse(newRequest("/"))
    val inputStream = connection1.body.byteStream()
    inputStream.close()
    val elapsedNanos = System.nanoTime() - startNanos
    val elapsedMillis = TimeUnit.NANOSECONDS.toMillis(elapsedNanos)

    // If we're working correctly, this should be greater than 100ms, but less than double that.
    // Previously we had a bug where we would download the entire response body as long as no
    // individual read took longer than 100ms.
    assertThat(elapsedMillis).isLessThan(500L)

    // Do another request to confirm that the discarded connection was not pooled.
    assertContent("A", getResponse(newRequest("/")))
    assertThat(server.takeRequest().exchangeIndex).isEqualTo(0)
    // Connection is not pooled.
    assertThat(server.takeRequest().exchangeIndex).isEqualTo(0)
  }

  @Test
  fun setChunkedStreamingMode() {
    server.enqueue(MockResponse())
    val response =
      getResponse(
        Request(
          url = server.url("/"),
          body = TransferKind.CHUNKED.newRequestBody("ABCDEFGHIJKLMNOPQ"),
        ),
      )
    assertThat(response.code).isEqualTo(200)
    val request = server.takeRequest()
    assertThat(request.body?.utf8()).isEqualTo("ABCDEFGHIJKLMNOPQ")
    assertThat(request.chunkSizes).isEqualTo(listOf("ABCDEFGHIJKLMNOPQ".length))
  }

  @Test
  fun authenticateWithFixedLengthStreaming() {
    testAuthenticateWithStreamingPost(TransferKind.FIXED_LENGTH)
  }

  @Test
  fun authenticateWithChunkedStreaming() {
    testAuthenticateWithStreamingPost(TransferKind.CHUNKED)
  }

  private fun testAuthenticateWithStreamingPost(streamingMode: TransferKind) {
    server.enqueue(
      MockResponse(
        code = 401,
        headers = headersOf("WWW-Authenticate", "Basic realm=\"protected area\""),
        body = "Please authenticate.",
      ),
    )
    server.enqueue(
      MockResponse(body = "Authenticated!"),
    )
    java.net.Authenticator.setDefault(RecordingAuthenticator())
    client =
      client
        .newBuilder()
        .authenticator(JavaNetAuthenticator())
        .build()
    val request =
      Request(
        url = server.url("/"),
        body = streamingMode.newRequestBody("ABCD"),
      )
    val response = getResponse(request)
    assertThat(response.code).isEqualTo(200)
    assertContent("Authenticated!", response)

    // No authorization header for the request...
    val recordedRequest = server.takeRequest()
    assertThat(recordedRequest.headers["Authorization"]).isNull()
    assertThat(recordedRequest.body?.utf8()).isEqualTo("ABCD")
  }

  @Test
  fun postBodyRetransmittedAfterAuthorizationFail() {
    postBodyRetransmittedAfterAuthorizationFail("abc")
  }

  @Test
  fun postBodyRetransmittedAfterAuthorizationFail_HTTP_2() {
    platform.assumeHttp2Support()
    enableProtocol(Protocol.HTTP_2)
    postBodyRetransmittedAfterAuthorizationFail("abc")
  }

  /** Don't explode when resending an empty post. https://github.com/lysine-dev/okhttp/issues/1131  */
  @Test
  fun postEmptyBodyRetransmittedAfterAuthorizationFail() {
    postBodyRetransmittedAfterAuthorizationFail("")
  }

  @Test
  fun postEmptyBodyRetransmittedAfterAuthorizationFail_HTTP_2() {
    platform.assumeHttp2Support()
    enableProtocol(Protocol.HTTP_2)
    postBodyRetransmittedAfterAuthorizationFail("")
  }

  private fun postBodyRetransmittedAfterAuthorizationFail(body: String) {
    server.enqueue(
      MockResponse(code = 401),
    )
    server.enqueue(MockResponse())
    val credential = basic("jesse", "secret")
    client =
      client
        .newBuilder()
        .authenticator(RecordingOkAuthenticator(credential, null))
        .build()
    val response =
      getResponse(
        Request(
          url = server.url("/"),
          body = body.toRequestBody(),
        ),
      )
    assertThat(response.code).isEqualTo(200)
    response.body.byteStream().close()
    val recordedRequest1 = server.takeRequest()
    assertThat(recordedRequest1.method).isEqualTo("POST")
    assertThat(recordedRequest1.body?.utf8()).isEqualTo(body)
    assertThat(recordedRequest1.headers["Authorization"]).isNull()
    val recordedRequest2 = server.takeRequest()
    assertThat(recordedRequest2.method).isEqualTo("POST")
    assertThat(recordedRequest2.body?.utf8()).isEqualTo(body)
    assertThat(recordedRequest2.headers["Authorization"]).isEqualTo(credential)
  }

  @Test
  fun nonStandardAuthenticationScheme() {
    val calls = authCallsForHeader("WWW-Authenticate: Foo")
    assertThat(calls).isEqualTo(emptyList<String>())
  }

  @Test
  fun nonStandardAuthenticationSchemeWithRealm() {
    val calls = authCallsForHeader("WWW-Authenticate: Foo realm=\"Bar\"")
    assertThat(calls.size).isEqualTo(0)
  }

  // Digest auth is currently unsupported. Test that digest requests should fail reasonably.
  // http://code.google.com/p/android/issues/detail?id=11140
  @Test
  fun digestAuthentication() {
    val calls =
      authCallsForHeader(
        "WWW-Authenticate: Digest " +
          "realm=\"testrealm@host.com\", qop=\"auth,auth-int\", " +
          "nonce=\"dcd98b7102dd2f0e8b11d0f600bfb0c093\", " +
          "opaque=\"5ccc069c403ebaf9f0171e9517f40e41\"",
      )
    assertThat(calls.size).isEqualTo(0)
  }

  @Test
  fun allAttributesSetInServerAuthenticationCallbacks() {
    val calls = authCallsForHeader("WWW-Authenticate: Basic realm=\"Bar\"")
    assertThat(calls.size).isEqualTo(1)
    val url = server.url("/").toUrl()
    val call = calls[0]
    assertThat(call).contains("host=" + url.host)
    assertThat(call).contains("port=" + url.port)
    assertThat(call).contains("site=" + url.host)
    assertThat(call).contains("url=$url")
    assertThat(call).contains("type=" + java.net.Authenticator.RequestorType.SERVER)
    assertThat(call).contains("prompt=Bar")
    assertThat(call).contains("protocol=http")
    assertThat(call.lowercase(Locale.US))
      .contains("scheme=basic") // lowercase for the RI.
  }

  @Test
  fun allAttributesSetInProxyAuthenticationCallbacks() {
    val calls = authCallsForHeader("Proxy-Authenticate: Basic realm=\"Bar\"")
    assertThat(calls.size).isEqualTo(1)
    val url = server.url("/").toUrl()
    val call = calls[0]
    assertThat(call).contains("host=" + url.host)
    assertThat(call).contains("port=" + url.port)
    assertThat(call).contains("site=" + url.host)
    assertThat(call).contains("url=http://android.com")
    assertThat(call).contains("type=" + java.net.Authenticator.RequestorType.PROXY)
    assertThat(call).contains("prompt=Bar")
    assertThat(call).contains("protocol=http")
    assertThat(call.lowercase(Locale.US)).contains("scheme=basic")
  }

  private fun authCallsForHeader(authHeader: String): List<String> {
    val proxy = authHeader.startsWith("Proxy-")
    val responseCode = if (proxy) 407 else 401
    val authenticator = RecordingAuthenticator(null)
    java.net.Authenticator.setDefault(authenticator)
    server.enqueue(
      MockResponse
        .Builder()
        .code(responseCode)
        .addHeader(authHeader)
        .body("Please authenticate.")
        .build(),
    )
    val response: Response
    if (proxy) {
      client =
        client
          .newBuilder()
          .proxy(server.proxyAddress)
          .proxyAuthenticator(JavaNetAuthenticator())
          .build()
      response = getResponse(Request("http://android.com/".toHttpUrl()))
    } else {
      client =
        client
          .newBuilder()
          .authenticator(JavaNetAuthenticator())
          .build()
      response = getResponse(newRequest("/"))
    }
    assertThat(response.code).isEqualTo(responseCode)
    response.body.byteStream().close()
    return authenticator.calls
  }

  @Test
  fun setValidRequestMethod() {
    assertMethodForbidsRequestBody("GET")
    assertMethodPermitsRequestBody("DELETE")
    assertMethodForbidsRequestBody("HEAD")
    assertMethodPermitsRequestBody("OPTIONS")
    assertMethodPermitsRequestBody("POST")
    assertMethodPermitsRequestBody("PUT")
    assertMethodPermitsRequestBody("TRACE")
    assertMethodPermitsRequestBody("PATCH")
    assertMethodPermitsNoRequestBody("GET")
    assertMethodPermitsNoRequestBody("DELETE")
    assertMethodPermitsNoRequestBody("HEAD")
    assertMethodPermitsNoRequestBody("OPTIONS")
    assertMethodForbidsNoRequestBody("POST")
    assertMethodForbidsNoRequestBody("PUT")
    assertMethodPermitsNoRequestBody("TRACE")
    assertMethodForbidsNoRequestBody("PATCH")
  }

  private fun assertMethodPermitsRequestBody(requestMethod: String) {
    val request =
      Request
        .Builder()
        .url(server.url("/"))
        .method(requestMethod, "abc".toRequestBody(null))
        .build()
    assertThat(request.method).isEqualTo(requestMethod)
  }

  private fun assertMethodForbidsRequestBody(requestMethod: String) {
    assertFailsWith<IllegalArgumentException> {
      Request
        .Builder()
        .url(server.url("/"))
        .method(requestMethod, "abc".toRequestBody(null))
        .build()
    }
  }

  private fun assertMethodPermitsNoRequestBody(requestMethod: String) {
    val request =
      Request
        .Builder()
        .url(server.url("/"))
        .method(requestMethod, null)
        .build()
    assertThat(request.method).isEqualTo(requestMethod)
  }

  private fun assertMethodForbidsNoRequestBody(requestMethod: String) {
    assertFailsWith<IllegalArgumentException> {
      Request
        .Builder()
        .url(server.url("/"))
        .method(requestMethod, null)
        .build()
    }
  }

  @Test
  fun setInvalidRequestMethodLowercase() {
    assertValidRequestMethod("get")
  }

  @Test
  fun setInvalidRequestMethodConnect() {
    assertValidRequestMethod("CONNECT")
  }

  private fun assertValidRequestMethod(requestMethod: String) {
    server.enqueue(MockResponse())
    val response =
      getResponse(
        Request
          .Builder()
          .url(server.url("/"))
          .method(requestMethod, null)
          .build(),
      )
    assertThat(response.code).isEqualTo(200)
    val recordedRequest = server.takeRequest()
    assertThat(recordedRequest.method).isEqualTo(requestMethod)
  }

  @Test
  fun shoutcast() {
    server.enqueue(
      MockResponse
        .Builder()
        .status("ICY 200 OK")
        .addHeader("Accept-Ranges: none")
        .addHeader("Content-Type: audio/mpeg")
        .addHeader("icy-br:128")
        .addHeader("ice-audio-info: bitrate=128;samplerate=44100;channels=2")
        .addHeader("icy-br:128")
        .addHeader("icy-description:Rock")
        .addHeader("icy-genre:riders")
        .addHeader("icy-name:A2RRock")
        .addHeader("icy-pub:1")
        .addHeader("icy-url:http://www.A2Rradio.com")
        .addHeader("Server: Icecast 2.3.3-kh8")
        .addHeader("Cache-Control: no-cache")
        .addHeader("Pragma: no-cache")
        .addHeader("Expires: Mon, 26 Jul 1997 05:00:00 GMT")
        .addHeader("icy-metaint:16000")
        .body("mp3 data")
        .build(),
    )
    val response = getResponse(newRequest("/"))
    assertThat(response.code).isEqualTo(200)
    assertThat(response.message).isEqualTo("OK")
    assertContent("mp3 data", response)
  }

  @Test
  fun ntripr1() {
    server.enqueue(
      MockResponse
        .Builder()
        .status("SOURCETABLE 200 OK")
        .addHeader("Server: NTRIP Caster 1.5.5/1.0")
        .addHeader("Date: 23/Jan/2004:08:54:59 UTC")
        .addHeader("Content-Type: text/plain")
        .body("STR;FFMJ2;Frankfurt;RTCM 2.1;1(1),3(19),16(59);0;GPS;GREF;DEU;50.12;8.68;0;1;GPSNet V2.10;none;N;N;560;Demo\nENDSOURCETABLE")
        .build(),
    )
    val response = getResponse(newRequest("/"))
    assertThat(response.code).isEqualTo(200)
    assertThat(response.message).isEqualTo("OK")
    assertContent(
      "STR;FFMJ2;Frankfurt;RTCM 2.1;1(1),3(19),16(59);0;GPS;GREF;DEU;50.12;8.68;0;1;GPSNet V2.10;none;N;N;560;Demo\nENDSOURCETABLE",
      response,
    )
  }

  @Test
  fun secureFixedLengthStreaming() {
    testSecureStreamingPost(TransferKind.FIXED_LENGTH)
  }

  @Test
  fun secureChunkedStreaming() {
    testSecureStreamingPost(TransferKind.CHUNKED)
  }

  /**
   * Users have reported problems using HTTPS with streaming request bodies.
   * http://code.google.com/p/android/issues/detail?id=12860
   */
  private fun testSecureStreamingPost(streamingMode: TransferKind) {
    server.useHttps(handshakeCertificates.sslSocketFactory())
    server.enqueue(
      MockResponse(body = "Success!"),
    )
    client =
      client
        .newBuilder()
        .sslSocketFactory(
          handshakeCertificates.sslSocketFactory(),
          handshakeCertificates.trustManager,
        ).hostnameVerifier(RecordingHostnameVerifier())
        .build()
    val response =
      getResponse(
        Request(
          url = server.url("/"),
          body = streamingMode.newRequestBody("ABCD"),
        ),
      )
    assertThat(readAscii(response.body.byteStream(), Int.MAX_VALUE)).isEqualTo(
      "Success!",
    )
    val request = server.takeRequest()
    assertThat(request.requestLine).isEqualTo("POST / HTTP/1.1")
    if (streamingMode === TransferKind.FIXED_LENGTH) {
      assertThat(request.chunkSizes).isNull()
    } else if (streamingMode === TransferKind.CHUNKED) {
      assertThat(request.chunkSizes).isEqualTo(listOf(4))
    }
    assertThat(request.body?.utf8()).isEqualTo("ABCD")
  }

  @Test
  fun authenticateWithPost() {
    val pleaseAuthenticate =
      MockResponse(
        code = 401,
        headers = headersOf("WWW-Authenticate", "Basic realm=\"protected area\""),
        body = "Please authenticate.",
      )
    // Fail auth three times...
    server.enqueue(pleaseAuthenticate)
    server.enqueue(pleaseAuthenticate)
    server.enqueue(pleaseAuthenticate)
    // ...then succeed the fourth time.
    server.enqueue(
      MockResponse(body = "Successful auth!"),
    )
    java.net.Authenticator.setDefault(RecordingAuthenticator())
    client =
      client
        .newBuilder()
        .authenticator(JavaNetAuthenticator())
        .build()
    val response =
      getResponse(
        Request(
          url = server.url("/"),
          body = "ABCD".toRequestBody(null),
        ),
      )
    assertThat(readAscii(response.body.byteStream(), Int.MAX_VALUE)).isEqualTo(
      "Successful auth!",
    )

    // No authorization header for the first request...
    var request = server.takeRequest()
    assertThat(request.headers["Authorization"]).isNull()

    // ...but the three requests that follow include an authorization header.
    for (i in 0..2) {
      request = server.takeRequest()
      assertThat(request.requestLine).isEqualTo("POST / HTTP/1.1")
      assertThat(request.headers["Authorization"]).isEqualTo(
        "Basic " + RecordingAuthenticator.BASE_64_CREDENTIALS,
      )
      assertThat(request.body?.utf8()).isEqualTo("ABCD")
    }
  }

  @Test
  fun authenticateWithGet() {
    val pleaseAuthenticate =
      MockResponse(
        code = 401,
        headers = headersOf("WWW-Authenticate", "Basic realm=\"protected area\""),
        body = "Please authenticate.",
      )
    // Fail auth three times...
    server.enqueue(pleaseAuthenticate)
    server.enqueue(pleaseAuthenticate)
    server.enqueue(pleaseAuthenticate)
    // ...then succeed the fourth time.
    server.enqueue(
      MockResponse(body = "Successful auth!"),
    )
    java.net.Authenticator.setDefault(RecordingAuthenticator())
    client =
      client
        .newBuilder()
        .authenticator(JavaNetAuthenticator())
        .build()
    val response = getResponse(newRequest("/"))
    assertThat(readAscii(response.body.byteStream(), Int.MAX_VALUE))
      .isEqualTo("Successful auth!")

    // No authorization header for the first request...
    var request = server.takeRequest()
    assertThat(request.headers["Authorization"]).isNull()

    // ...but the three requests that follow requests include an authorization header.
    for (i in 0..2) {
      request = server.takeRequest()
      assertThat(request.requestLine).isEqualTo("GET / HTTP/1.1")
      assertThat(request.headers["Authorization"])
        .isEqualTo("Basic ${RecordingAuthenticator.BASE_64_CREDENTIALS}")
    }
  }

  @Test
  fun authenticateWithCharset() {
    server.enqueue(
      MockResponse(
        code = 401,
        headers =
          headersOf(
            "WWW-Authenticate",
            "Basic realm=\"protected area\", charset=\"UTF-8\"",
          ),
        body = "Please authenticate with UTF-8.",
      ),
    )
    server.enqueue(
      MockResponse(
        code = 401,
        headers =
          headersOf(
            "WWW-Authenticate",
            "Basic realm=\"protected area\"",
          ),
        body = "Please authenticate with ISO-8859-1.",
      ),
    )
    server.enqueue(
      MockResponse(body = "Successful auth!"),
    )
    java.net.Authenticator.setDefault(
      RecordingAuthenticator(
        PasswordAuthentication("username", "mötorhead".toCharArray()),
      ),
    )
    client =
      client
        .newBuilder()
        .authenticator(JavaNetAuthenticator())
        .build()
    val response = getResponse(newRequest("/"))
    assertThat(readAscii(response.body.byteStream(), Int.MAX_VALUE))
      .isEqualTo("Successful auth!")

    // No authorization header for the first request...
    val request1 = server.takeRequest()
    assertThat(request1.headers["Authorization"]).isNull()

    // UTF-8 encoding for the first credential.
    val request2 = server.takeRequest()
    assertThat(request2.headers["Authorization"]).isEqualTo(
      "Basic dXNlcm5hbWU6bcO2dG9yaGVhZA==",
    )

    // ISO-8859-1 encoding for the second credential.
    val request3 = server.takeRequest()
    assertThat(request3.headers["Authorization"])
      .isEqualTo("Basic dXNlcm5hbWU6bfZ0b3JoZWFk")
  }

  /** https://code.google.com/p/android/issues/detail?id=74026  */
  @Test
  fun authenticateWithGetAndTransparentGzip() {
    val pleaseAuthenticate =
      MockResponse(
        code = 401,
        headers = headersOf("WWW-Authenticate", "Basic realm=\"protected area\""),
        body = "Please authenticate.",
      )
    // Fail auth three times...
    server.enqueue(pleaseAuthenticate)
    server.enqueue(pleaseAuthenticate)
    server.enqueue(pleaseAuthenticate)
    // ...then succeed the fourth time.
    val successfulResponse =
      MockResponse
        .Builder()
        .addHeader("Content-Encoding", "gzip")
        .body(gzip("Successful auth!"))
        .build()
    server.enqueue(successfulResponse)
    java.net.Authenticator.setDefault(RecordingAuthenticator())
    client =
      client
        .newBuilder()
        .authenticator(JavaNetAuthenticator())
        .build()
    val response = getResponse(newRequest("/"))
    assertThat(readAscii(response.body.byteStream(), Int.MAX_VALUE))
      .isEqualTo("Successful auth!")

    // no authorization header for the first request...
    var request = server.takeRequest()
    assertThat(request.headers["Authorization"]).isNull()

    // ...but the three requests that follow requests include an authorization header
    for (i in 0..2) {
      request = server.takeRequest()
      assertThat(request.requestLine).isEqualTo("GET / HTTP/1.1")
      assertThat(request.headers["Authorization"]).isEqualTo(
        "Basic ${RecordingAuthenticator.BASE_64_CREDENTIALS}",
      )
    }
  }

  /** https://github.com/lysine-dev/okhttp/issues/342  */
  @Test
  fun authenticateRealmUppercase() {
    server.enqueue(
      MockResponse(
        code = 401,
        headers = headersOf("wWw-aUtHeNtIcAtE", "bAsIc rEaLm=\"pRoTeCtEd aReA\""),
        body = "Please authenticate.",
      ),
    )
    server.enqueue(
      MockResponse(body = "Successful auth!"),
    )
    java.net.Authenticator.setDefault(RecordingAuthenticator())
    client =
      client
        .newBuilder()
        .authenticator(JavaNetAuthenticator())
        .build()
    val response = getResponse(newRequest("/"))
    assertThat(readAscii(response.body.byteStream(), Int.MAX_VALUE))
      .isEqualTo("Successful auth!")
  }

  @Test
  fun redirectedWithChunkedEncoding() {
    testRedirected(TransferKind.CHUNKED, true)
  }

  @Test
  fun redirectedWithContentLengthHeader() {
    testRedirected(TransferKind.FIXED_LENGTH, true)
  }

  @Test
  fun redirectedWithNoLengthHeaders() {
    testRedirected(TransferKind.END_OF_STREAM, false)
  }

  private fun testRedirected(
    transferKind: TransferKind,
    reuse: Boolean,
  ) {
    val mockResponse =
      MockResponse
        .Builder()
        .code(HttpURLConnection.HTTP_MOVED_TEMP)
        .addHeader("Location: /foo")
    transferKind.setBody(mockResponse, "This page has moved!", 10)
    server.enqueue(mockResponse.build())
    server.enqueue(MockResponse(body = "This is the new location!"))
    val response = getResponse(newRequest("/"))
    assertThat(readAscii(response.body.byteStream(), Int.MAX_VALUE)).isEqualTo(
      "This is the new location!",
    )
    val first = server.takeRequest()
    assertThat(first.requestLine).isEqualTo("GET / HTTP/1.1")
    val retry = server.takeRequest()
    assertThat(retry.requestLine).isEqualTo("GET /foo HTTP/1.1")
    if (reuse) {
      assertThat(retry.exchangeIndex, "Expected connection reuse")
        .isEqualTo(1)
    }
  }

  @Test
  fun redirectedOnHttps() {
    server.useHttps(handshakeCertificates.sslSocketFactory())
    server.enqueue(
      MockResponse(
        code = HttpURLConnection.HTTP_MOVED_TEMP,
        headers = headersOf("Location", "/foo"),
        body = "This page has moved!",
      ),
    )
    server.enqueue(
      MockResponse(body = "This is the new location!"),
    )
    client =
      client
        .newBuilder()
        .sslSocketFactory(
          handshakeCertificates.sslSocketFactory(),
          handshakeCertificates.trustManager,
        ).hostnameVerifier(RecordingHostnameVerifier())
        .build()
    val response = getResponse(newRequest("/"))
    assertThat(readAscii(response.body.byteStream(), Int.MAX_VALUE)).isEqualTo(
      "This is the new location!",
    )
    val first = server.takeRequest()
    assertThat(first.requestLine).isEqualTo("GET / HTTP/1.1")
    val retry = server.takeRequest()
    assertThat(retry.requestLine).isEqualTo("GET /foo HTTP/1.1")
    assertThat(retry.exchangeIndex, "Expected connection reuse")
      .isEqualTo(1)
  }

  @Test
  fun notRedirectedFromHttpsToHttp() {
    server.useHttps(handshakeCertificates.sslSocketFactory())
    server.enqueue(
      MockResponse(
        code = HttpURLConnection.HTTP_MOVED_TEMP,
        headers = headersOf("Location", "http://anyhost/foo"),
        body = "This page has moved!",
      ),
    )
    client =
      client
        .newBuilder()
        .followSslRedirects(false)
        .sslSocketFactory(
          handshakeCertificates.sslSocketFactory(),
          handshakeCertificates.trustManager,
        ).hostnameVerifier(RecordingHostnameVerifier())
        .build()
    val response = getResponse(newRequest("/"))
    assertThat(readAscii(response.body.byteStream(), Int.MAX_VALUE))
      .isEqualTo("This page has moved!")
  }

  @Test
  fun notRedirectedFromHttpToHttps() {
    server.enqueue(
      MockResponse(
        code = HttpURLConnection.HTTP_MOVED_TEMP,
        headers = headersOf("Location", "https://anyhost/foo"),
        body = "This page has moved!",
      ),
    )
    client =
      client
        .newBuilder()
        .followSslRedirects(false)
        .build()
    val response = getResponse(newRequest("/"))
    assertThat(readAscii(response.body.byteStream(), Int.MAX_VALUE))
      .isEqualTo("This page has moved!")
  }

  @Test
  fun redirectedFromHttpsToHttpFollowingProtocolRedirects() {
    server2.enqueue(
      MockResponse(body = "This is insecure HTTP!"),
    )
    server.useHttps(handshakeCertificates.sslSocketFactory())
    server.enqueue(
      MockResponse(
        code = HttpURLConnection.HTTP_MOVED_TEMP,
        headers = headersOf("Location", server2.url("/").toString()),
        body = "This page has moved!",
      ),
    )
    client =
      client
        .newBuilder()
        .sslSocketFactory(
          handshakeCertificates.sslSocketFactory(),
          handshakeCertificates.trustManager,
        ).hostnameVerifier(RecordingHostnameVerifier())
        .followSslRedirects(true)
        .build()
    val response = getResponse(newRequest("/"))
    assertContent("This is insecure HTTP!", response)
    assertThat(response.handshake).isNull()
  }

  @Test
  fun redirectedFromHttpToHttpsFollowingProtocolRedirects() {
    server2.useHttps(handshakeCertificates.sslSocketFactory())
    server2.enqueue(
      MockResponse(body = "This is secure HTTPS!"),
    )
    server.enqueue(
      MockResponse(
        code = HttpURLConnection.HTTP_MOVED_TEMP,
        headers = headersOf("Location", server2.url("/").toString()),
        body = "This page has moved!",
      ),
    )
    client =
      client
        .newBuilder()
        .sslSocketFactory(
          handshakeCertificates.sslSocketFactory(),
          handshakeCertificates.trustManager,
        ).hostnameVerifier(RecordingHostnameVerifier())
        .followSslRedirects(true)
        .build()
    val response = getResponse(newRequest("/"))
    assertContent("This is secure HTTPS!", response)
  }

  @Test
  fun redirectToAnotherOriginServer() {
    redirectToAnotherOriginServer(false)
  }

  @Test
  fun redirectToAnotherOriginServerWithHttps() {
    redirectToAnotherOriginServer(true)
  }

  private fun redirectToAnotherOriginServer(https: Boolean) {
    if (https) {
      server.useHttps(handshakeCertificates.sslSocketFactory())
      server2.useHttps(handshakeCertificates.sslSocketFactory())
      server2.protocolNegotiationEnabled = false
      client =
        client
          .newBuilder()
          .sslSocketFactory(
            handshakeCertificates.sslSocketFactory(),
            handshakeCertificates.trustManager,
          ).hostnameVerifier(RecordingHostnameVerifier())
          .build()
    }
    server2.enqueue(
      MockResponse(body = "This is the 2nd server!"),
    )
    server2.enqueue(
      MockResponse(body = "This is the 2nd server, again!"),
    )
    server.enqueue(
      MockResponse(
        code = HttpURLConnection.HTTP_MOVED_TEMP,
        headers = headersOf("Location", server2.url("/").toString()),
        body = "This page has moved!",
      ),
    )
    server.enqueue(
      MockResponse(body = "This is the first server again!"),
    )
    val response = getResponse(newRequest("/"))
    assertContent("This is the 2nd server!", response)
    assertThat(response.request.url).isEqualTo(
      server2.url("/"),
    )

    // make sure the first server was careful to recycle the connection
    assertContent("This is the first server again!", getResponse(Request(server.url("/"))))
    assertContent("This is the 2nd server, again!", getResponse(Request(server2.url("/"))))
    val server1Host = server.hostName + ":" + server.port
    val server2Host = server2.hostName + ":" + server2.port
    assertThat(server.takeRequest().headers["Host"]).isEqualTo(server1Host)
    assertThat(server2.takeRequest().headers["Host"]).isEqualTo(server2Host)
    assertThat(server.takeRequest().exchangeIndex, "Expected connection reuse")
      .isEqualTo(1)
    assertThat(server2.takeRequest().exchangeIndex, "Expected connection reuse")
      .isEqualTo(1)
  }

  @Test
  fun redirectWithProxySelector() {
    val proxySelectionRequests: MutableList<URI> = ArrayList()
    client =
      client
        .newBuilder()
        .proxySelector(
          object : ProxySelector() {
            override fun select(uri: URI): List<Proxy> {
              proxySelectionRequests.add(uri)
              val proxyServer = if (uri.port == server.port) server else server2
              return listOf(proxyServer.proxyAddress)
            }

            override fun connectFailed(
              uri: URI,
              address: SocketAddress,
              failure: IOException,
            ): Unit = throw AssertionError()
          },
        ).build()
    server2.enqueue(
      MockResponse(body = "This is the 2nd server!"),
    )
    server.enqueue(
      MockResponse(
        code = HttpURLConnection.HTTP_MOVED_TEMP,
        headers = headersOf("Location", server2.url("/b").toString()),
        body = "This page has moved!",
      ),
    )
    assertContent("This is the 2nd server!", getResponse(newRequest("/a")))
    assertThat(proxySelectionRequests).isEqualTo(
      listOf(
        server.url("/").toUrl().toURI(),
        server2.url("/").toUrl().toURI(),
      ),
    )
  }

  @Test
  fun redirectWithAuthentication() {
    server2.enqueue(
      MockResponse(body = "Page 2"),
    )
    server.enqueue(
      MockResponse(code = 401),
    )
    server.enqueue(
      MockResponse(
        code = 302,
        headers = headersOf("Location", server2.url("/b").toString()),
      ),
    )
    client =
      client
        .newBuilder()
        .authenticator(RecordingOkAuthenticator(basic("jesse", "secret"), null))
        .build()
    assertContent("Page 2", getResponse(newRequest("/a")))
    val redirectRequest = server2.takeRequest()
    assertThat(redirectRequest.headers["Authorization"]).isNull()
    assertThat(redirectRequest.url.encodedPath).isEqualTo("/b")
  }

  @Test
  fun response300MultipleChoiceWithPost() {
    // Chrome doesn't follow the redirect, but Firefox and the RI both do
    testResponseRedirectedWithPost(HttpURLConnection.HTTP_MULT_CHOICE, TransferKind.END_OF_STREAM)
  }

  @Test
  fun response301MovedPermanentlyWithPost() {
    testResponseRedirectedWithPost(HttpURLConnection.HTTP_MOVED_PERM, TransferKind.END_OF_STREAM)
  }

  @Test
  fun response302MovedTemporarilyWithPost() {
    testResponseRedirectedWithPost(HttpURLConnection.HTTP_MOVED_TEMP, TransferKind.END_OF_STREAM)
  }

  @Test
  fun response303SeeOtherWithPost() {
    testResponseRedirectedWithPost(HttpURLConnection.HTTP_SEE_OTHER, TransferKind.END_OF_STREAM)
  }

  @Test
  fun postRedirectToGetWithChunkedRequest() {
    testResponseRedirectedWithPost(HttpURLConnection.HTTP_MOVED_TEMP, TransferKind.CHUNKED)
  }

  @Test
  fun postRedirectToGetWithStreamedRequest() {
    testResponseRedirectedWithPost(HttpURLConnection.HTTP_MOVED_TEMP, TransferKind.FIXED_LENGTH)
  }

  private fun testResponseRedirectedWithPost(
    redirectCode: Int,
    transferKind: TransferKind,
  ) {
    server.enqueue(
      MockResponse(
        code = redirectCode,
        headers = headersOf("Location", "/page2"),
        body = "This page has moved!",
      ),
    )
    server.enqueue(
      MockResponse(body = "Page 2"),
    )
    val response =
      getResponse(
        Request(
          url = server.url("/page1"),
          body = transferKind.newRequestBody("ABCD"),
        ),
      )
    assertThat(readAscii(response.body.byteStream(), Int.MAX_VALUE))
      .isEqualTo("Page 2")
    val page1 = server.takeRequest()
    assertThat(page1.requestLine).isEqualTo("POST /page1 HTTP/1.1")
    assertThat(page1.body?.utf8()).isEqualTo("ABCD")
    val page2 = server.takeRequest()
    assertThat(page2.requestLine).isEqualTo("GET /page2 HTTP/1.1")
  }

  @Test
  fun redirectedPostStripsRequestBodyHeaders() {
    server.enqueue(
      MockResponse(
        code = HttpURLConnection.HTTP_MOVED_TEMP,
        headers = headersOf("Location", "/page2"),
      ),
    )
    server.enqueue(
      MockResponse(body = "Page 2"),
    )
    val response =
      getResponse(
        Request
          .Builder()
          .url(server.url("/page1"))
          .post("ABCD".toRequestBody("text/plain; charset=utf-8".toMediaType()))
          .header("Transfer-Encoding", "identity")
          .build(),
      )
    assertThat(readAscii(response.body.byteStream(), Int.MAX_VALUE))
      .isEqualTo("Page 2")
    assertThat(server.takeRequest().requestLine)
      .isEqualTo("POST /page1 HTTP/1.1")
    val page2 = server.takeRequest()
    assertThat(page2.requestLine).isEqualTo("GET /page2 HTTP/1.1")
    assertThat(page2.headers["Content-Length"]).isNull()
    assertThat(page2.headers["Content-Type"]).isNull()
    assertThat(page2.headers["Transfer-Encoding"]).isNull()
  }

  @Test
  fun response305UseProxy() {
    server.enqueue(
      MockResponse(
        code = HttpURLConnection.HTTP_USE_PROXY,
        headers = headersOf("Location", server.url("/").toString()),
        body = "This page has moved!",
      ),
    )
    server.enqueue(
      MockResponse(body = "Proxy Response"),
    )
    val response = getResponse(newRequest("/foo"))
    // Fails on the RI, which gets "Proxy Response".
    assertThat(readAscii(response.body.byteStream(), Int.MAX_VALUE))
      .isEqualTo("This page has moved!")
    val page1 = server.takeRequest()
    assertThat(page1.requestLine).isEqualTo("GET /foo HTTP/1.1")
    assertThat(server.requestCount).isEqualTo(1)
  }

  @Test
  fun response307WithGet() {
    testRedirect(true, "GET")
  }

  @Test
  fun response307WithHead() {
    testRedirect(true, "HEAD")
  }

  @Test
  fun response307WithOptions() {
    testRedirect(true, "OPTIONS")
  }

  @Test
  fun response307WithPost() {
    testRedirect(true, "POST")
  }

  @Test
  fun response308WithGet() {
    testRedirect(false, "GET")
  }

  @Test
  fun response308WithHead() {
    testRedirect(false, "HEAD")
  }

  @Test
  fun response308WithOptions() {
    testRedirect(false, "OPTIONS")
  }

  @Test
  fun response308WithPost() {
    testRedirect(false, "POST")
  }

  /**
   * In OkHttp 4.5 and earlier, HTTP 307 and 308 redirects were only honored if the request method
   * was GET or HEAD.
   *
   * In OkHttp 4.6 and later, HTTP 307 and 308 redirects are honored for all request methods.
   *
   * If you're upgrading to OkHttp 4.6 and would like to retain the previous behavior, install this
   * as a **network interceptor**. It will strip the `Location` header of impacted responses to
   * prevent the redirect.
   *
   * ```
   * OkHttpClient client = client.newBuilder()
   *   .addNetworkInterceptor(new LegacyRedirectInterceptor())
   *   .build();
   * ```
   */
  internal class LegacyRedirectInterceptor : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
      val response = chain.proceed(chain.request())
      val code = response.code
      if (code != HTTP_TEMP_REDIRECT && code != HTTP_PERM_REDIRECT) return response
      val method = response.request.method
      if (method == "GET" || method == "HEAD") return response
      val location = response.header("Location") ?: return response
      return response
        .newBuilder()
        .removeHeader("Location")
        .header("LegacyRedirectInterceptor-Location", location)
        .build()
    }
  }

  @Test
  fun response307WithPostReverted() {
    client =
      client
        .newBuilder()
        .addNetworkInterceptor(LegacyRedirectInterceptor())
        .build()
    val response1 =
      MockResponse(
        code = HTTP_TEMP_REDIRECT,
        headers = headersOf("Location", "/page2"),
        body = "This page has moved!",
      )
    server.enqueue(response1)
    val request =
      Request(
        url = server.url("/page1"),
        body = "ABCD".toRequestBody(null),
      )
    val response = getResponse(request)
    val responseString = readAscii(response.body.byteStream(), Int.MAX_VALUE)
    val page1 = server.takeRequest()
    assertThat(page1.requestLine).isEqualTo("POST /page1 HTTP/1.1")
    assertThat(page1.body?.utf8()).isEqualTo("ABCD")
    assertThat(server.requestCount).isEqualTo(1)
    assertThat(responseString).isEqualTo("This page has moved!")
  }

  @Test
  fun response308WithPostReverted() {
    client =
      client
        .newBuilder()
        .addNetworkInterceptor(LegacyRedirectInterceptor())
        .build()
    val response1 =
      MockResponse(
        code = HTTP_PERM_REDIRECT,
        body = "This page has moved!",
        headers = headersOf("Location", "/page2"),
      )
    server.enqueue(response1)
    val request =
      Request(
        url = server.url("/page1"),
        body = "ABCD".toRequestBody(null),
      )
    val response = getResponse(request)
    val responseString = readAscii(response.body.byteStream(), Int.MAX_VALUE)
    val page1 = server.takeRequest()
    assertThat(page1.requestLine).isEqualTo("POST /page1 HTTP/1.1")
    assertThat(page1.body?.utf8()).isEqualTo("ABCD")
    assertThat(server.requestCount).isEqualTo(1)
    assertThat(responseString).isEqualTo("This page has moved!")
  }

  private fun testRedirect(
    temporary: Boolean,
    method: String,
  ) {
    val response1 =
      MockResponse
        .Builder()
        .code(
          if (temporary) HTTP_TEMP_REDIRECT else HTTP_PERM_REDIRECT,
        ).addHeader("Location: /page2")
    if (method != "HEAD") {
      response1.body("This page has moved!")
    }
    server.enqueue(response1.build())
    server.enqueue(MockResponse(body = "Page 2"))
    val requestBuilder =
      Request
        .Builder()
        .url(server.url("/page1"))
    if (method == "POST") {
      requestBuilder.post("ABCD".toRequestBody(null))
    } else {
      requestBuilder.method(method, null)
    }
    val response = getResponse(requestBuilder.build())
    val responseString = readAscii(response.body.byteStream(), Int.MAX_VALUE)
    val page1 = server.takeRequest()
    assertThat(page1.requestLine).isEqualTo(
      "$method /page1 HTTP/1.1",
    )
    if (method == "GET") {
      assertThat(responseString).isEqualTo("Page 2")
    } else if (method == "HEAD") {
      assertThat(responseString).isEqualTo("")
    }
    assertThat(server.requestCount).isEqualTo(2)
    val page2 = server.takeRequest()
    assertThat(page2.requestLine)
      .isEqualTo("$method /page2 HTTP/1.1")
  }

  @Test
  fun follow20Redirects() {
    for (i in 0..19) {
      server.enqueue(
        MockResponse(
          code = HttpURLConnection.HTTP_MOVED_TEMP,
          headers = headersOf("Location", "/" + (i + 1)),
          body = "Redirecting to /" + (i + 1),
        ),
      )
    }
    server.enqueue(
      MockResponse(body = "Success!"),
    )
    val response = getResponse(newRequest("/0"))
    assertContent("Success!", response)
    assertThat(response.request.url)
      .isEqualTo(server.url("/20"))
  }

  @Test
  fun doesNotFollow21Redirects() {
    for (i in 0..20) {
      server.enqueue(
        MockResponse(
          code = HttpURLConnection.HTTP_MOVED_TEMP,
          headers = headersOf("Location", "/" + (i + 1)),
          body = "Redirecting to /" + (i + 1),
        ),
      )
    }
    assertFailsWith<ProtocolException> {
      getResponse(newRequest("/0"))
    }.also { expected ->
      assertThat(expected.message).isEqualTo(
        "Too many follow-up requests: 21",
      )
    }
  }

  @Test
  fun httpsWithCustomTrustManager() {
    val hostnameVerifier = RecordingHostnameVerifier()
    val trustManager = RecordingTrustManager(handshakeCertificates.trustManager)
    val sslContext = Platform.get().newSSLContext()
    sslContext.init(null, arrayOf<TrustManager>(trustManager), null)
    client =
      client
        .newBuilder()
        .hostnameVerifier(hostnameVerifier)
        .sslSocketFactory(sslContext.socketFactory, trustManager)
        .build()
    server.useHttps(handshakeCertificates.sslSocketFactory())
    server.enqueue(MockResponse(body = "ABC"))
    server.enqueue(MockResponse(body = "DEF"))
    server.enqueue(MockResponse(body = "GHI"))
    assertContent("ABC", getResponse(newRequest("/")))
    assertContent("DEF", getResponse(newRequest("/")))
    assertContent("GHI", getResponse(newRequest("/")))
    assertThat(hostnameVerifier.calls)
      .isEqualTo(listOf("verify " + server.hostName))
    assertThat(trustManager.calls)
      .isEqualTo(listOf("checkServerTrusted [CN=localhost 1]"))
  }

  @Test
  fun getClientRequestTimeout() {
    enqueueClientRequestTimeoutResponses()
    val response = getResponse(newRequest("/"))
    assertThat(response.code).isEqualTo(200)
    assertThat(readAscii(response.body.byteStream(), Int.MAX_VALUE))
      .isEqualTo("Body")
  }

  private fun enqueueClientRequestTimeoutResponses() {
    server.enqueue(
      MockResponse
        .Builder()
        .code(HttpURLConnection.HTTP_CLIENT_TIMEOUT)
        .addHeader("Connection", "Close")
        .body("You took too long!")
        .onResponseEnd(ShutdownConnection)
        .build(),
    )
    server.enqueue(
      MockResponse(body = "Body"),
    )
  }

  @Test
  fun bufferedBodyWithClientRequestTimeout() {
    enqueueClientRequestTimeoutResponses()
    val response =
      getResponse(
        Request(
          url = server.url("/"),
          body = "Hello".toRequestBody(null),
        ),
      )
    assertThat(response.code).isEqualTo(200)
    assertThat(readAscii(response.body.byteStream(), Int.MAX_VALUE))
      .isEqualTo("Body")
    val request1 = server.takeRequest()
    assertThat(request1.body?.utf8()).isEqualTo("Hello")
    val request2 = server.takeRequest()
    assertThat(request2.body?.utf8()).isEqualTo("Hello")
  }

  @Test
  fun streamedBodyWithClientRequestTimeout() {
    enqueueClientRequestTimeoutResponses()
    val response =
      getResponse(
        Request(
          url = server.url("/"),
          body = TransferKind.CHUNKED.newRequestBody("Hello"),
        ),
      )
    assertThat(response.code).isEqualTo(200)
    assertContent("Body", response)
    response.close()
    assertThat(server.requestCount).isEqualTo(2)
  }

  @Test
  fun readTimeouts() {
    // This relies on the fact that MockWebServer doesn't close the
    // connection after a response has been sent. This causes the client to
    // try to read more bytes than are sent, which results in a timeout.
    server.enqueue(
      MockResponse
        .Builder()
        .body("ABC")
        .clearHeaders()
        .addHeader("Content-Length: 4")
        .build(),
    )
    server.enqueue(
      MockResponse(body = "unused"),
    ) // to keep the server alive
    val response = getResponse(newRequest("/"))
    val source = response.body.source()
    source.timeout().timeout(1000, TimeUnit.MILLISECONDS)
    assertThat(source.readByte()).isEqualTo('A'.code.toByte())
    assertThat(source.readByte()).isEqualTo('B'.code.toByte())
    assertThat(source.readByte()).isEqualTo('C'.code.toByte())
    assertFailsWith<SocketTimeoutException> {
      source.readByte() // If Content-Length was accurate, this would return -1 immediately.
    }
    source.close()
  }

  /** Confirm that an unacknowledged write times out.  */
  @Test
  fun writeTimeouts() {
    val server = MockWebServer()
    // Sockets on some platforms can have large buffers that mean writes do not block when
    // required. These socket factories explicitly set the buffer sizes on sockets created.
    val socketBufferSize = 4 * 1024
    server.serverSocketFactory =
      object : DelegatingServerSocketFactory(getDefault()) {
        override fun configureServerSocket(serverSocket: ServerSocket): ServerSocket {
          serverSocket.receiveBufferSize = socketBufferSize
          return serverSocket
        }
      }
    client =
      client
        .newBuilder()
        .socketFactory(
          object : DelegatingSocketFactory(getDefault()) {
            override fun configureSocket(socket: Socket): Socket {
              socket.receiveBufferSize = socketBufferSize
              socket.sendBufferSize = socketBufferSize
              return socket
            }
          },
        ).writeTimeout(Duration.ofMillis(500))
        .build()
    server.start()
    server.enqueue(
      MockResponse
        .Builder()
        .throttleBody(1, 1, TimeUnit.SECONDS)
        .build(),
    ) // Prevent the server from reading!
    val request =
      Request(
        url = server.url("/"),
        body =
          object : RequestBody() {
            override fun contentType(): MediaType? = null

            override fun writeTo(sink: BufferedSink) {
              val data = ByteArray(2 * 1024 * 1024) // 2 MiB.
              sink.write(data)
            }
          },
      )
    assertFailsWith<SocketTimeoutException> {
      getResponse(request)
    }
  }

  @Test
  fun setChunkedEncodingAsRequestProperty() {
    server.enqueue(MockResponse())
    val response =
      getResponse(
        Request
          .Builder()
          .url(server.url("/"))
          .header("Transfer-encoding", "chunked")
          .post(TransferKind.CHUNKED.newRequestBody("ABC"))
          .build(),
      )
    assertThat(response.code).isEqualTo(200)
    val request = server.takeRequest()
    assertThat(request.body?.utf8()).isEqualTo("ABC")
  }

  @Test
  fun connectionCloseInRequest() {
    server.enqueue(MockResponse()) // Server doesn't honor the connection: close header!
    server.enqueue(MockResponse())
    val a =
      getResponse(
        Request
          .Builder()
          .url(server.url("/"))
          .header("Connection", "close")
          .build(),
      )
    assertThat(a.code).isEqualTo(200)
    val b = getResponse(newRequest("/"))
    assertThat(b.code).isEqualTo(200)
    assertThat(server.takeRequest().exchangeIndex).isEqualTo(0)
    assertThat(
      server.takeRequest().exchangeIndex,
      "When connection: close is used, each request should get its own connection",
    ).isEqualTo(0)
  }

  @Test
  fun connectionCloseInResponse() {
    server.enqueue(MockResponse(headers = headersOf("Connection", "close")))
    server.enqueue(MockResponse())
    val a = getResponse(newRequest("/"))
    assertThat(a.code).isEqualTo(200)
    val b = getResponse(newRequest("/"))
    assertThat(b.code).isEqualTo(200)
    assertThat(server.takeRequest().exchangeIndex).isEqualTo(0)
    assertThat(
      server.takeRequest().exchangeIndex,
      "When connection: close is used, each request should get its own connection",
    ).isEqualTo(0)
  }

  @Test
  fun connectionCloseWithRedirect() {
    server.enqueue(
      MockResponse(
        code = HttpURLConnection.HTTP_MOVED_TEMP,
        headers =
          headersOf(
            "Location",
            "/foo",
            "Connection",
            "close",
          ),
      ),
    )
    server.enqueue(MockResponse(body = "This is the new location!"))
    val response = getResponse(newRequest("/"))
    assertThat(readAscii(response.body.byteStream(), Int.MAX_VALUE)).isEqualTo(
      "This is the new location!",
    )
    assertThat(server.takeRequest().exchangeIndex).isEqualTo(0)
    assertThat(
      server.takeRequest().exchangeIndex,
      "When connection: close is used, each request should get its own connection",
    ).isEqualTo(0)
  }

  /**
   * Retry redirects if the socket is closed.
   * https://code.google.com/p/android/issues/detail?id=41576
   */
  @Test
  fun sameConnectionRedirectAndReuse() {
    server.enqueue(
      MockResponse
        .Builder()
        .code(HttpURLConnection.HTTP_MOVED_TEMP)
        .addHeader("Location", "/foo")
        .onResponseEnd(
          CloseSocket(
            closeSocket = false,
            shutdownInput = true,
          ),
        ).build(),
    )
    server.enqueue(MockResponse(body = "This is the new page!"))
    assertContent("This is the new page!", getResponse(newRequest("/")))
    assertThat(server.takeRequest().exchangeIndex).isEqualTo(0)
    assertThat(server.takeRequest().exchangeIndex).isEqualTo(0)
  }

  @Test
  fun responseCodeDisagreesWithHeaders() {
    server.enqueue(
      MockResponse(
        code = HttpURLConnection.HTTP_NO_CONTENT,
        body = "This body is not allowed!",
      ),
    )
    assertFailsWith<IOException> {
      getResponse(newRequest("/"))
    }.also { expected ->
      assertThat(expected.message).isEqualTo("HTTP 204 had non-zero Content-Length: 25")
    }
  }

  @Test
  fun singleByteReadIsSigned() {
    server.enqueue(
      MockResponse
        .Builder()
        .body(
          Buffer()
            .writeByte(-2)
            .writeByte(-1),
        ).build(),
    )
    val response = getResponse(newRequest("/"))
    val inputStream = response.body.byteStream()
    assertThat(inputStream.read()).isEqualTo(254)
    assertThat(inputStream.read()).isEqualTo(255)
    assertThat(inputStream.read()).isEqualTo(-1)
  }

  @Test
  fun flushAfterStreamTransmittedWithChunkedEncoding() {
    testFlushAfterStreamTransmitted(TransferKind.CHUNKED)
  }

  @Test
  fun flushAfterStreamTransmittedWithFixedLength() {
    testFlushAfterStreamTransmitted(TransferKind.FIXED_LENGTH)
  }

  @Test
  fun flushAfterStreamTransmittedWithNoLengthHeaders() {
    testFlushAfterStreamTransmitted(TransferKind.END_OF_STREAM)
  }

  /**
   * We explicitly permit apps to close the upload stream even after it has been transmitted.  We
   * also permit flush so that buffered streams can do a no-op flush when they are closed.
   * http://b/3038470
   */
  private fun testFlushAfterStreamTransmitted(transferKind: TransferKind) {
    server.enqueue(
      MockResponse(body = "abc"),
    )
    val sinkReference = AtomicReference<BufferedSink>()
    val response =
      getResponse(
        Request(
          url = server.url("/"),
          body =
            object : ForwardingRequestBody(transferKind.newRequestBody("def")) {
              override fun writeTo(sink: BufferedSink) {
                sinkReference.set(sink)
                super.writeTo(sink)
              }
            },
        ),
      )
    assertThat(readAscii(response.body.byteStream(), Int.MAX_VALUE))
      .isEqualTo("abc")
    assertFailsWith<IllegalStateException> {
      sinkReference.get().flush()
    }
    assertFailsWith<IllegalStateException> {
      sinkReference.get().write("ghi".toByteArray())
      sinkReference.get().emit()
    }
  }

  @Test
  fun getHeadersThrows() {
    server.enqueue(MockResponse.Builder().onRequestStart(CloseSocket()).build())
    assertFailsWith<IOException> {
      getResponse(newRequest("/"))
    }
  }

  @Test
  fun dnsFailureThrowsIOException() {
    client =
      client
        .newBuilder()
        .dns(FakeDns())
        .build()
    assertFailsWith<IOException> {
      getResponse(Request("http://host.unlikelytld".toHttpUrl()))
    }
  }

  @Test
  fun malformedUrlThrowsUnknownHostException() {
    assertFailsWith<IOException> {
      getResponse(Request("http://-/foo.html".toHttpUrl()))
    }
  }

  // The request should work once and then fail.
  @Test
  fun getKeepAlive() {
    server.enqueue(MockResponse(body = "ABC"))

    // The request should work once and then fail.
    val connection1 = getResponse(newRequest("/"))
    val source1 = connection1.body.source()
    source1.timeout().timeout(100, TimeUnit.MILLISECONDS)
    assertThat(readAscii(source1.inputStream(), Int.MAX_VALUE)).isEqualTo("ABC")
    server.close()
    assertFailsWith<ConnectException> {
      getResponse(newRequest("/"))
    }
  }

  /** http://code.google.com/p/android/issues/detail?id=14562  */
  @Test
  fun readAfterLastByte() {
    server.enqueue(
      MockResponse
        .Builder()
        .body("ABC")
        .clearHeaders()
        .addHeader("Connection: close")
        .onResponseEnd(ShutdownConnection)
        .build(),
    )
    val response = getResponse(newRequest("/"))
    val inputStream = response.body.byteStream()
    assertThat(readAscii(inputStream, 3)).isEqualTo("ABC")
    assertThat(inputStream.read()).isEqualTo(-1)
    // throws IOException in Gingerbread.
    assertThat(inputStream.read()).isEqualTo(-1)
  }

  @Test
  fun getOutputStreamOnGetFails() {
    assertFailsWith<IllegalArgumentException> {
      Request
        .Builder()
        .url(server.url("/"))
        .method("GET", "abc".toRequestBody(null))
        .build()
    }
  }

  @Test
  fun clientSendsContentLength() {
    server.enqueue(
      MockResponse(body = "A"),
    )
    val response =
      getResponse(
        Request(
          url = server.url("/"),
          body = "ABC".toRequestBody(null),
        ),
      )
    assertThat(readAscii(response.body.byteStream(), Int.MAX_VALUE)).isEqualTo("A")
    val request = server.takeRequest()
    assertThat(request.headers["Content-Length"]).isEqualTo("3")
    response.body.close()
  }

  @Test
  fun getContentLengthConnects() {
    server.enqueue(
      MockResponse(body = "ABC"),
    )
    val response = getResponse(newRequest("/"))
    assertThat(response.body.contentLength()).isEqualTo(3L)
    response.body.close()
  }

  @Test
  fun getContentTypeConnects() {
    server.enqueue(
      MockResponse(
        headers = headersOf("Content-Type", "text/plain"),
        body = "ABC",
      ),
    )
    val response = getResponse(newRequest("/"))
    assertThat(response.body.contentType()).isEqualTo(
      "text/plain".toMediaType(),
    )
    response.body.close()
  }

  @Test
  fun getContentEncodingConnects() {
    server.enqueue(
      MockResponse(
        headers = headersOf("Content-Encoding", "identity"),
        body = "ABC",
      ),
    )
    val response = getResponse(newRequest("/"))
    assertThat(response.header("Content-Encoding")).isEqualTo("identity")
    response.body.close()
  }

  @Test
  fun urlContainsQueryButNoPath() {
    server.enqueue(
      MockResponse(body = "A"),
    )
    val url = server.url("?query")
    val response = getResponse(Request(url))
    assertThat(readAscii(response.body.byteStream(), Int.MAX_VALUE)).isEqualTo("A")
    val request = server.takeRequest()
    assertThat(request.requestLine).isEqualTo("GET /?query HTTP/1.1")
  }

  @Test
  fun doOutputForMethodThatDoesntSupportOutput() {
    assertFailsWith<IllegalArgumentException> {
      Request
        .Builder()
        .url(server.url("/"))
        .method("HEAD", "".toRequestBody(null))
        .build()
    }
  }

  // http://code.google.com/p/android/issues/detail?id=20442
  @Test
  fun inputStreamAvailableWithChunkedEncoding() {
    testInputStreamAvailable(TransferKind.CHUNKED)
  }

  @Test
  fun inputStreamAvailableWithContentLengthHeader() {
    testInputStreamAvailable(TransferKind.FIXED_LENGTH)
  }

  @Test
  fun inputStreamAvailableWithNoLengthHeaders() {
    testInputStreamAvailable(TransferKind.END_OF_STREAM)
  }

  private fun testInputStreamAvailable(transferKind: TransferKind) {
    val body = "ABCDEFGH"
    val builder = MockResponse.Builder()
    transferKind.setBody(builder, body, 4)
    server.enqueue(builder.build())
    val response = getResponse(newRequest("/"))
    val inputStream = response.body.byteStream()
    for (i in 0 until body.length) {
      assertThat(inputStream.available()).isGreaterThanOrEqualTo(0)
      assertThat(inputStream.read()).isEqualTo(body[i].code)
    }
    assertThat(inputStream.available()).isEqualTo(0)
    assertThat(inputStream.read()).isEqualTo(-1)
  }

  @Test
  fun postFailsWithBufferedRequestForSmallRequest() {
    reusedConnectionFailsWithPost(TransferKind.END_OF_STREAM, 1024)
  }

  @Test
  fun postFailsWithBufferedRequestForLargeRequest() {
    reusedConnectionFailsWithPost(TransferKind.END_OF_STREAM, 16384)
  }

  @Test
  fun postFailsWithChunkedRequestForSmallRequest() {
    reusedConnectionFailsWithPost(TransferKind.CHUNKED, 1024)
  }

  @Test
  fun postFailsWithChunkedRequestForLargeRequest() {
    reusedConnectionFailsWithPost(TransferKind.CHUNKED, 16384)
  }

  @Test
  fun postFailsWithFixedLengthRequestForSmallRequest() {
    reusedConnectionFailsWithPost(TransferKind.FIXED_LENGTH, 1024)
  }

  @Test
  fun postFailsWithFixedLengthRequestForLargeRequest() {
    reusedConnectionFailsWithPost(TransferKind.FIXED_LENGTH, 16384)
  }

  private fun reusedConnectionFailsWithPost(
    transferKind: TransferKind,
    requestSize: Int,
  ) {
    server.enqueue(
      MockResponse
        .Builder()
        .body("A")
        .onResponseEnd(ShutdownConnection)
        .build(),
    )
    server.enqueue(MockResponse(body = "B"))
    server.enqueue(MockResponse(body = "C"))
    assertContent("A", getResponse(newRequest("/a")))

    // Give the server time to disconnect.
    Thread.sleep(500)

    // If the request body is larger than OkHttp's replay buffer, the failure may still occur.
    val requestBodyChars = CharArray(requestSize)
    Arrays.fill(requestBodyChars, 'x')
    val requestBody = String(requestBodyChars)
    for (j in 0..1) {
      try {
        val response =
          getResponse(
            Request(
              url = server.url("/b"),
              body = transferKind.newRequestBody(requestBody),
            ),
          )
        assertContent("B", response)
        break
      } catch (_: IOException) {
        // If there's a socket exception, this must have a streamed request body.
        assertThat(j).isEqualTo(0)
        assertThat(transferKind).isIn(TransferKind.CHUNKED, TransferKind.FIXED_LENGTH)
      }
    }
    val requestA = server.takeRequest()
    assertThat(requestA.url.encodedPath).isEqualTo("/a")
    val requestB = server.takeRequest()
    assertThat(requestB.url.encodedPath).isEqualTo("/b")
    assertThat(requestB.body?.utf8()).isEqualTo(requestBody)
  }

  @Test
  fun postBodyRetransmittedOnFailureRecovery() {
    server.enqueue(MockResponse(body = "abc"))
    server.enqueue(MockResponse.Builder().onResponseStart(CloseSocket()).build())
    server.enqueue(MockResponse(body = "def"))

    // Seed the connection pool so we have something that can fail.
    assertContent("abc", getResponse(newRequest("/")))
    val post =
      getResponse(
        Request(
          url = server.url("/"),
          body = "body!".toRequestBody(null),
        ),
      )
    assertContent("def", post)
    val get = server.takeRequest()
    assertThat(get.exchangeIndex).isEqualTo(0)
    val post1 = server.takeRequest()
    assertThat(post1.body?.utf8()).isEqualTo("body!")
    assertThat(post1.exchangeIndex).isEqualTo(1)
    val post2 = server.takeRequest()
    assertThat(post2.body?.utf8()).isEqualTo("body!")
    assertThat(post2.exchangeIndex).isEqualTo(0)
  }

  @Test
  fun fullyBufferedPostIsTooShort() {
    server.enqueue(
      MockResponse(body = "A"),
    )
    val requestBody: RequestBody =
      object : RequestBody() {
        override fun contentType(): MediaType? = null

        override fun contentLength(): Long = 4L

        override fun writeTo(sink: BufferedSink) {
          sink.writeUtf8("abc")
        }
      }
    assertFailsWith<IOException> {
      getResponse(
        Request(
          url = server.url("/b"),
          body = requestBody,
        ),
      )
    }
  }

  @Test
  fun fullyBufferedPostIsTooLong() {
    server.enqueue(
      MockResponse(body = "A"),
    )
    val requestBody: RequestBody =
      object : RequestBody() {
        override fun contentType(): MediaType? = null

        override fun contentLength(): Long = 3L

        override fun writeTo(sink: BufferedSink) {
          sink.writeUtf8("abcd")
        }
      }
    assertFailsWith<IOException> {
      getResponse(
        Request(
          url = server.url("/b"),
          body = requestBody,
        ),
      )
    }
  }

  @Test
  @Disabled
  fun testPooledConnectionsDetectHttp10() {
    // TODO: write a test that shows pooled connections detect HTTP/1.0 (vs. HTTP/1.1)
    fail("TODO")
  }

  @Test
  @Disabled
  fun postBodiesRetransmittedOnAuthProblems() {
    fail("TODO")
  }

  @Test
  @Disabled
  fun cookiesAndTrailers() {
    // Do cookie headers get processed too many times?
    fail("TODO")
  }

  @Test
  fun emptyRequestHeaderValueIsAllowed() {
    server.enqueue(
      MockResponse(body = "body"),
    )
    val response =
      getResponse(
        Request
          .Builder()
          .url(server.url("/"))
          .header("B", "")
          .build(),
      )
    assertContent("body", response)
    assertThat(response.request.header("B")).isEqualTo("")
  }

  @Test
  fun emptyResponseHeaderValueIsAllowed() {
    server.enqueue(
      MockResponse(
        headers = headersOf("A", ""),
        body = "body",
      ),
    )
    val response = getResponse(newRequest("/"))
    assertContent("body", response)
    assertThat(response.header("A")).isEqualTo("")
  }

  @Test
  fun emptyRequestHeaderNameIsStrict() {
    assertFailsWith<IllegalArgumentException> {
      Request
        .Builder()
        .url(server.url("/"))
        .header("", "A")
        .build()
    }
  }

  @Test
  fun emptyResponseHeaderNameIsLenient() {
    val headers = Headers.Builder()
    addHeaderLenient(headers, ":A")
    server.enqueue(
      MockResponse(
        headers = headers.build(),
        body = "body",
      ),
    )
    val response = getResponse(newRequest("/"))
    assertThat(response.code).isEqualTo(200)
    assertThat(response.header("")).isEqualTo("A")
    response.body.close()
  }

  @Test
  fun requestHeaderValidationIsStrict() {
    assertFailsWith<IllegalArgumentException> {
      Request
        .Builder()
        .addHeader("a\tb", "Value")
    }
    assertFailsWith<IllegalArgumentException> {
      Request
        .Builder()
        .addHeader("Name", "c\u007fd")
    }
    assertFailsWith<IllegalArgumentException> {
      Request
        .Builder()
        .addHeader("", "Value")
    }
    assertFailsWith<IllegalArgumentException> {
      Request
        .Builder()
        .addHeader("\ud83c\udf69", "Value")
    }
    assertFailsWith<IllegalArgumentException> {
      Request
        .Builder()
        .addHeader("Name", "\u2615\ufe0f")
    }
  }

  @Test
  fun responseHeaderParsingIsLenient() {
    val headersBuilder = Headers.Builder()
    headersBuilder.add("Content-Length", "0")
    addHeaderLenient(headersBuilder, "a\tb: c\u007fd")
    addHeaderLenient(headersBuilder, ": ef")
    addHeaderLenient(headersBuilder, "\ud83c\udf69: \u2615\ufe0f")
    val headers = headersBuilder.build()
    server.enqueue(MockResponse(headers = headers))
    val response = getResponse(newRequest("/"))
    assertThat(response.code).isEqualTo(200)
    assertThat(response.header("a\tb")).isEqualTo("c\u007fd")
    assertThat(response.header("\ud83c\udf69")).isEqualTo("\u2615\ufe0f")
    assertThat(response.header("")).isEqualTo("ef")
  }

  @Test
  @Disabled
  fun deflateCompression() {
    fail("TODO")
  }

  @Test
  @Disabled
  fun postBodiesRetransmittedOnIpAddressProblems() {
    fail("TODO")
  }

  @Test
  @Disabled
  fun pooledConnectionProblemsNotReportedToProxySelector() {
    fail("TODO")
  }

  @Test
  fun customBasicAuthenticator() {
    server.enqueue(
      MockResponse(
        code = 401,
        headers = headersOf("WWW-Authenticate", "Basic realm=\"protected area\""),
        body = "Please authenticate.",
      ),
    )
    server.enqueue(
      MockResponse(body = "A"),
    )
    val credential = basic("jesse", "peanutbutter")
    val authenticator = RecordingOkAuthenticator(credential, null)
    client =
      client
        .newBuilder()
        .authenticator(authenticator)
        .build()
    assertContent("A", getResponse(newRequest("/private")))
    assertThat(server.takeRequest().headers["Authorization"]).isNull()
    assertThat(server.takeRequest().headers["Authorization"]).isEqualTo(credential)
    assertThat(authenticator.onlyRoute().proxy).isEqualTo(Proxy.NO_PROXY)
    val response = authenticator.onlyResponse()
    assertThat(
      response.request.url
        .toUrl()
        .path,
    ).isEqualTo("/private")
    assertThat(response.challenges()).isEqualTo(listOf(Challenge("Basic", "protected area")))
  }

  @Test
  fun customTokenAuthenticator() {
    server.enqueue(
      MockResponse(
        code = 401,
        headers = headersOf("WWW-Authenticate", "Bearer realm=\"oauthed\""),
        body = "Please authenticate.",
      ),
    )
    server.enqueue(
      MockResponse(body = "A"),
    )
    val authenticator = RecordingOkAuthenticator("oauthed abc123", "Bearer")
    client =
      client
        .newBuilder()
        .authenticator(authenticator)
        .build()
    assertContent("A", getResponse(newRequest("/private")))
    assertThat(server.takeRequest().headers["Authorization"]).isNull()
    assertThat(server.takeRequest().headers["Authorization"]).isEqualTo(
      "oauthed abc123",
    )
    val response = authenticator.onlyResponse()
    assertThat(
      response.request.url
        .toUrl()
        .path,
    ).isEqualTo("/private")
    assertThat(response.challenges()).isEqualTo(listOf(Challenge("Bearer", "oauthed")))
  }

  @Test
  fun authenticateCallsTrackedAsRedirects() {
    server.enqueue(
      MockResponse(
        code = 302,
        headers = headersOf("Location", "/b"),
      ),
    )
    server.enqueue(
      MockResponse(
        code = 401,
        headers = headersOf("WWW-Authenticate", "Basic realm=\"protected area\""),
      ),
    )
    server.enqueue(
      MockResponse(body = "c"),
    )
    val authenticator =
      RecordingOkAuthenticator(
        basic("jesse", "peanutbutter"),
        "Basic",
      )
    client =
      client
        .newBuilder()
        .authenticator(authenticator)
        .build()
    assertContent("c", getResponse(newRequest("/a")))
    val challengeResponse = authenticator.responses[0]
    assertThat(
      challengeResponse.request.url
        .toUrl()
        .path,
    ).isEqualTo("/b")
    val redirectedBy = challengeResponse.priorResponse
    assertThat(
      redirectedBy!!
        .request.url
        .toUrl()
        .path,
    ).isEqualTo("/a")
  }

  @Test
  fun attemptAuthorization20Times() {
    for (i in 0..19) {
      server.enqueue(
        MockResponse(code = 401),
      )
    }
    server.enqueue(
      MockResponse(body = "Success!"),
    )
    val credential = basic("jesse", "peanutbutter")
    client =
      client
        .newBuilder()
        .authenticator(RecordingOkAuthenticator(credential, null))
        .build()
    val response = getResponse(newRequest("/0"))
    assertContent("Success!", response)
  }

  @Test
  fun doesNotAttemptAuthorization21Times() {
    for (i in 0..20) {
      server.enqueue(
        MockResponse(code = 401),
      )
    }
    val credential = basic("jesse", "peanutbutter")
    client =
      client
        .newBuilder()
        .authenticator(RecordingOkAuthenticator(credential, null))
        .build()
    assertFailsWith<ProtocolException> {
      getResponse(newRequest("/"))
    }.also { expected ->
      assertThat(expected.message).isEqualTo("Too many follow-up requests: 21")
    }
  }

  @Test
  fun setsNegotiatedProtocolHeader_HTTP_2() {
    platform.assumeHttp2Support()
    setsNegotiatedProtocolHeader(Protocol.HTTP_2)
  }

  private fun setsNegotiatedProtocolHeader(protocol: Protocol) {
    enableProtocol(protocol)
    server.enqueue(
      MockResponse(body = "A"),
    )
    client =
      client
        .newBuilder()
        .protocols(Arrays.asList(protocol, Protocol.HTTP_1_1))
        .build()
    val response = getResponse(newRequest("/"))
    assertThat(response.protocol).isEqualTo(protocol)
    assertContent("A", response)
  }

  @Test
  fun http10SelectedProtocol() {
    server.enqueue(
      MockResponse
        .Builder()
        .status("HTTP/1.0 200 OK")
        .build(),
    )
    val response = getResponse(newRequest("/"))
    assertThat(response.protocol).isEqualTo(Protocol.HTTP_1_0)
  }

  @Test
  fun http11SelectedProtocol() {
    server.enqueue(
      MockResponse
        .Builder()
        .status("HTTP/1.1 200 OK")
        .build(),
    )
    val response = getResponse(newRequest("/"))
    assertThat(response.protocol).isEqualTo(Protocol.HTTP_1_1)
  }

  /** For example, empty Protobuf RPC messages end up as a zero-length POST.  */
  @Test
  fun zeroLengthPost() {
    zeroLengthPayload("POST")
  }

  @Test
  fun zeroLengthPost_HTTP_2() {
    enableProtocol(Protocol.HTTP_2)
    zeroLengthPost()
  }

  /** For example, creating an Amazon S3 bucket ends up as a zero-length POST.  */
  @Test
  fun zeroLengthPut() {
    zeroLengthPayload("PUT")
  }

  @Test
  fun zeroLengthPut_HTTP_2() {
    enableProtocol(Protocol.HTTP_2)
    zeroLengthPut()
  }

  private fun zeroLengthPayload(method: String) {
    server.enqueue(MockResponse())
    val response =
      getResponse(
        Request
          .Builder()
          .url(server.url("/"))
          .method(method, "".toRequestBody(null))
          .build(),
      )
    assertContent("", response)
    val zeroLengthPayload = server.takeRequest()
    assertThat(zeroLengthPayload.method).isEqualTo(method)
    assertThat(zeroLengthPayload.headers["content-length"]).isEqualTo("0")
    assertThat(zeroLengthPayload.bodySize).isEqualTo(0L)
  }

  @Test
  fun setProtocols() {
    server.enqueue(
      MockResponse(body = "A"),
    )
    client =
      client
        .newBuilder()
        .protocols(Arrays.asList(Protocol.HTTP_1_1))
        .build()
    assertContent("A", getResponse(newRequest("/")))
  }

  @Test
  fun setProtocolsWithoutHttp11() {
    assertFailsWith<IllegalArgumentException> {
      OkHttpClient
        .Builder()
        .protocols(Arrays.asList(Protocol.HTTP_2))
    }
  }

  @Test
  fun setProtocolsWithNull() {
    assertFailsWith<IllegalArgumentException> {
      OkHttpClient
        .Builder()
        .protocols(Arrays.asList(Protocol.HTTP_1_1, null))
    }
  }

  @Test
  fun veryLargeFixedLengthRequest() {
    server.bodyLimit = 0
    server.enqueue(MockResponse())
    val contentLength = Int.MAX_VALUE + 1L
    val response =
      getResponse(
        Request(
          url = server.url("/"),
          body =
            object : RequestBody() {
              override fun contentType(): MediaType? = null

              override fun contentLength(): Long = contentLength

              override fun writeTo(sink: BufferedSink) {
                val buffer = ByteArray(1024 * 1024)
                var bytesWritten: Long = 0
                while (bytesWritten < contentLength) {
                  val byteCount = Math.min(buffer.size.toLong(), contentLength - bytesWritten).toInt()
                  bytesWritten += byteCount.toLong()
                  sink.write(buffer, 0, byteCount)
                }
              }
            },
        ),
      )
    assertContent("", response)
    val request = server.takeRequest()
    assertThat(request.headers["Content-Length"]).isEqualTo(
      java.lang.Long.toString(contentLength),
    )
  }

  @Test
  fun testNoSslFallback() {
    platform.assumeNotBouncyCastle()

    server.useHttps(handshakeCertificates.sslSocketFactory())
    server.enqueue(MockResponse.Builder().failHandshake().build())
    server.enqueue(MockResponse(body = "Response that would have needed fallbacks"))
    client =
      client
        .newBuilder()
        .sslSocketFactory(
          handshakeCertificates.sslSocketFactory(),
          handshakeCertificates.trustManager,
        ).build()
    assertFailsWith<IOException> {
      getResponse(newRequest("/"))
    }.also { expected ->
      when (expected) {
        is SSLProtocolException -> {
          // RI response to the FAIL_HANDSHAKE
        }

        is SSLHandshakeException -> {
          // Android's response to the FAIL_HANDSHAKE
        }

        is SSLException -> {
          // JDK 1.9 response to the FAIL_HANDSHAKE
          // javax.net.ssl.SSLException: Unexpected handshake message: client_hello
        }

        is SocketException -> {
          // Conscrypt's response to the FAIL_HANDSHAKE
        }

        else -> {
          throw expected
        }
      }
    }
  }

  /**
   * We had a bug where we attempted to gunzip responses that didn't have a body. This only came up
   * with 304s since that response code can include headers (like "Content-Encoding") without any
   * content to go along with it. https://github.com/lysine-dev/okhttp/issues/358
   */
  @Test
  fun noTransparentGzipFor304NotModified() {
    server.enqueue(
      MockResponse
        .Builder()
        .clearHeaders()
        .code(HttpURLConnection.HTTP_NOT_MODIFIED)
        .addHeader("Content-Encoding: gzip")
        .build(),
    )
    server.enqueue(
      MockResponse(body = "b"),
    )
    val response1 = getResponse(newRequest("/"))
    assertThat(response1.code).isEqualTo(HttpURLConnection.HTTP_NOT_MODIFIED)
    assertContent("", response1)
    val response2 = getResponse(newRequest("/"))
    assertThat(response2.code).isEqualTo(HttpURLConnection.HTTP_OK)
    assertContent("b", response2)
    val requestA = server.takeRequest()
    assertThat(requestA.exchangeIndex).isEqualTo(0)
    val requestB = server.takeRequest()
    assertThat(requestB.exchangeIndex).isEqualTo(1)
  }

  /**
   * We had a bug where we weren't closing Gzip streams on redirects.
   * https://github.com/lysine-dev/okhttp/issues/441
   */
  @Test
  fun gzipWithRedirectAndConnectionReuse() {
    server.enqueue(
      MockResponse
        .Builder()
        .code(HttpURLConnection.HTTP_MOVED_TEMP)
        .addHeader("Location: /foo")
        .addHeader("Content-Encoding: gzip")
        .body(gzip("Moved! Moved! Moved!"))
        .build(),
    )
    server.enqueue(
      MockResponse(body = "This is the new page!"),
    )
    val response = getResponse(newRequest("/"))
    assertContent("This is the new page!", response)
    val requestA = server.takeRequest()
    assertThat(requestA.exchangeIndex).isEqualTo(0)
    val requestB = server.takeRequest()
    assertThat(requestB.exchangeIndex).isEqualTo(1)
  }

  /**
   * The RFC is unclear in this regard as it only specifies that this should invalidate the cache
   * entry (if any).
   */
  @Test
  fun bodyPermittedOnDelete() {
    server.enqueue(MockResponse())
    val response =
      getResponse(
        Request
          .Builder()
          .url(server.url("/"))
          .delete("BODY".toRequestBody(null))
          .build(),
      )
    assertThat(response.code).isEqualTo(200)
    val request = server.takeRequest()
    assertThat(request.method).isEqualTo("DELETE")
    assertThat(request.body?.utf8()).isEqualTo("BODY")
  }

  @Test
  fun userAgentDefaultsToOkHttpVersion() {
    server.enqueue(
      MockResponse(body = "abc"),
    )
    assertContent("abc", getResponse(newRequest("/")))
    val request = server.takeRequest()
    assertThat(request.headers["User-Agent"]).isEqualTo(USER_AGENT)
  }

  @Test
  fun urlWithSpaceInHost() {
    assertFailsWith<IllegalArgumentException> {
      "http://and roid.com/".toHttpUrl()
    }
  }

  @Test
  fun urlWithSpaceInHostViaHttpProxy() {
    assertFailsWith<IllegalArgumentException> {
      "http://and roid.com/".toHttpUrl()
    }
  }

  @Test
  fun urlHostWithNul() {
    assertFailsWith<IllegalArgumentException> {
      "http://host\u0000/".toHttpUrl()
    }
  }

  @Test
  fun urlRedirectToHostWithNul() {
    val redirectUrl = "http://host\u0000/"
    server.enqueue(
      MockResponse
        .Builder()
        .code(302)
        .addHeaderLenient("Location", redirectUrl)
        .build(),
    )
    val response = getResponse(newRequest("/"))
    assertThat(response.code).isEqualTo(302)
    assertThat(response.header("Location")).isEqualTo(redirectUrl)
  }

  @Test
  fun urlWithBadAsciiHost() {
    assertFailsWith<IllegalArgumentException> {
      "http://host\u0001/".toHttpUrl()
    }
  }

  @Suppress("DEPRECATION_ERROR")
  @Test
  fun setSslSocketFactoryFailsOnJdk9() {
    platform.assumeJdk9()
    assertFailsWith<UnsupportedOperationException> {
      client
        .newBuilder()
        .sslSocketFactory(handshakeCertificates.sslSocketFactory())
    }
  }

  /** Confirm that runtime exceptions thrown inside of OkHttp propagate to the caller.  */
  @Test
  fun unexpectedExceptionSync() {
    client =
      client
        .newBuilder()
        .dns { throw RuntimeException("boom!") }
        .build()
    server.enqueue(MockResponse())
    assertFailsWith<RuntimeException> {
      getResponse(newRequest("/"))
    }.also { expected ->
      assertThat(expected.message).isEqualTo("boom!")
    }
  }

  @Test
  fun streamedBodyIsRetriedOnHttp2Shutdown() {
    platform.assumeHttp2Support()
    enableProtocol(Protocol.HTTP_2)
    server.enqueue(
      MockResponse
        .Builder()
        .body("abc")
        .onResponseEnd(ShutdownConnection)
        .build(),
    )
    server.enqueue(
      MockResponse(body = "def"),
    )

    // Send a separate request which will trigger a GOAWAY frame on the healthy connection.
    val response = getResponse(newRequest("/"))
    assertContent("abc", response)

    // Ensure the GOAWAY frame has time to be read and processed.
    Thread.sleep(500)
    assertContent(
      "def",
      getResponse(
        Request(
          url = server.url("/"),
          body = "123".toRequestBody(null),
        ),
      ),
    )
    val request1 = server.takeRequest()
    assertThat(request1.exchangeIndex).isEqualTo(0)
    val request2 = server.takeRequest()
    assertThat(request2.body?.utf8()).isEqualTo("123")
    assertThat(request2.exchangeIndex).isEqualTo(0)
  }

  @Test
  fun authenticateNoConnection() {
    server.enqueue(
      MockResponse
        .Builder()
        .code(401)
        .addHeader("Connection", "close")
        .onResponseEnd(ShutdownConnection)
        .build(),
    )
    java.net.Authenticator.setDefault(RecordingAuthenticator(null))
    client =
      client
        .newBuilder()
        .authenticator(JavaNetAuthenticator())
        .build()
    val response = getResponse(newRequest("/"))
    assertThat(response.code).isEqualTo(401)
  }

  private fun newRequest(s: String): Request = Request(server.url(s))

  private fun getResponse(request: Request): Response = client.newCall(request).execute()

  /** Returns a gzipped copy of `bytes`.  */
  fun gzip(data: String?): Buffer {
    val result = Buffer()
    val gzipSink = GzipSink(result).buffer()
    gzipSink.writeUtf8(data!!)
    gzipSink.close()
    return result
  }

  private fun assertContent(
    expected: String,
    response: Response,
    limit: Int = Int.MAX_VALUE,
  ) {
    assertThat(readAscii(response.body.byteStream(), limit)).isEqualTo(expected)
  }

  private fun newSet(vararg elements: String): Set<String> = setOf(*elements)

  internal enum class TransferKind {
    CHUNKED {
      override fun setBody(
        response: MockResponse.Builder,
        content: Buffer?,
        chunkSize: Int,
      ) {
        response.chunkedBody(content!!, chunkSize)
      }

      override fun newRequestBody(body: String): RequestBody =
        object : RequestBody() {
          override fun contentLength(): Long = -1L

          override fun contentType(): MediaType? = null

          override fun writeTo(sink: BufferedSink) {
            sink.writeUtf8(body)
          }
        }
    },

    FIXED_LENGTH {
      override fun setBody(
        response: MockResponse.Builder,
        content: Buffer?,
        chunkSize: Int,
      ) {
        response.body(content!!)
      }

      override fun newRequestBody(body: String): RequestBody =
        object : RequestBody() {
          override fun contentLength(): Long = body.utf8Size()

          override fun contentType(): MediaType? = null

          override fun writeTo(sink: BufferedSink) {
            sink.writeUtf8(body)
          }
        }
    },

    END_OF_STREAM {
      override fun setBody(
        response: MockResponse.Builder,
        content: Buffer?,
        chunkSize: Int,
      ) {
        response.body(content!!)
        response.onResponseEnd(ShutdownConnection)
        response.removeHeader("Content-Length")
      }

      override fun newRequestBody(body: String): RequestBody = throw TestAbortedException("END_OF_STREAM not implemented for requests")
    }, ;

    abstract fun setBody(
      response: MockResponse.Builder,
      content: Buffer?,
      chunkSize: Int,
    )

    abstract fun newRequestBody(body: String): RequestBody

    fun setBody(
      response: MockResponse.Builder,
      content: String?,
      chunkSize: Int,
    ) {
      setBody(response, Buffer().writeUtf8(content!!), chunkSize)
    }
  }

  internal enum class ProxyConfig {
    NO_PROXY {
      override fun connect(
        server: MockWebServer,
        client: OkHttpClient,
      ): Call.Factory =
        client
          .newBuilder()
          .proxy(Proxy.NO_PROXY)
          .build()
    },
    CREATE_ARG {
      override fun connect(
        server: MockWebServer,
        client: OkHttpClient,
      ): Call.Factory =
        client
          .newBuilder()
          .proxy(server.proxyAddress)
          .build()
    },
    PROXY_SYSTEM_PROPERTY {
      override fun connect(
        server: MockWebServer,
        client: OkHttpClient,
      ): Call.Factory {
        System.setProperty("proxyHost", server.hostName)
        System.setProperty("proxyPort", server.port.toString())
        return client
      }
    },
    HTTP_PROXY_SYSTEM_PROPERTY {
      override fun connect(
        server: MockWebServer,
        client: OkHttpClient,
      ): Call.Factory {
        System.setProperty("http.proxyHost", server.hostName)
        System.setProperty("http.proxyPort", server.port.toString())
        return client
      }
    },
    HTTPS_PROXY_SYSTEM_PROPERTY {
      override fun connect(
        server: MockWebServer,
        client: OkHttpClient,
      ): Call.Factory {
        System.setProperty("https.proxyHost", server.hostName)
        System.setProperty("https.proxyPort", server.port.toString())
        return client
      }
    }, ;

    abstract fun connect(
      server: MockWebServer,
      client: OkHttpClient,
    ): Call.Factory

    fun connect(
      server: MockWebServer,
      client: OkHttpClient,
      url: HttpUrl,
    ): Call =
      connect(server, client)
        .newCall(Request(url))
  }

  private class RecordingTrustManager(
    private val delegate: X509TrustManager,
  ) : X509TrustManager {
    val calls: MutableList<String> = ArrayList()

    override fun getAcceptedIssuers(): Array<X509Certificate> = delegate.acceptedIssuers

    override fun checkClientTrusted(
      chain: Array<X509Certificate>,
      authType: String,
    ) {
      calls.add("checkClientTrusted " + certificatesToString(chain))
    }

    override fun checkServerTrusted(
      chain: Array<X509Certificate>,
      authType: String,
    ) {
      calls.add("checkServerTrusted " + certificatesToString(chain))
    }

    private fun certificatesToString(certificates: Array<X509Certificate>): String {
      val result: MutableList<String> = ArrayList()
      for (certificate in certificates) {
        result.add(certificate.subjectDN.toString() + " " + certificate.serialNumber)
      }
      return result.toString()
    }
  }

  /**
   * Tests that use this will fail unless boot classpath is set. Ex. `-Xbootclasspath/p:/tmp/alpn-boot-8.0.0.v20140317`
   */
  private fun enableProtocol(protocol: Protocol) {
    client =
      client
        .newBuilder()
        .sslSocketFactory(
          handshakeCertificates.sslSocketFactory(),
          handshakeCertificates.trustManager,
        ).hostnameVerifier(RecordingHostnameVerifier())
        .protocols(Arrays.asList(protocol, Protocol.HTTP_1_1))
        .build()
    server.useHttps(handshakeCertificates.sslSocketFactory())
    server.protocolNegotiationEnabled = true
    server.protocols = client.protocols
  }

  /**
   * Used during tests that involve TLS connection fallback attempts. OkHttp includes the
   * TLS_FALLBACK_SCSV cipher on fallback connections. See [FallbackTestClientSocketFactory]
   * for details.
   */
  private fun suppressTlsFallbackClientSocketFactory() = FallbackTestClientSocketFactory(handshakeCertificates.sslSocketFactory())
}
