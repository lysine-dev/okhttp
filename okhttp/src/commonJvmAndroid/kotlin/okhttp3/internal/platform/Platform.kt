/*
 * Copyright (C) 2012 Square, Inc.
 * Copyright (C) 2012 The Android Open Source Project
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

import java.io.IOException
import java.net.InetSocketAddress
import java.net.Socket
import java.security.GeneralSecurityException
import java.security.KeyStore
import java.util.logging.Level
import java.util.logging.Logger
import javax.net.ServerSocketFactory
import javax.net.SocketFactory
import javax.net.ssl.ExtendedSSLSession
import javax.net.ssl.SNIHostName
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLException
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory
import javax.net.ssl.TrustManager
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager
import okhttp3.Dns
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.internal.OkHttpInternalApi
import okhttp3.internal.dns.EchRetryPlan
import okhttp3.internal.publicsuffix.PublicSuffixDatabase
import okhttp3.internal.readFieldOrNull
import okhttp3.internal.tls.BasicCertificateChainCleaner
import okhttp3.internal.tls.BasicTrustRootIndex
import okhttp3.internal.tls.CertificateChainCleaner
import okhttp3.internal.tls.TrustRootIndex
import okio.Buffer
import okio.ByteString
import org.codehaus.mojo.animal_sniffer.IgnoreJRERequirement

/**
 * Access to platform-specific features.
 *
 * ### Session Tickets
 *
 * Supported on Android 2.3+.
 * Supported on JDK 8+ via Conscrypt.
 *
 * ### ALPN (Application Layer Protocol Negotiation)
 *
 * Supported on Android 5.0+.
 *
 * Supported on OpenJDK 8 via the JettyALPN-boot library or Conscrypt.
 *
 * Supported on OpenJDK 9+ via SSLParameters and SSLSocket features.
 *
 * ### Trust Manager Extraction
 *
 * Supported on Android 2.3+ and OpenJDK 7+. There are no public APIs to recover the trust
 * manager that was used to create an [SSLSocketFactory].
 *
 * Not supported by choice on JDK9+ due to access checks.
 *
 * ### Android Cleartext Permit Detection
 *
 * Supported on Android 6.0+ via `NetworkSecurityPolicy`.
 */
@OkHttpInternalApi
open class Platform {
  open val socketFactory: SocketFactory
    get() = SocketFactory.getDefault()

  open val serverSocketFactory: ServerSocketFactory
    get() = ServerSocketFactory.getDefault()

  /** Prefix used on custom headers. */
  val prefix: String
    get() = "OkHttp"

  open fun newSSLContext(): SSLContext = SSLContext.getInstance("TLS")

  open fun platformTrustManager(): X509TrustManager {
    val factory =
      TrustManagerFactory.getInstance(
        TrustManagerFactory.getDefaultAlgorithm(),
      )
    factory.init(null as KeyStore?)
    val trustManagers = factory.trustManagers!!
    check(trustManagers.size == 1 && trustManagers[0] is X509TrustManager) {
      "Unexpected default trust managers: ${trustManagers.contentToString()}"
    }
    return trustManagers[0] as X509TrustManager
  }

  open fun trustManager(sslSocketFactory: SSLSocketFactory): X509TrustManager? {
    return try {
      // Attempt to get the trust manager from an OpenJDK socket factory. We attempt this on all
      // platforms in order to support Robolectric, which mixes classes from both Android and the
      // Oracle JDK. Note that we don't support HTTP/2 or other nice features on Robolectric.
      val sslContextClass = Class.forName("sun.security.ssl.SSLContextImpl")
      val context = readFieldOrNull(sslSocketFactory, sslContextClass, "context") ?: return null
      readFieldOrNull(context, X509TrustManager::class.java, "trustManager")
    } catch (e: ClassNotFoundException) {
      null
    } catch (e: RuntimeException) {
      // Throws InaccessibleObjectException (added in JDK9) on JDK 17 due to
      // JEP 403 Strongly Encapsulate JDK Internals.
      if (e.javaClass.name != "java.lang.reflect.InaccessibleObjectException") {
        throw e
      }

      null
    }
  }

  /**
   * Configure TLS extensions on `sslSocket` for `route`.
   */
  open fun configureTlsExtensions(
    sslSocket: SSLSocket,
    hostname: String?,
    protocols: List<@JvmSuppressWildcards Protocol>,
    echConfigList: ByteString?,
  ) {
  }

  /** Returns a plan to recover when a handshake that failed due to Encrypted Client Hello. */
  open fun echRetryPlan(exception: SSLException): EchRetryPlan? = null

