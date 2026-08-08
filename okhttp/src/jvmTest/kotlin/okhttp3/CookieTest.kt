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

import app.cash.burst.Burst
import app.cash.burst.burstValues
import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isFalse
import assertk.assertions.isNotEqualTo
import assertk.assertions.isNotNull
import assertk.assertions.isNull
import assertk.assertions.isTrue
import java.text.ParseException
import java.text.SimpleDateFormat
import java.util.Arrays
import java.util.Date
import kotlin.test.assertFailsWith
import okhttp3.Cookie.Companion.parse
import okhttp3.Cookie.Companion.parseAll
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.internal.UTC
import okhttp3.internal.http.MAX_DATE
import okhttp3.internal.parseCookie
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

@Burst
class CookieTest {
  val url = "https://example.com/".toHttpUrl()

  @Test fun simpleCookie() {
    val cookie = parse(url, "SID=31d4d96e407aad42")
    assertThat(cookie.toString()).isEqualTo("SID=31d4d96e407aad42; path=/")
  }

  @Test fun noEqualsSign() {
    assertThat(parse(url, "foo")).isNull()
    assertThat(parse(url, "foo; Path=/")).isNull()
  }

  @Test fun emptyName() {
    assertThat(parse(url, "=b")).isNull()
    assertThat(parse(url, " =b")).isNull()
    assertThat(parse(url, "\r\t \n=b")).isNull()
  }

  @Test fun spaceInName() {
    assertThat(parse(url, "a b=cd")!!.name).isEqualTo("a b")
  }

  @Test fun spaceInValue() {
    assertThat(parse(url, "ab=c d")!!.value).isEqualTo("c d")
  }

  @Test fun trimLeadingAndTrailingWhitespaceFromName() {
    assertThat(parse(url, " a=b")!!.name).isEqualTo("a")
    assertThat(parse(url, "a =b")!!.name).isEqualTo("a")
    assertThat(parse(url, "\r\t \na\n\t \n=b")!!.name).isEqualTo("a")
  }

  @Test fun emptyValue() {
    assertThat(parse(url, "a=")!!.value).isEqualTo("")
    assertThat(parse(url, "a= ")!!.value).isEqualTo("")
    assertThat(parse(url, "a=\r\t \n")!!.value).isEqualTo("")
  }

  @Test fun trimLeadingAndTrailingWhitespaceFromValue() {
    assertThat(parse(url, "a= ")!!.value).isEqualTo("")
    assertThat(parse(url, "a= b")!!.value).isEqualTo("b")
    assertThat(parse(url, "a=b ")!!.value).isEqualTo("b")
    assertThat(parse(url, "a=\r\t \nb\n\t \n")!!.value).isEqualTo("b")
  }

  @Test fun invalidCharacters() {
    assertThat(parse(url, "a\u0000b=cd")).isNull()
    assertThat(parse(url, "ab=c\u0000d")).isNull()
    assertThat(parse(url, "a\u0001b=cd")).isNull()
    assertThat(parse(url, "ab=c\u0001d")).isNull()
    assertThat(parse(url, "a\u0009b=cd")).isNull()
    assertThat(parse(url, "ab=c\u0009d")).isNull()
    assertThat(parse(url, "a\u001fb=cd")).isNull()
    assertThat(parse(url, "ab=c\u001fd")).isNull()
    assertThat(parse(url, "a\u007fb=cd")).isNull()
    assertThat(parse(url, "ab=c\u007fd")).isNull()
    assertThat(parse(url, "a\u0080b=cd")).isNull()
    assertThat(parse(url, "ab=c\u0080d")).isNull()
    assertThat(parse(url, "a\u00ffb=cd")).isNull()
    assertThat(parse(url, "ab=c\u00ffd")).isNull()
  }

