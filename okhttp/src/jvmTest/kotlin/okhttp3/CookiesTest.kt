/*
 * Copyright (C) 2010 The Android Open Source Project
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
import assertk.assertions.isCloseTo
import assertk.assertions.isEmpty
import assertk.assertions.isEqualTo
import assertk.assertions.isFalse
import assertk.assertions.isGreaterThan
import assertk.assertions.isNull
import assertk.assertions.isTrue
import assertk.fail
import java.net.CookieHandler
import java.net.CookieManager
import java.net.CookiePolicy
import java.net.HttpCookie
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.URI
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.junit5.StartStop
import okhttp3.Cookie.Companion.parse
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.java.net.cookiejar.JavaNetCookieJar
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.api.extension.RegisterExtension

/** Derived from Android's CookiesTest.  */
@Timeout(30)
class CookiesTest {
  @RegisterExtension
  val clientTestRule = OkHttpClientTestRule()

  @StartStop
  private val server = MockWebServer()

  private var client = clientTestRule.newClient()

  @Test
  fun testNetscapeResponse() {
    val cookieManager = CookieManager(null, CookiePolicy.ACCEPT_ORIGINAL_SERVER)
    client =
      client
        .newBuilder()
        .cookieJar(JavaNetCookieJar(cookieManager))
        .build()
    val urlWithIpAddress = urlWithIpAddress(server, "/path/foo")
    server.enqueue(
      MockResponse
        .Builder()
        .addHeader(
          "Set-Cookie: a=android; " +
            "expires=Fri, 31-Dec-9999 23:59:59 GMT; " +
            "path=/path; " +
            "domain=${urlWithIpAddress.host}; " +
            "secure",
        ).build(),
    )
    get(urlWithIpAddress)
    val cookies = cookieManager.cookieStore.cookies
    assertThat(cookies.size).isEqualTo(1)
    val cookie = cookies[0]
    assertThat(cookie.name).isEqualTo("a")
    assertThat(cookie.value).isEqualTo("android")
    assertThat(cookie.comment).isNull()
    assertThat(cookie.commentURL).isNull()
    assertThat(cookie.discard).isFalse()
    assertThat(cookie.maxAge).isGreaterThan(100000000000L)
    assertThat(cookie.path).isEqualTo("/path")
    assertThat(cookie.secure).isTrue()
    assertThat(cookie.version).isEqualTo(0)
  }

  @Test
  fun testRfc2109Response() {
    val cookieManager = CookieManager(null, CookiePolicy.ACCEPT_ORIGINAL_SERVER)
    client =
      client
        .newBuilder()
        .cookieJar(JavaNetCookieJar(cookieManager))
        .build()
    val urlWithIpAddress = urlWithIpAddress(server, "/path/foo")
    server.enqueue(
      MockResponse
        .Builder()
        .addHeader(
          "Set-Cookie: a=android; " +
            "Comment=this cookie is delicious; " +
            "Domain=${urlWithIpAddress.host}; " +
            "Max-Age=60; " +
            "Path=/path; " +
            "Secure; " +
            "Version=1",
        ).build(),
    )
    get(urlWithIpAddress)
    val cookies = cookieManager.cookieStore.cookies
    assertThat(cookies.size).isEqualTo(1)
    val cookie = cookies[0]
    assertThat(cookie.name).isEqualTo("a")
    assertThat(cookie.value).isEqualTo("android")
    assertThat(cookie.commentURL).isNull()
    assertThat(cookie.discard).isFalse()
    // Converting to a fixed date can cause rounding!
    assertThat(cookie.maxAge.toDouble()).isCloseTo(60.0, 5.0)
    assertThat(cookie.path).isEqualTo("/path")
    assertThat(cookie.secure).isTrue()
  }

