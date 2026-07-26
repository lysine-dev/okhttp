/*
 * Copyright (C) 2015 Square, Inc.
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
package okhttp3.internal.connection

import assertk.assertThat
import assertk.assertions.containsExactlyInAnyOrder
import assertk.assertions.isFalse
import assertk.assertions.isNotNull
import assertk.assertions.isNull
import assertk.assertions.isTrue
import java.io.IOException
import java.security.cert.CertificateException
import javax.net.ssl.SSLHandshakeException
import javax.net.ssl.SSLSocket
import okhttp3.ConnectionSpec
import okhttp3.OkHttpClientTestRule
import okhttp3.TestValueFactory
import okhttp3.TlsVersion
import okhttp3.testing.PlatformRule
import okhttp3.tls.internal.TlsUtil.localhost
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.RegisterExtension

class RetryConnectionTest {
  private val factory = TestValueFactory()
  private val handshakeCertificates = localhost()
  private val retryableException = SSLHandshakeException("Simulated handshake exception")

  @RegisterExtension
  val clientTestRule = OkHttpClientTestRule()

  @RegisterExtension
  val platform = PlatformRule()

  private var client = clientTestRule.newClient()

  @AfterEach internal fun tearDown() {
    factory.close()
  }

  @Test fun nonRetryableIOException() {
    val exception = IOException("Non-handshake exception")
    assertThat(retryTlsHandshake(exception)).isFalse()
  }

  @Test fun nonRetryableSSLHandshakeException() {
    val exception =
      SSLHandshakeException("Certificate handshake exception").apply {
        initCause(CertificateException())
      }
    assertThat(retryTlsHandshake(exception)).isFalse()
  }

  @Test fun retryableSSLHandshakeException() {
    assertThat(retryTlsHandshake(retryableException)).isTrue()
  }

  @Test fun someFallbacksSupported() {
    val sslV3 =
      ConnectionSpec
        .Builder(ConnectionSpec.MODERN_TLS)
        .tlsVersions(TlsVersion.SSL_3_0)
        .build()
    val routePlanner = factory.newRoutePlanner(client)
    val route = factory.newRoute()
    val connectionSpecs = listOf(ConnectionSpec.MODERN_TLS, ConnectionSpec.COMPATIBLE_TLS, sslV3)
    val enabledSocketTlsVersions =
      arrayOf(
        TlsVersion.TLS_1_2,
        TlsVersion.TLS_1_1,
        TlsVersion.TLS_1_0,
      )
    var socket = createSocketWithEnabledProtocols(*enabledSocketTlsVersions)

    // MODERN_TLS is used here.
    val attempt0 =
      routePlanner
        .planConnectToRoute(route)
        .planWithCurrentOrInitialConnectionSpec(connectionSpecs, socket)
    assertThat(attempt0.isTlsFallback).isFalse()
    connectionSpecs[attempt0.connectionSpecIndex].apply(socket, attempt0.isTlsFallback)
    assertEnabledProtocols(socket, TlsVersion.TLS_1_2)
    val attempt1 = attempt0.nextConnectionSpec(connectionSpecs, socket)
    assertThat(attempt1).isNotNull()
    assertThat(attempt1!!.isTlsFallback).isTrue()
    socket.close()

    // COMPATIBLE_TLS is used here.
    socket = createSocketWithEnabledProtocols(*enabledSocketTlsVersions)
    connectionSpecs[attempt1.connectionSpecIndex].apply(socket, attempt1.isTlsFallback)

    if (platform.isConscrypt()) {
      // Conscrypt 2.5.2 deprecated TLS 1.0 and 1.1, and 2.6 dropped them
      assertEnabledProtocols(socket, TlsVersion.TLS_1_2)
    } else {
      assertEnabledProtocols(socket, TlsVersion.TLS_1_2, TlsVersion.TLS_1_1, TlsVersion.TLS_1_0)
    }

    val attempt2 = attempt1.nextConnectionSpec(connectionSpecs, socket)
    assertThat(attempt2).isNull()
    socket.close()

    // sslV3 is not used because SSLv3 is not enabled on the socket.
  }

  private fun createSocketWithEnabledProtocols(vararg tlsVersions: TlsVersion): SSLSocket =
    (handshakeCertificates.sslSocketFactory().createSocket() as SSLSocket).apply {
      enabledProtocols = javaNames(*tlsVersions)
    }

  private fun assertEnabledProtocols(
    socket: SSLSocket,
    vararg required: TlsVersion,
  ) {
    assertThat(socket.enabledProtocols.toList()).containsExactlyInAnyOrder(*javaNames(*required))
  }

  private fun javaNames(vararg tlsVersions: TlsVersion) = tlsVersions.map { it.javaName }.toTypedArray()
}