  @Test fun maxAge() {
    assertThat(parseCookie(50000L, url, "a=b; Max-Age=1")!!.expiresAt).isEqualTo(51000L)
    assertThat(parseCookie(50000L, url, "a=b; Max-Age=9223372036854724")!!.expiresAt)
      .isEqualTo(MAX_DATE)
    assertThat(parseCookie(50000L, url, "a=b; Max-Age=9223372036854725")!!.expiresAt)
      .isEqualTo(MAX_DATE)
    assertThat(parseCookie(50000L, url, "a=b; Max-Age=9223372036854726")!!.expiresAt)
      .isEqualTo(MAX_DATE)
    assertThat(parseCookie(9223372036854773807L, url, "a=b; Max-Age=1")!!.expiresAt)
      .isEqualTo(MAX_DATE)
    assertThat(parseCookie(9223372036854773807L, url, "a=b; Max-Age=2")!!.expiresAt)
      .isEqualTo(MAX_DATE)
    assertThat(parseCookie(9223372036854773807L, url, "a=b; Max-Age=3")!!.expiresAt)
      .isEqualTo(MAX_DATE)
    assertThat(parseCookie(50000L, url, "a=b; Max-Age=10000000000000000000")!!.expiresAt)
      .isEqualTo(MAX_DATE)
  }

  @Test fun maxAgeNonPositive() {
    assertThat(parseCookie(50000L, url, "a=b; Max-Age=-1")!!.expiresAt)
      .isEqualTo(Long.MIN_VALUE)
    assertThat(parseCookie(50000L, url, "a=b; Max-Age=0")!!.expiresAt)
      .isEqualTo(Long.MIN_VALUE)
    assertThat(parseCookie(50000L, url, "a=b; Max-Age=-9223372036854775808")!!.expiresAt)
      .isEqualTo(Long.MIN_VALUE)
    assertThat(parseCookie(50000L, url, "a=b; Max-Age=-9223372036854775809")!!.expiresAt)
      .isEqualTo(Long.MIN_VALUE)
    assertThat(parseCookie(50000L, url, "a=b; Max-Age=-10000000000000000000")!!.expiresAt)
      .isEqualTo(Long.MIN_VALUE)
  }

  @Test fun domainAndPath() {
    val cookie = parse(url, "SID=31d4d96e407aad42; Path=/; Domain=example.com")
    assertThat(cookie!!.domain).isEqualTo("example.com")
    assertThat(cookie.path).isEqualTo("/")
    assertThat(cookie.hostOnly).isFalse()
    assertThat(cookie.toString()).isEqualTo("SID=31d4d96e407aad42; domain=example.com; path=/")
  }

  @Test fun secureAndHttpOnly() {
    val cookie = parse(url, "SID=31d4d96e407aad42; Path=/; Secure; HttpOnly")
    assertThat(cookie!!.secure).isTrue()
    assertThat(cookie.httpOnly).isTrue()
    assertThat(cookie.toString()).isEqualTo("SID=31d4d96e407aad42; path=/; secure; httponly")
  }

  @Test fun expiresDate() {
    assertThat(Date(parse(url, "a=b; Expires=Thu, 01 Jan 1970 00:00:00 GMT")!!.expiresAt))
      .isEqualTo(date("1970-01-01T00:00:00.000+0000"))
    assertThat(Date(parse(url, "a=b; Expires=Wed, 09 Jun 2021 10:18:14 GMT")!!.expiresAt))
      .isEqualTo(date("2021-06-09T10:18:14.000+0000"))
    assertThat(Date(parse(url, "a=b; Expires=Sun, 06 Nov 1994 08:49:37 GMT")!!.expiresAt))
      .isEqualTo(date("1994-11-06T08:49:37.000+0000"))
  }