  @Test
  fun testQuotedAttributeValues() {
    val cookieManager = CookieManager(null, CookiePolicy.ACCEPT_ORIGINAL_SERVER)
    client =
      client
        .newBuilder()
        .cookieJar(JavaNetCookieJar(cookieManager))
        .build()
    val urlWithIpAddress = urlWithIpAddress(server, "/path/foo")
    server.enqueue(
      MockResponse
        .Builder()
        .addHeader(
          "Set-Cookie: a=\"android\"; " +
            "Comment=\"this cookie is delicious\"; " +
            "CommentURL=\"http://google.com/\"; " +
            "Discard; " +
            "Domain=${urlWithIpAddress.host}; " +
            "Max-Age=60; " +
            "Path=\"/path\"; " +
            "Port=\"80,443,${server.port}\"; " +
            "Secure; " +
            "Version=\"1\"",
        ).build(),
    )
    get(urlWithIpAddress)
    val cookies = cookieManager.cookieStore.cookies
    assertThat(cookies.size).isEqualTo(1)
    val cookie = cookies[0]
    assertThat(cookie.name).isEqualTo("a")
    assertThat(cookie.value).isEqualTo("android")
    // Converting to a fixed date can cause rounding!
    assertThat(cookie.maxAge.toDouble()).isCloseTo(60.0, 1.0)
    assertThat(cookie.path).isEqualTo("/path")
    assertThat(cookie.secure).isTrue()
  }

  @Test
  fun testSendingCookiesFromStore() {
    server.enqueue(MockResponse())
    val serverUrl = urlWithIpAddress(server, "/")
    val cookieManager = CookieManager(null, CookiePolicy.ACCEPT_ORIGINAL_SERVER)
    val cookieA = HttpCookie("a", "android")
    cookieA.domain = serverUrl.host
    cookieA.path = "/"
    cookieManager.cookieStore.add(serverUrl.toUri(), cookieA)
    val cookieB = HttpCookie("b", "banana")
    cookieB.domain = serverUrl.host
    cookieB.path = "/"
    cookieManager.cookieStore.add(serverUrl.toUri(), cookieB)
    client =
      client
        .newBuilder()
        .cookieJar(JavaNetCookieJar(cookieManager))
        .build()
    get(serverUrl)
    val request = server.takeRequest()
    assertThat(request.headers["Cookie"]).isEqualTo("a=android; b=banana")
  }

  @Test
  fun cookieHandlerLikeAndroid() {
    server.enqueue(MockResponse())
    val serverUrl = urlWithIpAddress(server, "/")
    val androidCookieHandler: CookieHandler =
      object : CookieHandler() {
        override fun get(
          uri: URI,
          map: Map<String, List<String>>,
        ) = mapOf(
          "Cookie" to
            listOf(
              "\$Version=\"1\"; " +
                "a=\"android\";\$Path=\"/\";\$Domain=\"${serverUrl.host}\"; " +
                "b=\"banana\";\$Path=\"/\";\$Domain=\"${serverUrl.host}\"",
            ),
        )

        override fun put(
          uri: URI,
          map: Map<String, List<String>>,
        ) {
        }
      }
    client =
      client
        .newBuilder()
        .cookieJar(JavaNetCookieJar(androidCookieHandler))
        .build()
    get(serverUrl)
    val request = server.takeRequest()
    assertThat(request.headers["Cookie"]).isEqualTo("a=android; b=banana")
  }

  @Test
  fun receiveAndSendMultipleCookies() {
    server.enqueue(
      MockResponse
        .Builder()
        .addHeader("Set-Cookie", "a=android")
        .addHeader("Set-Cookie", "b=banana")
        .build(),
    )
    server.enqueue(MockResponse())
    val cookieManager = CookieManager(null, CookiePolicy.ACCEPT_ORIGINAL_SERVER)
    client =
      client
        .newBuilder()
        .cookieJar(JavaNetCookieJar(cookieManager))
        .build()
    get(urlWithIpAddress(server, "/"))
    val request1 = server.takeRequest()
    assertThat(request1.headers["Cookie"]).isNull()
    get(urlWithIpAddress(server, "/"))
    val request2 = server.takeRequest()
    assertThat(request2.headers["Cookie"]).isEqualTo("a=android; b=banana")
  }

