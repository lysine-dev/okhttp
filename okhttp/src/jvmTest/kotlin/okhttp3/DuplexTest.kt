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
package okhttp3

import assertk.all
import assertk.assertThat
import assertk.assertions.contains
import assertk.assertions.containsExactly
import assertk.assertions.isEqualTo
import assertk.assertions.isNotNull
import assertk.assertions.isNull
import assertk.assertions.isTrue
import assertk.assertions.prop
import java.io.IOException
import java.net.HttpURLConnection
import java.net.ProtocolException
import java.util.concurrent.BlockingQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.test.assertFailsWith
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.junit5.StartStop
import okhttp3.CallEvent.CallEnd
import okhttp3.CallEvent.CallStart
import okhttp3.CallEvent.ConnectEnd
import okhttp3.CallEvent.ConnectStart
import okhttp3.CallEvent.ConnectionAcquired
import okhttp3.CallEvent.ConnectionReleased
import okhttp3.CallEvent.DnsEnd
import okhttp3.CallEvent.DnsStart
import okhttp3.CallEvent.FollowUpDecision
import okhttp3.CallEvent.ProxySelectEnd
import okhttp3.CallEvent.ProxySelectStart
import okhttp3.CallEvent.RequestBodyEnd
import okhttp3.CallEvent.RequestBodyStart
import okhttp3.CallEvent.RequestFailed
import okhttp3.CallEvent.RequestHeadersEnd
import okhttp3.CallEvent.RequestHeadersStart
import okhttp3.CallEvent.ResponseBodyEnd
import okhttp3.CallEvent.ResponseBodyStart
import okhttp3.CallEvent.ResponseHeadersEnd
import okhttp3.CallEvent.ResponseHeadersStart
import okhttp3.CallEvent.SecureConnectEnd
import okhttp3.CallEvent.SecureConnectStart
import okhttp3.Credentials.basic
import okhttp3.Headers.Companion.headersOf
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.TestUtil.assumeNotWindows
import okhttp3.internal.RecordingOkAuthenticator
import okhttp3.internal.duplex.AsyncRequestBody
import okhttp3.internal.duplex.MockSocketHandler
import okhttp3.testing.PlatformRule
import okio.BufferedSink
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Disabled
import org.junit.jupiter.api.RepeatedTest
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.api.extension.RegisterExtension

@Timeout(30)
@Tag("Slowish")
class DuplexTest {
  @RegisterExtension
  val platform = PlatformRule()

  @RegisterExtension
  var clientTestRule = OkHttpClientTestRule()

  @StartStop
  private val server = MockWebServer()

  private var eventRecorder = EventRecorder()
  private val handshakeCertificates = platform.localhostHandshakeCertificates()
  private var client =
    clientTestRule
      .newClientBuilder()
      .eventListenerFactory(clientTestRule.wrap(eventRecorder))
      .build()
  private val executorService = Executors.newScheduledThreadPool(1)

  @BeforeEach
  fun setUp() {
    platform.assumeNotOpenJSSE()
    platform.assumeHttp2Support()
  }

  @AfterEach
  fun tearDown() {
    executorService.shutdown()
  }

  @Test
  @Throws(IOException::class)
  fun http1DoesntSupportDuplex() {
    val call =
      client.newCall(
        Request
          .Builder()
          .url(server.url("/"))
          .post(AsyncRequestBody())
          .build(),
      )
    assertFailsWith<ProtocolException> {
      call.execute()
    }
  }

  @Test
  fun trueDuplexClientWritesFirst() {
    enableProtocol(Protocol.HTTP_2)
    val body =
      MockSocketHandler()
        .receiveRequest("request A\n")
        .sendResponse("response B\n")
        .receiveRequest("request C\n")
        .sendResponse("response D\n")
        .receiveRequest("request E\n")
        .sendResponse("response F\n")
        .exhaustRequest()
        .exhaustResponse()
    server.enqueue(
      MockResponse
        .Builder()
        .clearHeaders()
        .socketHandler(body)
        .build(),
    )
    val call =
      client.newCall(
        Request
          .Builder()
          .url(server.url("/"))
          .post(AsyncRequestBody())
          .build(),
      )
    call.execute().use { response ->
      val requestBody = (call.request().body as AsyncRequestBody?)!!.takeSink()
      requestBody.writeUtf8("request A\n")
      requestBody.flush()
      val responseBody = response.body.source()
      assertThat(responseBody.readUtf8Line())
        .isEqualTo("response B")
      requestBody.writeUtf8("request C\n")
      requestBody.flush()
      assertThat(responseBody.readUtf8Line())
        .isEqualTo("response D")
      requestBody.writeUtf8("request E\n")
      requestBody.flush()
      assertThat(responseBody.readUtf8Line())
        .isEqualTo("response F")
      requestBody.close()
      assertThat(responseBody.readUtf8Line()).isNull()
    }
    body.awaitSuccess()
  }

