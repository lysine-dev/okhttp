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
package okhttp3

import assertk.assertThat
import assertk.assertions.contains
import assertk.assertions.containsExactly
import assertk.assertions.isEmpty
import assertk.assertions.isEqualTo
import assertk.assertions.isFalse
import assertk.assertions.isNotNull
import assertk.assertions.isNull
import assertk.assertions.isSameInstanceAs
import assertk.assertions.isTrue
import java.io.IOException
import java.net.SocketTimeoutException
import java.time.Duration
import java.util.concurrent.BlockingQueue
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.SynchronousQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.assertFailsWith
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.SocketEffect.ShutdownConnection
import mockwebserver3.junit5.StartStop
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.ResponseBody.Companion.toResponseBody
import okhttp3.TestUtil.assertSuppressed
import okio.Buffer
import okio.BufferedSink
import okio.ForwardingSink
import okio.ForwardingSource
import okio.GzipSink
import okio.Sink
import okio.Source
import okio.buffer
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.RegisterExtension

@Tag("Slow")
class InterceptorTest {
  @RegisterExtension
  val clientTestRule = OkHttpClientTestRule()

  @StartStop
  private val server = MockWebServer()

  private var client = clientTestRule.newClient()
  private val callback = RecordingCallback()

  @Test
  fun applicationInterceptorsCanShortCircuitResponses() {
    server.close() // Accept no connections.
    val request =
      Request
        .Builder()
        .url("https://localhost:1/")
        .build()
    val interceptorResponse =
      Response
        .Builder()
        .request(request)
        .protocol(Protocol.HTTP_1_1)
        .code(200)
        .message("Intercepted!")
        .body("abc".toResponseBody("text/plain; charset=utf-8".toMediaType()))
        .build()
    client =
      client
        .newBuilder()
        .addInterceptor(Interceptor { chain: Interceptor.Chain? -> interceptorResponse })
        .build()
    val response = client.newCall(request).execute()
    assertThat(response).isSameInstanceAs(interceptorResponse)
  }

  @Test
  fun networkInterceptorsCannotShortCircuitResponses() {
    server.enqueue(
      MockResponse
        .Builder()
        .code(500)
        .build(),
    )
    val interceptor =
      Interceptor { chain: Interceptor.Chain ->
        Response
          .Builder()
          .request(chain.request())
          .protocol(Protocol.HTTP_1_1)
          .code(200)
          .message("Intercepted!")
          .body("abc".toResponseBody("text/plain; charset=utf-8".toMediaType()))
          .build()
      }
    client =
      client
        .newBuilder()
        .addNetworkInterceptor(interceptor)
        .build()
    val request =
      Request
        .Builder()
        .url(server.url("/"))
        .build()
    assertFailsWith<IllegalStateException> {
      client.newCall(request).execute()
    }.also { expected ->
      assertThat(expected.message).isEqualTo(
        "network interceptor $interceptor must call proceed() exactly once",
      )
    }
  }

  @Test
  fun networkInterceptorsCannotCallProceedMultipleTimes() {
    server.enqueue(MockResponse())
    server.enqueue(MockResponse())
    val interceptor =
      Interceptor { chain: Interceptor.Chain ->
        chain.proceed(chain.request())
        chain.proceed(chain.request())
      }
    client =
      client
        .newBuilder()
        .addNetworkInterceptor(interceptor)
        .build()
    val request =
      Request
        .Builder()
        .url(server.url("/"))
        .build()
    assertFailsWith<IllegalStateException> {
      client.newCall(request).execute()
    }.also { expected ->
      assertThat(expected.message).isEqualTo(
        "network interceptor $interceptor must call proceed() exactly once",
      )
    }
  }

