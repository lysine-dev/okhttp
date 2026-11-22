/*
 * Copyright (C) 2013 Square, Inc.
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
import assertk.assertions.isEmpty
import assertk.assertions.isEqualTo
import assertk.assertions.isNull
import assertk.assertions.isSameInstanceAs
import assertk.assertions.isTrue
import kotlin.test.Test
import kotlin.test.assertFailsWith
import okhttp3.Headers.Companion.headersOf
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.internal.EmptyTags

class RequestCommonTest {
  @Test
  fun constructorNormal() {
    val url = "https://example.com/".toHttpUrl()
    val body = "hello".toRequestBody()
    val headers = headersOf("User-Agent", "RequestTest")
    val method = "PUT"
    val request =
      Request(
        url = url,
        headers = headers,
        method = method,
        body = body,
      )
    assertThat(request.url).isEqualTo(url)
    assertThat(request.headers).isEqualTo(headers)
    assertThat(request.method).isEqualTo(method)
    assertThat(request.body).isEqualTo(body)
    assertThat(request.tags).isEqualTo(EmptyTags)
  }

  @Test
  fun constructorNoBodyNoMethod() {
    val url = "https://example.com/".toHttpUrl()
    val headers = headersOf("User-Agent", "RequestTest")
    val request =
      Request(
        url = url,
        headers = headers,
      )
    assertThat(request.url).isEqualTo(url)
    assertThat(request.headers).isEqualTo(headers)
    assertThat(request.method).isEqualTo("GET")
    assertThat(request.body).isNull()
    assertThat(request.tags).isEqualTo(EmptyTags)
  }

  @Test
  fun constructorNoMethod() {
    val url = "https://example.com/".toHttpUrl()
    val body = "hello".toRequestBody()
    val headers = headersOf("User-Agent", "RequestTest")
    val request =
      Request(
        url = url,
        headers = headers,
        body = body,
      )
    assertThat(request.url).isEqualTo(url)
    assertThat(request.headers).isEqualTo(headers)
    assertThat(request.method).isEqualTo("POST")
    assertThat(request.body).isEqualTo(body)
    assertThat(request.tags).isEqualTo(EmptyTags)
  }

  @Test
  fun constructorNoBody() {
    val url = "https://example.com/".toHttpUrl()
    val headers = headersOf("User-Agent", "RequestTest")
    val method = "DELETE"
    val request =
      Request(
        url = url,
        headers = headers,
        method = method,
      )
    assertThat(request.url).isEqualTo(url)
    assertThat(request.headers).isEqualTo(headers)
    assertThat(request.method).isEqualTo(method)
    assertThat(request.body).isNull()
    assertThat(request.tags).isEqualTo(EmptyTags)
  }

  @Test
  fun newBuilderUrlResetsUrl() {
    val requestWithoutCache = Request.Builder().url("http://localhost/api").build()
    val builtRequestWithoutCache = requestWithoutCache.newBuilder().url("http://localhost/api/foo").build()
    assertThat(builtRequestWithoutCache.url).isEqualTo(
      "http://localhost/api/foo".toHttpUrl(),
    )
    val requestWithCache =
      Request
        .Builder()
        .url("http://localhost/api")
        .build()
    // cache url object
    requestWithCache.url
    val builtRequestWithCache =
      requestWithCache
        .newBuilder()
        .url("http://localhost/api/foo")
        .build()
    assertThat(builtRequestWithCache.url)
      .isEqualTo("http://localhost/api/foo".toHttpUrl())
  }

  @Test
  fun cacheControl() {
    val request =
      Request
        .Builder()
        .cacheControl(CacheControl.Builder().noCache().build())
        .url("https://square.com")
        .build()
    assertThat(request.headers("Cache-Control")).containsExactly("no-cache")
    assertThat(request.cacheControl.noCache).isTrue()
  }

  @Test
  fun emptyCacheControlClearsAllCacheControlHeaders() {
    val request =
      Request
        .Builder()
        .header("Cache-Control", "foo")
        .cacheControl(CacheControl.Builder().build())
        .url("https://square.com")
        .build()
    assertThat(request.headers("Cache-Control")).isEmpty()
  }

  @Test
  fun headerAcceptsPermittedCharacters() {
    val builder = Request.Builder()
    builder.header("AZab09~", "AZab09 ~")
    builder.addHeader("AZab09~", "AZab09 ~")
  }

  @Test
  fun emptyNameForbidden() {
    val builder = Request.Builder()
    assertFailsWith<IllegalArgumentException> {
      builder.header("", "Value")
    }
    assertFailsWith<IllegalArgumentException> {
      builder.addHeader("", "Value")
    }
  }

  @Test
  fun headerAllowsTabOnlyInValues() {
    val builder = Request.Builder()
    builder.header("key", "sample\tvalue")
    assertFailsWith<IllegalArgumentException> {
      builder.header("sample\tkey", "value")
    }
  }

  @Test
  fun headerForbidsControlCharacters() {
    assertForbiddenHeader("\u0000")
    assertForbiddenHeader("\r")
    assertForbiddenHeader("\n")
    assertForbiddenHeader("\u001f")
    assertForbiddenHeader("\u007f")
    assertForbiddenHeader("\u0080")
    assertForbiddenHeader("\ud83c\udf69")
  }

  private fun assertForbiddenHeader(s: String) {
    val builder = Request.Builder()
    assertFailsWith<IllegalArgumentException> {
      builder.header(s, "Value")
    }
    assertFailsWith<IllegalArgumentException> {
      builder.addHeader(s, "Value")
    }
    assertFailsWith<IllegalArgumentException> {
      builder.header("Name", s)
    }
    assertFailsWith<IllegalArgumentException> {
      builder.addHeader("Name", s)
    }
  }

  @Test
  fun noTag() {
    val request =
      Request
        .Builder()
        .url("https://square.com")
        .build()
    assertThat(request.tag<Any>()).isNull()
    assertThat(request.tag(Any::class)).isNull()
    assertThat(request.tag(String::class)).isNull()

    // Alternate access APIs also work.
    assertThat(request.tag<String>()).isNull()
    assertThat(request.tag(String::class)).isNull()
  }

  @Test
  fun defaultTag() {
    val tag = "1234"
    val request =
      Request
        .Builder()
        .url("https://square.com")
        .tag(tag as Any)
        .build()
    assertThat(request.tag<Any>()).isSameInstanceAs(tag)
    assertThat(request.tag(Any::class)).isSameInstanceAs(tag)
    assertThat(request.tag(String::class)).isNull()

    // Alternate access APIs also work.
    assertThat(request.tag<Any>()).isSameInstanceAs(tag)
    assertThat(request.tag(Any::class)).isSameInstanceAs(tag)
  }

  @Test
  fun nullRemovesTag() {
    val request =
      Request
        .Builder()
        .url("https://square.com")
        .tag("a" as Any)
        .tag(null)
        .build()
    assertThat(request.tag<Any>()).isNull()
  }

  @Test
  fun removeAbsentTag() {
    val request =
      Request
        .Builder()
        .url("https://square.com")
        .tag(null)
        .build()
    assertThat(request.tag<String>()).isNull()
  }

  @Test
  fun objectTag() {
    val tag = "1234"
    val request =
      Request
        .Builder()
        .url("https://square.com")
        .tag(Any::class, tag)
        .build()
    assertThat(request.tag<Any>()).isSameInstanceAs(tag)
    assertThat(request.tag(Any::class)).isSameInstanceAs(tag)
    assertThat(request.tag(String::class)).isNull()

    // Alternate access APIs also work.
    assertThat(request.tag(Any::class)).isSameInstanceAs(tag)
    assertThat(request.tag<Any>()).isSameInstanceAs(tag)
  }

  @Test
  fun kotlinReifiedTag() {
    val uuidTag = "1234"
    val request =
      Request
        .Builder()
        .url("https://square.com")
        .tag<String>(uuidTag) // Use the type parameter.
        .build()
    assertThat(request.tag<String>()).isSameInstanceAs("1234")
    assertThat(request.tag<Any>()).isNull()

    // Alternate access APIs also work.
    assertThat(request.tag(String::class)).isSameInstanceAs(uuidTag)
  }

  @Test
  fun kotlinClassTag() {
    val uuidTag = "1234"
    val request =
      Request
        .Builder()
        .url("https://square.com")
        .tag(String::class, uuidTag) // Use the KClass<*> parameter.
        .build()
    assertThat(request.tag(Any::class)).isNull()
    assertThat(request.tag(String::class)).isSameInstanceAs("1234")

    // Alternate access APIs also work.
    assertThat(request.tag(String::class)).isSameInstanceAs(uuidTag)
    assertThat(request.tag<String>()).isSameInstanceAs(uuidTag)
  }

  @Test
  fun replaceOnlyTag() {
    val uuidTag1 = "1234"
    val uuidTag2 = "4321"
    val request =
      Request
        .Builder()
        .url("https://square.com")
        .tag(String::class, uuidTag1)
        .tag(String::class, uuidTag2)
        .build()
    assertThat(request.tag(String::class)).isSameInstanceAs(uuidTag2)
  }

  @Test
  fun multipleTags() {
    val stringTag = "dilophosaurus"
    val longTag = 20170815L as Long?
    val objectTag = Any()
    val request =
      Request
        .Builder()
        .url("https://square.com")
        .tag(Any::class, objectTag)
        .tag(String::class, stringTag)
        .tag(Long::class, longTag)
        .build()
    assertThat(request.tag<Any>()).isSameInstanceAs(objectTag)
    assertThat(request.tag(Any::class)).isSameInstanceAs(objectTag)
    assertThat(request.tag(String::class)).isSameInstanceAs(stringTag)

    // TODO check for Jvm or handle Long/long correctly
//    assertThat(request.tag(Long::class)).isSameAs(longTag)
  }

  /** Confirm that we don't accidentally share the backing map between objects. */
  @Test
  fun tagsAreImmutable() {
    val builder =
      Request
        .Builder()
        .url("https://square.com")
    val requestA = builder.tag(String::class, "a").build()
    val requestB = builder.tag(String::class, "b").build()
    val requestC = requestA.newBuilder().tag(String::class, "c").build()
    assertThat(requestA.tag(String::class)).isSameInstanceAs("a")
    assertThat(requestB.tag(String::class)).isSameInstanceAs("b")
    assertThat(requestC.tag(String::class)).isSameInstanceAs("c")
  }

  @Test
  fun requestToStringRedactsSensitiveHeaders() {
    val headers =
      Headers
        .Builder()
        .add("content-length", "99")
        .add("authorization", "peanutbutter")
        .add("proxy-authorization", "chocolate")
        .add("cookie", "drink=coffee")
        .add("set-cookie", "accessory=sugar")
        .add("user-agent", "OkHttp")
        .build()
    val request =
      Request(
        "https://square.com".toHttpUrl(),
        headers,
      )
    assertThat(request.toString()).isEqualTo(
      "Request{method=GET, url=https://square.com/, headers=[" +
        "content-length:99," +
        " authorization:██," +
        " proxy-authorization:██," +
        " cookie:██," +
        " set-cookie:██," +
        " user-agent:OkHttp" +
        "]}",
    )
  }
}
