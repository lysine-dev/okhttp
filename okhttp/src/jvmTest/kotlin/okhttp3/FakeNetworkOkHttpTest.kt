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

import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isNull
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.sockets.FakeNetworkPlatform
import okhttp3.testing.PlatformRule
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.RegisterExtension

open class FakeNetworkOkHttpTest {
  private val platform = FakeNetworkPlatform()

  @RegisterExtension
  val platformRule =
    PlatformRule(
      platform = platform,
    )

  @RegisterExtension
  val clientTestRule = OkHttpClientTestRule()

  private val handshakeCertificates = platformRule.localhostHandshakeCertificates()

  // We can't create these until after platformRule runs. Sigh.
  private lateinit var server: MockWebServer
  private lateinit var client: OkHttpClient

  @BeforeEach
  fun setUp() {
    server = MockWebServer()
    client = clientTestRule.newClient()

    server.start()
  }

  @AfterEach
  fun tearDown() {
    server.close()
  }

  @Test
  fun `happy path`() {
    makeRequest()
  }

  @Test
  fun `happy path with TLS`() {
    enableTls()
    makeRequest()
  }

  fun makeRequest() {
    server.enqueue(
      MockResponse
        .Builder()
        .body("abc")
        .build(),
    )

    val request =
      Request(
        url = server.url("/"),
      )

    val call = client.newCall(request)

    val response = call.execute()
    assertThat(response.code).isEqualTo(200)
    assertThat(response.body.string()).isEqualTo("abc")

    val recordedRequest = server.takeRequest()
    assertThat(recordedRequest.method).isEqualTo("GET")
    assertThat(recordedRequest.body).isNull()
  }

  private fun enableTls() {
    client =
      client
        .newBuilder()
        .sslSocketFactory(
          handshakeCertificates.sslSocketFactory(),
          handshakeCertificates.trustManager,
        ).build()
    server.useHttps(handshakeCertificates.sslSocketFactory())
  }
}
