/*
 * Copyright (c) 2026 OkHttp Authors
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

import java.io.FilterOutputStream
import java.io.OutputStream
import java.net.Socket
import java.net.SocketTimeoutException
import java.util.concurrent.TimeUnit
import javax.net.SocketFactory
import kotlin.test.assertFailsWith
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.junit5.StartStop
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.sockets.DelegatingSocketFactory
import okhttp3.testing.PlatformRule
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.RegisterExtension

class WriteTimeoutDuringWriteToTest {
  @RegisterExtension
  val platform = PlatformRule()

  @RegisterExtension
  val clientTestRule = OkHttpClientTestRule()

  @StartStop
  private val server = MockWebServer()

  @Tag("Slowish")
  @Test
  fun delayedSocketWritesHonorWriteTimeout() {
    server.enqueue(MockResponse())

    val writeTimeoutMillis = 200L
    val client =
      clientTestRule
        .newClientBuilder()
        .writeTimeout(writeTimeoutMillis, TimeUnit.MILLISECONDS)
        .socketFactory(
          object : DelegatingSocketFactory(SocketFactory.getDefault()) {
            override fun createSocket(): Socket =
              object : Socket() {
                override fun getOutputStream(): OutputStream =
                  object : FilterOutputStream(super.getOutputStream()) {
                    override fun write(
                      b: ByteArray,
                      off: Int,
                      len: Int,
                    ) {
                      Thread.sleep(writeTimeoutMillis + 150)
                      super.write(b, off, len)
                    }
                  }
              }
          },
        ).build()

    val request =
      Request(
        url = server.url("/"),
        body = "test".toRequestBody(),
      )

    assertFailsWith<SocketTimeoutException> {
      client.newCall(request).execute()
    }
  }
}