  @Test
  fun testRedirectsDoNotIncludeTooManyCookies() {
    val redirectTarget = MockWebServer()
    redirectTarget.enqueue(MockResponse.Builder().body("A").build())
    redirectTarget.start()
    val redirectTargetUrl = urlWithIpAddress(redirectTarget, "/")
    val redirectSource = MockWebServer()
    redirectSource.enqueue(
      MockResponse
        .Builder()
        .code(HttpURLConnection.HTTP_MOVED_TEMP)
        .addHeader("Location: $redirectTargetUrl")
        .build(),
    )
    redirectSource.start()
    val redirectSourceUrl = urlWithIpAddress(redirectSource, "/")
    val cookieManager = CookieManager(null, CookiePolicy.ACCEPT_ORIGINAL_SERVER)
    val cookie = HttpCookie("c", "cookie")
    cookie.domain = redirectSourceUrl.host
    cookie.path = "/"
    val portList = redirectSource.port.toString()
    cookie.portlist = portList
    cookieManager.cookieStore.add(redirectSourceUrl.toUri(), cookie)
    client =
      client
        .newBuilder()
        .cookieJar(JavaNetCookieJar(cookieManager))
        .build()
    get(redirectSourceUrl)
    val request = redirectSource.takeRequest()
    assertThat(request.headers["Cookie"]).isEqualTo("c=cookie")
    for (header in redirectTarget.takeRequest().headers.names()) {
      if (header.startsWith("Cookie")) {
        fail(header)
      }
    }
  }

  @Test
  fun testCookiesSentIgnoresCase() {
    client =
      client
        .newBuilder()
        .cookieJar(
          JavaNetCookieJar(
            object : CookieManager() {
              override fun get(
                uri: URI,
                requestHeaders: Map<String, List<String>>,
              ) = mapOf(
                "COOKIE" to listOf("Bar=bar"),
                "cooKIE2" to listOf("Baz=baz"),
              )
            },
          ),
        ).build()
    server.enqueue(MockResponse())
    get(server.url("/"))
    val request = server.takeRequest()
    assertThat(request.headers["Cookie"]).isEqualTo("Bar=bar; Baz=baz")
    assertThat(request.headers["Cookie2"]).isNull()
    assertThat(request.headers["Quux"]).isNull()
  }

  @Test
  fun acceptOriginalServerMatchesSubdomain() {
    val cookieManager = CookieManager(null, CookiePolicy.ACCEPT_ORIGINAL_SERVER)
    val cookieJar = JavaNetCookieJar(cookieManager)
    val url = "https://www.squareup.com/".toHttpUrl()
    cookieJar.saveFromResponse(url, listOf(parse(url, "a=android; Domain=squareup.com")!!))
    val actualCookies = cookieJar.loadForRequest(url)
    assertThat(actualCookies.size).isEqualTo(1)
    assertThat(actualCookies[0].name).isEqualTo("a")
    assertThat(actualCookies[0].value).isEqualTo("android")
  }

  @Test
  fun acceptOriginalServerMatchesRfc2965Dot() {
    val cookieManager = CookieManager(null, CookiePolicy.ACCEPT_ORIGINAL_SERVER)
    val cookieJar = JavaNetCookieJar(cookieManager)
    val url = "https://www.squareup.com/".toHttpUrl()
    cookieJar.saveFromResponse(url, listOf(parse(url, "a=android; Domain=.squareup.com")!!))
    val actualCookies = cookieJar.loadForRequest(url)
    assertThat(actualCookies.size).isEqualTo(1)
    assertThat(actualCookies[0].name).isEqualTo("a")
    assertThat(actualCookies[0].value).isEqualTo("android")
  }