  @Test fun awkwardDates() {
    assertThat(parse(url, "a=b; Expires=Thu, 01 Jan 70 00:00:00 GMT")!!.expiresAt)
      .isEqualTo(0L)
    assertThat(parse(url, "a=b; Expires=Thu, 01 January 1970 00:00:00 GMT")!!.expiresAt)
      .isEqualTo(0L)
    assertThat(parse(url, "a=b; Expires=Thu, 01 Janucember 1970 00:00:00 GMT")!!.expiresAt)
      .isEqualTo(0L)
    assertThat(parse(url, "a=b; Expires=Thu, 1 Jan 1970 00:00:00 GMT")!!.expiresAt)
      .isEqualTo(0L)
    assertThat(parse(url, "a=b; Expires=Thu, 01 Jan 1970 0:00:00 GMT")!!.expiresAt)
      .isEqualTo(0L)
    assertThat(parse(url, "a=b; Expires=Thu, 01 Jan 1970 00:0:00 GMT")!!.expiresAt)
      .isEqualTo(0L)
    assertThat(parse(url, "a=b; Expires=Thu, 01 Jan 1970 00:00:0 GMT")!!.expiresAt)
      .isEqualTo(0L)
    assertThat(parse(url, "a=b; Expires=00:00:00 Thu, 01 Jan 1970 GMT")!!.expiresAt)
      .isEqualTo(0L)
    assertThat(parse(url, "a=b; Expires=00:00:00 1970 Jan 01")!!.expiresAt)
      .isEqualTo(0L)
    assertThat(parse(url, "a=b; Expires=00:00:00 1970 Jan 1")!!.expiresAt)
      .isEqualTo(0L)
  }

  @Test fun invalidYear() {
    assertThat(parse(url, "a=b; Expires=Thu, 01 Jan 1600 00:00:00 GMT")!!.expiresAt)
      .isEqualTo(MAX_DATE)
    assertThat(parse(url, "a=b; Expires=Thu, 01 Jan 19999 00:00:00 GMT")!!.expiresAt)
      .isEqualTo(MAX_DATE)
    assertThat(parse(url, "a=b; Expires=Thu, 01 Jan 00:00:00 GMT")!!.expiresAt)
      .isEqualTo(MAX_DATE)
  }

  @Test fun invalidMonth() {
    assertThat(parse(url, "a=b; Expires=Thu, 01 Foo 1970 00:00:00 GMT")!!.expiresAt)
      .isEqualTo(MAX_DATE)
    assertThat(parse(url, "a=b; Expires=Thu, 01 Foocember 1970 00:00:00 GMT")!!.expiresAt)
      .isEqualTo(MAX_DATE)
    assertThat(parse(url, "a=b; Expires=Thu, 01 1970 00:00:00 GMT")!!.expiresAt)
      .isEqualTo(MAX_DATE)
  }

  @Test fun invalidDayOfMonth() {
    assertThat(parse(url, "a=b; Expires=Thu, 32 Jan 1970 00:00:00 GMT")!!.expiresAt)
      .isEqualTo(MAX_DATE)
    assertThat(parse(url, "a=b; Expires=Thu, Jan 1970 00:00:00 GMT")!!.expiresAt)
      .isEqualTo(MAX_DATE)
  }

  @Test fun invalidHour() {
    assertThat(parse(url, "a=b; Expires=Thu, 01 Jan 1970 24:00:00 GMT")!!.expiresAt)
      .isEqualTo(MAX_DATE)
  }

  @Test fun invalidMinute() {
    assertThat(parse(url, "a=b; Expires=Thu, 01 Jan 1970 00:60:00 GMT")!!.expiresAt)
      .isEqualTo(MAX_DATE)
  }

  @Test fun invalidSecond() {
    assertThat(parse(url, "a=b; Expires=Thu, 01 Jan 1970 00:00:60 GMT")!!.expiresAt)
      .isEqualTo(MAX_DATE)
  }

  @Test fun domainMatches() {
    val cookie = parse(url, "a=b; domain=example.com")
    assertThat(cookie!!.matches("http://example.com".toHttpUrl())).isTrue()
    assertThat(cookie.matches("http://www.example.com".toHttpUrl())).isTrue()
    assertThat(cookie.matches("http://square.com".toHttpUrl())).isFalse()
  }

  /** If no domain is present, match only the origin domain.  */
  @Test fun domainMatchesNoDomain() {
    val cookie = parse(url, "a=b")
    assertThat(cookie!!.matches("http://example.com".toHttpUrl())).isTrue()
    assertThat(cookie.matches("http://www.example.com".toHttpUrl())).isFalse()
    assertThat(cookie.matches("http://square.com".toHttpUrl())).isFalse()
  }

