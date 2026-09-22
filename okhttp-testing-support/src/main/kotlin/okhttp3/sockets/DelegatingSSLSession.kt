/*
 * Copyright 2019 Square Inc.
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
@file:Suppress("DEPRECATION")

package okhttp3.sockets

import java.security.Principal
import java.security.cert.Certificate
import javax.net.ssl.SSLPeerUnverifiedException
import javax.net.ssl.SSLSession
import javax.net.ssl.SSLSessionContext
import javax.security.cert.X509Certificate

/** An [SSLSession] that delegates all calls.  */
abstract class DelegatingSSLSession(
  protected val delegate: SSLSession?,
) : SSLSession {
  override fun getId(): ByteArray = delegate!!.id

  override fun getSessionContext(): SSLSessionContext = delegate!!.sessionContext

  override fun getCreationTime(): Long = delegate!!.creationTime

  override fun getLastAccessedTime(): Long = delegate!!.lastAccessedTime

  override fun invalidate() {
    delegate!!.invalidate()
  }

  override fun isValid(): Boolean = delegate!!.isValid

  override fun putValue(
    s: String,
    o: Any,
  ) {
    delegate!!.putValue(s, o)
  }

  override fun getValue(s: String): Any = delegate!!.getValue(s)

  override fun removeValue(s: String) {
    delegate!!.removeValue(s)
  }

  override fun getValueNames(): Array<String> = delegate!!.valueNames

  @Throws(SSLPeerUnverifiedException::class)
  override fun getPeerCertificates(): Array<Certificate>? = delegate!!.peerCertificates

  override fun getLocalCertificates(): Array<Certificate>? = delegate!!.localCertificates

  @Suppress("removal", "OVERRIDE_DEPRECATION")
  @Throws(SSLPeerUnverifiedException::class)
  override fun getPeerCertificateChain(): Array<X509Certificate> = delegate!!.peerCertificateChain

  @Throws(SSLPeerUnverifiedException::class)
  override fun getPeerPrincipal(): Principal = delegate!!.peerPrincipal

  override fun getLocalPrincipal(): Principal = delegate!!.localPrincipal

  override fun getCipherSuite(): String = delegate!!.cipherSuite

  override fun getProtocol(): String = delegate!!.protocol

  override fun getPeerHost(): String = delegate!!.peerHost

  override fun getPeerPort(): Int = delegate!!.peerPort

  override fun getPacketBufferSize(): Int = delegate!!.packetBufferSize

  override fun getApplicationBufferSize(): Int = delegate!!.applicationBufferSize
}
