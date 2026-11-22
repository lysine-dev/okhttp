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
import assertk.assertions.hasMessage
import assertk.assertions.isEmpty
import assertk.assertions.isEqualTo
import assertk.assertions.isNull
import assertk.assertions.isSameInstanceAs
import assertk.assertions.isTrue
import java.io.File
import java.io.FileWriter
import java.net.URI
import java.util.UUID
import kotlin.test.assertFailsWith
import okhttp3.Headers.Companion.headersOf
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.internal.EmptyTags
import okio.Buffer
import okio.ByteString.Companion.decodeHex
import okio.ByteString.Companion.encodeUtf8
import okio.GzipSource
import okio.buffer
import okio.use
import org.junit.jupiter.api.Test

class RequestTest {
  @Test
  fun constructor() {
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
  fun string() {
    val contentType = "text/plain; charset=utf-8".toMediaType()
    val body = "abc".toByteArray().toRequestBody(contentType)
    assertThat(body.contentType()).isEqualTo(contentType)
    assertThat(body.contentLength()).isEqualTo(3)
    assertThat(bodyToHex(body)).isEqualTo("616263")
    assertThat(bodyToHex(body), "Retransmit body").isEqualTo("616263")
  }

  @Test
  fun stringWithDefaultCharsetAdded() {
    val contentType = "text/plain".toMediaType()
    val body = "\u0800".toRequestBody(contentType)
    assertThat(body.contentType()).isEqualTo("text/plain; charset=utf-8".toMediaType())
    assertThat(body.contentLength()).isEqualTo(3)
    assertThat(bodyToHex(body)).isEqualTo("e0a080")
  }

  @Test
  fun stringWithNonDefaultCharsetSpecified() {
    val contentType = "text/plain; charset=utf-16be".toMediaType()
    val body = "\u0800".toRequestBody(contentType)
    assertThat(body.contentType()).isEqualTo(contentType)
    assertThat(body.contentLength()).isEqualTo(2)
    assertThat(bodyToHex(body)).isEqualTo("0800")
  }

  @Test
  fun byteArray() {
    val contentType = "text/plain".toMediaType()
    val body: RequestBody = "abc".toByteArray().toRequestBody(contentType)
    assertThat(body.contentType()).isEqualTo(contentType)
    assertThat(body.contentLength()).isEqualTo(3)
    assertThat(bodyToHex(body)).isEqualTo("616263")
    assertThat(bodyToHex(body), "Retransmit body").isEqualTo("616263")
  }

  @Test
  fun byteArrayRange() {
    val contentType = "text/plain".toMediaType()
    val body: RequestBody = ".abcd".toByteArray().toRequestBody(contentType, 1, 3)
    assertThat(body.contentType()).isEqualTo(contentType)
    assertThat(body.contentLength()).isEqualTo(3)
    assertThat(bodyToHex(body)).isEqualTo("616263")
    assertThat(bodyToHex(body), "Retransmit body").isEqualTo("616263")
  }

  @Test
  fun byteString() {
    val contentType = "text/plain".toMediaType()
    val body: RequestBody = "Hello".encodeUtf8().toRequestBody(contentType)
    assertThat(body.contentType()).isEqualTo(contentType)
    assertThat(body.contentLength()).isEqualTo(5)
    assertThat(bodyToHex(body)).isEqualTo("48656c6c6f")
    assertThat(bodyToHex(body), "Retransmit body").isEqualTo("48656c6c6f")
  }

  @Test
  fun file() {
    val file = File.createTempFile("RequestTest", "tmp")
    val writer = FileWriter(file)
    writer.write("abc")
    writer.close()
    val contentType = "text/plain".toMediaType()
    val body: RequestBody = file.asRequestBody(contentType)
    assertThat(body.contentType()).isEqualTo(contentType)
    assertThat(body.contentLength()).isEqualTo(3)
    assertThat(bodyToHex(body)).isEqualTo("616263")
    assertThat(bodyToHex(body), "Retransmit body").isEqualTo("616263")
  }

  /** Common verbs used for apis such as GitHub, AWS, and Google Cloud.  */
  @Test
  fun crudVerbs() {
    val contentType = "application/json".toMediaType()
    val body = "{}".toRequestBody(contentType)

    val get =
      Request
        .Builder()
        .url("http://localhost/api")
        .get()
        .build()
    assertThat(get.method).isEqualTo("GET")
    assertThat(get.body).isNull()

    val head =
      Request
        .Builder()
        .url("http://localhost/api")
        .head()
        .build()
    assertThat(head.method).isEqualTo("HEAD")
    assertThat(head.body).isNull()

    val delete =
      Request
        .Builder()
        .url("http://localhost/api")
        .delete()
        .build()
    assertThat(delete.method).isEqualTo("DELETE")
    assertThat(delete.body!!.contentLength()).isEqualTo(0L)

    val post =
      Request
        .Builder()
        .url("http://localhost/api")
        .post(body)
        .build()
    assertThat(post.method).isEqualTo("POST")
    assertThat(post.body).isEqualTo(body)

    val put =
      Request
        .Builder()
        .url("http://localhost/api")
        .put(body)
        .build()
    assertThat(put.method).isEqualTo("PUT")
    assertThat(put.body).isEqualTo(body)

    val patch =
      Request
        .Builder()
        .url("http://localhost/api")
        .patch(body)
        .build()
    assertThat(patch.method).isEqualTo("PATCH")
    assertThat(patch.body).isEqualTo(body)

    val query =
      Request
        .Builder()
        .url("http://localhost/api")
        .query(body)
        .build()
    assertThat(query.method).isEqualTo("QUERY")
    assertThat(query.body).isEqualTo(body)
  }

  @Test
  fun uninitializedURI() {
    val request = Request.Builder().url("http://localhost/api").build()
    assertThat(request.url.toUri()).isEqualTo(URI("http://localhost/api"))
    assertThat(request.url).isEqualTo("http://localhost/api".toHttpUrl())
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
    assertThat(request.tag()).isNull()
    assertThat(request.tag(Any::class.java)).isNull()
    assertThat(request.tag(UUID::class.java)).isNull()
    assertThat(request.tag(String::class.java)).isNull()

    // Alternate access APIs also work.
    assertThat(request.tag<String>()).isNull()
    assertThat(request.tag(String::class)).isNull()
  }

  @Test
  fun defaultTag() {
    val tag = UUID.randomUUID()
    val request =
      Request
        .Builder()
        .url("https://square.com")
        .tag(tag)
        .build()
    assertThat(request.tag()).isSameInstanceAs(tag)
    assertThat(request.tag(Any::class.java)).isSameInstanceAs(tag)
    assertThat(request.tag(UUID::class.java)).isNull()
    assertThat(request.tag(String::class.java)).isNull()

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
        .tag("a")
        .tag(null)
        .build()
    assertThat(request.tag()).isNull()
  }

  @Test
  fun removeAbsentTag() {
    val request =
      Request
        .Builder()
        .url("https://square.com")
        .tag(null)
        .build()
    assertThat(request.tag()).isNull()
  }

  @Test
  fun objectTag() {
    val tag = UUID.randomUUID()
    val request =
      Request
        .Builder()
        .url("https://square.com")
        .tag(Any::class.java, tag)
        .build()
    assertThat(request.tag()).isSameInstanceAs(tag)
    assertThat(request.tag(Any::class.java)).isSameInstanceAs(tag)
    assertThat(request.tag(UUID::class.java)).isNull()
    assertThat(request.tag(String::class.java)).isNull()

    // Alternate access APIs also work.
    assertThat(request.tag(Any::class)).isSameInstanceAs(tag)
    assertThat(request.tag<Any>()).isSameInstanceAs(tag)
  }

  @Test
  fun javaClassTag() {
    val uuidTag = UUID.randomUUID()
    val request =
      Request
        .Builder()
        .url("https://square.com")
        .tag(UUID::class.java, uuidTag) // Use the Class<*> parameter.
        .build()
    assertThat(request.tag()).isNull()
    assertThat(request.tag(Any::class.java)).isNull()
    assertThat(request.tag(UUID::class.java)).isSameInstanceAs(uuidTag)
    assertThat(request.tag(String::class.java)).isNull()

    // Alternate access APIs also work.
    assertThat(request.tag(UUID::class)).isSameInstanceAs(uuidTag)
    assertThat(request.tag<UUID>()).isSameInstanceAs(uuidTag)
  }

  @Test
  fun kotlinReifiedTag() {
    val uuidTag = UUID.randomUUID()
    val request =
      Request
        .Builder()
        .url("https://square.com")
        .tag<UUID>(uuidTag) // Use the type parameter.
        .build()
    assertThat(request.tag()).isNull()
    assertThat(request.tag<Any>()).isNull()
    assertThat(request.tag<UUID>()).isSameInstanceAs(uuidTag)
    assertThat(request.tag<String>()).isNull()

    // Alternate access APIs also work.
    assertThat(request.tag(UUID::class.java)).isSameInstanceAs(uuidTag)
    assertThat(request.tag(UUID::class)).isSameInstanceAs(uuidTag)
  }

  @Test
  fun kotlinClassTag() {
    val uuidTag = UUID.randomUUID()
    val request =
      Request
        .Builder()
        .url("https://square.com")
        .tag(UUID::class, uuidTag) // Use the KClass<*> parameter.
        .build()
    assertThat(request.tag()).isNull()
    assertThat(request.tag(Any::class)).isNull()
    assertThat(request.tag(UUID::class)).isSameInstanceAs(uuidTag)
    assertThat(request.tag(String::class)).isNull()

    // Alternate access APIs also work.
    assertThat(request.tag(UUID::class.java)).isSameInstanceAs(uuidTag)
    assertThat(request.tag<UUID>()).isSameInstanceAs(uuidTag)
  }

  @Test
  fun replaceOnlyTag() {
    val uuidTag1 = UUID.randomUUID()
    val uuidTag2 = UUID.randomUUID()
    val request =
      Request
        .Builder()
        .url("https://square.com")
        .tag(UUID::class.java, uuidTag1)
        .tag(UUID::class.java, uuidTag2)
        .build()
    assertThat(request.tag(UUID::class.java)).isSameInstanceAs(uuidTag2)
  }

  @Test
  fun multipleTags() {
    val uuidTag = UUID.randomUUID()
    val stringTag = "dilophosaurus"
    val longTag = 20170815L as Long?
    val objectTag = Any()
    val request =
      Request
        .Builder()
        .url("https://square.com")
        .tag(Any::class.java, objectTag)
        .tag(UUID::class.java, uuidTag)
        .tag(String::class.java, stringTag)
        .tag(Long::class.javaObjectType, longTag)
        .build()
    assertThat(request.tag()).isSameInstanceAs(objectTag)
    assertThat(request.tag(Any::class.java)).isSameInstanceAs(objectTag)
    assertThat(request.tag(UUID::class.java)).isSameInstanceAs(uuidTag)
    assertThat(request.tag(String::class.java)).isSameInstanceAs(stringTag)
    assertThat(request.tag(Long::class.javaObjectType)).isSameInstanceAs(longTag)
  }

  /** Confirm that we don't accidentally share the backing map between objects. */
  @Test
  fun tagsAreImmutable() {
    val builder =
      Request
        .Builder()
        .url("https://square.com")
    val requestA = builder.tag(String::class.java, "a").build()
    val requestB = builder.tag(String::class.java, "b").build()
    val requestC = requestA.newBuilder().tag(String::class.java, "c").build()
    assertThat(requestA.tag(String::class.java)).isSameInstanceAs("a")
    assertThat(requestB.tag(String::class.java)).isSameInstanceAs("b")
    assertThat(requestC.tag(String::class.java)).isSameInstanceAs("c")
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

  @Test
  fun requestToStringIncludesTags() {
    val request =
      Request
        .Builder()
        .url("https://square.com/".toHttpUrl())
        .tag<String>("hello")
        .tag<Int>(5)
        .build()
    assertThat(request.toString()).isEqualTo(
      "Request{method=GET, url=https://square.com/, tags={" +
        "class kotlin.String=hello, " +
        "class kotlin.Int=5" +
        "}}",
    )
  }

  @Test
  fun gzip() {
    val mediaType = "text/plain; charset=utf-8".toMediaType()
    val originalBody = "This is the original message".toRequestBody(mediaType)
    assertThat(originalBody.contentLength()).isEqualTo(28L)
    assertThat(originalBody.contentType()).isEqualTo(mediaType)

    val request =
      Request
        .Builder()
        .url("https://square.com/")
        .post(originalBody)
        .gzip()
        .build()
    assertThat(request.headers["Content-Encoding"]).isEqualTo("gzip")
    assertThat(request.body?.contentLength()).isEqualTo(-1L)
    assertThat(request.body?.contentType()).isEqualTo(mediaType)

    val requestBodyBytes =
      Buffer()
        .apply {
          request.body?.writeTo(this)
        }

    val decompressedRequestBody =
      GzipSource(requestBodyBytes).use {
        it.buffer().readUtf8()
      }
    assertThat(decompressedRequestBody).isEqualTo("This is the original message")
  }

  @Test
  fun cannotGzipWithoutABody() {
    assertFailsWith<IllegalStateException> {
      Request
        .Builder()
        .url("https://square.com/")
        .gzip()
        .build()
    }.also {
      assertThat(it).hasMessage("cannot gzip a request that has no body")
    }
  }

  @Test
  fun cannotGzipWithAnotherContentEncoding() {
    assertFailsWith<IllegalStateException> {
      Request
        .Builder()
        .url("https://square.com/")
        .post("This is the original message".toRequestBody())
        .addHeader("Content-Encoding", "deflate")
        .gzip()
        .build()
    }.also {
      assertThat(it).hasMessage("Content-Encoding already set: deflate")
    }
  }

  @Test
  fun cannotGzipTwice() {
    assertFailsWith<IllegalStateException> {
      Request
        .Builder()
        .url("https://square.com/")
        .post("This is the original message".toRequestBody())
        .gzip()
        .gzip()
        .build()
    }.also {
      assertThat(it).hasMessage("Content-Encoding already set: gzip")
    }
  }

  @Test
  fun curlGet() {
    val request =
      Request
        .Builder()
        .url("https://example.com")
        .header("Authorization", "Bearer abc123")
        .build()

    val curl = request.toCurl()
    assertThat(curl)
      .isEqualTo(
        """
        |curl 'https://example.com/' \
        |  -H 'Authorization: Bearer abc123'
        """.trimMargin(),
      )
  }

  @Test
  fun curlPostWithBody() {
    val body = "{\"key\":\"value\"}".toRequestBody("application/json".toMediaType())

    val request =
      Request
        .Builder()
        .url("https://api.example.com/data")
        .post(body)
        .addHeader("Authorization", "Bearer abc123")
        .build()

    assertThat(request.toCurl())
      .isEqualTo(
        """
        |curl 'https://api.example.com/data' \
        |  -H 'Authorization: Bearer abc123' \
        |  -H 'Content-Type: application/json; charset=utf-8' \
        |  --data '{"key":"value"}'
        """.trimMargin(),
      )
  }

  @Test
  fun bodyContentTypeTakesPrecedence() {
    val body = "{\"key\":\"value\"}".toRequestBody("application/json".toMediaType())

    val request =
      Request
        .Builder()
        .url("https://api.example.com/data")
        .post(body)
        .addHeader("Content-Type", "text/plain")
        .build()

    assertThat(request.toCurl())
      .isEqualTo(
        """
        |curl 'https://api.example.com/data' \
        |  -H 'Content-Type: application/json; charset=utf-8' \
        |  --data '{"key":"value"}'
        """.trimMargin(),
      )
  }

  @Test
  fun requestContentTypeIsFallback() {
    val body = "{\"key\":\"value\"}".toRequestBody(contentType = null)

    val request =
      Request
        .Builder()
        .url("https://api.example.com/data")
        .post(body)
        .addHeader("Content-Type", "text/plain")
        .build()

    assertThat(request.toCurl())
      .isEqualTo(
        """
        |curl 'https://api.example.com/data' \
        |  -H 'Content-Type: text/plain' \
        |  --data '{"key":"value"}'
        """.trimMargin(),
      )
  }

  /** Put is not the default method so `-X 'PUT'` is included. */
  @Test
  fun curlPutWithBody() {
    val body = "{\"key\":\"value\"}".toRequestBody("application/json".toMediaType())

    val request =
      Request
        .Builder()
        .url("https://api.example.com/data")
        .put(body)
        .addHeader("Authorization", "Bearer abc123")
        .build()

    assertThat(request.toCurl())
      .isEqualTo(
        """
        |curl 'https://api.example.com/data' \
        |  -X 'PUT' \
        |  -H 'Authorization: Bearer abc123' \
        |  -H 'Content-Type: application/json; charset=utf-8' \
        |  --data '{"key":"value"}'
        """.trimMargin(),
      )
  }

  @Test
  fun curlPostWithComplexBody() {
    val jsonBody =
      """
      |{
      |  "user": {
      |    "id": 123,
      |    "name": "Tim O'Reilly"
      |  },
      |  "roles": ["admin", "editor"],
      |  "active": true
      |}
      |
      """.trimMargin()

    val body = jsonBody.toRequestBody("application/json".toMediaType())

    val request =
      Request
        .Builder()
        .url("https://api.example.com/users")
        .post(body)
        .addHeader("Authorization", "Bearer xyz789")
        .build()

    assertThat(request.toCurl())
      .isEqualTo(
        """
        |curl 'https://api.example.com/users' \
        |  -H 'Authorization: Bearer xyz789' \
        |  -H 'Content-Type: application/json; charset=utf-8' \
        |  --data '{
        |  "user": {
        |    "id": 123,
        |    "name": "Tim O'\''Reilly"
        |  },
        |  "roles": ["admin", "editor"],
        |  "active": true
        |}
        |'
        """.trimMargin(),
      )
  }

  @Test
  fun curlPostWithBinaryBody() {
    val binaryData = "00010203".decodeHex()
    val body = binaryData.toRequestBody("application/octet-stream".toMediaType())

    val request =
      Request
        .Builder()
        .url("https://api.example.com/upload")
        .post(body)
        .build()

    val curl = request.toCurl()
    assertThat(curl)
      .isEqualTo(
        """
        |curl 'https://api.example.com/upload' \
        |  -H 'Content-Type: application/octet-stream' \
        |  --data-binary '00010203'
        """.trimMargin(),
      )
  }

  @Test
  fun curlPostWithBinaryBodyOmitted() {
    val binaryData = "1020".decodeHex()
    val body = binaryData.toRequestBody("application/octet-stream".toMediaType())

    val request =
      Request
        .Builder()
        .url("https://api.example.com/upload")
        .post(body)
        .build()

    val curl = request.toCurl(includeBody = false)
    assertThat(curl)
      .isEqualTo(
        """
        |curl 'https://api.example.com/upload' \
        |  -X 'POST' \
        |  -H 'Content-Type: application/octet-stream'
        """.trimMargin(),
      )
  }

  private fun bodyToHex(body: RequestBody): String {
    val buffer = Buffer()
    body.writeTo(buffer)
    return buffer.readByteString().hex()
  }
}
