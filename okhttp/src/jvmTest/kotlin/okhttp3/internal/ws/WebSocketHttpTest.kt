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
package okhttp3.internal.ws

import assertk.assertThat
import assertk.assertions.isBetween
import assertk.assertions.isCloseTo
import assertk.assertions.isEmpty
import assertk.assertions.isEqualTo
import assertk.assertions.isFalse
import assertk.assertions.isInstanceOf
import assertk.assertions.isLessThan
import assertk.assertions.isNotNull
import assertk.assertions.isNull
import java.io.EOFException
import java.io.IOException
import java.io.InterruptedIOException
import java.net.HttpURLConnection
import java.net.ProtocolException
import java.net.SocketTimeoutException
import java.time.Duration
import java.util.Arrays
import java.util.Collections
import java.util.Random
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertFailsWith
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import mockwebserver3.SocketEffect.CloseSocket
import mockwebserver3.SocketEffect.Stall
import mockwebserver3.junit5.StartStop
import okhttp3.EventRecorder
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.OkHttpClientTestRule
import okhttp3.Protocol
import okhttp3.RecordingHostnameVerifier
import okhttp3.Request
import okhttp3.Response
import okhttp3.TestLogHandler
import okhttp3.TestUtil.assumeNotWindows
import okhttp3.TestUtil.repeat
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okhttp3.internal.UnreadableResponseBody
import okhttp3.internal.concurrent.TaskRunner
import okhttp3.internal.ws.WebSocketProtocol.acceptHeader
import okhttp3.testing.Flaky
import okhttp3.testing.PlatformRule
import okio.Buffer
import okio.ByteString
import okio.ByteString.Companion.decodeHex
import okio.ByteString.Companion.encodeUtf8
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Disabled
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.RegisterExtension

@Flaky
@Tag("Slow")
class WebSocketHttpTest {
  // Flaky https://github.com/lysine-dev/okhttp/issues/4515
  // Flaky https://github.com/lysine-dev/okhttp/issues/4953
  @RegisterExtension
  var clientTestRule = configureClientTestRule()

  @RegisterExtension
  var platform = PlatformRule()

  @RegisterExtension
  var testLogHandler = TestLogHandler(OkHttpClient::class.java)

  @StartStop
  private val webServer = MockWebServer()

  private val handshakeCertificates = platform.localhostHandshakeCertificates()
  private val clientListener = WebSocketRecorder("client")
  private val serverListener = WebSocketRecorder("server")
  private val random = Random(0)
  private var client =
    clientTestRule
      .newClientBuilder()
      .writeTimeout(Duration.ofMillis(500))
      .readTimeout(Duration.ofMillis(500))
      .addInterceptor(
        Interceptor { chain: Interceptor.Chain ->
          val response = chain.proceed(chain.request())
          // Ensure application interceptors never see a null body.
          assertThat(response.body).isNotNull()
          response
        },
      ).build()

  private fun configureClientTestRule(): OkHttpClientTestRule {
    val clientTestRule = OkHttpClientTestRule()
    clientTestRule.recordTaskRunner = true
    return clientTestRule
  }

  @BeforeEach
  fun setUp() {
    platform.assumeNotOpenJSSE()
  }

  @AfterEach
  @Throws(InterruptedException::class)
  fun tearDown() {
    clientListener.assertExhausted()
  }

  @Test
  fun textMessage() {
    webServer.enqueue(
      MockResponse
        .Builder()
        .webSocketUpgrade(serverListener)
        .build(),
    )
    val webSocket: WebSocket = newWebSocket()
    clientListener.assertOpen()
    val server = serverListener.assertOpen()
    webSocket.send("Hello, WebSockets!")
    serverListener.assertTextMessage("Hello, WebSockets!")
    closeWebSockets(webSocket, server)
  }

  @Test
  fun binaryMessage() {
    webServer.enqueue(
      MockResponse
        .Builder()
        .webSocketUpgrade(serverListener)
        .build(),
    )
    val webSocket: WebSocket = newWebSocket()
    clientListener.assertOpen()
    val server = serverListener.assertOpen()
    webSocket.send("Hello!".encodeUtf8())
    serverListener.assertBinaryMessage("Hello!".encodeUtf8())
    closeWebSockets(webSocket, server)
  }

  @Test
  fun serverMessage() {
    webServer.enqueue(
      MockResponse
        .Builder()
        .webSocketUpgrade(serverListener)
        .build(),
    )
    val webSocket: WebSocket = newWebSocket()
    clientListener.assertOpen()
    val server = serverListener.assertOpen()
    server.send("Hello, WebSockets!")
    clientListener.assertTextMessage("Hello, WebSockets!")
    closeWebSockets(webSocket, server)
  }

