/*
 * Copyright 2014 Square Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package okhttp3

import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory
import okhttp3.FallbackTestClientSocketFactory.Companion.TLS_FALLBACK_SCSV
import okhttp3.sockets.DelegatingSSLSocket
import okhttp3.sockets.DelegatingSSLSocketFactory

/**
 * An SSLSocketFactory that delegates calls. Sockets created by the delegate are wrapped with ones
 * that will not accept the [TLS_FALLBACK_SCSV] cipher, thus bypassing server-side fallback
 * checks on platforms that support it. Unfortunately this wrapping will disable any
 * reflection-based calls to SSLSocket from Platform.
 */
class FallbackTestClientSocketFactory(
  delegate: SSLSocketFactory,
) : DelegatingSSLSocketFactory(delegate) {
  override fun configureSocket(sslSocket: SSLSocket): SSLSocket = TlsFallbackScsvDisabledSSLSocket(sslSocket)

  private class TlsFallbackScsvDisabledSSLSocket(
    socket: SSLSocket,
  ) : DelegatingSSLSocket(socket) {
    override fun setEnabledCipherSuites(suites: Array<String>) {
      val enabledCipherSuites = mutableListOf<String>()
      for (suite in suites) {
        if (suite != TLS_FALLBACK_SCSV) {
          enabledCipherSuites.add(suite)
        }
      }
      delegate!!.enabledCipherSuites = enabledCipherSuites.toTypedArray<String>()
    }
  }

  companion object {
    /**
     * The cipher suite used during TLS connection fallback to indicate a fallback. See
     * https://tools.ietf.org/html/draft-ietf-tls-downgrade-scsv-00
     */
    const val TLS_FALLBACK_SCSV = "TLS_FALLBACK_SCSV"
  }
}
