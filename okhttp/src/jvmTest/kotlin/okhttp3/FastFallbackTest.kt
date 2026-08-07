/*
 * Copyright (C) 2022 Square, Inc.
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
import assertk.assertions.hasMessage
import assertk.assertions.hasSize
import assertk.assertions.isEqualTo
import java.io.IOException
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import java.net.Socket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import javax.net.SocketFactory
import kotlin.test.assertFailsWith
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.SocketEffect.CloseStream
import okhttp3.CallEvent.ConnectEnd
import okhttp3.CallEvent.ConnectFailed
import okhttp3.CallEvent.ConnectStart
import okhttp3.internal.http2.ErrorCode
import okhttp3.sockets.DelegatingSocketFactory
import okhttp3.testing.Flaky
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.api.extension.RegisterExtension
import org.junitpioneer.jupiter.RetryingTest
import org.opentest4j.TestAbortedException

/**
 * This test binds two different web servers (IPv4 and IPv6) to the same port, but on different
 * local IP addresses. Requests made to `127.0.0.1` will reach the IPv4 server, and requests made to
 * `::1` will reach the IPv6 server.
 *
 * By orchestrating two different servers with the same port but different IP addresses, we can
 * test what OkHttp does when both are reachable, or if only one is reachable.
 *
 * This test only runs on host machines that have both IPv4 and IPv6 addresses for localhost.
 */
@Timeout(30)
class FastFallbackTest {
  @RegisterExtension
  val clientTestRule = OkHttpClientTestRule()

  // Don't use JUnit 5 test rules for these; otherwise we can't bind them to a single local IP.
  private lateinit var localhostIpv4: InetAddress
  private lateinit var localhostIpv6: InetAddress
  private lateinit var serverIpv4: MockWebServer
  private lateinit var serverIpv6: MockWebServer

  private val eventRecorder = EventRecorder()
  private lateinit var client: OkHttpClient
  private lateinit var url: HttpUrl

  /**
   * This is mutable and order matters. By default, it contains [IPv4, IPv6]. Tests may manipulate
   * it to prefer IPv6.
   */
  private var dnsResults = listOf<InetAddress>()

  @BeforeEach
  internal fun setUp() {
    val inetAddresses = InetAddress.getAllByName("localhost")
    localhostIpv4 = inetAddresses.firstOrNull { it is Inet4Address }
      ?: throw TestAbortedException()
    localhostIpv6 = inetAddresses.firstOrNull { it is Inet6Address }
      ?: throw TestAbortedException()

    serverIpv4 = MockWebServer()
    serverIpv4.start(localhostIpv4, 0) // Pick any available port.

    serverIpv6 = MockWebServer()
    serverIpv6.start(localhostIpv6, serverIpv4.port) // Pick the same port as the IPv4 server.

    dnsResults =
      listOf(
        localhostIpv4,
        localhostIpv6,
      )

    client =
      clientTestRule
        .newClientBuilder()
        .eventListenerFactory(clientTestRule.wrap(eventRecorder))
        .connectTimeout(60, TimeUnit.SECONDS) // Deliberately exacerbate slow fallbacks.
        .dns { dnsResults }
        .fastFallback(true)
        .build()
    url =
      serverIpv4
        .url("/")
        .newBuilder()
        .host("localhost")
        .build()
  }

  @AfterEach
  internal fun tearDown() {
    serverIpv4.close()
    serverIpv6.close()
  }

  @Test
  fun callIpv6FirstEvenWhenIpv4IpIsListedFirst() {
    dnsResults =
      listOf(
        localhostIpv4,
        localhostIpv6,
      )
    serverIpv4.enqueue(
      MockResponse(body = "unexpected call to IPv4"),
    )
    serverIpv6.enqueue(
      MockResponse(body = "hello from IPv6"),
    )

    val call = client.newCall(Request(url))
    val response = call.execute()
    assertThat(response.body.string()).isEqualTo("hello from IPv6")

    // In the process we made one successful connection attempt.
    assertThat(eventRecorder.recordedEventTypes().filter { it == ConnectStart::class }).hasSize(1)
    assertThat(eventRecorder.recordedEventTypes().filter { it == ConnectFailed::class }).hasSize(0)
  }

  @Test
  fun callIpv6WhenBothServersAreReachable() {
    // Flip DNS results to prefer IPv6.
    dnsResults =
      listOf(
        localhostIpv6,
        localhostIpv4,
      )
    serverIpv4.enqueue(
      MockResponse(body = "unexpected call to IPv4"),
    )
    serverIpv6.enqueue(
      MockResponse(body = "hello from IPv6"),
    )

    val call = client.newCall(Request(url))
    val response = call.execute()
    assertThat(response.body.string()).isEqualTo("hello from IPv6")

    // In the process we made one successful connection attempt.
    assertThat(eventRecorder.recordedEventTypes().filter { it == ConnectStart::class }).hasSize(1)
    assertThat(eventRecorder.recordedEventTypes().filter { it == ConnectFailed::class }).hasSize(0)
  }

  @Test
  fun reachesIpv4WhenIpv6IsDown() {
    serverIpv6.close()
    serverIpv4.enqueue(
      MockResponse(body = "hello from IPv4"),
    )

    val call = client.newCall(Request(url))
    val response = call.execute()
    assertThat(response.body.string()).isEqualTo("hello from IPv4")

    // In the process we made one successful connection attempt.
    assertThat(eventRecorder.recordedEventTypes().filter { it == ConnectStart::class }).hasSize(2)
    assertThat(eventRecorder.recordedEventTypes().filter { it == ConnectFailed::class }).hasSize(1)
    assertThat(eventRecorder.recordedEventTypes().filter { it == ConnectEnd::class }).hasSize(1)
  }

