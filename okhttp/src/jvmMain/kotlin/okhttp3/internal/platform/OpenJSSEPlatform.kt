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
package okhttp3.internal.platform

import java.security.KeyStore
import java.security.Provider
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager
import okhttp3.Protocol
import okio.ByteString

/**
 * Platform using OpenJSSE (https://github.com/openjsse/openjsse) if installed as the first
 * Security Provider.
 *
 * Requires org.openjsse:openjsse >= 1.1.0 on the classpath.
 */
class OpenJSSEPlatform private constructor() : Platform() {
  private val provider: Provider =
    org.openjsse.net.ssl
      .OpenJSSE()

  // Selects TLSv1.3 so we are specific about our intended version ranges (not just 1.3)
  // and because it's a common pattern for VMs to have differences between supported and
  // defaulted versions for TLS based on what is requested.
  override fun newSSLContext(): SSLContext = SSLContext.getInstance("TLSv1.3", provider)

  override fun platformTrustManager(): X509TrustManager {
    val factory =
      TrustManagerFactory.getInstance(
        TrustManagerFactory.getDefaultAlgorithm(),
        provider,
      )
    factory.init(null as KeyStore?)
    val trustManagers = factory.trustManagers!!
    check(trustManagers.size == 1 && trustManagers[0] is X509TrustManager) {
      "Unexpected default trust managers: ${trustManagers.contentToString()}"
    }
    return trustManagers[0] as X509TrustManager
  }

  override fun trustManager(sslSocketFactory: SSLSocketFactory): X509TrustManager =
    throw UnsupportedOperationException(
      "clientBuilder.sslSocketFactory(SSLSocketFactory) not supported with OpenJSSE",
    )

  override fun configureTlsExtensions(
    sslSocket: SSLSocket,
    hostname: String?,
    protocols: List<@JvmSuppressWildcards Protocol>,
    echConfigList: ByteString?,
  ) {
    if (sslSocket is org.openjsse.javax.net.ssl.SSLSocket) {
      val sslParameters = sslSocket.sslParameters

      if (sslParameters is org.openjsse.javax.net.ssl.SSLParameters) {
        // Enable ALPN.
        val names = alpnProtocolNames(protocols)
        sslParameters.applicationProtocols = names.toTypedArray()

        sslSocket.sslParameters = sslParameters
      }
    } else {
      super.configureTlsExtensions(sslSocket, hostname, protocols, echConfigList)
    }
  }

  override fun getSelectedProtocol(sslSocket: SSLSocket): String? =
    if (sslSocket is org.openjsse.javax.net.ssl.SSLSocket) {
      when (val protocol = sslSocket.applicationProtocol) {
        // Handles both un-configured and none selected.
        null, "" -> null

        else -> protocol
      }
    } else {
      super.getSelectedProtocol(sslSocket)
    }

  companion object {
    val isSupported: Boolean =
      try {
        // Trigger an early exception over a fatal error, prefer a RuntimeException over Error.
        Class.forName("org.openjsse.net.ssl.OpenJSSE", false, OpenJSSEPlatform::class.java.classLoader)

        true
      } catch (_: ClassNotFoundException) {
        false
      }

    fun buildIfSupported(): OpenJSSEPlatform? = if (isSupported) OpenJSSEPlatform() else null
  }
}