  /** Ignore an optional leading `.` in the domain.  */
  @Test fun domainMatchesIgnoresLeadingDot() {
    val cookie = parse(url, "a=b; domain=.example.com")
    assertThat(cookie!!.matches("http://example.com".toHttpUrl())).isTrue()
    assertThat(cookie.matches("http://www.example.com".toHttpUrl())).isTrue()
    assertThat(cookie.matches("http://square.com".toHttpUrl())).isFalse()
  }

  /** Ignore the entire attribute if the domain ends with `.`.  */
  @Test fun domainIgnoredWithTrailingDot() {
    val cookie = parse(url, "a=b; domain=example.com.")
    assertThat(cookie!!.matches("http://example.com".toHttpUrl())).isTrue()
    assertThat(cookie.matches("http://www.example.com".toHttpUrl())).isFalse()
    assertThat(cookie.matches("http://square.com".toHttpUrl())).isFalse()
  }

  @Test fun idnDomainMatches() {
    val cookie = parse("http://☃.net/".toHttpUrl(), "a=b; domain=☃.net")
    assertThat(cookie!!.matches("http://☃.net/".toHttpUrl())).isTrue()
    assertThat(cookie.matches("http://xn--n3h.net/".toHttpUrl())).isTrue()
    assertThat(cookie.matches("http://www.☃.net/".toHttpUrl())).isTrue()
    assertThat(cookie.matches("http://www.xn--n3h.net/".toHttpUrl())).isTrue()
  }

  @Test fun punycodeDomainMatches() {
    val cookie = parse("http://xn--n3h.net/".toHttpUrl(), "a=b; domain=xn--n3h.net")
    assertThat(cookie!!.matches("http://☃.net/".toHttpUrl())).isTrue()
    assertThat(cookie.matches("http://xn--n3h.net/".toHttpUrl())).isTrue()
    assertThat(cookie.matches("http://www.☃.net/".toHttpUrl())).isTrue()
    assertThat(cookie.matches("http://www.xn--n3h.net/".toHttpUrl())).isTrue()
  }

  @Test fun domainMatchesIpAddress() {
    val urlWithIp = "http://123.45.234.56/".toHttpUrl()
    assertThat(parse(urlWithIp, "a=b; domain=234.56")).isNull()
    assertThat(parse(urlWithIp, "a=b; domain=123.45.234.56")!!.domain).isEqualTo("123.45.234.56")
  }

  @Test fun domainMatchesIpv6Address() {
    val cookie = parse("http://[::1]/".toHttpUrl(), "a=b; domain=::1")
    assertThat(cookie!!.domain).isEqualTo("::1")
    assertThat(cookie.matches("http://[::1]/".toHttpUrl())).isTrue()
  }

  @Test fun domainMatchesIpv6AddressWithCompression() {
    val cookie = parse("http://[0001:0000::]/".toHttpUrl(), "a=b; domain=0001:0000::")
    assertThat(cookie!!.domain).isEqualTo("1::")
    assertThat(cookie.matches("http://[1::]/".toHttpUrl())).isTrue()
  }

  @Test fun domainMatchesIpv6AddressWithIpv4Suffix() {
    val cookie =
      parse(
        "http://[::1:ffff:ffff]/".toHttpUrl(),
        "a=b; domain=::1:255.255.255.255",
      )
    assertThat(cookie!!.domain).isEqualTo("::1:ffff:ffff")
    assertThat(cookie.matches("http://[::1:ffff:ffff]/".toHttpUrl())).isTrue()
  }

  @Test fun ipv6AddressDoesntMatch() {
    val cookie = parse("http://[::1]/".toHttpUrl(), "a=b; domain=::2")
    assertThat(cookie).isNull()
  }

  @Test fun ipv6AddressMalformed() {
    val cookie = parse("http://[::1]/".toHttpUrl(), "a=b; domain=::2::2")
    assertThat(cookie!!.domain).isEqualTo("::1")
  }