  @Test
  fun throwingOnOpenFailsImmediately() {
    webServer.enqueue(
      MockResponse
        .Builder()
        .webSocketUpgrade(serverListener)
        .build(),
    )
    val e = RuntimeException()
    clientListener.setNextEventDelegate(
      object : WebSocketListener() {
        override fun onOpen(
          webSocket: WebSocket,
          response: Response,
        ): Unit = throw e
      },
    )
    newWebSocket()
    serverListener.assertOpen()
    serverListener.assertFailure(EOFException::class.java)
    serverListener.assertExhausted()
    clientListener.assertFailure(e)
  }

  @Disabled("AsyncCall currently lets runtime exceptions propagate.")
  @Test
  @Throws(
    Exception::class,
  )
  fun throwingOnFailLogs() {
    webServer.enqueue(
      MockResponse
        .Builder()
        .code(200)
        .body("Body")
        .build(),
    )
    val e = RuntimeException("boom")
    clientListener.setNextEventDelegate(
      object : WebSocketListener() {
        override fun onFailure(
          webSocket: WebSocket,
          t: Throwable,
          response: Response?,
        ): Unit = throw e
      },
    )
    newWebSocket()
    assertThat(testLogHandler.take()).isEqualTo("INFO: [WS client] onFailure")
  }

  @Test
  fun throwingOnMessageClosesImmediatelyAndFails() {
    webServer.enqueue(
      MockResponse
        .Builder()
        .webSocketUpgrade(serverListener)
        .build(),
    )
    newWebSocket()
    clientListener.assertOpen()
    val server = serverListener.assertOpen()
    val e = RuntimeException()
    clientListener.setNextEventDelegate(
      object : WebSocketListener() {
        override fun onMessage(
          webSocket: WebSocket,
          text: String,
        ): Unit = throw e
      },
    )
    server.send("Hello, WebSockets!")
    clientListener.assertFailure(e)
    serverListener.assertFailure(EOFException::class.java)
    serverListener.assertExhausted()
  }

  @Test
  fun throwingOnClosingClosesImmediatelyAndFails() {
    webServer.enqueue(
      MockResponse
        .Builder()
        .webSocketUpgrade(serverListener)
        .build(),
    )
    newWebSocket()
    clientListener.assertOpen()
    val server = serverListener.assertOpen()
    val e = RuntimeException()
    clientListener.setNextEventDelegate(
      object : WebSocketListener() {
        override fun onClosing(
          webSocket: WebSocket,
          code: Int,
          reason: String,
        ): Unit = throw e
      },
    )
    server.close(1000, "bye")
    clientListener.assertFailure(e)
    serverListener.assertFailure()
    serverListener.assertExhausted()
  }

  @Test
  fun unplannedCloseHandledByCloseWithoutFailure() {
    webServer.enqueue(
      MockResponse
        .Builder()
        .webSocketUpgrade(serverListener)
        .build(),
    )
    newWebSocket()
    clientListener.assertOpen()
    val server = serverListener.assertOpen()
    clientListener.setNextEventDelegate(
      object : WebSocketListener() {
        override fun onClosing(
          webSocket: WebSocket,
          code: Int,
          reason: String,
        ) {
          webSocket.close(1000, null)
        }
      },
    )
    server.close(1001, "bye")
    clientListener.assertClosed(1001, "bye")
    clientListener.assertExhausted()
    serverListener.assertClosing(1000, "")
    serverListener.assertClosed(1000, "")
    serverListener.assertExhausted()
  }

  @Test
  fun unplannedCloseHandledWithoutFailure() {
    webServer.enqueue(
      MockResponse
        .Builder()
        .webSocketUpgrade(serverListener)
        .build(),
    )
    newWebSocket()
    val webSocket = clientListener.assertOpen()
    val server = serverListener.assertOpen()
    closeWebSockets(webSocket, server)
  }

  @Test
  @Throws(IOException::class)
  fun non101RetainsBody() {
    webServer.enqueue(
      MockResponse
        .Builder()
        .code(200)
        .body("Body")
        .build(),
    )
    newWebSocket()
    clientListener.assertFailure(
      200,
      "Body",
      ProtocolException::class.java,
      "Expected HTTP 101 response but was '200 OK'",
    )
  }

  @Test
  @Throws(IOException::class)
  fun notFound() {
    webServer.enqueue(
      MockResponse
        .Builder()
        .status("HTTP/1.1 404 Not Found")
        .build(),
    )
    newWebSocket()
    clientListener.assertFailure(
      404,
      null,
      ProtocolException::class.java,
      "Expected HTTP 101 response but was '404 Not Found'",
    )
  }