  @Test
  fun trueDuplexServerWritesFirst() {
    enableProtocol(Protocol.HTTP_2)
    val body =
      MockSocketHandler()
        .sendResponse("response A\n")
        .receiveRequest("request B\n")
        .sendResponse("response C\n")
        .receiveRequest("request D\n")
        .sendResponse("response E\n")
        .receiveRequest("request F\n")
        .exhaustResponse()
        .exhaustRequest()
    server.enqueue(
      MockResponse
        .Builder()
        .clearHeaders()
        .socketHandler(body)
        .build(),
    )
    val call =
      client.newCall(
        Request
          .Builder()
          .url(server.url("/"))
          .post(AsyncRequestBody())
          .build(),
      )
    call.execute().use { response ->
      val requestBody = (call.request().body as AsyncRequestBody?)!!.takeSink()
      val responseBody = response.body.source()
      assertThat(responseBody.readUtf8Line())
        .isEqualTo("response A")
      requestBody.writeUtf8("request B\n")
      requestBody.flush()
      assertThat(responseBody.readUtf8Line())
        .isEqualTo("response C")
      requestBody.writeUtf8("request D\n")
      requestBody.flush()
      assertThat(responseBody.readUtf8Line())
        .isEqualTo("response E")
      requestBody.writeUtf8("request F\n")
      requestBody.flush()
      assertThat(responseBody.readUtf8Line()).isNull()
      requestBody.close()
    }
    body.awaitSuccess()
  }

  @Test
  fun clientReadsHeadersDataTrailers() {
    enableProtocol(Protocol.HTTP_2)
    val body =
      MockSocketHandler()
        .sendResponse("ok")
        .exhaustResponse()
    server.enqueue(
      MockResponse
        .Builder()
        .clearHeaders()
        .addHeader("h1", "v1")
        .addHeader("h2", "v2")
        .trailers(headersOf("trailers", "boom"))
        .socketHandler(body)
        .build(),
    )
    val call =
      client.newCall(
        Request
          .Builder()
          .url(server.url("/"))
          .build(),
      )
    call.execute().use { response ->
      assertThat(response.headers)
        .isEqualTo(headersOf("h1", "v1", "h2", "v2"))
      val responseBody = response.body.source()
      assertThat(responseBody.readUtf8(2)).isEqualTo("ok")
      assertThat(responseBody.exhausted()).isTrue()
      assertThat(response.trailers()).isEqualTo(headersOf("trailers", "boom"))
    }
    body.awaitSuccess()
  }

  @Test
  fun serverReadsHeadersData() {
    assumeNotWindows()
    enableProtocol(Protocol.HTTP_2)
    val body =
      MockSocketHandler()
        .exhaustResponse()
        .receiveRequest("hey\n")
        .receiveRequest("whats going on\n")
        .exhaustRequest()
    server.enqueue(
      MockResponse
        .Builder()
        .clearHeaders()
        .addHeader("h1", "v1")
        .addHeader("h2", "v2")
        .socketHandler(body)
        .build(),
    )
    val request =
      Request
        .Builder()
        .url(server.url("/"))
        .method("POST", AsyncRequestBody())
        .build()
    val call = client.newCall(request)
    call.execute().use { response ->
      val sink = (request.body as AsyncRequestBody?)!!.takeSink()
      sink.writeUtf8("hey\n")
      sink.writeUtf8("whats going on\n")
      sink.close()
    }
    body.awaitSuccess()
  }