  /**
   * These public suffixes were selected by inspecting the publicsuffix.org list. It's possible they
   * may change in the future. If this test begins to fail, please double check they are still
   * present in the public suffix list.
   */
  @Test fun domainIsPublicSuffix() {
    val ascii = "https://foo1.foo.bar.elb.amazonaws.com".toHttpUrl()
    assertThat(parse(ascii, "a=b; domain=foo.bar.elb.amazonaws.com")).isNotNull()
    assertThat(parse(ascii, "a=b; domain=bar.elb.amazonaws.com")).isNull()
    assertThat(parse(ascii, "a=b; domain=com")).isNull()
    val unicode = "https://長.長.長崎.jp".toHttpUrl()
    assertThat(parse(unicode, "a=b; domain=長.長崎.jp")).isNotNull()
    assertThat(parse(unicode, "a=b; domain=長崎.jp")).isNull()
    val punycode = "https://xn--ue5a.xn--ue5a.xn--8ltr62k.jp".toHttpUrl()
    assertThat(parse(punycode, "a=b; domain=xn--ue5a.xn--8ltr62k.jp")).isNotNull()
    assertThat(parse(punycode, "a=b; domain=xn--8ltr62k.jp")).isNull()
  }

  @Test fun hostOnly() {
    assertThat(parse(url, "a=b")!!.hostOnly).isTrue()
    assertThat(
      parse(url, "a=b; domain=example.com")!!.hostOnly,
    ).isFalse()
  }

  @Test fun defaultPath() {
    assertThat(parse("http://example.com/foo/bar".toHttpUrl(), "a=b")!!.path).isEqualTo("/foo")
    assertThat(parse("http://example.com/foo/".toHttpUrl(), "a=b")!!.path).isEqualTo("/foo")
    assertThat(parse("http://example.com/foo".toHttpUrl(), "a=b")!!.path).isEqualTo("/")
    assertThat(parse("http://example.com/".toHttpUrl(), "a=b")!!.path).isEqualTo("/")
  }

  @Test fun defaultPathIsUsedIfPathDoesntHaveLeadingSlash() {
    assertThat(
      parse("http://example.com/foo/bar".toHttpUrl(), "a=b; path=quux")!!.path,
    ).isEqualTo("/foo")
    assertThat(parse("http://example.com/foo/bar".toHttpUrl(), "a=b; path=")!!.path)
      .isEqualTo("/foo")
  }

  @Test fun pathAttributeDoesntNeedToMatch() {
    assertThat(parse("http://example.com/".toHttpUrl(), "a=b; path=/quux")!!.path)
      .isEqualTo("/quux")
    assertThat(parse("http://example.com/foo/bar".toHttpUrl(), "a=b; path=/quux")!!.path)
      .isEqualTo("/quux")
  }

  @Test fun httpOnly() {
    assertThat(parse(url, "a=b")!!.httpOnly).isFalse()
    assertThat(parse(url, "a=b; HttpOnly")!!.httpOnly).isTrue()
  }

  @Test fun secure() {
    assertThat(parse(url, "a=b")!!.secure).isFalse()
    assertThat(parse(url, "a=b; Secure")!!.secure).isTrue()
  }

  @Test fun partitioned() {
    assertThat(parse(url, "a=b")!!.partitioned).isFalse()
    assertThat(parse(url, "a=b; Partitioned")!!.partitioned).isTrue()
  }

  @Test fun securePartitioned() {
    val cookie = parse(url, "a=b; Secure; SameSite=None; Partitioned")
    assertThat(cookie!!.secure).isTrue()
    assertThat(cookie.partitioned).isTrue()
    assertThat(cookie.toString()).isEqualTo("a=b; path=/; secure; samesite=None; partitioned")
  }

  @Test fun maxAgeTakesPrecedenceOverExpires() {
    // Max-Age = 1, Expires = 2. In either order.
    assertThat(parseCookie(0L, url, "a=b; Max-Age=1; Expires=Thu, 01 Jan 1970 00:00:02 GMT")!!.expiresAt)
      .isEqualTo(1000L)
    assertThat(parseCookie(0L, url, "a=b; Expires=Thu, 01 Jan 1970 00:00:02 GMT; Max-Age=1")!!.expiresAt)
      .isEqualTo(1000L)
    // Max-Age = 2, Expires = 1. In either order.
    assertThat(parseCookie(0L, url, "a=b; Max-Age=2; Expires=Thu, 01 Jan 1970 00:00:01 GMT")!!.expiresAt)
      .isEqualTo(2000L)
    assertThat(parseCookie(0L, url, "a=b; Expires=Thu, 01 Jan 1970 00:00:01 GMT; Max-Age=2")!!.expiresAt)
      .isEqualTo(2000L)
  }

