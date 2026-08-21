/*
 * Copyright (C) 2015 Square, Inc.
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

import java.util.Calendar
import java.util.Date
import java.util.GregorianCalendar
import java.util.Locale
import java.util.regex.Pattern
import okhttp3.internal.UTC
import okhttp3.internal.canParseAsIpAddress
import okhttp3.internal.delimiterOffset
import okhttp3.internal.http.MAX_DATE
import okhttp3.internal.http.toHttpDateString
import okhttp3.internal.indexOfControlOrNonAscii
import okhttp3.internal.publicsuffix.PublicSuffixDatabase
import okhttp3.internal.toCanonicalHost
import okhttp3.internal.trimSubstring
import okhttp3.internal.unmodifiable
import org.codehaus.mojo.animal_sniffer.IgnoreJRERequirement

/**
 * An [RFC 6265](http://tools.ietf.org/html/rfc6265) Cookie.
 *
 * This class doesn't support additional attributes on cookies, like
 * [Chromium's Priority=HIGH extension][chromium_extension].
 *
 * [chromium_extension]: https://code.google.com/p/chromium/issues/detail?id=232693
 */
@Suppress("NAME_SHADOWING")
class Cookie private constructor(
  /** Returns a non-empty string with this cookie's name. */
  @get:JvmName("name") val name: String,
  /** Returns a possibly-empty string with this cookie's value. */
  @get:JvmName("value") val value: String,
  /**
   * Returns the time that this cookie expires, in the same format as [System.currentTimeMillis].
   * This is December 31, 9999 if the cookie is not [persistent], in which case it will expire at the
   * end of the current session.
   *
   * This may return a value less than the current time, in which case the cookie is already
   * expired. Webservers may return expired cookies as a mechanism to delete previously set cookies
   * that may or may not themselves be expired.
   */
  @get:JvmName("expiresAt") val expiresAt: Long,
  /**
   * Returns the cookie's domain. If [hostOnly] returns true this is the only domain that matches
   * this cookie; otherwise it matches this domain and all subdomains.
   */
  @get:JvmName("domain") val domain: String,
  /**
   * Returns this cookie's path. This cookie matches URLs prefixed with path segments that match
   * this path's segments. For example, if this path is `/foo` this cookie matches requests to
   * `/foo` and `/foo/bar`, but not `/` or `/football`.
   */
  @get:JvmName("path") val path: String,
  /** Returns true if this cookie should be limited to only HTTPS requests. */
  @get:JvmName("secure") val secure: Boolean,
  /**
   * Returns true if this cookie should be limited to only HTTP APIs. In web browsers this prevents
   * the cookie from being accessible to scripts.
   */
  @get:JvmName("httpOnly") val httpOnly: Boolean,
  /**
   * Returns true if this cookie does not expire at the end of the current session.
   *
   * This is true if either 'expires' or 'max-age' is present.
   */
  @get:JvmName("persistent") val persistent: Boolean,
  /**
   * Returns true if this cookie's domain should be interpreted as a single host name, or false if
   * it should be interpreted as a pattern. This flag will be false if its `Set-Cookie` header
   * included a `domain` attribute.
   *
   * For example, suppose the cookie's domain is `example.com`. If this flag is true it matches
   * **only** `example.com`. If this flag is false it matches `example.com` and all subdomains
   * including `api.example.com`, `www.example.com`, and `beta.api.example.com`.
   *
   * This is true unless 'domain' is present.
   */
  @get:JvmName("hostOnly") val hostOnly: Boolean,
  /**
   * Returns a string describing whether this cookie is sent for cross-site calls.
   *
   * Two URLs are on the same site if they share a [top private domain][HttpUrl.topPrivateDomain].
   * Otherwise, they are cross-site URLs.
   *
   * When a URL is requested, it may be in the context of another URL.
   *
   *  * **Embedded resources like images and iframes** in browsers use the context as the page in
   *    the address bar and the subject is the URL of an embedded resource.
   *
   *  * **Potentially-destructive navigations such as HTTP POST calls** use the context as the page
   *    originating the navigation, and the subject is the page being navigated to.
   *
   * The values of this attribute determine whether this cookie is sent for cross-site calls:
   *
   *  - "Strict": the cookie is omitted when the subject URL is an embedded resource or a
   *    potentially-destructive navigation.
   *
   *  - "Lax": the cookie is omitted when the subject URL is an embedded resource. It is sent for
   *    potentially-destructive navigation. This is the default value.
   *
   *  - "None": the cookie is always sent. The "Secure" attribute must also be set when setting this
   *    value.
   */
  @get:JvmName("sameSite")
  val sameSite: String?,
  /**
   * Returns true if this cookie has the `Partitioned` attribute set. In web browsers this restricts
   * the cookie to a separate storage jar per top-level site (CHIPS). Browsers require [secure] to
   * also be true before honoring this attribute.
   */
  @get:JvmName("partitioned") val partitioned: Boolean,
) {
  /**
   * Returns true if this cookie should be included on a request to [url]. In addition to this
   * check callers should also confirm that this cookie has not expired.
   */
  fun matches(url: HttpUrl): Boolean {
    val domainMatch =
      if (hostOnly) {
        url.host == domain
      } else {
        domainMatch(url.host, domain)
      }
    if (!domainMatch) return false

    if (!pathMatch(url, path)) return false

    return !secure || url.isHttps
  }

  override fun equals(other: Any?): Boolean =
    other is Cookie &&
      other.name == name &&
      other.value == value &&
      other.expiresAt == expiresAt &&
      other.domain == domain &&
      other.path == path &&
      other.secure == secure &&
      other.httpOnly == httpOnly &&
      other.persistent == persistent &&
      other.hostOnly == hostOnly &&
      other.sameSite == sameSite &&
      other.partitioned == partitioned

  @IgnoreJRERequirement // As of AGP 3.4.1, D8 desugars API 24 hashCode methods.
  override fun hashCode(): Int {
    var result = 17
    result = 31 * result + name.hashCode()
    result = 31 * result + value.hashCode()
    result = 31 * result + expiresAt.hashCode()
    result = 31 * result + domain.hashCode()
    result = 31 * result + path.hashCode()
    result = 31 * result + secure.hashCode()
    result = 31 * result + httpOnly.hashCode()
    result = 31 * result + persistent.hashCode()
    result = 31 * result + hostOnly.hashCode()
    result = 31 * result + sameSite.hashCode()
    result = 31 * result + partitioned.hashCode()
    return result
  }

  override fun toString(): String = toString(false)

  @JvmName("-deprecated_name")
  @Deprecated(
    message = "moved to val",
    replaceWith = ReplaceWith(expression = "name"),
    level = DeprecationLevel.ERROR,
  )
  fun name(): String = name

  @JvmName("-deprecated_value")
  @Deprecated(
    message = "moved to val",
    replaceWith = ReplaceWith(expression = "value"),
    level = DeprecationLevel.ERROR,
  )
  fun value(): String = value

  @JvmName("-deprecated_persistent")
  @Deprecated(
    message = "moved to val",
    replaceWith = ReplaceWith(expression = "persistent"),
    level = DeprecationLevel.ERROR,
  )
  fun persistent(): Boolean = persistent

  @JvmName("-deprecated_expiresAt")
  @Deprecated(
    message = "moved to val",
    replaceWith = ReplaceWith(expression = "expiresAt"),
    level = DeprecationLevel.ERROR,
  )
  fun expiresAt(): Long = expiresAt

  @JvmName("-deprecated_hostOnly")
  @Deprecated(
    message = "moved to val",
    replaceWith = ReplaceWith(expression = "hostOnly"),
    level = DeprecationLevel.ERROR,
  )
  fun hostOnly(): Boolean = hostOnly

  @JvmName("-deprecated_domain")
  @Deprecated(
    message = "moved to val",
    replaceWith = ReplaceWith(expression = "domain"),
    level = DeprecationLevel.ERROR,
  )
  fun domain(): String = domain

  @JvmName("-deprecated_path")
  @Deprecated(
    message = "moved to val",
    replaceWith = ReplaceWith(expression = "path"),
    level = DeprecationLevel.ERROR,
  )
  fun path(): String = path

  @JvmName("-deprecated_httpOnly")
  @Deprecated(
    message = "moved to val",
    replaceWith = ReplaceWith(expression = "httpOnly"),
    level = DeprecationLevel.ERROR,
  )
  fun httpOnly(): Boolean = httpOnly

  @JvmName("-deprecated_secure")
  @Deprecated(
    message = "moved to val",
    replaceWith = ReplaceWith(expression = "secure"),
    level = DeprecationLevel.ERROR,
  )
  fun secure(): Boolean = secure

  /**
   * @param forObsoleteRfc2965 true to include a leading `.` on the domain pattern. This is
   *     necessary for `example.com` to match `www.example.com` under RFC 2965. This extra dot is
   *     ignored by more recent specifications.
   */
  internal fun toString(forObsoleteRfc2965: Boolean): String {
    return buildString {
      append(name)
      append('=')
      append(value)

      if (persistent) {
        if (expiresAt == Long.MIN_VALUE) {
          append("; max-age=0")
        } else {
          append("; expires=").append(Date(expiresAt).toHttpDateString())
        }
      }

      if (!hostOnly) {
        append("; domain=")
        if (forObsoleteRfc2965) {
          append(".")
        }
        append(domain)
      }

      append("; path=").append(path)

      if (secure) {
        append("; secure")
      }

      if (httpOnly) {
        append("; httponly")
      }

      if (sameSite != null) {
        append("; samesite=").append(sameSite)
      }

      if (partitioned) {
        append("; partitioned")
      }

      return toString()
    }
  }

  fun newBuilder(): Builder = Builder(this)

  /**
   * Builds a cookie. The [name], [value], and [domain] values must all be set before calling
   * [build].
   */
  class Builder() {
    private var name: String? = null
    private var value: String? = null
    private var expiresAt = MAX_DATE
    private var domain: String? = null
    private var path = "/"
    private var secure = false
    private var httpOnly = false
    private var persistent = false
    private var hostOnly = false
    private var sameSite: String? = null
    private var partitioned = false

    internal constructor(cookie: Cookie) : this() {
      this.name = cookie.name
      this.value = cookie.value
      this.expiresAt = cookie.expiresAt
      this.domain = cookie.domain
      this.path = cookie.path
      this.secure = cookie.secure
      this.httpOnly = cookie.httpOnly
      this.persistent = cookie.persistent
      this.hostOnly = cookie.hostOnly
      this.sameSite = cookie.sameSite
      this.partitioned = cookie.partitioned
    }

    fun name(name: String) =
      apply {
        require(name.trim() == name) { "name is not trimmed" }
        this.name = name
      }

    fun value(value: String) =
      apply {
        require(value.trim() == value) { "value is not trimmed" }
        this.value = value
      }

    fun expiresAt(expiresAt: Long) =
      apply {
        var expiresAt = expiresAt
        if (expiresAt <= 0L) expiresAt = Long.MIN_VALUE
        if (expiresAt > MAX_DATE) expiresAt = MAX_DATE
        this.expiresAt = expiresAt
        this.persistent = true
      }

    /**
     * Set the domain pattern for this cookie. The cookie will match [domain] and all of its
     * subdomains.
     */
    fun domain(domain: String): Builder = domain(domain, false)

    /**
     * Set the host-only domain for this cookie. The cookie will match [domain] but none of
     * its subdomains.
     */
    fun hostOnlyDomain(domain: String): Builder = domain(domain, true)

    private fun domain(
      domain: String,
      hostOnly: Boolean,
    ) = apply {
      val canonicalDomain =
        domain.toCanonicalHost()
          ?: throw IllegalArgumentException("unexpected domain: $domain")
      this.domain = canonicalDomain
      this.hostOnly = hostOnly
    }

    fun path(path: String) =
      apply {
        require(path.startsWith("/")) { "path must start with '/'" }
        this.path = path
      }

    fun secure() =
      apply {
        this.secure = true
      }

    fun httpOnly() =
      apply {
        this.httpOnly = true
      }

    fun sameSite(sameSite: String) =
      apply {
        require(sameSite.trim() == sameSite) { "sameSite is not trimmed" }
        this.sameSite = sameSite
      }

    fun partitioned() =
      apply {
        this.partitioned = true
      }

    fun build(): Cookie =
      Cookie(
        name ?: throw NullPointerException("builder.name == null"),
        value ?: throw NullPointerException("builder.value == null"),
        expiresAt,
        domain ?: throw NullPointerException("builder.domain == null"),
        path,
        secure,
        httpOnly,
        persistent,
        hostOnly,
        sameSite,
        partitioned,
      )
  }

  @Suppress("NAME_SHADOWING")
  companion object {
    private val YEAR_PATTERN = Pattern.compile("(\\d{2,4})[^\\d]*")
    private val MONTH_PATTERN =
      Pattern.compile("(?i)(jan|feb|mar|apr|may|jun|jul|aug|sep|oct|nov|dec).*")
    private val DAY_OF_MONTH_PATTERN = Pattern.compile("(\\d{1,2})[^\\d]*")
    private val TIME_PATTERN = Pattern.compile("(\\d{1,2}):(\\d{1,2}):(\\d{1,2})[^\\d]*")

    private fun domainMatch(
      urlHost: String,
      domain: String,
    ): Boolean {
      if (urlHost == domain) {
        return true // As in 'example.com' matching 'example.com'.
      }

      return urlHost.endsWith(domain) &&
        urlHost[urlHost.length - domain.length - 1] == '.' &&
        !urlHost.canParseAsIpAddress()
    }

    private fun pathMatch(
      url: HttpUrl,
      path: String,
    ): Boolean {
      val urlPath = url.encodedPath

      if (urlPath == path) {
        return true // As in '/foo' matching '/foo'.
      }

      if (urlPath.startsWith(path)) {
        if (path.endsWith("/")) return true // As in '/' matching '/foo'.
        if (urlPath[path.length] == '/') return true // As in '/foo' matching '/foo/bar'.
      }

      return false
    }

    /**
     * Attempt to parse a `Set-Cookie` HTTP header value [setCookie] as a cookie. Returns null if
     * [setCookie] is not a well-formed cookie.
     */
    @JvmStatic
    fun parse(
      url: HttpUrl,
      setCookie: String,
    ): Cookie? = parse(System.currentTimeMillis(), url, setCookie)

    internal fun parse(
      currentTimeMillis: Long,
      url: HttpUrl,
      setCookie: String,
    ): Cookie? {
      val cookiePairEnd = setCookie.delimiterOffset(';')

      val pairEqualsSign = setCookie.delimiterOffset('=', endIndex = cookiePairEnd)
      if (pairEqualsSign == cookiePairEnd) return null

      val cookieName = setCookie.trimSubstring(endIndex = pairEqualsSign)
      if (cookieName.isEmpty() || cookieName.indexOfControlOrNonAscii() != -1) return null

      val cookieValue = setCookie.trimSubstring(pairEqualsSign + 1, cookiePairEnd)
      if (cookieValue.indexOfControlOrNonAscii() != -1) return null

      var expiresAt = MAX_DATE
      var deltaSeconds = -1L
      var domain: String? = null
      var path: String? = null
      var secureOnly = false
      var httpOnly = false
      var hostOnly = true
      var persistent = false
      var sameSite: String? = null
      var partitioned = false

      var pos = cookiePairEnd + 1
      val limit = setCookie.length
      while (pos < limit) {
        val attributePairEnd = setCookie.delimiterOffset(';', pos, limit)

        val attributeEqualsSign = setCookie.delimiterOffset('=', pos, attributePairEnd)
        val attributeName = setCookie.trimSubstring(pos, attributeEqualsSign)
        val attributeValue =
          if (attributeEqualsSign < attributePairEnd) {
            setCookie.trimSubstring(attributeEqualsSign + 1, attributePairEnd)
          } else {
            ""
          }

        when {
          attributeName.equals("expires", ignoreCase = true) -> {
            try {
              expiresAt = parseExpires(attributeValue, 0, attributeValue.length)
              persistent = true
            } catch (_: IllegalArgumentException) {
              // Ignore this attribute, it isn't recognizable as a date.
            }
          }

          attributeName.equals("max-age", ignoreCase = true) -> {
            try {
              deltaSeconds = parseMaxAge(attributeValue)
              persistent = true
            } catch (_: NumberFormatException) {
              // Ignore this attribute, it isn't recognizable as a max age.
            }
          }

          attributeName.equals("domain", ignoreCase = true) -> {
            try {
              domain = parseDomain(attributeValue)
              hostOnly = false
            } catch (_: IllegalArgumentException) {
              // Ignore this attribute, it isn't recognizable as a domain.
            }
          }

          attributeName.equals("path", ignoreCase = true) -> {
            path = attributeValue
          }

          attributeName.equals("secure", ignoreCase = true) -> {
            secureOnly = true
          }

          attributeName.equals("httponly", ignoreCase = true) -> {
            httpOnly = true
          }

          attributeName.equals("samesite", ignoreCase = true) -> {
            sameSite = attributeValue
          }

          attributeName.equals("partitioned", ignoreCase = true) -> {
            partitioned = true
          }
        }

        pos = attributePairEnd + 1
      }

      // If 'Max-Age' is present, it takes precedence over 'Expires', regardless of the order the two
      // attributes are declared in the cookie string.
      if (deltaSeconds == Long.MIN_VALUE) {
        expiresAt = Long.MIN_VALUE
      } else if (deltaSeconds != -1L) {
        val deltaMilliseconds =
          if (deltaSeconds <= Long.MAX_VALUE / 1000) {
            deltaSeconds * 1000
          } else {
            Long.MAX_VALUE
          }
        expiresAt = currentTimeMillis + deltaMilliseconds
        if (expiresAt < currentTimeMillis || expiresAt > MAX_DATE) {
          expiresAt = MAX_DATE // Handle overflow & limit the date range.
        }
      }

      // If the domain is present, it must domain match. Otherwise we have a host-only cookie.
      val urlHost = url.host
      if (domain == null) {
        domain = urlHost
      } else if (!domainMatch(urlHost, domain)) {
        return null // No domain match? This is either incompetence or malice!
      }

      // If the domain is a suffix of the url host, it must not be a public suffix.
      if (urlHost.length != domain.length &&
        PublicSuffixDatabase.get().getEffectiveTldPlusOne(domain) == null
      ) {
        return null
      }

      // If the path is absent or didn't start with '/', use the default path. It's a string like
      // '/foo/bar' for a URL like 'http://example.com/foo/bar/baz'. It always starts with '/'.
      if (path == null || !path.startsWith("/")) {
        val encodedPath = url.encodedPath
        val lastSlash = encodedPath.lastIndexOf('/')
        path = if (lastSlash != 0) encodedPath.substring(0, lastSlash) else "/"
      }

      return Cookie(
        cookieName,
        cookieValue,
        expiresAt,
        domain,
        path,
        secureOnly,
        httpOnly,
        persistent,
        hostOnly,
        sameSite,
        partitioned,
      )
    }

    /** Parse a date as specified in RFC 6265, section 5.1.1. */
    private fun parseExpires(
      s: String,
      pos: Int,
      limit: Int,
    ): Long {
      var pos = pos
      pos = dateCharacterOffset(s, pos, limit, false)

      var hour = -1
      var minute = -1
      var second = -1
      var dayOfMonth = -1
      var month = -1
      var year = -1
      val matcher = TIME_PATTERN.matcher(s)

      while (pos < limit) {
        val end = dateCharacterOffset(s, pos + 1, limit, true)
        matcher.region(pos, end)

        when {
          hour == -1 && matcher.usePattern(TIME_PATTERN).matches() -> {
            hour = matcher.group(1).toInt()
            minute = matcher.group(2).toInt()
            second = matcher.group(3).toInt()
          }

          dayOfMonth == -1 && matcher.usePattern(DAY_OF_MONTH_PATTERN).matches() -> {
            dayOfMonth = matcher.group(1).toInt()
          }

          month == -1 && matcher.usePattern(MONTH_PATTERN).matches() -> {
            val monthString = matcher.group(1).lowercase(Locale.US)
            month = MONTH_PATTERN.pattern().indexOf(monthString) / 4 // Sneaky! jan=1, dec=12.
          }

          year == -1 && matcher.usePattern(YEAR_PATTERN).matches() -> {
            year = matcher.group(1).toInt()
          }
        }

        pos = dateCharacterOffset(s, end + 1, limit, false)
      }

      // Convert two-digit years into four-digit years. 99 becomes 1999, 15 becomes 2015.
      if (year in 70..99) year += 1900
      if (year in 0..69) year += 2000

      // If any partial is omitted or out of range, return -1. The date is impossible. Note that leap
      // seconds are not supported by this syntax.
      require(year >= 1601)
      require(month != -1)
      require(dayOfMonth in 1..31)
      require(hour in 0..23)
      require(minute in 0..59)
      require(second in 0..59)

      GregorianCalendar(UTC).apply {
        isLenient = false
        set(Calendar.YEAR, year)
        set(Calendar.MONTH, month - 1)
        set(Calendar.DAY_OF_MONTH, dayOfMonth)
        set(Calendar.HOUR_OF_DAY, hour)
        set(Calendar.MINUTE, minute)
        set(Calendar.SECOND, second)
        set(Calendar.MILLISECOND, 0)
        return timeInMillis
      }
    }

    /**
     * Returns the index of the next date character in `input`, or if `invert` the index
     * of the next non-date character in `input`.
     */
    private fun dateCharacterOffset(
      input: String,
      pos: Int,
      limit: Int,
      invert: Boolean,
    ): Int {
      for (i in pos until limit) {
        val c = input[i].code
        val dateCharacter =
          (
            (
              ((c < ' '.code) && (c != '\t'.code)) || (c >= '\u007f'.code) || (c in ('0'.code..'9'.code)) || (c in ('a'.code..'z'.code)) ||
                (c in ('A'.code..'Z'.code)) ||
                (c == ':'.code)
            )
          )
        if (dateCharacter == !invert) return i
      }
      return limit
    }

    /**
     * Returns the positive value if [s] is positive, or [Long.MIN_VALUE] if it is either 0 or
     * negative. If the value is positive but out of range, this returns [Long.MAX_VALUE].
     *
     * @throws NumberFormatException if [s] is not an integer of any precision.
     */
    private fun parseMaxAge(s: String): Long {
      try {
        val parsed = s.toLong()
        return if (parsed <= 0L) Long.MIN_VALUE else parsed
      } catch (e: NumberFormatException) {
        // Check if the value is an integer (positive or negative) that's too big for a long.
        if (s.matches("-?\\d+".toRegex())) {
          return if (s.startsWith("-")) Long.MIN_VALUE else Long.MAX_VALUE
        }
        throw e
      }
    }

    /**
     * Returns a domain string like `example.com` for an input domain like `EXAMPLE.COM`
     * or `.example.com`.
     */
    private fun parseDomain(s: String): String {
      require(!s.endsWith("."))
      return s.removePrefix(".").toCanonicalHost() ?: throw IllegalArgumentException()
    }

    /** Returns all of the cookies from a set of HTTP response headers. */
    @JvmStatic
    fun parseAll(
      url: HttpUrl,
      headers: Headers,
    ): List<Cookie> {
      val cookieStrings = headers.values("Set-Cookie")
      var cookies: MutableList<Cookie>? = null

      for (i in 0 until cookieStrings.size) {
        val cookie = parse(url, cookieStrings[i]) ?: continue
        if (cookies == null) cookies = mutableListOf()
        cookies.add(cookie)
      }

      return cookies?.unmodifiable().orEmpty()
    }
  }
}