  @Test
  fun networkInterceptorsCannotChangeServerAddress() {
    server.enqueue(
      MockResponse
        .Builder()
        .code(500)
        .build(),
    )
    val interceptor =
      Interceptor { chain: Interceptor.Chain ->
        val address = chain.connection()!!.route().address
        val sameHost = address.url.host
        val differentPort = address.url.port + 1
        chain.proceed(
          chain
            .request()
            .newBuilder()
            .url("http://$sameHost:$differentPort/")
            .build(),
        )
      }
    client =
      client
        .newBuilder()
        .addNetworkInterceptor(interceptor)
        .build()
    val request =
      Request
        .Builder()
        .url(server.url("/"))
        .build()
    assertFailsWith<IllegalStateException> {
      client.newCall(request).execute()
    }.also { expected ->
      assertThat(expected.message).isEqualTo(
        "network interceptor $interceptor must retain the same host and port",
      )
    }
  }

  @Test
  fun networkInterceptorsHaveConnectionAccess() {
    server.enqueue(MockResponse())
    val interceptor =
      Interceptor { chain: Interceptor.Chain ->
        val connection = chain.connection()
        assertThat(connection).isNotNull()
        chain.proceed(chain.request())
      }
    client =
      client
        .newBuilder()
        .addNetworkInterceptor(interceptor)
        .build()
    val request =
      Request
        .Builder()
        .url(server.url("/"))
        .build()
    client.newCall(request).execute()
  }

  @Test
  fun networkInterceptorsObserveNetworkHeaders() {
    server.enqueue(
      MockResponse
        .Builder()
        .body(gzip("abcabcabc"))
        .addHeader("Content-Encoding: gzip")
        .build(),
    )
    val interceptor =
      Interceptor { chain: Interceptor.Chain ->
        // The network request has everything: User-Agent, Host, Accept-Encoding.
        val networkRequest = chain.request()
        assertThat(networkRequest.header("User-Agent")).isNotNull()
        assertThat(networkRequest.header("Host")).isEqualTo(
          server.hostName + ":" + server.port,
        )
        assertThat(networkRequest.header("Accept-Encoding")).isNotNull()

        // The network response also has everything, including the raw gzipped content.
        val networkResponse = chain.proceed(networkRequest)
        assertThat(networkResponse.header("Content-Encoding")).isEqualTo("gzip")
        networkResponse
      }
    client =
      client
        .newBuilder()
        .addNetworkInterceptor(interceptor)
        .build()
    val request =
      Request
        .Builder()
        .url(server.url("/"))
        .build()

    // No extra headers in the application's request.
    assertThat(request.header("User-Agent")).isNull()
    assertThat(request.header("Host")).isNull()
    assertThat(request.header("Accept-Encoding")).isNull()

    // No extra headers in the application's response.
    val response = client.newCall(request).execute()
    assertThat(request.header("Content-Encoding")).isNull()
    assertThat(response.body.string()).isEqualTo("abcabcabc")
  }

  @Test
  fun networkInterceptorsCanChangeRequestMethodFromGetToPost() {
    server.enqueue(MockResponse())
    val interceptor =
      Interceptor { chain: Interceptor.Chain ->
        val originalRequest = chain.request()
        val mediaType = "text/plain".toMediaType()
        val body = "abc".toRequestBody(mediaType)
        chain.proceed(
          originalRequest
            .newBuilder()
            .method("POST", body)
            .header("Content-Type", mediaType.toString())
            .header("Content-Length", body.contentLength().toString())
            .build(),
        )
      }
    client =
      client
        .newBuilder()
        .addNetworkInterceptor(interceptor)
        .build()
    val request =
      Request
        .Builder()
        .url(server.url("/"))
        .get()
        .build()
    client.newCall(request).execute()
    val recordedRequest = server.takeRequest()
    assertThat(recordedRequest.method).isEqualTo("POST")
    assertThat(recordedRequest.body?.utf8()).isEqualTo("abc")
  }

  @Test
  fun applicationInterceptorsRewriteRequestToServer() {
    rewriteRequestToServer(false)
  }

  @Test
  fun networkInterceptorsRewriteRequestToServer() {
    rewriteRequestToServer(true)
  }

