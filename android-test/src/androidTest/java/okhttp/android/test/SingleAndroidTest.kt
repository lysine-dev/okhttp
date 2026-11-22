/*
 * Copyright (C) 2025 Block, Inc.
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
package okhttp.android.test

import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.ConnectionPool
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.tls.internal.TlsUtil.localhost
import org.junit.jupiter.api.Test

/**
 * This single Junit 4 test is our Android test suite on API 21-25.
 */
class SingleAndroidTest {
  private val handshakeCertificates = localhost()

  private var client: OkHttpClient =
    OkHttpClient
      .Builder()
      .sslSocketFactory(
        handshakeCertificates.sslSocketFactory(),
        handshakeCertificates.trustManager,
      ).connectionPool(ConnectionPool(0, 1, TimeUnit.SECONDS))
      .build()

  private val server =
    MockWebServer()

  @Test
  fun testHttpsRequest() {
    server.useHttps(handshakeCertificates.sslSocketFactory())

    server.enqueue(MockResponse())
    server.start()

    val request = Request.Builder().url(server.url("/")).build()

    val response = client.newCall(request).execute()

    response.use {
      assertEquals(200, response.code)
    }

    while (client.connectionPool.connectionCount() > 0) {
      Thread.sleep(1000)
    }
  }

  @Test
  fun testHttpRequest() {
    server.enqueue(MockResponse())
    server.start()

    val request = Request.Builder().url(server.url("/")).build()

    val response = client.newCall(request).execute()

    response.use {
      assertEquals(200, response.code)
    }

    while (client.connectionPool.connectionCount() > 0) {
      Thread.sleep(1000)
    }
  }
}
