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
import assertk.assertions.containsExactlyInAnyOrder
import assertk.assertions.isEqualTo
import assertk.assertions.startsWith
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.junit5.StartStop
import org.apache.hc.client5.http.classic.methods.HttpGet
import org.apache.hc.client5.http.impl.classic.HttpClients
import org.apache.hc.core5.http.io.entity.EntityUtils
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test

/**
 * Apache HttpClient 5.x.
 *
 * https://hc.apache.org/httpcomponents-client-5.0.x/index.html
 *
 * Baseline test if we ned to validate OkHttp behaviour against other popular clients.
 */
class ApacheHttpClientTest {
  private val httpClient = HttpClients.createDefault()

  @StartStop
  private val server = MockWebServer()

  @AfterEach
  fun tearDown() {
    httpClient.close()
  }

  @Test
  fun get() {
    server.enqueue(
      MockResponse
        .Builder()
        .body("hello, Apache HttpClient 5.x")
        .build(),
    )

    val request = HttpGet(server.url("/").toUri())
    request.addHeader("Accept", "text/plain")

    @Suppress("DEPRECATION")
    httpClient.execute(request).use { response ->
      assertThat(response.code).isEqualTo(200)
      assertThat(EntityUtils.toString(response.entity)).isEqualTo("hello, Apache HttpClient 5.x")
    }

    val recorded = server.takeRequest()
    assertThat(recorded.headers["Accept"]).isEqualTo("text/plain")
    assertThat(
      recorded.headers["Accept-Encoding"]?.split(", ").orEmpty(),
    ).containsExactlyInAnyOrder(
      "gzip",
      "x-gzip",
      "deflate",
    )
    assertThat(recorded.headers["User-Agent"]!!).startsWith("Apache-HttpClient/")
  }
}