  private fun rewriteRequestToServer(network: Boolean) {
    server.enqueue(MockResponse())
    addInterceptor(network) { chain: Interceptor.Chain ->
      val originalRequest = chain.request()
      chain.proceed(
        originalRequest
          .newBuilder()
          .method("POST", uppercase(originalRequest.body))
          .addHeader("OkHttp-Intercepted", "yep")
          .build(),
      )
    }
    val request =
      Request
        .Builder()
        .url(server.url("/"))
        .addHeader("Original-Header", "foo")
        .method("PUT", "abc".toRequestBody("text/plain".toMediaType()))
        .build()
    client.newCall(request).execute()
    val recordedRequest = server.takeRequest()
    assertThat(recordedRequest.body?.utf8()).isEqualTo("ABC")
    assertThat(recordedRequest.headers["Original-Header"]).isEqualTo("foo")
    assertThat(recordedRequest.headers["OkHttp-Intercepted"]).isEqualTo("yep")
    assertThat(recordedRequest.method).isEqualTo("POST")
  }

  @Test
  fun applicationInterceptorsRewriteResponseFromServer() {
    rewriteResponseFromServer(false)
  }

  @Test
  fun networkInterceptorsRewriteResponseFromServer() {
    rewriteResponseFromServer(true)
  }

  private fun rewriteResponseFromServer(network: Boolean) {
    server.enqueue(
      MockResponse
        .Builder()
        .addHeader("Original-Header: foo")
        .body("abc")
        .build(),
    )
    addInterceptor(network) { chain: Interceptor.Chain ->
      val originalResponse = chain.proceed(chain.request())
      originalResponse
        .newBuilder()
        .body(uppercase(originalResponse.body))
        .addHeader("OkHttp-Intercepted", "yep")
        .build()
    }
    val request =
      Request
        .Builder()
        .url(server.url("/"))
        .build()
    val response = client.newCall(request).execute()
    assertThat(response.body.string()).isEqualTo("ABC")
    assertThat(response.header("OkHttp-Intercepted")).isEqualTo("yep")
    assertThat(response.header("Original-Header")).isEqualTo("foo")
  }

  @Test
  fun multipleApplicationInterceptors() {
    multipleInterceptors(false)
  }

  @Test
  fun multipleNetworkInterceptors() {
    multipleInterceptors(true)
  }

  private fun multipleInterceptors(network: Boolean) {
    server.enqueue(MockResponse())
    addInterceptor(network) { chain: Interceptor.Chain ->
      val originalRequest = chain.request()
      val originalResponse =
        chain.proceed(
          originalRequest
            .newBuilder()
            .addHeader("Request-Interceptor", "Android") // 1. Added first.
            .build(),
        )
      originalResponse
        .newBuilder()
        .addHeader("Response-Interceptor", "Donut") // 4. Added last.
        .build()
    }
    addInterceptor(network) { chain: Interceptor.Chain ->
      val originalRequest = chain.request()
      val originalResponse =
        chain.proceed(
          originalRequest
            .newBuilder()
            .addHeader("Request-Interceptor", "Bob") // 2. Added second.
            .build(),
        )
      originalResponse
        .newBuilder()
        .addHeader("Response-Interceptor", "Cupcake") // 3. Added third.
        .build()
    }
    val request =
      Request
        .Builder()
        .url(server.url("/"))
        .build()
    val response = client.newCall(request).execute()
    assertThat(response.headers("Response-Interceptor"))
      .containsExactly("Cupcake", "Donut")
    val recordedRequest = server.takeRequest()
    assertThat(recordedRequest.headers.values("Request-Interceptor"))
      .containsExactly("Android", "Bob")
  }

  @Test
  fun asyncApplicationInterceptors() {
    asyncInterceptors(false)
  }

  @Test
  fun asyncNetworkInterceptors() {
    asyncInterceptors(true)
  }

  private fun asyncInterceptors(network: Boolean) {
    server.enqueue(MockResponse())
    addInterceptor(network) { chain: Interceptor.Chain ->
      val originalResponse = chain.proceed(chain.request())
      originalResponse
        .newBuilder()
        .addHeader("OkHttp-Intercepted", "yep")
        .build()
    }
    val request =
      Request
        .Builder()
        .url(server.url("/"))
        .build()
    client.newCall(request).enqueue(callback)
    callback
      .await(request.url)
      .assertCode(200)
      .assertHeader("OkHttp-Intercepted", "yep")
  }

