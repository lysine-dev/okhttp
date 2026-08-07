/*
 * Copyright (C) 2016 Google Inc.
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
package okhttp3

import assertk.assertThat
import assertk.assertions.containsExactly
import assertk.assertions.isEqualTo
import assertk.assertions.isNotEqualTo
import assertk.assertions.isSameInstanceAs
import okhttp3.CipherSuite.Companion.forJavaName
import okhttp3.internal.applyConnectionSpec
import okhttp3.sockets.DelegatingSSLSocket
import org.junit.jupiter.api.Test

class CipherSuiteTest {
  @Test
  fun hashCode_usesIdentityHashCode_legacyCase() {
    val cs = CipherSuite.TLS_RSA_EXPORT_WITH_RC4_40_MD5 // This one's javaName starts with "SSL_".
    assertThat(cs.hashCode(), cs.toString())
      .isEqualTo(System.identityHashCode(cs))
  }

  @Test
  fun hashCode_usesIdentityHashCode_regularCase() {
    // This one's javaName matches the identifier.
    val cs = CipherSuite.TLS_RSA_WITH_AES_128_CBC_SHA256
    assertThat(cs.hashCode(), cs.toString())
      .isEqualTo(System.identityHashCode(cs))
  }

  @Test
  fun instancesAreInterned() {
    assertThat(forJavaName("TestCipherSuite"))
      .isSameInstanceAs(forJavaName("TestCipherSuite"))
    assertThat(forJavaName(CipherSuite.TLS_KRB5_WITH_DES_CBC_MD5.javaName))
      .isSameInstanceAs(
        CipherSuite.TLS_KRB5_WITH_DES_CBC_MD5,
      )
  }

  /**
   * Tests that interned CipherSuite instances remain the case across garbage collections, even if
   * the String used to construct them is no longer strongly referenced outside of the CipherSuite.
   */
  @Test
  fun instancesAreInterned_survivesGarbageCollection() {
    // We're not holding onto a reference to this String instance outside of the CipherSuite...
    val cs = forJavaName("FakeCipherSuite_instancesAreInterned")
    System.gc() // Unless cs references the String instance, it may now be garbage collected.
    assertThat(forJavaName(java.lang.String(cs.javaName) as String))
      .isSameInstanceAs(cs)
  }

  @Test
  fun equals() {
    assertThat(forJavaName("cipher")).isEqualTo(forJavaName("cipher"))
    assertThat(forJavaName("cipherB")).isNotEqualTo(forJavaName("cipherA"))
    assertThat(CipherSuite.TLS_RSA_EXPORT_WITH_RC4_40_MD5)
      .isEqualTo(forJavaName("SSL_RSA_EXPORT_WITH_RC4_40_MD5"))
    assertThat(CipherSuite.TLS_RSA_WITH_AES_128_CBC_SHA256)
      .isNotEqualTo(CipherSuite.TLS_RSA_EXPORT_WITH_RC4_40_MD5)
  }

  @Test
  fun forJavaName_acceptsArbitraryStrings() {
    // Shouldn't throw.
    forJavaName("example CipherSuite name that is not in the whitelist")
  }

  @Test
  fun javaName_examples() {
    assertThat(CipherSuite.TLS_RSA_EXPORT_WITH_RC4_40_MD5.javaName)
      .isEqualTo("SSL_RSA_EXPORT_WITH_RC4_40_MD5")
    assertThat(CipherSuite.TLS_RSA_WITH_AES_128_CBC_SHA256.javaName)
      .isEqualTo("TLS_RSA_WITH_AES_128_CBC_SHA256")
    assertThat(forJavaName("TestCipherSuite").javaName)
      .isEqualTo("TestCipherSuite")
  }

  @Test
  fun javaName_equalsToString() {
    assertThat(CipherSuite.TLS_RSA_EXPORT_WITH_RC4_40_MD5.toString())
      .isEqualTo(CipherSuite.TLS_RSA_EXPORT_WITH_RC4_40_MD5.javaName)
    assertThat(CipherSuite.TLS_RSA_WITH_AES_128_CBC_SHA256.toString())
      .isEqualTo(CipherSuite.TLS_RSA_WITH_AES_128_CBC_SHA256.javaName)
  }

  /**
   * On the Oracle JVM some older cipher suites have the "SSL_" prefix and others have the "TLS_"
   * prefix. On the IBM JVM all cipher suites have the "SSL_" prefix.
   *
   * Prior to OkHttp 3.3.1 we accepted either form and consider them equivalent. And since OkHttp
   * 3.7.0 this is also true. But OkHttp 3.3.1 through 3.6.0 treated these as different.
   */
  @Test
  fun forJavaName_fromLegacyEnumName() {
    // These would have been considered equal in OkHttp 3.3.1, but now aren't.
    assertThat(forJavaName("SSL_RSA_EXPORT_WITH_RC4_40_MD5"))
      .isEqualTo(forJavaName("TLS_RSA_EXPORT_WITH_RC4_40_MD5"))
    assertThat(forJavaName("SSL_DH_RSA_EXPORT_WITH_DES40_CBC_SHA"))
      .isEqualTo(forJavaName("TLS_DH_RSA_EXPORT_WITH_DES40_CBC_SHA"))
    assertThat(forJavaName("SSL_FAKE_NEW_CIPHER"))
      .isEqualTo(forJavaName("TLS_FAKE_NEW_CIPHER"))
  }

  @Test
  fun applyIntersectionRetainsTlsPrefixes() {
    val socket =
      FakeSslSocket(
        enabledProtocols = listOf("TLSv1"),
        supportedCipherSuites = listOf("SSL_A", "SSL_B", "SSL_C", "SSL_D", "SSL_E"),
        enabledCipherSuites = listOf("SSL_A", "SSL_B", "SSL_C"),
      )
    val connectionSpec =
      ConnectionSpec
        .Builder(true)
        .tlsVersions(TlsVersion.TLS_1_0)
        .cipherSuites("TLS_A", "TLS_C", "TLS_E")
        .build()
    applyConnectionSpec(connectionSpec, socket, false)
    assertThat(socket.enabledCipherSuites).containsExactly("TLS_A", "TLS_C")
  }

  @Test
  fun applyIntersectionRetainsSslPrefixes() {
    val socket =
      FakeSslSocket(
        enabledProtocols = listOf("TLSv1"),
        supportedCipherSuites = listOf("TLS_A", "TLS_B", "TLS_C", "TLS_D", "TLS_E"),
        enabledCipherSuites = listOf("TLS_A", "TLS_B", "TLS_C"),
      )
    val connectionSpec =
      ConnectionSpec
        .Builder(true)
        .tlsVersions(TlsVersion.TLS_1_0)
        .cipherSuites("SSL_A", "SSL_C", "SSL_E")
        .build()
    applyConnectionSpec(connectionSpec, socket, false)
    assertThat(socket.enabledCipherSuites).containsExactly("SSL_A", "SSL_C")
  }

  @Test
  fun applyIntersectionAddsSslScsvForFallback() {
    val socket =
      FakeSslSocket(
        enabledProtocols = listOf("TLSv1"),
        supportedCipherSuites = listOf("SSL_A", "SSL_FALLBACK_SCSV"),
        enabledCipherSuites = listOf("SSL_A"),
      )
    val connectionSpec =
      ConnectionSpec
        .Builder(true)
        .tlsVersions(TlsVersion.TLS_1_0)
        .cipherSuites("SSL_A")
        .build()
    applyConnectionSpec(connectionSpec, socket, true)
    assertThat(socket.enabledCipherSuites).containsExactly("SSL_A", "SSL_FALLBACK_SCSV")
  }

  @Test
  fun applyIntersectionAddsTlsScsvForFallback() {
    val socket =
      FakeSslSocket(
        enabledProtocols = listOf("TLSv1"),
        supportedCipherSuites = listOf("TLS_A", "TLS_FALLBACK_SCSV"),
        enabledCipherSuites = listOf("TLS_A"),
      )
    val connectionSpec =
      ConnectionSpec
        .Builder(true)
        .tlsVersions(TlsVersion.TLS_1_0)
        .cipherSuites("TLS_A")
        .build()
    applyConnectionSpec(connectionSpec, socket, true)
    assertThat(socket.enabledCipherSuites).containsExactly("TLS_A", "TLS_FALLBACK_SCSV")
  }

  @Test
  fun applyIntersectionToProtocolVersion() {
    val socket =
      FakeSslSocket(
        enabledProtocols = listOf("TLSv1", "TLSv1.1", "TLSv1.2"),
        supportedCipherSuites = listOf("TLS_A"),
        enabledCipherSuites = listOf("TLS_A"),
      )
    val connectionSpec =
      ConnectionSpec
        .Builder(true)
        .tlsVersions(TlsVersion.TLS_1_1, TlsVersion.TLS_1_2, TlsVersion.TLS_1_3)
        .cipherSuites("TLS_A")
        .build()
    applyConnectionSpec(connectionSpec, socket, false)
    assertThat(socket.enabledProtocols).containsExactly("TLSv1.1", "TLSv1.2")
  }

  class FakeSslSocket(
    var enabledProtocols: List<String> = listOf(TlsVersion.TLS_1_3.javaName),
    var supportedCipherSuites: List<String> =
      listOf(
        CipherSuite.TLS_AES_128_GCM_SHA256.javaName,
        CipherSuite.TLS_AES_256_GCM_SHA384.javaName,
        CipherSuite.TLS_CHACHA20_POLY1305_SHA256.javaName,
      ),
    var enabledCipherSuites: List<String> = supportedCipherSuites,
  ) : DelegatingSSLSocket(null) {
    override fun getEnabledProtocols() = enabledProtocols.toTypedArray()

    override fun setEnabledProtocols(protocols: Array<String>) {
      this.enabledProtocols = protocols.toList()
    }

    override fun getSupportedCipherSuites() = supportedCipherSuites.toTypedArray()

    override fun getEnabledCipherSuites() = enabledCipherSuites.toTypedArray()

    override fun setEnabledCipherSuites(suites: Array<String>) {
      this.enabledCipherSuites = suites.toList()
    }
  }
}