  @Test
  fun clientTimeoutClosesBody() {
    webServer.enqueue(
      MockResponse
        .Builder()
        .code(408)
        .build(),
    )
    webServer.enqueue(
      MockResponse
        .Builder()
        .webSocketUpgrade(serverListener)
        .build(),
    )
    val webSocket: WebSocket = newWebSocket()
    clientListener.assertOpen()
    val server = serverListener.assertOpen()
    webSocket.send("abc")
    serverListener.assertTextMessage("abc")
    server.send("def")
    clientListener.assertTextMessage("def")
    closeWebSockets(webSocket, server)
  }

  @Test
  @Throws(IOException::class)
  fun missingConnectionHeader() {
    webServer.enqueue(
      MockResponse
        .Builder()
        .code(101)
        .setHeader("Upgrade", "websocket")
        .setHeader("Sec-WebSocket-Accept", "ujmZX4KXZqjwy6vi1aQFH5p4Ygk=")
        .build(),
    )
    webServer.enqueue(
      MockResponse
        .Builder()
        .onRequestStart(CloseSocket())
        .build(),
    )
    val webSocket = newWebSocket()
    clientListener.assertFailure(
      101,
      null,
      ProtocolException::class.java,
      "Expected 'Connection' header value 'Upgrade' but was 'null'",
    )
    webSocket.cancel()
  }

  @Test
  @Throws(IOException::class)
  fun wrongConnectionHeader() {
    webServer.enqueue(
      MockResponse
        .Builder()
        .code(101)
        .setHeader("Upgrade", "websocket")
        .setHeader("Connection", "Downgrade")
        .setHeader("Sec-WebSocket-Accept", "ujmZX4KXZqjwy6vi1aQFH5p4Ygk=")
        .build(),
    )
    webServer.enqueue(
      MockResponse
        .Builder()
        .onRequestStart(CloseSocket())
        .build(),
    )
    val webSocket = newWebSocket()
    clientListener.assertFailure(
      101,
      null,
      ProtocolException::class.java,
      "Expected 'Connection' header value 'Upgrade' but was 'Downgrade'",
    )
    webSocket.cancel()
  }

  @Test
  @Throws(IOException::class)
  fun missingUpgradeHeader() {
    webServer.enqueue(
      MockResponse
        .Builder()
        .code(101)
        .setHeader("Connection", "Upgrade")
        .setHeader("Sec-WebSocket-Accept", "ujmZX4KXZqjwy6vi1aQFH5p4Ygk=")
        .build(),
    )
    webServer.enqueue(
      MockResponse
        .Builder()
        .onRequestStart(CloseSocket())
        .build(),
    )
    val webSocket = newWebSocket()
    clientListener.assertFailure(
      101,
      null,
      ProtocolException::class.java,
      "Expected 'Upgrade' header value 'websocket' but was 'null'",
    )
    webSocket.cancel()
  }

  @Test
  @Throws(IOException::class)
  fun wrongUpgradeHeader() {
    webServer.enqueue(
      MockResponse
        .Builder()
        .code(101)
        .setHeader("Connection", "Upgrade")
        .setHeader("Upgrade", "Pepsi")
        .setHeader("Sec-WebSocket-Accept", "ujmZX4KXZqjwy6vi1aQFH5p4Ygk=")
        .build(),
    )
    webServer.enqueue(
      MockResponse
        .Builder()
        .onRequestStart(CloseSocket())
        .build(),
    )
    val webSocket = newWebSocket()
    clientListener.assertFailure(
      101,
      null,
      ProtocolException::class.java,
      "Expected 'Upgrade' header value 'websocket' but was 'Pepsi'",
    )
    webSocket.cancel()
  }

  @Test
  @Throws(IOException::class)
  fun missingMagicHeader() {
    webServer.enqueue(
      MockResponse
        .Builder()
        .code(101)
        .setHeader("Connection", "Upgrade")
        .setHeader("Upgrade", "websocket")
        .build(),
    )
    webServer.enqueue(
      MockResponse
        .Builder()
        .onRequestStart(CloseSocket())
        .build(),
    )
    val webSocket = newWebSocket()
    clientListener.assertFailure(
      101,
      null,
      ProtocolException::class.java,
      "Expected 'Sec-WebSocket-Accept' header value 'ujmZX4KXZqjwy6vi1aQFH5p4Ygk=' but was 'null'",
    )
    webSocket.cancel()
  }