  @Test
  fun applicationInterceptorsCanMakeMultipleRequestsToServer() {
    server.enqueue(MockResponse.Builder().body("a").build())
    server.enqueue(MockResponse.Builder().body("b").build())
    client =
      client
        .newBuilder()
        .addInterceptor(
          Interceptor { chain: Interceptor.Chain ->
            val response1 = chain.proceed(chain.request())
            response1.body.close()
            chain.proceed(chain.request())
          },
        ).build()
    val request =
      Request
        .Builder()
        .url(server.url("/"))
        .build()
    val response = client.newCall(request).execute()
    assertThat("b").isEqualTo(response.body.string())
  }

  /** Make sure interceptors can interact with the OkHttp client.  */
  @Test
  fun interceptorMakesAnUnrelatedRequest() {
    server.enqueue(MockResponse.Builder().body("a").build()) // Fetched by interceptor.
    server.enqueue(MockResponse.Builder().body("b").build()) // Fetched directly.
    client =
      client
        .newBuilder()
        .addInterceptor(
          Interceptor { chain: Interceptor.Chain ->
            if (chain.request().url.encodedPath == "/b") {
              val requestA =
                Request
                  .Builder()
                  .url(server.url("/a"))
                  .build()
              val responseA = client.newCall(requestA).execute()
              assertThat(responseA.body.string()).isEqualTo("a")
            }
            chain.proceed(chain.request())
          },
        ).build()
    val requestB =
      Request
        .Builder()
        .url(server.url("/b"))
        .build()
    val responseB = client.newCall(requestB).execute()
    assertThat(responseB.body.string()).isEqualTo("b")
  }

  /** Make sure interceptors can interact with the OkHttp client asynchronously.  */
  @Test
  fun interceptorMakesAnUnrelatedAsyncRequest() {
    server.enqueue(MockResponse.Builder().body("a").build()) // Fetched by interceptor.
    server.enqueue(MockResponse.Builder().body("b").build()) // Fetched directly.
    client =
      client
        .newBuilder()
        .addInterceptor(
          Interceptor { chain: Interceptor.Chain ->
            if (chain.request().url.encodedPath == "/b") {
              val requestA =
                Request
                  .Builder()
                  .url(server.url("/a"))
                  .build()
              try {
                val callbackA = RecordingCallback()
                client.newCall(requestA).enqueue(callbackA)
                callbackA.await(requestA.url).assertBody("a")
              } catch (e: Exception) {
                throw RuntimeException(e)
              }
            }
            chain.proceed(chain.request())
          },
        ).build()
    val requestB =
      Request
        .Builder()
        .url(server.url("/b"))
        .build()
    val callbackB = RecordingCallback()
    client.newCall(requestB).enqueue(callbackB)
    callbackB.await(requestB.url).assertBody("b")
  }

  @Test
  fun applicationInterceptorThrowsRuntimeExceptionSynchronous() {
    interceptorThrowsRuntimeExceptionSynchronous(false)
  }

  @Test
  fun networkInterceptorThrowsRuntimeExceptionSynchronous() {
    interceptorThrowsRuntimeExceptionSynchronous(true)
  }

  /**
   * When an interceptor throws an unexpected exception, synchronous callers can catch it and deal
   * with it.
   */
  private fun interceptorThrowsRuntimeExceptionSynchronous(network: Boolean) {
    addInterceptor(network) { chain: Interceptor.Chain? -> throw RuntimeException("boom!") }
    val request =
      Request
        .Builder()
        .url(server.url("/"))
        .build()
    assertFailsWith<RuntimeException> {
      client.newCall(request).execute()
    }.also { expected ->
      assertThat(expected.message).isEqualTo("boom!")
    }
  }

