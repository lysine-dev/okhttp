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
@file:Suppress("Since15")
@file:SuppressLint("NewApi")

package okhttp3.sockets

import android.annotation.SuppressLint
import java.security.Provider
import java.security.SecureRandom
import javax.net.ServerSocketFactory
import javax.net.SocketFactory
import javax.net.ssl.KeyManager
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLContextSpi
import javax.net.ssl.SSLException
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory
import javax.net.ssl.TrustManager
import javax.net.ssl.X509KeyManager
import javax.net.ssl.X509TrustManager
import okhttp3.Protocol
import okhttp3.internal.ech.EchRejectedException
import okhttp3.internal.ech.EchRetryPlan
import okhttp3.internal.platform.Platform
import okhttp3.tls.internal.TlsUtil.newKeyManager
import okio.ByteString

class FakeNetworkPlatform : Platform() {
  val network = FakeNetwork()

  var handshaker: Handshaker = InsecureHandshaker()

  override val socketFactory: SocketFactory
    get() = network.socketFactory

  override val serverSocketFactory: ServerSocketFactory
    get() = network.serverSocketFactory

  override fun newSSLContext(): SSLContext {
    @Suppress("DEPRECATION") // Non-deprecated overload requires Java 9+.
    val provider =
      object : Provider("FakeNetwork", 0.0, "") {
      }
    return object : SSLContext(FakeSslContextSpi(), provider, "TLSv1.2") {
    }
  }

  override fun trustManager(sslSocketFactory: SSLSocketFactory) = null

  override fun configureTlsExtensions(
    sslSocket: SSLSocket,
    hostname: String?,
    protocols: List<@JvmSuppressWildcards Protocol>,
    echConfigList: ByteString?,
  ) {
    check(sslSocket is FakeSslSocket)
    sslSocket.sslParameters.applicationProtocols = protocols.map { it.toString() }.toTypedArray()
    sslSocket.echConfigList = echConfigList
  }

  override fun afterHandshake(sslSocket: SSLSocket) {
  }

  override fun getSelectedProtocol(sslSocket: SSLSocket): String? = sslSocket.applicationProtocol

  override fun getHandshakeServerNames(sslSocket: SSLSocket): List<String> {
    val serverNames = sslSocket.sslParameters.serverNames ?: return listOf()
    return serverNames.map { it.encoded.decodeToString() }
  }

  override fun newSslSocketFactory(trustManager: X509TrustManager): SSLSocketFactory {
    val sslContext = newSSLContext()
    sslContext.init(
      arrayOf(newKeyManager(null, null)),
      arrayOf(trustManager),
      SecureRandom(),
    )
    return sslContext.socketFactory
  }

  override fun echRetryPlan(exception: SSLException): EchRetryPlan? =
    when (exception) {
      is EchRejectedException -> {
        EchRetryPlan.getOrNull(
          publicName = exception.publicName,
          configList = exception.nextEchConfigList,
        )
      }

      else -> {
        null
      }
    }

  private inner class FakeSslContextSpi : SSLContextSpi() {
    private var fakeTls: FakeTls? = null

    override fun engineInit(
      keyManagers: Array<out KeyManager>,
      trustManagers: Array<out TrustManager>,
      secureRandom: SecureRandom,
    ) {
      check(this.fakeTls == null) { "already initialized" }

      // FakeNetworkPlatform.handshaker may change after engineInit(), and we want the latest value.
      val forwardingHandshaker =
        object : Handshaker {
          override fun handshake(
            client: Handshaker.ClientInputs,
            server: Handshaker.ServerInputs,
          ) = this@FakeNetworkPlatform.handshaker.handshake(client, server)
        }

      fakeTls =
        FakeTls(
          handshaker = forwardingHandshaker,
          keyManager = keyManagers.filterIsInstance<X509KeyManager>().single(),
          trustManager = trustManagers.filterIsInstance<X509TrustManager>().single(),
        )
    }

    override fun engineGetSocketFactory(): SSLSocketFactory {
      val fakeTls = this.fakeTls ?: error("call init() first")
      return fakeTls.sslSocketFactory
    }

    override fun engineGetServerSocketFactory() = error("unsupported")

    override fun engineCreateSSLEngine() = error("unsupported")

    override fun engineCreateSSLEngine(
      host: String,
      port: Int,
    ) = error("unsupported")

    override fun engineGetServerSessionContext() = error("unsupported")

    override fun engineGetClientSessionContext() = error("unsupported")
  }
}
