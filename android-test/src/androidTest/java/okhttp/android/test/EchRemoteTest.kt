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
import android.os.Build
import app.cash.burst.Burst
import assertk.assertThat
import assertk.assertions.contains
import assertk.assertions.doesNotContain
import assertk.assertions.hasSize
import assertk.assertions.isEqualTo
import assertk.assertions.isNotNull
import assertk.assertions.isNull
import okhttp3.Call
import okhttp3.Dns
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.Route
import okhttp3.android.AndroidDns
import okhttp3.dnsoverhttps.DnsOverHttps
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test

/**
 * Confirms Encrypted Client Hello (ECH) end to end.
 *
 * Test with both [AndroidDns] and [DnsOverHttps].
 *
 * See `res/xml/network_security_config.xml` for overrides.
 */
@SuppressLint("NewApi")
@Tag("Remote")
@Burst
class EchRemoteTest(
  private val dnsApi: DnsApi = DnsApi.Doh,
) {
  private lateinit var client: OkHttpClient

  @BeforeEach
  fun setUp() {
    // ECH requires API 37.
    assumeTrue(Build.VERSION.SDK_INT >= 37)

    val bootstrapClient = OkHttpClient()
    val dns = dnsApi.create(bootstrapClient)
    client =
      bootstrapClient.newBuilder()
        .addNetworkInterceptor(RouteTagger)
        .dns(dns)
        .build()
  }

  @Test
  fun cloudflareUsesEch() {
    val call = client.newCall(Request("https://cloudflare-ech.com/cdn-cgi/trace".toHttpUrl()))
    call.execute().use { response ->
      assertThat(call.routeList.routes.single().echConfigList).isNotNull()

      val body = response.body.string()
      assertThat(body).contains("sni=encrypted")
    }
  }

  @Test
  fun echIsAcceptedOnTlsEchDev() {
    val call = client.newCall(Request("https://tls-ech.dev/".toHttpUrl()))
    call.execute().use { response ->
      assertThat(call.routeList.routes.single().echConfigList).isNotNull()

      val body = response.body.string()

      // Only the heading identifies the server we reached; every page links to all of the others.
      assertThat(body).contains("<h1>tls-ech.dev</h1>")
      assertThat(body).contains("You are using ECH")
      assertThat(body).doesNotContain("not using ECH")
    }
  }

  @Test
  fun echIsRetriedOnStaleTlsEchDev() {
    val call = client.newCall(Request("https://stale.tls-ech.dev/".toHttpUrl()))
    call.execute().use { response ->
      val routes = call.routeList.routes
      assertThat(routes).hasSize(2)
      assertThat(routes[0].echConfigList).isNotNull()
      assertThat(routes[1].echConfigList).isNotNull()

      val body = response.body.string()
      assertThat(body).contains("<h1>stale.tls-ech.dev</h1>")
      assertThat(body).contains("You are using ECH")
      assertThat(body).doesNotContain("not using ECH")
    }
  }

  /**
   * This page redirects to 'https://wrong.tls-ech.dev:445/', but nothing is listening on that port
   * on that server.
   */
  @Test
  fun echIsAcceptedOnWrongTlsEchDev() {
    val verifiedHostnames = mutableListOf<String>()
    val hostnameVerifier = client.hostnameVerifier
    val client =
      client
        .newBuilder()
        .hostnameVerifier { hostname, session ->
          verifiedHostnames += hostname
          hostnameVerifier.verify(hostname, session)
        }
        .followRedirects(false)
        .build()

    val call = client.newCall(Request("https://wrong.tls-ech.dev/".toHttpUrl()))
    call.execute().use { response ->
      assertThat(call.routeList.routes.single().echConfigList).isNotNull()

      assertThat(response.code).isEqualTo(302)
      assertThat(response.headers["Location"])
        .isEqualTo("https://wrong.tls-ech.dev:445/")
      assertThat(verifiedHostnames).contains("wrong.tls-ech.dev")
    }
  }

  /** TLS 1.2 cannot carry ECH. */
  @Test
  fun tlsIsNotUsedOnTls12TlsEchDev() {
    val call = client.newCall(Request("https://tls12.tls-ech.dev/".toHttpUrl()))
    call.execute().use { response ->
      val routes = call.routeList.routes
      assertThat(routes).hasSize(2)
      assertThat(routes[0].echConfigList).isNotNull()
      assertThat(routes[1].echConfigList).isNull()

      val body = response.body.string()
      assertThat(body).contains("<h1>tls12.tls-ech.dev</h1>")
      assertThat(body).contains("You are not using ECH")
      assertThat(body).doesNotContain("You are using ECH")
    }
  }

  @Test
  fun echIsAcceptedOnDefoIe() {
    val call = client.newCall(Request("https://defo.ie/ech-check.php".toHttpUrl()))
    call.execute().use { response ->
      assertThat(call.routeList.routes.single().echConfigList).isNotNull()

      val body = response.body.string()
      assertThat(body).contains("SSL_ECH_STATUS: success")
    }
  }

  /**
   * Disabled by policy.
   */
  @Test
  fun policyDisabledHostDoesNotUseEch() {
    val call = client.newCall(Request("https://crypto.cloudflare.com/cdn-cgi/trace".toHttpUrl()))
    call.execute().use { response ->
      assertThat(call.routeList.routes.single().echConfigList).isNotNull()

      val body = response.body.string()
      assertThat(body).contains("sni=plaintext")
    }
  }

  enum class DnsApi {
    Android {
      override fun create(client: OkHttpClient) = AndroidDns()
    },

    Doh {
      /** DNS server is addressed by IP, so resolving the resolver doesn't need a resolver. */
      override fun create(client: OkHttpClient) = DnsOverHttps.Builder()
        .client(client)
        .url("https://1.1.1.1/dns-query".toHttpUrl())
        .includeServiceMetadata(true)
        .build()
    };

    abstract fun create(client: OkHttpClient): Dns
  }
}

/**
 * Collect Route information to confirm we sent an ECH config list to our TLS stack. Whether we
 * actually encrypted the client hello depends on our TLS stack.
 */
private object RouteTagger : Interceptor {
  override fun intercept(chain: Interceptor.Chain): Response {
    val routeList = chain.call().routeList
    routeList.routes += chain.connection()!!.route()
    return chain.proceed(chain.request())
  }
}

private val Call.routeList: RouteList
  get() = tag(RouteList::class) { RouteList() }

/**
 * All of the routes used to retrieve an HTTP response.
 */
private class RouteList {
  val routes = mutableListOf<Route>()
}