  @Test
  fun networkInterceptorModifiedRequestIsReturned() {
    server.enqueue(MockResponse())
    val modifyHeaderInterceptor =
      Interceptor { chain: Interceptor.Chain ->
        val modifiedRequest =
          chain
            .request()
            .newBuilder()
            .header("User-Agent", "intercepted request")
            .build()
        chain.proceed(modifiedRequest)
      }
    client =
      client
        .newBuilder()
        .addNetworkInterceptor(modifyHeaderInterceptor)
        .build()
    val request =
      Request
        .Builder()
        .url(server.url("/"))
        .header("User-Agent", "user request")
        .build()
    val response = client.newCall(request).execute()
    assertThat(response.request.header("User-Agent")).isNotNull()
    assertThat(response.request.header("User-Agent")).isEqualTo("user request")
    assertThat(response.networkResponse!!.request.header("User-Agent")).isEqualTo(
      "intercepted request",
    )
  }

  @Test
  fun applicationInterceptorThrowsRuntimeExceptionAsynchronous() {
    interceptorThrowsRuntimeExceptionAsynchronous(false)
  }

  @Test
  fun networkInterceptorThrowsRuntimeExceptionAsynchronous() {
    interceptorThrowsRuntimeExceptionAsynchronous(true)
  }

  /**
   * When an interceptor throws an unexpected exception, asynchronous calls are canceled. The
   * exception goes to the uncaught exception handler.
   */
  private fun interceptorThrowsRuntimeExceptionAsynchronous(network: Boolean) {
    val boom = RuntimeException("boom!")
    addInterceptor(network) { chain: Interceptor.Chain? -> throw boom }
    val executor = ExceptionCatchingExecutor()
    client =
      client
        .newBuilder()
        .dispatcher(Dispatcher(executor))
        .build()
    val request =
      Request
        .Builder()
        .url(server.url("/"))
        .build()
    val call = client.newCall(request)
    call.enqueue(callback)
    val recordedResponse = callback.await(server.url("/"))
    assertThat(recordedResponse.failure, "canceled due to java.lang.RuntimeException: boom!")
    assertThat(recordedResponse.failure?.cause).isEqualTo(boom)
    assertThat(call.isCanceled()).isTrue()
    assertThat(executor.takeException()).isEqualTo(boom)
  }

  @Test
  fun networkInterceptorReturnsConnectionOnEmptyBody() {
    server.enqueue(
      MockResponse
        .Builder()
        .onResponseEnd(ShutdownConnection)
        .addHeader("Connection", "Close")
        .build(),
    )
    val interceptor =
      Interceptor { chain: Interceptor.Chain ->
        val response = chain.proceed(chain.request())
        assertThat(chain.connection()).isNotNull()
        response
      }
    client =
      client
        .newBuilder()
        .addNetworkInterceptor(interceptor)
        .build()
    val request =
      Request
        .Builder()
        .url(server.url("/"))
        .build()
    val response = client.newCall(request).execute()
    response.body.close()
  }

  @Test
  fun connectTimeout() {
    val interceptor1 =
      Interceptor { chainA: Interceptor.Chain ->
        assertThat(chainA.connectTimeoutMillis()).isEqualTo(5000)
        val chainB = chainA.withConnectTimeout(100, TimeUnit.MILLISECONDS)
        assertThat(chainB.connectTimeoutMillis()).isEqualTo(100)
        chainB.proceed(chainA.request())
      }
    val interceptor2 =
      Interceptor { chain: Interceptor.Chain ->
        assertThat(chain.connectTimeoutMillis()).isEqualTo(100)
        chain.proceed(chain.request())
      }
    client =
      client
        .newBuilder()
        .connectTimeout(Duration.ofSeconds(5))
        .addInterceptor(interceptor1)
        .addInterceptor(interceptor2)
        .build()
    val request1 =
      Request
        .Builder()
        .url("http://" + TestUtil.UNREACHABLE_ADDRESS_IPV4)
        .build()
    val call = client.newCall(request1)
    val startNanos = System.nanoTime()
    assertFailsWith<SocketTimeoutException> {
      call.execute()
    }
    val elapsedNanos = System.nanoTime() - startNanos
    org.junit.jupiter.api.Assertions.assertTrue(
      elapsedNanos < TimeUnit.SECONDS.toNanos(5),
      "Timeout should have taken ~100ms but was " + elapsedNanos / 1e6 + " ms",
    )
  }

