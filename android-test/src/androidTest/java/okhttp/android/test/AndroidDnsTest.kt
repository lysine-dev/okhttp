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
package okhttp.android.test

import android.os.Build
import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isNotEmpty
import assertk.assertions.matchesPredicate
import java.net.InetAddress
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.junit5.StartStop
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.android.AndroidDns
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * Exercises [AndroidDns] as the resolver behind an [OkHttpClient], without service metadata (ECH).
 * The requests go to a local [MockWebServer] over `localhost`, so the system resolver is driven but
 * no external network is required.
 */
class AndroidDnsTest {
  @StartStop
  private val server = MockWebServer()

  @BeforeEach
  fun setup() {
    // AndroidDns is backed by DnsResolver, which is API 29+.
    assumeTrue(Build.VERSION.SDK_INT >= 29)
  }

  @Test
  fun lookupResolvesLocalhostToLoopback() {
    val addresses = AndroidDns(includeServiceMetadata = false).lookup("localhost")

    assertThat(addresses).isNotEmpty()
    assertThat(addresses).matchesPredicate { it.all(InetAddress::isLoopbackAddress) }
  }

  @Test
  fun getWithAndroidDns() {
    server.enqueue(MockResponse(body = "hello"))

    val client =
      OkHttpClient
        .Builder()
        .dns(AndroidDns(includeServiceMetadata = false))
        .build()

    // Force a hostname (not an IP literal) so the request goes through AndroidDns.
    val url = server.url("/").newBuilder().host("localhost").build()

    client.newCall(Request.Builder().url(url).build()).execute().use { response ->
      assertThat(response.code).isEqualTo(200)
      assertThat(response.body.string()).isEqualTo("hello")
    }
  }
}
