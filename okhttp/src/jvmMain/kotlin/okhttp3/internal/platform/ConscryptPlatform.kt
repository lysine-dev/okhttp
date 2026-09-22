/*
 * Copyright (C) 2014 Square, Inc.
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
import java.security.cert.X509Certificate
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSession
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory
import javax.net.ssl.TrustManager
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager
import okhttp3.Protocol
import okio.ByteString
import org.conscrypt.Conscrypt
import org.conscrypt.ConscryptHostnameVerifier

/**
 * Platform using Conscrypt (conscrypt.org) if installed as the first Security Provider.
 *
 * Requires org.conscrypt:conscrypt-openjdk-uber >= 2.1.0 on the classpath.
 */
class ConscryptPlatform private constructor() : Platform() {
  private val provider: Provider = Conscrypt.newProvider()

  // See release notes https://groups.google.com/forum/#!forum/conscrypt
  // for version differences
  override fun newSSLContext(): SSLContext =
    // supports TLSv1.3 by default (version api is >= 1.4.0)
    SSLContext.getInstance("TLS", provider)

  override fun platformTrustManager(): X509TrustManager {
    val trustManagers =
      TrustManagerFactory
        .getInstance(TrustManagerFactory.getDefaultAlgorithm())
        .apply {
          init(null as KeyStore?)
        }.trustManagers!!
    check(trustManagers.size == 1 && trustManagers[0] is X509TrustManager) {
      "Unexpected default trust managers: ${trustManagers.contentToString()}"
    }
    val x509TrustManager = trustManagers[0] as X509TrustManager
    // Disabled because OkHttp will run anyway
    Conscrypt.setHostnameVerifier(x509TrustManager, DisabledHostnameVerifier)
    return x509TrustManager
  }

  internal object DisabledHostnameVerifier : ConscryptHostnameVerifier {
    fun verify(
      hostname: String?,
      session: SSLSession?,
    ): Boolean = true

    override fun verify(
      certs: Array<out X509Certificate>?,
      hostname: String?,
      session: SSLSession?,
    ): Boolean = true
  }

  override fun trustManager(sslSocketFactory: SSLSocketFactory): X509TrustManager? = null

  override fun configureTlsExtensions(
    sslSocket: SSLSocket,
    hostname: String?,
    protocols: List<@JvmSuppressWildcards Protocol>,
    echConfigList: ByteString?,
  ) {
    if (Conscrypt.isConscrypt(sslSocket)) {
      // Enable session tickets.
      Conscrypt.setUseSessionTickets(sslSocket, true)

      // Enable ALPN.
      val names = alpnProtocolNames(protocols)
      Conscrypt.setApplicationProtocols(sslSocket, names.toTypedArray())
    } else {
      super.configureTlsExtensions(sslSocket, hostname, protocols, echConfigList)
    }
  }

  override fun getSelectedProtocol(sslSocket: SSLSocket): String? =
    if (Conscrypt.isConscrypt(sslSocket)) {
      Conscrypt.getApplicationProtocol(sslSocket)
    } else {
      super.getSelectedProtocol(sslSocket)
    }

  override fun newSslSocketFactory(trustManager: X509TrustManager): SSLSocketFactory =
    newSSLContext()
      .apply {
        init(null, arrayOf<TrustManager>(trustManager), null)
      }.socketFactory

  companion object {
    val isSupported: Boolean =
      try {
        // Trigger an early exception over a fatal error, prefer a RuntimeException over Error.
        Class.forName("org.conscrypt.Conscrypt\$Version", false, ConscryptPlatform::class.java.classLoader)

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

    fun buildIfSupported(): ConscryptPlatform? = if (isSupported) ConscryptPlatform() else null

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
