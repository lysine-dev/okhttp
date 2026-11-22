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
package okhttp3

import app.cash.burst.Burst
import assertk.all
import assertk.assertThat
import assertk.assertions.contains
import assertk.assertions.containsExactly
import assertk.assertions.doesNotContain
import assertk.assertions.isEmpty
import assertk.assertions.isEqualTo
import assertk.assertions.isFalse
import assertk.assertions.isIn
import assertk.assertions.isInstanceOf
import assertk.assertions.isNotEmpty
import assertk.assertions.isNotNull
import assertk.assertions.isNull
import assertk.assertions.isSameInstanceAs
import assertk.assertions.prop
import java.io.File
import java.io.IOException
import java.io.InterruptedIOException
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.UnknownHostException
import java.time.Duration
import java.util.Arrays
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.assertFailsWith
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.SocketEffect.CloseSocket
import mockwebserver3.junit5.StartStop
import okhttp3.CallEvent.CacheConditionalHit
import okhttp3.CallEvent.CacheHit
import okhttp3.CallEvent.CacheMiss
import okhttp3.CallEvent.CallEnd
import okhttp3.CallEvent.CallFailed
import okhttp3.CallEvent.CallStart
import okhttp3.CallEvent.Canceled
import okhttp3.CallEvent.ConnectEnd
import okhttp3.CallEvent.ConnectFailed
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
import okhttp3.CallEvent.ResponseFailed
import okhttp3.CallEvent.ResponseHeadersEnd
import okhttp3.CallEvent.ResponseHeadersStart
import okhttp3.CallEvent.RetryDecision
import okhttp3.CallEvent.SatisfactionFailure
import okhttp3.CallEvent.SecureConnectEnd
import okhttp3.CallEvent.SecureConnectStart
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.ResponseBody.Companion.toResponseBody
import okhttp3.internal.DoubleInetAddressDns
import okhttp3.internal.RecordingOkAuthenticator
import okhttp3.internal.connection.RealConnectionPool.Companion.get
import okhttp3.logging.HttpLoggingInterceptor
import okhttp3.testing.Flaky
import okhttp3.testing.PlatformRule
import okio.Buffer
import okio.BufferedSink
import org.hamcrest.BaseMatcher
import org.hamcrest.CoreMatchers
import org.hamcrest.Description
import org.hamcrest.Matcher
import org.hamcrest.MatcherAssert
import org.junit.Assume.assumeThat
import org.junit.Assume.assumeTrue
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.api.extension.RegisterExtension

