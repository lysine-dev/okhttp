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
import assertk.assertions.contains
import assertk.assertions.isEqualTo
import assertk.assertions.startsWith
import java.io.IOException
import java.security.SecureRandom
import java.security.cert.X509Certificate
import javax.net.ssl.KeyManager
import javax.net.ssl.SSLHandshakeException
import javax.net.ssl.SSLPeerUnverifiedException
import javax.net.ssl.SSLSocketFactory
import javax.net.ssl.TrustManager
import kotlin.test.assertFailsWith
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.SocketEffect.ShutdownConnection
import mockwebserver3.junit5.StartStop
import okhttp3.CertificatePinner
import okhttp3.CertificatePinner.Companion.pin
import okhttp3.OkHttpClientTestRule
import okhttp3.RecordingHostnameVerifier
import okhttp3.Request
import okhttp3.internal.platform.Platform.Companion.get
import okhttp3.testing.PlatformRule
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate
import okhttp3.tls.internal.TlsUtil.newKeyManager
import okhttp3.tls.internal.TlsUtil.newTrustManager
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.RegisterExtension

class CertificatePinnerChainValidationTest {
  @RegisterExtension
  var platform = PlatformRule()

  @RegisterExtension
  var clientTestRule = OkHttpClientTestRule()

  @StartStop
  private val server = MockWebServer()

  @BeforeEach
  fun setup() {
    platform.assumeNotBouncyCastle()
  }

  /**
   * The pinner should pull the root certificate from the trust manager.
   */
  @Test
  fun pinRootNotPresentInChain() {
    // Fails on 11.0.1 https://github.com/lysine-dev/okhttp/issues/4703
    val rootCa =
      HeldCertificate
        .Builder()
        .serialNumber(1L)
        .certificateAuthority(1)
        .commonName("root")
        .build()
    val intermediateCa =
      HeldCertificate
        .Builder()
        .signedBy(rootCa)
        .certificateAuthority(0)
        .serialNumber(2L)
        .commonName("intermediate_ca")
        .build()
    val certificate =
      HeldCertificate
        .Builder()
        .signedBy(intermediateCa)
        .serialNumber(3L)
        .commonName(server.hostName)
        .build()
    val certificatePinner =
      CertificatePinner
        .Builder()
        .add(server.hostName, pin(rootCa.certificate))
        .build()
    val handshakeCertificates =
      HandshakeCertificates
        .Builder()
        .addTrustedCertificate(rootCa.certificate)
        .build()
    val client =
      clientTestRule
        .newClientBuilder()
        .sslSocketFactory(
          handshakeCertificates.sslSocketFactory(),
          handshakeCertificates.trustManager,
        ).hostnameVerifier(RecordingHostnameVerifier())
        .certificatePinner(certificatePinner)
        .build()
    val serverHandshakeCertificates =
      HandshakeCertificates
        .Builder()
        .heldCertificate(certificate, intermediateCa.certificate)
        .build()
    server.useHttps(serverHandshakeCertificates.sslSocketFactory())

    // The request should complete successfully.
    server.enqueue(
      MockResponse
        .Builder()
        .body("abc")
        .build(),
    )
    val call1 =
      client.newCall(
        Request
          .Builder()
          .url(server.url("/"))
          .build(),
      )
    val response1 = call1.execute()
    assertThat(response1.body.string()).isEqualTo("abc")
  }

