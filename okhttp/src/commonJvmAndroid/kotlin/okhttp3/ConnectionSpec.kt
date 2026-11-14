/*
 * Copyright (C) 2014 Square, Inc.
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

import java.util.Arrays
import java.util.Objects
import javax.net.ssl.SSLSocket
import okhttp3.ConnectionSpec.Builder
import okhttp3.internal.concat
import okhttp3.internal.effectiveCipherSuites
import okhttp3.internal.hasIntersection
import okhttp3.internal.indexOf
import okhttp3.internal.intersect

/**
 * Specifies configuration for the socket connection that HTTP traffic travels through. For `https:`
 * URLs, this includes the TLS version and cipher suites to use when negotiating a secure
 * connection.
 *
 * The TLS versions configured in a connection spec are only be used if they are also enabled in the
 * SSL socket. For example, if an SSL socket does not have TLS 1.3 enabled, it will not be used even
 * if it is present on the connection spec. The same policy also applies to cipher suites.
 *
 * Use [Builder.allEnabledTlsVersions] and [Builder.allEnabledCipherSuites] to defer all feature
 * selection to the underlying SSL socket.
 *
 * The configuration of each spec changes with each OkHttp release. This is annoying: upgrading
 * your OkHttp library can break connectivity to certain web servers! But it’s a necessary annoyance
 * because the TLS ecosystem is dynamic and staying up to date is necessary to stay secure. See
 * [OkHttp's TLS Configuration History][tls_history] to track these changes.
 *
 * [tls_history]: https://lysine.dev/okhttp/tls_configuration_history/
 */