  /** If a cookie incorrectly defines multiple 'Max-Age' attributes, the last one defined wins.  */
  @Test fun lastMaxAgeWins() {
    assertThat(parseCookie(0L, url, "a=b; Max-Age=2; Max-Age=4; Max-Age=1; Max-Age=3")!!.expiresAt)
      .isEqualTo(3000L)
  }

  /** If a cookie incorrectly defines multiple 'Expires' attributes, the last one defined wins.  */
  @Test fun lastExpiresAtWins() {
    assertThat(
      parseCookie(
        0L,
        url,
        "a=b; " +
          "Expires=Thu, 01 Jan 1970 00:00:02 GMT; " +
          "Expires=Thu, 01 Jan 1970 00:00:04 GMT; " +
          "Expires=Thu, 01 Jan 1970 00:00:01 GMT; " +
          "Expires=Thu, 01 Jan 1970 00:00:03 GMT",
      )!!.expiresAt,
    ).isEqualTo(3000L)
  }

  @Test fun maxAgeOrExpiresMakesCookiePersistent() {
    assertThat(parseCookie(0L, url, "a=b")!!.persistent).isFalse()
    assertThat(parseCookie(0L, url, "a=b; Max-Age=1")!!.persistent).isTrue()
    assertThat(parseCookie(0L, url, "a=b; Expires=Thu, 01 Jan 1970 00:00:01 GMT")!!.persistent)
      .isTrue()
  }

  @Test fun parseAll() {
    val headers =
      Headers
        .Builder()
        .add("Set-Cookie: a=b")
        .add("Set-Cookie: c=d")
        .build()
    val cookies = parseAll(url, headers)
    assertThat(cookies.size).isEqualTo(2)
    assertThat(cookies[0].toString()).isEqualTo("a=b; path=/")
    assertThat(cookies[1].toString()).isEqualTo("c=d; path=/")
  }

  @Test fun builder() {
    val cookie =
      Cookie
        .Builder()
        .name("a")
        .value("b")
        .domain("example.com")
        .build()
    assertThat(cookie.name).isEqualTo("a")
    assertThat(cookie.value).isEqualTo("b")
    assertThat(cookie.expiresAt).isEqualTo(MAX_DATE)
    assertThat(cookie.domain).isEqualTo("example.com")
    assertThat(cookie.path).isEqualTo("/")
    assertThat(cookie.secure).isFalse()
    assertThat(cookie.httpOnly).isFalse()
    assertThat(cookie.persistent).isFalse()
    assertThat(cookie.hostOnly).isFalse()
    assertThat(cookie.sameSite).isNull()
    assertThat(cookie.partitioned).isFalse()
  }

  @Test fun newBuilder() {
    val cookie =
      parseCookie(0L, url, "c=d; Max-Age=1")!!
        .newBuilder()
        .name("a")
        .value("b")
        .domain("example.com")
        .expiresAt(MAX_DATE)
        .build()
    assertThat(cookie.name).isEqualTo("a")
    assertThat(cookie.value).isEqualTo("b")
    assertThat(cookie.expiresAt).isEqualTo(MAX_DATE)
    assertThat(cookie.domain).isEqualTo("example.com")
    assertThat(cookie.path).isEqualTo("/")
    assertThat(cookie.secure).isFalse()
    assertThat(cookie.httpOnly).isFalse()
    // can't be unset
    assertThat(cookie.persistent).isTrue()
    assertThat(cookie.hostOnly).isFalse()
  }

  @Test fun builderNameValidation() {
    assertFailsWith<IllegalArgumentException> {
      Cookie.Builder().name(" a ")
    }
  }

