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
package okhttp3.dnsoverhttps

import assertk.assertThat
import assertk.assertions.matchesPredicate
import java.net.InetAddress
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.OkHttpClientTestRule
import okhttp3.Request
import okhttp3.Response
import okhttp3.testing.PlatformRule
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.RegisterExtension

@Tag("Remote")
class EchRemoteTest {
  @RegisterExtension
  val platform = PlatformRule(requiredPlatformName = PlatformRule.CONSCRYPT_PROPERTY)

  @RegisterExtension
  val clientTestRule = OkHttpClientTestRule()

  private var client = clientTestRule.newClientBuilder()
    .dns(
      DnsOverHttps
        .Builder()
        .client(clientTestRule.newClient())
        .url("https://1.1.1.1/dns-query".toHttpUrl())
        .bootstrapDnsHosts(InetAddress.getByName("1.1.1.1"))
        .includeHttps(true)
        .build()
    )
    .build()

  @Test
  fun testHttpsRequest() {
    val cloudflareBody = client.sendRequest(
      Request.Builder().url("https://crypto.cloudflare.com/cdn-cgi/trace").build()
    ) {
      it.body.string()
    }
    assertThat(cloudflareBody).matchesPredicate { it.contains("sni=encrypted") }

    val tlsEchBody = client.sendRequest(Request.Builder().url("https://tls-ech.dev/").build()) {
      it.body.string()
    }
    assertThat(tlsEchBody).matchesPredicate { it.contains("You are using ECH. :)") }
  }

  private fun <T> OkHttpClient.sendRequest(request: Request, fn: (Response) -> T): T {
    val response = newCall(request).execute()

    return response.use {
      fn(it)
    }
  }
}
