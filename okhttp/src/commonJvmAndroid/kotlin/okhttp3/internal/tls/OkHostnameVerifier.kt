/*
 *  Licensed to the Apache Software Foundation (ASF) under one or more
 *  contributor license agreements.  See the NOTICE file distributed with
 *  this work for additional information regarding copyright ownership.
 *  The ASF licenses this file to You under the Apache License, Version 2.0
 *  (the "License"); you may not use this file except in compliance with
 *  the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */
package okhttp3.internal.tls

import java.security.cert.CertificateParsingException
import java.security.cert.X509Certificate
import java.util.Locale
import javax.net.ssl.HostnameVerifier
import javax.net.ssl.SSLException
import javax.net.ssl.SSLSession
import okhttp3.internal.canParseAsIpAddress
import okhttp3.internal.toCanonicalHost
import okio.utf8Size

/**
 * A HostnameVerifier consistent with [RFC 2818][rfc_2818].
 *
 * [rfc_2818]: http://www.ietf.org/rfc/rfc2818.txt
 */
@Suppress("NAME_SHADOWING")
object OkHostnameVerifier : HostnameVerifier {
  private const val ALT_DNS_NAME = 2
  private const val ALT_IPA_NAME = 7

  override fun verify(
    host: String,
    session: SSLSession,
  ): Boolean =
    if (!host.isAscii()) {
      false
    } else {
      try {
        verify(host, session.peerCertificates[0] as X509Certificate)
      } catch (_: SSLException) {
        false
      }
    }

  fun verify(
    host: String,
    certificate: X509Certificate,
  ): Boolean =
    when {
      host.canParseAsIpAddress() -> verifyIpAddress(host, certificate)
      else -> verifyHostname(host, certificate)
    }

  /** Returns true if [certificate] matches [ipAddress]. */
  private fun verifyIpAddress(
    ipAddress: String,
    certificate: X509Certificate,
  ): Boolean {
    val canonicalIpAddress = ipAddress.toCanonicalHost() ?: return false

    return getSubjectAltNames(certificate, ALT_IPA_NAME).any {
      canonicalIpAddress == it.toCanonicalHost()
    }
  }

  /** Returns true if [certificate] matches [hostname]. */
  private fun verifyHostname(
    hostname: String,
    certificate: X509Certificate,
  ): Boolean {
    val hostname = hostname.asciiToLowercase()
    return getSubjectAltNames(certificate, ALT_DNS_NAME).any {
      verifyHostname(hostname, it)
    }
  }

  /**
   * This is like [toLowerCase] except that it does nothing if this contains any non-ASCII
   * characters. We want to avoid lower casing special chars like U+212A (Kelvin symbol) because
   * they can return ASCII characters that match real hostnames.
   */
  private fun String.asciiToLowercase(): String =
    when {
      isAscii() -> lowercase(Locale.US)

      // This is an ASCII string.
      else -> this
    }

  /** Returns true if the [String] is ASCII encoded (0-127). */
  private fun String.isAscii() = length == utf8Size().toInt()

  /**
   * Returns true if [hostname] matches the domain name [pattern].
   *
   * @param hostname lower-case host name.
   * @param pattern domain name pattern from certificate. May be a wildcard pattern such as
   *     `*.android.com`.
   */
  private fun verifyHostname(
    hostname: String?,
    pattern: String?,
  ): Boolean {
    var hostname = hostname
    var pattern = pattern
    if (hostname.isNullOrEmpty() ||
      hostname.startsWith(".") ||
      hostname.endsWith("..")
    ) {
      // Invalid domain name.
      return false
    }
    if (pattern.isNullOrEmpty() ||
      pattern.startsWith(".") ||
      pattern.endsWith("..")
    ) {
      // Invalid pattern.
      return false
    }

    // Normalize hostname and pattern by turning them into absolute domain names if they are not
    // yet absolute. This is needed because server certificates do not normally contain absolute
    // names or patterns, but they should be treated as absolute. At the same time, any hostname
    // presented to this method should also be treated as absolute for the purposes of matching
    // to the server certificate.
    //   www.android.com  matches www.android.com
    //   www.android.com  matches www.android.com.
    //   www.android.com. matches www.android.com.
    //   www.android.com. matches www.android.com
    if (!hostname.endsWith(".")) {
      hostname += "."
    }
    if (!pattern.endsWith(".")) {
      pattern += "."
    }
    // Hostname and pattern are now absolute domain names.

    pattern = pattern.asciiToLowercase()
    // Hostname and pattern are now in lower case -- domain names are case-insensitive.

    if ("*" !in pattern) {
      // Not a wildcard pattern -- hostname and pattern must match exactly.
      return hostname == pattern
    }

    // Wildcard pattern

    // WILDCARD PATTERN RULES:
    // 1. Asterisk (*) is only permitted in the left-most domain name label and must be the
    //    only character in that label (i.e., must match the whole left-most label).
    //    For example, *.example.com is permitted, while *a.example.com, a*.example.com,
    //    a*b.example.com, a.*.example.com are not permitted.
    // 2. Asterisk (*) cannot match across domain name labels.
    //    For example, *.example.com matches test.example.com but does not match
    //    sub.test.example.com.
    // 3. Wildcard patterns for single-label domain names are not permitted.

    if (!pattern.startsWith("*.") || pattern.indexOf('*', 1) != -1) {
      // Asterisk (*) is only permitted in the left-most domain name label and must be the only
      // character in that label
      return false
    }

    // Optimization: check whether hostname is too short to match the pattern. hostName must be at
    // least as long as the pattern because asterisk must match the whole left-most label and
    // hostname starts with a non-empty label. Thus, asterisk has to match one or more characters.
    if (hostname.length < pattern.length) {
      return false // Hostname too short to match the pattern.
    }

    if ("*." == pattern) {
      return false // Wildcard pattern for single-label domain name -- not permitted.
    }

    // Hostname must end with the region of pattern following the asterisk.
    val suffix = pattern.substring(1)
    if (!hostname.endsWith(suffix)) {
      return false // Hostname does not end with the suffix.
    }

    // Check that asterisk did not match across domain name labels.
    val suffixStartIndexInHostname = hostname.length - suffix.length
    if (suffixStartIndexInHostname > 0 &&
      hostname.lastIndexOf('.', suffixStartIndexInHostname - 1) != -1
    ) {
      return false // Asterisk is matching across domain name labels -- not permitted.
    }

    // Hostname matches pattern.
    return true
  }

  fun allSubjectAltNames(certificate: X509Certificate): List<String> {
    val altIpaNames = getSubjectAltNames(certificate, ALT_IPA_NAME)
    val altDnsNames = getSubjectAltNames(certificate, ALT_DNS_NAME)
    return altIpaNames + altDnsNames
  }

  private fun getSubjectAltNames(
    certificate: X509Certificate,
    type: Int,
  ): List<String> {
    try {
      val subjectAltNames = certificate.subjectAlternativeNames ?: return emptyList()
      val result = mutableListOf<String>()
      for (subjectAltName in subjectAltNames) {
        if (subjectAltName == null || subjectAltName.size < 2) continue
        if (subjectAltName[0] != type) continue
        val altName = subjectAltName[1] ?: continue
        result.add(altName as String)
      }
      return result
    } catch (_: CertificateParsingException) {
      return emptyList()
    }
  }
}
