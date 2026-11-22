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

import assertk.assertThat
import assertk.assertions.isEqualTo
import java.security.Principal
import java.security.cert.Certificate
import javax.net.ssl.ExtendedSSLSession
import javax.net.ssl.SNIHostName
import javax.net.ssl.SNIServerName
import javax.net.ssl.SSLSession
import javax.net.ssl.SSLSessionContext
import okhttp3.internal.platform.Platform.Companion.isAndroid
import okhttp3.sockets.DelegatingSSLSocket
import okhttp3.testing.PlatformRule
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.RegisterExtension

class PlatformTest {
  @RegisterExtension
  var platform = PlatformRule()

  @Test
  fun alwaysBuilds() {
    Platform()
  }

  /** Guard against the default value changing by accident.  */
  @Test
  fun defaultPrefix() {
    assertThat(Platform().prefix).isEqualTo("OkHttp")
  }

  @Test
  fun testToStringIsClassname() {
    assertThat(Platform().toString()).isEqualTo("Platform")
  }

  @Test
  fun testNotAndroid() {
    platform.assumeNotAndroid()

    // This is tautological so just confirms that it runs.
    assertThat(isAndroid).isEqualTo(false)
  }

  @Test
  fun getHandshakeServerNamesWithNullRequestedServerNames() {
    val session =
      object : FakeExtendedSSLSession() {
        override fun getRequestedServerNames(): List<SNIServerName>? = null
      }
    val sslSocket =
      object : DelegatingSSLSocket(null) {
        override fun getSession(): SSLSession = session
      }

    assertThat(Platform.get().getHandshakeServerNames(sslSocket)).isEqualTo(emptyList<String>())
  }

  @Test
  fun getHandshakeServerNamesWithSniHostNames() {
    val session =
      object : FakeExtendedSSLSession() {
        override fun getRequestedServerNames(): List<SNIServerName> = listOf(SNIHostName("example.com"))
      }
    val sslSocket =
      object : DelegatingSSLSocket(null) {
        override fun getSession(): SSLSession = session
      }

    assertThat(Platform.get().getHandshakeServerNames(sslSocket)).isEqualTo(listOf("example.com"))
  }

  private open class FakeExtendedSSLSession : ExtendedSSLSession() {
    override fun getRequestedServerNames(): List<SNIServerName>? = emptyList()

    override fun getId(): ByteArray = ByteArray(0)

    override fun getSessionContext(): SSLSessionContext? = null

    override fun getCreationTime(): Long = 0L

    override fun getLastAccessedTime(): Long = 0L

    override fun invalidate() {}

    override fun isValid(): Boolean = true

    override fun putValue(
      name: String?,
      value: Any?,
    ) {}

    override fun getValue(name: String?): Any? = null

    override fun removeValue(name: String?) {}

    override fun getValueNames(): Array<String> = emptyArray()

    override fun getPeerCertificates(): Array<Certificate> = emptyArray()

    override fun getLocalCertificates(): Array<Certificate> = emptyArray()

    @Suppress("DEPRECATION")
    @Deprecated("Deprecated in Java")
    override fun getPeerCertificateChain(): Array<javax.security.cert.X509Certificate> = emptyArray()

    override fun getPeerPrincipal(): Principal? = null

    override fun getLocalPrincipal(): Principal? = null

    override fun getCipherSuite(): String = "TLS_ECDHE_RSA_WITH_AES_128_GCM_SHA256"

    override fun getProtocol(): String = "TLSv1.3"

    override fun getPeerHost(): String = "localhost"

    override fun getPeerPort(): Int = 443

    override fun getLocalSupportedSignatureAlgorithms(): Array<String> = emptyArray()

    override fun getPeerSupportedSignatureAlgorithms(): Array<String> = emptyArray()

    override fun getPacketBufferSize(): Int = 16 * 1024

    override fun getApplicationBufferSize(): Int = 16 * 1024
  }
}