  /**
   * The pinner should accept an intermediate from the server's chain.
   */
  @Test
  fun pinIntermediatePresentInChain() {
    // Fails on 11.0.1 https://github.com/lysine-dev/okhttp/issues/4703
    val rootCa =
      HeldCertificate
        .Builder()
        .serialNumber(1L)
        .certificateAuthority(1)
        .commonName("root")
        .build()
    val intermediateCa =
      HeldCertificate
        .Builder()
        .signedBy(rootCa)
        .certificateAuthority(0)
        .serialNumber(2L)
        .commonName("intermediate_ca")
        .build()
    val certificate =
      HeldCertificate
        .Builder()
        .signedBy(intermediateCa)
        .serialNumber(3L)
        .commonName(server.hostName)
        .build()
    val certificatePinner =
      CertificatePinner
        .Builder()
        .add(server.hostName, pin(intermediateCa.certificate))
        .build()
    val handshakeCertificates =
      HandshakeCertificates
        .Builder()
        .addTrustedCertificate(rootCa.certificate)
        .build()
    val client =
      clientTestRule
        .newClientBuilder()
        .sslSocketFactory(
          handshakeCertificates.sslSocketFactory(),
          handshakeCertificates.trustManager,
        ).hostnameVerifier(RecordingHostnameVerifier())
        .certificatePinner(certificatePinner)
        .build()
    val serverHandshakeCertificates =
      HandshakeCertificates
        .Builder()
        .heldCertificate(certificate, intermediateCa.certificate)
        .build()
    server.useHttps(serverHandshakeCertificates.sslSocketFactory())

    // The request should complete successfully.
    server.enqueue(
      MockResponse
        .Builder()
        .body("abc")
        .onResponseEnd(ShutdownConnection)
        .build(),
    )
    val call1 =
      client.newCall(
        Request
          .Builder()
          .url(server.url("/"))
          .build(),
      )
    val response1 = call1.execute()
    assertThat(response1.body.string()).isEqualTo("abc")
    response1.close()

    // Force a fresh connection for the next request.
    client.connectionPool.evictAll()

    // Confirm that a second request also succeeds. This should detect caching problems.
    server.enqueue(
      MockResponse
        .Builder()
        .body("def")
        .onResponseEnd(ShutdownConnection)
        .build(),
    )
    val call2 =
      client.newCall(
        Request
          .Builder()
          .url(server.url("/"))
          .build(),
      )
    val response2 = call2.execute()
    assertThat(response2.body.string()).isEqualTo("def")
    response2.close()
  }

  @Test
  fun unrelatedPinnedLeafCertificateInChain() {
    // https://github.com/lysine-dev/okhttp/issues/4729
    platform.expectFailureOnConscryptPlatform()
    platform.expectFailureOnCorrettoPlatform()
    platform.expectFailureOnLoomPlatform()

    // Start with a trusted root CA certificate.
    val rootCa =
      HeldCertificate
        .Builder()
        .serialNumber(1L)
        .certificateAuthority(1)
        .commonName("root")
        .build()

    // Add a good intermediate CA, and have that issue a good certificate to localhost. Prepare an
    // SSL context for an HTTP client under attack. It includes the trusted CA and a pinned
    // certificate.
    val goodIntermediateCa =
      HeldCertificate
        .Builder()
        .signedBy(rootCa)
        .certificateAuthority(0)
        .serialNumber(2L)
        .commonName("good_intermediate_ca")
        .build()
    val goodCertificate =
      HeldCertificate
        .Builder()
        .signedBy(goodIntermediateCa)
        .serialNumber(3L)
        .commonName(server.hostName)
        .build()
    val certificatePinner =
      CertificatePinner
        .Builder()
        .add(server.hostName, pin(goodCertificate.certificate))
        .build()
    val handshakeCertificates =
      HandshakeCertificates
        .Builder()
        .addTrustedCertificate(rootCa.certificate)
        .build()
    val client =
      clientTestRule
        .newClientBuilder()
        .sslSocketFactory(
          handshakeCertificates.sslSocketFactory(),
          handshakeCertificates.trustManager,
        ).hostnameVerifier(RecordingHostnameVerifier())
        .certificatePinner(certificatePinner)
        .build()

    // Add a bad intermediate CA and have that issue a rogue certificate for localhost. Prepare
    // an SSL context for an attacking webserver. It includes both these rogue certificates plus the
    // trusted good certificate above. The attack is that by including the good certificate in the
    // chain, we may trick the certificate pinner into accepting the rouge certificate.
    val compromisedIntermediateCa =
      HeldCertificate
        .Builder()
        .signedBy(rootCa)
        .certificateAuthority(0)
        .serialNumber(4L)
        .commonName("bad_intermediate_ca")
        .build()
    val rogueCertificate =
      HeldCertificate
        .Builder()
        .serialNumber(5L)
        .signedBy(compromisedIntermediateCa)
        .commonName(server.hostName)
        .build()
    val socketFactory =
      newServerSocketFactory(
        rogueCertificate,
        compromisedIntermediateCa.certificate,
        goodCertificate.certificate,
      )
    server.useHttps(socketFactory)
    server.enqueue(
      MockResponse
        .Builder()
        .body("abc")
        .addHeader("Content-Type: text/plain")
        .build(),
    )

    // Make a request from client to server. It should succeed certificate checks (unfortunately the
    // rogue CA is trusted) but it should fail certificate pinning.
    val request =
      Request
        .Builder()
        .url(server.url("/"))
        .build()
    val call = client.newCall(request)
    assertFailsWith<SSLPeerUnverifiedException> {
      call.execute()
    }.also { expected ->
      // Certificate pinning fails!
      assertThat(expected.message!!).startsWith("Certificate pinning failure!")
    }
  }

