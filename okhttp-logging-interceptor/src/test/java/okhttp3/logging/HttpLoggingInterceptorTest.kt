/*
 * Copyright (C) 2015 Square, Inc.
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
package okhttp3.logging

import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isLessThan
import assertk.assertions.isLessThanOrEqualTo
import assertk.assertions.isSameInstanceAs
import assertk.assertions.matches
import java.net.UnknownHostException
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.junit5.StartStop
import okhttp3.HttpUrl
import okhttp3.Interceptor
import okhttp3.MediaType
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.RecordingHostnameVerifier
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.WebSocketListener
import okhttp3.logging.HttpLoggingInterceptor.Level
import okhttp3.testing.PlatformRule
import okio.Buffer
import okio.BufferedSink
import okio.ByteString.Companion.decodeBase64
import org.junit.jupiter.api.Assertions.fail
import org.junit.jupiter.api.Assumptions
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.RegisterExtension

class HttpLoggingInterceptorTest {
  @RegisterExtension
  val platform = PlatformRule()

  @StartStop
  private val server = MockWebServer()

  private val handshakeCertificates = platform.localhostHandshakeCertificates()
  private val hostnameVerifier = RecordingHostnameVerifier()
  private lateinit var client: OkHttpClient
  private lateinit var host: String
  private lateinit var url: HttpUrl
  private val networkLogs = LogRecorder()
  private val networkInterceptor = HttpLoggingInterceptor(networkLogs)
  private val applicationLogs = LogRecorder()
  private val applicationInterceptor = HttpLoggingInterceptor(applicationLogs)
  private var extraNetworkInterceptor: Interceptor? = null

  private fun setLevel(level: Level) {
    networkInterceptor.setLevel(level)
    applicationInterceptor.setLevel(level)
  }

  @BeforeEach
  fun setUp() {
    client =
      OkHttpClient
        .Builder()
        .addNetworkInterceptor(
          Interceptor { chain ->
            when {
              extraNetworkInterceptor != null -> extraNetworkInterceptor!!.intercept(chain)
              else -> chain.proceed(chain.request())
            }
          },
        ).addNetworkInterceptor(networkInterceptor)
        .addInterceptor(applicationInterceptor)
        .sslSocketFactory(
          handshakeCertificates.sslSocketFactory(),
          handshakeCertificates.trustManager,
        ).hostnameVerifier(hostnameVerifier)
        .build()
    host = "${server.hostName}:${server.port}"
    url = server.url("/")
  }

  @Test
  fun levelGetter() {
    // The default is NONE.
    assertThat(applicationInterceptor.level).isEqualTo(Level.NONE)
    for (level in Level.entries) {
      applicationInterceptor.setLevel(level)
      assertThat(applicationInterceptor.level).isEqualTo(level)
    }
  }

  @Test
  fun setLevelShouldReturnSameInstanceOfInterceptor() {
    for (level in Level.entries) {
      assertThat(applicationInterceptor.setLevel(level)).isSameInstanceAs(applicationInterceptor)
    }
  }

  @Test
  fun none() {
    server.enqueue(MockResponse())
    client.newCall(request().build()).execute()
    applicationLogs.assertNoMoreLogs()
    networkLogs.assertNoMoreLogs()
  }

  @Test
  fun basicGet() {
    setLevel(Level.BASIC)
    server.enqueue(MockResponse())
    client.newCall(request().build()).execute()
    applicationLogs
      .assertLogEqual("--> GET $url")
      .assertLogMatch(Regex("""<-- 200 OK $url \(\d+ms, 0-byte body\)"""))
      .assertNoMoreLogs()
    networkLogs
      .assertLogEqual("--> GET $url http/1.1")
      .assertLogMatch(Regex("""<-- 200 OK $url \(\d+ms, 0-byte body\)"""))
      .assertNoMoreLogs()
  }

  @Test
  fun basicPost() {
    setLevel(Level.BASIC)
    server.enqueue(MockResponse())
    client.newCall(request().post("Hi?".toRequestBody(PLAIN)).build()).execute()
    applicationLogs
      .assertLogEqual("--> POST $url (3-byte body)")
      .assertLogMatch(Regex("""<-- 200 OK $url \(\d+ms, 0-byte body\)"""))
      .assertNoMoreLogs()
    networkLogs
      .assertLogEqual("--> POST $url http/1.1 (3-byte body)")
      .assertLogMatch(Regex("""<-- 200 OK $url \(\d+ms, 0-byte body\)"""))
      .assertNoMoreLogs()
  }

  @Test
  fun basicResponseBody() {
    setLevel(Level.BASIC)
    server.enqueue(
      MockResponse
        .Builder()
        .body("Hello!")
        .setHeader("Content-Type", PLAIN)
        .build(),
    )
    val response = client.newCall(request().build()).execute()
    response.body.close()
    applicationLogs
      .assertLogEqual("--> GET $url")
      .assertLogMatch(Regex("""<-- 200 OK $url \(\d+ms, 6-byte body\)"""))
      .assertNoMoreLogs()
    networkLogs
      .assertLogEqual("--> GET $url http/1.1")
      .assertLogMatch(Regex("""<-- 200 OK $url \(\d+ms, 6-byte body\)"""))
      .assertNoMoreLogs()
  }

  @Test
  fun basicChunkedResponseBody() {
    setLevel(Level.BASIC)
    server.enqueue(
      MockResponse
        .Builder()
        .chunkedBody("Hello!", 2)
        .setHeader("Content-Type", PLAIN)
        .build(),
    )
    val response = client.newCall(request().build()).execute()
    response.body.close()
    applicationLogs
      .assertLogEqual("--> GET $url")
      .assertLogMatch(Regex("""<-- 200 OK $url \(\d+ms, unknown-length body\)"""))
      .assertNoMoreLogs()
    networkLogs
      .assertLogEqual("--> GET $url http/1.1")
      .assertLogMatch(Regex("""<-- 200 OK $url \(\d+ms, unknown-length body\)"""))
      .assertNoMoreLogs()
  }

  @Test
  fun headersGet() {
    setLevel(Level.HEADERS)
    server.enqueue(MockResponse())
    val response = client.newCall(request().build()).execute()
    response.body.close()
    applicationLogs
      .assertLogEqual("--> GET $url")
      .assertLogEqual("--> END GET")
      .assertLogMatch(Regex("""<-- 200 OK $url \(\d+ms\)"""))
      .assertLogEqual("Content-Length: 0")
      .assertLogEqual("<-- END HTTP")
      .assertNoMoreLogs()
    networkLogs
      .assertLogEqual("--> GET $url http/1.1")
      .assertLogEqual("Host: $host")
      .assertLogEqual("Connection: Keep-Alive")
      .assertLogEqual("Accept-Encoding: gzip")
      .assertLogMatch(Regex("""User-Agent: okhttp/.+"""))
      .assertLogEqual("--> END GET")
      .assertLogMatch(Regex("""<-- 200 OK $url \(\d+ms\)"""))
      .assertLogEqual("Content-Length: 0")
      .assertLogEqual("<-- END HTTP")
      .assertNoMoreLogs()
  }

  @Test
  fun headersPost() {
    setLevel(Level.HEADERS)
    server.enqueue(MockResponse())
    val request = request().post("Hi?".toRequestBody(PLAIN)).build()
    val response = client.newCall(request).execute()
    response.body.close()
    applicationLogs
      .assertLogEqual("--> POST $url")
      .assertLogEqual("Content-Type: text/plain; charset=utf-8")
      .assertLogEqual("Content-Length: 3")
      .assertLogEqual("--> END POST")
      .assertLogMatch(Regex("""<-- 200 OK $url \(\d+ms\)"""))
      .assertLogEqual("Content-Length: 0")
      .assertLogEqual("<-- END HTTP")
      .assertNoMoreLogs()
    networkLogs
      .assertLogEqual("--> POST $url http/1.1")
      .assertLogEqual("Content-Type: text/plain; charset=utf-8")
      .assertLogEqual("Content-Length: 3")
      .assertLogEqual("Host: $host")
      .assertLogEqual("Connection: Keep-Alive")
      .assertLogEqual("Accept-Encoding: gzip")
      .assertLogMatch(Regex("""User-Agent: okhttp/.+"""))
      .assertLogEqual("--> END POST")
      .assertLogMatch(Regex("""<-- 200 OK $url \(\d+ms\)"""))
      .assertLogEqual("Content-Length: 0")
      .assertLogEqual("<-- END HTTP")
      .assertNoMoreLogs()
  }

  @Test
  fun headersPostNoContentType() {
    setLevel(Level.HEADERS)
    server.enqueue(MockResponse())
    val request = request().post("Hi?".toRequestBody(null)).build()
    val response = client.newCall(request).execute()
    response.body.close()
    applicationLogs
      .assertLogEqual("--> POST $url")
      .assertLogEqual("Content-Length: 3")
      .assertLogEqual("--> END POST")
      .assertLogMatch(Regex("""<-- 200 OK $url \(\d+ms\)"""))
      .assertLogEqual("Content-Length: 0")
      .assertLogEqual("<-- END HTTP")
      .assertNoMoreLogs()
    networkLogs
      .assertLogEqual("--> POST $url http/1.1")
      .assertLogEqual("Content-Length: 3")
      .assertLogEqual("Host: $host")
      .assertLogEqual("Connection: Keep-Alive")
      .assertLogEqual("Accept-Encoding: gzip")
      .assertLogMatch(Regex("""User-Agent: okhttp/.+"""))
      .assertLogEqual("--> END POST")
      .assertLogMatch(Regex("""<-- 200 OK $url \(\d+ms\)"""))
      .assertLogEqual("Content-Length: 0")
      .assertLogEqual("<-- END HTTP")
      .assertNoMoreLogs()
  }

  @Test
  fun headersPostNoLength() {
    setLevel(Level.HEADERS)
    server.enqueue(MockResponse())
    val body: RequestBody =
      object : RequestBody() {
        override fun contentType() = PLAIN

        override fun writeTo(sink: BufferedSink) {
          sink.writeUtf8("Hi!")
        }
      }
    val response = client.newCall(request().post(body).build()).execute()
    response.body.close()
    applicationLogs
      .assertLogEqual("--> POST $url")
      .assertLogEqual("Content-Type: text/plain; charset=utf-8")
      .assertLogEqual("--> END POST")
      .assertLogMatch(Regex("""<-- 200 OK $url \(\d+ms\)"""))
      .assertLogEqual("Content-Length: 0")
      .assertLogEqual("<-- END HTTP")
      .assertNoMoreLogs()
    networkLogs
      .assertLogEqual("--> POST $url http/1.1")
      .assertLogEqual("Content-Type: text/plain; charset=utf-8")
      .assertLogEqual("Transfer-Encoding: chunked")
      .assertLogEqual("Host: $host")
      .assertLogEqual("Connection: Keep-Alive")
      .assertLogEqual("Accept-Encoding: gzip")
      .assertLogMatch(Regex("""User-Agent: okhttp/.+"""))
      .assertLogEqual("--> END POST")
      .assertLogMatch(Regex("""<-- 200 OK $url \(\d+ms\)"""))
      .assertLogEqual("Content-Length: 0")
      .assertLogEqual("<-- END HTTP")
      .assertNoMoreLogs()
  }

  @Test
  fun headersPostWithHeaderOverrides() {
    setLevel(Level.HEADERS)
    extraNetworkInterceptor =
      Interceptor { chain: Interceptor.Chain ->
        chain.proceed(
          chain
            .request()
            .newBuilder()
            .header("Content-Length", "2")
            .header("Content-Type", "text/plain-ish")
            .build(),
        )
      }
    server.enqueue(MockResponse())
    client
      .newCall(
        request()
          .post("Hi?".toRequestBody(PLAIN))
          .build(),
      ).execute()
    applicationLogs
      .assertLogEqual("--> POST $url")
      .assertLogEqual("Content-Type: text/plain; charset=utf-8")
      .assertLogEqual("Content-Length: 3")
      .assertLogEqual("--> END POST")
      .assertLogMatch(Regex("""<-- 200 OK $url \(\d+ms\)"""))
      .assertLogEqual("Content-Length: 0")
      .assertLogEqual("<-- END HTTP")
      .assertNoMoreLogs()
    networkLogs
      .assertLogEqual("--> POST $url http/1.1")
      .assertLogEqual("Host: $host")
      .assertLogEqual("Connection: Keep-Alive")
      .assertLogEqual("Accept-Encoding: gzip")
      .assertLogMatch(Regex("""User-Agent: okhttp/.+"""))
      .assertLogEqual("Content-Length: 2")
      .assertLogEqual("Content-Type: text/plain-ish")
      .assertLogEqual("--> END POST")
      .assertLogMatch(Regex("""<-- 200 OK $url \(\d+ms\)"""))
      .assertLogEqual("Content-Length: 0")
      .assertLogEqual("<-- END HTTP")
      .assertNoMoreLogs()
  }

  @Test
  fun headersResponseBody() {
    setLevel(Level.HEADERS)
    server.enqueue(
      MockResponse
        .Builder()
        .body("Hello!")
        .setHeader("Content-Type", PLAIN)
        .build(),
    )
    val response = client.newCall(request().build()).execute()
    response.body.close()
    applicationLogs
      .assertLogEqual("--> GET $url")
      .assertLogEqual("--> END GET")
      .assertLogMatch(Regex("""<-- 200 OK $url \(\d+ms\)"""))
      .assertLogEqual("Content-Length: 6")
      .assertLogEqual("Content-Type: text/plain; charset=utf-8")
      .assertLogEqual("<-- END HTTP")
      .assertNoMoreLogs()
    networkLogs
      .assertLogEqual("--> GET $url http/1.1")
      .assertLogEqual("Host: $host")
      .assertLogEqual("Connection: Keep-Alive")
      .assertLogEqual("Accept-Encoding: gzip")
      .assertLogMatch(Regex("""User-Agent: okhttp/.+"""))
      .assertLogEqual("--> END GET")
      .assertLogMatch(Regex("""<-- 200 OK $url \(\d+ms\)"""))
      .assertLogEqual("Content-Length: 6")
      .assertLogEqual("Content-Type: text/plain; charset=utf-8")
      .assertLogEqual("<-- END HTTP")
      .assertNoMoreLogs()
  }

  @Test
  fun bodyGet() {
    setLevel(Level.BODY)
    server.enqueue(MockResponse())
    val response = client.newCall(request().build()).execute()
    response.body.close()
    applicationLogs
      .assertLogEqual("--> GET $url")
      .assertLogEqual("--> END GET")
      .assertLogMatch(Regex("""<-- 200 OK $url \(\d+ms\)"""))
      .assertLogEqual("Content-Length: 0")
      .assertLogMatch(Regex("""<-- END HTTP \(\d+ms, 0-byte body\)"""))
      .assertNoMoreLogs()
    networkLogs
      .assertLogEqual("--> GET $url http/1.1")
      .assertLogEqual("Host: $host")
      .assertLogEqual("Connection: Keep-Alive")
      .assertLogEqual("Accept-Encoding: gzip")
      .assertLogMatch(Regex("""User-Agent: okhttp/.+"""))
      .assertLogEqual("--> END GET")
      .assertLogMatch(Regex("""<-- 200 OK $url \(\d+ms\)"""))
      .assertLogEqual("Content-Length: 0")
      .assertLogMatch(Regex("""<-- END HTTP \(\d+ms, 0-byte body\)"""))
      .assertNoMoreLogs()
  }

  @Test
  fun bodyGet204() {
    setLevel(Level.BODY)
    bodyGetNoBody(204)
  }

  @Test
  fun bodyGet205() {
    setLevel(Level.BODY)
    bodyGetNoBody(205)
  }

  private fun bodyGetNoBody(code: Int) {
    server.enqueue(
      MockResponse
        .Builder()
        .status("HTTP/1.1 $code No Content")
        .build(),
    )
    val response = client.newCall(request().build()).execute()
    response.body.close()
    applicationLogs
      .assertLogEqual("--> GET $url")
      .assertLogEqual("--> END GET")
      .assertLogMatch(Regex("""<-- $code No Content $url \(\d+ms\)"""))
      .assertLogEqual("Content-Length: 0")
      .assertLogMatch(Regex("""<-- END HTTP \(\d+ms, 0-byte body\)"""))
      .assertNoMoreLogs()
    networkLogs
      .assertLogEqual("--> GET $url http/1.1")
      .assertLogEqual("Host: $host")
      .assertLogEqual("Connection: Keep-Alive")
      .assertLogEqual("Accept-Encoding: gzip")
      .assertLogMatch(Regex("""User-Agent: okhttp/.+"""))
      .assertLogEqual("--> END GET")
      .assertLogMatch(Regex("""<-- $code No Content $url \(\d+ms\)"""))
      .assertLogEqual("Content-Length: 0")
      .assertLogMatch(Regex("""<-- END HTTP \(\d+ms, 0-byte body\)"""))
      .assertNoMoreLogs()
  }

  @Test
  fun bodyPost() {
    setLevel(Level.BODY)
    server.enqueue(MockResponse())
    val request = request().post("Hi?".toRequestBody(PLAIN)).build()
    val response = client.newCall(request).execute()
    response.body.close()
    applicationLogs
      .assertLogEqual("--> POST $url")
      .assertLogEqual("Content-Type: text/plain; charset=utf-8")
      .assertLogEqual("Content-Length: 3")
      .assertLogEqual("")
      .assertLogEqual("Hi?")
      .assertLogEqual("--> END POST (3-byte body)")
      .assertLogMatch(Regex("""<-- 200 OK $url \(\d+ms\)"""))
      .assertLogEqual("Content-Length: 0")
      .assertLogMatch(Regex("""<-- END HTTP \(\d+ms, 0-byte body\)"""))
      .assertNoMoreLogs()
    networkLogs
      .assertLogEqual("--> POST $url http/1.1")
      .assertLogEqual("Content-Type: text/plain; charset=utf-8")
      .assertLogEqual("Content-Length: 3")
      .assertLogEqual("Host: $host")
      .assertLogEqual("Connection: Keep-Alive")
      .assertLogEqual("Accept-Encoding: gzip")
      .assertLogMatch(Regex("""User-Agent: okhttp/.+"""))
      .assertLogEqual("")
      .assertLogEqual("Hi?")
      .assertLogEqual("--> END POST (3-byte body)")
      .assertLogMatch(Regex("""<-- 200 OK $url \(\d+ms\)"""))
      .assertLogEqual("Content-Length: 0")
      .assertLogMatch(Regex("""<-- END HTTP \(\d+ms, 0-byte body\)"""))
      .assertNoMoreLogs()
  }

  @Test
  fun bodyResponseBody() {
    setLevel(Level.BODY)
    server.enqueue(
      MockResponse
        .Builder()
        .body("Hello!")
        .setHeader("Content-Type", PLAIN)
        .build(),
    )
    val response = client.newCall(request().build()).execute()
    response.body.close()
    applicationLogs
      .assertLogEqual("--> GET $url")
      .assertLogEqual("--> END GET")
      .assertLogMatch(Regex("""<-- 200 OK $url \(\d+ms\)"""))
      .assertLogEqual("Content-Length: 6")
      .assertLogEqual("Content-Type: text/plain; charset=utf-8")
      .assertLogEqual("")
      .assertLogEqual("Hello!")
      .assertLogMatch(Regex("""<-- END HTTP \(\d+ms, 6-byte body\)"""))
      .assertNoMoreLogs()
    networkLogs
      .assertLogEqual("--> GET $url http/1.1")
      .assertLogEqual("Host: $host")
      .assertLogEqual("Connection: Keep-Alive")
      .assertLogEqual("Accept-Encoding: gzip")
      .assertLogMatch(Regex("""User-Agent: okhttp/.+"""))
      .assertLogEqual("--> END GET")
      .assertLogMatch(Regex("""<-- 200 OK $url \(\d+ms\)"""))
      .assertLogEqual("Content-Length: 6")
      .assertLogEqual("Content-Type: text/plain; charset=utf-8")
      .assertLogEqual("")
      .assertLogEqual("Hello!")
      .assertLogMatch(Regex("""<-- END HTTP \(\d+ms, 6-byte body\)"""))
      .assertNoMoreLogs()
  }

  @Test
  fun bodyResponseBodyChunked() {
    setLevel(Level.BODY)
    server.enqueue(
      MockResponse
        .Builder()
        .chunkedBody("Hello!", 2)
        .setHeader("Content-Type", PLAIN)
        .build(),
    )
    val response = client.newCall(request().build()).execute()
    response.body.close()
    applicationLogs
      .assertLogEqual("--> GET $url")
      .assertLogEqual("--> END GET")
      .assertLogMatch(Regex("""<-- 200 OK $url \(\d+ms\)"""))
      .assertLogEqual("Transfer-encoding: chunked")
      .assertLogEqual("Content-Type: text/plain; charset=utf-8")
      .assertLogEqual("")
      .assertLogEqual("Hello!")
      .assertLogMatch(Regex("""<-- END HTTP \(\d+ms, 6-byte body\)"""))
      .assertNoMoreLogs()
    networkLogs
      .assertLogEqual("--> GET $url http/1.1")
      .assertLogEqual("Host: $host")
      .assertLogEqual("Connection: Keep-Alive")
      .assertLogEqual("Accept-Encoding: gzip")
      .assertLogMatch(Regex("""User-Agent: okhttp/.+"""))
      .assertLogEqual("--> END GET")
      .assertLogMatch(Regex("""<-- 200 OK $url \(\d+ms\)"""))
      .assertLogEqual("Transfer-encoding: chunked")
      .assertLogEqual("Content-Type: text/plain; charset=utf-8")
      .assertLogEqual("")
      .assertLogEqual("Hello!")
      .assertLogMatch(Regex("""<-- END HTTP \(\d+ms, 6-byte body\)"""))
      .assertNoMoreLogs()
  }

  @Test
  fun bodyRequestGzipEncoded() {
    setLevel(Level.BODY)
    server.enqueue(
      MockResponse
        .Builder()
        .setHeader("Content-Type", PLAIN)
        .body(Buffer().writeUtf8("Uncompressed"))
        .build(),
    )
    val response =
      client
        .newCall(
          request()
            .post("Uncompressed".toRequestBody())
            .gzip()
            .build(),
        ).execute()
    val responseBody = response.body
    assertThat(responseBody.string(), "Expected response body to be valid")
      .isEqualTo("Uncompressed")
    responseBody.close()
    networkLogs
      .assertLogEqual("--> POST $url http/1.1")
      .assertLogEqual("Content-Encoding: gzip")
      .assertLogEqual("Transfer-Encoding: chunked")
      .assertLogEqual("Host: $host")
      .assertLogEqual("Connection: Keep-Alive")
      .assertLogEqual("Accept-Encoding: gzip")
      .assertLogMatch(Regex("""User-Agent: okhttp/.+"""))
      .assertLogEqual("")
      .assertLogEqual("--> END POST (12-byte, 32-gzipped-byte body)")
      .assertLogMatch(Regex("""<-- 200 OK $url \(\d+ms\)"""))
      .assertLogEqual("Content-Type: text/plain; charset=utf-8")
      .assertLogMatch(Regex("""Content-Length: \d+"""))
      .assertLogEqual("")
      .assertLogEqual("Uncompressed")
      .assertLogMatch(Regex("""<-- END HTTP \(\d+ms, 12-byte body\)"""))
      .assertNoMoreLogs()
  }

  @Test
  fun bodyResponseGzipEncoded() {
    setLevel(Level.BODY)
    server.enqueue(
      MockResponse
        .Builder()
        .setHeader("Content-Encoding", "gzip")
        .setHeader("Content-Type", PLAIN)
        .body(Buffer().write("H4sIAAAAAAAAAPNIzcnJ11HwQKIAdyO+9hMAAAA=".decodeBase64()!!))
        .build(),
    )
    val response = client.newCall(request().build()).execute()
    val responseBody = response.body
    assertThat(responseBody.string(), "Expected response body to be valid")
      .isEqualTo("Hello, Hello, Hello")
    responseBody.close()
    networkLogs
      .assertLogEqual("--> GET $url http/1.1")
      .assertLogEqual("Host: $host")
      .assertLogEqual("Connection: Keep-Alive")
      .assertLogEqual("Accept-Encoding: gzip")
      .assertLogMatch(Regex("""User-Agent: okhttp/.+"""))
      .assertLogEqual("--> END GET")
      .assertLogMatch(Regex("""<-- 200 OK $url \(\d+ms\)"""))
      .assertLogEqual("Content-Encoding: gzip")
      .assertLogEqual("Content-Type: text/plain; charset=utf-8")
      .assertLogMatch(Regex("""Content-Length: \d+"""))
      .assertLogEqual("")
      .assertLogEqual("Hello, Hello, Hello")
      .assertLogMatch(Regex("""<-- END HTTP \(\d+ms, 19-byte, 29-gzipped-byte body\)"""))
      .assertNoMoreLogs()
    applicationLogs
      .assertLogEqual("--> GET $url")
      .assertLogEqual("--> END GET")
      .assertLogMatch(Regex("""<-- 200 OK $url \(\d+ms\)"""))
      .assertLogEqual("Content-Type: text/plain; charset=utf-8")
      .assertLogEqual("")
      .assertLogEqual("Hello, Hello, Hello")
      .assertLogMatch(Regex("""<-- END HTTP \(\d+ms, 19-byte body\)"""))
      .assertNoMoreLogs()
  }

  @Test
  fun bodyResponseUnknownEncoded() {
    setLevel(Level.BODY)
    server.enqueue(
      MockResponse
        .Builder() // It's invalid to return this if not requested, but the server might anyway
        .setHeader("Content-Encoding", "xyz")
        .setHeader("Content-Type", PLAIN)
        .body(Buffer().write("iwmASGVsbG8sIEhlbGxvLCBIZWxsbwoD".decodeBase64()!!))
        .build(),
    )
    val response = client.newCall(request().build()).execute()
    response.body.close()
    networkLogs
      .assertLogEqual("--> GET $url http/1.1")
      .assertLogEqual("Host: $host")
      .assertLogEqual("Connection: Keep-Alive")
      .assertLogEqual("Accept-Encoding: gzip")
      .assertLogMatch(Regex("""User-Agent: okhttp/.+"""))
      .assertLogEqual("--> END GET")
      .assertLogMatch(Regex("""<-- 200 OK $url \(\d+ms\)"""))
      .assertLogEqual("Content-Encoding: xyz")
      .assertLogEqual("Content-Type: text/plain; charset=utf-8")
      .assertLogMatch(Regex("""Content-Length: \d+"""))
      .assertLogEqual("<-- END HTTP (encoded body omitted)")
      .assertNoMoreLogs()
    applicationLogs
      .assertLogEqual("--> GET $url")
      .assertLogEqual("--> END GET")
      .assertLogMatch(Regex("""<-- 200 OK $url \(\d+ms\)"""))
      .assertLogEqual("Content-Encoding: xyz")
      .assertLogEqual("Content-Type: text/plain; charset=utf-8")
      .assertLogMatch(Regex("""Content-Length: \d+"""))
      .assertLogEqual("<-- END HTTP (encoded body omitted)")
      .assertNoMoreLogs()
  }

  @Test
  fun bodyResponseIsStreaming() {
    setLevel(Level.BODY)
    server.enqueue(
      MockResponse
        .Builder()
        .setHeader("Content-Type", "text/event-stream")
        .chunkedBody(
          """
          |event: add
          |data: 73857293
          |
          |event: remove
          |data: 2153
          |
          |event: add
          |data: 113411
          |
          |
          """.trimMargin(),
          8,
        ).build(),
    )
    val response = client.newCall(request().build()).execute()
    response.body.close()
    networkLogs
      .assertLogEqual("--> GET $url http/1.1")
      .assertLogEqual("Host: $host")
      .assertLogEqual("Connection: Keep-Alive")
      .assertLogEqual("Accept-Encoding: gzip")
      .assertLogMatch(Regex("""User-Agent: okhttp/.+"""))
      .assertLogEqual("--> END GET")
      .assertLogMatch(Regex("""<-- 200 OK $url \(\d+ms\)"""))
      .assertLogEqual("Content-Type: text/event-stream")
      .assertLogMatch(Regex("""Transfer-encoding: chunked"""))
      .assertLogEqual("<-- END HTTP (streaming)")
      .assertNoMoreLogs()
    applicationLogs
      .assertLogEqual("--> GET $url")
      .assertLogEqual("--> END GET")
      .assertLogMatch(Regex("""<-- 200 OK $url \(\d+ms\)"""))
      .assertLogEqual("Content-Type: text/event-stream")
      .assertLogMatch(Regex("""Transfer-encoding: chunked"""))
      .assertLogEqual("<-- END HTTP (streaming)")
      .assertNoMoreLogs()
  }

  @Test
  fun bodyResponseIsUnreadable() {
    setLevel(Level.BODY)
    val serverListener = object : WebSocketListener() {}
    server.enqueue(
      MockResponse
        .Builder()
        .webSocketUpgrade(serverListener)
        .build(),
    )
    val response =
      client
        .newCall(
          request()
            .header("Connection", "Upgrade")
            .header("Upgrade", "websocket")
            .header("Sec-WebSocket-Key", "abc123")
            .build(),
        ).execute()
    response.body.close()
    networkLogs
      .assertLogEqual("--> GET $url http/1.1")
      .assertLogEqual("Connection: Upgrade")
      .assertLogEqual("Upgrade: websocket")
      .assertLogEqual("Sec-WebSocket-Key: abc123")
      .assertLogEqual("Host: $host")
      .assertLogEqual("Accept-Encoding: gzip")
      .assertLogMatch(Regex("""User-Agent: okhttp/.+"""))
      .assertLogEqual("--> END GET")
      .assertLogMatch(Regex("""<-- 101 Switching Protocols $url \(\d+ms\)"""))
      .assertLogEqual("Content-Length: 0")
      .assertLogEqual("Connection: Upgrade")
      .assertLogEqual("Upgrade: websocket")
      .assertLogMatch(Regex("""Sec-WebSocket-Accept: .+"""))
      .assertLogEqual("<-- END HTTP (unreadable body)")
      .assertNoMoreLogs()
    applicationLogs
      .assertLogEqual("--> GET $url")
      .assertLogEqual("Connection: Upgrade")
      .assertLogEqual("Upgrade: websocket")
      .assertLogEqual("Sec-WebSocket-Key: abc123")
      .assertLogEqual("--> END GET")
      .assertLogMatch(Regex("""<-- 101 Switching Protocols $url \(\d+ms\)"""))
      .assertLogEqual("Content-Length: 0")
      .assertLogEqual("Connection: Upgrade")
      .assertLogEqual("Upgrade: websocket")
      .assertLogMatch(Regex("""Sec-WebSocket-Accept: .+"""))
      .assertLogEqual("<-- END HTTP (unreadable body)")
      .assertNoMoreLogs()
  }

  @Test
  fun bodyGetMalformedCharset() {
    setLevel(Level.BODY)
    server.enqueue(
      MockResponse
        .Builder()
        .setHeader("Content-Type", "text/html; charset=0")
        .body("Body with unknown charset")
        .build(),
    )
    val response = client.newCall(request().build()).execute()
    response.body.close()
    networkLogs
      .assertLogEqual("--> GET $url http/1.1")
      .assertLogEqual("Host: $host")
      .assertLogEqual("Connection: Keep-Alive")
      .assertLogEqual("Accept-Encoding: gzip")
      .assertLogMatch(Regex("""User-Agent: okhttp/.+"""))
      .assertLogEqual("--> END GET")
      .assertLogMatch(Regex("""<-- 200 OK $url \(\d+ms\)"""))
      .assertLogEqual("Content-Type: text/html; charset=0")
      .assertLogMatch(Regex("""Content-Length: \d+"""))
      .assertLogMatch(Regex(""))
      .assertLogEqual("Body with unknown charset")
      .assertLogMatch(Regex("""<-- END HTTP \(\d+ms, 25-byte body\)"""))
      .assertNoMoreLogs()
    applicationLogs
      .assertLogEqual("--> GET $url")
      .assertLogEqual("--> END GET")
      .assertLogMatch(Regex("""<-- 200 OK $url \(\d+ms\)"""))
      .assertLogEqual("Content-Type: text/html; charset=0")
      .assertLogMatch(Regex("""Content-Length: \d+"""))
      .assertLogEqual("")
      .assertLogEqual("Body with unknown charset")
      .assertLogMatch(Regex("""<-- END HTTP \(\d+ms, 25-byte body\)"""))
      .assertNoMoreLogs()
  }

  @Test
  fun responseBodyIsBinary() {
    setLevel(Level.BODY)
    val buffer = Buffer()
    buffer.writeUtf8CodePoint(0x89)
    buffer.writeUtf8CodePoint(0x50)
    buffer.writeUtf8CodePoint(0x4e)
    buffer.writeUtf8CodePoint(0x47)
    buffer.writeUtf8CodePoint(0x0d)
    buffer.writeUtf8CodePoint(0x0a)
    buffer.writeUtf8CodePoint(0x1a)
    buffer.writeUtf8CodePoint(0x0a)
    server.enqueue(
      MockResponse
        .Builder()
        .body(buffer)
        .setHeader("Content-Type", "image/png; charset=utf-8")
        .build(),
    )
    val response = client.newCall(request().build()).execute()
    response.body.close()
    applicationLogs
      .assertLogEqual("--> GET $url")
      .assertLogEqual("--> END GET")
      .assertLogMatch(Regex("""<-- 200 OK $url \(\d+ms\)"""))
      .assertLogEqual("Content-Length: 9")
      .assertLogEqual("Content-Type: image/png; charset=utf-8")
      .assertLogEqual("")
      .assertLogMatch(Regex("""<-- END HTTP \(\d+ms, binary 9-byte body omitted\)"""))
      .assertNoMoreLogs()
    networkLogs
      .assertLogEqual("--> GET $url http/1.1")
      .assertLogEqual("Host: $host")
      .assertLogEqual("Connection: Keep-Alive")
      .assertLogEqual("Accept-Encoding: gzip")
      .assertLogMatch(Regex("""User-Agent: okhttp/.+"""))
      .assertLogEqual("--> END GET")
      .assertLogMatch(Regex("""<-- 200 OK $url \(\d+ms\)"""))
      .assertLogEqual("Content-Length: 9")
      .assertLogEqual("Content-Type: image/png; charset=utf-8")
      .assertLogEqual("")
      .assertLogMatch(Regex("""<-- END HTTP \(\d+ms, binary 9-byte body omitted\)"""))
      .assertNoMoreLogs()
  }

  @Test
  fun connectFail() {
    setLevel(Level.BASIC)
    client =
      OkHttpClient
        .Builder()
        .dns { hostname: String? -> throw UnknownHostException("reason") }
        .addInterceptor(applicationInterceptor)
        .build()
    try {
      client.newCall(request().build()).execute()
      fail<Any>()
    } catch (expected: UnknownHostException) {
    }
    applicationLogs
      .assertLogEqual("--> GET $url")
      .assertLogMatch(Regex("""<-- HTTP FAILED: java.net.UnknownHostException: reason. $url \(\d+ms\)"""))
      .assertNoMoreLogs()
  }

  @Test
  fun http2() {
    server.useHttps(handshakeCertificates.sslSocketFactory())
    url = server.url("/")
    setLevel(Level.BASIC)
    server.enqueue(MockResponse())
    val response = client.newCall(request().build()).execute()
    Assumptions.assumeTrue(response.protocol == Protocol.HTTP_2)
    applicationLogs
      .assertLogEqual("--> GET $url")
      .assertLogMatch(Regex("""<-- 200 $url \(\d+ms, 0-byte body\)"""))
      .assertNoMoreLogs()
    networkLogs
      .assertLogEqual("--> GET $url h2")
      .assertLogMatch(Regex("""<-- 200 $url \(\d+ms, 0-byte body\)"""))
      .assertNoMoreLogs()
  }

  @Test
  fun headersAreRedacted() {
    val networkInterceptor =
      HttpLoggingInterceptor(networkLogs).setLevel(
        Level.HEADERS,
      )
    networkInterceptor.redactHeader("sEnSiTiVe")
    val applicationInterceptor =
      HttpLoggingInterceptor(applicationLogs).setLevel(
        Level.HEADERS,
      )
    applicationInterceptor.redactHeader("sEnSiTiVe")
    client =
      OkHttpClient
        .Builder()
        .addNetworkInterceptor(networkInterceptor)
        .addInterceptor(applicationInterceptor)
        .build()
    server.enqueue(
      MockResponse
        .Builder()
        .addHeader("SeNsItIvE", "Value")
        .addHeader("Not-Sensitive", "Value")
        .build(),
    )
    val response =
      client
        .newCall(
          request()
            .addHeader("SeNsItIvE", "Value")
            .addHeader("Not-Sensitive", "Value")
            .build(),
        ).execute()
    response.body.close()
    applicationLogs
      .assertLogEqual("--> GET $url")
      .assertLogEqual("SeNsItIvE: ██")
      .assertLogEqual("Not-Sensitive: Value")
      .assertLogEqual("--> END GET")
      .assertLogMatch(Regex("""<-- 200 OK $url \(\d+ms\)"""))
      .assertLogEqual("Content-Length: 0")
      .assertLogEqual("SeNsItIvE: ██")
      .assertLogEqual("Not-Sensitive: Value")
      .assertLogEqual("<-- END HTTP")
      .assertNoMoreLogs()
    networkLogs
      .assertLogEqual("--> GET $url http/1.1")
      .assertLogEqual("SeNsItIvE: ██")
      .assertLogEqual("Not-Sensitive: Value")
      .assertLogEqual("Host: $host")
      .assertLogEqual("Connection: Keep-Alive")
      .assertLogEqual("Accept-Encoding: gzip")
      .assertLogMatch(Regex("""User-Agent: okhttp/.+"""))
      .assertLogEqual("--> END GET")
      .assertLogMatch(Regex("""<-- 200 OK $url \(\d+ms\)"""))
      .assertLogEqual("Content-Length: 0")
      .assertLogEqual("SeNsItIvE: ██")
      .assertLogEqual("Not-Sensitive: Value")
      .assertLogEqual("<-- END HTTP")
      .assertNoMoreLogs()
  }

  @Test
  fun sensitiveQueryParamsAreRedacted() {
    url = server.url("/api/login?user=test_user&authentication=basic&password=confidential_password")
    val networkInterceptor =
      HttpLoggingInterceptor(networkLogs).setLevel(
        Level.BASIC,
      )
    networkInterceptor.redactQueryParams("user", "passWord")

    val applicationInterceptor =
      HttpLoggingInterceptor(applicationLogs).setLevel(
        Level.BASIC,
      )
    applicationInterceptor.redactQueryParams("user", "PassworD")

    client =
      OkHttpClient
        .Builder()
        .addNetworkInterceptor(networkInterceptor)
        .addInterceptor(applicationInterceptor)
        .build()
    server.enqueue(
      MockResponse
        .Builder()
        .build(),
    )
    val response =
      client
        .newCall(
          request()
            .build(),
        ).execute()
    response.body.close()
    val redactedUrl = networkInterceptor.redactUrl(url)
    val redactedUrlPattern = redactedUrl.replace("?", """\?""")
    applicationLogs
      .assertLogEqual("--> GET $redactedUrl")
      .assertLogMatch(Regex("""<-- 200 OK $redactedUrlPattern \(\d+ms, \d+-byte body\)"""))
      .assertNoMoreLogs()
    networkLogs
      .assertLogEqual("--> GET $redactedUrl http/1.1")
      .assertLogMatch(Regex("""<-- 200 OK $redactedUrlPattern \(\d+ms, \d+-byte body\)"""))
      .assertNoMoreLogs()
  }

  @Test
  fun preserveQueryParamsAfterRedacted() {
    url =
      server.url(
        """/api/login?
      |user=test_user&
      |authentication=basic&
      |password=confidential_password&
      |authentication=rather simple login method
        """.trimMargin(),
      )
    val networkInterceptor =
      HttpLoggingInterceptor(networkLogs).setLevel(
        Level.BASIC,
      )
    networkInterceptor.redactQueryParams("user", "passWord")

    val applicationInterceptor =
      HttpLoggingInterceptor(applicationLogs).setLevel(
        Level.BASIC,
      )
    applicationInterceptor.redactQueryParams("user", "PassworD")

    client =
      OkHttpClient
        .Builder()
        .addNetworkInterceptor(networkInterceptor)
        .addInterceptor(applicationInterceptor)
        .build()
    server.enqueue(
      MockResponse
        .Builder()
        .build(),
    )
    val response =
      client
        .newCall(
          request()
            .build(),
        ).execute()
    response.body.close()
    val redactedUrl = networkInterceptor.redactUrl(url)
    val redactedUrlPattern = redactedUrl.replace("?", """\?""")
    applicationLogs
      .assertLogEqual("--> GET $redactedUrl")
      .assertLogMatch(Regex("""<-- 200 OK $redactedUrlPattern \(\d+ms, \d+-byte body\)"""))
      .assertNoMoreLogs()
    networkLogs
      .assertLogEqual("--> GET $redactedUrl http/1.1")
      .assertLogMatch(Regex("""<-- 200 OK $redactedUrlPattern \(\d+ms, \d+-byte body\)"""))
      .assertNoMoreLogs()
  }

  @Test
  fun duplexRequestsAreNotLogged() {
    platform.assumeHttp2Support()
    server.useHttps(handshakeCertificates.sslSocketFactory()) // HTTP/2
    url = server.url("/")
    setLevel(Level.BODY)
    server.enqueue(
      MockResponse
        .Builder()
        .body("Hello response!")
        .build(),
    )
    val asyncRequestBody: RequestBody =
      object : RequestBody() {
        override fun contentType(): MediaType? = null

        override fun writeTo(sink: BufferedSink) {
          sink.writeUtf8("Hello request!")
          sink.close()
        }

        override fun isDuplex(): Boolean = true
      }
    val request =
      request()
        .post(asyncRequestBody)
        .build()
    val response = client.newCall(request).execute()
    Assumptions.assumeTrue(response.protocol == Protocol.HTTP_2)
    assertThat(response.body.string()).isEqualTo("Hello response!")
    applicationLogs
      .assertLogEqual("--> POST $url")
      .assertLogEqual("--> END POST (duplex request body omitted)")
      .assertLogMatch(Regex("""<-- 200 $url \(\d+ms\)"""))
      .assertLogEqual("content-length: 15")
      .assertLogEqual("")
      .assertLogEqual("Hello response!")
      .assertLogMatch(Regex("""<-- END HTTP \(\d+ms, 15-byte body\)"""))
      .assertNoMoreLogs()
  }

  @Test
  fun oneShotRequestsAreNotLogged() {
    url = server.url("/")
    setLevel(Level.BODY)
    server.enqueue(
      MockResponse
        .Builder()
        .body("Hello response!")
        .build(),
    )
    val asyncRequestBody: RequestBody =
      object : RequestBody() {
        var counter = 0

        override fun contentType() = null

        override fun writeTo(sink: BufferedSink) {
          counter++
          assertThat(counter).isLessThanOrEqualTo(1)
          sink.writeUtf8("Hello request!")
          sink.close()
        }

        override fun isOneShot() = true
      }
    val request =
      request()
        .post(asyncRequestBody)
        .build()
    val response = client.newCall(request).execute()
    assertThat(response.body.string()).isEqualTo("Hello response!")
    applicationLogs
      .assertLogEqual("""--> POST $url""")
      .assertLogEqual("""--> END POST (one-shot body omitted)""")
      .assertLogMatch(Regex("""<-- 200 OK $url \(\d+ms\)"""))
      .assertLogEqual("""Content-Length: 15""")
      .assertLogEqual("")
      .assertLogEqual("""Hello response!""")
      .assertLogMatch(Regex("""<-- END HTTP \(\d+ms, 15-byte body\)"""))
      .assertNoMoreLogs()
  }

  private fun request(): Request.Builder = Request.Builder().url(url)

  internal class LogRecorder(
    val prefix: Regex = Regex(""),
  ) : HttpLoggingInterceptor.Logger {
    private val logs = mutableListOf<String>()
    private var index = 0

    fun assertLogEqual(expected: String) =
      apply {
        assertThat(index, "No more messages found")
          .isLessThan(logs.size)
        assertThat(logs[index++]).isEqualTo(expected)
        return this
      }

    fun assertLogMatch(regex: Regex) =
      apply {
        assertThat(index, "No more messages found")
          .isLessThan(logs.size)
        assertThat(logs[index++])
          .matches(Regex(prefix.pattern + regex.pattern, RegexOption.DOT_MATCHES_ALL))
      }

    fun assertNoMoreLogs() {
      assertThat(logs.size, "More messages remain: ${logs.subList(index, logs.size)}")
        .isEqualTo(index)
    }

    override fun log(message: String) {
      logs.add(message)
    }
  }

  companion object {
    private val PLAIN = "text/plain; charset=utf-8".toMediaType()
  }
}