  @Test
  fun acceptOriginalServerMatchesExactly() {
    val cookieManager = CookieManager(null, CookiePolicy.ACCEPT_ORIGINAL_SERVER)
    val cookieJar = JavaNetCookieJar(cookieManager)
    val url = "https://squareup.com/".toHttpUrl()
    cookieJar.saveFromResponse(url, listOf(parse(url, "a=android; Domain=squareup.com")!!))
    val actualCookies = cookieJar.loadForRequest(url)
    assertThat(actualCookies.size).isEqualTo(1)
    assertThat(actualCookies[0].name).isEqualTo("a")
    assertThat(actualCookies[0].value).isEqualTo("android")
  }

  @Test
  fun acceptOriginalServerDoesNotMatchDifferentServer() {
    val cookieManager = CookieManager(null, CookiePolicy.ACCEPT_ORIGINAL_SERVER)
    val cookieJar = JavaNetCookieJar(cookieManager)
    val url1 = "https://api.squareup.com/".toHttpUrl()
    cookieJar.saveFromResponse(url1, listOf(parse(url1, "a=android; Domain=api.squareup.com")!!))
    val url2 = "https://www.squareup.com/".toHttpUrl()
    val actualCookies = cookieJar.loadForRequest(url2)
    assertThat(actualCookies).isEmpty()
  }

  @Test
  fun testQuoteStripping() {
    client =
      client
        .newBuilder()
        .cookieJar(
          JavaNetCookieJar(
            object : CookieManager() {
              override fun get(
                uri: URI,
                requestHeaders: Map<String, List<String>>,
              ) = mapOf(
                "COOKIE" to listOf("Bar=\""),
                "cooKIE2" to listOf("Baz=\"baz\""),
              )
            },
          ),
        ).build()
    server.enqueue(MockResponse())
    get(server.url("/"))
    val request = server.takeRequest()
    assertThat(request.headers["Cookie"]).isEqualTo("Bar=\"; Baz=baz")
    assertThat(request.headers["Cookie2"]).isNull()
    assertThat(request.headers["Quux"]).isNull()
  }

  @Test
  fun cookieHandlerWithQuotedValueAndTrailingSpace() {
    server.enqueue(MockResponse())
    val serverUrl = urlWithIpAddress(server, "/")
    val androidCookieHandler: CookieHandler =
      object : CookieHandler() {
        override fun get(
          uri: URI,
          map: Map<String, List<String>>,
        ) = mapOf(
          "Cookie" to
            listOf(
              "a=\"android \"",
            ),
        )

        override fun put(
          uri: URI,
          map: Map<String, List<String>>,
        ) {
        }
      }
    client =
      client
        .newBuilder()
        .cookieJar(JavaNetCookieJar(androidCookieHandler))
        .build()
    get(serverUrl)
    val request = server.takeRequest()
    assertThat(request.headers["Cookie"]).isEqualTo("a=android")
    assertThat(request.headers["Quux"]).isNull()
  }

  @Test
  fun receiveAndSendUntrimmedCookie() {
    server.enqueue(
      MockResponse
        .Builder()
        .addHeader("Set-Cookie", "a=\"android \"")
        .build(),
    )
    server.enqueue(MockResponse())
    val cookieManager = CookieManager(null, CookiePolicy.ACCEPT_ORIGINAL_SERVER)
    client =
      client
        .newBuilder()
        .cookieJar(JavaNetCookieJar(cookieManager))
        .build()
    get(urlWithIpAddress(server, "/"))
    val request1 = server.takeRequest()
    assertThat(request1.headers["Cookie"]).isNull()
    get(urlWithIpAddress(server, "/"))
    val request2 = server.takeRequest()
    assertThat(request2.headers["Cookie"]).isEqualTo("a=android")
  }

  private fun urlWithIpAddress(
    server: MockWebServer,
    path: String,
  ): HttpUrl =
    server
      .url(path)
      .newBuilder()
      .host(InetAddress.getByName(server.hostName).hostAddress)
      .build()

  private operator fun get(url: HttpUrl) {
    val call =
      client.newCall(
        Request
          .Builder()
          .url(url)
          .build(),
      )
    val response = call.execute()
    response.body.close()
  }
}