  @Test
  fun unrelatedPinnedIntermediateCertificateInChain() {
    // https://github.com/lysine-dev/okhttp/issues/4729
    platform.expectFailureOnConscryptPlatform()
    platform.expectFailureOnCorrettoPlatform()
    platform.expectFailureOnLoomPlatform()

    // Start with two root CA certificates, one is good and the other is compromised.
    val rootCa =
      HeldCertificate
        .Builder()
        .serialNumber(1L)
        .certificateAuthority(1)
        .commonName("root")
        .build()
    val compromisedRootCa =
      HeldCertificate
        .Builder()
        .serialNumber(2L)
        .certificateAuthority(1)
        .commonName("compromised_root")
        .build()

    // Add a good intermediate CA, and have that issue a good certificate to localhost. Prepare an
    // SSL context for an HTTP client under attack. It includes the trusted CA and a pinned
    // certificate.
    val goodIntermediateCa =
      HeldCertificate
        .Builder()
        .signedBy(rootCa)
        .certificateAuthority(0)
        .serialNumber(3L)
        .commonName("intermediate_ca")
        .build()
    val certificatePinner =
      CertificatePinner
        .Builder()
        .add(server.hostName, pin(goodIntermediateCa.certificate))
        .build()
    val handshakeCertificates =
      HandshakeCertificates
        .Builder()
        .addTrustedCertificate(rootCa.certificate)
        .addTrustedCertificate(compromisedRootCa.certificate)
        .build()
    val client =
      clientTestRule
        .newClientBuilder()
        .sslSocketFactory(
          handshakeCertificates.sslSocketFactory(),
          handshakeCertificates.trustManager,
        ).hostnameVerifier(RecordingHostnameVerifier())
        .certificatePinner(certificatePinner)
        .build()

    // The attacker compromises the root CA, issues an intermediate with the same common name
    // "intermediate_ca" as the good CA. This signs a rogue certificate for localhost. The server
    // serves the good CAs certificate in the chain, which means the certificate pinner sees a
    // different set of certificates than the SSL verifier.
    val compromisedIntermediateCa =
      HeldCertificate
        .Builder()
        .signedBy(compromisedRootCa)
        .certificateAuthority(0)
        .serialNumber(4L)
        .commonName("intermediate_ca")
        .build()
    val rogueCertificate =
      HeldCertificate
        .Builder()
        .serialNumber(5L)
        .signedBy(compromisedIntermediateCa)
        .commonName(server.hostName)
        .build()
    val socketFactory =
      newServerSocketFactory(
        rogueCertificate,
        goodIntermediateCa.certificate,
        compromisedIntermediateCa.certificate,
      )
    server.useHttps(socketFactory)
    server.enqueue(
      MockResponse
        .Builder()
        .body("abc")
        .addHeader("Content-Type: text/plain")
        .build(),
    )

    // Make a request from client to server. It should succeed certificate checks (unfortunately the
    // rogue CA is trusted) but it should fail certificate pinning.
    val request =
      Request
        .Builder()
        .url(server.url("/"))
        .build()
    val call = client.newCall(request)
    assertFailsWith<IOException> {
      call.execute()
    }.also { expected ->
      when (expected) {
        is SSLHandshakeException -> {
          // On Android, the handshake fails before the certificate pinner runs.
          assertThat(expected.message!!).contains("Could not validate certificate")
        }

        is SSLPeerUnverifiedException -> {
          // On OpenJDK, the handshake succeeds but the certificate pinner fails.
          assertThat(expected.message!!).startsWith("Certificate pinning failure!")
        }

        else -> {
          throw expected
        }
      }
    }
  }