  @Test
  @Throws(IOException::class)
  fun wrongMagicHeader() {
    webServer.enqueue(
      MockResponse
        .Builder()
        .code(101)
        .setHeader("Connection", "Upgrade")
        .setHeader("Upgrade", "websocket")
        .setHeader("Sec-WebSocket-Accept", "magic")
        .build(),
    )
    webServer.enqueue(
      MockResponse
        .Builder()
        .onRequestStart(CloseSocket())
        .build(),
    )
    val webSocket = newWebSocket()
    clientListener.assertFailure(
      101,
      null,
      ProtocolException::class.java,
      "Expected 'Sec-WebSocket-Accept' header value 'ujmZX4KXZqjwy6vi1aQFH5p4Ygk=' but was 'magic'",
    )
    webSocket.cancel()
  }

  @Test
  @Throws(IOException::class)
  fun clientIncludesForbiddenHeader() {
    newWebSocket(
      Request
        .Builder()
        .url(webServer.url("/"))
        .header("Sec-WebSocket-Extensions", "permessage-deflate")
        .build(),
    )
    clientListener.assertFailure(
      ProtocolException::class.java,
      "Request header not permitted: 'Sec-WebSocket-Extensions'",
    )
  }

  @Test
  fun webSocketAndApplicationInterceptors() {
    val interceptedCount = AtomicInteger()
    client =
      client
        .newBuilder()
        .addInterceptor(
          Interceptor { chain: Interceptor.Chain ->
            assertThat(chain.request().body).isNull()
            val response = chain.proceed(chain.request())
            assertThat(response.header("Connection")).isEqualTo("Upgrade")
            assertThat(response.body).isInstanceOf<UnreadableResponseBody>()
            interceptedCount.incrementAndGet()
            response
          },
        ).build()
    webServer.enqueue(
      MockResponse
        .Builder()
        .webSocketUpgrade(serverListener)
        .build(),
    )
    val webSocket: WebSocket = newWebSocket()
    clientListener.assertOpen()
    assertThat(interceptedCount.get()).isEqualTo(1)
    closeWebSockets(webSocket, serverListener.assertOpen())
  }

  @Test
  fun webSocketAndNetworkInterceptors() {
    client =
      client
        .newBuilder()
        .addNetworkInterceptor(
          Interceptor { chain: Interceptor.Chain? ->
            throw AssertionError() // Network interceptors don't execute.
          },
        ).build()
    webServer.enqueue(
      MockResponse
        .Builder()
        .webSocketUpgrade(serverListener)
        .build(),
    )
    val webSocket: WebSocket = newWebSocket()
    clientListener.assertOpen()
    val server = serverListener.assertOpen()
    closeWebSockets(webSocket, server)
  }

  @Test
  fun overflowOutgoingQueue() {
    webServer.enqueue(
      MockResponse
        .Builder()
        .webSocketUpgrade(serverListener)
        .build(),
    )
    val webSocket: WebSocket = newWebSocket()
    clientListener.assertOpen()

    // Send messages until the client's outgoing buffer overflows!
    val message: ByteString = ByteString.of(*ByteArray(1024 * 1024))
    var messageCount: Long = 0
    while (true) {
      val success = webSocket.send(message)
      if (!success) break
      messageCount++
      val queueSize = webSocket.queueSize()
      assertThat(queueSize).isBetween(0L, messageCount * message.size)
      // Expect to fail before enqueueing 32 MiB.
      assertThat(messageCount).isLessThan(32L)
    }

    // Confirm all sent messages were received, followed by a client-initiated close.
    val server = serverListener.assertOpen()
    for (i in 0 until messageCount) {
      serverListener.assertBinaryMessage(message)
    }
    serverListener.assertClosing(1001, "")

    // When the server acknowledges the close the connection shuts down gracefully.
    server.close(1000, null)
    clientListener.assertClosing(1000, "")
    clientListener.assertClosed(1000, "")
    serverListener.assertClosed(1001, "")
  }

  @Test
  fun closeReasonMaximumLength() {
    webServer.enqueue(
      MockResponse
        .Builder()
        .webSocketUpgrade(serverListener)
        .build(),
    )
    val clientReason = repeat('C', 123)
    val serverReason = repeat('S', 123)
    val webSocket: WebSocket = newWebSocket()
    val server = serverListener.assertOpen()
    clientListener.assertOpen()
    webSocket.close(1000, clientReason)
    serverListener.assertClosing(1000, clientReason)
    server.close(1000, serverReason)
    clientListener.assertClosing(1000, serverReason)
    clientListener.assertClosed(1000, serverReason)
    serverListener.assertClosed(1000, clientReason)
  }

