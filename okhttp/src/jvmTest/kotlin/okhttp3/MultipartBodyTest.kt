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

import assertk.assertThat
import assertk.assertions.isEqualTo
import java.io.IOException
import java.nio.charset.StandardCharsets
import kotlin.test.assertFailsWith
import okhttp3.Headers.Companion.headersOf
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.RequestBody.Companion.toRequestBody
import okio.Buffer
import okio.BufferedSink
import okio.utf8Size
import org.junit.jupiter.api.Test

class MultipartBodyTest {
  @Test
  fun onePartRequired() {
    assertFailsWith<IllegalStateException> {
      MultipartBody.Builder().build()
    }.also { expected ->
      assertThat(expected.message)
        .isEqualTo("Multipart body must have at least one part.")
    }
  }

  @Test
  fun singlePart() {
    val expected =
      """
      |--123
      |
      |Hello, World!
      |--123--
      |
      """.trimMargin().replace("\n", "\r\n")
    val body =
      MultipartBody
        .Builder("123")
        .addPart("Hello, World!".toRequestBody(null))
        .build()
    assertThat(body.boundary).isEqualTo("123")
    assertThat(body.type).isEqualTo(MultipartBody.MIXED)
    assertThat(body.contentType().toString())
      .isEqualTo("multipart/mixed; boundary=123")
    assertThat(body.parts.size).isEqualTo(1)
    assertThat(body.contentLength()).isEqualTo(33L)
    val buffer = Buffer()
    body.writeTo(buffer)
    assertThat(body.contentLength()).isEqualTo(buffer.size)
    assertThat(buffer.readUtf8()).isEqualTo(expected)
  }

  @Test
  fun threeParts() {
    val expected =
      """
      |--123
      |
      |Quick
      |--123
      |
      |Brown
      |--123
      |
      |Fox
      |--123--
      |
      """.trimMargin().replace("\n", "\r\n")
    val body =
      MultipartBody
        .Builder("123")
        .addPart("Quick".toRequestBody(null))
        .addPart("Brown".toRequestBody(null))
        .addPart("Fox".toRequestBody(null))
        .build()
    assertThat(body.boundary).isEqualTo("123")
    assertThat(body.type).isEqualTo(MultipartBody.MIXED)
    assertThat(body.contentType().toString())
      .isEqualTo("multipart/mixed; boundary=123")
    assertThat(body.parts.size).isEqualTo(3)
    assertThat(body.contentLength()).isEqualTo(55L)
    val buffer = Buffer()
    body.writeTo(buffer)
    assertThat(body.contentLength()).isEqualTo(buffer.size)
    assertThat(buffer.readUtf8()).isEqualTo(expected)
  }

  @Test
  fun boundaryWithNonTokenCharacterIsQuoted() {
    val body =
      MultipartBody
        .Builder("abc:def")
        .addPart("Hello, World!".toRequestBody(null))
        .build()
    assertThat(body.boundary).isEqualTo("abc:def")
    assertThat(body.contentType().toString())
      .isEqualTo("multipart/mixed; boundary=\"abc:def\"")
    assertThat(body.contentType().parameter("boundary")).isEqualTo("abc:def")
  }

  @Test
  fun boundaryContainingQuoteIsRejected() {
    val builder =
      MultipartBody
        .Builder("a\"; charset=\"evil")
        .addPart("Hello, World!".toRequestBody(null))
    assertFailsWith<IllegalArgumentException> {
      builder.build()
    }.also { expected ->
      assertThat(expected.message)
        .isEqualTo("boundary contains a character that can't be quoted: a\"; charset=\"evil")
    }
  }