  /**
   * Not checking the CA bit created a vulnerability in old OkHttp releases. It is exploited by
   * triggering different chains to be discovered by the TLS engine and our chain cleaner. In this
   * attack there's several different chains.
   *
   *
   * The victim's gets a non-CA certificate signed by a CA, and pins the CA root and/or
   * intermediate. This is business as usual.
   *
   * ```
   *   pinnedRoot (trusted by CertificatePinner)
   *     -> pinnedIntermediate (trusted by CertificatePinner)
   *       -> realVictim
   * ```
   *
   * The attacker compromises a CA. They take the public key from an intermediate certificate
   * signed by the compromised CA's certificate and uses it in a non-CA certificate. They ask the
   * pinned CA above to sign it for non-certificate-authority uses:
   *
   * ```
   *   pinnedRoot (trusted by CertificatePinner)
   *     -> pinnedIntermediate (trusted by CertificatePinner)
   *         -> attackerSwitch
   * ```
   *
   * The attacker serves a set of certificates that yields a too-long chain in our certificate
   * pinner. The served certificates (incorrectly) formed a single chain to the pinner:
   *
   * ```
   *   attackerCa
   *     -> attackerIntermediate
   *         -> pinnedRoot (trusted by CertificatePinner)
   *             -> pinnedIntermediate (trusted by CertificatePinner)
   *                 -> attackerSwitch (not a CA certificate!)
   *                     -> phonyVictim
   * ```
   *
   * But this chain is wrong because the attackerSwitch certificate is being used in a CA role even
   * though it is not a CA certificate. There are pinned certificates in the chain! The correct
   * chain is much shorter because it skips the non-CA certificate.
   *
   * ```
   *   attackerCa
   *     -> attackerIntermediate
   *         -> phonyVictim
   * ```
   *
   * Some implementations fail the TLS handshake when they see the long chain, and don't give
   * CertificatePinner the opportunity to produce a different chain from their own. This includes
   * the OpenJDK 11 TLS implementation, which itself fails the handshake when it encounters a non-CA
   * certificate.
   */
  @Test
  fun signersMustHaveCaBitSet() {
    val attackerCa =
      HeldCertificate
        .Builder()
        .serialNumber(1L)
        .certificateAuthority(4)
        .commonName("attacker ca")
        .build()
    val attackerIntermediate =
      HeldCertificate
        .Builder()
        .serialNumber(2L)
        .certificateAuthority(3)
        .commonName("attacker")
        .signedBy(attackerCa)
        .build()
    val pinnedRoot =
      HeldCertificate
        .Builder()
        .serialNumber(3L)
        .certificateAuthority(2)
        .commonName("pinned root")
        .signedBy(attackerIntermediate)
        .build()
    val pinnedIntermediate =
      HeldCertificate
        .Builder()
        .serialNumber(4L)
        .certificateAuthority(1)
        .commonName("pinned intermediate")
        .signedBy(pinnedRoot)
        .build()
    val attackerSwitch =
      HeldCertificate
        .Builder()
        .serialNumber(5L)
        .keyPair(attackerIntermediate.keyPair) // share keys between compromised CA and leaf!
        .commonName("attacker")
        .addSubjectAlternativeName("attacker.com")
        .signedBy(pinnedIntermediate)
        .build()
    val phonyVictim =
      HeldCertificate
        .Builder()
        .serialNumber(6L)
        .signedBy(attackerSwitch)
        .addSubjectAlternativeName("victim.com")
        .commonName("victim")
        .build()
    val certificatePinner =
      CertificatePinner
        .Builder()
        .add(server.hostName, pin(pinnedRoot.certificate))
        .build()
    val handshakeCertificates =
      HandshakeCertificates
        .Builder()
        .addTrustedCertificate(pinnedRoot.certificate)
        .addTrustedCertificate(attackerCa.certificate)
        .build()
    val client =
      clientTestRule
        .newClientBuilder()
        .sslSocketFactory(
          handshakeCertificates.sslSocketFactory(),
          handshakeCertificates.trustManager,
        ).hostnameVerifier(RecordingHostnameVerifier())
        .certificatePinner(certificatePinner)
        .build()
    val serverHandshakeCertificates =
      HandshakeCertificates
        .Builder()
        .heldCertificate(
          phonyVictim,
          attackerSwitch.certificate,
          pinnedIntermediate.certificate,
          pinnedRoot.certificate,
          attackerIntermediate.certificate,
        ).build()
    server.useHttps(serverHandshakeCertificates.sslSocketFactory())
    server.enqueue(MockResponse())

    // Make a request from client to server. It should succeed certificate checks (unfortunately the
    // rogue CA is trusted) but it should fail certificate pinning.
    val request =
      Request
        .Builder()
        .url(server.url("/"))
        .build()
    val call = client.newCall(request)
    assertFailsWith<IOException> {
      call.execute()
    }.also { expected ->
      when (expected) {
        is SSLPeerUnverifiedException -> {
          // Certificate pinning fails!
          assertThat(expected.message!!).startsWith("Certificate pinning failure!")
        }

        is SSLHandshakeException -> {
          // We didn't have the opportunity to do certificate pinning because the handshake failed.
          assertThat(expected.message!!).contains("this is not a CA certificate")
        }

        else -> {
          throw expected
        }
      }
    }
  }

