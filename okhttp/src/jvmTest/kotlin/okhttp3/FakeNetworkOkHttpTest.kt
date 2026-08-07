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
import mockwebserver3.junit5.StartStop
import okhttp3.sockets.FakeNetwork
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.RegisterExtension

open class FakeNetworkOkHttpTest {
  private val network = FakeNetwork()

  @RegisterExtension
  val clientTestRule = OkHttpClientTestRule()

  @StartStop
  private val server =
    MockWebServer()
      .apply {
        serverSocketFactory = network.serverSocketFactory
      }

  private var client =
    clientTestRule
      .newClientBuilder()
      .socketFactory(network.socketFactory)
      .build()

  @Test
  fun `happy path`() {
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
}
