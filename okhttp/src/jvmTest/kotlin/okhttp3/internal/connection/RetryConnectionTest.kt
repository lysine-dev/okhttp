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
import assertk.assertions.hasMessage
import assertk.assertions.isEmpty
import assertk.assertions.isEqualTo
import assertk.assertions.isFalse
import assertk.assertions.isNotEqualTo
import assertk.assertions.isNotNull
import assertk.assertions.isNull
import assertk.assertions.isTrue
import java.io.IOException
import java.security.cert.CertificateException
import javax.net.ssl.SSLException
import javax.net.ssl.SSLHandshakeException
import javax.net.ssl.SSLSocket
import kotlin.test.assertFailsWith
import okhttp3.ConnectionSpec
import okhttp3.FakeDns
import okhttp3.OkHttpClientTestRule
import okhttp3.Route
import okhttp3.TestValueFactory
import okhttp3.TlsVersion
import okhttp3.internal.dns.ResourceRecord
import okhttp3.internal.ech.EchRetryPlan
import okhttp3.internal.ech.EchUntrustedException
import okhttp3.internal.platform.Platform
import okhttp3.testing.PlatformRule
import okhttp3.tls.internal.TlsUtil.localhost
import okio.ByteString
import okio.ByteString.Companion.encodeUtf8
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.RegisterExtension

class RetryConnectionTest {
  private val factory = TestValueFactory()
  private val handshakeCertificates = localhost()
  private val retryableException = SSLHandshakeException("Simulated handshake exception")
  private val echRetryException = SSLHandshakeException("ECH Mismatch with updated config")
  private val echDisabledException = SSLHandshakeException("ECH Mismatch without config")
  private val echRetryPlan =
    EchRetryPlan.getOrNull(
      publicName = "public.tls-ech.dev",
      configList = "retry config".encodeUtf8(),
    )!!
  private val echDisabledPlan =
    EchRetryPlan.getOrNull(
      publicName = "public.tls-ech.dev",
      configList = null,
    )!!

  @RegisterExtension
  val clientTestRule = OkHttpClientTestRule()

  @RegisterExtension
  val platform =
    PlatformRule(
      platform =
        object : Platform() {
          override fun echRetryPlan(exception: SSLException): EchRetryPlan? =
            when {
              exception === echRetryException -> echRetryPlan
              exception === echDisabledException -> echDisabledPlan
              else -> null
            }
        },
    )

  private var client = clientTestRule.newClient()

  @AfterEach internal fun tearDown() {
    factory.close()
  }

  @Test fun nonRetryableIOException() {
    val exception = IOException("Non-handshake exception")
    assertThat(attemptAnotherConnectionSpec(exception)).isFalse()
  }

  @Test fun nonRetryableSSLHandshakeException() {
    val exception =
      SSLHandshakeException("Certificate handshake exception").apply {
        initCause(CertificateException())
      }
    assertThat(attemptAnotherConnectionSpec(exception)).isFalse()
  }

  @Test fun retryableSSLHandshakeException() {
    assertThat(attemptAnotherConnectionSpec(retryableException)).isTrue()
  }

  @Test fun echRetryConfigIsUsedOnceWithoutTlsFallback() {
    val address = newEchAddress()
    val routePlanner = factory.newRoutePlanner(client, address)
    val route = factory.newRoute(address)
    val connectionSpecs = listOf(ConnectionSpec.MODERN_TLS, ConnectionSpec.COMPATIBLE_TLS)
    val socket = createEchSocket()
    val attempt0 =
      routePlanner
        .planConnectToRoute(route)
        .planWithCurrentOrInitialConnectionSpec(connectionSpecs, socket)

    val attempt1 = attempt0.nextConnectionSpec(connectionSpecs, socket, echRetryException)

    assertThat(attempt1).isNotNull()
    assertThat(attempt1!!.route.echConfigList).isEqualTo(echRetryPlan.configList)
    assertThat(attempt1.isTlsFallback).isFalse()
    assertThat(verifiedHostnames).isEqualTo(listOf(echRetryPlan.publicName))

    verifiedHostnames.clear()
    val attempt2 = attempt1.nextConnectionSpec(connectionSpecs, socket, retryableException)
    assertThat(attempt2).isNull()
    // An ordinary handshake failure doesn't verify a public hostname.
    assertThat(verifiedHostnames).isEmpty()
    socket.close()
  }