  /**
   * Attack the CA intermediates check by presenting unrelated chains to the handshake vs.
   * certificate pinner.
   *
   * This chain is valid but not pinned:
   *
   * ```
   *   attackerCa
   *    -> phonyVictim
   * ```
   *
   *
   * This chain is pinned but not valid:
   *
   * ```
   *   attackerCa
   *     -> pinnedRoot (trusted by CertificatePinner)
   *         -> compromisedIntermediate (max intermediates: 0)
   *             -> attackerIntermediate (max intermediates: 0)
   *                 -> phonyVictim
   * ```
   */
  @Test
  fun intermediateMustNotHaveMoreIntermediatesThanSigner() {
    val attackerCa =
      HeldCertificate
        .Builder()
        .serialNumber(1L)
        .certificateAuthority(2)
        .commonName("attacker ca")
        .build()
    val pinnedRoot =
      HeldCertificate
        .Builder()
        .serialNumber(2L)
        .certificateAuthority(1)
        .commonName("pinned root")
        .signedBy(attackerCa)
        .build()
    val compromisedIntermediate =
      HeldCertificate
        .Builder()
        .serialNumber(3L)
        .certificateAuthority(0)
        .commonName("compromised intermediate")
        .signedBy(pinnedRoot)
        .build()
    val attackerIntermediate =
      HeldCertificate
        .Builder()
        .keyPair(attackerCa.keyPair) // Share keys between compromised CA and intermediate!
        .serialNumber(4L)
        .certificateAuthority(0) // More intermediates than permitted by signer!
        .commonName("attacker intermediate")
        .signedBy(compromisedIntermediate)
        .build()
    val phonyVictim =
      HeldCertificate
        .Builder()
        .serialNumber(5L)
        .signedBy(attackerIntermediate)
        .addSubjectAlternativeName("victim.com")
        .commonName("victim")
        .build()
    val certificatePinner =
      CertificatePinner
        .Builder()
        .add(server.hostName, pin(pinnedRoot.certificate))
        .build()
    val handshakeCertificates =
      HandshakeCertificates
        .Builder()
        .addTrustedCertificate(pinnedRoot.certificate)
        .addTrustedCertificate(attackerCa.certificate)
        .build()
    val client =
      clientTestRule
        .newClientBuilder()
        .sslSocketFactory(
          handshakeCertificates.sslSocketFactory(),
          handshakeCertificates.trustManager,
        ).hostnameVerifier(RecordingHostnameVerifier())
        .certificatePinner(certificatePinner)
        .build()
    val serverHandshakeCertificates =
      HandshakeCertificates
        .Builder()
        .heldCertificate(
          phonyVictim,
          attackerIntermediate.certificate,
          compromisedIntermediate.certificate,
          pinnedRoot.certificate,
        ).build()
    server.useHttps(serverHandshakeCertificates.sslSocketFactory())
    server.enqueue(MockResponse())

    // Make a request from client to server. It should not succeed certificate checks.
    val request =
      Request
        .Builder()
        .url(server.url("/"))
        .build()
    val call = client.newCall(request)
    assertFailsWith<SSLHandshakeException> {
      call.execute()
    }
  }