  @Test
  fun reachesIpv6WhenIpv4IsDown() {
    serverIpv4.close()
    serverIpv6.enqueue(
      MockResponse(body = "hello from IPv6"),
    )

    val call = client.newCall(Request(url))
    val response = call.execute()
    assertThat(response.body.string()).isEqualTo("hello from IPv6")

    // In the process we made two connection attempts including one failure.
    assertThat(eventRecorder.recordedEventTypes().filter { it == ConnectStart::class }).hasSize(1)
    assertThat(eventRecorder.recordedEventTypes().filter { it == ConnectEnd::class }).hasSize(1)
    assertThat(eventRecorder.recordedEventTypes().filter { it == ConnectFailed::class }).hasSize(0)
  }

  @Test
  fun failsWhenBothServersAreDown() {
    serverIpv4.close()
    serverIpv6.close()

    val call = client.newCall(Request(url))
    assertFailsWith<IOException> {
      call.execute()
    }

    // In the process we made two unsuccessful connection attempts.
    assertThat(eventRecorder.recordedEventTypes().filter { it == ConnectStart::class }).hasSize(2)
    assertThat(eventRecorder.recordedEventTypes().filter { it == ConnectFailed::class }).hasSize(2)
  }

  @RetryingTest(5)
  @Flaky
  fun reachesIpv4AfterUnreachableIpv6Address() {
    dnsResults =
      listOf(
        TestUtil.UNREACHABLE_ADDRESS_IPV6.address,
        localhostIpv4,
      )
    serverIpv6.close()
    serverIpv4.enqueue(
      MockResponse(body = "hello from IPv4"),
    )

    val call = client.newCall(Request(url))
    val response = call.execute()
    assertThat(response.body.string()).isEqualTo("hello from IPv4")

    // In the process we made two connection attempts including one failure.
    assertThat(eventRecorder.recordedEventTypes().filter { it == ConnectStart::class }).hasSize(2)
    assertThat(eventRecorder.recordedEventTypes().filter { it == ConnectFailed::class }).hasSize(1)
  }

  @Test
  fun timesOutWithFastFallbackDisabled() {
    dnsResults =
      listOf(
        TestUtil.UNREACHABLE_ADDRESS_IPV4.address,
        localhostIpv6,
      )
    serverIpv4.close()
    serverIpv6.enqueue(
      MockResponse(body = "hello from IPv6"),
    )

    client =
      client
        .newBuilder()
        .fastFallback(false)
        .callTimeout(1_000, TimeUnit.MILLISECONDS)
        .build()
    val call = client.newCall(Request(url))
    assertFailsWith<IOException> {
      call.execute()
    }.also { expected ->
      assertThat(expected).hasMessage("timeout")
    }
  }

  /**
   * This test reproduces a crash where OkHttp attempted to use a deferred connection when the call
   * already had a healthy connection. It sets up a deferred connection by stalling the IPv6
   * connect, and it sets up a same-connection retry with [ErrorCode.REFUSED_STREAM].
   *
   * https://github.com/lysine-dev/okhttp/pull/7190
   */
  @Test
  fun preferCallConnectionOverDeferredConnection() {
    // Make sure we have enough connection options to permit retries.
    dnsResults =
      listOf(
        localhostIpv4,
        localhostIpv6,
        TestUtil.UNREACHABLE_ADDRESS_IPV4.address,
      )
    serverIpv4.protocols = listOf(Protocol.H2_PRIOR_KNOWLEDGE)
    serverIpv6.protocols = listOf(Protocol.H2_PRIOR_KNOWLEDGE)

    // Yield the first IP address so the second IP address completes first.
    val firstConnectLatch = CountDownLatch(1)
    val socketFactory =
      object : DelegatingSocketFactory(SocketFactory.getDefault()) {
        var first = true

        override fun createSocket(): Socket {
          if (first) {
            first = false
            firstConnectLatch.await()
          }
          return super.createSocket()
        }
      }

    client =
      client
        .newBuilder()
        .protocols(listOf(Protocol.H2_PRIOR_KNOWLEDGE))
        .socketFactory(socketFactory)
        .addNetworkInterceptor(
          Interceptor { chain ->
            try {
              chain.proceed(chain.request())
            } finally {
              firstConnectLatch.countDown()
            }
          },
        ).build()

    // Set up a same-connection retry.
    serverIpv4.enqueue(
      MockResponse
        .Builder()
        .onRequestStart(CloseStream(ErrorCode.REFUSED_STREAM.httpCode))
        .build(),
    )
    serverIpv4.enqueue(
      MockResponse(body = "this was the 2nd request on IPv4"),
    )
    serverIpv6.enqueue(
      MockResponse(body = "unexpected call to IPv6"),
    )

    // Confirm the retry succeeds on the same connection.
    val call = client.newCall(Request(url))
    val response = call.execute()
    assertThat(response.body.string()).isEqualTo("this was the 2nd request on IPv4")
    assertThat(serverIpv4.takeRequest().exchangeIndex).isEqualTo(0)
    assertThat(serverIpv4.takeRequest().exchangeIndex).isEqualTo(1)
  }
}