  @Test
  fun requestBodyEndsAfterResponseBody() {
    enableProtocol(Protocol.HTTP_2)
    val body =
      MockSocketHandler()
        .exhaustResponse()
        .receiveRequest("request A\n")
        .exhaustRequest()
    server.enqueue(
      MockResponse
        .Builder()
        .clearHeaders()
        .socketHandler(body)
        .build(),
    )
    val call =
      client.newCall(
        Request
          .Builder()
          .url(server.url("/"))
          .post(AsyncRequestBody())
          .build(),
      )
    call.execute().use { response ->
      val responseBody = response.body.source()
      assertTrue(responseBody.exhausted())
      val requestBody = (call.request().body as AsyncRequestBody?)!!.takeSink()
      requestBody.writeUtf8("request A\n")
      requestBody.close()
    }
    body.awaitSuccess()
    assertThat(eventRecorder.recordedEventTypes()).containsExactly(
      CallStart::class,
      ProxySelectStart::class,
      ProxySelectEnd::class,
      DnsStart::class,
      DnsEnd::class,
      ConnectStart::class,
      SecureConnectStart::class,
      SecureConnectEnd::class,
      ConnectEnd::class,
      ConnectionAcquired::class,
      RequestHeadersStart::class,
      RequestHeadersEnd::class,
      RequestBodyStart::class,
      ResponseHeadersStart::class,
      ResponseHeadersEnd::class,
      FollowUpDecision::class,
      ResponseBodyStart::class,
      ResponseBodyEnd::class,
      RequestBodyEnd::class,
      ConnectionReleased::class,
      CallEnd::class,
    )
  }

  @Test
  fun duplexWith100Continue() {
    enableProtocol(Protocol.HTTP_2)
    val body =
      MockSocketHandler()
        .receiveRequest("request body\n")
        .sendResponse("response body\n")
        .exhaustRequest()
    server.enqueue(
      MockResponse
        .Builder()
        .clearHeaders()
        .add100Continue()
        .socketHandler(body)
        .build(),
    )
    val call =
      client.newCall(
        Request
          .Builder()
          .url(server.url("/"))
          .header("Expect", "100-continue")
          .post(AsyncRequestBody())
          .build(),
      )
    call.execute().use { response ->
      val requestBody = (call.request().body as AsyncRequestBody?)!!.takeSink()
      requestBody.writeUtf8("request body\n")
      requestBody.flush()
      val responseBody = response.body.source()
      assertThat(responseBody.readUtf8Line())
        .isEqualTo("response body")
      requestBody.close()
      assertThat(responseBody.readUtf8Line()).isNull()
    }
    body.awaitSuccess()
  }

  /**
   * Duplex calls that have follow-ups are weird. By the time we know there's a follow-up we've
   * already split off another thread to stream the request body. Because we permit at most one
   * exchange at a time we break the request stream out from under that writer.
   */
  @Test
  fun duplexWithRedirect() {
    enableProtocol(Protocol.HTTP_2)
    val duplexResponseSent = CountDownLatch(1)
    val requestHeadersEndListener =
      object : EventListener() {
        override fun requestHeadersEnd(
          call: Call,
          request: Request,
        ) {
          // Wait for the server to send the duplex response before acting on the 301 response
          // and resetting the stream.
          duplexResponseSent.await()
        }
      }
    client =
      client
        .newBuilder()
        .eventListener(eventRecorder.eventListener + requestHeadersEndListener)
        .build()
    val body =
      MockSocketHandler()
        .sendResponse("/a has moved!\n", duplexResponseSent)
        .requestIOException()
        .exhaustResponse()
    server.enqueue(
      MockResponse
        .Builder()
        .clearHeaders()
        .code(HttpURLConnection.HTTP_MOVED_PERM)
        .addHeader("Location: /b")
        .socketHandler(body)
        .build(),
    )
    server.enqueue(
      MockResponse
        .Builder()
        .body("this is /b")
        .build(),
    )
    val call =
      client.newCall(
        Request
          .Builder()
          .url(server.url("/"))
          .post(AsyncRequestBody())
          .build(),
      )
    call.execute().use { response ->
      val responseBody = response.body.source()
      assertThat(responseBody.readUtf8Line())
        .isEqualTo("this is /b")
    }
    val requestBody = (call.request().body as AsyncRequestBody?)!!.takeSink()
    assertFailsWith<IOException> {
      requestBody.writeUtf8("request body\n")
      requestBody.flush()
    }.also { expected ->
      assertThat(expected.message)
        .isEqualTo("stream was reset: CANCEL")
    }
    body.awaitSuccess()
    assertThat(eventRecorder.recordedEventTypes()).containsExactly(
      CallStart::class,
      ProxySelectStart::class,
      ProxySelectEnd::class,
      DnsStart::class,
      DnsEnd::class,
      ConnectStart::class,
      SecureConnectStart::class,
      SecureConnectEnd::class,
      ConnectEnd::class,
      ConnectionAcquired::class,
      RequestHeadersStart::class,
      RequestHeadersEnd::class,
      RequestBodyStart::class,
      ResponseHeadersStart::class,
      ResponseHeadersEnd::class,
      ResponseBodyStart::class,
      ResponseBodyEnd::class,
      FollowUpDecision::class,
      RequestHeadersStart::class,
      RequestHeadersEnd::class,
      ResponseHeadersStart::class,
      ResponseHeadersEnd::class,
      FollowUpDecision::class,
      ResponseBodyStart::class,
      ResponseBodyEnd::class,
      ConnectionReleased::class,
      CallEnd::class,
      RequestFailed::class,
    )
    assertThat(eventRecorder.findEvent<FollowUpDecision>()).all {
      prop(FollowUpDecision::nextRequest).isNotNull()
    }
  }

