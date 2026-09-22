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

import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isNull
import java.net.URI
import java.net.URL
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.testing.PlatformRule
import org.junit.jupiter.api.Test

@Suppress("HttpUrlsUsage") // Don't warn if we should be using https://.
open class HttpUrlJvmTest {
  val platform = PlatformRule()

  /** This one's ugly: the HttpUrl's host is non-empty, but the URI's host is null. */
  @Test
  fun hostContainsOnlyStrippedCharacters() {
    val url = "http://>/".toHttpUrl()
    assertThat(url.host).isEqualTo(">")
    assertThat(url.toUri().host).isNull()
  }

  /**
   * Strip unexpected characters when converting to URI (which is more strict).
   * https://github.com/lysine-dev/okhttp/issues/5667
   */
  @Test
  fun hostToUriStripsCharacters() {
    val httpUrl = "http://example\".com/".toHttpUrl()
    assertThat(httpUrl.toUri().toString()).isEqualTo("http://example.com/")
  }

  /** Confirm that URI retains other characters. https://github.com/lysine-dev/okhttp/issues/5236 */
  @Test
  fun hostToUriStripsCharacters2() {
    val httpUrl = "http://\${tracker}/".toHttpUrl()
    assertThat(httpUrl.toUri().toString()).isEqualTo("http://\$tracker/")
  }

  @Test
  fun fragmentNonAsciiThatOffendsJavaNetUri() {
    val url = "http://host/#\u0080".toHttpUrl()
    assertThat(url.toString()).isEqualTo("http://host/#\u0080")
    assertThat(url.fragment).isEqualTo("\u0080")
    assertThat(url.encodedFragment).isEqualTo("\u0080")
    // Control characters may be stripped!
    assertThat(url.toUri()).isEqualTo(URI("http://host/#"))
  }

  @Test
  fun toUriWithControlCharacters() {
    // Percent-encoded in the path.
    assertThat("http://host/a\u0000b".toHttpUrl().toUri())
      .isEqualTo(URI("http://host/a%00b"))
    assertThat("http://host/a\u0080b".toHttpUrl().toUri())
      .isEqualTo(URI("http://host/a%C2%80b"))
    assertThat("http://host/a\u009fb".toHttpUrl().toUri())
      .isEqualTo(URI("http://host/a%C2%9Fb"))
    // Percent-encoded in the query.
    assertThat("http://host/?a\u0000b".toHttpUrl().toUri())
      .isEqualTo(URI("http://host/?a%00b"))
    assertThat("http://host/?a\u0080b".toHttpUrl().toUri())
      .isEqualTo(URI("http://host/?a%C2%80b"))
    assertThat("http://host/?a\u009fb".toHttpUrl().toUri())
      .isEqualTo(URI("http://host/?a%C2%9Fb"))
    // Stripped from the fragment.
    assertThat("http://host/#a\u0000b".toHttpUrl().toUri())
      .isEqualTo(URI("http://host/#a%00b"))
    assertThat("http://host/#a\u0080b".toHttpUrl().toUri())
      .isEqualTo(URI("http://host/#ab"))
    assertThat("http://host/#a\u009fb".toHttpUrl().toUri())
      .isEqualTo(URI("http://host/#ab"))
  }

  @Test
  fun toUriWithSpaceCharacters() {
    // Percent-encoded in the path.
    assertThat("http://host/a\u000bb".toHttpUrl().toUri())
      .isEqualTo(URI("http://host/a%0Bb"))
    assertThat("http://host/a b".toHttpUrl().toUri())
      .isEqualTo(URI("http://host/a%20b"))
    assertThat("http://host/a\u2009b".toHttpUrl().toUri())
      .isEqualTo(URI("http://host/a%E2%80%89b"))
    assertThat("http://host/a\u3000b".toHttpUrl().toUri())
      .isEqualTo(URI("http://host/a%E3%80%80b"))
    // Percent-encoded in the query.
    assertThat("http://host/?a\u000bb".toHttpUrl().toUri())
      .isEqualTo(URI("http://host/?a%0Bb"))
    assertThat("http://host/?a b".toHttpUrl().toUri())
      .isEqualTo(URI("http://host/?a%20b"))
    assertThat("http://host/?a\u2009b".toHttpUrl().toUri())
      .isEqualTo(URI("http://host/?a%E2%80%89b"))
    assertThat("http://host/?a\u3000b".toHttpUrl().toUri())
      .isEqualTo(URI("http://host/?a%E3%80%80b"))
    // Stripped from the fragment.
    assertThat("http://host/#a\u000bb".toHttpUrl().toUri())
      .isEqualTo(URI("http://host/#a%0Bb"))
    assertThat("http://host/#a b".toHttpUrl().toUri())
      .isEqualTo(URI("http://host/#a%20b"))
    assertThat("http://host/#a\u2009b".toHttpUrl().toUri())
      .isEqualTo(URI("http://host/#ab"))
    assertThat("http://host/#a\u3000b".toHttpUrl().toUri())
      .isEqualTo(URI("http://host/#ab"))
  }

