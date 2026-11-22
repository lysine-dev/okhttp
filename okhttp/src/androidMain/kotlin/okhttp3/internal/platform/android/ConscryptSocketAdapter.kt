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
import org.conscrypt.Conscrypt

/**
 * Simple non-reflection SocketAdapter for Conscrypt when included as an application dependency
 * directly.
 */
class ConscryptSocketAdapter : SocketAdapter {
  override fun matchesSocket(sslSocket: SSLSocket): Boolean = Conscrypt.isConscrypt(sslSocket)

  override fun isSupported(): Boolean = isSupported

  override fun getSelectedProtocol(sslSocket: SSLSocket): String? =
    when {
      matchesSocket(sslSocket) -> Conscrypt.getApplicationProtocol(sslSocket)
      else -> null // No TLS extensions if the socket class is custom.
    }

  override fun configureTlsExtensions(
    sslSocket: SSLSocket,
    hostname: String?,
    protocols: List<Protocol>,
    echConfigList: ByteString?,
  ) {
    // No TLS extensions if the socket class is custom.
    if (matchesSocket(sslSocket)) {
      // Enable session tickets.
      Conscrypt.setUseSessionTickets(sslSocket, true)

      // Enable ALPN.
      val names = Platform.alpnProtocolNames(protocols)
      Conscrypt.setApplicationProtocols(sslSocket, names.toTypedArray())
    }
  }

  companion object {
    val factory =
      object : DeferredSocketAdapter.Factory {
        override fun matchesSocket(sslSocket: SSLSocket): Boolean = isSupported && Conscrypt.isConscrypt(sslSocket)

        override fun create(sslSocket: SSLSocket): SocketAdapter = ConscryptSocketAdapter()
      }

    val isSupported: Boolean =
      try {
        // Trigger an early exception over a fatal error, prefer a RuntimeException over Error.
        Class.forName("org.conscrypt.Conscrypt\$Version", false, javaClass.classLoader)

        when {
          // Bump this version if we ever have a binary incompatibility
          Conscrypt.isAvailable() && atLeastVersion(2, 1, 0) -> true

          else -> false
        }
      } catch (e: NoClassDefFoundError) {
        false
      } catch (e: ClassNotFoundException) {
        false
      }

    fun atLeastVersion(
      major: Int,
      minor: Int = 0,
      patch: Int = 0,
    ): Boolean {
      val conscryptVersion = Conscrypt.version() ?: return false

      if (conscryptVersion.major() != major) {
        return conscryptVersion.major() > major
      }

      if (conscryptVersion.minor() != minor) {
        return conscryptVersion.minor() > minor
      }

      return conscryptVersion.patch() >= patch
    }
  }
}