  @Test fun builderValueValidation() {
    assertFailsWith<IllegalArgumentException> {
      Cookie.Builder().value(" b ")
    }
  }

  @Test fun builderClampsMaxDate() {
    val cookie =
      Cookie
        .Builder()
        .name("a")
        .value("b")
        .hostOnlyDomain("example.com")
        .expiresAt(Long.MAX_VALUE)
        .build()
    assertThat(cookie.toString()).isEqualTo("a=b; expires=Fri, 31 Dec 9999 23:59:59 GMT; path=/")
  }

  @Test fun builderExpiresAt() {
    val cookie =
      Cookie
        .Builder()
        .name("a")
        .value("b")
        .hostOnlyDomain("example.com")
        .expiresAt(date("1970-01-01T00:00:01.000+0000").time)
        .build()
    assertThat(cookie.toString()).isEqualTo("a=b; expires=Thu, 01 Jan 1970 00:00:01 GMT; path=/")
  }

  @Test fun builderClampsMinDate() {
    val cookie =
      Cookie
        .Builder()
        .name("a")
        .value("b")
        .hostOnlyDomain("example.com")
        .expiresAt(date("1970-01-01T00:00:00.000+0000").time)
        .build()
    assertThat(cookie.toString()).isEqualTo("a=b; max-age=0; path=/")
  }

  @Test fun builderDomainValidation() {
    assertFailsWith<IllegalArgumentException> {
      Cookie.Builder().hostOnlyDomain("a/b")
    }
  }

  @Test fun builderDomain() {
    val cookie =
      Cookie
        .Builder()
        .name("a")
        .value("b")
        .hostOnlyDomain("squareup.com")
        .build()
    assertThat(cookie.domain).isEqualTo("squareup.com")
    assertThat(cookie.hostOnly).isTrue()
  }

  @Test fun builderPath() {
    val cookie =
      Cookie
        .Builder()
        .name("a")
        .value("b")
        .hostOnlyDomain("example.com")
        .path("/foo")
        .build()
    assertThat(cookie.path).isEqualTo("/foo")
  }

  @Test fun builderPathValidation() {
    assertFailsWith<IllegalArgumentException> {
      Cookie.Builder().path("foo")
    }
  }

  @Test fun builderSecure() {
    val cookie =
      Cookie
        .Builder()
        .name("a")
        .value("b")
        .hostOnlyDomain("example.com")
        .secure()
        .build()
    assertThat(cookie.secure).isTrue()
  }

  @Test fun builderHttpOnly() {
    val cookie =
      Cookie
        .Builder()
        .name("a")
        .value("b")
        .hostOnlyDomain("example.com")
        .httpOnly()
        .build()
    assertThat(cookie.httpOnly).isTrue()
  }

  @Test fun builderPartitioned() {
    val cookie =
      Cookie
        .Builder()
        .name("a")
        .value("b")
        .hostOnlyDomain("example.com")
        .partitioned()
        .build()
    assertThat(cookie.partitioned).isTrue()
  }

  @Test fun builderIpv6() {
    val cookie =
      Cookie
        .Builder()
        .name("a")
        .value("b")
        .domain("0:0:0:0:0:0:0:1")
        .build()
    assertThat(cookie.domain).isEqualTo("::1")
  }

  @Test fun emptySameSite() {
    assertThat(parse(url, "a=b; SameSite=")!!.sameSite).isEqualTo("")
    assertThat(parse(url, "a=b; SameSite= ")!!.sameSite).isEqualTo("")
    assertThat(parse(url, "a=b; SameSite=\r\t \n")!!.sameSite).isEqualTo("")
  }

  @Test fun spaceInSameSite() {
    assertThat(parse(url, "a=b; SameSite=a b")!!.sameSite).isEqualTo("a b")
  }

  @Test fun trimLeadingAndTrailingWhitespaceFromSameSite() {
    assertThat(parse(url, "a=b; SameSite= ")!!.sameSite).isEqualTo("")
    assertThat(parse(url, "a= b; SameSite= Lax")!!.sameSite).isEqualTo("Lax")
    assertThat(parse(url, "a=b ; SameSite=Lax ;")!!.sameSite).isEqualTo("Lax")
    assertThat(parse(url, "a=\r\t \nb\n; \rSameSite=\n \tLax")!!.sameSite).isEqualTo("Lax")
  }

