/*
 * Copyright (C) 2016 Square, Inc.
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
package okhttp3.internal.tls

import assertk.assertThat
import assertk.assertions.endsWith
import assertk.assertions.isEqualTo
import assertk.assertions.isNull
import assertk.assertions.startsWith
import java.io.IOException
import java.net.SocketException
import java.security.GeneralSecurityException
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.util.Arrays
import javax.net.ssl.KeyManager
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLException
import javax.net.ssl.SSLHandshakeException
import javax.net.ssl.SSLPeerUnverifiedException
import javax.net.ssl.SSLSocketFactory
import javax.net.ssl.TrustManager
import javax.security.auth.x500.X500Principal
import kotlin.test.assertFailsWith
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.junit5.StartStop
import okhttp3.CallEvent.CallFailed
import okhttp3.CallEvent.CallStart
import okhttp3.CallEvent.ConnectStart
import okhttp3.CallEvent.DnsEnd
import okhttp3.CallEvent.DnsStart
import okhttp3.CallEvent.ProxySelectEnd
import okhttp3.CallEvent.ProxySelectStart
import okhttp3.CallEvent.SecureConnectStart
import okhttp3.EventRecorder
import okhttp3.OkHttpClient
import okhttp3.OkHttpClientTestRule
import okhttp3.Request
import okhttp3.internal.http2.ConnectionShutdownException
import okhttp3.testing.Flaky
import okhttp3.testing.PlatformRule
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate
import okhttp3.tls.internal.TlsUtil.newKeyManager
import okhttp3.tls.internal.TlsUtil.newTrustManager
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.RegisterExtension
import org.junitpioneer.jupiter.RetryingTest

@Tag("Slowish")
class ClientAuthTest {
  @RegisterExtension
  val platform = PlatformRule()

  @RegisterExtension
  val clientTestRule = OkHttpClientTestRule()

  @StartStop
  private val server = MockWebServer()

  private lateinit var serverRootCa: HeldCertificate
  private lateinit var serverIntermediateCa: HeldCertificate
  private lateinit var serverCert: HeldCertificate
  private lateinit var clientRootCa: HeldCertificate
  private lateinit var clientIntermediateCa: HeldCertificate
  private lateinit var clientCert: HeldCertificate

  @BeforeEach
  fun setUp() {
    platform.assumeNotOpenJSSE()
    platform.assumeNotBouncyCastle()
    serverRootCa =
      HeldCertificate
        .Builder()
        .serialNumber(1L)
        .certificateAuthority(1)
        .commonName("root")
        .addSubjectAlternativeName("root_ca.com")
        .build()
    serverIntermediateCa =
      HeldCertificate
        .Builder()
        .signedBy(serverRootCa)
        .certificateAuthority(0)
        .serialNumber(2L)
        .commonName("intermediate_ca")
        .addSubjectAlternativeName("intermediate_ca.com")
        .build()
    serverCert =
      HeldCertificate
        .Builder()
        .signedBy(serverIntermediateCa)
        .serialNumber(3L)
        .commonName("Local Host")
        .addSubjectAlternativeName(server.hostName)
        .build()
    clientRootCa =
      HeldCertificate
        .Builder()
        .serialNumber(1L)
        .certificateAuthority(1)
        .commonName("root")
        .addSubjectAlternativeName("root_ca.com")
        .build()
    clientIntermediateCa =
      HeldCertificate
        .Builder()
        .signedBy(serverRootCa)
        .certificateAuthority(0)
        .serialNumber(2L)
        .commonName("intermediate_ca")
        .addSubjectAlternativeName("intermediate_ca.com")
        .build()
    clientCert =
      HeldCertificate
        .Builder()
        .signedBy(clientIntermediateCa)
        .serialNumber(4L)
        .commonName("Jethro Willis")
        .addSubjectAlternativeName("jethrowillis.com")
        .build()
  }

  @Test
  fun clientAuthForWants() {
    val client = buildClient(clientCert, clientIntermediateCa.certificate)
    val socketFactory = buildServerSslSocketFactory()
    server.useHttps(socketFactory)
    server.requestClientAuth()
    server.enqueue(
      MockResponse
        .Builder()
        .body("abc")
        .build(),
    )
    val call = client.newCall(Request.Builder().url(server.url("/")).build())
    val response = call.execute()
    assertThat(response.handshake!!.peerPrincipal)
      .isEqualTo(X500Principal("CN=Local Host"))
    assertThat(response.handshake!!.localPrincipal)
      .isEqualTo(X500Principal("CN=Jethro Willis"))
    assertThat(response.body.string()).isEqualTo("abc")
  }

  @Test
  fun clientAuthForNeeds() {
    val client = buildClient(clientCert, clientIntermediateCa.certificate)
    val socketFactory = buildServerSslSocketFactory()
    server.useHttps(socketFactory)
    server.requireClientAuth()
    server.enqueue(
      MockResponse
        .Builder()
        .body("abc")
        .build(),
    )
    val call = client.newCall(Request.Builder().url(server.url("/")).build())
    val response = call.execute()
    assertThat(response.handshake!!.peerPrincipal).isEqualTo(
      X500Principal("CN=Local Host"),
    )
    assertThat(response.handshake!!.localPrincipal).isEqualTo(
      X500Principal("CN=Jethro Willis"),
    )
    assertThat(response.body.string()).isEqualTo("abc")
  }

  @Test
  fun clientAuthSkippedForNone() {
    val client = buildClient(clientCert, clientIntermediateCa.certificate)
    val socketFactory = buildServerSslSocketFactory()
    server.useHttps(socketFactory)
    server.noClientAuth()
    server.enqueue(
      MockResponse
        .Builder()
        .body("abc")
        .build(),
    )
    val call = client.newCall(Request.Builder().url(server.url("/")).build())
    val response = call.execute()
    assertThat(response.handshake!!.peerPrincipal).isEqualTo(
      X500Principal("CN=Local Host"),
    )
    assertThat(response.handshake!!.localPrincipal).isNull()
    assertThat(response.body.string()).isEqualTo("abc")
  }

  @Test
  fun missingClientAuthSkippedForWantsOnly() {
    val client = buildClient(null, clientIntermediateCa.certificate)
    val socketFactory = buildServerSslSocketFactory()
    server.useHttps(socketFactory)
    server.requestClientAuth()
    server.enqueue(
      MockResponse
        .Builder()
        .body("abc")
        .build(),
    )
    val call = client.newCall(Request.Builder().url(server.url("/")).build())
    val response = call.execute()
    assertThat(response.handshake!!.peerPrincipal).isEqualTo(
      X500Principal("CN=Local Host"),
    )
    assertThat(response.handshake!!.localPrincipal).isNull()
    assertThat(response.body.string()).isEqualTo("abc")
  }

  @Flaky
  @RetryingTest(5)
  fun missingClientAuthFailsForNeeds() {
    // Fails with 11.0.1 https://github.com/lysine-dev/okhttp/issues/4598
    // StreamReset stream was reset: PROT...
    val client = buildClient(null, clientIntermediateCa.certificate)
    val socketFactory = buildServerSslSocketFactory()
    server.useHttps(socketFactory)
    server.requireClientAuth()
    val call = client.newCall(Request.Builder().url(server.url("/")).build())
    assertFailsWith<IOException> {
      call.execute()
    }.also { expected ->
      when (expected) {
        is SSLHandshakeException -> {
          // JDK 11+
        }

        is SSLException -> {
          // javax.net.ssl.SSLException: readRecord
        }

        is SocketException -> {
          // Conscrypt, JDK 8 (>= 292), JDK 9
        }

        else -> {
          assertThat(expected.message).isEqualTo("exhausted all routes")
        }
      }
    }
  }

  @Test
  fun commonNameIsNotTrusted() {
    serverCert =
      HeldCertificate
        .Builder()
        .signedBy(serverIntermediateCa)
        .serialNumber(3L)
        .commonName(server.hostName)
        .addSubjectAlternativeName("different-host.com")
        .build()
    val client = buildClient(clientCert, clientIntermediateCa.certificate)
    val socketFactory = buildServerSslSocketFactory()
    server.useHttps(socketFactory)
    server.requireClientAuth()
    val call = client.newCall(Request.Builder().url(server.url("/")).build())
    assertFailsWith<SSLPeerUnverifiedException> {
      call.execute()
    }
  }

  @Test
  fun invalidClientAuthFails() {
    // Fails with https://github.com/lysine-dev/okhttp/issues/4598
    // StreamReset stream was reset: PROT...
    val clientCert2 =
      HeldCertificate
        .Builder()
        .serialNumber(4L)
        .commonName("Jethro Willis")
        .build()
    val client = buildClient(clientCert2)
    val socketFactory = buildServerSslSocketFactory()
    server.useHttps(socketFactory)
    server.requireClientAuth()
    val call = client.newCall(Request.Builder().url(server.url("/")).build())
    assertFailsWith<IOException> {
      call.execute()
    }.also { expected ->
      when (expected) {
        is SSLHandshakeException -> {
          // JDK 11+
        }

        is SSLException -> {
          // javax.net.ssl.SSLException: readRecord
        }

        is SocketException -> {
          // Conscrypt, JDK 8 (>= 292), JDK 9
        }

        is ConnectionShutdownException -> {
          // It didn't fail until it reached the application layer.
        }

        else -> {
          assertThat(expected.message).isEqualTo("exhausted all routes")
        }
      }
    }
  }

  @Test
  fun invalidClientAuthEvents() {
    server.enqueue(
      MockResponse
        .Builder()
        .body("abc")
        .build(),
    )
    clientCert =
      HeldCertificate
        .Builder()
        .signedBy(clientIntermediateCa)
        .serialNumber(4L)
        .commonName("Jethro Willis")
        .addSubjectAlternativeName("jethrowillis.com")
        .validityInterval(1, 2)
        .build()
    var client = buildClient(clientCert, clientIntermediateCa.certificate)
    val eventRecorder = EventRecorder()
    client =
      client
        .newBuilder()
        .eventListener(eventRecorder.eventListener)
        .build()
    val socketFactory = buildServerSslSocketFactory()
    server.useHttps(socketFactory)
    server.requireClientAuth()
    val call = client.newCall(Request.Builder().url(server.url("/")).build())
    assertFailsWith<IOException> {
      call.execute()
    }

    // Observed Events are variable
    // JDK 14
    // CallStart, ProxySelectStart, ProxySelectEnd, DnsStart, DnsEnd, ConnectStart, SecureConnectStart,
    // SecureConnectEnd, ConnectEnd, ConnectionAcquired, RequestHeadersStart, RequestHeadersEnd,
    // ResponseFailed, ConnectionReleased, CallFailed
    // JDK 8
    // CallStart, ProxySelectStart, ProxySelectEnd, DnsStart, DnsEnd, ConnectStart, SecureConnectStart,
    // ConnectFailed, CallFailed
    // Gradle - JDK 11
    // CallStart, ProxySelectStart, ProxySelectEnd, DnsStart, DnsEnd, ConnectStart, SecureConnectStart,
    // SecureConnectEnd, ConnectFailed, CallFailed
    val recordedEventTypes = eventRecorder.recordedEventTypes()
    assertThat(recordedEventTypes).startsWith(
      CallStart::class,
      ProxySelectStart::class,
      ProxySelectEnd::class,
      DnsStart::class,
      DnsEnd::class,
      ConnectStart::class,
      SecureConnectStart::class,
    )
    assertThat(recordedEventTypes).endsWith(CallFailed::class)
  }

  private fun buildClient(
    heldCertificate: HeldCertificate?,
    vararg intermediates: X509Certificate,
  ): OkHttpClient {
    val builder =
      HandshakeCertificates
        .Builder()
        .addTrustedCertificate(serverRootCa.certificate)
    if (heldCertificate != null) {
      builder.heldCertificate(heldCertificate, *intermediates)
    }
    val handshakeCertificates = builder.build()
    return clientTestRule
      .newClientBuilder()
      .sslSocketFactory(
        handshakeCertificates.sslSocketFactory(),
        handshakeCertificates.trustManager,
      ).build()
  }

  private fun buildServerSslSocketFactory(): SSLSocketFactory {
    // The test uses JDK default SSL Context instead of the Platform provided one
    // as Conscrypt seems to have some differences, we only want to test client side here.
    return try {
      val keyManager =
        newKeyManager(
          null,
          serverCert,
          serverIntermediateCa.certificate,
        )
      val trustManager =
        newTrustManager(
          null,
          Arrays.asList(serverRootCa.certificate, clientRootCa.certificate),
          emptyList(),
        )
      val sslContext = SSLContext.getInstance("TLS")
      sslContext.init(
        arrayOf<KeyManager>(keyManager),
        arrayOf<TrustManager>(trustManager),
        SecureRandom(),
      )
      sslContext.socketFactory
    } catch (e: GeneralSecurityException) {
      throw AssertionError(e)
    }
  }
}