  @Test
  fun chainWithReadTimeout() {
    val interceptor1 =
      Interceptor { chainA: Interceptor.Chain ->
        assertThat(chainA.readTimeoutMillis()).isEqualTo(5000)
        val chainB = chainA.withReadTimeout(100, TimeUnit.MILLISECONDS)
        assertThat(chainB.readTimeoutMillis()).isEqualTo(100)
        chainB.proceed(chainA.request())
      }
    val interceptor2 =
      Interceptor { chain: Interceptor.Chain ->
        assertThat(chain.readTimeoutMillis()).isEqualTo(100)
        chain.proceed(chain.request())
      }
    client =
      client
        .newBuilder()
        .readTimeout(Duration.ofSeconds(5))
        .addInterceptor(interceptor1)
        .addInterceptor(interceptor2)
        .build()
    server.enqueue(
      MockResponse
        .Builder()
        .body("abc")
        .throttleBody(1, 1, TimeUnit.SECONDS)
        .build(),
    )
    val request1 =
      Request
        .Builder()
        .url(server.url("/"))
        .build()
    val call = client.newCall(request1)
    val response = call.execute()
    val body = response.body
    assertFailsWith<SocketTimeoutException> {
      body.string()
    }
  }

  @Test
  fun networkInterceptorCannotChangeReadTimeout() {
    addInterceptor(true) { chain: Interceptor.Chain ->
      chain
        .withReadTimeout(
          100,
          TimeUnit.MILLISECONDS,
        ).proceed(chain.request())
    }
    val request1 = Request.Builder().url(server.url("/")).build()
    val call = client.newCall(request1)
    assertFailsWith<IllegalStateException> {
      call.execute()
    }.also { expected ->
      assertThat(expected.message)
        .isEqualTo("Timeouts can't be adjusted in a network interceptor")
    }
  }

  @Test
  fun networkInterceptorCannotChangeWriteTimeout() {
    addInterceptor(true) { chain: Interceptor.Chain ->
      chain
        .withWriteTimeout(
          100,
          TimeUnit.MILLISECONDS,
        ).proceed(chain.request())
    }
    val request1 = Request.Builder().url(server.url("/")).build()
    val call = client.newCall(request1)
    assertFailsWith<IllegalStateException> {
      call.execute()
    }.also { expected ->
      assertThat(expected.message)
        .isEqualTo("Timeouts can't be adjusted in a network interceptor")
    }
  }

  @Test
  fun networkInterceptorCannotChangeConnectTimeout() {
    addInterceptor(true) { chain: Interceptor.Chain ->
      chain
        .withConnectTimeout(
          100,
          TimeUnit.MILLISECONDS,
        ).proceed(chain.request())
    }
    val request1 = Request.Builder().url(server.url("/")).build()
    val call = client.newCall(request1)
    assertFailsWith<IllegalStateException> {
      call.execute()
    }.also { expected ->
      assertThat(expected.message)
        .isEqualTo("Timeouts can't be adjusted in a network interceptor")
    }
  }

  @Test
  fun chainWithWriteTimeout() {
    val interceptor1 =
      Interceptor { chainA: Interceptor.Chain ->
        assertThat(chainA.writeTimeoutMillis()).isEqualTo(5000)
        val chainB = chainA.withWriteTimeout(100, TimeUnit.MILLISECONDS)
        assertThat(chainB.writeTimeoutMillis()).isEqualTo(100)
        chainB.proceed(chainA.request())
      }
    val interceptor2 =
      Interceptor { chain: Interceptor.Chain ->
        assertThat(chain.writeTimeoutMillis()).isEqualTo(100)
        chain.proceed(chain.request())
      }
    client =
      client
        .newBuilder()
        .writeTimeout(Duration.ofSeconds(5))
        .addInterceptor(interceptor1)
        .addInterceptor(interceptor2)
        .build()
    server.enqueue(
      MockResponse
        .Builder()
        .body("abc")
        .throttleBody(1, 1, TimeUnit.SECONDS)
        .build(),
    )
    val data = ByteArray(2 * 1024 * 1024) // 2 MiB.
    val request1 =
      Request
        .Builder()
        .url(server.url("/"))
        .post(data.toRequestBody("text/plain".toMediaType()))
        .build()
    val call = client.newCall(request1)
    assertFailsWith<SocketTimeoutException> {
      call.execute() // we want this call to throw a SocketTimeoutException
    }
  }