  /**
   * Auth requires follow-ups. Unlike redirects, the auth follow-up also has a request body. This
   * test makes a single call with two duplex requests!
   */
  @RepeatedTest(1000)
  fun duplexWithAuthChallenge() {
    enableProtocol(Protocol.HTTP_2)
    val credential = basic("jesse", "secret")
    client =
      client
        .newBuilder()
        .authenticator(RecordingOkAuthenticator(credential, null))
        .build()
    val body1 =
      MockSocketHandler()
        .sendResponse("please authenticate!\n")
        .requestIOException()
        .exhaustResponse()
    server.enqueue(
      MockResponse
        .Builder()
        .clearHeaders()
        .code(HttpURLConnection.HTTP_UNAUTHORIZED)
        .socketHandler(body1)
        .build(),
    )
    val body =
      MockSocketHandler()
        .sendResponse("response body\n")
        .exhaustResponse()
        .receiveRequest("request body\n")
        .exhaustRequest()
    server.enqueue(
      MockResponse
        .Builder()
        .clearHeaders()
        .socketHandler(body)
        .build(),
    )
    val call =
      client.newCall(
        Request
          .Builder()
          .url(server.url("/"))
          .post(AsyncRequestBody())
          .build(),
      )
    val response2 = call.execute()

    // First duplex request is detached with violence.
    val requestBody1 = (call.request().body as AsyncRequestBody?)!!.takeSink()
    assertFailsWith<IOException> {
      requestBody1.writeUtf8("not authenticated\n")
      requestBody1.flush()
    }.also { expected ->
      assertThat(expected.message)
        .isEqualTo("stream was reset: CANCEL")
    }
    body1.awaitSuccess()

    // Second duplex request proceeds normally.
    val requestBody2 = (call.request().body as AsyncRequestBody?)!!.takeSink()
    requestBody2.writeUtf8("request body\n")
    requestBody2.close()
    val responseBody2 = response2.body.source()
    assertThat(responseBody2.readUtf8Line())
      .isEqualTo("response body")
    assertTrue(responseBody2.exhausted())
    body.awaitSuccess()

    // No more requests attempted!
    (call.request().body as AsyncRequestBody?)!!.assertNoMoreSinks()
  }

  @Test
  fun fullCallTimeoutAppliesToSetup() {
    enableProtocol(Protocol.HTTP_2)
    server.enqueue(
      MockResponse
        .Builder()
        .headersDelay(500, TimeUnit.MILLISECONDS)
        .build(),
    )
    val request =
      Request
        .Builder()
        .url(server.url("/"))
        .post(AsyncRequestBody())
        .build()
    val call = client.newCall(request)
    call.timeout().timeout(250, TimeUnit.MILLISECONDS)
    assertFailsWith<IOException> {
      call.execute()
    }.also { expected ->
      assertThat(expected.message).isEqualTo("timeout")
      assertTrue(call.isCanceled())
    }
  }