class ConnectionSpec internal constructor(
  @get:JvmName("isTls") val isTls: Boolean,
  @get:JvmName("supportsTlsExtensions") val supportsTlsExtensions: Boolean,
  internal val cipherSuitesAsString: Array<String>?,
  private val tlsVersionsAsString: Array<String>?,
) {
  /**
   * Returns the cipher suites to use for a connection. Returns null if all of the SSL socket's
   * enabled cipher suites should be used.
   */
  @get:JvmName("cipherSuites")
  val cipherSuites: List<CipherSuite>?
    get() {
      return cipherSuitesAsString?.map { CipherSuite.forJavaName(it) }
    }

  @JvmName("-deprecated_cipherSuites")
  @Deprecated(
    message = "moved to val",
    replaceWith = ReplaceWith(expression = "cipherSuites"),
    level = DeprecationLevel.ERROR,
  )
  fun cipherSuites(): List<CipherSuite>? = cipherSuites

  /**
   * Returns the TLS versions to use when negotiating a connection. Returns null if all of the SSL
   * socket's enabled TLS versions should be used.
   */
  @get:JvmName("tlsVersions")
  val tlsVersions: List<TlsVersion>?
    get() {
      return tlsVersionsAsString?.map { TlsVersion.forJavaName(it) }
    }

  @JvmName("-deprecated_tlsVersions")
  @Deprecated(
    message = "moved to val",
    replaceWith = ReplaceWith(expression = "tlsVersions"),
    level = DeprecationLevel.ERROR,
  )
  fun tlsVersions(): List<TlsVersion>? = tlsVersions

  @JvmName("-deprecated_supportsTlsExtensions")
  @Deprecated(
    message = "moved to val",
    replaceWith = ReplaceWith(expression = "supportsTlsExtensions"),
    level = DeprecationLevel.ERROR,
  )
  fun supportsTlsExtensions(): Boolean = supportsTlsExtensions

  /** Applies this spec to [sslSocket]. */
  internal fun apply(
    sslSocket: SSLSocket,
    isFallback: Boolean,
  ) {
    val specToApply = supportedSpec(sslSocket, isFallback)

    if (specToApply.tlsVersions != null) {
      sslSocket.enabledProtocols = specToApply.tlsVersionsAsString
    }

    if (specToApply.cipherSuites != null) {
      sslSocket.enabledCipherSuites = specToApply.cipherSuitesAsString
    }
  }

  /**
   * Returns a copy of this that omits cipher suites and TLS versions not enabled by [sslSocket].
   */
  private fun supportedSpec(
    sslSocket: SSLSocket,
    isFallback: Boolean,
  ): ConnectionSpec {
    val socketEnabledCipherSuites = sslSocket.enabledCipherSuites
    var cipherSuitesIntersection: Array<String> = effectiveCipherSuites(socketEnabledCipherSuites)

    val tlsVersionsIntersection =
      if (tlsVersionsAsString != null) {
        sslSocket.enabledProtocols.intersect(tlsVersionsAsString, naturalOrder())
      } else {
        sslSocket.enabledProtocols
      }

    // In accordance with https://tools.ietf.org/html/draft-ietf-tls-downgrade-scsv-00 the SCSV
    // cipher is added to signal that a protocol fallback has taken place.
    val supportedCipherSuites = sslSocket.supportedCipherSuites
    val indexOfFallbackScsv =
      supportedCipherSuites.indexOf(
        "TLS_FALLBACK_SCSV",
        CipherSuite.ORDER_BY_NAME,
      )
    if (isFallback && indexOfFallbackScsv != -1) {
      cipherSuitesIntersection =
        cipherSuitesIntersection.concat(
          supportedCipherSuites[indexOfFallbackScsv],
        )
    }

    return Builder(this)
      .cipherSuites(*cipherSuitesIntersection)
      .tlsVersions(*tlsVersionsIntersection)
      .build()
  }

  /**
   * Returns `true` if the socket, as currently configured, supports this connection spec. In
   * order for a socket to be compatible the enabled cipher suites and protocols must intersect.
   *
   * For cipher suites, at least one of the [required cipher suites][cipherSuites] must match the
   * socket's enabled cipher suites. If there are no required cipher suites the socket must have at
   * least one cipher suite enabled.
   *
   * For protocols, at least one of the [required protocols][tlsVersions] must match the socket's
   * enabled protocols.
   */
  fun isCompatible(socket: SSLSocket): Boolean {
    if (!isTls) {
      return false
    }

    if (tlsVersionsAsString != null &&
      !tlsVersionsAsString.hasIntersection(socket.enabledProtocols, naturalOrder())
    ) {
      return false
    }

    if (cipherSuitesAsString != null &&
      !cipherSuitesAsString.hasIntersection(
        socket.enabledCipherSuites,
        CipherSuite.ORDER_BY_NAME,
      )
    ) {
      return false
    }

    return true
  }

  override fun equals(other: Any?): Boolean {
    if (other !is ConnectionSpec) return false
    if (other === this) return true

    if (this.isTls != other.isTls) return false

    if (isTls) {
      if (!Arrays.equals(this.cipherSuitesAsString, other.cipherSuitesAsString)) return false
      if (!Arrays.equals(this.tlsVersionsAsString, other.tlsVersionsAsString)) return false
      if (this.supportsTlsExtensions != other.supportsTlsExtensions) return false
    }

    return true
  }

  override fun hashCode(): Int {
    var result = 17
    if (isTls) {
      result = 31 * result + (cipherSuitesAsString?.contentHashCode() ?: 0)
      result = 31 * result + (tlsVersionsAsString?.contentHashCode() ?: 0)
      result = 31 * result + if (supportsTlsExtensions) 0 else 1
    }
    return result
  }

  override fun toString(): String {
    if (!isTls) return "ConnectionSpec()"

    return (
      "ConnectionSpec(" +
        "cipherSuites=${Objects.toString(cipherSuites, "[all enabled]")}, " +
        "tlsVersions=${Objects.toString(tlsVersions, "[all enabled]")}, " +
        "supportsTlsExtensions=$supportsTlsExtensions)"
    )
  }

  class Builder {
    internal var tls: Boolean = false
    internal var cipherSuites: Array<String>? = null
    internal var tlsVersions: Array<String>? = null
    internal var supportsTlsExtensions: Boolean = false

    internal constructor(tls: Boolean) {
      this.tls = tls
    }

    constructor(connectionSpec: ConnectionSpec) {
      this.tls = connectionSpec.isTls
      this.cipherSuites = connectionSpec.cipherSuitesAsString
      this.tlsVersions = connectionSpec.tlsVersionsAsString
      this.supportsTlsExtensions = connectionSpec.supportsTlsExtensions
    }

    fun allEnabledCipherSuites() =
      apply {
        require(tls) { "no cipher suites for cleartext connections" }
        this.cipherSuites = null
      }

    fun cipherSuites(vararg cipherSuites: CipherSuite): Builder =
      apply {
        require(tls) { "no cipher suites for cleartext connections" }
        val strings = cipherSuites.map { it.javaName }.toTypedArray()
        return cipherSuites(*strings)
      }

    fun cipherSuites(vararg cipherSuites: String) =
      apply {
        require(tls) { "no cipher suites for cleartext connections" }
        require(cipherSuites.isNotEmpty()) { "At least one cipher suite is required" }

        @Suppress("UNCHECKED_CAST")
        this.cipherSuites = cipherSuites.copyOf() as Array<String> // Defensive copy.
      }

    fun allEnabledTlsVersions() =
      apply {
        require(tls) { "no TLS versions for cleartext connections" }
        this.tlsVersions = null
      }

    fun tlsVersions(vararg tlsVersions: TlsVersion): Builder =
      apply {
        require(tls) { "no TLS versions for cleartext connections" }

        val strings = tlsVersions.map { it.javaName }.toTypedArray()
        return tlsVersions(*strings)
      }

    fun tlsVersions(vararg tlsVersions: String) =
      apply {
        require(tls) { "no TLS versions for cleartext connections" }
        require(tlsVersions.isNotEmpty()) { "At least one TLS version is required" }

        @Suppress("UNCHECKED_CAST")
        this.tlsVersions = tlsVersions.copyOf() as Array<String> // Defensive copy.
      }

    @Deprecated(
      "since OkHttp 3.13 all TLS-connections are expected to support TLS extensions.\n" +
        "In a future release setting this to true will be unnecessary and setting it to false\n" +
        "will have no effect.",
    )
    fun supportsTlsExtensions(supportsTlsExtensions: Boolean) =
      apply {
        require(tls) { "no TLS extensions for cleartext connections" }
        this.supportsTlsExtensions = supportsTlsExtensions
      }

    fun build(): ConnectionSpec =
      ConnectionSpec(
        tls,
        supportsTlsExtensions,
        cipherSuites,
        tlsVersions,
      )
  }

  @Suppress("DEPRECATION")
  companion object {
    // Most secure but generally supported list.
    private val RESTRICTED_CIPHER_SUITES =
      listOf(
        // TLSv1.3.
        CipherSuite.TLS_AES_128_GCM_SHA256,
        CipherSuite.TLS_AES_256_GCM_SHA384,
        CipherSuite.TLS_CHACHA20_POLY1305_SHA256,
        // TLSv1.0, TLSv1.1, TLSv1.2.
        CipherSuite.TLS_ECDHE_ECDSA_WITH_AES_128_GCM_SHA256,
        CipherSuite.TLS_ECDHE_RSA_WITH_AES_128_GCM_SHA256,
        CipherSuite.TLS_ECDHE_ECDSA_WITH_AES_256_GCM_SHA384,
        CipherSuite.TLS_ECDHE_RSA_WITH_AES_256_GCM_SHA384,
        CipherSuite.TLS_ECDHE_ECDSA_WITH_CHACHA20_POLY1305_SHA256,
        CipherSuite.TLS_ECDHE_RSA_WITH_CHACHA20_POLY1305_SHA256,
      )

    // This is nearly equal to the cipher suites supported in Chrome 72, current as of 2019-02-24.
    // See https://tinyurl.com/okhttp-cipher-suites for availability.
    private val APPROVED_CIPHER_SUITES =
      listOf(
        // TLSv1.3.
        CipherSuite.TLS_AES_128_GCM_SHA256,
        CipherSuite.TLS_AES_256_GCM_SHA384,
        CipherSuite.TLS_CHACHA20_POLY1305_SHA256,
        // TLSv1.0, TLSv1.1, TLSv1.2.
        CipherSuite.TLS_ECDHE_ECDSA_WITH_AES_128_GCM_SHA256,
        CipherSuite.TLS_ECDHE_RSA_WITH_AES_128_GCM_SHA256,
        CipherSuite.TLS_ECDHE_ECDSA_WITH_AES_256_GCM_SHA384,
        CipherSuite.TLS_ECDHE_RSA_WITH_AES_256_GCM_SHA384,
        CipherSuite.TLS_ECDHE_ECDSA_WITH_CHACHA20_POLY1305_SHA256,
        CipherSuite.TLS_ECDHE_RSA_WITH_CHACHA20_POLY1305_SHA256,
        // Note that the following cipher suites are all on HTTP/2's bad cipher suites list. We'll
        // continue to include them until better suites are commonly available.
        CipherSuite.TLS_ECDHE_RSA_WITH_AES_128_CBC_SHA,
        CipherSuite.TLS_ECDHE_RSA_WITH_AES_256_CBC_SHA,
        CipherSuite.TLS_RSA_WITH_AES_128_GCM_SHA256,
        CipherSuite.TLS_RSA_WITH_AES_256_GCM_SHA384,
        CipherSuite.TLS_RSA_WITH_AES_128_CBC_SHA,
        CipherSuite.TLS_RSA_WITH_AES_256_CBC_SHA,
        CipherSuite.TLS_RSA_WITH_3DES_EDE_CBC_SHA,
      )

    /** A secure TLS connection that requires a recent client platform and a recent server. */
    @JvmField
    val RESTRICTED_TLS =
      Builder(true)
        .cipherSuites(*RESTRICTED_CIPHER_SUITES.toTypedArray())
        .tlsVersions(TlsVersion.TLS_1_3, TlsVersion.TLS_1_2)
        .supportsTlsExtensions(true)
        .build()

    /**
     * A modern TLS configuration that works on most client platforms and can connect to most servers.
     * This is OkHttp's default configuration.
     */
    @JvmField
    val MODERN_TLS =
      Builder(true)
        .cipherSuites(*APPROVED_CIPHER_SUITES.toTypedArray())
        .tlsVersions(TlsVersion.TLS_1_3, TlsVersion.TLS_1_2)
        .supportsTlsExtensions(true)
        .build()

    /**
     * A backwards-compatible fallback configuration that works on obsolete client platforms and can
     * connect to obsolete servers. When possible, prefer to upgrade your client platform or server
     * rather than using this configuration.
     */
    @JvmField
    val COMPATIBLE_TLS =
      Builder(true)
        .cipherSuites(*APPROVED_CIPHER_SUITES.toTypedArray())
        .tlsVersions(TlsVersion.TLS_1_3, TlsVersion.TLS_1_2, TlsVersion.TLS_1_1, TlsVersion.TLS_1_0)
        .supportsTlsExtensions(true)
        .build()

    /** Unencrypted, unauthenticated connections for `http:` URLs. */
    @JvmField
    val CLEARTEXT = Builder(false).build()
  }
}