  @Test fun builderSameSiteTrimmed() {
    var cookieBuilder =
      Cookie
        .Builder()
        .name("a")
        .value("b")
        .domain("example.com")

    assertThrows<IllegalArgumentException> {
      cookieBuilder.sameSite(" a").build()
    }
    assertThrows<IllegalArgumentException> {
      cookieBuilder.sameSite("a ").build()
    }
    assertThrows<IllegalArgumentException> {
      cookieBuilder.sameSite(" a ").build()
    }

    cookieBuilder.sameSite("a").build()
  }

  @Test
  fun builderSameSite(sameSite: String = burstValues("Lax", "Strict", "UnrecognizedButValid")) {
    val cookie =
      Cookie
        .Builder()
        .name("a")
        .value("b")
        .domain("example.com")
        .sameSite(sameSite)
        .build()
    assertThat(cookie.sameSite).isEqualTo(sameSite)
  }

  /** Note that we permit building a cookie that doesn’t follow the rules. */
  @Test fun builderSameSiteNoneDoesNotRequireSecure() {
    val cookieBuilder =
      Cookie
        .Builder()
        .name("a")
        .value("b")
        .domain("example.com")
        .sameSite("None")

    val cookie = cookieBuilder.build()
    assertThat(cookie.sameSite).isEqualTo("None")
  }

  @Test fun equalsAndHashCode() {
    val cookieStrings =
      Arrays.asList(
        "a=b; Path=/c; Domain=example.com; Max-Age=5; Secure; HttpOnly",
        "a= ; Path=/c; Domain=example.com; Max-Age=5; Secure; HttpOnly",
        "a=b;          Domain=example.com; Max-Age=5; Secure; HttpOnly",
        "a=b; Path=/c;                     Max-Age=5; Secure; HttpOnly",
        "a=b; Path=/c; Domain=example.com;            Secure; HttpOnly",
        "a=b; Path=/c; Domain=example.com; Max-Age=5;         HttpOnly",
        "a=b; Path=/c; Domain=example.com; Max-Age=5; Secure;         ",
        "a=b; Path=/c; Domain=example.com; Max-Age=5; Secure; HttpOnly; SameSite=Lax",
        "a= ; Path=/c; Domain=example.com; Max-Age=5; Secure; HttpOnly; SameSite=Lax",
        "a=b;          Domain=example.com; Max-Age=5; Secure; HttpOnly; SameSite=Lax",
        "a=b; Path=/c;                     Max-Age=5; Secure; HttpOnly; SameSite=Lax",
        "a=b; Path=/c; Domain=example.com;            Secure; HttpOnly; SameSite=Lax",
        "a=b; Path=/c; Domain=example.com; Max-Age=5;         HttpOnly; SameSite=Lax",
        "a=b; Path=/c; Domain=example.com; Max-Age=5; Secure;         ; SameSite=Lax",
        "a=b; Path=/c; Domain=example.com; Max-Age=5; Secure; HttpOnly; Partitioned",
        "a=b; Path=/c; Domain=example.com; Max-Age=5; Secure; HttpOnly; SameSite=None; Partitioned",
      )
    for (stringA in cookieStrings) {
      val cookieA = parseCookie(0, url, stringA!!)
      for (stringB in cookieStrings) {
        val cookieB = parseCookie(0, url, stringB!!)
        if (stringA == stringB) {
          assertThat(cookieB.hashCode()).isEqualTo(cookieA.hashCode())
          assertThat(cookieB).isEqualTo(cookieA)
        } else {
          assertThat(cookieB.hashCode()).isNotEqualTo(cookieA.hashCode().toLong())
          assertThat(cookieB).isNotEqualTo(cookieA)
        }
      }
      assertThat(cookieA).isNotEqualTo(null)
    }
  }

  @Throws(ParseException::class)
  private fun date(s: String): Date {
    val format = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSZ")
    format.timeZone = UTC
    return format.parse(s)
  }
}