  @Test
  fun fullCallTimeoutDoesNotApplyOnceConnected() {
    enableProtocol(Protocol.HTTP_2)
    val body =
      MockSocketHandler()
        .sendResponse("response A\n")
        .sleep(750, TimeUnit.MILLISECONDS)
        .sendResponse("response B\n")
        .receiveRequest("request C\n")
        .exhaustResponse()
        .exhaustRequest()
    server.enqueue(
      MockResponse
        .Builder()
        .clearHeaders()
        .socketHandler(body)
        .build(),
    )
    val request =
      Request
        .Builder()
        .url(server.url("/"))
        .post(AsyncRequestBody())
        .build()
    val call = client.newCall(request)
    call
      .timeout()
      .timeout(500, TimeUnit.MILLISECONDS) // Long enough for the first TLS handshake.
    call.execute().use { response ->
      val requestBody = (call.request().body as AsyncRequestBody?)!!.takeSink()
      val responseBody = response.body.source()
      assertThat(responseBody.readUtf8Line())
        .isEqualTo("response A")
      assertThat(responseBody.readUtf8Line())
        .isEqualTo("response B")
      requestBody.writeUtf8("request C\n")
      requestBody.close()
      assertThat(responseBody.readUtf8Line()).isNull()
    }
    body.awaitSuccess()
  }

  @Test
  fun duplexWithRewriteInterceptors() {
    enableProtocol(Protocol.HTTP_2)
    val body =
      MockSocketHandler()
        .receiveRequest("REQUEST A\n")
        .sendResponse("response B\n")
        .exhaustRequest()
        .exhaustResponse()
    server.enqueue(
      MockResponse
        .Builder()
        .clearHeaders()
        .socketHandler(body)
        .build(),
    )
    client =
      client
        .newBuilder()
        .addInterceptor(UppercaseRequestInterceptor())
        .addInterceptor(UppercaseResponseInterceptor())
        .build()
    val call =
      client.newCall(
        Request
          .Builder()
          .url(server.url("/"))
          .post(AsyncRequestBody())
          .build(),
      )
    call.execute().use { response ->
      val requestBody = (call.request().body as AsyncRequestBody?)!!.takeSink()
      requestBody.writeUtf8("request A\n")
      requestBody.flush()
      val responseBody = response.body.source()
      assertThat(responseBody.readUtf8Line())
        .isEqualTo("RESPONSE B")
      requestBody.close()
      assertThat(responseBody.readUtf8Line()).isNull()
    }
    body.awaitSuccess()
  }

  /**
   * OkHttp currently doesn't implement failing the request body stream independently of failing the
   * corresponding response body stream. This is necessary if we want servers to be able to stop
   * inbound data and send an early 400 before the request body completes.
   *
   * This test sends a slow request that is canceled by the server. It expects the response to still
   * be readable after the request stream is canceled.
   */
  @Disabled
  @Test
  fun serverCancelsRequestBodyAndSendsResponseBody() {
    client =
      client
        .newBuilder()
        .retryOnConnectionFailure(false)
        .build()
    val log: BlockingQueue<String?> = LinkedBlockingQueue()
    enableProtocol(Protocol.HTTP_2)
    val body =
      MockSocketHandler()
        .sendResponse("success!")
        .exhaustResponse()
        .cancelStream()
    server.enqueue(
      MockResponse
        .Builder()
        .clearHeaders()
        .socketHandler(body)
        .build(),
    )
    val call =
      client.newCall(
        Request
          .Builder()
          .url(server.url("/"))
          .post(
            object : RequestBody() {
              override fun contentType(): MediaType? = null

              override fun writeTo(sink: BufferedSink) {
                try {
                  for (i in 0..9) {
                    sink.writeUtf8(".")
                    sink.flush()
                    Thread.sleep(100)
                  }
                } catch (e: IOException) {
                  log.add(e.toString())
                  throw e
                } catch (e: Exception) {
                  log.add(e.toString())
                }
              }
            },
          ).build(),
      )
    call.execute().use { response ->
      assertThat(response.body.string()).isEqualTo("success!")
    }
    body.awaitSuccess()
    assertThat(log.take()!!)
      .contains("StreamResetException: stream was reset: CANCEL")
  }

  /**
   * We delay sending the last byte of the request body 1500 ms. The 1000 ms read timeout should
   * only elapse 1000 ms after the request body is sent.
   */
  @Test
  fun headersReadTimeoutDoesNotStartUntilLastRequestBodyByteFire() {
    enableProtocol(Protocol.HTTP_2)
    server.enqueue(
      MockResponse
        .Builder()
        .headersDelay(1500, TimeUnit.MILLISECONDS)
        .build(),
    )
    val request =
      Request
        .Builder()
        .url(server.url("/"))
        .post(DelayedRequestBody("hello".toRequestBody(null), 1500, TimeUnit.MILLISECONDS))
        .build()
    client =
      client
        .newBuilder()
        .readTimeout(1000, TimeUnit.MILLISECONDS)
        .build()
    val call = client.newCall(request)
    assertFailsWith<IOException> {
      call.execute()
    }.also { expected ->
      assertThat(expected.message).isEqualTo("timeout")
    }
  }

