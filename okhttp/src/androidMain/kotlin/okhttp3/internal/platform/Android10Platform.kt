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

import android.annotation.SuppressLint
import android.content.Context
import android.os.Build
import android.os.StrictMode
import android.security.NetworkSecurityPolicy
import android.util.CloseGuard
import android.util.Log
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory
import javax.net.ssl.X509TrustManager
import okhttp3.Protocol
import okhttp3.internal.SuppressSignatureCheck
import okhttp3.internal.platform.AndroidPlatform.Companion.Tag
import okhttp3.internal.platform.android.Android10SocketAdapter
import okhttp3.internal.platform.android.Android17SocketAdapter
import okhttp3.internal.platform.android.AndroidCertificateChainCleaner
import okhttp3.internal.platform.android.AndroidSocketAdapter
import okhttp3.internal.platform.android.BouncyCastleSocketAdapter
import okhttp3.internal.platform.android.ConscryptSocketAdapter
import okhttp3.internal.platform.android.DeferredSocketAdapter
import okhttp3.internal.tls.CertificateChainCleaner
import okhttp3.internal.tls.TrustRootIndex
import okio.ByteString

/** Android 10+ (API 29+). */
@SuppressSignatureCheck
class Android10Platform :
  Platform(),
  ContextAwarePlatform {
  override var applicationContext: Context? = null

  private val socketAdapters =
    listOfNotNull(
      Android17SocketAdapter.buildIfSupported(),
      Android10SocketAdapter.buildIfSupported(),
      DeferredSocketAdapter(AndroidSocketAdapter.playProviderFactory),
      // Delay and Defer any initialisation of Conscrypt and BouncyCastle
      DeferredSocketAdapter(ConscryptSocketAdapter.factory),
      DeferredSocketAdapter(BouncyCastleSocketAdapter.factory),
    ).filter { it.isSupported() }

  override fun trustManager(sslSocketFactory: SSLSocketFactory): X509TrustManager? =
    socketAdapters
      .find { it.matchesSocketFactory(sslSocketFactory) }
      ?.trustManager(sslSocketFactory)

  override fun newSSLContext(): SSLContext {
    StrictMode.noteSlowCall("newSSLContext")

    return super.newSSLContext()
  }

  override fun buildTrustRootIndex(trustManager: X509TrustManager): TrustRootIndex {
    StrictMode.noteSlowCall("buildTrustRootIndex")

    return super.buildTrustRootIndex(trustManager)
  }

  override fun configureTlsExtensions(
    sslSocket: SSLSocket,
    hostname: String?,
    protocols: List<Protocol>,
    echConfigList: ByteString?,
  ) {
    // No TLS extensions if the socket class is custom.
    socketAdapters
      .find { it.matchesSocket(sslSocket) }
      ?.configureTlsExtensions(sslSocket, hostname, protocols, echConfigList)
  }

  override fun getSelectedProtocol(sslSocket: SSLSocket): String? =
    // No TLS extensions if the socket class is custom.
    socketAdapters.find { it.matchesSocket(sslSocket) }?.getSelectedProtocol(sslSocket)

  override fun getStackTraceForCloseable(closer: String): Any? =
    if (Build.VERSION.SDK_INT >= 30) {
      CloseGuard().apply { open(closer) }
    } else {
      super.getStackTraceForCloseable(closer)
    }

  override fun logCloseableLeak(
    message: String,
    stackTrace: Any?,
  ) {
    if (Build.VERSION.SDK_INT >= 30) {
      (stackTrace as CloseGuard).warnIfOpen()
    } else {
      // Unable to report via CloseGuard. As a last-ditch effort, send it to the logger.
      super.logCloseableLeak(message, stackTrace)
    }
  }

  @SuppressLint("NewApi")
  override fun isCleartextTrafficPermitted(hostname: String): Boolean =
    NetworkSecurityPolicy.getInstance().isCleartextTrafficPermitted(hostname)

  override fun buildCertificateChainCleaner(trustManager: X509TrustManager): CertificateChainCleaner =
    AndroidCertificateChainCleaner.buildIfSupported(trustManager) ?: super.buildCertificateChainCleaner(trustManager)

  override fun log(
    message: String,
    level: Int,
    t: Throwable?,
  ) {
    if (level == WARN) {
      Log.w(Tag, message, t)
    } else {
      Log.i(Tag, message, t)
    }
  }

  companion object {
    val isSupported: Boolean = isAndroid && Build.VERSION.SDK_INT >= 29

    fun buildIfSupported(): Platform? = if (isSupported) Android10Platform() else null
  }
}