  @Test
  fun closeReasonTooLong() {
    webServer.enqueue(
      MockResponse
        .Builder()
        .webSocketUpgrade(serverListener)
        .build(),
    )
    val webSocket: WebSocket = newWebSocket()
    val server = serverListener.assertOpen()
    clientListener.assertOpen()
    val reason = repeat('X', 124)
    assertFailsWith<IllegalArgumentException> {
      webSocket.close(1000, reason)
    }.also { expected ->
      assertThat(expected.message).isEqualTo("reason.size() > 123: $reason")
    }
    webSocket.close(1000, null)
    serverListener.assertClosing(1000, "")
    server.close(1000, null)
    clientListener.assertClosing(1000, "")
    clientListener.assertClosed(1000, "")
    serverListener.assertClosed(1000, "")
  }

  @Test
  fun wsScheme() {
    assumeNotWindows()
    websocketScheme("ws")
  }

  @Test
  fun wsUppercaseScheme() {
    websocketScheme("WS")
  }

  @Test
  fun wssScheme() {
    webServer.useHttps(handshakeCertificates.sslSocketFactory())
    client =
      client
        .newBuilder()
        .sslSocketFactory(
          handshakeCertificates.sslSocketFactory(),
          handshakeCertificates.trustManager,
        ).hostnameVerifier(RecordingHostnameVerifier())
        .build()
    websocketScheme("wss")
  }

  @Test
  fun httpsScheme() {
    webServer.useHttps(handshakeCertificates.sslSocketFactory())
    client =
      client
        .newBuilder()
        .sslSocketFactory(
          handshakeCertificates.sslSocketFactory(),
          handshakeCertificates.trustManager,
        ).hostnameVerifier(RecordingHostnameVerifier())
        .build()
    websocketScheme("https")
  }

  @Test
  fun readTimeoutAppliesToHttpRequest() {
    webServer.enqueue(
      MockResponse
        .Builder()
        .onResponseStart(Stall)
        .build(),
    )
    val webSocket: WebSocket = newWebSocket()
    clientListener.assertFailure(
      SocketTimeoutException::class.java,
      "timeout",
      "Read timed out",
    )
    assertThat(webSocket.close(1000, null)).isFalse()
  }

  /**
   * There's no read timeout when reading the first byte of a new frame. But as soon as we start
   * reading a frame we enable the read timeout. In this test we have the server returning the first
   * byte of a frame but no more frames.
   */
  @Test
  fun readTimeoutAppliesWithinFrames() {
    webServer.dispatcher =
      object : Dispatcher() {
        override fun dispatch(request: RecordedRequest): MockResponse =
          upgradeResponse(request)
            .body(Buffer().write("81".decodeHex())) // Truncated frame.
            .removeHeader("Content-Length")
            .build()
      }
    val webSocket: WebSocket = newWebSocket()
    clientListener.assertOpen()
    clientListener.assertFailure(
      SocketTimeoutException::class.java,
      "timeout",
      "Read timed out",
    )
    assertThat(webSocket.close(1000, null)).isFalse()
  }

  @Test
  @Throws(Exception::class)
  fun readTimeoutDoesNotApplyAcrossFrames() {
    webServer.enqueue(
      MockResponse
        .Builder()
        .webSocketUpgrade(serverListener)
        .build(),
    )
    val webSocket: WebSocket = newWebSocket()
    clientListener.assertOpen()
    val server = serverListener.assertOpen()

    // Sleep longer than the HTTP client's read timeout.
    Thread.sleep((client.readTimeoutMillis + 500).toLong())
    server.send("abc")
    clientListener.assertTextMessage("abc")
    closeWebSockets(webSocket, server)
  }

  @Test
  @Throws(Exception::class)
  fun clientPingsServerOnInterval() {
    client =
      client
        .newBuilder()
        .pingInterval(Duration.ofMillis(500))
        .build()
    webServer.enqueue(
      MockResponse
        .Builder()
        .webSocketUpgrade(serverListener)
        .build(),
    )
    val webSocket = newWebSocket()
    clientListener.assertOpen()
    val server = serverListener.assertOpen() as RealWebSocket
    val startNanos = System.nanoTime()
    while (webSocket.receivedPongCount() < 3) {
      Thread.sleep(50)
    }
    val elapsedUntilPong3 = System.nanoTime() - startNanos
    assertThat(TimeUnit.NANOSECONDS.toMillis(elapsedUntilPong3).toDouble())
      .isCloseTo(1500.0, 250.0)

    // The client pinged the server 3 times, and it has ponged back 3 times.
    assertThat(webSocket.sentPingCount()).isEqualTo(3)
    assertThat(server.receivedPingCount()).isEqualTo(3)
    assertThat(webSocket.receivedPongCount()).isEqualTo(3)

    // The server has never pinged the client.
    assertThat(server.receivedPongCount()).isEqualTo(0)
    assertThat(webSocket.receivedPingCount()).isEqualTo(0)
    closeWebSockets(webSocket, server)
  }