  @Test
  fun fieldAndTwoFiles() {
    val expected =
      """
      |--AaB03x
      |Content-Disposition: form-data; name="submit-name"
      |
      |Larry
      |--AaB03x
      |Content-Disposition: form-data; name="files"
      |Content-Type: multipart/mixed; boundary=BbC04y
      |
      |--BbC04y
      |Content-Disposition: file; filename="file1.txt"
      |Content-Type: text/plain; charset=utf-8
      |
      |... contents of file1.txt ...
      |--BbC04y
      |Content-Disposition: file; filename="file2.gif"
      |Content-Transfer-Encoding: binary
      |Content-Type: image/gif
      |
      |... contents of file2.gif ...
      |--BbC04y--
      |
      |--AaB03x--
      |
      """.trimMargin().replace("\n", "\r\n")
    val body =
      MultipartBody
        .Builder("AaB03x")
        .setType(MultipartBody.FORM)
        .addFormDataPart("submit-name", "Larry")
        .addFormDataPart(
          "files",
          null,
          MultipartBody
            .Builder("BbC04y")
            .addPart(
              headersOf("Content-Disposition", "file; filename=\"file1.txt\""),
              "... contents of file1.txt ...".toRequestBody("text/plain".toMediaType()),
            ).addPart(
              headersOf(
                "Content-Disposition",
                "file; filename=\"file2.gif\"",
                "Content-Transfer-Encoding",
                "binary",
              ),
              "... contents of file2.gif ..."
                .toByteArray(StandardCharsets.UTF_8)
                .toRequestBody("image/gif".toMediaType()),
            ).build(),
        ).build()
    assertThat(body.boundary).isEqualTo("AaB03x")
    assertThat(body.type).isEqualTo(MultipartBody.FORM)
    assertThat(body.contentType().toString()).isEqualTo(
      "multipart/form-data; boundary=AaB03x",
    )
    assertThat(body.parts.size).isEqualTo(2)
    assertThat(body.contentLength()).isEqualTo(488L)
    val buffer = Buffer()
    body.writeTo(buffer)
    assertThat(body.contentLength()).isEqualTo(buffer.size)
    assertThat(buffer.readUtf8()).isEqualTo(expected)
  }

  @Test
  fun stringEscapingIsWeird() {
    val expected =
      """
      |--AaB03x
      |Content-Disposition: form-data; name="field with spaces"; filename="filename with spaces.txt"
      |Content-Type: text/plain; charset=utf-8
      |
      |okay
      |--AaB03x
      |Content-Disposition: form-data; name="field with %22"
      |
      |"
      |--AaB03x
      |Content-Disposition: form-data; name="field with %22"
      |
      |%22
      |--AaB03x
      |Content-Disposition: form-data; name="field with ~"
      |
      |Alpha
      |--AaB03x--
      |
      """.trimMargin().replace("\n", "\r\n")
    val body =
      MultipartBody
        .Builder("AaB03x")
        .setType(MultipartBody.FORM)
        .addFormDataPart(
          "field with spaces",
          "filename with spaces.txt",
          "okay".toRequestBody("text/plain; charset=utf-8".toMediaType()),
        ).addFormDataPart("field with \"", "\"")
        .addFormDataPart("field with %22", "%22")
        .addFormDataPart("field with \u007e", "Alpha")
        .build()
    val buffer = Buffer()
    body.writeTo(buffer)
    assertThat(buffer.readUtf8()).isEqualTo(expected)
  }

  @Test
  fun streamingPartHasNoLength() {
    class StreamingBody(
      private val body: String,
    ) : RequestBody() {
      override fun contentType(): MediaType? = null

      @Throws(IOException::class)
      override fun writeTo(sink: BufferedSink) {
        sink.writeUtf8(body)
      }
    }

    val expected =
      """
      |--123
      |
      |Quick
      |--123
      |
      |Brown
      |--123
      |
      |Fox
      |--123--
      |
      """.trimMargin().replace("\n", "\r\n")
    val body =
      MultipartBody
        .Builder("123")
        .addPart("Quick".toRequestBody(null))
        .addPart(StreamingBody("Brown"))
        .addPart("Fox".toRequestBody(null))
        .build()
    assertThat(body.boundary).isEqualTo("123")
    assertThat(body.type).isEqualTo(MultipartBody.MIXED)
    assertThat(body.contentType().toString())
      .isEqualTo("multipart/mixed; boundary=123")
    assertThat(body.parts.size).isEqualTo(3)
    assertThat(body.contentLength()).isEqualTo(-1)
    val buffer = Buffer()
    body.writeTo(buffer)
    assertThat(buffer.readUtf8()).isEqualTo(expected)
  }