  @Test
  fun chainCanCancelCall() {
    val callRef = AtomicReference<Call>()
    val interceptor =
      Interceptor { chain: Interceptor.Chain ->
        val call = chain.call()
        callRef.set(call)
        assertThat(call.isCanceled()).isFalse()
        call.cancel()
        assertThat(call.isCanceled()).isTrue()
        chain.proceed(chain.request())
      }
    client =
      client
        .newBuilder()
        .addInterceptor(interceptor)
        .build()
    val request =
      Request
        .Builder()
        .url(server.url("/"))
        .build()
    val call = client.newCall(request)
    assertFailsWith<IOException> {
      call.execute()
    }
    assertThat(callRef.get()).isSameInstanceAs(call)
  }

  private fun uppercase(original: RequestBody?): RequestBody =
    object : RequestBody() {
      override fun contentType(): MediaType? = original!!.contentType()

      override fun contentLength(): Long = original!!.contentLength()

      override fun writeTo(sink: BufferedSink) {
        val uppercase = uppercase(sink)
        val bufferedSink = uppercase.buffer()
        original!!.writeTo(bufferedSink)
        bufferedSink.emit()
      }
    }

  private fun uppercase(original: BufferedSink): Sink =
    object : ForwardingSink(original) {
      override fun write(
        source: Buffer,
        byteCount: Long,
      ) {
        original.writeUtf8(source.readUtf8(byteCount).uppercase())
      }
    }

  private fun gzip(data: String): Buffer {
    val result = Buffer()
    val sink = GzipSink(result).buffer()
    sink.writeUtf8(data)
    sink.close()
    return result
  }

  @Test
  fun chainInterceptorsAccessorsInApplicationInterceptors() {
    server.enqueue(MockResponse.Builder().body("ok").build())

    lateinit var app1: Interceptor
    lateinit var app2: Interceptor
    lateinit var app3: Interceptor
    lateinit var net1: Interceptor
    lateinit var net2: Interceptor

    app1 =
      Interceptor { chain ->
        assertThat(chain.interceptors).containsExactly(app2, app3)
        assertThat(chain.networkInterceptors).containsExactly(net1, net2)
        chain.proceed(chain.request())
      }
    app2 =
      Interceptor { chain ->
        assertThat(chain.interceptors).containsExactly(app3)
        assertThat(chain.networkInterceptors).containsExactly(net1, net2)
        chain.proceed(chain.request())
      }
    app3 =
      Interceptor { chain ->
        assertThat(chain.interceptors).isEmpty()
        assertThat(chain.networkInterceptors).containsExactly(net1, net2)
        chain.proceed(chain.request())
      }
    net1 =
      Interceptor { chain ->
        chain.proceed(chain.request())
      }
    net2 =
      Interceptor { chain ->
        chain.proceed(chain.request())
      }

    client =
      client
        .newBuilder()
        .addInterceptor(app1)
        .addInterceptor(app2)
        .addInterceptor(app3)
        .addNetworkInterceptor(net1)
        .addNetworkInterceptor(net2)
        .build()

    val response = client.newCall(Request.Builder().url(server.url("/")).build()).execute()
    assertThat(response.body.string()).isEqualTo("ok")
  }