  /** Same as the previous test, but the server stalls sending the response body.  */
  @Test
  fun bodyReadTimeoutDoesNotStartUntilLastRequestBodyByteFire() {
    enableProtocol(Protocol.HTTP_2)
    server.enqueue(
      MockResponse
        .Builder()
        .bodyDelay(1500, TimeUnit.MILLISECONDS)
        .body("this should never be received")
        .build(),
    )
    val request =
      Request
        .Builder()
        .url(server.url("/"))
        .post(DelayedRequestBody("hello".toRequestBody(null), 1500, TimeUnit.MILLISECONDS))
        .build()
    client =
      client
        .newBuilder()
        .readTimeout(1000, TimeUnit.MILLISECONDS)
        .build()
    val call = client.newCall(request)
    val response = call.execute()
    assertFailsWith<IOException> {
      response.body.string()
    }.also { expected ->
      assertThat(expected.message).isEqualTo("timeout")
    }
  }

  /**
   * We delay sending the last byte of the request body 1500 ms. The 1000 ms read timeout shouldn't
   * elapse because it shouldn't start until the request body is sent.
   */
  @Test
  fun headersReadTimeoutDoesNotStartUntilLastRequestBodyByteNoFire() {
    enableProtocol(Protocol.HTTP_2)
    server.enqueue(
      MockResponse
        .Builder()
        .headersDelay(500, TimeUnit.MILLISECONDS)
        .build(),
    )
    val request =
      Request
        .Builder()
        .url(server.url("/"))
        .post(DelayedRequestBody("hello".toRequestBody(null), 1500, TimeUnit.MILLISECONDS))
        .build()
    client =
      client
        .newBuilder()
        .readTimeout(1000, TimeUnit.MILLISECONDS)
        .build()
    val call = client.newCall(request)
    val response = call.execute()
    assertThat(response.isSuccessful).isTrue()
  }

  /**
   * We delay sending the last byte of the request body 1500 ms. The 1000 ms read timeout shouldn't
   * elapse because it shouldn't start until the request body is sent.
   */
  @Test
  fun bodyReadTimeoutDoesNotStartUntilLastRequestBodyByteNoFire() {
    enableProtocol(Protocol.HTTP_2)
    server.enqueue(
      MockResponse
        .Builder()
        .bodyDelay(500, TimeUnit.MILLISECONDS)
        .body("success")
        .build(),
    )
    val request =
      Request
        .Builder()
        .url(server.url("/"))
        .post(DelayedRequestBody("hello".toRequestBody(null), 1500, TimeUnit.MILLISECONDS))
        .build()
    client =
      client
        .newBuilder()
        .readTimeout(1000, TimeUnit.MILLISECONDS)
        .build()
    val call = client.newCall(request)
    val response = call.execute()
    assertThat(response.body.string()).isEqualTo("success")
  }

  /**
   * Tests that use this will fail unless boot classpath is set. Ex. `-Xbootclasspath/p:/tmp/alpn-boot-8.0.0.v20140317`
   */
  private fun enableProtocol(protocol: Protocol) {
    enableTls()
    client =
      client
        .newBuilder()
        .protocols(listOf(protocol, Protocol.HTTP_1_1))
        .build()
    server.protocols = client.protocols
  }

  private fun enableTls() {
    client =
      client
        .newBuilder()
        .sslSocketFactory(
          handshakeCertificates.sslSocketFactory(),
          handshakeCertificates.trustManager,
        ).hostnameVerifier(RecordingHostnameVerifier())
        .build()
    server.useHttps(handshakeCertificates.sslSocketFactory())
  }

  private inner class DelayedRequestBody(
    private val delegate: RequestBody,
    delay: Long,
    timeUnit: TimeUnit,
  ) : RequestBody() {
    private val delayMillis = timeUnit.toMillis(delay)

    override fun contentType() = delegate.contentType()

    override fun isDuplex() = true

    override fun writeTo(sink: BufferedSink) {
      executorService.schedule({
        try {
          delegate.writeTo(sink)
          sink.close()
        } catch (e: IOException) {
          throw RuntimeException(e)
        }
      }, delayMillis, TimeUnit.MILLISECONDS)
    }
  }
}
