/*
 * Copyright (C) 2026 Block, Inc.
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
import androidx.test.platform.app.InstrumentationRegistry
import java.io.ByteArrayInputStream
import java.net.InetAddress
import java.security.KeyStore
import java.security.cert.CertificateFactory
import java.util.Base64
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager
import kotlin.test.assertContains
import kotlin.test.assertEquals
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.dnsoverhttps.DnsOverHttps
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test

class EncryptedClientHelloTest {
  @Test
  fun greenPathAcceptsEncryptedClientHello() {
    val response = fixture().get(GREEN_NAME)

    assertContains(response, "\"echAccepted\":true")
    assertContains(response, "\"serverName\":\"$GREEN_NAME\"")
  }

  @Test
  fun rejectedConfigIsRetriedWithServerConfig() {
    val response = fixture().get(RETRY_NAME)

    assertContains(response, "\"echAccepted\":true")
    assertContains(response, "\"serverName\":\"$RETRY_NAME\"")
  }

  @Test
  fun rejectedConfigWithoutServerConfigIsRetriedWithoutEch() {
    val response = fixture().get(DISABLED_NAME)

    assertContains(response, "\"echAccepted\":false")
    assertContains(response, "\"serverName\":\"$DISABLED_NAME\"")
  }

  private fun fixture(): Fixture {
    val arguments = InstrumentationRegistry.getArguments()
    assumeTrue(arguments.getString("ech") == "true", "requires the host-side ECH fixtures")
    assumeTrue(Build.VERSION.SDK_INT >= 37, "ECH requires Android API 37")
    val dohPort = requireNotNull(arguments.getString("dohPort")).toInt()
    val caCertificate = Base64.getDecoder().decode(requireNotNull(arguments.getString("caCertificate")))
    val (sslContext, trustManager) = sslContext(caCertificate)
    return Fixture(dohPort, sslContext, trustManager)
  }

  private class Fixture(
    dohPort: Int,
    sslContext: SSLContext,
    trustManager: X509TrustManager,
  ) {
    private val client: OkHttpClient

    init {
      val bootstrapClient =
        OkHttpClient
          .Builder()
          .sslSocketFactory(sslContext.socketFactory, trustManager)
          .build()
      val dns =
        DnsOverHttps
          .Builder()
          .client(bootstrapClient)
          .url("https://$DOH_NAME:$dohPort/dns-query".toHttpUrl())
          .bootstrapDnsHosts(InetAddress.getByName("127.0.0.1"))
          .includeServiceMetadata(true)
          .resolvePrivateAddresses(true)
          .post(true)
          .build()
      client = bootstrapClient.newBuilder().dns(dns).build()
    }

    fun get(hostname: String): String =
      client
        .newCall(Request("https://$hostname/".toHttpUrl()))
        .execute()
        .use { response ->
          assertEquals(200, response.code)
          response.body.string()
        }
  }

  private companion object {
    private fun sslContext(caCertificatePem: ByteArray): Pair<SSLContext, X509TrustManager> {
      val certificate =
        CertificateFactory
          .getInstance("X.509")
          .generateCertificate(ByteArrayInputStream(caCertificatePem))
      val keyStore = KeyStore.getInstance(KeyStore.getDefaultType())
      keyStore.load(null)
      keyStore.setCertificateEntry("fixture", certificate)
      val trustManagerFactory = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
      trustManagerFactory.init(keyStore)
      val trustManager = trustManagerFactory.trustManagers.single() as X509TrustManager
      val sslContext = SSLContext.getInstance("TLS")
      sslContext.init(null, arrayOf(trustManager), null)
      return sslContext to trustManager
    }

    private const val DOH_NAME = "doh.test"
    private const val GREEN_NAME = "green.secret.test"
    private const val RETRY_NAME = "retry.secret.test"
    private const val DISABLED_NAME = "disabled.secret.test"
  }
}
