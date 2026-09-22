/*
 * Copyright (C) 2013 Square, Inc.
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
package okhttp3.internal.http2

import app.cash.burst.Burst
import app.cash.burst.burstValues
import assertk.assertThat
import assertk.assertions.contains
import assertk.assertions.hasMessage
import assertk.assertions.isCloseTo
import assertk.assertions.isEqualTo
import assertk.assertions.isFalse
import assertk.assertions.isNull
import assertk.assertions.isTrue
import assertk.fail
import java.io.IOException
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.time.Duration
import java.util.Arrays
import java.util.concurrent.BlockingQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.SynchronousQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicReference
import javax.net.ssl.SSLException
import kotlin.test.assertFailsWith
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.PushPromise
import mockwebserver3.QueueDispatcher
import mockwebserver3.RecordedRequest
import mockwebserver3.SocketEffect.CloseStream
import mockwebserver3.SocketEffect.ShutdownConnection
import mockwebserver3.SocketEffect.Stall
import mockwebserver3.junit5.StartStop
import okhttp3.Cache
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Connection
import okhttp3.Cookie
import okhttp3.Credentials.basic
import okhttp3.EventListener
import okhttp3.Headers.Companion.headersOf
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Interceptor
import okhttp3.MediaType
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.OkHttpClientTestRule
import okhttp3.Protocol
import okhttp3.RecordingCookieJar
import okhttp3.RecordingHostnameVerifier
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.Response
import okhttp3.Route
import okhttp3.TestLogHandler
import okhttp3.TestUtil.assumeNotWindows
import okhttp3.TestUtil.repeat
import okhttp3.TestUtil.threadFactory
import okhttp3.internal.DoubleInetAddressDns
import okhttp3.internal.RecordingOkAuthenticator
import okhttp3.internal.connection.RealConnection
import okhttp3.internal.discard
import okhttp3.testing.Flaky
import okhttp3.testing.PlatformRule
import okhttp3.tls.HandshakeCertificates
import okio.Buffer
import okio.BufferedSink
import okio.GzipSink
import okio.Path.Companion.toPath
import okio.buffer
import okio.fakefilesystem.FakeFileSystem
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Disabled
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.api.extension.RegisterExtension

/** Test how HTTP/2 interacts with HTTP features.  */
@Timeout(60)
@Flaky
@Tag("Slow")
@Burst
class HttpOverHttp2Test(
  val protocol: Protocol = burstValues(Protocol.H2_PRIOR_KNOWLEDGE, Protocol.HTTP_2),
) {
  @RegisterExtension
  val platform: PlatformRule = PlatformRule()

  @RegisterExtension
  val clientTestRule = configureClientTestRule()

  @RegisterExtension
  val testLogHandler: TestLogHandler = TestLogHandler(Http2::class.java)

  // Flaky https://github.com/lysine-dev/okhttp/issues/4632
  // Flaky https://github.com/lysine-dev/okhttp/issues/4633
  private val handshakeCertificates: HandshakeCertificates =
    platform.localhostHandshakeCertificates()

  @StartStop
  private val server = MockWebServer()

  private lateinit var client: OkHttpClient
  private val fileSystem: FakeFileSystem = FakeFileSystem()
  private val cache: Cache = Cache(fileSystem, "/tmp/cache".toPath(), Long.MAX_VALUE)
  private lateinit var scheme: String

  private fun configureClientTestRule(): OkHttpClientTestRule {
    val clientTestRule = OkHttpClientTestRule()
    clientTestRule.recordTaskRunner = true
    return clientTestRule
  }

  @BeforeEach
  fun setUp() {
    platform.assumeNotOpenJSSE()
    if (protocol === Protocol.HTTP_2) {
      platform.assumeHttp2Support()
      server.useHttps(handshakeCertificates.sslSocketFactory())
      client =
        clientTestRule
          .newClientBuilder()
          .protocols(listOf(Protocol.HTTP_2, Protocol.HTTP_1_1))
          .sslSocketFactory(
            handshakeCertificates.sslSocketFactory(),
            handshakeCertificates.trustManager,
          ).hostnameVerifier(RecordingHostnameVerifier())
          .build()
      scheme = "https"
    } else {
      server.protocols = listOf(Protocol.H2_PRIOR_KNOWLEDGE)
      client =
        clientTestRule
          .newClientBuilder()
          .protocols(listOf(Protocol.H2_PRIOR_KNOWLEDGE))
          .build()
      scheme = "http"
    }
  }

  @AfterEach fun tearDown() {
//    TODO reenable after https://github.com/lysine-dev/okhttp/issues/8206
//    fileSystem.checkNoOpenFiles()
    cache.close()

    java.net.Authenticator.setDefault(null)
  }

  @Test
  fun get() {
    server.enqueue(MockResponse(body = "ABCDE"))
    val call = client.newCall(Request(server.url("/foo")))
    val response = call.execute()
    assertThat(response.body.string()).isEqualTo("ABCDE")
    assertThat(response.code).isEqualTo(200)
    assertThat(response.message).isEqualTo("")
    assertThat(response.protocol).isEqualTo(protocol)
    val request = server.takeRequest()
    assertThat(request.requestLine).isEqualTo("GET /foo HTTP/2")
    assertThat(request.headers[":scheme"]).isEqualTo(scheme)
    assertThat(request.headers[":authority"]).isEqualTo("${server.hostName}:${server.port}")
  }

  @Test
  fun get204Response() {
    val responseWithoutBody =
      MockResponse
        .Builder()
        .status("HTTP/1.1 204")
        .removeHeader("Content-Length")
        .build()
    server.enqueue(responseWithoutBody)
    val call = client.newCall(Request(server.url("/foo")))
    val response = call.execute()

    // Body contains nothing.
    assertThat(response.body.bytes().size).isEqualTo(0)
    assertThat(response.body.contentLength()).isEqualTo(0)

    // Content-Length header doesn't exist in a 204 response.
    assertThat(response.header("content-length")).isNull()
    assertThat(response.code).isEqualTo(204)
    val request = server.takeRequest()
    assertThat(request.requestLine).isEqualTo("GET /foo HTTP/2")
  }

  @Test
  fun head() {
    val mockResponse =
      MockResponse
        .Builder()
        .setHeader("Content-Length", 5)
        .status("HTTP/1.1 200")
        .build()
    server.enqueue(mockResponse)
    val call =
      client.newCall(
        Request
          .Builder()
          .head()
          .url(server.url("/foo"))
          .build(),
      )
    val response = call.execute()

    // Body contains nothing.
    assertThat(response.body.bytes().size).isEqualTo(0)
    assertThat(response.body.contentLength()).isEqualTo(0)

    // Content-Length header stays correctly.
    assertThat(response.header("content-length")).isEqualTo("5")
    val request = server.takeRequest()
    assertThat(request.requestLine).isEqualTo("HEAD /foo HTTP/2")
  }

  @Test
  fun emptyResponse() {
    server.enqueue(MockResponse())
    val call = client.newCall(Request(server.url("/foo")))
    val response = call.execute()
    assertThat(response.body.byteStream().read()).isEqualTo(-1)
    response.body.close()
  }

  @Test
  fun noDefaultContentLengthOnStreamingPost() {
    val postBytes = "FGHIJ".toByteArray()
    server.enqueue(MockResponse(body = "ABCDE"))
    val call =
      client.newCall(
        Request(
          url = server.url("/foo"),
          body =
            object : RequestBody() {
              override fun contentType(): MediaType = "text/plain; charset=utf-8".toMediaType()

              override fun writeTo(sink: BufferedSink) {
                sink.write(postBytes)
              }
            },
        ),
      )
    val response = call.execute()
    assertThat(response.body.string()).isEqualTo("ABCDE")
    val request = server.takeRequest()
    assertThat(request.requestLine).isEqualTo("POST /foo HTTP/2")
    assertArrayEquals(postBytes, request.body?.toByteArray())
    assertThat(request.headers["Content-Length"]).isNull()
  }

  @Test
  fun userSuppliedContentLengthHeader() {
    val postBytes = "FGHIJ".toByteArray()
    server.enqueue(MockResponse(body = "ABCDE"))
    val call =
      client.newCall(
        Request(
          url = server.url("/foo"),
          body =
            object : RequestBody() {
              override fun contentType(): MediaType = "text/plain; charset=utf-8".toMediaType()

              override fun contentLength(): Long = postBytes.size.toLong()

              override fun writeTo(sink: BufferedSink) {
                sink.write(postBytes)
              }
            },
        ),
      )
    val response = call.execute()
    assertThat(response.body.string()).isEqualTo("ABCDE")
    val request = server.takeRequest()
    assertThat(request.requestLine).isEqualTo("POST /foo HTTP/2")
    assertArrayEquals(postBytes, request.body?.toByteArray())
    assertThat(request.headers["Content-Length"]!!.toInt()).isEqualTo(postBytes.size)
  }

  @Test
  fun closeAfterFlush() {
    val postBytes = "FGHIJ".toByteArray()
    server.enqueue(MockResponse(body = "ABCDE"))
    val call =
      client.newCall(
        Request(
          url = server.url("/foo"),
          body =
            object : RequestBody() {
              override fun contentType(): MediaType = "text/plain; charset=utf-8".toMediaType()

              override fun contentLength(): Long = postBytes.size.toLong()

              override fun writeTo(sink: BufferedSink) {
                sink.write(postBytes) // push bytes into the stream's buffer
                sink.flush() // Http2Connection.writeData subject to write window
                sink.close() // Http2Connection.writeData empty frame
              }
            },
        ),
      )
    val response = call.execute()
    assertThat(response.body.string()).isEqualTo("ABCDE")
    val request = server.takeRequest()
    assertThat(request.requestLine).isEqualTo("POST /foo HTTP/2")
    assertArrayEquals(postBytes, request.body?.toByteArray())
    assertThat(request.headers["Content-Length"]!!.toInt()).isEqualTo(postBytes.size)
  }

  @Test
  fun connectionReuse() {
    server.enqueue(MockResponse(body = "ABCDEF"))
    server.enqueue(MockResponse(body = "GHIJKL"))
    val call1 = client.newCall(Request(server.url("/r1")))
    val call2 = client.newCall(Request(server.url("/r1")))
    val response1 = call1.execute()
    val response2 = call2.execute()
    assertThat(response1.body.source().readUtf8(3)).isEqualTo("ABC")
    assertThat(response2.body.source().readUtf8(3)).isEqualTo("GHI")
    assertThat(response1.body.source().readUtf8(3)).isEqualTo("DEF")
    assertThat(response2.body.source().readUtf8(3)).isEqualTo("JKL")
    val c0e0 = server.takeRequest()
    assertThat(c0e0.connectionIndex).isEqualTo(0)
    assertThat(c0e0.exchangeIndex).isEqualTo(0)
    val c0e1 = server.takeRequest()
    assertThat(c0e1.connectionIndex).isEqualTo(0)
    assertThat(c0e1.exchangeIndex).isEqualTo(1)
    response1.close()
    response2.close()
  }

  @Test
  fun connectionWindowUpdateAfterCanceling() {
    server.enqueue(
      MockResponse
        .Builder()
        .body(Buffer().write(ByteArray(Http2Connection.OKHTTP_CLIENT_WINDOW_SIZE + 1)))
        .build(),
    )
    server.enqueue(
      MockResponse(body = "abc"),
    )
    val call1 = client.newCall(Request(server.url("/")))
    val response1 = call1.execute()
    waitForDataFrames(Http2Connection.OKHTTP_CLIENT_WINDOW_SIZE)

    // Cancel the call and discard what we've buffered for the response body. This should free up
    // the connection flow-control window so new requests can proceed.
    call1.cancel()
    assertThat(
      response1.body.source().discard(1, TimeUnit.SECONDS),
      "Call should not have completed successfully.",
    ).isFalse()
    val call2 = client.newCall(Request(server.url("/")))
    val response2 = call2.execute()
    assertThat(response2.body.string()).isEqualTo("abc")
  }

  /** Wait for the client to receive `dataLength` DATA frames.  */
  private fun waitForDataFrames(dataLength: Int) {
    val expectedFrameCount = dataLength / 16384
    var dataFrameCount = 0
    while (dataFrameCount < expectedFrameCount) {
      val log = testLogHandler.take()
      if (log == "FINE: << 0x00000003 16384 DATA          ") {
        dataFrameCount++
      }
    }
  }

  @Test
  fun connectionWindowUpdateOnClose() {
    server.enqueue(
      MockResponse
        .Builder()
        .body(Buffer().write(ByteArray(Http2Connection.OKHTTP_CLIENT_WINDOW_SIZE + 1)))
        .build(),
    )
    server.enqueue(
      MockResponse(body = "abc"),
    )
    // Enqueue an additional response that show if we burnt a good prior response.
    server.enqueue(
      MockResponse(body = "XXX"),
    )
    val call1 = client.newCall(Request(server.url("/")))
    val response1 = call1.execute()
    waitForDataFrames(Http2Connection.OKHTTP_CLIENT_WINDOW_SIZE)

    // Cancel the call and close the response body. This should discard the buffered data and update
    // the connection flow-control window.
    call1.cancel()
    response1.close()
    val call2 = client.newCall(Request(server.url("/")))
    val response2 = call2.execute()
    assertThat(response2.body.string()).isEqualTo("abc")
  }

  @Test
  fun concurrentRequestWithEmptyFlowControlWindow() {
    server.enqueue(
      MockResponse
        .Builder()
        .body(Buffer().write(ByteArray(Http2Connection.OKHTTP_CLIENT_WINDOW_SIZE)))
        .build(),
    )
    server.enqueue(
      MockResponse(body = "abc"),
    )
    val call1 = client.newCall(Request(server.url("/")))
    val response1 = call1.execute()
    waitForDataFrames(Http2Connection.OKHTTP_CLIENT_WINDOW_SIZE)
    assertThat(response1.body.contentLength()).isEqualTo(
      Http2Connection.OKHTTP_CLIENT_WINDOW_SIZE.toLong(),
    )
    val read = response1.body.source().read(ByteArray(8192))
    assertThat(read).isEqualTo(8192)

    // Make a second call that should transmit the response headers. The response body won't be
    // transmitted until the flow-control window is updated from the first request.
    val call2 = client.newCall(Request(server.url("/")))
    val response2 = call2.execute()
    assertThat(response2.code).isEqualTo(200)

    // Close the response body. This should discard the buffered data and update the connection
    // flow-control window.
    response1.close()
    assertThat(response2.body.string()).isEqualTo("abc")
  }

  /** https://github.com/lysine-dev/okhttp/issues/373  */
  @Test
  @Disabled
  fun synchronousRequest() {
    server.enqueue(MockResponse(body = "A"))
    server.enqueue(MockResponse(body = "A"))
    val executor = Executors.newCachedThreadPool(threadFactory("HttpOverHttp2Test"))
    val countDownLatch = CountDownLatch(2)
    executor.execute(AsyncRequest("/r1", countDownLatch))
    executor.execute(AsyncRequest("/r2", countDownLatch))
    countDownLatch.await()
    assertThat(server.takeRequest().exchangeIndex).isEqualTo(0)
    assertThat(server.takeRequest().exchangeIndex).isEqualTo(1)
  }

  @Test
  fun gzippedResponseBody() {
    server.enqueue(
      MockResponse
        .Builder()
        .addHeader("Content-Encoding: gzip")
        .body(gzip("ABCABCABC"))
        .build(),
    )
    val call = client.newCall(Request(server.url("/r1")))
    val response = call.execute()
    assertThat(response.body.string()).isEqualTo("ABCABCABC")
  }

  @Test
  fun authenticate() {
    server.enqueue(
      MockResponse(
        code = HttpURLConnection.HTTP_UNAUTHORIZED,
        headers = headersOf("www-authenticate", "Basic realm=\"protected area\""),
        body = "Please authenticate.",
      ),
    )
    server.enqueue(
      MockResponse(body = "Successful auth!"),
    )
    val credential = basic("username", "password")
    client =
      client
        .newBuilder()
        .authenticator(RecordingOkAuthenticator(credential, "Basic"))
        .build()
    val call = client.newCall(Request(server.url("/")))
    val response = call.execute()
    assertThat(response.body.string()).isEqualTo("Successful auth!")
    val denied = server.takeRequest()
    assertThat(denied.headers["Authorization"]).isNull()
    val accepted = server.takeRequest()
    assertThat(accepted.requestLine).isEqualTo("GET / HTTP/2")
    assertThat(accepted.headers["Authorization"]).isEqualTo(credential)
  }

  @Test
  fun redirect() {
    server.enqueue(
      MockResponse(
        code = HttpURLConnection.HTTP_MOVED_TEMP,
        headers = headersOf("Location", "/foo"),
        body = "This page has moved!",
      ),
    )
    server.enqueue(MockResponse(body = "This is the new location!"))
    val call = client.newCall(Request(server.url("/")))
    val response = call.execute()
    assertThat(response.body.string()).isEqualTo("This is the new location!")
    val request1 = server.takeRequest()
    assertThat(request1.url.encodedPath).isEqualTo("/")
    val request2 = server.takeRequest()
    assertThat(request2.url.encodedPath).isEqualTo("/foo")
  }

  @Test
  fun readAfterLastByte() {
    server.enqueue(MockResponse(body = "ABC"))
    val call = client.newCall(Request(server.url("/")))
    val response = call.execute()
    val inputStream = response.body.byteStream()
    assertThat(inputStream.read()).isEqualTo('A'.code)
    assertThat(inputStream.read()).isEqualTo('B'.code)
    assertThat(inputStream.read()).isEqualTo('C'.code)
    assertThat(inputStream.read()).isEqualTo(-1)
    assertThat(inputStream.read()).isEqualTo(-1)
    inputStream.close()
  }

  @Test
  fun readResponseHeaderTimeout() {
    server.enqueue(MockResponse.Builder().onResponseStart(Stall).build())
    server.enqueue(MockResponse(body = "A"))
    client =
      client
        .newBuilder()
        .readTimeout(Duration.ofSeconds(1))
        .build()

    // Make a call expecting a timeout reading the response headers.
    val call1 = client.newCall(Request(server.url("/")))
    assertFailsWith<SocketTimeoutException> {
      call1.execute()
    }.also { expected ->
      assertThat(expected.message).isEqualTo("timeout")
    }

    // Confirm that a subsequent request on the same connection is not impacted.
    val call2 = client.newCall(Request(server.url("/")))
    val response2 = call2.execute()
    assertThat(response2.body.string()).isEqualTo("A")

    // Confirm that the connection was reused.
    assertThat(server.takeRequest().exchangeIndex).isEqualTo(0)
    assertThat(server.takeRequest().exchangeIndex).isEqualTo(1)
  }

  /**
   * Test to ensure we don't  throw a read timeout on responses that are progressing.  For this
   * case, we take a 4KiB body and throttle it to 1KiB/second.  We set the read timeout to two
   * seconds.  If our implementation is acting correctly, it will not throw, as it is progressing.
   */
  @Test
  fun readTimeoutMoreGranularThanBodySize() {
    val body = CharArray(4096) // 4KiB to read.
    Arrays.fill(body, 'y')
    server.enqueue(
      MockResponse
        .Builder()
        .body(String(body))
        .throttleBody(1024, 1, TimeUnit.SECONDS) // Slow connection 1KiB/second.
        .build(),
    )
    client =
      client
        .newBuilder()
        .readTimeout(Duration.ofSeconds(2))
        .build()
    val call = client.newCall(Request(server.url("/")))
    val response = call.execute()
    assertThat(response.body.string()).isEqualTo(String(body))
  }

  /**
   * Test to ensure we throw a read timeout on responses that are progressing too slowly.  For this
   * case, we take a 2KiB body and throttle it to 1KiB/second.  We set the read timeout to half a
   * second.  If our implementation is acting correctly, it will throw, as a byte doesn't arrive in
   * time.
   */
  @Test
  fun readTimeoutOnSlowConnection() {
    val body = repeat('y', 2048)
    server.enqueue(
      MockResponse
        .Builder()
        .body(body)
        .throttleBody(1024, 1, TimeUnit.SECONDS)
        .build(),
    ) // Slow connection 1KiB/second.
    server.enqueue(
      MockResponse(body = body),
    )
    client =
      client
        .newBuilder()
        .readTimeout(Duration.ofMillis(500)) // Half a second to read something.
        .build()

    // Make a call expecting a timeout reading the response body.
    val call1 = client.newCall(Request(server.url("/")))
    val response1 = call1.execute()
    assertFailsWith<SocketTimeoutException> {
      response1.body.string()
    }.also { expected ->
      assertThat(expected.message).isEqualTo("timeout")
    }

    // Confirm that a subsequent request on the same connection is not impacted.
    val call2 = client.newCall(Request(server.url("/")))
    val response2 = call2.execute()
    assertThat(response2.body.string()).isEqualTo(body)

    // Confirm that the connection was reused.
    assertThat(server.takeRequest().exchangeIndex).isEqualTo(0)
    assertThat(server.takeRequest().exchangeIndex).isEqualTo(1)
  }

  @Test
  fun connectionTimeout() {
    server.enqueue(
      MockResponse
        .Builder()
        .body("A")
        .bodyDelay(1, TimeUnit.SECONDS)
        .build(),
    )
    val client1 =
      client
        .newBuilder()
        .readTimeout(Duration.ofSeconds(2))
        .build()
    val call1 =
      client1
        .newCall(
          Request
            .Builder()
            .url(server.url("/"))
            .build(),
        )
    val client2 =
      client
        .newBuilder()
        .readTimeout(Duration.ofMillis(200))
        .build()
    val call2 =
      client2
        .newCall(
          Request
            .Builder()
            .url(server.url("/"))
            .build(),
        )
    val response1 = call1.execute()
    assertThat(response1.body.string()).isEqualTo("A")
    assertFailsWith<IOException> {
      call2.execute()
    }

    // Confirm that the connection was reused.
    assertThat(server.takeRequest().exchangeIndex).isEqualTo(0)
    assertThat(server.takeRequest().exchangeIndex).isEqualTo(1)
  }

  @Test
  fun responsesAreCached() {
    client =
      client
        .newBuilder()
        .cache(cache)
        .build()
    server.enqueue(
      MockResponse(
        headers = headersOf("cache-control", "max-age=60"),
        body = "A",
      ),
    )
    val call1 = client.newCall(Request(server.url("/")))
    val response1 = call1.execute()
    assertThat(response1.body.string()).isEqualTo("A")
    assertThat(cache.requestCount()).isEqualTo(1)
    assertThat(cache.networkCount()).isEqualTo(1)
    assertThat(cache.hitCount()).isEqualTo(0)
    val call2 = client.newCall(Request(server.url("/")))
    val response2 = call2.execute()
    assertThat(response2.body.string()).isEqualTo("A")
    val call3 = client.newCall(Request(server.url("/")))
    val response3 = call3.execute()
    assertThat(response3.body.string()).isEqualTo("A")
    assertThat(cache.requestCount()).isEqualTo(3)
    assertThat(cache.networkCount()).isEqualTo(1)
    assertThat(cache.hitCount()).isEqualTo(2)
  }

  @Test
  fun conditionalCache() {
    client =
      client
        .newBuilder()
        .cache(cache)
        .build()
    server.enqueue(
      MockResponse(
        headers = headersOf("ETag", "v1"),
        body = "A",
      ),
    )
    server.enqueue(
      MockResponse(code = HttpURLConnection.HTTP_NOT_MODIFIED),
    )
    val call1 = client.newCall(Request(server.url("/")))
    val response1 = call1.execute()
    assertThat(response1.body.string()).isEqualTo("A")
    assertThat(cache.requestCount()).isEqualTo(1)
    assertThat(cache.networkCount()).isEqualTo(1)
    assertThat(cache.hitCount()).isEqualTo(0)
    val call2 = client.newCall(Request(server.url("/")))
    val response2 = call2.execute()
    assertThat(response2.body.string()).isEqualTo("A")
    assertThat(cache.requestCount()).isEqualTo(2)
    assertThat(cache.networkCount()).isEqualTo(2)
    assertThat(cache.hitCount()).isEqualTo(1)
  }

  @Test
  fun responseCachedWithoutConsumingFullBody() {
    client =
      client
        .newBuilder()
        .cache(cache)
        .build()
    server.enqueue(
      MockResponse(
        headers = headersOf("cache-control", "max-age=60"),
        body = "ABCD",
      ),
    )
    server.enqueue(
      MockResponse(
        headers = headersOf("cache-control", "max-age=60"),
        body = "EFGH",
      ),
    )
    val call1 = client.newCall(Request(server.url("/")))
    val response1 = call1.execute()
    assertThat(response1.body.source().readUtf8(2)).isEqualTo("AB")
    response1.body.close()
    val call2 = client.newCall(Request(server.url("/")))
    val response2 = call2.execute()
    assertThat(response2.body.source().readUtf8()).isEqualTo("ABCD")
    response2.body.close()
  }

  @Test
  fun sendRequestCookies() {
    val cookieJar = RecordingCookieJar()
    val requestCookie =
      Cookie
        .Builder()
        .name("a")
        .value("b")
        .domain(server.hostName)
        .build()
    cookieJar.enqueueRequestCookies(requestCookie)
    client =
      client
        .newBuilder()
        .cookieJar(cookieJar)
        .build()
    server.enqueue(MockResponse())
    val call = client.newCall(Request(server.url("/")))
    val response = call.execute()
    assertThat(response.body.string()).isEqualTo("")
    val request = server.takeRequest()
    assertThat(request.headers["Cookie"]).isEqualTo("a=b")
  }

  @Test
  fun receiveResponseCookies() {
    val cookieJar = RecordingCookieJar()
    client =
      client
        .newBuilder()
        .cookieJar(cookieJar)
        .build()
    server.enqueue(
      MockResponse(headers = headersOf("set-cookie", "a=b")),
    )
    val call = client.newCall(Request(server.url("/")))
    val response = call.execute()
    assertThat(response.body.string()).isEqualTo("")
    cookieJar.assertResponseCookies("a=b; path=/")
  }

  @Test
  fun cancelWithStreamNotCompleted() {
    server.enqueue(MockResponse(body = "abc"))
    server.enqueue(MockResponse(body = "def"))

    // Disconnect before the stream is created. A connection is still established!
    val call1 = client.newCall(Request(server.url("/")))
    val response = call1.execute()
    call1.cancel()

    // That connection is pooled, and it works.
    assertThat(client.connectionPool.connectionCount()).isEqualTo(1)
    val call2 = client.newCall(Request(server.url("/")))
    val response2 = call2.execute()
    assertThat(response2.body.string()).isEqualTo("def")
    assertThat(server.takeRequest().exchangeIndex).isEqualTo(0)

    // Clean up the connection.
    response.close()
  }

  @Test
  fun noRecoveryFromOneRefusedStream() {
    server.enqueue(
      MockResponse
        .Builder()
        .onRequestStart(CloseStream(ErrorCode.REFUSED_STREAM.httpCode))
        .build(),
    )
    server.enqueue(MockResponse(body = "abc"))
    val call = client.newCall(Request(server.url("/")))
    assertFailsWith<StreamResetException> {
      call.execute()
    }.also { expected ->
      assertThat(expected.errorCode).isEqualTo(ErrorCode.REFUSED_STREAM)
    }
  }

  @Test
  fun recoverFromRefusedStreamWhenAnotherRouteExists() {
    client =
      client
        .newBuilder()
        .dns(DoubleInetAddressDns()) // Two routes!
        .build()
    server.enqueue(
      MockResponse
        .Builder()
        .onRequestStart(CloseStream(ErrorCode.REFUSED_STREAM.httpCode))
        .build(),
    )
    server.enqueue(MockResponse(body = "abc"))

    val request = Request(server.url("/"))
    val response = client.newCall(request).execute()
    assertThat(response.body.string()).isEqualTo("abc")
    assertThat(server.takeRequest().exchangeIndex).isEqualTo(0)

    // Note that although we have two routes available, we only use one. The retry is permitted
    // because there are routes available, but it chooses the existing connection since it isn't
    // yet considered unhealthy.
    assertThat(server.takeRequest().exchangeIndex).isEqualTo(1)
  }

  @Test
  fun noRecoveryWhenRoutesExhausted() {
    client =
      client
        .newBuilder()
        .dns(DoubleInetAddressDns()) // Two routes!
        .build()
    server.enqueue(
      MockResponse
        .Builder()
        .onRequestStart(CloseStream(ErrorCode.REFUSED_STREAM.httpCode))
        .build(),
    )
    server.enqueue(
      MockResponse
        .Builder()
        .onRequestStart(CloseStream(ErrorCode.REFUSED_STREAM.httpCode))
        .build(),
    )
    server.enqueue(
      MockResponse
        .Builder()
        .onRequestStart(CloseStream(ErrorCode.REFUSED_STREAM.httpCode))
        .build(),
    )

    val request = Request(server.url("/"))
    assertFailsWith<StreamResetException> {
      client.newCall(request).execute()
    }.also { expected ->
      assertThat(expected.errorCode).isEqualTo(ErrorCode.REFUSED_STREAM)
    }
    assertThat(server.takeRequest().exchangeIndex).isEqualTo(0) // New connection.
    assertThat(server.takeRequest().exchangeIndex).isEqualTo(1) // Pooled connection.
    assertThat(server.takeRequest().exchangeIndex).isEqualTo(0) // New connection.
  }

  @Test
  fun connectionWithOneRefusedStreamIsPooled() {
    server.enqueue(
      MockResponse
        .Builder()
        .onRequestStart(CloseStream(ErrorCode.REFUSED_STREAM.httpCode))
        .build(),
    )
    server.enqueue(MockResponse(body = "abc"))
    val request = Request(server.url("/"))

    // First call fails because it only has one route.
    assertFailsWith<StreamResetException> {
      client.newCall(request).execute()
    }.also { expected ->
      assertThat(expected.errorCode).isEqualTo(ErrorCode.REFUSED_STREAM)
    }
    assertThat(server.takeRequest().exchangeIndex).isEqualTo(0)

    // Second call succeeds on the pooled connection.
    val response = client.newCall(request).execute()
    assertThat(response.body.string()).isEqualTo("abc")
    assertThat(server.takeRequest().exchangeIndex).isEqualTo(1)
  }

  @Test
  fun connectionWithTwoRefusedStreamsIsNotPooled() {
    server.enqueue(
      MockResponse
        .Builder()
        .onRequestStart(CloseStream(ErrorCode.REFUSED_STREAM.httpCode))
        .build(),
    )
    server.enqueue(
      MockResponse
        .Builder()
        .onRequestStart(CloseStream(ErrorCode.REFUSED_STREAM.httpCode))
        .build(),
    )
    server.enqueue(MockResponse(body = "abc"))
    server.enqueue(MockResponse(body = "def"))
    val request = Request(server.url("/"))

    // First call makes a new connection and fails because it is the only route.
    assertFailsWith<StreamResetException> {
      client.newCall(request).execute()
    }.also { expected ->
      assertThat(expected.errorCode).isEqualTo(ErrorCode.REFUSED_STREAM)
    }
    assertThat(server.takeRequest().exchangeIndex).isEqualTo(0) // New connection.

    // Second call attempts the pooled connection, and it fails. Then it retries a new route which
    // succeeds.
    val response2 = client.newCall(request).execute()
    assertThat(response2.body.string()).isEqualTo("abc")
    assertThat(server.takeRequest().exchangeIndex).isEqualTo(1) // Pooled connection.
    assertThat(server.takeRequest().exchangeIndex).isEqualTo(0) // New connection.

    // Third call reuses the second connection.
    val response3 = client.newCall(request).execute()
    assertThat(response3.body.string()).isEqualTo("def")
    assertThat(server.takeRequest().exchangeIndex).isEqualTo(1) // New connection.
  }

  /**
   * We had a bug where we'd perform infinite retries of route that fail with connection shutdown
   * errors. The problem was that the logic that decided whether to reuse a route didn't track
   * certain HTTP/2 errors. https://github.com/lysine-dev/okhttp/issues/5547
   */
  @Test
  fun noRecoveryFromTwoRefusedStreams() {
    server.enqueue(
      MockResponse
        .Builder()
        .onRequestStart(CloseStream(ErrorCode.REFUSED_STREAM.httpCode))
        .build(),
    )
    server.enqueue(
      MockResponse
        .Builder()
        .onRequestStart(CloseStream(ErrorCode.REFUSED_STREAM.httpCode))
        .build(),
    )
    server.enqueue(
      MockResponse(body = "abc"),
    )
    val call = client.newCall(Request(server.url("/")))
    assertFailsWith<StreamResetException> {
      call.execute()
    }.also { expected ->
      assertThat(expected.errorCode).isEqualTo(ErrorCode.REFUSED_STREAM)
    }
  }

  @Test
  fun recoverFromOneInternalErrorRequiresNewConnection() {
    recoverFromOneHttp2ErrorRequiresNewConnection(ErrorCode.INTERNAL_ERROR)
  }

  @Test
  fun recoverFromOneCancelRequiresNewConnection() {
    recoverFromOneHttp2ErrorRequiresNewConnection(ErrorCode.CANCEL)
  }

  private fun recoverFromOneHttp2ErrorRequiresNewConnection(errorCode: ErrorCode?) {
    server.enqueue(
      MockResponse.Builder().onRequestStart(CloseStream(errorCode!!.httpCode)).build(),
    )
    server.enqueue(MockResponse(body = "abc"))
    client =
      client
        .newBuilder()
        .dns(DoubleInetAddressDns())
        .build()
    val call = client.newCall(Request(server.url("/")))
    val response = call.execute()
    assertThat(response.body.string()).isEqualTo("abc")

    // New connection.
    val c0e0 = server.takeRequest()
    assertThat(c0e0.connectionIndex).isEqualTo(0)
    assertThat(c0e0.exchangeIndex).isEqualTo(0)
    // New connection.
    val c1e0 = server.takeRequest()
    assertThat(c1e0.connectionIndex).isEqualTo(1)
    assertThat(c1e0.exchangeIndex).isEqualTo(0)
  }

  @Test
  fun recoverFromMultipleRefusedStreamsRequiresNewConnection() {
    server.enqueue(
      MockResponse
        .Builder()
        .onRequestStart(CloseStream(ErrorCode.REFUSED_STREAM.httpCode))
        .build(),
    )
    server.enqueue(
      MockResponse
        .Builder()
        .onRequestStart(CloseStream(ErrorCode.REFUSED_STREAM.httpCode))
        .build(),
    )
    server.enqueue(MockResponse(body = "abc"))
    client =
      client
        .newBuilder()
        .dns(DoubleInetAddressDns())
        .build()
    val call = client.newCall(Request(server.url("/")))
    val response = call.execute()
    assertThat(response.body.string()).isEqualTo("abc")

    // New connection.
    assertThat(server.takeRequest().exchangeIndex).isEqualTo(0)
    // Reused connection.
    assertThat(server.takeRequest().exchangeIndex).isEqualTo(1)
    // New connection.
    assertThat(server.takeRequest().exchangeIndex).isEqualTo(0)
  }

  @Test
  fun recoverFromCancelReusesConnection() {
    val responseDequeuedLatches =
      listOf(
        // No synchronization for the last request, which is not canceled:
        CountDownLatch(1),
        CountDownLatch(0),
      )
    val requestCanceledLatches =
      listOf(
        CountDownLatch(1),
        CountDownLatch(0),
      )
    val dispatcher = RespondAfterCancelDispatcher(responseDequeuedLatches, requestCanceledLatches)
    dispatcher.enqueue(
      MockResponse
        .Builder()
        .bodyDelay(10, TimeUnit.SECONDS)
        .body("abc")
        .build(),
    )
    dispatcher.enqueue(
      MockResponse(body = "def"),
    )
    server.dispatcher = dispatcher
    client =
      client
        .newBuilder()
        .dns(DoubleInetAddressDns())
        .build()
    callAndCancel(0, responseDequeuedLatches[0], requestCanceledLatches[0])

    // Make a second request to ensure the connection is reused.
    val call = client.newCall(Request(server.url("/")))
    val response = call.execute()
    assertThat(response.body.string()).isEqualTo("def")
    assertThat(server.takeRequest().exchangeIndex).isEqualTo(1)
  }

  @Test
  fun recoverFromMultipleCancelReusesConnection() {
    val responseDequeuedLatches =
      Arrays.asList(
        CountDownLatch(1),
        // No synchronization for the last request, which is not canceled:
        CountDownLatch(1),
        CountDownLatch(0),
      )
    val requestCanceledLatches =
      Arrays.asList(
        CountDownLatch(1),
        CountDownLatch(1),
        CountDownLatch(0),
      )
    val dispatcher = RespondAfterCancelDispatcher(responseDequeuedLatches, requestCanceledLatches)
    dispatcher.enqueue(
      MockResponse
        .Builder()
        .bodyDelay(10, TimeUnit.SECONDS)
        .body("abc")
        .build(),
    )
    dispatcher.enqueue(
      MockResponse
        .Builder()
        .bodyDelay(10, TimeUnit.SECONDS)
        .body("def")
        .build(),
    )
    dispatcher.enqueue(
      MockResponse(body = "ghi"),
    )
    server.dispatcher = dispatcher
    client =
      client
        .newBuilder()
        .dns(DoubleInetAddressDns())
        .build()
    callAndCancel(0, responseDequeuedLatches[0], requestCanceledLatches[0])
    callAndCancel(1, responseDequeuedLatches[1], requestCanceledLatches[1])

    // Make a third request to ensure the connection is reused.
    val call = client.newCall(Request(server.url("/")))
    val response = call.execute()
    assertThat(response.body.string()).isEqualTo("ghi")
    assertThat(server.takeRequest().exchangeIndex).isEqualTo(2)
  }

  private class RespondAfterCancelDispatcher(
    private val responseDequeuedLatches: List<CountDownLatch>,
    private val requestCanceledLatches: List<CountDownLatch>,
  ) : QueueDispatcher() {
    private var responseIndex = 0

    @Synchronized
    override fun dispatch(request: RecordedRequest): MockResponse {
      // This guarantees a deterministic sequence when handling the canceled request:
      // 1. Server reads request and dequeues first response
      // 2. Client cancels request
      // 3. Server tries to send response on the canceled stream
      // Otherwise, there is no guarantee for the sequence. For example, the server may use the
      // first mocked response to respond to the second request.
      val response = super.dispatch(request)
      responseDequeuedLatches[responseIndex].countDown()
      requestCanceledLatches[responseIndex].await()
      responseIndex++
      return response
    }
  }

  /** Make a call and canceling it as soon as it's accepted by the server.  */
  private fun callAndCancel(
    expectedSequenceNumber: Int,
    responseDequeuedLatch: CountDownLatch?,
    requestCanceledLatch: CountDownLatch?,
  ) {
    val call = client.newCall(Request(server.url("/")))
    val latch = CountDownLatch(1)
    call.enqueue(
      object : Callback {
        override fun onFailure(
          call: Call,
          e: IOException,
        ) {
          latch.countDown()
        }

        override fun onResponse(
          call: Call,
          response: Response,
        ) {
          fail("")
        }
      },
    )
    assertThat(server.takeRequest().exchangeIndex)
      .isEqualTo(expectedSequenceNumber)
    responseDequeuedLatch!!.await()
    call.cancel()
    // Avoid flaky race conditions
    Thread.sleep(100)
    requestCanceledLatch!!.countDown()
    latch.await()
  }

  @Test
  fun noRecoveryFromRefusedStreamWithRetryDisabled() {
    noRecoveryFromErrorWithRetryDisabled(ErrorCode.REFUSED_STREAM)
  }

  @Test
  fun noRecoveryFromInternalErrorWithRetryDisabled() {
    noRecoveryFromErrorWithRetryDisabled(ErrorCode.INTERNAL_ERROR)
  }

  @Test
  fun noRecoveryFromCancelWithRetryDisabled() {
    noRecoveryFromErrorWithRetryDisabled(ErrorCode.CANCEL)
  }

  private fun noRecoveryFromErrorWithRetryDisabled(errorCode: ErrorCode?) {
    server.enqueue(
      MockResponse.Builder().onRequestStart(CloseStream(errorCode!!.httpCode)).build(),
    )
    server.enqueue(MockResponse(body = "abc"))
    client =
      client
        .newBuilder()
        .retryOnConnectionFailure(false)
        .build()
    val call = client.newCall(Request(server.url("/")))
    assertFailsWith<StreamResetException> {
      call.execute()
    }.also { expected ->
      assertThat(expected.errorCode).isEqualTo(errorCode)
    }
  }

  @Test
  fun recoverFromConnectionNoNewStreamsOnFollowUp() {
    server.enqueue(MockResponse(code = 401))
    server.enqueue(
      MockResponse
        .Builder()
        .onRequestStart(CloseStream(ErrorCode.INTERNAL_ERROR.httpCode))
        .build(),
    )
    server.enqueue(MockResponse(body = "DEF"))
    server.enqueue(
      MockResponse(
        code = 301,
        headers = headersOf("Location", "/foo"),
      ),
    )
    server.enqueue(MockResponse(body = "ABC"))
    val latch = CountDownLatch(1)
    val responses: BlockingQueue<String?> = SynchronousQueue()
    val authenticator =
      okhttp3.Authenticator { route: Route?, response: Response? ->
        responses.offer(response!!.body.string())
        try {
          latch.await()
        } catch (e: InterruptedException) {
          throw AssertionError()
        }
        response.request
      }
    val blockingAuthClient =
      client
        .newBuilder()
        .authenticator(authenticator)
        .build()
    val callback: Callback =
      object : Callback {
        override fun onFailure(
          call: Call,
          e: IOException,
        ) {
          fail("")
        }

        override fun onResponse(
          call: Call,
          response: Response,
        ) {
          responses.offer(response.body.string())
        }
      }

    // Make the first request waiting until we get our auth challenge.
    val request = Request(server.url("/"))
    blockingAuthClient.newCall(request).enqueue(callback)
    val response1 = responses.take()
    assertThat(response1).isEqualTo("")
    assertThat(server.takeRequest().exchangeIndex).isEqualTo(0)

    // Now make the second request which will restrict the first HTTP/2 connection from creating new
    // streams.
    client.newCall(request).enqueue(callback)
    val response2 = responses.take()
    assertThat(response2).isEqualTo("DEF")
    assertThat(server.takeRequest().exchangeIndex).isEqualTo(1)
    assertThat(server.takeRequest().exchangeIndex).isEqualTo(0)

    // Let the first request proceed. It should discard the the held HTTP/2 connection and get a new
    // one.
    latch.countDown()
    val response3 = responses.take()
    assertThat(response3).isEqualTo("ABC")
    assertThat(server.takeRequest().exchangeIndex).isEqualTo(1)
    assertThat(server.takeRequest().exchangeIndex).isEqualTo(2)
  }

  @Test
  fun nonAsciiResponseHeader() {
    server.enqueue(
      MockResponse
        .Builder()
        .addHeaderLenient("Alpha", "α")
        .addHeaderLenient("β", "Beta")
        .build(),
    )
    val call = client.newCall(Request(server.url("/")))
    val response = call.execute()
    response.close()
    assertThat(response.header("Alpha")).isEqualTo("α")
    assertThat(response.header("β")).isEqualTo("Beta")
  }

  @Test
  fun serverSendsPushPromise_GET() {
    val pushPromise =
      PushPromise(
        "GET",
        "/foo/bar",
        headersOf("foo", "bar"),
        MockResponse(body = "bar"),
      )
    server.enqueue(
      MockResponse
        .Builder()
        .body("ABCDE")
        .addPush(pushPromise)
        .build(),
    )
    val call = client.newCall(Request(server.url("/foo")))
    val response = call.execute()
    assertThat(response.body.string()).isEqualTo("ABCDE")
    assertThat(response.code).isEqualTo(200)
    assertThat(response.message).isEqualTo("")
    val request = server.takeRequest()
    assertThat(request.requestLine).isEqualTo("GET /foo HTTP/2")
    assertThat(request.headers[":scheme"]).isEqualTo(scheme)
    assertThat(request.headers[":authority"]).isEqualTo(
      server.hostName + ":" + server.port,
    )
    val pushedRequest = server.takeRequest()
    assertThat(pushedRequest.requestLine).isEqualTo("GET /foo/bar HTTP/2")
    assertThat(pushedRequest.headers["foo"]).isEqualTo("bar")
  }

  @Test
  fun serverSendsPushPromise_HEAD() {
    val pushPromise =
      PushPromise(
        "HEAD",
        "/foo/bar",
        headersOf("foo", "bar"),
        MockResponse(code = 204),
      )
    server.enqueue(
      MockResponse
        .Builder()
        .body("ABCDE")
        .addPush(pushPromise)
        .build(),
    )
    val call = client.newCall(Request(server.url("/foo")))
    val response = call.execute()
    assertThat(response.body.string()).isEqualTo("ABCDE")
    assertThat(response.code).isEqualTo(200)
    assertThat(response.message).isEqualTo("")
    val request = server.takeRequest()
    assertThat(request.requestLine).isEqualTo("GET /foo HTTP/2")
    assertThat(request.headers[":scheme"]).isEqualTo(scheme)
    assertThat(request.headers[":authority"]).isEqualTo(
      server.hostName + ":" + server.port,
    )
    val pushedRequest = server.takeRequest()
    assertThat(pushedRequest.requestLine).isEqualTo("HEAD /foo/bar HTTP/2")
    assertThat(pushedRequest.headers["foo"]).isEqualTo("bar")
  }

  @Test
  fun noDataFramesSentWithNullRequestBody() {
    server.enqueue(MockResponse(body = "ABC"))
    val call =
      client.newCall(
        Request
          .Builder()
          .url(server.url("/"))
          .method("DELETE", null)
          .build(),
      )
    val response = call.execute()
    assertThat(response.body.string()).isEqualTo("ABC")
    assertThat(response.protocol).isEqualTo(protocol)
    val logs = testLogHandler.takeAll()
    assertThat(firstFrame(logs, "HEADERS")!!, "header logged")
      .contains("HEADERS       END_STREAM|END_HEADERS")
  }

  @Test
  fun emptyDataFrameSentWithEmptyBody() {
    server.enqueue(MockResponse(body = "ABC"))
    val call =
      client.newCall(
        Request
          .Builder()
          .url(server.url("/"))
          .method("DELETE", RequestBody.EMPTY)
          .build(),
      )
    val response = call.execute()
    assertThat(response.body.string()).isEqualTo("ABC")
    assertThat(response.protocol).isEqualTo(protocol)
    val logs = testLogHandler.takeAll()
    assertThat(firstFrame(logs, "HEADERS")!!, "header logged")
      .contains("HEADERS       END_HEADERS")
    // While MockWebServer waits to read the client's HEADERS frame before sending the response, it
    // doesn't wait to read the client's DATA frame and may send a DATA frame before the client
    // does. So we can't assume the client's empty DATA will be logged first.
    assertThat(countFrames(logs, "FINE: >> 0x00000003     0 DATA          END_STREAM"))
      .isEqualTo(1)
    assertThat(countFrames(logs, "FINE: >> 0x00000003     3 DATA          END_STREAM"))
      .isEqualTo(1)
  }

  @Test
  fun pingsTransmitted() {
    // Ping every 500 ms, starting at 500 ms.
    client =
      client
        .newBuilder()
        .pingInterval(Duration.ofMillis(500))
        .build()

    // Delay the response to give 1 ping enough time to be sent and replied to.
    server.enqueue(
      MockResponse
        .Builder()
        .bodyDelay(750, TimeUnit.MILLISECONDS)
        .body("ABC")
        .build(),
    )
    val call = client.newCall(Request(server.url("/")))
    val response = call.execute()
    assertThat(response.body.string()).isEqualTo("ABC")
    assertThat(response.protocol).isEqualTo(protocol)

    // Confirm a single ping was sent and received, and its reply was sent and received.
    val logs = testLogHandler.takeAll()
    assertThat(countFrames(logs, "FINE: >> 0x00000000     8 PING          "))
      .isEqualTo(1)
    assertThat(countFrames(logs, "FINE: << 0x00000000     8 PING          "))
      .isEqualTo(1)
    assertThat(countFrames(logs, "FINE: >> 0x00000000     8 PING          ACK"))
      .isEqualTo(1)
    assertThat(countFrames(logs, "FINE: << 0x00000000     8 PING          ACK"))
      .isEqualTo(1)
  }

  @Test
  fun missingPongsFailsConnection() {
    if (protocol === Protocol.HTTP_2) {
      // https://github.com/lysine-dev/okhttp/issues/5221
      platform.expectFailureOnJdkVersion(12)
    }

    // Ping every 500 ms, starting at 500 ms.
    client =
      client
        .newBuilder()
        .readTimeout(Duration.ofSeconds(10)) // Confirm we fail before the read timeout.
        .pingInterval(Duration.ofMillis(500))
        .build()

    // Set up the server to ignore the socket. It won't respond to pings!
    server.enqueue(MockResponse.Builder().onRequestStart(Stall).build())

    // Make a call. It'll fail as soon as our pings detect a problem.
    val call = client.newCall(Request(server.url("/")))
    val executeAtNanos = System.nanoTime()
    assertFailsWith<StreamResetException> {
      call.execute()
    }.also { expected ->
      assertThat(expected.message).isEqualTo(
        "stream was reset: PROTOCOL_ERROR",
      )
    }
    val elapsedUntilFailure = System.nanoTime() - executeAtNanos
    assertThat(TimeUnit.NANOSECONDS.toMillis(elapsedUntilFailure).toDouble())
      .isCloseTo(1000.0, 250.0)

    // Confirm a single ping was sent but not acknowledged.
    val logs = testLogHandler.takeAll()
    assertThat(countFrames(logs, "FINE: >> 0x00000000     8 PING          "))
      .isEqualTo(1)
    assertThat(countFrames(logs, "FINE: << 0x00000000     8 PING          ACK"))
      .isEqualTo(0)
  }

  @Test
  fun streamTimeoutDegradesConnectionAfterNoPong() {
    assumeNotWindows()
    client =
      client
        .newBuilder()
        .readTimeout(Duration.ofMillis(500))
        .build()

    // Stalling the socket will cause TWO requests to time out!
    server.enqueue(MockResponse.Builder().onRequestStart(Stall).build())

    // The 3rd request should be sent to a fresh connection.
    server.enqueue(
      MockResponse(body = "fresh connection"),
    )

    // The first call times out.
    val call1 = client.newCall(Request(server.url("/")))
    assertFailsWith<IOException> {
      call1.execute()
    }.also { expected ->
      when (expected) {
        is SocketTimeoutException, is SSLException -> {}

        else -> {
          throw expected
        }
      }
    }

    // The second call times out because it uses the same bad connection.
    val call2 = client.newCall(Request(server.url("/")))
    assertFailsWith<SocketTimeoutException> {
      call2.execute()
    }

    // But after the degraded pong timeout, that connection is abandoned.
    Thread.sleep(TimeUnit.NANOSECONDS.toMillis(Http2Connection.DEGRADED_PONG_TIMEOUT_NS.toLong()))
    val call3 = client.newCall(Request(server.url("/")))
    call3.execute().use { response ->
      assertThat(
        response.body.string(),
      ).isEqualTo("fresh connection")
    }
  }

  @Test
  fun oneStreamTimeoutDoesNotBreakConnection() {
    client =
      client
        .newBuilder()
        .readTimeout(Duration.ofMillis(500))
        .build()
    server.enqueue(
      MockResponse
        .Builder()
        .bodyDelay(1000, TimeUnit.MILLISECONDS)
        .body("a")
        .build(),
    )
    server.enqueue(MockResponse(body = "b"))
    server.enqueue(MockResponse(body = "c"))

    // The first call times out.
    val call1 = client.newCall(Request(server.url("/")))
    assertFailsWith<SocketTimeoutException> {
      call1.execute().use { response ->
        response.body.string()
      }
    }

    // The second call succeeds.
    val call2 = client.newCall(Request(server.url("/")))
    call2.execute().use { response ->
      assertThat(
        response.body.string(),
      ).isEqualTo("b")
    }

    // Calls succeed after the degraded pong timeout because the degraded pong was received.
    Thread.sleep(TimeUnit.NANOSECONDS.toMillis(Http2Connection.DEGRADED_PONG_TIMEOUT_NS.toLong()))
    val call3 = client.newCall(Request(server.url("/")))
    call3.execute().use { response ->
      assertThat(
        response.body.string(),
      ).isEqualTo("c")
    }

    // All calls share a connection.
    assertThat(server.takeRequest().exchangeIndex).isEqualTo(0)
    assertThat(server.takeRequest().exchangeIndex).isEqualTo(1)
    assertThat(server.takeRequest().exchangeIndex).isEqualTo(2)
  }

  private fun firstFrame(
    logs: List<String>,
    type: String,
  ): String? {
    for (log in logs) {
      if (type in log) {
        return log
      }
    }
    return null
  }

  private fun countFrames(
    logs: List<String>,
    message: String,
  ): Int {
    var result = 0
    for (log in logs) {
      if (log == message) {
        result++
      }
    }
    return result
  }

  /**
   * Push a setting that permits up to 2 concurrent streams, then make 3 concurrent requests and
   * confirm that the third concurrent request prepared a new connection.
   */
  @Test
  fun settingsLimitsMaxConcurrentStreams() {
    val settings = Settings()
    settings[Settings.MAX_CONCURRENT_STREAMS] = 2

    // Read & write a full request to confirm settings are accepted.
    server.enqueue(
      MockResponse
        .Builder()
        .settings(settings)
        .build(),
    )
    val call = client.newCall(Request(server.url("/")))
    val response = call.execute()
    assertThat(response.body.string()).isEqualTo("")
    server.enqueue(
      MockResponse(body = "ABC"),
    )
    server.enqueue(
      MockResponse(body = "DEF"),
    )
    server.enqueue(
      MockResponse(body = "GHI"),
    )
    val call1 = client.newCall(Request(server.url("/")))
    val response1 = call1.execute()
    val call2 = client.newCall(Request(server.url("/")))
    val response2 = call2.execute()
    val call3 = client.newCall(Request(server.url("/")))
    val response3 = call3.execute()
    assertThat(response1.body.string()).isEqualTo("ABC")
    assertThat(response2.body.string()).isEqualTo("DEF")
    assertThat(response3.body.string()).isEqualTo("GHI")
    // Settings connection.
    assertThat(server.takeRequest().exchangeIndex).isEqualTo(0)
    // Reuse settings connection.
    assertThat(server.takeRequest().exchangeIndex).isEqualTo(1)
    // Reuse settings connection.
    assertThat(server.takeRequest().exchangeIndex).isEqualTo(2)
    // New connection!
    assertThat(server.takeRequest().exchangeIndex).isEqualTo(0)
  }

  @Test
  fun connectionNotReusedAfterShutdown() {
    server.enqueue(
      MockResponse
        .Builder()
        .body("ABC")
        .onResponseEnd(ShutdownConnection)
        .build(),
    )
    server.enqueue(MockResponse(body = "DEF"))
    // Enqueue an additional response that show if we burnt a good prior response.
    server.enqueue(
      MockResponse(body = "XXX"),
    )
    val connections: MutableList<RealConnection?> = ArrayList()
    val localClient =
      client
        .newBuilder()
        .eventListener(
          object : EventListener() {
            override fun connectionAcquired(
              call: Call,
              connection: Connection,
            ) {
              connections.add(connection as RealConnection)
            }
          },
        ).build()
    val call1 = localClient.newCall(Request(server.url("/")))
    val response1 = call1.execute()
    assertThat(response1.body.string()).isEqualTo("ABC")

    // Add delays for DISCONNECT_AT_END to propogate
    waitForConnectionShutdown(connections[0])
    val call2 = localClient.newCall(Request(server.url("/")))
    val response2 = call2.execute()
    assertThat(response2.body.string()).isEqualTo("DEF")
    assertThat(server.takeRequest().exchangeIndex).isEqualTo(0)
    assertThat(server.takeRequest().exchangeIndex).isEqualTo(0)
  }

  @Throws(InterruptedException::class, TimeoutException::class)
  private fun waitForConnectionShutdown(connection: RealConnection?) {
    if (connection!!.isHealthy(false)) {
      Thread.sleep(100L)
    }
    if (connection.isHealthy(false)) {
      Thread.sleep(2000L)
    }
    if (connection.isHealthy(false)) {
      throw TimeoutException("connection didn't shutdown within timeout")
    }
  }

  /**
   * This simulates a race condition where we receive a healthy HTTP/2 connection and just prior to
   * writing our request, we get a GOAWAY frame from the server.
   */
  @Test
  fun connectionShutdownAfterHealthCheck() {
    server.enqueue(
      MockResponse
        .Builder()
        .body("ABC")
        .onResponseEnd(ShutdownConnection)
        .build(),
    )
    server.enqueue(MockResponse(body = "DEF"))
    val client2 =
      client
        .newBuilder()
        .addNetworkInterceptor(
          object : Interceptor {
            var executedCall = false

            override fun intercept(chain: Interceptor.Chain): Response {
              if (!executedCall) {
                // At this point, we have a healthy HTTP/2 connection. This call will trigger the
                // server to send a GOAWAY frame, leaving the connection in a shutdown state.
                executedCall = true
                val call =
                  client.newCall(
                    Request
                      .Builder()
                      .url(server.url("/"))
                      .build(),
                  )
                val response = call.execute()
                assertThat(response.body.string()).isEqualTo("ABC")
                // Wait until the GOAWAY has been processed.
                val connection = chain.connection() as RealConnection?
                while (connection!!.isHealthy(false));
              }
              return chain.proceed(chain.request())
            }
          },
        ).build()
    val call = client2.newCall(Request(server.url("/")))
    val response = call.execute()
    assertThat(response.body.string()).isEqualTo("DEF")
    assertThat(server.takeRequest().exchangeIndex).isEqualTo(0)
    assertThat(server.takeRequest().exchangeIndex).isEqualTo(0)
  }

  @Test
  fun responseHeadersAfterGoaway() {
    server.enqueue(
      MockResponse
        .Builder()
        .headersDelay(1, TimeUnit.SECONDS)
        .body("ABC")
        .build(),
    )
    server.enqueue(
      MockResponse
        .Builder()
        .body("DEF")
        .onResponseEnd(ShutdownConnection)
        .build(),
    )
    val latch = CountDownLatch(2)
    val errors = ArrayList<IOException?>()
    val bodies: BlockingQueue<String?> = LinkedBlockingQueue()
    val callback: Callback =
      object : Callback {
        override fun onResponse(
          call: Call,
          response: Response,
        ) {
          bodies.add(response.body.string())
          latch.countDown()
        }

        override fun onFailure(
          call: Call,
          e: IOException,
        ) {
          errors.add(e)
          latch.countDown()
        }
      }
    client.newCall(Request.Builder().url(server.url("/")).build()).enqueue(
      callback,
    )
    client.newCall(Request.Builder().url(server.url("/")).build()).enqueue(
      callback,
    )
    latch.await()
    assertThat(bodies.remove()).isEqualTo("DEF")
    if (errors.isEmpty()) {
      assertThat(bodies.remove()).isEqualTo("ABC")
      assertThat(server.requestCount).isEqualTo(2)
    } else {
      // https://github.com/lysine-dev/okhttp/issues/4836
      // As documented in SocketEffect, this is known to be flaky.
      val error = errors[0]
      if (error !is StreamResetException) {
        throw error!!
      }
    }
  }

  /**
   * We don't know if the connection will support HTTP/2 until after we've connected. When multiple
   * connections are requested concurrently OkHttp will pessimistically connect multiple times, then
   * close any unnecessary connections. This test confirms that behavior works as intended.
   *
   * This test uses proxy tunnels to get a hook while a connection is being established.
   */
  @Test
  fun concurrentHttp2ConnectionsDeduplicated() {
    assumeTrue(protocol === Protocol.HTTP_2)
    server.useHttps(handshakeCertificates.sslSocketFactory())
    val queueDispatcher = QueueDispatcher()
    queueDispatcher.enqueue(
      MockResponse
        .Builder()
        .inTunnel()
        .build(),
    )
    queueDispatcher.enqueue(
      MockResponse
        .Builder()
        .inTunnel()
        .build(),
    )
    queueDispatcher.enqueue(MockResponse(body = "call2 response"))
    queueDispatcher.enqueue(MockResponse(body = "call1 response"))

    // We use a re-entrant dispatcher to initiate one HTTPS connection while the other is in flight.
    server.dispatcher =
      object : Dispatcher() {
        var requestCount = 0

        override fun dispatch(request: RecordedRequest): MockResponse {
          val result = queueDispatcher.dispatch(request)
          requestCount++
          if (requestCount == 1) {
            // Before handling call1's CONNECT we do all of call2. This part re-entrant!
            try {
              val call2 =
                client.newCall(
                  Request
                    .Builder()
                    .url("https://android.com/call2")
                    .build(),
                )
              val response2 = call2.execute()
              assertThat(response2.body.string()).isEqualTo("call2 response")
            } catch (e: IOException) {
              throw RuntimeException(e)
            }
          }
          return result
        }

        override fun peek(): MockResponse = queueDispatcher.peek()

        override fun close() {
          queueDispatcher.close()
        }
      }
    client =
      client
        .newBuilder()
        .proxy(server.proxyAddress)
        .build()
    val call1 = client.newCall(Request("https://android.com/call1".toHttpUrl()))
    val response2 = call1.execute()
    assertThat(response2.body.string()).isEqualTo("call1 response")
    val call1Connect = server.takeRequest()
    assertThat(call1Connect.method).isEqualTo("CONNECT")
    assertThat(call1Connect.exchangeIndex).isEqualTo(0)
    val call2Connect = server.takeRequest()
    assertThat(call2Connect.method).isEqualTo("CONNECT")
    assertThat(call2Connect.exchangeIndex).isEqualTo(0)
    val call2Get = server.takeRequest()
    assertThat(call2Get.method).isEqualTo("GET")
    assertThat(call2Get.url.encodedPath).isEqualTo("/call2")
    assertThat(call2Get.exchangeIndex).isEqualTo(0)
    val call1Get = server.takeRequest()
    assertThat(call1Get.method).isEqualTo("GET")
    assertThat(call1Get.url.encodedPath).isEqualTo("/call1")
    assertThat(call1Get.exchangeIndex).isEqualTo(1)
    assertThat(client.connectionPool.connectionCount()).isEqualTo(1)
  }

  /** https://github.com/lysine-dev/okhttp/issues/3103  */
  @Test
  fun domainFronting() {
    client =
      client
        .newBuilder()
        .addNetworkInterceptor(
          Interceptor { chain: Interceptor.Chain? ->
            val request =
              chain!!
                .request()
                .newBuilder()
                .header("Host", "privateobject.com")
                .build()
            chain.proceed(request)
          },
        ).build()
    server.enqueue(MockResponse())
    val call = client.newCall(Request(server.url("/")))
    val response = call.execute()
    assertThat(response.body.string()).isEqualTo("")
    val recordedRequest = server.takeRequest()
    assertThat(recordedRequest.headers[":authority"]).isEqualTo("privateobject.com")
  }

  private fun gzip(bytes: String): Buffer {
    val bytesOut = Buffer()
    val sink = GzipSink(bytesOut).buffer()
    sink.writeUtf8(bytes)
    sink.close()
    return bytesOut
  }

  internal inner class AsyncRequest(
    val path: String,
    val countDownLatch: CountDownLatch,
  ) : Runnable {
    override fun run() {
      try {
        val call =
          client.newCall(
            Request
              .Builder()
              .url(server.url(path))
              .build(),
          )
        val response = call.execute()
        assertThat(response.body.string()).isEqualTo("A")
        countDownLatch.countDown()
      } catch (e: Exception) {
        throw RuntimeException(e)
      }
    }
  }

  /** https://github.com/lysine-dev/okhttp/issues/4875  */
  @Test
  fun shutdownAfterLateCoalescing() {
    val latch = CountDownLatch(2)
    val callback: Callback =
      object : Callback {
        override fun onResponse(
          call: Call,
          response: Response,
        ) {
          fail("")
        }

        override fun onFailure(
          call: Call,
          e: IOException,
        ) {
          latch.countDown()
        }
      }
    client =
      client
        .newBuilder()
        .eventListenerFactory(
          clientTestRule.wrap(
            object : EventListener() {
              var callCount = 0

              override fun connectionAcquired(
                call: Call,
                connection: Connection,
              ) {
                try {
                  if (callCount++ == 1) {
                    server.close()
                  }
                } catch (e: IOException) {
                  fail("")
                }
              }
            },
          ),
        ).build()
    client.newCall(Request.Builder().url(server.url("")).build()).enqueue(
      callback,
    )
    client.newCall(Request.Builder().url(server.url("")).build()).enqueue(
      callback,
    )
    latch.await()
  }

  @Test
  fun cancelWhileWritingRequestBodySendsCancelToServer() {
    server.enqueue(MockResponse())
    val callReference = AtomicReference<Call?>()
    val call =
      client.newCall(
        Request(
          url = server.url("/"),
          body =
            object : RequestBody() {
              override fun contentType() = "text/plain; charset=utf-8".toMediaType()

              override fun writeTo(sink: BufferedSink) {
                callReference.get()!!.cancel()
              }
            },
        ),
      )
    callReference.set(call)
    assertFailsWith<IOException> {
      call.execute()
    }.also { expected ->
      assertThat(call.isCanceled()).isTrue()
    }
    val recordedRequest = server.takeRequest()
    assertThat(recordedRequest.failure!!).hasMessage("stream was reset: CANCEL")
  }

  @Test
  fun http2WithProxy() {
    server.enqueue(
      MockResponse
        .Builder()
        .inTunnel()
        .build(),
    )
    server.enqueue(MockResponse(body = "ABCDE"))
    val client =
      client
        .newBuilder()
        .proxy(server.proxyAddress)
        .build()

    val url = server.url("/").resolve("//android.com/foo")!!
    val port =
      when (url.scheme) {
        "https" -> 443
        "http" -> 80
        else -> error("unexpected scheme")
      }

    val call = client.newCall(Request(url))
    val response = call.execute()
    assertThat(response.body.string()).isEqualTo("ABCDE")
    assertThat(response.code).isEqualTo(200)
    assertThat(response.message).isEqualTo("")
    assertThat(response.protocol).isEqualTo(protocol)

    val tunnelRequest = server.takeRequest()
    assertThat(tunnelRequest.requestLine).isEqualTo("CONNECT android.com:$port HTTP/1.1")

    val request = server.takeRequest()
    assertThat(request.requestLine).isEqualTo("GET /foo HTTP/2")
    assertThat(request.headers[":scheme"]).isEqualTo(scheme)
    assertThat(request.headers[":authority"]).isEqualTo("android.com")
  }

  /** Respond to a proxy authorization challenge.  */
  @Test
  fun proxyAuthenticateOnConnect() {
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
    server.enqueue(MockResponse(body = "response body"))
    val client =
      client
        .newBuilder()
        .proxy(server.proxyAddress)
        .proxyAuthenticator(RecordingOkAuthenticator("password", "Basic"))
        .build()

    val url = server.url("/").resolve("//android.com/foo")!!
    val port =
      when (url.scheme) {
        "https" -> 443
        "http" -> 80
        else -> error("unexpected scheme")
      }

    val request = Request(url)
    val response = client.newCall(request).execute()
    assertThat(response.body.string()).isEqualTo("response body")

    val connect1 = server.takeRequest()
    assertThat(connect1.requestLine).isEqualTo("CONNECT android.com:$port HTTP/1.1")
    assertThat(connect1.headers["Proxy-Authorization"]).isNull()

    val connect2 = server.takeRequest()
    assertThat(connect2.requestLine).isEqualTo("CONNECT android.com:$port HTTP/1.1")
    assertThat(connect2.headers["Proxy-Authorization"]).isEqualTo("password")

    val get = server.takeRequest()
    assertThat(get.requestLine).isEqualTo("GET /foo HTTP/2")
    assertThat(get.headers["Proxy-Authorization"]).isNull()
  }
}
