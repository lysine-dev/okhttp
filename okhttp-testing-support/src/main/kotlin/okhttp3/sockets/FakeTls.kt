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
package okhttp3.sockets

import java.io.InputStream
import java.net.InetAddress
import java.net.Socket
import javax.net.ssl.SSLSocketFactory
import javax.net.ssl.X509TrustManager
import okhttp3.CipherSuite
import okhttp3.TlsVersion
import okhttp3.tls.HandshakeCertificates

/**
 * A fake TLS stack to accompany our fake network.
 *
 * Unlike [FakeNetwork] which is a natural singleton, we expect each peer to have their own
 * independent instances of [FakeTls]. This allows us to simulate different configurations for
 * each peer.
 *
 * Note that only the client's [handshaker] is used, and its result is used by both client and
 * server.
 */
class FakeTls(
  val handshaker: Handshaker,
  val handshakeCertificates: HandshakeCertificates,
  val supportedTlsVersions: List<TlsVersion> =
    listOf(
      TlsVersion.TLS_1_3,
      TlsVersion.TLS_1_2,
    ),
  val enabledTlsVersions: List<TlsVersion> = supportedTlsVersions,
  val supportedCipherSuites: List<CipherSuite> =
    listOf(
      CipherSuite.TLS_AES_128_GCM_SHA256, // TLSv1.3.
      CipherSuite.TLS_ECDHE_ECDSA_WITH_AES_128_GCM_SHA256, // TLSv1.2
    ),
  val defaultCipherSuites: List<CipherSuite> = supportedCipherSuites,
) {
  val trustManager: X509TrustManager
    get() = handshakeCertificates.trustManager

  val sslSocketFactory =
    object : SSLSocketFactory() {
      override fun getDefaultCipherSuites() = this@FakeTls.defaultCipherSuites.map { it.javaName }.toTypedArray()

      override fun getSupportedCipherSuites() = this@FakeTls.supportedCipherSuites.map { it.javaName }.toTypedArray()

      override fun createSocket(
        socket: Socket,
        consumed: InputStream,
        autoClose: Boolean,
      ) = error("unsupported")

      override fun createSocket() = error("unsupported")

      override fun createSocket(
        socket: Socket,
        host: String?,
        port: Int,
        autoClose: Boolean,
      ): FakeSslSocket {
        require(autoClose)
        return FakeSslSocket(
          tls = this@FakeTls,
          socket = socket as FakeSocket,
          hostname = host,
          tlsVersions = this@FakeTls.enabledTlsVersions,
          cipherSuites = this@FakeTls.defaultCipherSuites,
        )
      }

      override fun createSocket(
        host: String?,
        port: Int,
      ) = error("unsupported")

      override fun createSocket(
        host: String?,
        port: Int,
        localHost: InetAddress,
        localPort: Int,
      ) = error("unsupported")

      override fun createSocket(
        host: InetAddress?,
        port: Int,
      ) = error("unsupported")

      override fun createSocket(
        address: InetAddress,
        port: Int,
        localAddress: InetAddress?,
        localPort: Int,
      ) = error("unsupported")
    }
}
