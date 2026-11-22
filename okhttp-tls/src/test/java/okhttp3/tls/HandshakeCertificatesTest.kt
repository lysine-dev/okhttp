/*
 * Copyright (C) 2018 Square, Inc.
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
package okhttp3.tls

import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.matchesPredicate
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.security.PrivateKey
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.Future
import javax.net.ssl.SSLSocket
import okhttp3.Handshake
import okhttp3.Handshake.Companion.handshake
import okhttp3.TestUtil.threadFactory
import okhttp3.internal.closeQuietly
import okhttp3.internal.platform.Platform
import okhttp3.testing.PlatformRule
import okio.ByteString.Companion.toByteString
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.RegisterExtension

class HandshakeCertificatesTest {
  @RegisterExtension
  var platform = PlatformRule()

  private lateinit var executorService: ExecutorService

  private var serverSocket: ServerSocket? = null

  @BeforeEach fun setUp() {
    executorService = Executors.newCachedThreadPool(threadFactory("HandshakeCertificatesTest"))
  }

  @AfterEach fun tearDown() {
    executorService.shutdown()
    serverSocket?.closeQuietly()
  }

  @Test fun clientAndServer() {
    platform.assumeNotConscrypt()
    platform.assumeNotBouncyCastle()

    val clientRoot =
      HeldCertificate
        .Builder()
        .certificateAuthority(1)
        .build()
    val clientIntermediate =
      HeldCertificate
        .Builder()
        .certificateAuthority(0)
        .signedBy(clientRoot)
        .build()
    val clientCertificate =
      HeldCertificate
        .Builder()
        .signedBy(clientIntermediate)
        .build()
    val serverRoot =
      HeldCertificate
        .Builder()
        .certificateAuthority(1)
        .build()
    val serverIntermediate =
      HeldCertificate
        .Builder()
        .certificateAuthority(0)
        .signedBy(serverRoot)
        .build()
    val serverCertificate =
      HeldCertificate
        .Builder()
        .signedBy(serverIntermediate)
        .build()
    val server =
      HandshakeCertificates
        .Builder()
        .addTrustedCertificate(clientRoot.certificate)
        .heldCertificate(serverCertificate, serverIntermediate.certificate)
        .build()
    val client =
      HandshakeCertificates
        .Builder()
        .addTrustedCertificate(serverRoot.certificate)
        .heldCertificate(clientCertificate, clientIntermediate.certificate)
        .build()
    val serverAddress = startTlsServer()
    val serverHandshakeFuture = doServerHandshake(server)
    val clientHandshakeFuture = doClientHandshake(client, serverAddress)
    val serverHandshake = serverHandshakeFuture.get()
    assertThat(listOf(clientCertificate.certificate, clientIntermediate.certificate))
      .isEqualTo(serverHandshake.peerCertificates)
    assertThat(listOf(serverCertificate.certificate, serverIntermediate.certificate))
      .isEqualTo(serverHandshake.localCertificates)
    val clientHandshake = clientHandshakeFuture.get()
    assertThat(listOf(serverCertificate.certificate, serverIntermediate.certificate))
      .isEqualTo(clientHandshake.peerCertificates)
    assertThat(listOf(clientCertificate.certificate, clientIntermediate.certificate))
      .isEqualTo(clientHandshake.localCertificates)
  }

  @Test fun keyManager() {
    val root =
      HeldCertificate
        .Builder()
        .certificateAuthority(1)
        .build()
    val intermediate =
      HeldCertificate
        .Builder()
        .certificateAuthority(0)
        .signedBy(root)
        .build()
    val certificate =
      HeldCertificate
        .Builder()
        .signedBy(intermediate)
        .build()
    val handshakeCertificates =
      HandshakeCertificates
        .Builder()
        .addTrustedCertificate(root.certificate) // BouncyCastle requires at least one
        .heldCertificate(certificate, intermediate.certificate)
        .build()
    assertPrivateKeysEquals(
      certificate.keyPair.private,
      handshakeCertificates.keyManager.getPrivateKey("private"),
    )
    assertThat(handshakeCertificates.keyManager.getCertificateChain("private").toList())
      .isEqualTo(listOf(certificate.certificate, intermediate.certificate))
  }

  @Test fun platformTrustedCertificates() {
    val handshakeCertificates =
      HandshakeCertificates
        .Builder()
        .addPlatformTrustedCertificates()
        .build()
    val acceptedIssuers = handshakeCertificates.trustManager.acceptedIssuers
    val names =
      acceptedIssuers
        .map { it.subjectDN.name }
        .toSet()

    // It's safe to assume all platforms will have a major Internet certificate issuer.
    val majorIssuers =
      listOf(
        "DigiCert",
        "Let's Encrypt",
        "ISRG", // Internet Security Research Group (Let's Encrypt parent)
        "GlobalSign",
        "Comodo",
        "Sectigo",
        "GeoTrust",
        "Entrust",
      )
    assertThat(names).matchesPredicate { strings ->
      strings.any { name -> majorIssuers.any { issuer -> name.contains(issuer, ignoreCase = true) } }
    }
  }

  private fun startTlsServer(): InetSocketAddress {
    val serverSocketFactory = Platform.get().serverSocketFactory
    serverSocket = serverSocketFactory.createServerSocket()
    val serverAddress = InetAddress.getByName("localhost")
    serverSocket!!.bind(InetSocketAddress(serverAddress, 0), 50)
    return InetSocketAddress(serverAddress, serverSocket!!.localPort)
  }

  private fun doServerHandshake(server: HandshakeCertificates): Future<Handshake> {
    return executorService.submit<Handshake> {
      serverSocket!!.accept().use { rawSocket ->
        val sslSocket =
          server.sslSocketFactory().createSocket(
            rawSocket,
            rawSocket.inetAddress.hostAddress,
            rawSocket.port,
            true,
          ) as SSLSocket
        sslSocket.use {
          sslSocket.useClientMode = false
          sslSocket.wantClientAuth = true
          sslSocket.startHandshake()
          return@submit sslSocket.session.handshake()
        }
      }
    }
  }

  private fun doClientHandshake(
    client: HandshakeCertificates,
    serverAddress: InetSocketAddress,
  ): Future<Handshake> {
    return executorService.submit<Handshake> {
      Platform.get().socketFactory.createSocket().use { rawSocket ->
        rawSocket.connect(serverAddress)
        val sslSocket =
          client.sslSocketFactory().createSocket(
            rawSocket,
            rawSocket.inetAddress.hostAddress,
            rawSocket.port,
            true,
          ) as SSLSocket
        sslSocket.use {
          sslSocket.startHandshake()
          return@submit sslSocket.session.handshake()
        }
      }
    }
  }

  private fun assertPrivateKeysEquals(
    expected: PrivateKey,
    actual: PrivateKey,
  ) {
    assertThat(actual.encoded.toByteString()).isEqualTo(expected.encoded.toByteString())
  }
}
