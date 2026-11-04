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
package okhttp3.sse.internal

import assertk.assertThat
import assertk.assertions.containsExactly
import assertk.assertions.isEqualTo
import java.util.concurrent.TimeUnit
import mockwebserver3.MockResponse
import mockwebserver3.MockResponseBody
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
import okhttp3.CallEvent.RequestHeadersEnd
import okhttp3.CallEvent.RequestHeadersStart
import okhttp3.CallEvent.ResponseBodyEnd
import okhttp3.CallEvent.ResponseBodyStart
import okhttp3.CallEvent.ResponseHeadersEnd
import okhttp3.CallEvent.ResponseHeadersStart
import okhttp3.EventRecorder
import okhttp3.Headers
import okhttp3.OkHttpClientTestRule
import okhttp3.Request
import okhttp3.sse.EventSource
import okhttp3.sse.EventSources.createFactory
import okhttp3.testing.PlatformRule
import okio.BufferedSink
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.RegisterExtension
import org.junitpioneer.jupiter.RetryingTest

@Tag("Slowish")
class EventSourceHttpTest {
  @RegisterExtension
  val platform = PlatformRule()

  @StartStop
  private val server = MockWebServer()

  @RegisterExtension
  val clientTestRule = OkHttpClientTestRule()
  private val eventRecorder = EventRecorder()
  private val listener = EventSourceRecorder()
  private var client =
    clientTestRule
      .newClientBuilder()
      .eventListenerFactory(clientTestRule.wrap(eventRecorder))
      .build()

  @AfterEach
  fun after() {
    listener.assertExhausted()
  }

  @Test
  fun event() {
    server.enqueue(
      MockResponse
        .Builder()
        .body(
          """
          |data: hey
          |
          |
          """.trimMargin(),
        ).setHeader("content-type", "text/event-stream")
        .build(),
    )
    val source = newEventSource()
    assertThat(source.request().url.encodedPath).isEqualTo("/")
    listener.assertOpen()
    listener.assertEvent(null, null, "hey")
    listener.assertClose()
  }

  @RetryingTest(5)
  fun cancelInEventShortCircuits() {
    server.enqueue(
      MockResponse
        .Builder()
        .body(
          """
          |data: hey
          |
          |
          """.trimMargin(),
        ).setHeader("content-type", "text/event-stream")
        .build(),
    )
    listener.enqueueCancel() // Will cancel in onOpen().
    newEventSource()
    listener.assertOpen()
    listener.assertFailure("canceled")
  }

  @Test
  fun badContentType() {
    server.enqueue(
      MockResponse
        .Builder()
        .body(
          """
          |data: hey
          |
          |
          """.trimMargin(),
        ).setHeader("content-type", "text/plain")
        .build(),
    )
    newEventSource()
    listener.assertFailure("Invalid content-type: text/plain")
  }

  @Test
  fun badResponseCode() {
    server.enqueue(
      MockResponse
        .Builder()
        .body(
          """
          |data: hey
          |
          |
          """.trimMargin(),
        ).setHeader("content-type", "text/event-stream")
        .code(401)
        .build(),
    )
    newEventSource()
    listener.assertFailure(null)
  }

  @Test
  fun fullCallTimeoutDoesNotApplyOnceConnected() {
    client =
      client
        .newBuilder()
        .callTimeout(250, TimeUnit.MILLISECONDS)
        .build()
    server.enqueue(
      MockResponse
        .Builder()
        .bodyDelay(500, TimeUnit.MILLISECONDS)
        .setHeader("content-type", "text/event-stream")
        .body("data: hey\n\n")
        .build(),
    )
    val source = newEventSource()
    assertThat(source.request().url.encodedPath).isEqualTo("/")
    listener.assertOpen()
    listener.assertEvent(null, null, "hey")
    listener.assertClose()
  }

