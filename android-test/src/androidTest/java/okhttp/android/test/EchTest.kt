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

import android.annotation.SuppressLint
import android.net.ssl.EchConfigMismatchException
import android.os.Build
import app.cash.burst.Burst
import assertk.assertThat
import assertk.assertions.contains
import assertk.assertions.doesNotContain
import assertk.assertions.isEqualTo
import assertk.assertions.isFalse
import assertk.assertions.isTrue
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.android.EchAwareDns
import okhttp3.dnsoverhttps.DnsOverHttps
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.fail

/**
 * Confirms Encrypted Client Hello (ECH) end to end, with [EchAwareDns].
 *
 * Test with both [okhttp3.android.AndroidDns] and [DnsOverHttps].
 *
 * See `res/xml/network_security_config.xml` for overrides.
 */
@SuppressLint("NewApi")
@Tag("Remote")
@Burst
class EchTest(
  private val useDoh: Boolean = false,
) {
  private lateinit var client: OkHttpClient

  @BeforeEach
  fun setUp() {
    // EchAwareDns reads API 37 NetworkSecurityPolicy.getDomainEncryptionMode().
    assumeTrue(Build.VERSION.SDK_INT >= 37)

    client =
      OkHttpClient
        .Builder()
        .dns(dns())
        .build()
  }

  @Test
  fun cloudflareUsesEch() {
    assertThat(client.get("https://cloudflare-ech.com/cdn-cgi/trace")).contains("sni=encrypted")
  }

  @Test
  fun tlsEchDevUsesEch() {
    val body = client.get("https://tls-ech.dev/")

    assertThat(body).contains("You are using ECH")
    assertThat(body).doesNotContain("not using ECH")
  }

  @Test
  fun staleEchConfigIsNotRetried() {
    val rejection = client.echRejectionFrom("https://stale.tls-ech.dev/")

    // TODO retry with these, then assert "You are using ECH" like tlsEchDevUsesEch.
    assertThat(rejection.hasRetryConfigList()).isTrue()
    assertThat(rejection.publicHostname).isEqualTo("public.tls-ech.dev")
  }

  @Test
  fun wrongPublicNameIsNotRetried() {
    val rejection = client.echRejectionFrom("https://wrong.tls-ech.dev/")

    // TODO retry with these, then assert "You are using ECH" like tlsEchDevUsesEch.
    assertThat(rejection.hasRetryConfigList()).isTrue()
    assertThat(rejection.publicHostname).isEqualTo("public.tls-ech.dev")
  }

  /**
   * TLS 1.2 cannot carry ECH.
   */
  @Test
  fun tls12OffersNothingToRetryWith() {
    assertThat(client.echRejectionFrom("https://tls12.tls-ech.dev/").hasRetryConfigList()).isFalse()
  }

  /**
   * Makes the call at [url] and returns the ECH rejection it fails with.
   *
   * TODO handle EchConfigMismatchException.retry_configs.
   */
  private fun OkHttpClient.echRejectionFrom(url: String): EchConfigMismatchException {
    val body =
      try {
        get(url)
      } catch (e: EchConfigMismatchException) {
        return e
      }

    fail("expected $url to reject ECH, but it returned: $body")
  }

  @Test
  fun defoUsesEch() {
    assertThat(client.get("https://defo.ie/ech-check.php")).contains("SSL_ECH_STATUS: success")
  }

  /**
   * Disabled by policy.
   */
  @Test
  fun policyDisabledHostDoesNotUseEch() {
    assertThat(client.get("https://crypto.cloudflare.com/cdn-cgi/trace")).contains("sni=plaintext")
  }

  /**
   * [EchAwareDns] over the platform resolver, or over DoH when [useDoh]. Both arms use the same
   * source: the ECH one carries service metadata, the other doesn't.
   */
  private fun dns(): EchAwareDns =
    when {
      useDoh -> {
        val bootstrapClient = OkHttpClient()
        EchAwareDns(
          echDns = dnsOverHttps(bootstrapClient, includeServiceMetadata = true),
          addressOnlyDns = dnsOverHttps(bootstrapClient, includeServiceMetadata = false),
        )
      }
      else -> EchAwareDns()
    }

  /** Addressed by IP, so resolving the resolver doesn't need a resolver. */
  private fun dnsOverHttps(
    bootstrapClient: OkHttpClient,
    includeServiceMetadata: Boolean,
  ): DnsOverHttps =
    DnsOverHttps
      .Builder()
      .client(bootstrapClient)
      .url("https://1.1.1.1/dns-query".toHttpUrl())
      .includeServiceMetadata(includeServiceMetadata)
      .build()

  private fun OkHttpClient.get(url: String): String =
    newCall(Request.Builder().url(url).build()).execute().use { response ->
      response.body.string()
    }
}