  @Test
  @Throws(Exception::class)
  fun clientDoesNotPingServerByDefault() {
    webServer.enqueue(
      MockResponse
        .Builder()
        .webSocketUpgrade(serverListener)
        .build(),
    )
    val webSocket = newWebSocket()
    clientListener.assertOpen()
    val server = serverListener.assertOpen() as RealWebSocket
    Thread.sleep(1000)

    // No pings and no pongs.
    assertThat(webSocket.sentPingCount()).isEqualTo(0)
    assertThat(webSocket.receivedPingCount()).isEqualTo(0)
    assertThat(webSocket.receivedPongCount()).isEqualTo(0)
    assertThat(server.sentPingCount()).isEqualTo(0)
    assertThat(server.receivedPingCount()).isEqualTo(0)
    assertThat(server.receivedPongCount()).isEqualTo(0)
    closeWebSockets(webSocket, server)
  }

  /**
   * Configure the websocket to send pings every 500 ms. Artificially prevent the server from
   * responding to pings. The client should give up when attempting to send its 2nd ping, at about
   * 1000 ms.
   */
  @Test
  fun unacknowledgedPingFailsConnection() {
    assumeNotWindows()
    client =
      client
        .newBuilder()
        .pingInterval(Duration.ofMillis(500))
        .build()

    // Stall in onOpen to prevent pongs from being sent.
    val latch = CountDownLatch(1)
    webServer.enqueue(
      MockResponse
        .Builder()
        .webSocketUpgrade(
          object : WebSocketListener() {
            override fun onOpen(
              webSocket: WebSocket,
              response: Response,
            ) {
              try {
                latch.await() // The server can't respond to pings!
              } catch (e: InterruptedException) {
                throw AssertionError(e)
              }
            }
          },
        ).build(),
    )
    val openAtNanos = System.nanoTime()
    newWebSocket()
    clientListener.assertOpen()
    clientListener.assertFailure(
      SocketTimeoutException::class.java,
      "sent ping but didn't receive pong within 500ms (after 0 successful ping/pongs)",
    )
    latch.countDown()
    val elapsedUntilFailure = System.nanoTime() - openAtNanos
    assertThat(TimeUnit.NANOSECONDS.toMillis(elapsedUntilFailure).toDouble())
      .isCloseTo(1000.0, 250.0)
  }

  /** https://github.com/lysine-dev/okhttp/issues/2788  */
  @Test
  fun clientCancelsIfCloseIsNotAcknowledged() {
    webServer.enqueue(
      MockResponse
        .Builder()
        .webSocketUpgrade(serverListener)
        .build(),
    )
    val webSocket = newWebSocket()
    clientListener.assertOpen()
    val server = serverListener.assertOpen()

    // Initiate a close on the client, which will schedule a hard cancel in 500 ms.
    val closeAtNanos = System.nanoTime()
    webSocket.close(1000, "goodbye", 500L)
    serverListener.assertClosing(1000, "goodbye")

    // Confirm that the hard cancel occurred after 500 ms.
    clientListener.assertFailure()
    val elapsedUntilFailure = System.nanoTime() - closeAtNanos
    assertThat(TimeUnit.NANOSECONDS.toMillis(elapsedUntilFailure).toDouble())
      .isCloseTo(500.0, 250.0)

    // Close the server and confirm it saw what we expected.
    server.close(1000, null)
    serverListener.assertClosed(1000, "goodbye")
  }

  @Test
  fun webSocketsDontTriggerEventListener() {
    val eventRecorder = EventRecorder()
    client =
      client
        .newBuilder()
        .eventListenerFactory(clientTestRule.wrap(eventRecorder))
        .build()
    webServer.enqueue(
      MockResponse
        .Builder()
        .webSocketUpgrade(serverListener)
        .build(),
    )
    val webSocket: WebSocket = newWebSocket()
    clientListener.assertOpen()
    val server = serverListener.assertOpen()
    webSocket.send("Web Sockets and Events?!")
    serverListener.assertTextMessage("Web Sockets and Events?!")
    webSocket.close(1000, "")
    serverListener.assertClosing(1000, "")
    server.close(1000, "")
    clientListener.assertClosing(1000, "")
    clientListener.assertClosed(1000, "")
    serverListener.assertClosed(1000, "")
    assertThat(eventRecorder.recordedEventTypes()).isEmpty()
  }

  @Test
  @Throws(Exception::class)
  fun callTimeoutAppliesToSetup() {
    webServer.enqueue(
      MockResponse
        .Builder()
        .headersDelay(500, TimeUnit.MILLISECONDS)
        .build(),
    )
    client =
      client
        .newBuilder()
        .readTimeout(Duration.ZERO)
        .writeTimeout(Duration.ZERO)
        .callTimeout(Duration.ofMillis(100))
        .build()
    newWebSocket()
    clientListener.assertFailure(InterruptedIOException::class.java, "timeout")
  }