  @Test
  fun toUriWithNonHexPercentEscape() {
    assertThat("http://host/%xx".toHttpUrl().toUri()).isEqualTo(URI("http://host/%25xx"))
  }

  @Test
  fun toUriWithTruncatedPercentEscape() {
    assertThat("http://host/%a".toHttpUrl().toUri()).isEqualTo(URI("http://host/%25a"))
    assertThat("http://host/%".toHttpUrl().toUri()).isEqualTo(URI("http://host/%25"))
  }

  @Test
  fun fromJavaNetUrl() {
    val javaNetUrl = URL("http://username:password@host/path?query#fragment")
    val httpUrl = javaNetUrl.toHttpUrlOrNull()
    assertThat(httpUrl.toString())
      .isEqualTo("http://username:password@host/path?query#fragment")
  }

  @Test
  fun fromJavaNetUrlUnsupportedScheme() {
    // java.net.MalformedURLException: unknown protocol: mailto
    platform.assumeNotAndroid()

    // Accessing an URL protocol that was not enabled. The URL protocol mailto is not tested and
    // might not work as expected. It can be enabled by adding the --enable-url-protocols=mailto
    // option to the native-image command.
    platform.assumeNotGraalVMImage()
    val javaNetUrl = URL("mailto:user@example.com")
    assertThat(javaNetUrl.toHttpUrlOrNull()).isNull()
  }

  @Test
  fun fromUri() {
    val uri = URI("http://username:password@host/path?query#fragment")
    val httpUrl = uri.toHttpUrlOrNull()
    assertThat(httpUrl.toString())
      .isEqualTo("http://username:password@host/path?query#fragment")
  }

  @Test
  fun fromUriUnsupportedScheme() {
    val uri = URI("mailto:user@example.com")
    assertThat(uri.toHttpUrlOrNull()).isNull()
  }

  @Test
  fun fromUriPartial() {
    val uri = URI("/path")
    assertThat(uri.toHttpUrlOrNull()).isNull()
  }

  @Test
  fun toJavaNetUrl() {
    val httpUrl = "http://username:password@host/path?query#fragment".toHttpUrl()
    val javaNetUrl = httpUrl.toUrl()
    assertThat(javaNetUrl.toString())
      .isEqualTo("http://username:password@host/path?query#fragment")
  }

  @Test
  fun toUri() {
    val httpUrl = "http://username:password@host/path?query#fragment".toHttpUrl()
    val uri = httpUrl.toUri()
    assertThat(uri.toString())
      .isEqualTo("http://username:password@host/path?query#fragment")
  }

  @Test
  fun toUriFragmentSpecialCharacters() {
    val url =
      HttpUrl
        .Builder()
        .scheme("http")
        .host("host")
        .fragment("=[]:;\"~|?#@^/$%*")
        .build()
    assertThat(url.toString()).isEqualTo("http://host/#=[]:;\"~|?#@^/$%25*")
    assertThat(url.toUri().toString())
      .isEqualTo("http://host/#=[]:;%22~%7C?%23@%5E/$%25*")
  }

  @Test
  fun toUriSpecialQueryCharacters() {
    val httpUrl = "http://host/?d=abc!@[]^`{}|\\".toHttpUrl()
    val uri = httpUrl.toUri()
    assertThat(uri.toString()).isEqualTo("http://host/?d=abc!@[]%5E%60%7B%7D%7C%5C")
  }

  @Test
  fun toUriWithUsernameNoPassword() {
    val httpUrl =
      HttpUrl
        .Builder()
        .scheme("http")
        .username("user")
        .host("host")
        .build()
    assertThat(httpUrl.toString()).isEqualTo("http://user@host/")
    assertThat(httpUrl.toUri().toString()).isEqualTo("http://user@host/")
  }

  @Test
  fun toUriUsernameSpecialCharacters() {
    val url =
      HttpUrl
        .Builder()
        .scheme("http")
        .host("host")
        .username("=[]:;\"~|?#@^/$%*")
        .build()
    assertThat(url.toString())
      .isEqualTo("http://%3D%5B%5D%3A%3B%22~%7C%3F%23%40%5E%2F$%25*@host/")
    assertThat(url.toUri().toString())
      .isEqualTo("http://%3D%5B%5D%3A%3B%22~%7C%3F%23%40%5E%2F$%25*@host/")
  }

  @Test
  fun toUriPasswordSpecialCharacters() {
    val url =
      HttpUrl
        .Builder()
        .scheme("http")
        .host("host")
        .username("user")
        .password("=[]:;\"~|?#@^/$%*")
        .build()
    assertThat(url.toString())
      .isEqualTo("http://user:%3D%5B%5D%3A%3B%22~%7C%3F%23%40%5E%2F$%25*@host/")
    assertThat(url.toUri().toString())
      .isEqualTo("http://user:%3D%5B%5D%3A%3B%22~%7C%3F%23%40%5E%2F$%25*@host/")
  }

