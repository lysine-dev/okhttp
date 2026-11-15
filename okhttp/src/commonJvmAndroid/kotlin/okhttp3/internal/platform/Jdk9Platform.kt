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
package okhttp3.internal.platform

import java.security.NoSuchAlgorithmException
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory
import javax.net.ssl.X509TrustManager
import okhttp3.Protocol
import okhttp3.internal.SuppressSignatureCheck
import okio.ByteString

/**
 * OpenJDK 9+ and JDK8 build 252+.
 *
 * This may also be used for Android tests with Robolectric.
 */
@Suppress("NewApi")
open class Jdk9Platform : Platform() {
  @SuppressSignatureCheck
  override fun configureTlsExtensions(
    sslSocket: SSLSocket,
    hostname: String?,
    protocols: List<@JvmSuppressWildcards Protocol>,
    echConfigList: ByteString?,
  ) {
    val sslParameters = sslSocket.sslParameters

    val names = alpnProtocolNames(protocols)

    sslParameters.applicationProtocols = names.toTypedArray()

    sslSocket.sslParameters = sslParameters
  }

  @SuppressSignatureCheck
  override fun getSelectedProtocol(sslSocket: SSLSocket): String? =
    try {
      // SSLSocket.getApplicationProtocol returns "" if application protocols values will not
      // be used. Observed if you didn't specify SSLParameters.setApplicationProtocols
      when (val protocol = sslSocket.applicationProtocol) {
        null, "" -> null
        else -> protocol
      }
    } catch (e: UnsupportedOperationException) {
      // https://docs.oracle.com/javase/9/docs/api/javax/net/ssl/SSLSocket.html#getApplicationProtocol--
      null
    }

  override fun trustManager(sslSocketFactory: SSLSocketFactory): X509TrustManager? {
    // Not supported due to access checks on JDK 9+:
    // java.lang.reflect.InaccessibleObjectException: Unable to make member of class
    // sun.security.ssl.SSLSocketFactoryImpl accessible:  module java.base does not export
    // sun.security.ssl to unnamed module @xxx
    throw UnsupportedOperationException(
      "clientBuilder.sslSocketFactory(SSLSocketFactory) not supported on JDK 8 (>= 252) or JDK 9+",
    )
  }

  override fun newSSLContext(): SSLContext =
    when {
      majorVersion != null && majorVersion >= 9 -> {
        SSLContext.getInstance("TLS")
      }

      else -> {
        try {
          // Based on SSLSocket.getApplicationProtocol check we should
          // have TLSv1.3 if we request it.
          // See https://www.oracle.com/java/technologies/javase/8u261-relnotes.html
          SSLContext.getInstance("TLSv1.3")
        } catch (nsae: NoSuchAlgorithmException) {
          SSLContext.getInstance("TLS")
        }
      }
    }

  companion object {
    val isAvailable: Boolean

    val majorVersion = System.getProperty("java.specification.version")?.toIntOrNull()

    init {
      isAvailable =
        if (majorVersion != null) {
          majorVersion >= 9
        } else {
          try {
            // also present on JDK8 after build 252.
            SSLSocket::class.java.getMethod("getApplicationProtocol")
            true
          } catch (nsme: NoSuchMethodException) {
            false
          }
        }
    }

    fun buildIfSupported(): Jdk9Platform? = if (isAvailable) Jdk9Platform() else null
  }
}
