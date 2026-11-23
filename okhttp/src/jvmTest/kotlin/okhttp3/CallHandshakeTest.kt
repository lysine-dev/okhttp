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
package okhttp3

import assertk.assertThat
import assertk.assertions.containsExactly
import assertk.assertions.isEqualTo
import assertk.assertions.isIn
import javax.net.ssl.SSLSocket
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.junit5.StartStop
import okhttp3.CipherSuite.Companion.TLS_AES_128_GCM_SHA256
import okhttp3.CipherSuite.Companion.TLS_AES_256_GCM_SHA384
import okhttp3.CipherSuite.Companion.TLS_CHACHA20_POLY1305_SHA256
import okhttp3.CipherSuite.Companion.TLS_ECDHE_ECDSA_WITH_AES_128_GCM_SHA256
import okhttp3.CipherSuite.Companion.TLS_ECDHE_ECDSA_WITH_AES_256_CBC_SHA384
import okhttp3.CipherSuite.Companion.TLS_ECDHE_ECDSA_WITH_AES_256_GCM_SHA384
import okhttp3.CipherSuite.Companion.TLS_ECDHE_ECDSA_WITH_CHACHA20_POLY1305_SHA256
import okhttp3.CipherSuite.Companion.TLS_ECDHE_RSA_WITH_AES_128_GCM_SHA256
import okhttp3.CipherSuite.Companion.TLS_ECDHE_RSA_WITH_AES_256_GCM_SHA384
import okhttp3.CipherSuite.Companion.TLS_ECDHE_RSA_WITH_CHACHA20_POLY1305_SHA256
import okhttp3.internal.effectiveCipherSuites
import okhttp3.internal.platform.Platform
import okhttp3.testing.PlatformRule
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.RegisterExtension

class CallHandshakeTest {
  private lateinit var client: OkHttpClient

  @StartStop
  private val server = MockWebServer()

  @RegisterExtension
  @JvmField
  val clientTestRule = OkHttpClientTestRule()

  @RegisterExtension
  @JvmField
  var platform = PlatformRule()

  private val handshakeCertificates = platform.localhostHandshakeCertificates()

  /** Ciphers in order we observed directly on the socket. */
  private lateinit var handshakeEnabledCipherSuites: List<String>

  /** Ciphers in order we observed on sslSocketFactory defaults. */
  private lateinit var defaultEnabledCipherSuites: List<String>

  /** Ciphers in order we observed on sslSocketFactory supported. */
  private lateinit var defaultSupportedCipherSuites: List<String>

  val expectedModernTls12CipherSuites =
    listOf(TLS_ECDHE_ECDSA_WITH_AES_128_GCM_SHA256, TLS_ECDHE_ECDSA_WITH_AES_256_GCM_SHA384, TLS_ECDHE_ECDSA_WITH_AES_256_CBC_SHA384)
  val expectedModernTls13CipherSuites = listOf(TLS_ECDHE_ECDSA_WITH_AES_128_GCM_SHA256, TLS_AES_128_GCM_SHA256, TLS_AES_256_GCM_SHA384)

  @BeforeEach
  fun setup() {
    server.enqueue(MockResponse())

    client =
      clientTestRule
        .newClientBuilder()
        .sslSocketFactory(
          handshakeCertificates.sslSocketFactory(),
          handshakeCertificates.trustManager,
        ).hostnameVerifier(RecordingHostnameVerifier())
        .build()
    server.useHttps(handshakeCertificates.sslSocketFactory())

    defaultEnabledCipherSuites =
      handshakeCertificates.sslSocketFactory().defaultCipherSuites.toList()
    defaultSupportedCipherSuites =
      handshakeCertificates.sslSocketFactory().supportedCipherSuites.toList()
  }

  @Test
  fun testDefaultHandshakeCipherSuiteOrderingTls12Restricted() {
    // We are avoiding making guarantees on ordering of secondary Platforms.
    platform.assumeNotConscrypt()
    platform.assumeNotBouncyCastle()

    val client = makeClient(ConnectionSpec.RESTRICTED_TLS, TlsVersion.TLS_1_2)

    val handshake = makeRequest(client)

    assertThat(handshake.cipherSuite).isIn(*expectedModernTls12CipherSuites.toTypedArray())

    // Probably something like
    // TLS_AES_128_GCM_SHA256
    // TLS_AES_256_GCM_SHA384
    // TLS_ECDHE_ECDSA_WITH_AES_256_GCM_SHA384
    // TLS_ECDHE_ECDSA_WITH_AES_128_GCM_SHA256
    // TLS_ECDHE_RSA_WITH_AES_256_GCM_SHA384
    // TLS_ECDHE_RSA_WITH_AES_128_GCM_SHA256
    assertThat(handshakeEnabledCipherSuites).containsExactly(
      *expectedConnectionCipherSuites(client).toTypedArray(),
    )
    assertThat(handshake.tlsVersion).isEqualTo(TlsVersion.TLS_1_2)
  }

