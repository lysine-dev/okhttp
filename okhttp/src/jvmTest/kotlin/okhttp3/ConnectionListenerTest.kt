/*
 * Copyright (C) 2017 Square, Inc.
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
@file:Suppress(
  "CANNOT_OVERRIDE_INVISIBLE_MEMBER",
  "INVISIBLE_MEMBER",
  "INVISIBLE_REFERENCE",
)

package okhttp3

import assertk.assertThat
import assertk.assertions.containsExactly
import assertk.assertions.hasMessage
import assertk.assertions.isEqualTo
import assertk.assertions.isFalse
import assertk.assertions.isGreaterThan
import assertk.assertions.isIn
import assertk.assertions.isNotNull
import java.io.IOException
import java.net.InetSocketAddress
import java.net.UnknownHostException
import java.time.Duration
import java.util.Arrays
import java.util.concurrent.TimeUnit
import kotlin.test.assertFailsWith
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.junit5.StartStop
import okhttp3.Headers.Companion.headersOf
import okhttp3.internal.DoubleInetAddressDns
import okhttp3.internal.connection.RealConnectionPool.Companion.get
import okhttp3.testing.Flaky
import okhttp3.testing.PlatformRule
import okhttp3.tls.internal.TlsUtil.localhost
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.api.extension.RegisterExtension

@Flaky // STDOUT logging enabled for test
@Timeout(30)
@Tag("Slow")
open class ConnectionListenerTest {
  @RegisterExtension
  val platform = PlatformRule()

  @RegisterExtension
  val clientTestRule = OkHttpClientTestRule()

  @StartStop
  private val server = MockWebServer()

  private val listener = RecordingConnectionListener()
  private val handshakeCertificates = localhost()

  open val fastFallback: Boolean get() = true

  private var client: OkHttpClient =
    clientTestRule
      .newClientBuilder()
      .connectionPool(ConnectionPool(connectionListener = listener))
      .fastFallback(fastFallback)
      .build()

  @BeforeEach
  fun setUp() {
    platform.assumeNotOpenJSSE()
    platform.assumeNotBouncyCastle()
    listener.forbidLock(get(client.connectionPool))
    listener.forbidLock(client.dispatcher)
  }

  @Test
  fun successfulCallEventSequence() {
    server.enqueue(MockResponse(body = "abc"))
    val call =
      client.newCall(
        Request
          .Builder()
          .url(server.url("/"))
          .build(),
      )
    val response = call.execute()
    assertThat(response.code).isEqualTo(200)
    assertThat(response.body.string()).isEqualTo("abc")
    response.body.close()
    assertThat(listener.recordedEventTypes()).containsExactly(
      "ConnectStart",
      "ConnectEnd",
      "ConnectionAcquired",
      "ConnectionReleased",
    )
  }

  @Test
  fun failedCallEventSequence() {
    server.enqueue(
      MockResponse
        .Builder()
        .headersDelay(2, TimeUnit.SECONDS)
        .build(),
    )
    client =
      client
        .newBuilder()
        .readTimeout(Duration.ofMillis(250))
        .build()
    val call =
      client.newCall(
        Request
          .Builder()
          .url(server.url("/"))
          .build(),
      )
    assertFailsWith<IOException> {
      call.execute()
    }.also { expected ->
      assertThat(expected.message).isIn("timeout", "Read timed out")
    }
    assertThat(listener.recordedEventTypes()).containsExactly(
      "ConnectStart",
      "ConnectEnd",
      "ConnectionAcquired",
      "NoNewExchanges",
      "ConnectionReleased",
      "ConnectionClosed",
    )
  }

  @Throws(IOException::class)
  private fun assertSuccessfulEventOrder() {
    val call =
      client.newCall(
        Request
          .Builder()
          .url(server.url("/"))
          .build(),
      )
    val response = call.execute()
    assertThat(response.code).isEqualTo(200)
    response.body.string()
    response.body.close()
    assertThat(listener.recordedEventTypes()).containsExactly(
      "ConnectStart",
      "ConnectEnd",
      "ConnectionAcquired",
      "ConnectionReleased",
    )
  }

  @Test
  @Throws(IOException::class)
  fun secondCallEventSequence() {
    enableTls()
    server.protocols = listOf(Protocol.HTTP_2, Protocol.HTTP_1_1)
    server.enqueue(MockResponse())
    server.enqueue(MockResponse())

    client
      .newCall(Request(server.url("/")))
      .execute()
      .close()

    client
      .newCall(Request(server.url("/")))
      .execute()
      .close()

    assertThat(listener.recordedEventTypes()).containsExactly(
      "ConnectStart",
      "ConnectEnd",
      "ConnectionAcquired",
      "ConnectionReleased",
      "ConnectionAcquired",
      "ConnectionReleased",
    )
  }

  @Test
  @Throws(IOException::class)
  fun successfulEmptyH2CallEventSequence() {
    enableTls()
    server.protocols = Arrays.asList(Protocol.HTTP_2, Protocol.HTTP_1_1)
    server.enqueue(MockResponse())
    assertSuccessfulEventOrder()
  }

  @Test
  @Throws(IOException::class)
  fun multipleDnsLookupsForSingleCall() {
    server.enqueue(
      MockResponse(
        code = 301,
        headers = headersOf("Location", "http://www.fakeurl:" + server.port),
      ),
    )
    server.enqueue(MockResponse())
    val dns = FakeDns()
    dns["fakeurl"] = client.dns.lookup(server.hostName)
    dns["www.fakeurl"] = client.dns.lookup(server.hostName)
    client =
      client
        .newBuilder()
        .dns(dns)
        .build()
    val call =
      client.newCall(
        Request
          .Builder()
          .url("http://fakeurl:" + server.port)
          .build(),
      )
    val response = call.execute()
    assertThat(response.code).isEqualTo(200)
    response.body.close()
    listener.removeUpToEvent(ConnectionEvent.ConnectEnd::class.java)
    listener.removeUpToEvent(ConnectionEvent.ConnectionReleased::class.java)
    listener.removeUpToEvent(ConnectionEvent.ConnectionAcquired::class.java)
    listener.removeUpToEvent(ConnectionEvent.ConnectionReleased::class.java)
  }

  @Test
  @Throws(IOException::class)
  fun successfulConnect() {
    server.enqueue(MockResponse())
    val call =
      client.newCall(
        Request
          .Builder()
          .url(server.url("/"))
          .build(),
      )
    val response = call.execute()
    assertThat(response.code).isEqualTo(200)
    response.body.close()
    val address = client.dns.lookup(server.hostName)[0]
    val expectedAddress = InetSocketAddress(address, server.port)
    val event = listener.removeUpToEvent(ConnectionEvent.ConnectStart::class.java)
    assertThat(event.route.socketAddress).isEqualTo(expectedAddress)
  }

  @Test
  @Throws(UnknownHostException::class)
  fun failedConnect() {
    enableTls()
    server.enqueue(MockResponse.Builder().failHandshake().build())
    val call =
      client.newCall(
        Request
          .Builder()
          .url(server.url("/"))
          .build(),
      )
    assertFailsWith<IOException> {
      call.execute()
    }
    val address = client.dns.lookup(server.hostName)[0]
    val expectedAddress = InetSocketAddress(address, server.port)
    val event = listener.removeUpToEvent(ConnectionEvent.ConnectFailed::class.java)
    assertThat(event.route.socketAddress).isEqualTo(expectedAddress)

    // Read error: ssl=0x7fd1d8d0fee8: Failure in SSL library, usually a protocol error
    if (!platform.isConscrypt()) {
      assertThat(event.exception.message).isIn(
        "Unexpected handshake message: client_hello",
        "(unexpected_message) Unexpected handshake message: client_hello",
      )
    }
  }

  @Test
  @Throws(IOException::class)
  fun multipleConnectsForSingleCall() {
    enableTls()
    server.enqueue(MockResponse.Builder().failHandshake().build())
    server.enqueue(MockResponse())
    client =
      client
        .newBuilder()
        .dns(DoubleInetAddressDns())
        .build()
    val call =
      client.newCall(
        Request
          .Builder()
          .url(server.url("/"))
          .build(),
      )
    val response = call.execute()
    assertThat(response.code).isEqualTo(200)
    response.body.close()
    assertThat(listener.recordedEventTypes()).containsExactly(
      "ConnectStart",
      "ConnectFailed",
      "ConnectStart",
      "ConnectEnd",
      "ConnectionAcquired",
      "ConnectionReleased",
    )
  }

  @Test
  @Throws(IOException::class)
  fun successfulHttpProxyConnect() {
    server.enqueue(MockResponse())
    val proxy = server.proxyAddress
    client =
      client
        .newBuilder()
        .proxy(proxy)
        .build()
    val call =
      client.newCall(
        Request
          .Builder()
          .url("http://www.fakeurl")
          .build(),
      )
    val response = call.execute()
    assertThat(response.code).isEqualTo(200)
    response.body.close()
    assertThat(listener.recordedEventTypes()).containsExactly(
      "ConnectStart",
      "ConnectEnd",
      "ConnectionAcquired",
      "ConnectionReleased",
    )
    val event = listener.removeUpToEvent(ConnectionEvent.ConnectEnd::class.java)
    assertThat(event.connection.route().proxy).isEqualTo(proxy)
  }

  @Test
  @Throws(IOException::class)
  fun connectionMetrics() {
    server.enqueue(MockResponse())
    val call =
      client.newCall(
        Request
          .Builder()
          .url(server.url("/"))
          .build(),
      )
    val response = call.execute()
    assertThat(response.code).isEqualTo(200)
    response.body.close()
    val event = listener.removeUpToEvent(ConnectionEvent.ConnectEnd::class.java)
    val connection = event.connection
    assertThat(connection.connectAtMillis()).isGreaterThan(0L)
    assertThat(connection.successCount()).isEqualTo(1)
    assertThat(connection.callCount()).isEqualTo(0)
    assertThat(connection.idleAtMillis()).isNotNull()
    assertThat(connection.noNewExchanges()).isFalse()
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
}

@Flaky // STDOUT logging enabled for test
@Timeout(30)
@Tag("Slow")
class ConnectionListenerLegacyTest : ConnectionListenerTest() {
  override val fastFallback get() = false
}
