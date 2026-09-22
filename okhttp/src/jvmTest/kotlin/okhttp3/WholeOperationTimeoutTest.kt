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

import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isFalse
import assertk.assertions.isNotNull
import assertk.assertions.isTrue
import java.io.IOException
import java.io.InterruptedIOException
import java.net.HttpURLConnection
import java.time.Duration
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.assertFailsWith
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.junit5.StartStop
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.TestUtil.repeat
import okhttp3.testing.Flaky
import okio.BufferedSink
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.api.extension.RegisterExtension

@Timeout(30)
@Tag("Slow")
class WholeOperationTimeoutTest {
  @RegisterExtension
  val clientTestRule = OkHttpClientTestRule()
  private val client = clientTestRule.newClient()

  @StartStop
  private val server = MockWebServer()

  @Test
  fun defaultConfigIsNoTimeout() {
    val request =
      Request
        .Builder()
        .url(server.url("/"))
        .build()
    val call = client.newCall(request)
    assertThat(call.timeout().timeoutNanos()).isEqualTo(0)
  }

  @Test
  fun configureClientDefault() {
    val request =
      Request
        .Builder()
        .url(server.url("/"))
        .build()
    val timeoutClient =
      client
        .newBuilder()
        .callTimeout(Duration.ofMillis(456))
        .build()
    val call = timeoutClient.newCall(request)
    assertThat(call.timeout().timeoutNanos())
      .isEqualTo(TimeUnit.MILLISECONDS.toNanos(456))
  }

  @Test
  fun timeoutWritingRequest() {
    server.enqueue(MockResponse())
    val request =
      Request
        .Builder()
        .url(server.url("/"))
        .post(sleepingRequestBody(500))
        .build()
    val call = client.newCall(request)
    call.timeout().timeout(250, TimeUnit.MILLISECONDS)
    assertFailsWith<IOException> {
      call.execute()
    }.also { expected ->
      assertThat(expected.message).isEqualTo("timeout")
      assertThat(call.isCanceled()).isTrue()
    }
  }

  @Test
  fun timeoutWritingRequestWithEnqueue() {
    server.enqueue(MockResponse())
    val request =
      Request
        .Builder()
        .url(server.url("/"))
        .post(sleepingRequestBody(500))
        .build()
    val latch = CountDownLatch(1)
    val exceptionRef = AtomicReference<Throwable>()
    val call = client.newCall(request)
    call.timeout().timeout(250, TimeUnit.MILLISECONDS)
    call.enqueue(
      object : Callback {
        override fun onFailure(
          call: Call,
          e: IOException,
        ) {
          exceptionRef.set(e)
          latch.countDown()
        }

        @Throws(IOException::class)
        override fun onResponse(
          call: Call,
          response: Response,
        ) {
          response.close()
          latch.countDown()
        }
      },
    )
    latch.await()
    assertThat(call.isCanceled()).isTrue()
    assertThat(exceptionRef.get()).isNotNull()
  }

  @Test
  fun timeoutProcessing() {
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
        .build()
    val call = client.newCall(request)
    call.timeout().timeout(250, TimeUnit.MILLISECONDS)
    assertFailsWith<IOException> {
      call.execute()
    }.also { expected ->
      assertThat(expected.message).isEqualTo("timeout")
      assertThat(call.isCanceled()).isTrue()
    }
  }

  @Test
  fun timeoutProcessingWithEnqueue() {
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
        .build()
    val latch = CountDownLatch(1)
    val exceptionRef = AtomicReference<Throwable>()
    val call = client.newCall(request)
    call.timeout().timeout(250, TimeUnit.MILLISECONDS)
    call.enqueue(
      object : Callback {
        override fun onFailure(
          call: Call,
          e: IOException,
        ) {
          exceptionRef.set(e)
          latch.countDown()
        }

        @Throws(IOException::class)
        override fun onResponse(
          call: Call,
          response: Response,
        ) {
          response.close()
          latch.countDown()
        }
      },
    )
    latch.await()
    assertThat(call.isCanceled()).isTrue()
    assertThat(exceptionRef.get()).isNotNull()
  }

  @Test
  fun timeoutReadingResponse() {
    server.enqueue(
      MockResponse
        .Builder()
        .body(BIG_ENOUGH_BODY)
        .build(),
    )
    val request =
      Request
        .Builder()
        .url(server.url("/"))
        .build()
    val call = client.newCall(request)
    call.timeout().timeout(250, TimeUnit.MILLISECONDS)
    val response = call.execute()
    Thread.sleep(500)
    assertFailsWith<IOException> {
      response.body.source().readUtf8()
    }.also { expected ->
      assertThat(expected.message).isEqualTo("timeout")
      assertThat(call.isCanceled()).isTrue()
    }
  }