  /** https://www.rfc-editor.org/rfc/rfc9849.html#section-6.1.6 */
  @Test fun echRetryUsesOnlyAddressesFromOriginalDnsResults() {
    val dns = FakeDns()
    val hostname = "stale.tls-ech.dev"
    val originalAddresses = dns.allocate(2)
    val newAddress = dns.allocate(1).single()
    factory.dns = dns
    factory.uriHost = hostname
    dns[hostname] =
      listOf(
        ResourceRecord.Https(
          name = hostname,
          timeToLive = 5,
          echConfigList = "stale config".encodeUtf8(),
        ),
        *originalAddresses
          .map {
            ResourceRecord.IpAddress(
              name = hostname,
              timeToLive = 5,
              address = it,
            )
          }.toTypedArray(),
      )
    val address = newEchAddress()
    val routePlanner = factory.newRoutePlanner(client, address)
    val connectionSpecs = listOf(ConnectionSpec.MODERN_TLS)
    val socket = createEchSocket()
    val attempt0 =
      routePlanner
        .planConnect()
        .planWithCurrentOrInitialConnectionSpec(connectionSpecs, socket)

    // A new DNS result must not influence a retry of the previous ECH configuration.
    dns[hostname] = listOf(newAddress)
    val attempt1 = attempt0.nextConnectionSpec(connectionSpecs, socket, echRetryException)

    assertThat(attempt1).isNotNull()
    assertThat(attempt1!!.route.socketAddress.address).isEqualTo(originalAddresses[0])
    assertThat(attempt1.route.socketAddress.address).isNotEqualTo(newAddress)
    assertThat(verifiedHostnames).isEqualTo(listOf(echRetryPlan.publicName))
    dns.assertRequests(hostname)
    socket.close()
  }

  @Test fun untrustedEchRetryConfigIsNotRetried() {
    val address = newEchAddress(verified = false)
    val routePlanner = factory.newRoutePlanner(client, address)
    val route = factory.newRoute(address)
    val connectionSpecs = listOf(ConnectionSpec.MODERN_TLS, ConnectionSpec.COMPATIBLE_TLS)
    val socket = createEchSocket()
    val attempt0 =
      routePlanner
        .planConnectToRoute(route)
        .planWithCurrentOrInitialConnectionSpec(connectionSpecs, socket)

    // not retried because validation failed
    val e =
      assertFailsWith<EchUntrustedException> {
        attempt0.nextConnectionSpec(connectionSpecs, socket, echRetryException)
      }
    assertThat(e).hasMessage("public_name 'public.tls-ech.dev' not verified")

    assertThat(verifiedHostnames).isEqualTo(listOf(echRetryPlan.publicName))
    socket.close()
  }

  /**
   * A server that offers no retry config has securely disabled ECH, so we retry without it.
   *
   * https://www.rfc-editor.org/rfc/rfc9849.html#section-6.1.6
   */
  @Test fun missingEchRetryConfigIsRetriedWithout() {
    val address = newEchAddress()
    val routePlanner = factory.newRoutePlanner(client, address)
    val route = factory.newRoute(address).withEchConfigList("stale config".encodeUtf8())
    val connectionSpecs = listOf(ConnectionSpec.MODERN_TLS, ConnectionSpec.COMPATIBLE_TLS)
    val socket = createEchSocket()
    val attempt0 =
      routePlanner
        .planConnectToRoute(route)
        .planWithCurrentOrInitialConnectionSpec(connectionSpecs, socket)

    val attempt1 = attempt0.nextConnectionSpec(connectionSpecs, socket, echDisabledException)

    assertThat(attempt1).isNotNull()
    assertThat(attempt1!!.route.echConfigList).isNull()
    assertThat(attempt1.isTlsFallback).isFalse()
    assertThat(verifiedHostnames).isEqualTo(listOf(echDisabledPlan.publicName))

    // Having disabled ECH once, we don't do it again.
    verifiedHostnames.clear()
    val attempt2 = attempt1.nextConnectionSpec(connectionSpecs, socket, echDisabledException)
    assertThat(attempt2).isNull()
    assertThat(verifiedHostnames).isEmpty()
    socket.close()
  }

