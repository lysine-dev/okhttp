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
import okio.ByteString

/**
 * Deferred implementation of SocketAdapter that works by observing the socket
 * and initializing on first use.
 *
 * We use this because eager classpath checks cause confusion and excessive logging in Android,
 * and we can't rely on classnames after proguard, so are probably best served by falling through
 * to a situation of trying our least likely noisiest options.
 */
class DeferredSocketAdapter(
  private val socketAdapterFactory: Factory,
) : SocketAdapter {
  private var delegate: SocketAdapter? = null

  override fun isSupported(): Boolean = true

  override fun matchesSocket(sslSocket: SSLSocket): Boolean = socketAdapterFactory.matchesSocket(sslSocket)

  override fun configureTlsExtensions(
    sslSocket: SSLSocket,
    hostname: String?,
    protocols: List<Protocol>,
    echConfigList: ByteString?,
  ) {
    getDelegate(sslSocket)?.configureTlsExtensions(sslSocket, hostname, protocols, echConfigList)
  }

  override fun getSelectedProtocol(sslSocket: SSLSocket): String? = getDelegate(sslSocket)?.getSelectedProtocol(sslSocket)

  @Synchronized private fun getDelegate(sslSocket: SSLSocket): SocketAdapter? {
    if (this.delegate == null && socketAdapterFactory.matchesSocket(sslSocket)) {
      this.delegate = socketAdapterFactory.create(sslSocket)
    }

    return delegate
  }

  interface Factory {
    fun matchesSocket(sslSocket: SSLSocket): Boolean

    fun create(sslSocket: SSLSocket): SocketAdapter
  }
}
