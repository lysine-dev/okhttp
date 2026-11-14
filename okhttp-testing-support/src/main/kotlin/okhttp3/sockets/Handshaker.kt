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

import javax.net.ssl.SSLException
import javax.net.ssl.X509KeyManager
import okhttp3.CipherSuite
import okhttp3.Handshake
import okhttp3.Protocol
import okhttp3.TlsVersion
import okio.ByteString
import okio.Socket

/**
 * This implements the policy of a TLS handshake, deciding which [TlsVersion], [CipherSuite], and
 * [Protocol] to negotiate.
 *
 * In a real TLS handshake the client and server are mutually-distrusting parties, and they each
 * implement their own handshaking logic. For this fake, we use a single handshaker that makes
 * decisions on behalf of both parties.
 */
interface Handshaker {
  /**
   * Returns a two-element array containing the client result and the server result.
   */
  fun handshake(
    client: ClientInputs,
    server: ServerInputs,
  ): Result

  sealed interface Inputs {
    val tlsVersions: List<TlsVersion>
    val cipherSuites: List<CipherSuite>
    val protocols: List<Protocol>?
    val keyManager: X509KeyManager
  }

  data class ClientInputs(
    override val tlsVersions: List<TlsVersion>,
    override val cipherSuites: List<CipherSuite>,
    override val protocols: List<Protocol>?,
    override val keyManager: X509KeyManager,
    val hostname: String?,
    val echConfigList: ByteString?,
  ) : Inputs

  data class ServerInputs(
    override val tlsVersions: List<TlsVersion>,
    override val cipherSuites: List<CipherSuite>,
    override val protocols: List<Protocol>?,
    override val keyManager: X509KeyManager,
    val clientAuth: ClientAuth,
  ) : Inputs

  enum class ClientAuth {
    None,
    Requested,
    Required,
  }

  sealed interface Result {
    val clientHandshake: Handshake?
    val serverHandshake: Handshake?
    val selectedProtocol: Protocol?

    class Success(
      val clientSocket: Socket,
      val serverSocket: Socket,
      override val clientHandshake: Handshake,
      override val serverHandshake: Handshake,
      override val selectedProtocol: Protocol?,
    ) : Result

    class Failure(
      val exception: SSLException,
      override val clientHandshake: Handshake? = null,
      override val serverHandshake: Handshake? = null,
      override val selectedProtocol: Protocol? = null,
    ) : Result
  }
}
