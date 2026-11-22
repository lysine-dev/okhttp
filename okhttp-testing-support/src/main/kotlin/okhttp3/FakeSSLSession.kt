/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements. See the NOTICE file distributed with this
 * work for additional information regarding copyright ownership. The ASF
 * licenses this file to You under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 */
@file:Suppress("DEPRECATION")

package okhttp3

import java.security.Principal
import java.security.cert.Certificate
import javax.net.ssl.SSLPeerUnverifiedException
import javax.net.ssl.SSLSession
import javax.net.ssl.SSLSessionContext
import javax.security.cert.X509Certificate

class FakeSSLSession(
  vararg val certificates: Certificate,
) : SSLSession {
  override fun getApplicationBufferSize(): Int = throw UnsupportedOperationException()

  override fun getCipherSuite(): String = throw UnsupportedOperationException()

  override fun getCreationTime(): Long = throw UnsupportedOperationException()

  override fun getId(): ByteArray = throw UnsupportedOperationException()

  override fun getLastAccessedTime(): Long = throw UnsupportedOperationException()

  override fun getLocalCertificates(): Array<Certificate> = throw UnsupportedOperationException()

  override fun getLocalPrincipal(): Principal = throw UnsupportedOperationException()

  override fun getPacketBufferSize(): Int = throw UnsupportedOperationException()

  @Suppress("UNCHECKED_CAST")
  @Throws(SSLPeerUnverifiedException::class)
  override fun getPeerCertificates(): Array<Certificate> =
    if (certificates.isEmpty()) {
      throw SSLPeerUnverifiedException("peer not authenticated")
    } else {
      certificates as Array<Certificate>
    }

  @Suppress("removal", "OVERRIDE_DEPRECATION")
  @Throws(
    SSLPeerUnverifiedException::class,
  )
  override fun getPeerCertificateChain(): Array<X509Certificate> = throw UnsupportedOperationException()

  override fun getPeerHost(): String = throw UnsupportedOperationException()

  override fun getPeerPort(): Int = throw UnsupportedOperationException()

  @Throws(SSLPeerUnverifiedException::class)
  override fun getPeerPrincipal(): Principal = throw UnsupportedOperationException()

  override fun getProtocol(): String = throw UnsupportedOperationException()

  override fun getSessionContext(): SSLSessionContext = throw UnsupportedOperationException()

  override fun putValue(
    s: String,
    obj: Any,
  ): Unit = throw UnsupportedOperationException()

  override fun removeValue(s: String): Unit = throw UnsupportedOperationException()

  override fun getValue(s: String): Any = throw UnsupportedOperationException()

  override fun getValueNames(): Array<String> = throw UnsupportedOperationException()

  override fun invalidate(): Unit = throw UnsupportedOperationException()

  override fun isValid(): Boolean = throw UnsupportedOperationException()
}
