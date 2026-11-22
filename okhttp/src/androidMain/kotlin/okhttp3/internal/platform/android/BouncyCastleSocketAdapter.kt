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
package okhttp3.internal.platform.android

import javax.net.ssl.SSLSocket
import okhttp3.Protocol
import okhttp3.internal.platform.Platform
import okio.ByteString
import org.bouncycastle.jsse.BCSSLSocket

/**
 * Simple non-reflection SocketAdapter for BouncyCastle.
 */
class BouncyCastleSocketAdapter : SocketAdapter {
  override fun matchesSocket(sslSocket: SSLSocket): Boolean = sslSocket is BCSSLSocket

  override fun isSupported(): Boolean = isSupported

  override fun getSelectedProtocol(sslSocket: SSLSocket): String? {
    val s = sslSocket as BCSSLSocket

    return when (val protocol = s.applicationProtocol) {
      null, "" -> null
      else -> protocol
    }
  }

  override fun configureTlsExtensions(
    sslSocket: SSLSocket,
    hostname: String?,
    protocols: List<Protocol>,
    echConfigList: ByteString?,
  ) {
    // No TLS extensions if the socket class is custom.
    if (matchesSocket(sslSocket)) {
      val bcSocket = sslSocket as BCSSLSocket

      val sslParameters = bcSocket.parameters

      // Enable ALPN.
      sslParameters.applicationProtocols = Platform.alpnProtocolNames(protocols).toTypedArray()

      bcSocket.parameters = sslParameters
    }
  }

  companion object {
    val factory =
      object : DeferredSocketAdapter.Factory {
        override fun matchesSocket(sslSocket: SSLSocket): Boolean = isSupported && sslSocket is BCSSLSocket

        override fun create(sslSocket: SSLSocket): SocketAdapter = BouncyCastleSocketAdapter()
      }

    val isSupported: Boolean =
      try {
        // Trigger an early exception over a fatal error, prefer a RuntimeException over Error.
        Class.forName("org.bouncycastle.jsse.provider.BouncyCastleJsseProvider", false, javaClass.classLoader)

        true
      } catch (_: ClassNotFoundException) {
        false
      }
  }
}
