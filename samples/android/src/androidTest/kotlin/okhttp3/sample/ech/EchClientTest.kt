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
package okhttp3.sample.ech

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SdkSuppress
import assertk.assertThat
import assertk.assertions.contains
import assertk.assertions.isTrue
import okhttp3.Dns
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.android.AndroidDns
import okhttp3.dnsoverhttps.DnsOverHttps
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
@SdkSuppress(minSdkVersion = 37)
class EchClientTest {
  @Test
  fun androidDns() {
    testEch(AndroidDns())
  }

  @Test
  fun dnsOverHttps() {
    testEch(
      DnsOverHttps.Builder()
        .client(OkHttpClient())
        .url("https://1.1.1.1/dns-query".toHttpUrl())
        .build()
    )
  }

  /**
   * ```
   * DNS record: cloudflare-ech.com/104.18.10.118
   * DNS record: cloudflare-ech.com/104.18.11.118
   * DNS record: cloudflare-ech.com/2606:4700::6812:a76
   * DNS record: cloudflare-ech.com/2606:4700::6812:b76
   * DNS record: ServiceMetadata{
   *   cloudflare-ech.com,
   *   alpnIds=[h3, h2, http/1.1],
   *   ipAddressHints=[104.18.10.118, 104.18.11.118, 2606:4700::6812:a76, 2606:4700::6812:b76],
   *   echConfigList=0045fe0d0041da002000201e8ee5aa34c64a7439d45dfd1157ab774e2f70abccceef4cd24ae0998286cc760004000100010012636c6f7564666c6172652d6563682e636f6d0000
   * }
   * Parsed ECHConfigList:
   *   config[0]:
   *     version: 0xfe0d
   *     contents length: 65
   *     config ID: 218
   *     KEM ID: 0x0020
   *     public key: 1e8ee5aa34c64a7439d45dfd1157ab774e2f70abccceef4cd24ae0998286cc76
   *     maximum name length: 0
   *     public name: cloudflare-ech.com
   *     cipher suites:
   *       KDF 0x0001, AEAD 0x0001
   *     extensions:
   * ```
   */
  private fun testEch(dns: Dns) {
    val client = OkHttpClient.Builder()
      .dns(dns)
      .build()

    val echCheckRequest =
      Request("https://cloudflare-ech.com/cdn-cgi/trace".toHttpUrl())

    client.newCall(echCheckRequest).execute().use { response ->
      assertThat(response.isSuccessful).isTrue()
      assertThat(response.body.string()).contains("sni=encrypted")
    }
  }
}