  @Test
  fun contentTypeHeaderIsForbidden() {
    val multipart = MultipartBody.Builder()
    assertFailsWith<IllegalArgumentException> {
      multipart.addPart(
        headersOf("Content-Type", "text/plain"),
        "Hello, World!".toRequestBody(null),
      )
    }
  }

  @Test
  fun contentLengthHeaderIsForbidden() {
    val multipart = MultipartBody.Builder()
    assertFailsWith<IllegalArgumentException> {
      multipart.addPart(
        headersOf("Content-Length", "13"),
        "Hello, World!".toRequestBody(null),
      )
    }
  }

  @Test
  @Throws(IOException::class)
  fun partAccessors() {
    val body =
      MultipartBody
        .Builder()
        .addPart(headersOf("Foo", "Bar"), "Baz".toRequestBody(null))
        .build()
    assertThat(body.parts.size).isEqualTo(1)
    val part1Buffer = Buffer()
    val part1 = body.part(0)
    part1.body.writeTo(part1Buffer)
    assertThat(part1.headers).isEqualTo(headersOf("Foo", "Bar"))
    assertThat(part1Buffer.readUtf8()).isEqualTo("Baz")
  }

  @Test
  fun nonAsciiFilename() {
    val expected =
      """
      |--AaB03x
      |Content-Disposition: form-data; name="attachment"; filename="resumé.pdf"
      |Content-Type: application/pdf; charset=utf-8
      |
      |Jesse’s Resumé
      |--AaB03x--
      |
      """.trimMargin().replace("\n", "\r\n")
    val body =
      MultipartBody
        .Builder("AaB03x")
        .setType(MultipartBody.FORM)
        .addFormDataPart(
          "attachment",
          "resumé.pdf",
          "Jesse’s Resumé".toRequestBody("application/pdf".toMediaTypeOrNull()),
        ).build()
    val buffer = Buffer()
    body.writeTo(buffer)
    assertThat(buffer.readUtf8()).isEqualTo(expected)
  }

  @Test
  fun writeTwice() {
    val expected =
      """
      |--123
      |
      |Hello, World!
      |--123--
      |
      """.trimMargin().replace("\n", "\r\n")
    val body =
      MultipartBody
        .Builder("123")
        .addPart("Hello, World!".toRequestBody(null))
        .build()

    assertThat(body.isOneShot()).isEqualTo(false)

    val buffer = Buffer()
    body.writeTo(buffer)
    assertThat(body.contentLength()).isEqualTo(buffer.size)
    assertThat(buffer.readUtf8()).isEqualTo(expected)

    val buffer2 = Buffer()
    body.writeTo(buffer2)
    assertThat(body.contentLength()).isEqualTo(buffer2.size)
    assertThat(buffer2.readUtf8()).isEqualTo(expected)
  }

  @Test
  fun writeTwiceWithOneShot() {
    val expected =
      """
      |--123
      |
      |Hello, World!
      |--123--
      |
      """.trimMargin().replace("\n", "\r\n")
    val body =
      MultipartBody
        .Builder("123")
        .addPart("Hello, World!".toOneShotRequestBody())
        .build()

    assertThat(body.isOneShot()).isEqualTo(true)

    val buffer = Buffer()
    body.writeTo(buffer)
    assertThat(body.contentLength()).isEqualTo(buffer.size)
    assertThat(buffer.readUtf8()).isEqualTo(expected)
  }

  fun String.toOneShotRequestBody(): RequestBody =
    object : RequestBody() {
      override fun contentType() = null

      override fun isOneShot(): Boolean = true

      override fun contentLength() = this@toOneShotRequestBody.utf8Size()

      override fun writeTo(sink: BufferedSink) {
        sink.writeUtf8(this@toOneShotRequestBody)
      }
    }
}