  @Test
  fun toUriPathSpecialCharacters() {
    val url =
      HttpUrl
        .Builder()
        .scheme("http")
        .host("host")
        .addPathSegment("=[]:;\"~|?#@^/$%*")
        .build()
    assertThat(url.toString())
      .isEqualTo("http://host/=[]:;%22~%7C%3F%23@%5E%2F$%25*")
    assertThat(url.toUri().toString())
      .isEqualTo("http://host/=%5B%5D:;%22~%7C%3F%23@%5E%2F$%25*")
  }

  @Test
  fun toUriQueryParameterNameSpecialCharacters() {
    val url =
      HttpUrl
        .Builder()
        .scheme("http")
        .host("host")
        .addQueryParameter("=[]:;\"~|?#@^/$%*", "a")
        .build()
    assertThat(url.toString())
      .isEqualTo("http://host/?%3D%5B%5D%3A%3B%22%7E%7C%3F%23%40%5E%2F%24%25*=a")
    assertThat(url.toUri().toString())
      .isEqualTo("http://host/?%3D%5B%5D%3A%3B%22%7E%7C%3F%23%40%5E%2F%24%25*=a")
    assertThat(url.queryParameter("=[]:;\"~|?#@^/$%*")).isEqualTo("a")
  }

  @Test
  fun toUriQueryParameterValueSpecialCharacters() {
    val url =
      HttpUrl
        .Builder()
        .scheme("http")
        .host("host")
        .addQueryParameter("a", "=[]:;\"~|?#@^/$%*")
        .build()
    assertThat(url.toString())
      .isEqualTo("http://host/?a=%3D%5B%5D%3A%3B%22%7E%7C%3F%23%40%5E%2F%24%25*")
    assertThat(url.toUri().toString())
      .isEqualTo("http://host/?a=%3D%5B%5D%3A%3B%22%7E%7C%3F%23%40%5E%2F%24%25*")
    assertThat(url.queryParameter("a")).isEqualTo("=[]:;\"~|?#@^/$%*")
  }

  @Test
  fun toUriQueryValueSpecialCharacters() {
    val url =
      HttpUrl
        .Builder()
        .scheme("http")
        .host("host")
        .query("=[]:;\"~|?#@^/$%*")
        .build()
    assertThat(url.toString()).isEqualTo("http://host/?=[]:;%22~|?%23@^/$%25*")
    assertThat(url.toUri().toString())
      .isEqualTo("http://host/?=[]:;%22~%7C?%23@%5E/$%25*")
  }

  @Test
  fun topPrivateDomain() {
    assertThat("https://google.com".toHttpUrl().topPrivateDomain())
      .isEqualTo("google.com")
    assertThat("https://adwords.google.co.uk".toHttpUrl().topPrivateDomain())
      .isEqualTo("google.co.uk")
    assertThat("https://栃.栃木.jp".toHttpUrl().topPrivateDomain())
      .isEqualTo("xn--ewv.xn--4pvxs.jp")
    assertThat("https://xn--ewv.xn--4pvxs.jp".toHttpUrl().topPrivateDomain())
      .isEqualTo("xn--ewv.xn--4pvxs.jp")
    assertThat("https://co.uk".toHttpUrl().topPrivateDomain()).isNull()
    assertThat("https://square".toHttpUrl().topPrivateDomain()).isNull()
    assertThat("https://栃木.jp".toHttpUrl().topPrivateDomain()).isNull()
    assertThat("https://xn--4pvxs.jp".toHttpUrl().topPrivateDomain()).isNull()
    assertThat("https://localhost".toHttpUrl().topPrivateDomain()).isNull()
    assertThat("https://127.0.0.1".toHttpUrl().topPrivateDomain()).isNull()

    // https://github.com/lysine-dev/okhttp/issues/6109
    assertThat("http://a./".toHttpUrl().topPrivateDomain()).isNull()
    assertThat("http://squareup.com./".toHttpUrl().topPrivateDomain())
      .isEqualTo("squareup.com")
  }

  @Test
  fun fragmentNonAscii() {
    val url = "http://host/#Σ".toHttpUrl()
    assertThat(url.toUri().toString()).isEqualTo("http://host/#Σ")
  }

  @Test
  fun fragmentPercentEncodedNonAscii() {
    val url = "http://host/#%C2%80".toHttpUrl()
    assertThat(url.toUri().toString()).isEqualTo("http://host/#%C2%80")
  }

  @Test
  fun fragmentPercentEncodedPartialCodePoint() {
    val url = "http://host/#%80".toHttpUrl()
    assertThat(url.toUri().toString()).isEqualTo("http://host/#%80")
  }
}
