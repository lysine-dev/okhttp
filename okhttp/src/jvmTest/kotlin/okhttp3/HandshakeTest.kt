/*
 * Copyright (C) 2019 Square, Inc.
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
import assertk.assertions.hasMessage
import assertk.assertions.isEmpty
import assertk.assertions.isEqualTo
import assertk.assertions.isNull
import java.io.IOException
import java.security.cert.Certificate
import kotlin.test.assertFailsWith
import okhttp3.Handshake.Companion.handshake
import okhttp3.sockets.DelegatingSSLSession
import okhttp3.tls.HeldCertificate
import org.junit.jupiter.api.Test

class HandshakeTest {
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

  @Test
  fun createFromParts() {
    val handshake =
      Handshake.get(
        tlsVersion = TlsVersion.TLS_1_3,
        cipherSuite = CipherSuite.TLS_AES_128_GCM_SHA256,
        peerCertificates = listOf(serverCertificate.certificate, serverIntermediate.certificate),
        localCertificates = listOf(),
      )

    assertThat(handshake.tlsVersion).isEqualTo(TlsVersion.TLS_1_3)
    assertThat(handshake.cipherSuite).isEqualTo(CipherSuite.TLS_AES_128_GCM_SHA256)
    assertThat(handshake.peerCertificates).containsExactly(
      serverCertificate.certificate,
      serverIntermediate.certificate,
    )
    assertThat(handshake.localPrincipal).isNull()
    assertThat(handshake.peerPrincipal)
      .isEqualTo(serverCertificate.certificate.subjectX500Principal)
    assertThat(handshake.localCertificates).isEmpty()
  }

  @Test
  fun createFromSslSession() {
    val sslSession =
      FakeSSLSession(
        "TLSv1.3",
        "TLS_AES_128_GCM_SHA256",
        arrayOf(serverCertificate.certificate, serverIntermediate.certificate),
        null,
      )

    val handshake = sslSession.handshake()

    assertThat(handshake.tlsVersion).isEqualTo(TlsVersion.TLS_1_3)
    assertThat(handshake.cipherSuite).isEqualTo(CipherSuite.TLS_AES_128_GCM_SHA256)
    assertThat(handshake.peerCertificates).containsExactly(
      serverCertificate.certificate,
      serverIntermediate.certificate,
    )
    assertThat(handshake.localPrincipal).isNull()
    assertThat(handshake.peerPrincipal)
      .isEqualTo(serverCertificate.certificate.subjectX500Principal)
    assertThat(handshake.localCertificates).isEmpty()
  }

  @Test
  fun sslWithNullNullNull() {
    val sslSession =
      FakeSSLSession(
        "TLSv1.3",
        "SSL_NULL_WITH_NULL_NULL",
        arrayOf(serverCertificate.certificate, serverIntermediate.certificate),
        null,
      )

    assertFailsWith<IOException> {
      sslSession.handshake()
    }.also { expected ->
      assertThat(expected).hasMessage("cipherSuite == SSL_NULL_WITH_NULL_NULL")
    }
  }

  @Test
  fun tlsWithNullNullNull() {
    val sslSession =
      FakeSSLSession(
        "TLSv1.3",
        "TLS_NULL_WITH_NULL_NULL",
        arrayOf(serverCertificate.certificate, serverIntermediate.certificate),
        null,
      )

    assertFailsWith<IOException> {
      sslSession.handshake()
    }.also { expected ->
      assertThat(expected).hasMessage("cipherSuite == TLS_NULL_WITH_NULL_NULL")
    }
  }

  class FakeSSLSession(
    private val protocol: String,
    private val cipherSuite: String,
    private val peerCertificates: Array<Certificate>?,
    private val localCertificates: Array<Certificate>?,
  ) : DelegatingSSLSession(null) {
    override fun getProtocol() = protocol

    override fun getCipherSuite() = cipherSuite

    override fun getPeerCertificates() = peerCertificates

    override fun getLocalCertificates() = localCertificates
  }
}