  @Test
  fun readTimeoutAppliesOnceConnected() {
    client =
      client
        .newBuilder()
        .callTimeout(250, TimeUnit.MILLISECONDS)
        .readTimeout(500, TimeUnit.MILLISECONDS)
        .build()

    val body = object : MockResponseBody {
      override val contentLength: Long
        get() = -1

      override fun writeTo(sink: BufferedSink) {
        repeat(10) {
          sink.writeUtf8("data: hey $it\n\n")
          sink.flush()
          if (it == 4) {
            Thread.sleep(2_000)
          } else {
            Thread.sleep(100)
          }
        }
      }
    }

    server.enqueue(
      MockResponse
        .Builder()
        .setHeader("content-type", "text/event-stream")
        .body(body)
        .build(),
    )
    val source = newEventSource()
    assertThat(source.request().url.encodedPath).isEqualTo("/")
    listener.assertOpen()
    listener.assertEvent(null, null, "hey 0")
    listener.assertEvent(null, null, "hey 1")
    listener.assertEvent(null, null, "hey 2")
    listener.assertEvent(null, null, "hey 3")
    listener.assertEvent(null, null, "hey 4")
    listener.assertFailure("timeout")
  }

  @Test
  fun fullCallTimeoutAppliesToSetup() {
    client =
      client
        .newBuilder()
        .callTimeout(250, TimeUnit.MILLISECONDS)
        .build()
    server.enqueue(
      MockResponse
        .Builder()
        .headersDelay(500, TimeUnit.MILLISECONDS)
        .setHeader("content-type", "text/event-stream")
        .body("data: hey\n\n")
        .build(),
    )
    newEventSource()
    listener.assertFailure("timeout")
  }

  @Test
  fun retainsAccept() {
    server.enqueue(
      MockResponse
        .Builder()
        .body(
          """
          |data: hey
          |
          |
          """.trimMargin(),
        ).setHeader("content-type", "text/event-stream")
        .build(),
    )
    newEventSource("text/plain")
    listener.assertOpen()
    listener.assertEvent(null, null, "hey")
    listener.assertClose()
    assertThat(server.takeRequest().headers["Accept"]).isEqualTo("text/plain")
  }

  @Test
  fun setsMissingAccept() {
    server.enqueue(
      MockResponse
        .Builder()
        .body(
          """
          |data: hey
          |
          |
          """.trimMargin(),
        ).setHeader("content-type", "text/event-stream")
        .build(),
    )
    newEventSource()
    listener.assertOpen()
    listener.assertEvent(null, null, "hey")
    listener.assertClose()
    assertThat(server.takeRequest().headers["Accept"])
      .isEqualTo("text/event-stream")
  }

  @Test
  fun eventListenerEvents() {
    server.enqueue(
      MockResponse
        .Builder()
        .body(
          """
          |data: hey
          |
          |
          """.trimMargin(),
        ).setHeader("content-type", "text/event-stream")
        .build(),
    )
    val source = newEventSource()
    assertThat(source.request().url.encodedPath).isEqualTo("/")
    listener.assertOpen()
    listener.assertEvent(null, null, "hey")
    listener.assertClose()
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
  fun sseReauths() {
    client =
      client
        .newBuilder()
        .authenticator { route, response ->
          response.request
            .newBuilder()
            .header("Authorization", "XYZ")
            .build()
        }.build()
    server.enqueue(
      MockResponse(
        code = 401,
        body = "{\"error\":{\"message\":\"No auth credentials found\",\"code\":401}}",
        headers = Headers.headersOf("content-type", "application/json"),
      ),
    )
    server.enqueue(
      MockResponse(
        body =
          """
          |data: hey
          |
          |
          """.trimMargin(),
        headers = Headers.headersOf("content-type", "text/event-stream"),
      ),
    )
    val source = newEventSource()
    assertThat(source.request().url.encodedPath).isEqualTo("/")
    listener.assertOpen()
    listener.assertEvent(null, null, "hey")
    listener.assertClose()
  }

  @Test
  fun sseWithoutAuthenticator() {
    server.enqueue(
      MockResponse(
        code = 401,
        body = "{\"error\":{\"message\":\"No auth credentials found\",\"code\":401}}",
        headers = Headers.headersOf("content-type", "application/json"),
      ),
    )
    val source = newEventSource()
    assertThat(source.request().url.encodedPath).isEqualTo("/")
    listener.assertFailure(code = 401, message = "{\"error\":{\"message\":\"No auth credentials found\",\"code\":401}}")
  }

  private fun newEventSource(accept: String? = null): EventSource {
    val builder =
      Request
        .Builder()
        .url(server.url("/"))
    if (accept != null) {
      builder.header("Accept", accept)
    }
    val request = builder.build()
    val factory = createFactory(client)
    return factory.newEventSource(request, listener)
  }
}