  /**
   * A retry config in response to a retry config signals a misconfigured server, but the server may
   * still securely disable ECH.
   *
   * https://www.rfc-editor.org/rfc/rfc9849.html#section-6.1.6
   */
  @Test fun echRetryConfigIsRetriedOnceOnly() {
    val address = factory.newHttpsAddress(hostnameVerifier = { _, _ -> true })
    val routePlanner = factory.newRoutePlanner(client, address)
    val route = factory.newRoute(address).withEchConfigList("stale config".encodeUtf8())
    val connectionSpecs = listOf(ConnectionSpec.MODERN_TLS, ConnectionSpec.COMPATIBLE_TLS)
    val socket = createEchSocket()
    val attempt0 =
      routePlanner
        .planConnectToRoute(route)
        .planWithCurrentOrInitialConnectionSpec(connectionSpecs, socket)

    val attempt1 = attempt0.nextConnectionSpec(connectionSpecs, socket, echRetryException)
    assertThat(attempt1).isNotNull()
    assertThat(attempt1!!.route.echConfigList).isEqualTo(echRetryPlan.configList)

    // At most two attempts are made.
    assertThat(attempt1.nextConnectionSpec(connectionSpecs, socket, echRetryException)).isNull()
    assertThat(attempt1.nextConnectionSpec(connectionSpecs, socket, echDisabledException)).isNull()
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
    val attempt1 = attempt0.nextConnectionSpec(connectionSpecs, socket, retryableException)
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

    val attempt2 = attempt1.nextConnectionSpec(connectionSpecs, socket, retryableException)
    assertThat(attempt2).isNull()
    socket.close()

    // sslV3 is not used because SSLv3 is not enabled on the socket.
  }

  /** Records each hostname the [newEchAddress] verifier was asked to verify. */
  private val verifiedHostnames = mutableListOf<String>()

  private fun newEchAddress(verified: Boolean = true) =
    factory.newHttpsAddress(
      hostnameVerifier = { hostname, _ ->
        verifiedHostnames += hostname
        verified
      },
    )

  /**
   * ECH is only carried by TLS 1.3, so every ECH attempt needs it enabled.
   *
   * https://www.rfc-editor.org/rfc/rfc9849.html#section-1
   */
  private fun createEchSocket(): SSLSocket = createSocketWithEnabledProtocols(TlsVersion.TLS_1_3, TlsVersion.TLS_1_2)

  private fun createSocketWithEnabledProtocols(vararg tlsVersions: TlsVersion): SSLSocket =
    (handshakeCertificates.sslSocketFactory().createSocket() as SSLSocket).apply {
      enabledProtocols = javaNames(*tlsVersions)
    }

  private fun Route.withEchConfigList(echConfigList: ByteString): Route =
    Route(
      address = address,
      proxy = proxy,
      socketAddress = socketAddress,
      echConfigList = echConfigList,
    )

  private fun assertEnabledProtocols(
    socket: SSLSocket,
    vararg required: TlsVersion,
  ) {
    assertThat(socket.enabledProtocols.toList()).containsExactlyInAnyOrder(*javaNames(*required))
  }

  private fun javaNames(vararg tlsVersions: TlsVersion) = tlsVersions.map { it.javaName }.toTypedArray()
}