  @Test
  @Throws(Exception::class)
  fun callTimeoutDoesNotApplyOnceConnected() {
    client =
      client
        .newBuilder()
        .callTimeout(Duration.ofMillis(100))
        .build()
    webServer.enqueue(
      MockResponse
        .Builder()
        .webSocketUpgrade(serverListener)
        .build(),
    )
    val webSocket: WebSocket = newWebSocket()
    clientListener.assertOpen()
    val server = serverListener.assertOpen()
    Thread.sleep(500)
    server.send("Hello, WebSockets!")
    clientListener.assertTextMessage("Hello, WebSockets!")
    closeWebSockets(webSocket, server)
  }

  /**
   * We had a bug where web socket connections were leaked if the HTTP connection upgrade was not
   * successful. This test confirms that connections are released back to the connection pool!
   * https://github.com/lysine-dev/okhttp/issues/4258
   */
  @Test
  @Throws(Exception::class)
  fun webSocketConnectionIsReleased() {
    // This test assumes HTTP/1.1 pooling semantics.
    client =
      client
        .newBuilder()
        .protocols(Arrays.asList(Protocol.HTTP_1_1))
        .build()
    webServer.enqueue(
      MockResponse
        .Builder()
        .code(HttpURLConnection.HTTP_NOT_FOUND)
        .body("not found!")
        .build(),
    )
    webServer.enqueue(MockResponse())
    newWebSocket()
    clientListener.assertFailure()
    val regularRequest =
      Request
        .Builder()
        .url(webServer.url("/"))
        .build()
    val response = client.newCall(regularRequest).execute()
    response.close()
    assertThat(webServer.takeRequest().exchangeIndex).isEqualTo(0)
    assertThat(webServer.takeRequest().exchangeIndex).isEqualTo(1)
  }

  /** https://github.com/lysine-dev/okhttp/issues/5705  */
  @Test
  fun closeWithoutSuccessfulConnect() {
    val request =
      Request
        .Builder()
        .url(webServer.url("/"))
        .build()
    val webSocket = client.newWebSocket(request, clientListener)
    webSocket.send("hello")
    webSocket.close(1000, null)
  }

  /** https://github.com/lysine-dev/okhttp/issues/7768  */
  @Test
  @Throws(InterruptedException::class)
  fun reconnectingToNonWebSocket() {
    for (i in 0..29) {
      webServer.enqueue(
        MockResponse
          .Builder()
          .bodyDelay(100, TimeUnit.MILLISECONDS)
          .body("Wrong endpoint")
          .code(401)
          .build(),
      )
    }
    val request =
      Request
        .Builder()
        .url(webServer.url("/"))
        .build()
    val attempts = CountDownLatch(20)
    val webSockets = Collections.synchronizedList(ArrayList<WebSocket>())
    val reconnectOnFailure: WebSocketListener =
      object : WebSocketListener() {
        override fun onFailure(
          webSocket: WebSocket,
          t: Throwable,
          response: Response?,
        ) {
          if (attempts.count > 0) {
            clientListener.setNextEventDelegate(this)
            webSockets.add(client.newWebSocket(request, clientListener))
            attempts.countDown()
          }
        }
      }
    clientListener.setNextEventDelegate(reconnectOnFailure)
    webSockets.add(client.newWebSocket(request, clientListener))
    attempts.await()
    synchronized(webSockets) {
      for (webSocket in webSockets) {
        webSocket.cancel()
      }
    }
  }

  @Test
  @Throws(Exception::class)
  fun compressedMessages() {
    successfulExtensions("permessage-deflate")
  }

  @Test
  @Throws(Exception::class)
  fun compressedMessagesNoClientContextTakeover() {
    successfulExtensions("permessage-deflate; client_no_context_takeover")
  }

  @Test
  @Throws(Exception::class)
  fun compressedMessagesNoServerContextTakeover() {
    successfulExtensions("permessage-deflate; server_no_context_takeover")
  }

  @Test
  @Throws(Exception::class)
  fun unexpectedExtensionParameter() {
    extensionNegotiationFailure("permessage-deflate; unknown_parameter=15")
  }

  @Test
  @Throws(Exception::class)
  fun clientMaxWindowBitsIncluded() {
    extensionNegotiationFailure("permessage-deflate; client_max_window_bits=15")
  }

  @Test
  @Throws(Exception::class)
  fun serverMaxWindowBitsTooLow() {
    extensionNegotiationFailure("permessage-deflate; server_max_window_bits=7")
  }

