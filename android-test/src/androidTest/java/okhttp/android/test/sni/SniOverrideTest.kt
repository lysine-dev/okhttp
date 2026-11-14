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
package okhttp.android.test.sni

import android.os.Build
import android.util.Log
import assertk.assertThat
import assertk.assertions.contains
import assertk.assertions.isEqualTo
import java.security.cert.X509Certificate
import javax.net.ssl.SNIHostName
import javax.net.ssl.SNIServerName
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory
import okhttp3.Dns
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.sockets.DelegatingSSLSocketFactory
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test

/**
 * Tests for SNI overriding on Android.
 */
@Tag("Remote")
class SniOverrideTest {
  var client =
    OkHttpClient
      .Builder()
      .build()

  @Test
  fun getWithCustomSocketFactory() {
    assumeTrue(Build.VERSION.SDK_INT >= 24)

    class CustomSSLSocketFactory(
      delegate: SSLSocketFactory,
    ) : DelegatingSSLSocketFactory(delegate) {
      override fun configureSocket(sslSocket: SSLSocket): SSLSocket {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
          val parameters = sslSocket.sslParameters
          val sni = parameters.serverNames
          Log.d("CustomSSLSocketFactory", "old SNI: $sni")
          parameters.serverNames = mutableListOf<SNIServerName>(SNIHostName("cloudflare-dns.com"))
          sslSocket.sslParameters = parameters
        }

        return sslSocket
      }
    }

    client =
      client
        .newBuilder()
        .sslSocketFactory(CustomSSLSocketFactory(client.sslSocketFactory), client.x509TrustManager!!)
        .hostnameVerifier { hostname, session ->
          val s = "hostname: $hostname peerHost:${session.peerHost}"
          Log.d("SniOverrideTest", s)
          try {
            val cert = session.peerCertificates[0] as X509Certificate
            for (name in cert.subjectAlternativeNames) {
              if (name[0] as Int == 2) {
                Log.d("SniOverrideTest", "cert: " + name[1])
              }
            }
            true
          } catch (e: Exception) {
            false
          }
        }.build()

    val request =
      Request
        .Builder()
        .url("https://sni.cloudflaressl.com/cdn-cgi/trace")
        .header("Host", "cloudflare-dns.com")
        .build()
    client.newCall(request).execute().use { response ->
      assertThat(response.code).isEqualTo(200)
      assertThat(response.protocol).isEqualTo(Protocol.HTTP_2)

      assertThat(response.body.string()).contains("h=cloudflare-dns.com")
    }
  }

  @Test
  fun getWithDns() {
    client =
      client
        .newBuilder()
        .dns {
          Dns.SYSTEM.lookup("sni.cloudflaressl.com")
        }.build()

    val request =
      Request
        .Builder()
        .url("https://cloudflare-dns.com/cdn-cgi/trace")
        .build()
    client.newCall(request).execute().use { response ->
      assertThat(response.code).isEqualTo(200)
      assertThat(response.protocol).isEqualTo(Protocol.HTTP_2)
      assertThat(response.body.string()).contains("h=cloudflare-dns.com")
    }
  }
}