@Flaky // STDOUT logging enabled for test
@Timeout(30)
@Tag("Slow")
@Burst
class EventListenerTest(
  val listenerInstalledOn: ListenerInstalledOn = ListenerInstalledOn.Client,
) {
  @RegisterExtension
  val platform = PlatformRule()

  @RegisterExtension
  val clientTestRule = OkHttpClientTestRule()

  @StartStop
  private val server = MockWebServer()

  private val eventRecorder = EventRecorder()
  private val handshakeCertificates = platform.localhostHandshakeCertificates()
  private var client =
    clientTestRule
      .newClientBuilder()
      .apply {
        if (listenerInstalledOn == ListenerInstalledOn.Client) {
          eventListenerFactory(clientTestRule.wrap(eventRecorder))
        }
      }.build()
  private var socksProxy: SocksProxy? = null
  private var cache: Cache? = null

  @BeforeEach
  fun setUp() {
    platform.assumeNotOpenJSSE()
    eventRecorder.forbidLock(get(client.connectionPool))
    eventRecorder.forbidLock(client.dispatcher)
  }

  @AfterEach
  fun tearDown() {
    if (socksProxy != null) {
      socksProxy!!.shutdown()
    }
    if (cache != null) {
      cache!!.delete()
    }
  }

  @Test
  fun successfulCallEventSequence() {
    server.enqueue(
      MockResponse
        .Builder()
        .body("abc")
        .build(),
    )
    val call =
      client.newCallWithListener(
        Request
          .Builder()
          .url(server.url("/"))
          .build(),
      )
    val response = call.execute()
    assertThat(response.code).isEqualTo(200)
    assertThat(response.body.string()).isEqualTo("abc")
    response.body.close()
    assertThat(eventRecorder.recordedEventTypes()).containsExactly(
      CallStart::class,
      ProxySelectStart::class,
      ProxySelectEnd::class,
      DnsStart::class,
      DnsEnd::class,
      ConnectStart::class,
      ConnectEnd::class,
      ConnectionAcquired::class,
      RequestHeadersStart::class,
      RequestHeadersEnd::class,
      ResponseHeadersStart::class,
      ResponseHeadersEnd::class,
      FollowUpDecision::class,
      ResponseBodyStart::class,
      ResponseBodyEnd::class,
      ConnectionReleased::class,
      CallEnd::class,
    )
  }

  @Test
  fun successfulCallEventSequenceForIpAddress() {
    server.enqueue(
      MockResponse
        .Builder()
        .body("abc")
        .build(),
    )
    val ipAddress = InetAddress.getLoopbackAddress().hostAddress
    val call =
      client.newCallWithListener(
        Request
          .Builder()
          .url(
            server
              .url("/")
              .newBuilder()
              .host(ipAddress!!)
              .build(),
          ).build(),
      )
    val response = call.execute()
    assertThat(response.code).isEqualTo(200)
    assertThat(response.body.string()).isEqualTo("abc")
    response.body.close()
    assertThat(eventRecorder.recordedEventTypes()).containsExactly(
      CallStart::class,
      ProxySelectStart::class,
      ProxySelectEnd::class,
      ConnectStart::class,
      ConnectEnd::class,
      ConnectionAcquired::class,
      RequestHeadersStart::class,
      RequestHeadersEnd::class,
      ResponseHeadersStart::class,
      ResponseHeadersEnd::class,
      FollowUpDecision::class,
      ResponseBodyStart::class,
      ResponseBodyEnd::class,
      ConnectionReleased::class,
      CallEnd::class,
    )
  }

  @Test
  fun successfulCallEventSequenceForEnqueue() {
    server.enqueue(
      MockResponse
        .Builder()
        .body("abc")
        .build(),
    )
    val call =
      client.newCallWithListener(
        Request
          .Builder()
          .url(server.url("/"))
          .build(),
      )
    val completionLatch = CountDownLatch(1)
    val callback: Callback =
      object : Callback {
        override fun onFailure(
          call: Call,
          e: IOException,
        ) {
          completionLatch.countDown()
        }

        override fun onResponse(
          call: Call,
          response: Response,
        ) {
          response.close()
          completionLatch.countDown()
        }
      }
    call.enqueue(callback)
    completionLatch.await()
    assertThat(eventRecorder.recordedEventTypes()).containsExactly(
      CallStart::class,
      ProxySelectStart::class,
      ProxySelectEnd::class,
      DnsStart::class,
      DnsEnd::class,
      ConnectStart::class,
      ConnectEnd::class,
      ConnectionAcquired::class,
      RequestHeadersStart::class,
      RequestHeadersEnd::class,
      ResponseHeadersStart::class,
      ResponseHeadersEnd::class,
      FollowUpDecision::class,
      ResponseBodyStart::class,
      ResponseBodyEnd::class,
      ConnectionReleased::class,
      CallEnd::class,
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
      client.newCallWithListener(
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
    assertThat(eventRecorder.recordedEventTypes()).containsExactly(
      CallStart::class,
      ProxySelectStart::class,
      ProxySelectEnd::class,
      DnsStart::class,
      DnsEnd::class,
      ConnectStart::class,
      ConnectEnd::class,
      ConnectionAcquired::class,
      RequestHeadersStart::class,
      RequestHeadersEnd::class,
      ResponseFailed::class,
      RetryDecision::class,
      ConnectionReleased::class,
      CallFailed::class,
    )
    assertThat(eventRecorder.findEvent<RetryDecision>()).all {
      prop(RetryDecision::retry).isFalse()
    }
  }

  @Test
  fun failedDribbledCallEventSequence() {
    server.enqueue(
      MockResponse
        .Builder()
        .body("0123456789")
        .throttleBody(2, 100, TimeUnit.MILLISECONDS)
        .onResponseBody(CloseSocket())
        .build(),
    )
    client =
      client
        .newBuilder()
        .protocols(listOf<Protocol>(Protocol.HTTP_1_1))
        .readTimeout(Duration.ofMillis(250))
        .build()
    val call =
      client.newCallWithListener(
        Request
          .Builder()
          .url(server.url("/"))
          .build(),
      )
    val response = call.execute()
    assertFailsWith<IOException> {
      response.body.string()
    }.also { expected ->
      assertThat(expected.message).isEqualTo("unexpected end of stream")
    }
    assertThat(eventRecorder.recordedEventTypes()).containsExactly(
      CallStart::class,
      ProxySelectStart::class,
      ProxySelectEnd::class,
      DnsStart::class,
      DnsEnd::class,
      ConnectStart::class,
      ConnectEnd::class,
      ConnectionAcquired::class,
      RequestHeadersStart::class,
      RequestHeadersEnd::class,
      ResponseHeadersStart::class,
      ResponseHeadersEnd::class,
      FollowUpDecision::class,
      ResponseBodyStart::class,
      ResponseFailed::class,
      ConnectionReleased::class,
      CallFailed::class,
    )
    val responseFailed = eventRecorder.removeUpToEvent<ResponseFailed>()
    assertThat(responseFailed.ioe.message).isEqualTo("unexpected end of stream")
  }

  @Test
  fun canceledCallEventSequence() {
    val call =
      client.newCallWithListener(
        Request
          .Builder()
          .url(server.url("/"))
          .build(),
      )
    call.cancel()
    assertFailsWith<IOException> {
      call.execute()
    }.also { expected ->
      assertThat(expected.message).isEqualTo("Canceled")
    }
    assertThat(eventRecorder.recordedEventTypes()).containsExactly(
      Canceled::class,
      CallStart::class,
      CallFailed::class,
    )
  }

  @Test
  fun cancelAsyncCall() {
    server.enqueue(
      MockResponse
        .Builder()
        .body("abc")
        .build(),
    )
    val call =
      client.newCallWithListener(
        Request
          .Builder()
          .url(server.url("/"))
          .build(),
      )
    call.enqueue(
      object : Callback {
        override fun onFailure(
          call: Call,
          e: IOException,
        ) {
        }

        override fun onResponse(
          call: Call,
          response: Response,
        ) {
          response.close()
        }
      },
    )
    call.cancel()
    assertThat(eventRecorder.recordedEventTypes()).contains(Canceled::class)
  }

  @Test
  fun multipleCancelsEmitsOnlyOneEvent() {
    server.enqueue(
      MockResponse
        .Builder()
        .body("abc")
        .build(),
    )
    val call =
      client.newCallWithListener(
        Request
          .Builder()
          .url(server.url("/"))
          .build(),
      )
    call.cancel()
    call.cancel()
    assertThat(eventRecorder.recordedEventTypes()).containsExactly(Canceled::class)
  }

  private fun assertSuccessfulEventOrder(
    responseMatcher: Matcher<Response?>?,
    emptyBody: Boolean = false,
  ) {
    val call =
      client.newCallWithListener(
        Request
          .Builder()
          .url(server.url("/"))
          .build(),
      )
    val response = call.execute()
    assertThat(response.code).isEqualTo(200)
    response.body.string()
    response.body.close()
    assumeThat(response, responseMatcher)
    var expectedEventTypes =
      listOf(
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
        ResponseHeadersStart::class,
        ResponseHeadersEnd::class,
      )
    expectedEventTypes +=
      when {
        emptyBody -> {
          listOf(
            ResponseBodyStart::class,
            ResponseBodyEnd::class,
            FollowUpDecision::class,
          )
        }

        else -> {
          listOf(
            FollowUpDecision::class,
            ResponseBodyStart::class,
            ResponseBodyEnd::class,
          )
        }
      }
    expectedEventTypes +=
      listOf(
        ConnectionReleased::class,
        CallEnd::class,
      )
    assertThat(eventRecorder.recordedEventTypes()).isEqualTo(expectedEventTypes)
  }

  @Test
  fun secondCallEventSequence() {
    enableTlsWithTunnel()
    server.protocols = Arrays.asList(Protocol.HTTP_2, Protocol.HTTP_1_1)
    server.enqueue(MockResponse())
    server.enqueue(MockResponse())
    client
      .newCallWithListener(
        Request
          .Builder()
          .url(server.url("/"))
          .build(),
      ).execute()
      .close()
    eventRecorder.removeUpToEvent<CallEnd>()
    val call =
      client.newCallWithListener(
        Request
          .Builder()
          .url(server.url("/"))
          .build(),
      )
    val response = call.execute()
    response.close()
    assertThat(eventRecorder.recordedEventTypes()).containsExactly(
      CallStart::class,
      ConnectionAcquired::class,
      RequestHeadersStart::class,
      RequestHeadersEnd::class,
      ResponseHeadersStart::class,
      ResponseHeadersEnd::class,
      ResponseBodyStart::class,
      ResponseBodyEnd::class,
      FollowUpDecision::class,
      ConnectionReleased::class,
      CallEnd::class,
    )
  }

  private fun assertBytesReadWritten(
    listener: EventRecorder,
    requestHeaderLength: Matcher<Long?>?,
    requestBodyBytes: Matcher<Long?>?,
    responseHeaderLength: Matcher<Long?>?,
    responseBodyBytes: Matcher<Long?>?,
  ) {
    if (requestHeaderLength != null) {
      val responseHeadersEnd = listener.removeUpToEvent<RequestHeadersEnd>()
      MatcherAssert.assertThat(
        "request header length",
        responseHeadersEnd.headerLength,
        requestHeaderLength,
      )
    } else {
      assertThat(listener.recordedEventTypes())
        .doesNotContain(RequestHeadersEnd::class)
    }
    if (requestBodyBytes != null) {
      val responseBodyEnd: RequestBodyEnd = listener.removeUpToEvent<RequestBodyEnd>()
      MatcherAssert.assertThat(
        "request body bytes",
        responseBodyEnd.bytesWritten,
        requestBodyBytes,
      )
    } else {
      assertThat(listener.recordedEventTypes()).doesNotContain(RequestBodyEnd::class)
    }
    if (responseHeaderLength != null) {
      val responseHeadersEnd: ResponseHeadersEnd =
        listener.removeUpToEvent<ResponseHeadersEnd>()
      MatcherAssert.assertThat(
        "response header length",
        responseHeadersEnd.headerLength,
        responseHeaderLength,
      )
    } else {
      assertThat(listener.recordedEventTypes())
        .doesNotContain(ResponseHeadersEnd::class)
    }
    if (responseBodyBytes != null) {
      val responseBodyEnd: ResponseBodyEnd = listener.removeUpToEvent<ResponseBodyEnd>()
      MatcherAssert.assertThat(
        "response body bytes",
        responseBodyEnd.bytesRead,
        responseBodyBytes,
      )
    } else {
      assertThat(listener.recordedEventTypes()).doesNotContain(ResponseBodyEnd::class)
    }
  }

  private fun greaterThan(value: Long): Matcher<Long?> =
    object : BaseMatcher<Long?>() {
      override fun describeTo(description: Description?) {
        description!!.appendText("> $value")
      }

      override fun matches(o: Any?): Boolean = (o as Long?)!! > value
    }

  private fun matchesProtocol(protocol: Protocol?): Matcher<Response?> =
    object : BaseMatcher<Response?>() {
      override fun describeTo(description: Description?) {
        description!!.appendText("is HTTP/2")
      }

      override fun matches(o: Any?): Boolean = (o as Response?)!!.protocol == protocol
    }

  @Test
  fun successfulEmptyH2CallEventSequence() {
    enableTlsWithTunnel()
    server.protocols = Arrays.asList(Protocol.HTTP_2, Protocol.HTTP_1_1)
    server.enqueue(MockResponse())
    assertSuccessfulEventOrder(matchesProtocol(Protocol.HTTP_2), emptyBody = true)
    assertBytesReadWritten(
      eventRecorder,
      CoreMatchers.any(Long::class.java),
      null,
      greaterThan(0L),
      CoreMatchers.equalTo(0L),
    )
  }

  @Test
  fun successfulEmptyHttpsCallEventSequence() {
    enableTlsWithTunnel()
    server.protocols = Arrays.asList(Protocol.HTTP_1_1)
    server.enqueue(
      MockResponse
        .Builder()
        .body("abc")
        .build(),
    )
    assertSuccessfulEventOrder(anyResponse)
    assertBytesReadWritten(
      eventRecorder,
      CoreMatchers.any(Long::class.java),
      null,
      greaterThan(0L),
      CoreMatchers.equalTo(3L),
    )
  }

  @Test
  fun successfulChunkedHttpsCallEventSequence() {
    enableTlsWithTunnel()
    server.protocols = Arrays.asList(Protocol.HTTP_1_1)
    server.enqueue(
      MockResponse
        .Builder()
        .bodyDelay(100, TimeUnit.MILLISECONDS)
        .chunkedBody("Hello!", 2)
        .build(),
    )
    assertSuccessfulEventOrder(anyResponse)
    assertBytesReadWritten(
      eventRecorder,
      CoreMatchers.any(Long::class.java),
      null,
      greaterThan(0L),
      CoreMatchers.equalTo(6L),
    )
  }

  @Test
  fun successfulChunkedH2CallEventSequence() {
    enableTlsWithTunnel()
    server.protocols = Arrays.asList(Protocol.HTTP_2, Protocol.HTTP_1_1)
    server.enqueue(
      MockResponse
        .Builder()
        .bodyDelay(100, TimeUnit.MILLISECONDS)
        .chunkedBody("Hello!", 2)
        .build(),
    )
    assertSuccessfulEventOrder(matchesProtocol(Protocol.HTTP_2))
    assertBytesReadWritten(
      eventRecorder,
      CoreMatchers.any(Long::class.java),
      null,
      CoreMatchers.equalTo(0L),
      greaterThan(6L),
    )
  }

  @Test
  fun successfulDnsLookup() {
    server.enqueue(MockResponse())
    val call =
      client.newCallWithListener(
        Request
          .Builder()
          .url(server.url("/"))
          .build(),
      )
    val response = call.execute()
    assertThat(response.code).isEqualTo(200)
    response.body.close()
    val dnsStart: DnsStart = eventRecorder.removeUpToEvent<DnsStart>()
    assertThat(dnsStart.call).isSameInstanceAs(call)
    assertThat(dnsStart.domainName).isEqualTo(server.hostName)
    val dnsEnd: DnsEnd = eventRecorder.removeUpToEvent<DnsEnd>()
    assertThat(dnsEnd.call).isSameInstanceAs(call)
    assertThat(dnsEnd.domainName).isEqualTo(server.hostName)
    assertThat(dnsEnd.inetAddressList.size).isEqualTo(1)
  }

  @Test
  fun noDnsLookupOnPooledConnection() {
    server.enqueue(MockResponse())
    server.enqueue(MockResponse())

    // Seed the pool.
    val call1 =
      client.newCallWithListener(
        Request
          .Builder()
          .url(server.url("/"))
          .build(),
      )
    val response1 = call1.execute()
    assertThat(response1.code).isEqualTo(200)
    response1.body.close()
    eventRecorder.clearAllEvents()
    val call2 =
      client.newCallWithListener(
        Request
          .Builder()
          .url(server.url("/"))
          .build(),
      )
    val response2 = call2.execute()
    assertThat(response2.code).isEqualTo(200)
    response2.body.close()
    val recordedEvents = eventRecorder.recordedEventTypes()
    assertThat(recordedEvents).doesNotContain(DnsStart::class)
    assertThat(recordedEvents).doesNotContain(DnsEnd::class)
  }

  @Test
  fun multipleDnsLookupsForSingleCall() {
    server.enqueue(
      MockResponse
        .Builder()
        .code(301)
        .setHeader("Location", "http://www.fakeurl:" + server.port)
        .build(),
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
      client.newCallWithListener(
        Request
          .Builder()
          .url("http://fakeurl:" + server.port)
          .build(),
      )
    val response = call.execute()
    assertThat(response.code).isEqualTo(200)
    response.body.close()
    eventRecorder.removeUpToEvent<DnsStart>()
    eventRecorder.removeUpToEvent<DnsEnd>()
    eventRecorder.removeUpToEvent<DnsStart>()
    eventRecorder.removeUpToEvent<DnsEnd>()
  }

  @Test
  fun failedDnsLookup() {
    client =
      client
        .newBuilder()
        .dns(FakeDns())
        .build()
    val call =
      client.newCallWithListener(
        Request
          .Builder()
          .url("http://fakeurl/")
          .build(),
      )
    assertFailsWith<IOException> {
      call.execute()
    }
    eventRecorder.removeUpToEvent<DnsStart>()
    val callFailed: CallFailed = eventRecorder.removeUpToEvent<CallFailed>()
    assertThat(callFailed.call).isSameInstanceAs(call)
    assertThat(callFailed.ioe).isInstanceOf<UnknownHostException>()
  }

  @Test
  fun emptyDnsLookup() {
    val emptyDns = Dns { listOf() }
    client =
      client
        .newBuilder()
        .dns(emptyDns)
        .build()
    val call =
      client.newCallWithListener(
        Request
          .Builder()
          .url("http://fakeurl/")
          .build(),
      )
    assertFailsWith<IOException> {
      call.execute()
    }
    eventRecorder.removeUpToEvent<DnsStart>()
    val callFailed: CallFailed = eventRecorder.removeUpToEvent<CallFailed>()
    assertThat(callFailed.call).isSameInstanceAs(call)
    assertThat(callFailed.ioe).isInstanceOf(
      UnknownHostException::class.java,
    )
  }

  @Test
  fun successfulConnect() {
    server.enqueue(MockResponse())
    val call =
      client.newCallWithListener(
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
    val connectStart = eventRecorder.removeUpToEvent<ConnectStart>()
    assertThat(connectStart.call).isSameInstanceAs(call)
    assertThat(connectStart.inetSocketAddress).isEqualTo(expectedAddress)
    assertThat(connectStart.proxy).isEqualTo(Proxy.NO_PROXY)
    val connectEnd = eventRecorder.removeUpToEvent<CallEvent.ConnectEnd>()
    assertThat(connectEnd.call).isSameInstanceAs(call)
    assertThat(connectEnd.inetSocketAddress).isEqualTo(expectedAddress)
    assertThat(connectEnd.protocol).isEqualTo(Protocol.HTTP_1_1)
  }

  @Test
  fun failedConnect() {
    enableTlsWithTunnel()
    server.enqueue(
      MockResponse
        .Builder()
        .failHandshake()
        .build(),
    )
    val call =
      client.newCallWithListener(
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
    val connectStart = eventRecorder.removeUpToEvent<ConnectStart>()
    assertThat(connectStart.call).isSameInstanceAs(call)
    assertThat(connectStart.inetSocketAddress).isEqualTo(expectedAddress)
    assertThat(connectStart.proxy).isEqualTo(Proxy.NO_PROXY)
    val connectFailed = eventRecorder.removeUpToEvent<ConnectFailed>()
    assertThat(connectFailed.call).isSameInstanceAs(call)
    assertThat(connectFailed.inetSocketAddress).isEqualTo(expectedAddress)
    assertThat(connectFailed.protocol).isNull()
    assertThat(connectFailed.ioe).isNotNull()
  }

  @Test
  fun multipleConnectsForSingleCall() {
    enableTlsWithTunnel()
    server.enqueue(
      MockResponse
        .Builder()
        .failHandshake()
        .build(),
    )
    server.enqueue(MockResponse())
    client =
      client
        .newBuilder()
        .dns(DoubleInetAddressDns())
        .build()
    val call =
      client.newCallWithListener(
        Request
          .Builder()
          .url(server.url("/"))
          .build(),
      )
    val response = call.execute()
    assertThat(response.code).isEqualTo(200)
    response.body.close()
    eventRecorder.removeUpToEvent<ConnectStart>()
    eventRecorder.removeUpToEvent<ConnectFailed>()
    eventRecorder.removeUpToEvent<ConnectStart>()
    eventRecorder.removeUpToEvent<ConnectEnd>()
  }

  @Test
  fun successfulHttpProxyConnect() {
    server.enqueue(MockResponse())
    client =
      client
        .newBuilder()
        .proxy(server.proxyAddress)
        .build()
    val call =
      client.newCallWithListener(
        Request
          .Builder()
          .url("http://www.fakeurl")
          .build(),
      )
    val response = call.execute()
    assertThat(response.code).isEqualTo(200)
    response.body.close()
    val address = client.dns.lookup(server.hostName)[0]
    val expectedAddress = InetSocketAddress(address, server.port)
    val connectStart: ConnectStart =
      eventRecorder.removeUpToEvent<ConnectStart>()
    assertThat(connectStart.call).isSameInstanceAs(call)
    assertThat(connectStart.inetSocketAddress).isEqualTo(expectedAddress)
    assertThat(connectStart.proxy).isEqualTo(
      server.proxyAddress,
    )
    val connectEnd = eventRecorder.removeUpToEvent<ConnectEnd>()
    assertThat(connectEnd.call).isSameInstanceAs(call)
    assertThat(connectEnd.inetSocketAddress).isEqualTo(expectedAddress)
    assertThat(connectEnd.protocol).isEqualTo(Protocol.HTTP_1_1)
  }

  @Test
  fun successfulSocksProxyConnect() {
    server.enqueue(MockResponse())
    socksProxy = SocksProxy()
    socksProxy!!.play()
    val proxy = socksProxy!!.proxy()
    client =
      client
        .newBuilder()
        .proxy(proxy)
        .build()
    val call =
      client.newCallWithListener(
        Request
          .Builder()
          .url("http://" + SocksProxy.HOSTNAME_THAT_ONLY_THE_PROXY_KNOWS + ":" + server.port)
          .build(),
      )
    val response = call.execute()
    assertThat(response.code).isEqualTo(200)
    response.body.close()
    val expectedAddress =
      InetSocketAddress.createUnresolved(
        SocksProxy.HOSTNAME_THAT_ONLY_THE_PROXY_KNOWS,
        server.port,
      )
    val connectStart = eventRecorder.removeUpToEvent<ConnectStart>()
    assertThat(connectStart.call).isSameInstanceAs(call)
    assertThat(connectStart.inetSocketAddress).isEqualTo(expectedAddress)
    assertThat(connectStart.proxy).isEqualTo(proxy)
    val connectEnd = eventRecorder.removeUpToEvent<ConnectEnd>()
    assertThat(connectEnd.call).isSameInstanceAs(call)
    assertThat(connectEnd.inetSocketAddress).isEqualTo(expectedAddress)
    assertThat(connectEnd.protocol).isEqualTo(Protocol.HTTP_1_1)
  }

  @Test
  fun authenticatingTunnelProxyConnect() {
    enableTlsWithTunnel()
    server.enqueue(
      MockResponse
        .Builder()
        .inTunnel()
        .code(407)
        .addHeader("Proxy-Authenticate: Basic realm=\"localhost\"")
        .addHeader("Connection: close")
        .build(),
    )
    server.enqueue(
      MockResponse
        .Builder()
        .inTunnel()
        .build(),
    )
    server.enqueue(MockResponse())
    client =
      client
        .newBuilder()
        .proxy(server.proxyAddress)
        .proxyAuthenticator(RecordingOkAuthenticator("password", "Basic"))
        .build()
    val call =
      client.newCallWithListener(
        Request
          .Builder()
          .url(server.url("/"))
          .build(),
      )
    val response = call.execute()
    assertThat(response.code).isEqualTo(200)
    response.body.close()
    eventRecorder.removeUpToEvent<ConnectStart>()
    val connectEnd = eventRecorder.removeUpToEvent<ConnectEnd>()
    assertThat(connectEnd.protocol).isNull()
    eventRecorder.removeUpToEvent<ConnectStart>()
    eventRecorder.removeUpToEvent<ConnectEnd>()
  }

  @Test
  fun successfulSecureConnect() {
    enableTlsWithTunnel()
    server.enqueue(MockResponse())
    val call =
      client.newCallWithListener(
        Request
          .Builder()
          .url(server.url("/"))
          .build(),
      )
    val response = call.execute()
    assertThat(response.code).isEqualTo(200)
    response.body.close()
    val secureStart = eventRecorder.removeUpToEvent<SecureConnectStart>()
    assertThat(secureStart.call).isSameInstanceAs(call)
    val secureEnd = eventRecorder.removeUpToEvent<SecureConnectEnd>()
    assertThat(secureEnd.call).isSameInstanceAs(call)
    assertThat(secureEnd.handshake).isNotNull()
  }

  @Test
  fun failedSecureConnect() {
    enableTlsWithTunnel()
    server.enqueue(
      MockResponse
        .Builder()
        .failHandshake()
        .build(),
    )
    val call =
      client.newCallWithListener(
        Request
          .Builder()
          .url(server.url("/"))
          .build(),
      )
    assertFailsWith<IOException> {
      call.execute()
    }
    val secureStart = eventRecorder.removeUpToEvent<SecureConnectStart>()
    assertThat(secureStart.call).isSameInstanceAs(call)
    val callFailed = eventRecorder.removeUpToEvent<CallFailed>()
    assertThat(callFailed.call).isSameInstanceAs(call)
    assertThat(callFailed.ioe).isNotNull()
  }

  @Test
  fun secureConnectWithTunnel() {
    enableTlsWithTunnel()
    server.enqueue(
      MockResponse
        .Builder()
        .inTunnel()
        .build(),
    )
    server.enqueue(MockResponse())
    client =
      client
        .newBuilder()
        .proxy(server.proxyAddress)
        .build()
    val call =
      client.newCallWithListener(
        Request
          .Builder()
          .url(server.url("/"))
          .build(),
      )
    val response = call.execute()
    assertThat(response.code).isEqualTo(200)
    response.body.close()
    val secureStart = eventRecorder.removeUpToEvent<SecureConnectStart>()
    assertThat(secureStart.call).isSameInstanceAs(call)
    val secureEnd = eventRecorder.removeUpToEvent<SecureConnectEnd>()
    assertThat(secureEnd.call).isSameInstanceAs(call)
    assertThat(secureEnd.handshake).isNotNull()
  }

  @Test
  fun multipleSecureConnectsForSingleCall() {
    enableTlsWithTunnel()
    server.enqueue(
      MockResponse
        .Builder()
        .failHandshake()
        .build(),
    )
    server.enqueue(MockResponse())
    client =
      client
        .newBuilder()
        .dns(DoubleInetAddressDns())
        .build()
    val call =
      client.newCallWithListener(
        Request
          .Builder()
          .url(server.url("/"))
          .build(),
      )
    val response = call.execute()
    assertThat(response.code).isEqualTo(200)
    response.body.close()
    eventRecorder.removeUpToEvent<SecureConnectStart>()
    eventRecorder.removeUpToEvent<ConnectFailed>()
    eventRecorder.removeUpToEvent<SecureConnectStart>()
    eventRecorder.removeUpToEvent<SecureConnectEnd>()
  }

  @Test
  fun noSecureConnectsOnPooledConnection() {
    enableTlsWithTunnel()
    server.enqueue(MockResponse())
    server.enqueue(MockResponse())
    client =
      client
        .newBuilder()
        .dns(DoubleInetAddressDns())
        .build()

    // Seed the pool.
    val call1 =
      client.newCallWithListener(
        Request
          .Builder()
          .url(server.url("/"))
          .build(),
      )
    val response1 = call1.execute()
    assertThat(response1.code).isEqualTo(200)
    response1.body.close()
    eventRecorder.clearAllEvents()
    val call2 =
      client.newCallWithListener(
        Request
          .Builder()
          .url(server.url("/"))
          .build(),
      )
    val response2 = call2.execute()
    assertThat(response2.code).isEqualTo(200)
    response2.body.close()
    val recordedEvents = eventRecorder.recordedEventTypes()
    assertThat(recordedEvents).doesNotContain(SecureConnectStart::class)
    assertThat(recordedEvents).doesNotContain(SecureConnectEnd::class)
  }

  @Test
  fun successfulConnectionFound() {
    server.enqueue(MockResponse())
    val call =
      client.newCallWithListener(
        Request
          .Builder()
          .url(server.url("/"))
          .build(),
      )
    val response = call.execute()
    assertThat(response.code).isEqualTo(200)
    response.body.close()
    val connectionAcquired = eventRecorder.removeUpToEvent<ConnectionAcquired>()
    assertThat(connectionAcquired.call).isSameInstanceAs(call)
    assertThat(connectionAcquired.connection).isNotNull()
  }

  @Test
  fun noConnectionFoundOnFollowUp() {
    server.enqueue(
      MockResponse
        .Builder()
        .code(301)
        .addHeader("Location", "/foo")
        .build(),
    )
    server.enqueue(
      MockResponse
        .Builder()
        .body("ABC")
        .build(),
    )
    val call =
      client.newCallWithListener(
        Request
          .Builder()
          .url(server.url("/"))
          .build(),
      )
    val response = call.execute()
    assertThat(response.body.string()).isEqualTo("ABC")
    eventRecorder.removeUpToEvent<ConnectionAcquired>()
    val remainingEvents = eventRecorder.recordedEventTypes()
    assertThat(remainingEvents).doesNotContain(ConnectionAcquired::class)
  }

  @Test
  fun pooledConnectionFound() {
    server.enqueue(MockResponse())
    server.enqueue(MockResponse())

    // Seed the pool.
    val call1 =
      client.newCallWithListener(
        Request
          .Builder()
          .url(server.url("/"))
          .build(),
      )
    val response1 = call1.execute()
    assertThat(response1.code).isEqualTo(200)
    response1.body.close()
    val connectionAcquired1 = eventRecorder.removeUpToEvent<ConnectionAcquired>()
    eventRecorder.clearAllEvents()
    val call2 =
      client.newCallWithListener(
        Request
          .Builder()
          .url(server.url("/"))
          .build(),
      )
    val response2 = call2.execute()
    assertThat(response2.code).isEqualTo(200)
    response2.body.close()
    val connectionAcquired2 = eventRecorder.removeUpToEvent<ConnectionAcquired>()
    assertThat(connectionAcquired2.connection).isSameInstanceAs(
      connectionAcquired1.connection,
    )
  }

  @Test
  fun multipleConnectionsFoundForSingleCall() {
    server.enqueue(
      MockResponse
        .Builder()
        .code(301)
        .addHeader("Location", "/foo")
        .addHeader("Connection", "Close")
        .build(),
    )
    server.enqueue(
      MockResponse
        .Builder()
        .body("ABC")
        .build(),
    )
    val call =
      client.newCallWithListener(
        Request
          .Builder()
          .url(server.url("/"))
          .build(),
      )
    val response = call.execute()
    assertThat(response.body.string()).isEqualTo("ABC")
    eventRecorder.removeUpToEvent<ConnectionAcquired>()
    eventRecorder.removeUpToEvent<ConnectionAcquired>()
  }

  @Test
  fun responseBodyFailHttp1OverHttps() {
    enableTlsWithTunnel()
    server.protocols = Arrays.asList(Protocol.HTTP_1_1)
    responseBodyFail(Protocol.HTTP_1_1)
  }

  @Test
  fun responseBodyFailHttp2OverHttps() {
    platform.assumeHttp2Support()
    enableTlsWithTunnel()
    server.protocols = Arrays.asList(Protocol.HTTP_2, Protocol.HTTP_1_1)
    responseBodyFail(Protocol.HTTP_2)
  }

  @Test
  fun responseBodyFailHttp() {
    responseBodyFail(Protocol.HTTP_1_1)
  }

  private fun responseBodyFail(expectedProtocol: Protocol?) {
    // Use a 2 MiB body so the disconnect won't happen until the client has read some data.
    val responseBodySize = 2 * 1024 * 1024 // 2 MiB
    server.enqueue(
      MockResponse
        .Builder()
        .body(Buffer().write(ByteArray(responseBodySize)))
        .onResponseBody(CloseSocket())
        .build(),
    )
    val call =
      client.newCallWithListener(
        Request
          .Builder()
          .url(server.url("/"))
          .build(),
      )
    val response = call.execute()
    if (expectedProtocol == Protocol.HTTP_2) {
      // soft failure since client may not support depending on Platform
      assumeThat(response, matchesProtocol(Protocol.HTTP_2))
    }
    assertThat(response.protocol).isEqualTo(expectedProtocol)
    assertFailsWith<IOException> {
      response.body.string()
    }
    val callFailed = eventRecorder.removeUpToEvent<CallFailed>()
    assertThat(callFailed.ioe).isNotNull()
  }

  @Test
  fun emptyResponseBody() {
    server.enqueue(
      MockResponse
        .Builder()
        .body("")
        .bodyDelay(1, TimeUnit.SECONDS)
        .onResponseBody(CloseSocket())
        .build(),
    )
    val call =
      client.newCallWithListener(
        Request
          .Builder()
          .url(server.url("/"))
          .build(),
      )
    val response = call.execute()
    response.body.close()
    assertThat(eventRecorder.recordedEventTypes()).containsExactly(
      CallStart::class,
      ProxySelectStart::class,
      ProxySelectEnd::class,
      DnsStart::class,
      DnsEnd::class,
      ConnectStart::class,
      ConnectEnd::class,
      ConnectionAcquired::class,
      RequestHeadersStart::class,
      RequestHeadersEnd::class,
      ResponseHeadersStart::class,
      ResponseHeadersEnd::class,
      ResponseBodyStart::class,
      ResponseBodyEnd::class,
      FollowUpDecision::class,
      ConnectionReleased::class,
      CallEnd::class,
    )
  }

  @Test
  fun emptyResponseBodyConnectionClose() {
    server.enqueue(
      MockResponse
        .Builder()
        .addHeader("Connection", "close")
        .body("")
        .build(),
    )
    val call =
      client.newCallWithListener(
        Request
          .Builder()
          .url(server.url("/"))
          .build(),
      )
    val response = call.execute()
    response.body.close()
    assertThat(eventRecorder.recordedEventTypes()).containsExactly(
      CallStart::class,
      ProxySelectStart::class,
      ProxySelectEnd::class,
      DnsStart::class,
      DnsEnd::class,
      ConnectStart::class,
      ConnectEnd::class,
      ConnectionAcquired::class,
      RequestHeadersStart::class,
      RequestHeadersEnd::class,
      ResponseHeadersStart::class,
      ResponseHeadersEnd::class,
      ResponseBodyStart::class,
      ResponseBodyEnd::class,
      FollowUpDecision::class,
      ConnectionReleased::class,
      CallEnd::class,
    )
  }

  @Test
  fun responseBodyClosedClosedWithoutReadingAllData() {
    server.enqueue(
      MockResponse
        .Builder()
        .body("abc")
        .bodyDelay(1, TimeUnit.SECONDS)
        .onResponseBody(CloseSocket())
        .build(),
    )
    val call =
      client.newCallWithListener(
        Request
          .Builder()
          .url(server.url("/"))
          .build(),
      )
    val response = call.execute()
    response.body.close()
    assertThat(eventRecorder.recordedEventTypes()).containsExactly(
      CallStart::class,
      ProxySelectStart::class,
      ProxySelectEnd::class,
      DnsStart::class,
      DnsEnd::class,
      ConnectStart::class,
      ConnectEnd::class,
      ConnectionAcquired::class,
      RequestHeadersStart::class,
      RequestHeadersEnd::class,
      ResponseHeadersStart::class,
      ResponseHeadersEnd::class,
      FollowUpDecision::class,
      ResponseBodyStart::class,
      ResponseBodyEnd::class,
      ConnectionReleased::class,
      CallEnd::class,
    )
  }

  @Test
  fun requestBodyFailHttp1OverHttps() {
    enableTlsWithTunnel()
    server.protocols = Arrays.asList(Protocol.HTTP_1_1)
    requestBodyFail(Protocol.HTTP_1_1)
  }

  @Test
  fun requestBodyFailHttp2OverHttps() {
    platform.assumeHttp2Support()
    enableTlsWithTunnel()
    server.protocols = Arrays.asList(Protocol.HTTP_2, Protocol.HTTP_1_1)
    requestBodyFail(Protocol.HTTP_2)
  }

  @Test
  fun requestBodyFailHttp() {
    requestBodyFail(null)
  }

  private fun requestBodyFail(expectedProtocol: Protocol?) {
    server.enqueue(
      MockResponse
        .Builder()
        .onRequestBody(CloseSocket())
        .build(),
    )
    val request = NonCompletingRequestBody()
    val call =
      client.newCallWithListener(
        Request
          .Builder()
          .url(server.url("/"))
          .post(request)
          .build(),
      )
    assertFailsWith<IOException> {
      call.execute()
    }
    if (expectedProtocol != null) {
      val connectionAcquired = eventRecorder.removeUpToEvent<ConnectionAcquired>()
      assertThat(connectionAcquired.connection.protocol())
        .isEqualTo(expectedProtocol)
    }
    val callFailed = eventRecorder.removeUpToEvent<CallFailed>()
    assertThat(callFailed.ioe).isNotNull()
    assertThat(request.ioe).isNotNull()
  }

  private inner class NonCompletingRequestBody : RequestBody() {
    private val chunk: ByteArray? = ByteArray(1024 * 1024)
    var ioe: IOException? = null

    override fun contentType(): MediaType? = "text/plain".toMediaType()

    override fun contentLength(): Long = chunk!!.size * 8L

    override fun writeTo(sink: BufferedSink) {
      try {
        var i = 0
        while (i < contentLength()) {
          sink.write(chunk!!)
          sink.flush()
          Thread.sleep(100)
          i += chunk.size
        }
      } catch (e: IOException) {
        ioe = e
      } catch (e: InterruptedException) {
        throw RuntimeException(e)
      }
    }
  }

  @Test
  fun requestBodyMultipleFailuresReportedOnlyOnce() {
    val requestBody: RequestBody =
      object : RequestBody() {
        override fun contentType() = "text/plain".toMediaType()

        override fun contentLength(): Long = 1024 * 1024 * 256

        override fun writeTo(sink: BufferedSink) {
          var failureCount = 0
          for (i in 0..1023) {
            try {
              sink.write(ByteArray(1024 * 256))
              sink.flush()
            } catch (e: IOException) {
              failureCount++
              if (failureCount == 3) throw e
            }
          }
        }
      }
    server.enqueue(
      MockResponse
        .Builder()
        .onRequestBody(CloseSocket())
        .build(),
    )
    val call =
      client.newCallWithListener(
        Request
          .Builder()
          .url(server.url("/"))
          .post(requestBody)
          .build(),
      )
    assertFailsWith<IOException> {
      call.execute()
    }
    assertThat(eventRecorder.recordedEventTypes()).containsExactly(
      CallStart::class,
      ProxySelectStart::class,
      ProxySelectEnd::class,
      DnsStart::class,
      DnsEnd::class,
      ConnectStart::class,
      ConnectEnd::class,
      ConnectionAcquired::class,
      RequestHeadersStart::class,
      RequestHeadersEnd::class,
      RequestBodyStart::class,
      RequestFailed::class,
      ResponseFailed::class,
      RetryDecision::class,
      ConnectionReleased::class,
      CallFailed::class,
    )
  }

  @Test
  fun requestBodySuccessHttp1OverHttps() {
    enableTlsWithTunnel()
    server.protocols = Arrays.asList(Protocol.HTTP_1_1)
    requestBodySuccess(
      "Hello".toRequestBody("text/plain".toMediaType()),
      CoreMatchers.equalTo(5L),
      CoreMatchers.equalTo(19L),
    )
  }

  @Test
  fun requestBodySuccessHttp2OverHttps() {
    platform.assumeHttp2Support()
    enableTlsWithTunnel()
    server.protocols = Arrays.asList(Protocol.HTTP_2, Protocol.HTTP_1_1)
    requestBodySuccess(
      "Hello".toRequestBody("text/plain".toMediaType()),
      CoreMatchers.equalTo(5L),
      CoreMatchers.equalTo(19L),
    )
  }

  @Test
  fun requestBodySuccessHttp() {
    requestBodySuccess(
      "Hello".toRequestBody("text/plain".toMediaType()),
      CoreMatchers.equalTo(5L),
      CoreMatchers.equalTo(19L),
    )
  }

  @Test
  fun requestBodySuccessStreaming() {
    val requestBody: RequestBody =
      object : RequestBody() {
        override fun contentType() = "text/plain".toMediaType()

        override fun writeTo(sink: BufferedSink) {
          sink.write(ByteArray(8192))
          sink.flush()
        }
      }
    requestBodySuccess(requestBody, CoreMatchers.equalTo(8192L), CoreMatchers.equalTo(19L))
  }

  @Test
  fun requestBodySuccessEmpty() {
    requestBodySuccess(
      "".toRequestBody("text/plain".toMediaType()),
      CoreMatchers.equalTo(0L),
      CoreMatchers.equalTo(19L),
    )
  }

  @Test
  fun successfulCallEventSequenceWithListener() {
    server.enqueue(
      MockResponse
        .Builder()
        .body("abc")
        .build(),
    )
    client =
      client
        .newBuilder()
        .addNetworkInterceptor(
          HttpLoggingInterceptor()
            .setLevel(HttpLoggingInterceptor.Level.BODY),
        ).build()
    val call =
      client.newCallWithListener(
        Request
          .Builder()
          .url(server.url("/"))
          .build(),
      )
    val response = call.execute()
    assertThat(response.code).isEqualTo(200)
    assertThat(response.body.string()).isEqualTo("abc")
    response.body.close()
    assertThat(eventRecorder.recordedEventTypes()).containsExactly(
      CallStart::class,
      ProxySelectStart::class,
      ProxySelectEnd::class,
      DnsStart::class,
      DnsEnd::class,
      ConnectStart::class,
      ConnectEnd::class,
      ConnectionAcquired::class,
      RequestHeadersStart::class,
      RequestHeadersEnd::class,
      ResponseHeadersStart::class,
      ResponseHeadersEnd::class,
      ResponseBodyStart::class,
      ResponseBodyEnd::class,
      FollowUpDecision::class,
      ConnectionReleased::class,
      CallEnd::class,
    )
  }

  private fun requestBodySuccess(
    body: RequestBody?,
    requestBodyBytes: Matcher<Long?>?,
    responseHeaderLength: Matcher<Long?>?,
  ) {
    server.enqueue(
      MockResponse
        .Builder()
        .code(200)
        .body("World!")
        .build(),
    )
    val call =
      client.newCallWithListener(
        Request
          .Builder()
          .url(server.url("/"))
          .post(body!!)
          .build(),
      )
    val response = call.execute()
    assertThat(response.body.string()).isEqualTo("World!")
    assertBytesReadWritten(
      eventRecorder,
      CoreMatchers.any(Long::class.java),
      requestBodyBytes,
      responseHeaderLength,
      CoreMatchers.equalTo(6L),
    )
  }

  @Test
  fun timeToFirstByteHttp1OverHttps() {
    enableTlsWithTunnel()
    server.protocols = Arrays.asList(Protocol.HTTP_1_1)
    timeToFirstByte()
  }

  @Test
  fun timeToFirstByteHttp2OverHttps() {
    platform.assumeHttp2Support()
    enableTlsWithTunnel()
    server.protocols = Arrays.asList(Protocol.HTTP_2, Protocol.HTTP_1_1)
    timeToFirstByte()
  }

  /**
   * Test to confirm that events are reported at the time they occur and no earlier and no later.
   * This inserts a bunch of synthetic 250 ms delays into both client and server and confirms that
   * the same delays make it back into the events.
   *
   * We've had bugs where we report an event when we request data rather than when the data actually
   * arrives. https://github.com/lysine-dev/okhttp/issues/5578
   */
  private fun timeToFirstByte() {
    val applicationInterceptorDelay = 250L
    val networkInterceptorDelay = 250L
    val requestBodyDelay = 250L
    val responseHeadersStartDelay = 250L
    val responseBodyStartDelay = 250L
    val responseBodyEndDelay = 250L

    // Warm up the client so the timing part of the test gets a pooled connection.
    server.enqueue(MockResponse())
    val warmUpCall =
      client.newCallWithListener(
        Request
          .Builder()
          .url(server.url("/"))
          .build(),
      )
    warmUpCall.execute().use { warmUpResponse -> warmUpResponse.body.string() }
    eventRecorder.clearAllEvents()

    // Create a client with artificial delays.
    client =
      client
        .newBuilder()
        .addInterceptor(
          Interceptor { chain: Interceptor.Chain ->
            try {
              Thread.sleep(applicationInterceptorDelay)
              return@Interceptor chain.proceed(chain.request())
            } catch (e: InterruptedException) {
              throw InterruptedIOException()
            }
          },
        ).addNetworkInterceptor(
          Interceptor { chain: Interceptor.Chain ->
            try {
              Thread.sleep(networkInterceptorDelay)
              return@Interceptor chain.proceed(chain.request())
            } catch (e: InterruptedException) {
              throw InterruptedIOException()
            }
          },
        ).build()

    // Create a request body with artificial delays.
    val call =
      client.newCallWithListener(
        Request
          .Builder()
          .url(server.url("/"))
          .post(
            object : RequestBody() {
              override fun contentType(): MediaType? = null

              override fun writeTo(sink: BufferedSink) {
                try {
                  Thread.sleep(requestBodyDelay)
                  sink.writeUtf8("abc")
                } catch (e: InterruptedException) {
                  throw InterruptedIOException()
                }
              }
            },
          ).build(),
      )

    // Create a response with artificial delays.
    server.enqueue(
      MockResponse
        .Builder()
        .headersDelay(responseHeadersStartDelay, TimeUnit.MILLISECONDS)
        .bodyDelay(responseBodyStartDelay, TimeUnit.MILLISECONDS)
        .throttleBody(5, responseBodyEndDelay, TimeUnit.MILLISECONDS)
        .body("fghijk")
        .build(),
    )
    call.execute().use { response ->
      assertThat(response.body.string()).isEqualTo("fghijk")
    }

    // Confirm the events occur when expected.
    eventRecorder.takeEvent(CallStart::class.java, 0L)
    eventRecorder.takeEvent(ConnectionAcquired::class.java, applicationInterceptorDelay)
    eventRecorder.takeEvent(RequestHeadersStart::class.java, networkInterceptorDelay)
    eventRecorder.takeEvent(RequestHeadersEnd::class.java, 0L)
    eventRecorder.takeEvent(RequestBodyStart::class.java, 0L)
    eventRecorder.takeEvent(RequestBodyEnd::class.java, requestBodyDelay)
    eventRecorder.takeEvent(ResponseHeadersStart::class.java, responseHeadersStartDelay)
    eventRecorder.takeEvent(ResponseHeadersEnd::class.java, 0L)
    eventRecorder.takeEvent(FollowUpDecision::class.java, 0L)
    eventRecorder.takeEvent(ResponseBodyStart::class.java, responseBodyStartDelay)
    eventRecorder.takeEvent(ResponseBodyEnd::class.java, responseBodyEndDelay)
    eventRecorder.takeEvent(ConnectionReleased::class.java, 0L)
    eventRecorder.takeEvent(CallEnd::class.java, 0L)
  }

  private fun enableTlsWithTunnel() {
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

  @Test
  fun redirectUsingSameConnectionEventSequence() {
    server.enqueue(
      MockResponse
        .Builder()
        .code(HttpURLConnection.HTTP_MOVED_TEMP)
        .addHeader("Location: /foo")
        .build(),
    )
    server.enqueue(MockResponse())
    val call = client.newCallWithListener(Request.Builder().url(server.url("/")).build())
    call.execute()
    assertThat(eventRecorder.recordedEventTypes()).containsExactly(
      CallStart::class,
      ProxySelectStart::class,
      ProxySelectEnd::class,
      DnsStart::class,
      DnsEnd::class,
      ConnectStart::class,
      ConnectEnd::class,
      ConnectionAcquired::class,
      RequestHeadersStart::class,
      RequestHeadersEnd::class,
      ResponseHeadersStart::class,
      ResponseHeadersEnd::class,
      ResponseBodyStart::class,
      ResponseBodyEnd::class,
      FollowUpDecision::class,
      RequestHeadersStart::class,
      RequestHeadersEnd::class,
      ResponseHeadersStart::class,
      ResponseHeadersEnd::class,
      ResponseBodyStart::class,
      ResponseBodyEnd::class,
      FollowUpDecision::class,
      ConnectionReleased::class,
      CallEnd::class,
    )
    assertThat(eventRecorder.findEvent<FollowUpDecision>()).all {
      prop(FollowUpDecision::nextRequest).isNotNull()
    }
  }

  @Test
  fun redirectUsingNewConnectionEventSequence() {
    val otherServer = MockWebServer()
    otherServer.start()
    server.enqueue(
      MockResponse
        .Builder()
        .code(HttpURLConnection.HTTP_MOVED_TEMP)
        .addHeader("Location: " + otherServer.url("/foo"))
        .build(),
    )
    otherServer.enqueue(MockResponse())
    val call = client.newCallWithListener(Request.Builder().url(server.url("/")).build())
    call.execute()
    assertThat(eventRecorder.recordedEventTypes()).containsExactly(
      CallStart::class,
      ProxySelectStart::class,
      ProxySelectEnd::class,
      DnsStart::class,
      DnsEnd::class,
      ConnectStart::class,
      ConnectEnd::class,
      ConnectionAcquired::class,
      RequestHeadersStart::class,
      RequestHeadersEnd::class,
      ResponseHeadersStart::class,
      ResponseHeadersEnd::class,
      ResponseBodyStart::class,
      ResponseBodyEnd::class,
      FollowUpDecision::class,
      ConnectionReleased::class,
      ProxySelectStart::class,
      ProxySelectEnd::class,
      DnsStart::class,
      DnsEnd::class,
      ConnectStart::class,
      ConnectEnd::class,
      ConnectionAcquired::class,
      RequestHeadersStart::class,
      RequestHeadersEnd::class,
      ResponseHeadersStart::class,
      ResponseHeadersEnd::class,
      ResponseBodyStart::class,
      ResponseBodyEnd::class,
      FollowUpDecision::class,
      ConnectionReleased::class,
      CallEnd::class,
    )
    assertThat(eventRecorder.findEvent<FollowUpDecision>()).all {
      prop(FollowUpDecision::nextRequest).isNotNull()
    }
    otherServer.close()
  }

  @Test
  fun applicationInterceptorProceedsMultipleTimes() {
    server.enqueue(MockResponse.Builder().body("a").build())
    server.enqueue(MockResponse.Builder().body("b").build())
    client =
      client
        .newBuilder()
        .addInterceptor(
          Interceptor { chain: Interceptor.Chain? ->
            chain!!
              .proceed(chain.request())
              .use { a -> assertThat(a.body.string()).isEqualTo("a") }
            chain.proceed(chain.request())
          },
        ).build()
    val call = client.newCallWithListener(Request.Builder().url(server.url("/")).build())
    val response = call.execute()
    assertThat(response.body.string()).isEqualTo("b")
    assertThat(eventRecorder.recordedEventTypes()).containsExactly(
      CallStart::class,
      ProxySelectStart::class,
      ProxySelectEnd::class,
      DnsStart::class,
      DnsEnd::class,
      ConnectStart::class,
      ConnectEnd::class,
      ConnectionAcquired::class,
      RequestHeadersStart::class,
      RequestHeadersEnd::class,
      ResponseHeadersStart::class,
      ResponseHeadersEnd::class,
      FollowUpDecision::class,
      ResponseBodyStart::class,
      ResponseBodyEnd::class,
      RequestHeadersStart::class,
      RequestHeadersEnd::class,
      ResponseHeadersStart::class,
      ResponseHeadersEnd::class,
      FollowUpDecision::class,
      ResponseBodyStart::class,
      ResponseBodyEnd::class,
      ConnectionReleased::class,
      CallEnd::class,
    )
    assertThat(server.takeRequest().exchangeIndex).isEqualTo(0)
    assertThat(server.takeRequest().exchangeIndex).isEqualTo(1)
  }

  @Test
  fun applicationInterceptorShortCircuit() {
    client =
      client
        .newBuilder()
        .addInterceptor(
          Interceptor { chain: Interceptor.Chain? ->
            Response
              .Builder()
              .request(chain!!.request())
              .protocol(Protocol.HTTP_1_1)
              .code(200)
              .message("OK")
              .body("a".toResponseBody(null))
              .build()
          },
        ).build()
    val call = client.newCallWithListener(Request.Builder().url(server.url("/")).build())
    val response = call.execute()
    assertThat(response.body.string()).isEqualTo("a")
    assertThat(eventRecorder.recordedEventTypes())
      .containsExactly(CallStart::class, CallEnd::class)
  }

  /** Response headers start, then the entire request body, then response headers end.  */
  @Test
  fun expectContinueStartsResponseHeadersEarly() {
    server.enqueue(
      MockResponse
        .Builder()
        .add100Continue()
        .build(),
    )
    val request =
      Request
        .Builder()
        .url(server.url("/"))
        .header("Expect", "100-continue")
        .post("abc".toRequestBody("text/plain".toMediaType()))
        .build()
    val call = client.newCallWithListener(request)
    call.execute()
    assertThat(eventRecorder.recordedEventTypes()).containsExactly(
      CallStart::class,
      ProxySelectStart::class,
      ProxySelectEnd::class,
      DnsStart::class,
      DnsEnd::class,
      ConnectStart::class,
      ConnectEnd::class,
      ConnectionAcquired::class,
      RequestHeadersStart::class,
      RequestHeadersEnd::class,
      ResponseHeadersStart::class,
      RequestBodyStart::class,
      RequestBodyEnd::class,
      ResponseHeadersEnd::class,
      ResponseBodyStart::class,
      ResponseBodyEnd::class,
      FollowUpDecision::class,
      ConnectionReleased::class,
      CallEnd::class,
    )
  }

  @Test
  fun timeToFirstByteGapBetweenResponseHeaderStartAndEnd() {
    val responseHeadersStartDelay = 250L
    server.enqueue(
      MockResponse
        .Builder()
        .add100Continue()
        .headersDelay(responseHeadersStartDelay, TimeUnit.MILLISECONDS)
        .build(),
    )
    val request =
      Request
        .Builder()
        .url(server.url("/"))
        .header("Expect", "100-continue")
        .post("abc".toRequestBody("text/plain".toMediaType()))
        .build()
    val call = client.newCallWithListener(request)
    call
      .execute()
      .use { response -> assertThat(response.body.string()).isEqualTo("") }
    eventRecorder.removeUpToEvent<ResponseHeadersStart>()
    eventRecorder.takeEvent(RequestBodyStart::class.java, 0L)
    eventRecorder.takeEvent(RequestBodyEnd::class.java, 0L)
    eventRecorder.takeEvent(ResponseHeadersEnd::class.java, responseHeadersStartDelay)
  }

  @Test
  fun cacheMiss() {
    enableCache()
    server.enqueue(
      MockResponse
        .Builder()
        .body("abc")
        .build(),
    )
    val call =
      client.newCallWithListener(
        Request
          .Builder()
          .url(server.url("/"))
          .build(),
      )
    val response = call.execute()
    assertThat(response.code).isEqualTo(200)
    assertThat(response.body.string()).isEqualTo("abc")
    response.close()
    assertThat(eventRecorder.recordedEventTypes()).containsExactly(
      CallStart::class,
      CacheMiss::class,
      ProxySelectStart::class,
      ProxySelectEnd::class,
      DnsStart::class,
      DnsEnd::class,
      ConnectStart::class,
      ConnectEnd::class,
      ConnectionAcquired::class,
      RequestHeadersStart::class,
      RequestHeadersEnd::class,
      ResponseHeadersStart::class,
      ResponseHeadersEnd::class,
      FollowUpDecision::class,
      ResponseBodyStart::class,
      ResponseBodyEnd::class,
      ConnectionReleased::class,
      CallEnd::class,
    )
  }

  @Test
  fun conditionalCache() {
    enableCache()
    server.enqueue(
      MockResponse
        .Builder()
        .addHeader("ETag", "v1")
        .body("abc")
        .build(),
    )
    server.enqueue(
      MockResponse
        .Builder()
        .code(HttpURLConnection.HTTP_NOT_MODIFIED)
        .build(),
    )
    var call =
      client.newCallWithListener(
        Request
          .Builder()
          .url(server.url("/"))
          .build(),
      )
    var response = call.execute()
    assertThat(response.code).isEqualTo(200)
    response.close()
    eventRecorder.clearAllEvents()
    call = call.cloneWithListener()
    response = call.execute()
    assertThat(response.code).isEqualTo(200)
    assertThat(response.body.string()).isEqualTo("abc")
    response.close()
    assertThat(eventRecorder.recordedEventTypes()).containsExactly(
      CallStart::class,
      CacheConditionalHit::class,
      ConnectionAcquired::class,
      RequestHeadersStart::class,
      RequestHeadersEnd::class,
      ResponseHeadersStart::class,
      ResponseHeadersEnd::class,
      ResponseBodyStart::class,
      ResponseBodyEnd::class,
      CacheHit::class,
      FollowUpDecision::class,
      ConnectionReleased::class,
      CallEnd::class,
    )
  }

  @Test
  fun conditionalCacheMiss() {
    enableCache()
    server.enqueue(
      MockResponse
        .Builder()
        .addHeader("ETag: v1")
        .body("abc")
        .build(),
    )
    server.enqueue(
      MockResponse
        .Builder()
        .code(HttpURLConnection.HTTP_OK)
        .addHeader("ETag: v2")
        .body("abd")
        .build(),
    )
    var call =
      client.newCallWithListener(
        Request
          .Builder()
          .url(server.url("/"))
          .build(),
      )
    var response = call.execute()
    assertThat(response.code).isEqualTo(200)
    response.close()
    eventRecorder.clearAllEvents()
    call = call.cloneWithListener()
    response = call.execute()
    assertThat(response.code).isEqualTo(200)
    assertThat(response.body.string()).isEqualTo("abd")
    response.close()
    assertThat(eventRecorder.recordedEventTypes()).containsExactly(
      CallStart::class,
      CacheConditionalHit::class,
      ConnectionAcquired::class,
      RequestHeadersStart::class,
      RequestHeadersEnd::class,
      ResponseHeadersStart::class,
      ResponseHeadersEnd::class,
      CacheMiss::class,
      FollowUpDecision::class,
      ResponseBodyStart::class,
      ResponseBodyEnd::class,
      ConnectionReleased::class,
      CallEnd::class,
    )
  }

  @Test
  fun satisfactionFailure() {
    enableCache()
    val call =
      client.newCallWithListener(
        Request
          .Builder()
          .url(server.url("/"))
          .cacheControl(CacheControl.FORCE_CACHE)
          .build(),
      )
    val response = call.execute()
    assertThat(response.code).isEqualTo(504)
    response.close()
    assertThat(eventRecorder.recordedEventTypes())
      .containsExactly(
        CallStart::class,
        SatisfactionFailure::class,
        FollowUpDecision::class,
        CallEnd::class,
      )
    assertThat(eventRecorder.findEvent<FollowUpDecision>()).all {
      prop(FollowUpDecision::nextRequest).isNull()
    }
  }

  @Test
  fun cacheHit() {
    enableCache()
    server.enqueue(
      MockResponse
        .Builder()
        .body("abc")
        .addHeader("cache-control: public, max-age=300")
        .build(),
    )
    var call =
      client.newCallWithListener(
        Request
          .Builder()
          .url(server.url("/"))
          .build(),
      )
    var response = call.execute()
    assertThat(response.code).isEqualTo(200)
    assertThat(response.body.string()).isEqualTo("abc")
    response.close()
    eventRecorder.clearAllEvents()
    call = call.cloneWithListener()
    response = call.execute()
    assertThat(response.code).isEqualTo(200)
    assertThat(response.body.string()).isEqualTo("abc")
    response.close()
    assertThat(eventRecorder.recordedEventTypes())
      .containsExactly(
        CallStart::class,
        CacheHit::class,
        FollowUpDecision::class,
        CallEnd::class,
      )
  }

  /** Make sure we didn't mess up our special case for [EventListener.NONE]. */
  @Test
  fun eventListenerPlusNoneAggregation() {
    val a = EventRecorder(enforceOrder = false)
    val aPlusNone = a.eventListener + EventListener.NONE

    aPlusNone.callStart(FailingCall())
    assertThat(a.takeEvent()).isInstanceOf<CallStart>()
    assertThat(a.eventSequence).isEmpty()
  }

  /** Make sure we didn't mess up our special case for [EventListener.NONE]. */
  @Test
  fun nonePlusEventListenerAggregation() {
    val a = EventRecorder(enforceOrder = false)
    val nonePlusA = EventListener.NONE + a.eventListener

    nonePlusA.callStart(FailingCall())
    assertThat(a.takeEvent()).isInstanceOf<CallStart>()
    assertThat(a.eventSequence).isEmpty()
  }

  /** Make sure we didn't mess up our special case for combining aggregates. */
  @Test
  fun moreThanTwoAggregation() {
    val a = EventRecorder(enforceOrder = false)
    val b = EventRecorder(enforceOrder = false)
    val c = EventRecorder(enforceOrder = false)
    val d = EventRecorder(enforceOrder = false)

    val abcd = (a.eventListener + b.eventListener) + (c.eventListener + d.eventListener)
    abcd.callStart(FailingCall())

    assertThat(a.takeEvent()).isInstanceOf<CallStart>()
    assertThat(a.eventSequence).isEmpty()
    assertThat(b.takeEvent()).isInstanceOf<CallStart>()
    assertThat(b.eventSequence).isEmpty()
    assertThat(c.takeEvent()).isInstanceOf<CallStart>()
    assertThat(c.eventSequence).isEmpty()
    assertThat(d.takeEvent()).isInstanceOf<CallStart>()
    assertThat(d.eventSequence).isEmpty()
  }

  /** Reflectively call every event function to confirm it is correctly forwarded. */
  @Test
  fun aggregateEventListenerIsComplete() {
    val sampleValues = sampleValuesMap()

    val solo = EventRecorder(enforceOrder = false)
    val left = EventRecorder(enforceOrder = false)
    val right = EventRecorder(enforceOrder = false)
    val composite = left.eventListener + right.eventListener

    for (method in EventListener::class.java.declaredMethods) {
      if (method.name == "plus") continue

      val args =
        method.parameters
          .map { sampleValues[it.type] ?: error("no sample value for ${it.type}") }
          .toTypedArray()

      method.invoke(solo.eventListener, *args)
      method.invoke(composite, *args)

      val expectedEvent = solo.takeEvent()
      assertThat(solo.eventSequence).isEmpty()

      assertThat(left.takeEvent()::class).isEqualTo(expectedEvent::class)
      assertThat(left.eventSequence).isEmpty()

      assertThat(right.takeEvent()::class).isEqualTo(expectedEvent::class)
      assertThat(right.eventSequence).isEmpty()
    }
  }

  /** Listeners added with [Call.addEventListener] don't exist on clones of that call. */
  @Test
  fun clonedCallDoesNotHaveAddedEventListeners() {
    assumeTrue(listenerInstalledOn != ListenerInstalledOn.Client)

    server.enqueue(
      MockResponse
        .Builder()
        .body("abc")
        .build(),
    )
    val call =
      client.newCallWithListener(
        Request
          .Builder()
          .url(server.url("/"))
          .build(),
      )
    val clone = call.clone() // Not cloneWithListener.

    val response = clone.execute()
    assertThat(response.code).isEqualTo(200)
    assertThat(response.body.string()).isEqualTo("abc")
    response.body.close()
    assertThat(eventRecorder.recordedEventTypes()).isEmpty()
  }

  /** Listeners added with [OkHttpClient.Builder.eventListener] are also added to clones. */
  @Test
  fun clonedCallHasClientEventListeners() {
    assumeTrue(listenerInstalledOn == ListenerInstalledOn.Client)

    server.enqueue(
      MockResponse
        .Builder()
        .body("abc")
        .build(),
    )
    val call =
      client.newCallWithListener(
        Request
          .Builder()
          .url(server.url("/"))
          .build(),
      )
    val clone = call.clone() // Not cloneWithListener().

    val response = clone.execute()
    assertThat(response.code).isEqualTo(200)
    assertThat(response.body.string()).isEqualTo("abc")
    response.body.close()
    assertThat(eventRecorder.recordedEventTypes()).isNotEmpty()
  }

  /**
   * Returns a map with sample values for each possible parameter of an [EventListener] function
   * parameter.
   */
  private fun sampleValuesMap(): Map<Class<*>, Any> {
    TestValueFactory().use { factory ->
      val address = factory.newAddress("a")
      val route = factory.newRoute(address)
      val pool = factory.newConnectionPool()
      val url = "https://example.com/".toHttpUrl()
      val request = Request(url = url)
      val response =
        Response
          .Builder()
          .request(request)
          .protocol(Protocol.HTTP_1_1)
          .code(200)
          .message("OK")
          .build()
      val handshake =
        Handshake.get(
          tlsVersion = TlsVersion.TLS_1_3,
          cipherSuite = CipherSuite.TLS_AES_128_GCM_SHA256,
          peerCertificates = listOf(),
          localCertificates = listOf(),
        )

      return mapOf(
        Boolean::class.java to false,
        Call::class.java to FailingCall(),
        Connection::class.java to factory.newConnection(pool, route),
        Dispatcher::class.java to Dispatcher(),
        Handshake::class.java to handshake,
        HttpUrl::class.java to url,
        IOException::class.java to IOException("boom"),
        InetSocketAddress::class.java to InetSocketAddress.createUnresolved("localhost", 80),
        List::class.java to listOf<Any?>(),
        Long::class.java to 123L,
        Protocol::class.java to Protocol.HTTP_2,
        Proxy::class.java to Proxy.NO_PROXY,
        Request::class.java to request,
        Response::class.java to response,
        String::class.java to "hello",
      )
    }
  }

  private fun enableCache(): Cache? {
    cache = makeCache()
    client = client.newBuilder().cache(cache).build()
    return cache
  }

  private fun makeCache(): Cache {
    val cacheDir = File.createTempFile("cache-", ".dir")
    cacheDir.delete()
    return Cache(cacheDir, (1024 * 1024).toLong())
  }

  private fun OkHttpClient.newCallWithListener(request: Request): Call =
    newCall(request)
      .apply {
        addEventRecorder(eventRecorder)
      }

  private fun Call.cloneWithListener(): Call =
    clone()
      .apply {
        addEventRecorder(eventRecorder)
      }

  private fun Call.addEventRecorder(eventRecorder: EventRecorder) {
    when (listenerInstalledOn) {
      ListenerInstalledOn.Call -> {
        addEventListener(eventRecorder.eventListener)
      }

      ListenerInstalledOn.Relay -> {
        addEventListener(EventListenerRelay(this, eventRecorder).eventListener)
      }

      ListenerInstalledOn.Client -> {} // listener is added elsewhere.
    }
  }

  enum class ListenerInstalledOn {
    Client,
    Call,
    Relay,
  }

  companion object {
    val anyResponse = CoreMatchers.any(Response::class.java)
  }
}