  @Test
  @Throws(Exception::class)
  fun serverMaxWindowBitsTooHigh() {
    extensionNegotiationFailure("permessage-deflate; server_max_window_bits=16")
  }

  @Test
  @Throws(Exception::class)
  fun serverMaxWindowBitsJustRight() {
    successfulExtensions("permessage-deflate; server_max_window_bits=15")
  }

  @Throws(Exception::class)
  private fun successfulExtensions(extensionsHeader: String) {
    webServer.enqueue(
      MockResponse
        .Builder()
        .addHeader("Sec-WebSocket-Extensions", extensionsHeader)
        .webSocketUpgrade(serverListener)
        .build(),
    )
    val client: WebSocket = newWebSocket()
    clientListener.assertOpen()
    val server = serverListener.assertOpen()

    // Server to client message big enough to be compressed.
    val message1 = repeat('a', RealWebSocket.DEFAULT_MINIMUM_DEFLATE_SIZE.toInt())
    server.send(message1)
    clientListener.assertTextMessage(message1)

    // Client to server message big enough to be compressed.
    val message2 = repeat('b', RealWebSocket.DEFAULT_MINIMUM_DEFLATE_SIZE.toInt())
    client.send(message2)
    serverListener.assertTextMessage(message2)

    // Empty server to client message.
    val message3 = ""
    server.send(message3)
    clientListener.assertTextMessage(message3)

    // Empty client to server message.
    val message4 = ""
    client.send(message4)
    serverListener.assertTextMessage(message4)

    // Server to client message that shares context with message1.
    val message5 = message1 + message1
    server.send(message5)
    clientListener.assertTextMessage(message5)

    // Client to server message that shares context with message2.
    val message6 = message2 + message2
    client.send(message6)
    serverListener.assertTextMessage(message6)
    closeWebSockets(client, server)
    val upgradeRequest = webServer.takeRequest()
    assertThat(upgradeRequest.headers["Sec-WebSocket-Extensions"])
      .isEqualTo("permessage-deflate")
  }

  @Throws(Exception::class)
  private fun extensionNegotiationFailure(extensionsHeader: String) {
    webServer.enqueue(
      MockResponse
        .Builder()
        .addHeader("Sec-WebSocket-Extensions", extensionsHeader)
        .webSocketUpgrade(serverListener)
        .build(),
    )
    newWebSocket()
    clientListener.assertOpen()
    val server = serverListener.assertOpen()
    val clientReason = "unexpected Sec-WebSocket-Extensions in response header"
    serverListener.assertClosing(1010, clientReason)
    server.close(1010, "")
    clientListener.assertClosing(1010, "")
    clientListener.assertClosed(1010, "")
    serverListener.assertClosed(1010, clientReason)
    clientListener.assertExhausted()
    serverListener.assertExhausted()
  }

  private fun upgradeResponse(request: RecordedRequest): MockResponse.Builder {
    val key = request.headers["Sec-WebSocket-Key"]
    return MockResponse
      .Builder()
      .status("HTTP/1.1 101 Switching Protocols")
      .setHeader("Connection", "Upgrade")
      .setHeader("Upgrade", "websocket")
      .setHeader("Sec-WebSocket-Accept", acceptHeader(key!!))
  }

  private fun websocketScheme(scheme: String) {
    webServer.enqueue(
      MockResponse
        .Builder()
        .webSocketUpgrade(serverListener)
        .build(),
    )
    val request =
      Request
        .Builder()
        .url(scheme + "://" + webServer.hostName + ":" + webServer.port + "/")
        .build()
    val webSocket = newWebSocket(request)
    clientListener.assertOpen()
    val server = serverListener.assertOpen()
    webSocket.send("abc")
    serverListener.assertTextMessage("abc")
    closeWebSockets(webSocket, server)
  }

  private fun newWebSocket(
    request: Request =
      Request
        .Builder()
        .get()
        .url(
          webServer.url("/"),
        ).build(),
  ): RealWebSocket {
    val webSocket =
      RealWebSocket(
        TaskRunner.INSTANCE,
        request,
        clientListener,
        random,
        client.pingIntervalMillis.toLong(),
        null,
        0L,
        client.webSocketCloseTimeout.toLong(),
      )
    webSocket.connect(client)
    return webSocket
  }

  private fun closeWebSockets(
    client: WebSocket,
    server: WebSocket,
  ) {
    server.close(1001, "")
    clientListener.assertClosing(1001, "")
    client.close(1000, "")
    serverListener.assertClosing(1000, "")
    clientListener.assertClosed(1001, "")
    serverListener.assertClosed(1000, "")
    clientListener.assertExhausted()
    serverListener.assertExhausted()
  }
}
