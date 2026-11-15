/*
 * Copyright (C) 2020 Square, Inc.
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
package okhttp3.compare

import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.matches
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.junit5.StartStop
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.testing.PlatformRule
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.RegisterExtension

/**
 * OkHttp.
 *
 * https://lysine.dev/okhttp/
 */
class OkHttpClientTest {
  @JvmField @RegisterExtension
  val platform = PlatformRule()

  @StartStop
  private val server = MockWebServer()

  @Test fun get() {
    server.enqueue(MockResponse(body = "hello, OkHttp"))

    val client = OkHttpClient()

    val request =
      Request
        .Builder()
        .url(server.url("/"))
        .header("Accept", "text/plain")
        .build()
    val response = client.newCall(request).execute()
    assertThat(response.code).isEqualTo(200)
    assertThat(response.body.string()).isEqualTo("hello, OkHttp")

    val recorded = server.takeRequest()
    assertThat(recorded.headers["Accept"]).isEqualTo("text/plain")
    assertThat(recorded.headers["Accept-Encoding"]).isEqualTo("gzip")
    assertThat(recorded.headers["Connection"]).isEqualTo("Keep-Alive")
    assertThat(recorded.headers["User-Agent"]!!).matches(Regex("okhttp/.*"))
  }
}