  @Test
  fun timeoutReadingResponseWithEnqueue() {
    server.enqueue(
      MockResponse
        .Builder()
        .body(BIG_ENOUGH_BODY)
        .build(),
    )
    val request =
      Request
        .Builder()
        .url(server.url("/"))
        .build()
    val latch = CountDownLatch(1)
    val exceptionRef = AtomicReference<Throwable>()
    val call = client.newCall(request)
    call.timeout().timeout(250, TimeUnit.MILLISECONDS)
    call.enqueue(
      object : Callback {
        override fun onFailure(
          call: Call,
          e: IOException,
        ) {
          latch.countDown()
        }

        @Throws(IOException::class)
        override fun onResponse(
          call: Call,
          response: Response,
        ) {
          try {
            Thread.sleep(500)
          } catch (e: InterruptedException) {
            throw AssertionError()
          }
          assertFailsWith<IOException> {
            response.body.source().readUtf8()
          }.also { expected ->
            exceptionRef.set(expected)
            latch.countDown()
          }
        }
      },
    )
    latch.await()
    assertThat(call.isCanceled()).isTrue()
    assertThat(exceptionRef.get()).isNotNull()
  }

  @Test
  fun singleTimeoutForAllFollowUpRequests() {
    server.enqueue(
      MockResponse
        .Builder()
        .code(HttpURLConnection.HTTP_MOVED_TEMP)
        .setHeader("Location", "/b")
        .headersDelay(100, TimeUnit.MILLISECONDS)
        .build(),
    )
    server.enqueue(
      MockResponse
        .Builder()
        .code(HttpURLConnection.HTTP_MOVED_TEMP)
        .setHeader("Location", "/c")
        .headersDelay(100, TimeUnit.MILLISECONDS)
        .build(),
    )
    server.enqueue(
      MockResponse
        .Builder()
        .code(HttpURLConnection.HTTP_MOVED_TEMP)
        .setHeader("Location", "/d")
        .headersDelay(100, TimeUnit.MILLISECONDS)
        .build(),
    )
    server.enqueue(
      MockResponse
        .Builder()
        .code(HttpURLConnection.HTTP_MOVED_TEMP)
        .setHeader("Location", "/e")
        .headersDelay(100, TimeUnit.MILLISECONDS)
        .build(),
    )
    server.enqueue(
      MockResponse
        .Builder()
        .code(HttpURLConnection.HTTP_MOVED_TEMP)
        .setHeader("Location", "/f")
        .headersDelay(100, TimeUnit.MILLISECONDS)
        .build(),
    )
    server.enqueue(MockResponse())
    val request =
      Request
        .Builder()
        .url(server.url("/a"))
        .build()
    val call = client.newCall(request)
    call.timeout().timeout(250, TimeUnit.MILLISECONDS)
    assertFailsWith<IOException> {
      call.execute()
    }.also { expected ->
      assertThat(expected.message).isEqualTo("timeout")
      assertThat(call.isCanceled()).isTrue()
    }
  }

  @Test
  fun timeoutFollowingRedirectOnNewConnection() {
    val otherServer = MockWebServer()
    otherServer.start()
    server.enqueue(
      MockResponse
        .Builder()
        .code(HttpURLConnection.HTTP_MOVED_TEMP)
        .setHeader("Location", otherServer.url("/"))
        .build(),
    )
    otherServer.enqueue(
      MockResponse
        .Builder()
        .headersDelay(500, TimeUnit.MILLISECONDS)
        .build(),
    )
    val request = Request.Builder().url(server.url("/")).build()
    val call = client.newCall(request)
    call.timeout().timeout(250, TimeUnit.MILLISECONDS)
    assertFailsWith<IOException> {
      call.execute()
    }.also { expected ->
      assertThat(expected.message).isEqualTo("timeout")
      assertThat(call.isCanceled()).isTrue()
    }
  }

  @Flaky
  @Test
  fun noTimeout() {
    // Flaky https://github.com/lysine-dev/okhttp/issues/5304
    server.enqueue(
      MockResponse
        .Builder()
        .headersDelay(250, TimeUnit.MILLISECONDS)
        .body(BIG_ENOUGH_BODY)
        .build(),
    )
    val request =
      Request
        .Builder()
        .url(server.url("/"))
        .post(sleepingRequestBody(250))
        .build()
    val call = client.newCall(request)
    call.timeout().timeout(2000, TimeUnit.MILLISECONDS)
    val response = call.execute()
    Thread.sleep(250)
    response.body.source().readUtf8()
    response.close()
    assertThat(call.isCanceled()).isFalse()
  }

  private fun sleepingRequestBody(sleepMillis: Int): RequestBody =
    object : RequestBody() {
      override fun contentType(): MediaType? = "text/plain".toMediaTypeOrNull()

      @Throws(IOException::class)
      override fun writeTo(sink: BufferedSink) {
        try {
          sink.writeUtf8("abc")
          sink.flush()
          Thread.sleep(sleepMillis.toLong())
          sink.writeUtf8("def")
        } catch (e: InterruptedException) {
          throw InterruptedIOException()
        }
      }
    }

  companion object {
    /** A large response body. Smaller bodies might successfully read after the socket is closed!  */
    private val BIG_ENOUGH_BODY = repeat('a', 64 * 1024)
  }
}