  @Test
  fun testDefaultHandshakeCipherSuiteOrderingTls12Modern() {
    // We are avoiding making guarantees on ordering of secondary Platforms.
    platform.assumeNotConscrypt()
    platform.assumeNotBouncyCastle()

    val client = makeClient(ConnectionSpec.MODERN_TLS, TlsVersion.TLS_1_2)

    val handshake = makeRequest(client)

    assertThat(handshake.cipherSuite).isIn(*expectedModernTls12CipherSuites.toTypedArray())

    // Probably something like
    // TLS_AES_128_GCM_SHA256
    // TLS_AES_256_GCM_SHA384
    // TLS_ECDHE_ECDSA_WITH_AES_256_GCM_SHA384
    // TLS_ECDHE_ECDSA_WITH_AES_128_GCM_SHA256
    // TLS_ECDHE_RSA_WITH_AES_256_GCM_SHA384
    // TLS_RSA_WITH_AES_256_GCM_SHA384
    // TLS_ECDHE_RSA_WITH_AES_128_GCM_SHA256
    // TLS_RSA_WITH_AES_128_GCM_SHA256
    // TLS_ECDHE_RSA_WITH_AES_256_CBC_SHA
    // TLS_RSA_WITH_AES_256_CBC_SHA
    // TLS_ECDHE_RSA_WITH_AES_128_CBC_SHA
    // TLS_RSA_WITH_AES_128_CBC_SHA
    assertThat(handshakeEnabledCipherSuites).containsExactly(
      *expectedConnectionCipherSuites(client).toTypedArray(),
    )
    assertThat(handshake.tlsVersion).isEqualTo(TlsVersion.TLS_1_2)
  }

  @Test
  fun testDefaultHandshakeCipherSuiteOrderingTls13Modern() {
    // We are avoiding making guarantees on ordering of secondary Platforms.
    platform.assumeNotBouncyCastle()
    platform.assumeNotJdk8Alpn()

    val client = makeClient(ConnectionSpec.MODERN_TLS, TlsVersion.TLS_1_3)

    val handshake = makeRequest(client)

    assertThat(handshake.cipherSuite).isIn(*expectedModernTls13CipherSuites.toTypedArray())

    // TODO: filter down to TLSv1.3 when only activated.
    // Probably something like
    // TLS_AES_128_GCM_SHA256
    // TLS_AES_256_GCM_SHA384
    // TLS_ECDHE_ECDSA_WITH_AES_256_GCM_SHA384
    // TLS_ECDHE_ECDSA_WITH_AES_128_GCM_SHA256
    // TLS_ECDHE_RSA_WITH_AES_256_GCM_SHA384
    // TLS_RSA_WITH_AES_256_GCM_SHA384
    // TLS_ECDHE_RSA_WITH_AES_128_GCM_SHA256
    // TLS_RSA_WITH_AES_128_GCM_SHA256
    // TLS_ECDHE_RSA_WITH_AES_256_CBC_SHA
    // TLS_RSA_WITH_AES_256_CBC_SHA
    // TLS_ECDHE_RSA_WITH_AES_128_CBC_SHA
    // TLS_RSA_WITH_AES_128_CBC_SHA
    assertThat(handshakeEnabledCipherSuites).containsExactly(
      *expectedConnectionCipherSuites(client).toTypedArray(),
    )
    assertThat(handshake.tlsVersion).isEqualTo(TlsVersion.TLS_1_3)
  }

  @Test
  fun testHandshakeCipherSuiteOrderingWhenReversed() {
    // We are avoiding making guarantees on ordering of secondary Platforms.
    platform.assumeNotConscrypt()
    platform.assumeNotBouncyCastle()

    val reversed = ConnectionSpec.COMPATIBLE_TLS.cipherSuites!!.reversed()
    val client =
      makeClient(
        ConnectionSpec.COMPATIBLE_TLS,
        TlsVersion.TLS_1_2,
        reversed,
      )

    makeRequest(client)

    val expectedConnectionCipherSuites = expectedConnectionCipherSuites(client)
    // Will choose a poor cipher suite but not plaintext.
//    assertThat(handshake.cipherSuite).isEqualTo("TLS_ECDHE_ECDSA_WITH_AES_128_GCM_SHA256")
    assertThat(handshakeEnabledCipherSuites).containsExactly(
      *expectedConnectionCipherSuites.toTypedArray(),
    )
  }

