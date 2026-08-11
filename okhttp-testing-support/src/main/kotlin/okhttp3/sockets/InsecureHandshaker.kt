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

import java.security.cert.X509Certificate
import javax.net.ssl.SSLHandshakeException
import javax.net.ssl.X509KeyManager
import okhttp3.Handshake
import okhttp3.internal.connection.asBufferedSocket
import okio.inMemorySocketPair

/**
 * A basic handshaker that makes policy decisions without doing any useful cryptography.
 */
class InsecureHandshaker : Handshaker {
  override fun handshake(
    client: Handshaker.ClientInputs,
    server: Handshaker.ServerInputs,
  ): Handshaker.Result {
    val tlsVersion =
      server.tlsVersions.firstOrNull { it in client.tlsVersions }
        ?: throw SSLHandshakeException("no matching TLS version")
    val cipherSuite =
      server.cipherSuites.firstOrNull { it in client.cipherSuites }
        ?: throw SSLHandshakeException("no matching cipher suite")
    val protocol =
      server.protocols
        .orEmpty()
        .firstOrNull { it in client.protocols.orEmpty() }

    // For more accuracy, we should pick the key type based on the cipher suite.
    val keyType = "EC"

    val clientCertificates =
      when (server.clientAuth) {
        Handshaker.ClientAuth.Required -> {
          client.keyManager.clientCertificatesOrNull(keyType)
            ?: throw SSLHandshakeException("required client certificates not sent")
        }

        Handshaker.ClientAuth.Requested -> {
          client.keyManager.clientCertificatesOrNull(keyType)
            ?: listOf()
        }

        Handshaker.ClientAuth.None -> {
          listOf()
        }
      }

    val serverCertificates = server.keyManager.serverCertificates(keyType)

    val (clientSocket, serverSocket) = inMemorySocketPair(maxBufferSize = 1024 * 1024)

    return Handshaker.Result(
      clientSocket = clientSocket.asBufferedSocket(),
      serverSocket = serverSocket.asBufferedSocket(),
      clientHandshake =
        Handshake.get(
          tlsVersion = tlsVersion,
          cipherSuite = cipherSuite,
          peerCertificates = serverCertificates,
          localCertificates = clientCertificates,
        ),
      serverHandshake =
        Handshake.get(
          tlsVersion = tlsVersion,
          cipherSuite = cipherSuite,
          peerCertificates = clientCertificates,
          localCertificates = serverCertificates,
        ),
      selectedProtocol = protocol,
    )
  }

  private fun X509KeyManager.serverCertificates(keyType: String): List<X509Certificate> {
    val alias =
      getServerAliases(keyType, null)
        .firstOrNull()
        ?: throw SSLHandshakeException("no server aliases for $keyType")

    return getCertificateChain(alias).toList()
  }

  private fun X509KeyManager.clientCertificatesOrNull(keyType: String): List<X509Certificate>? {
    val alias =
      getClientAliases(keyType, null)
        .firstOrNull()
        ?: return null

    return getCertificateChain(alias).toList()
  }
}
