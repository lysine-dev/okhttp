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
package okhttp.regression.compare

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.apache.hc.client5.http.classic.methods.HttpGet
import org.apache.hc.client5.http.impl.classic.HttpClients
import org.apache.hc.core5.http.HttpVersion
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Ignore
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Apache HttpClient 5.x.
 *
 * https://hc.apache.org/httpcomponents-client-5.0.x/index.html
 */
@Ignore("Failing with Netty errors")
@RunWith(AndroidJUnit4::class)
class ApacheHttpClientTest {
  private var httpClient = HttpClients.createDefault()

  @After fun tearDown() {
    httpClient.close()
  }

  @Test fun get() {
    val request = HttpGet("https://google.com/robots.txt")

    httpClient.execute(request).use { response ->
      assertEquals(200, response.code)
      // TODO enable ALPN later
      assertEquals(HttpVersion.HTTP_1_1, response.version)
    }
  }
}