  /** Called after the TLS handshake to release resources allocated by [configureTlsExtensions]. */
  open fun afterHandshake(sslSocket: SSLSocket) {
  }

  /** Returns the negotiated protocol, or null if no protocol was negotiated. */
  open fun getSelectedProtocol(sslSocket: SSLSocket): String? = null

  /** For MockWebServer. This returns the inbound SNI names. */
  @Suppress("NewApi")
  @IgnoreJRERequirement // This function is overridden to require API >= 24.
  open fun getHandshakeServerNames(sslSocket: SSLSocket): List<String> {
    val session = sslSocket.session as? ExtendedSSLSession ?: return listOf()
    return try {
      session.requestedServerNames.mapNotNull { (it as? SNIHostName)?.asciiName }
    } catch (_: UnsupportedOperationException) {
      // UnsupportedOperationException – if the underlying provider does not implement the operation
      // https://github.com/bcgit/bc-java/issues/1773
      listOf()
    }
  }

  /**
   * Provides the default [Dns] for the system, by default [Dns.SYSTEM].
   */
  open fun platformDns(): Dns = Dns.SYSTEM

  @Throws(IOException::class)
  open fun connectSocket(
    socket: Socket,
    address: InetSocketAddress,
    connectTimeout: Int,
  ) {
    socket.connect(address, connectTimeout)
  }

  open fun log(
    message: String,
    level: Int = INFO,
    t: Throwable? = null,
  ) {
    val logLevel = if (level == WARN) Level.WARNING else Level.INFO
    logger.log(logLevel, message, t)
  }

  open fun isCleartextTrafficPermitted(hostname: String): Boolean = true

  /**
   * Returns an object that holds a stack trace created at the moment this method is executed. This
   * should be used specifically for [java.io.Closeable] objects and in conjunction with
   * [logCloseableLeak].
   */
  open fun getStackTraceForCloseable(closer: String): Any? =
    when {
      logger.isLoggable(Level.FINE) -> Throwable(closer)

      // These are expensive to allocate.
      else -> null
    }

  open fun logCloseableLeak(
    message: String,
    stackTrace: Any?,
  ) {
    var logMessage = message
    if (stackTrace == null) {
      logMessage += " To see where this was allocated, set the OkHttpClient logger level to " +
        "FINE: Logger.getLogger(OkHttpClient.class.getName()).setLevel(Level.FINE);"
    }
    log(logMessage, WARN, stackTrace as Throwable?)
  }

  open fun buildCertificateChainCleaner(trustManager: X509TrustManager): CertificateChainCleaner =
    BasicCertificateChainCleaner(buildTrustRootIndex(trustManager))

  open fun buildTrustRootIndex(trustManager: X509TrustManager): TrustRootIndex = BasicTrustRootIndex(*trustManager.acceptedIssuers)

  open fun newSslSocketFactory(trustManager: X509TrustManager): SSLSocketFactory {
    try {
      return newSSLContext()
        .apply {
          init(null, arrayOf<TrustManager>(trustManager), null)
        }.socketFactory
    } catch (e: GeneralSecurityException) {
      throw AssertionError("No System TLS: $e", e) // The system has no TLS. Just give up.
    }
  }

  override fun toString(): String = javaClass.simpleName

  companion object {
    @Volatile private var platform = findPlatform()

    const val INFO = 4
    const val WARN = 5

    private val logger = Logger.getLogger(OkHttpClient::class.java.name)

    @JvmStatic
    fun get(): Platform = platform

    fun resetForTests(platform: Platform = findPlatform()) {
      this.platform = platform
      PublicSuffixDatabase.resetForTests()
    }

    fun alpnProtocolNames(protocols: List<Protocol>) = protocols.filter { it != Protocol.HTTP_1_0 }.map { it.toString() }

    val isAndroid: Boolean
      get() = PlatformRegistry.isAndroid

    /** Attempt to match the host runtime to a capable Platform implementation. */
    private fun findPlatform(): Platform = PlatformRegistry.findPlatform()

    /**
     * Returns the concatenation of 8-bit, length prefixed protocol names.
     * http://tools.ietf.org/html/draft-agl-tls-nextprotoneg-04#page-4
     */
    fun concatLengthPrefixed(protocols: List<Protocol>): ByteArray {
      val result = Buffer()
      for (protocol in alpnProtocolNames(protocols)) {
        result.writeByte(protocol.length)
        result.writeUtf8(protocol)
      }
      return result.readByteArray()
    }
  }
}