  @Test
  fun lonePinnedCertificate() {
    val onlyCertificate =
      HeldCertificate
        .Builder()
        .serialNumber(1L)
        .commonName("root")
        .build()
    val certificatePinner =
      CertificatePinner
        .Builder()
        .add(server.hostName, pin(onlyCertificate.certificate))
        .build()
    val handshakeCertificates =
      HandshakeCertificates
        .Builder()
        .addTrustedCertificate(onlyCertificate.certificate)
        .build()
    val client =
      clientTestRule
        .newClientBuilder()
        .sslSocketFactory(
          handshakeCertificates.sslSocketFactory(),
          handshakeCertificates.trustManager,
        ).hostnameVerifier(RecordingHostnameVerifier())
        .certificatePinner(certificatePinner)
        .build()
    val serverHandshakeCertificates =
      HandshakeCertificates
        .Builder()
        .heldCertificate(onlyCertificate)
        .build()
    server.useHttps(serverHandshakeCertificates.sslSocketFactory())

    // The request should complete successfully.
    server.enqueue(
      MockResponse
        .Builder()
        .body("abc")
        .build(),
    )
    val call1 =
      client.newCall(
        Request
          .Builder()
          .url(server.url("/"))
          .build(),
      )
    val response1 = call1.execute()
    assertThat(response1.body.string()).isEqualTo("abc")
  }

  private fun newServerSocketFactory(
    heldCertificate: HeldCertificate,
    vararg intermediates: X509Certificate,
  ): SSLSocketFactory {
    // Test setup fails on JDK9
    // java.security.KeyStoreException: Certificate chain is not valid
    // at sun.security.pkcs12.PKCS12KeyStore.setKeyEntry
    // http://openjdk.java.net/jeps/229
    // http://hg.openjdk.java.net/jdk9/jdk9/jdk/file/2c1c21d11e58/src/share/classes/sun/security/pkcs12/PKCS12KeyStore.java#l596
    val keystoreType = if (platform.isJdk9()) "JKS" else null
    val x509KeyManager = newKeyManager(keystoreType, heldCertificate, *intermediates)
    val trustManager =
      newTrustManager(
        keystoreType,
        emptyList(),
        emptyList(),
      )
    val sslContext = get().newSSLContext()
    sslContext.init(
      arrayOf<KeyManager>(x509KeyManager),
      arrayOf<TrustManager>(trustManager),
      SecureRandom(),
    )
    return sslContext.socketFactory
  }
}