  @Test
  fun clientOrderApplied() {
//    // Flaky in CI
//    // CallHandshakeTest[jvm] > defaultOrderMaintained()[jvm] FAILED
//    //  org.bouncycastle.tls.TlsFatalAlertReceived: handshake_failure(40)
//    platform.assumeNotBouncyCastle()

    val client = makeClient()
    makeRequest(client)

    // As of OkHttp 5 we now apply the ordering from the OkHttpClient, which defaults to MODERN_TLS
    // Clients might need a changed order, but can at least define a preferred order to override that default.
    val socketOrderedByDefaults =
      handshakeEnabledCipherSuites.sortedBy { ConnectionSpec.MODERN_TLS.cipherSuitesAsString!!.indexOf(it) }

    assertThat(handshakeEnabledCipherSuites).containsExactly(
      *socketOrderedByDefaults.toTypedArray(),
    )
  }

  @Test
  fun advertisedOrderInRestricted() {
    assertThat(ConnectionSpec.RESTRICTED_TLS.cipherSuites!!).containsExactly(
      TLS_AES_128_GCM_SHA256,
      TLS_AES_256_GCM_SHA384,
      TLS_CHACHA20_POLY1305_SHA256,
      TLS_ECDHE_ECDSA_WITH_AES_128_GCM_SHA256,
      TLS_ECDHE_RSA_WITH_AES_128_GCM_SHA256,
      TLS_ECDHE_ECDSA_WITH_AES_256_GCM_SHA384,
      TLS_ECDHE_RSA_WITH_AES_256_GCM_SHA384,
      TLS_ECDHE_ECDSA_WITH_CHACHA20_POLY1305_SHA256,
      TLS_ECDHE_RSA_WITH_CHACHA20_POLY1305_SHA256,
    )
  }

  @Test
  fun effectiveOrderInRestrictedJdk11() {
    platform.assumeJdkVersion(11)
    // We are avoiding making guarantees on ordering of secondary Platforms.
    platform.assumeNotConscrypt()
    platform.assumeNotBouncyCastle()

    val platform = Platform.get()
    val platformDefaultCipherSuites =
      platform.newSslSocketFactory(platform.platformTrustManager()).defaultCipherSuites
    val cipherSuites =
      ConnectionSpec.RESTRICTED_TLS.effectiveCipherSuites(platformDefaultCipherSuites)

    if (cipherSuites.contains(TLS_CHACHA20_POLY1305_SHA256.javaName)) {
      assertThat(cipherSuites).containsExactly(
        TLS_AES_128_GCM_SHA256.javaName,
        TLS_AES_256_GCM_SHA384.javaName,
        TLS_CHACHA20_POLY1305_SHA256.javaName,
        TLS_ECDHE_ECDSA_WITH_AES_128_GCM_SHA256.javaName,
        TLS_ECDHE_RSA_WITH_AES_128_GCM_SHA256.javaName,
        TLS_ECDHE_ECDSA_WITH_AES_256_GCM_SHA384.javaName,
        TLS_ECDHE_RSA_WITH_AES_256_GCM_SHA384.javaName,
        TLS_ECDHE_ECDSA_WITH_CHACHA20_POLY1305_SHA256.javaName,
        TLS_ECDHE_RSA_WITH_CHACHA20_POLY1305_SHA256.javaName,
      )
    } else {
      assertThat(cipherSuites).containsExactly(
        TLS_AES_128_GCM_SHA256.javaName,
        TLS_AES_256_GCM_SHA384.javaName,
        TLS_ECDHE_ECDSA_WITH_AES_256_GCM_SHA384.javaName,
        TLS_ECDHE_ECDSA_WITH_AES_128_GCM_SHA256.javaName,
        TLS_ECDHE_RSA_WITH_AES_256_GCM_SHA384.javaName,
        TLS_ECDHE_RSA_WITH_AES_128_GCM_SHA256.javaName,
      )
    }
  }

  private fun expectedConnectionCipherSuites(client: OkHttpClient): Set<String> =
    client.connectionSpecs
      .first()
      .cipherSuites!!
      .map {
        it.javaName
      }.intersect(defaultEnabledCipherSuites.toSet())

  private fun makeClient(
    connectionSpec: ConnectionSpec? = null,
    tlsVersion: TlsVersion? = null,
    cipherSuites: List<CipherSuite>? = null,
  ): OkHttpClient =
    this.client
      .newBuilder()
      .apply {
        if (connectionSpec != null) {
          connectionSpecs(
            listOf(
              ConnectionSpec
                .Builder(connectionSpec)
                .apply {
                  if (tlsVersion != null) {
                    tlsVersions(tlsVersion)
                  }
                  if (cipherSuites != null) {
                    cipherSuites(*cipherSuites.toTypedArray())
                  }
                }.build(),
            ),
          )
        }
      }.addNetworkInterceptor {
        val socket = it.connection()!!.socket() as SSLSocket

        handshakeEnabledCipherSuites = socket.enabledCipherSuites.toList()

        it.proceed(it.request())
      }.build()

  private fun makeRequest(client: OkHttpClient): Handshake {
    val call = client.newCall(Request(server.url("/")))
    return call.execute().use { it.handshake!! }
  }
}
