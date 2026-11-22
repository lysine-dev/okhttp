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
import javax.net.ssl.SSLSocketFactory
import javax.net.ssl.X509TrustManager
import okhttp3.Protocol
import okio.ByteString

interface SocketAdapter {
  fun isSupported(): Boolean

  fun trustManager(sslSocketFactory: SSLSocketFactory): X509TrustManager? = null

  fun matchesSocket(sslSocket: SSLSocket): Boolean

  fun matchesSocketFactory(sslSocketFactory: SSLSocketFactory): Boolean = false

  fun configureTlsExtensions(
    sslSocket: SSLSocket,
    hostname: String?,
    protocols: List<Protocol>,
    echConfigList: ByteString?,
  )

  fun getSelectedProtocol(sslSocket: SSLSocket): String?
}