  @Test
  fun chainInterceptorsAccessorsInNetworkInterceptors() {
    server.enqueue(MockResponse.Builder().body("ok").build())

    lateinit var app: Interceptor
    lateinit var net1: Interceptor
    lateinit var net2: Interceptor
    lateinit var net3: Interceptor

    app =
      Interceptor { chain ->
        chain.proceed(chain.request())
      }
    net1 =
      Interceptor { chain ->
        assertThat(chain.interceptors).isEmpty()
        assertThat(chain.networkInterceptors).containsExactly(net2, net3)
        chain.proceed(chain.request())
      }
    net2 =
      Interceptor { chain ->
        assertThat(chain.interceptors).isEmpty()
        assertThat(chain.networkInterceptors).containsExactly(net3)
        chain.proceed(chain.request())
      }
    net3 =
      Interceptor { chain ->
        assertThat(chain.interceptors).isEmpty()
        assertThat(chain.networkInterceptors).isEmpty()
        chain.proceed(chain.request())
      }

    client =
      client
        .newBuilder()
        .addInterceptor(app)
        .addNetworkInterceptor(net1)
        .addNetworkInterceptor(net2)
        .addNetworkInterceptor(net3)
        .build()

    val response = client.newCall(Request.Builder().url(server.url("/")).build()).execute()
    assertThat(response.body.string()).isEqualTo("ok")
  }

  @Test
  fun chainInterceptorsAccessorsWithNoConfiguredInterceptors() {
    server.enqueue(MockResponse.Builder().body("ok").build())

    val netInterceptor =
      Interceptor { chain ->
        assertThat(chain.interceptors).isEmpty()
        assertThat(chain.networkInterceptors).isEmpty()
        chain.proceed(chain.request())
      }

    client =
      client
        .newBuilder()
        .addNetworkInterceptor(netInterceptor)
        .build()

    val response = client.newCall(Request.Builder().url(server.url("/")).build()).execute()
    assertThat(response.body.string()).isEqualTo("ok")
  }

  @Test
  fun internalInterceptorsAreNeverExposed() {
    server.enqueue(MockResponse.Builder().body("ok").build())

    val appInterceptor =
      Interceptor { chain ->
        for (i in chain.interceptors) {
          assertThat(i.javaClass.name.startsWith("okhttp3.internal.")).isFalse()
        }
        for (i in chain.networkInterceptors) {
          assertThat(i.javaClass.name.startsWith("okhttp3.internal.")).isFalse()
        }
        chain.proceed(chain.request())
      }

    val netInterceptor =
      Interceptor { chain ->
        for (i in chain.interceptors) {
          assertThat(i.javaClass.name.startsWith("okhttp3.internal.")).isFalse()
        }
        for (i in chain.networkInterceptors) {
          assertThat(i.javaClass.name.startsWith("okhttp3.internal.")).isFalse()
        }
        chain.proceed(chain.request())
      }

    client =
      client
        .newBuilder()
        .addInterceptor(appInterceptor)
        .addNetworkInterceptor(netInterceptor)
        .build()

    val response = client.newCall(Request.Builder().url(server.url("/")).build()).execute()
    assertThat(response.body.string()).isEqualTo("ok")
  }

  private fun addInterceptor(
    network: Boolean,
    interceptor: Interceptor,
  ) {
    val builder = client.newBuilder()
    if (network) {
      builder.addNetworkInterceptor(interceptor)
    } else {
      builder.addInterceptor(interceptor)
    }
    client = builder.build()
  }

  /** Catches exceptions that are otherwise headed for the uncaught exception handler.  */
  private class ExceptionCatchingExecutor : ThreadPoolExecutor(1, 1, 0, TimeUnit.SECONDS, SynchronousQueue()) {
    private val exceptions: BlockingQueue<Exception> = LinkedBlockingQueue()

    override fun execute(runnable: Runnable) {
      super.execute {
        try {
          runnable.run()
        } catch (e: Exception) {
          exceptions.add(e)
        }
      }
    }

    fun takeException(): Exception = exceptions.take()
  }

  companion object {
    fun uppercase(original: ResponseBody): ResponseBody =
      object : ResponseBody() {
        override fun contentType() = original.contentType()

        override fun contentLength() = original.contentLength()

        override fun source() = uppercase(original.source()).buffer()
      }

    private fun uppercase(original: Source): Source {
      return object : ForwardingSource(original) {
        override fun read(
          sink: Buffer,
          byteCount: Long,
        ): Long {
          val mixedCase = Buffer()
          val count = original.read(mixedCase, byteCount)
          sink.writeUtf8(mixedCase.readUtf8().uppercase())
          return count
        }
      }
    }
  }
}
