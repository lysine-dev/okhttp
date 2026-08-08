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

import java.io.IOException
import java.util.UUID
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.internal.toImmutableList
import okio.Buffer
import okio.BufferedSink
import okio.ByteString
import okio.ByteString.Companion.encodeUtf8

/**
 * An [RFC 2387][rfc_2387]-compliant request body.
 *
 * [rfc_2387]: http://www.ietf.org/rfc/rfc2387.txt
 */
@Suppress("NAME_SHADOWING")
class MultipartBody internal constructor(
  private val boundaryByteString: ByteString,
  @get:JvmName("type") val type: MediaType,
  @get:JvmName("parts") val parts: List<Part>,
) : RequestBody() {
  private val contentType: MediaType =
    buildString {
      append(type)
      append("; boundary=")
      appendBoundary(boundary)
    }.toMediaType()
  private var contentLength = -1L

  @get:JvmName("boundary")
  val boundary: String
    get() = boundaryByteString.utf8()

  /** The number of parts in this multipart body. */
  @get:JvmName("size")
  val size: Int
    get() = parts.size

  fun part(index: Int): Part = parts[index]

  override fun isOneShot(): Boolean = parts.any { it.body.isOneShot() }

  /** A combination of [type] and [boundaryByteString]. */
  override fun contentType(): MediaType = contentType

  @JvmName("-deprecated_type")
  @Deprecated(
    message = "moved to val",
    replaceWith = ReplaceWith(expression = "type"),
    level = DeprecationLevel.ERROR,
  )
  fun type(): MediaType = type

  @JvmName("-deprecated_boundary")
  @Deprecated(
    message = "moved to val",
    replaceWith = ReplaceWith(expression = "boundary"),
    level = DeprecationLevel.ERROR,
  )
  fun boundary(): String = boundary

  @JvmName("-deprecated_size")
  @Deprecated(
    message = "moved to val",
    replaceWith = ReplaceWith(expression = "size"),
    level = DeprecationLevel.ERROR,
  )
  fun size(): Int = size

  @JvmName("-deprecated_parts")
  @Deprecated(
    message = "moved to val",
    replaceWith = ReplaceWith(expression = "parts"),
    level = DeprecationLevel.ERROR,
  )
  fun parts(): List<Part> = parts

  @Throws(IOException::class)
  override fun contentLength(): Long {
    var result = contentLength
    if (result == -1L) {
      result = writeOrCountBytes(null, true)
      contentLength = result
    }
    return result
  }

  @Throws(IOException::class)
  override fun writeTo(sink: BufferedSink) {
    writeOrCountBytes(sink, false)
  }

  /**
   * Either writes this request to [sink] or measures its content length. We have one method do
   * double-duty to make sure the counting and content are consistent, particularly when it comes
   * to awkward operations like measuring the encoded length of header strings, or the
   * length-in-digits of an encoded integer.
   */
  @Throws(IOException::class)
  private fun writeOrCountBytes(
    sink: BufferedSink?,
    countBytes: Boolean,
  ): Long {
    var sink = sink
    var byteCount = 0L

    var byteCountBuffer: Buffer? = null
    if (countBytes) {
      byteCountBuffer = Buffer()
      sink = byteCountBuffer
    }

    for (p in 0 until parts.size) {
      val part = parts[p]
      val headers = part.headers
      val body = part.body

      sink!!.write(DASHDASH)
      sink.write(boundaryByteString)
      sink.write(CRLF)

      if (headers != null) {
        for (h in 0 until headers.size) {
          sink
            .writeUtf8(headers.name(h))
            .write(COLONSPACE)
            .writeUtf8(headers.value(h))
            .write(CRLF)
        }
      }

      val contentType = body.contentType()
      if (contentType != null) {
        sink
          .writeUtf8("Content-Type: ")
          .writeUtf8(contentType.toString())
          .write(CRLF)
      }

      // We can't measure the body's size without the sizes of its components.
      val contentLength = body.contentLength()
      if (contentLength == -1L && countBytes) {
        byteCountBuffer!!.clear()
        return -1L
      }

      sink.write(CRLF)

      if (countBytes) {
        byteCount += contentLength
      } else {
        body.writeTo(sink)
      }

      sink.write(CRLF)
    }

    sink!!.write(DASHDASH)
    sink.write(boundaryByteString)
    sink.write(DASHDASH)
    sink.write(CRLF)

    if (countBytes) {
      byteCount += byteCountBuffer!!.size
      byteCountBuffer.clear()
    }

    return byteCount
  }

  class Part private constructor(
    @get:JvmName("headers") val headers: Headers?,
    @get:JvmName("body") val body: RequestBody,
  ) {
    @JvmName("-deprecated_headers")
    @Deprecated(
      message = "moved to val",
      replaceWith = ReplaceWith(expression = "headers"),
      level = DeprecationLevel.ERROR,
    )
    fun headers(): Headers? = headers

    @JvmName("-deprecated_body")
    @Deprecated(
      message = "moved to val",
      replaceWith = ReplaceWith(expression = "body"),
      level = DeprecationLevel.ERROR,
    )
    fun body(): RequestBody = body

    companion object {
      @JvmStatic
      fun create(body: RequestBody): Part = create(null, body)

      @JvmStatic
      fun create(
        headers: Headers?,
        body: RequestBody,
      ): Part {
        require(headers?.get("Content-Type") == null) { "Unexpected header: Content-Type" }
        require(headers?.get("Content-Length") == null) { "Unexpected header: Content-Length" }
        return Part(headers, body)
      }

      @JvmStatic
      fun createFormData(
        name: String,
        value: String,
      ): Part = createFormData(name, null, value.toRequestBody())

      @JvmStatic
      fun createFormData(
        name: String,
        filename: String?,
        body: RequestBody,
      ): Part {
        val disposition =
          buildString {
            append("form-data; name=")
            appendQuotedString(name)

            if (filename != null) {
              append("; filename=")
              appendQuotedString(filename)
            }
          }

        val headers =
          Headers
            .Builder()
            .addUnsafeNonAscii("Content-Disposition", disposition)
            .build()

        return create(headers, body)
      }
    }
  }

  class Builder
    @JvmOverloads
    constructor(
      boundary: String = UUID.randomUUID().toString(),
    ) {
      private val boundary: ByteString = boundary.encodeUtf8()
      private var type = MIXED
      private val parts = mutableListOf<Part>()

      /**
       * Set the MIME type. Expected values for `type` are [MIXED] (the default), [ALTERNATIVE],
       * [DIGEST], [PARALLEL] and [FORM].
       */
      fun setType(type: MediaType) =
        apply {
          require(type.type == "multipart") { "multipart != $type" }
          this.type = type
        }

      /** Add a part to the body. */
      fun addPart(body: RequestBody) =
        apply {
          addPart(Part.create(body))
        }

      /** Add a part to the body. */
      fun addPart(
        headers: Headers?,
        body: RequestBody,
      ) = apply {
        addPart(Part.create(headers, body))
      }

      /** Add a form data part to the body. */
      fun addFormDataPart(
        name: String,
        value: String,
      ) = apply {
        addPart(Part.createFormData(name, value))
      }

      /** Add a form data part to the body. */
      fun addFormDataPart(
        name: String,
        filename: String?,
        body: RequestBody,
      ) = apply {
        addPart(Part.createFormData(name, filename, body))
      }

      /** Add a part to the body. */
      fun addPart(part: Part) =
        apply {
          parts += part
        }

      /** Assemble the specified parts into a request body. */
      fun build(): MultipartBody {
        check(parts.isNotEmpty()) { "Multipart body must have at least one part." }
        return MultipartBody(boundary, type, parts.toImmutableList())
      }
    }

  companion object {
    /**
     * The "mixed" subtype of "multipart" is intended for use when the body parts are independent
     * and need to be bundled in a particular order. Any "multipart" subtypes that an implementation
     * does not recognize must be treated as being of subtype "mixed".
     */
    @JvmField
    val MIXED = "multipart/mixed".toMediaType()

    /**
     * The "multipart/alternative" type is syntactically identical to "multipart/mixed", but the
     * semantics are different. In particular, each of the body parts is an "alternative" version of
     * the same information.
     */
    @JvmField
    val ALTERNATIVE = "multipart/alternative".toMediaType()

    /**
     * This type is syntactically identical to "multipart/mixed", but the semantics are different.
     * In particular, in a digest, the default `Content-Type` value for a body part is changed from
     * "text/plain" to "message/rfc822".
     */
    @JvmField
    val DIGEST = "multipart/digest".toMediaType()

    /**
     * This type is syntactically identical to "multipart/mixed", but the semantics are different.
     * In particular, in a parallel entity, the order of body parts is not significant.
     */
    @JvmField
    val PARALLEL = "multipart/parallel".toMediaType()

    /**
     * The media-type multipart/form-data follows the rules of all multipart MIME data streams as
     * outlined in RFC 2046. In forms, there are a series of fields to be supplied by the user who
     * fills out the form. Each field has a name. Within a given form, the names are unique.
     */
    @JvmField
    val FORM = "multipart/form-data".toMediaType()

    private val COLONSPACE = byteArrayOf(':'.code.toByte(), ' '.code.toByte())
    private val CRLF = byteArrayOf('\r'.code.toByte(), '\n'.code.toByte())
    private val DASHDASH = byteArrayOf('-'.code.toByte(), '-'.code.toByte())

    private const val TOKEN_SPECIALS = "-!#\$%&'*+.^_`{|}~"

    /**
     * Appends [boundary] to this StringBuilder, unchanged if it's a valid RFC 2045 token or as a
     * quoted-string otherwise. A boundary that contains a character like `:` isn't a token, so it
     * must be quoted for the `Content-Type` to parse. See RFC 2045 section 5.1. A `"` or `\` can't
     * round-trip through the quoted-string that `MediaType` reads back, so those are rejected.
     */
    private fun StringBuilder.appendBoundary(boundary: String) {
      var quoted = boundary.isEmpty()
      for (c in boundary) {
        require(c != '"' && c != '\\') { "boundary contains a character that can't be quoted: $boundary" }
        if (!quoted && !c.isTokenChar()) quoted = true
      }
      if (quoted) {
        append('"')
        append(boundary)
        append('"')
      } else {
        append(boundary)
      }
    }

    private fun Char.isTokenChar(): Boolean =
      this in 'a'..'z' ||
        this in 'A'..'Z' ||
        this in '0'..'9' ||
        this in TOKEN_SPECIALS

    /**
     * Appends a quoted-string to a StringBuilder.
     *
     * RFC 2388 is rather vague about how one should escape special characters in form-data
     * parameters, and as it turns out Firefox and Chrome actually do rather different things, and
     * both say in their comments that they're not really sure what the right approach is. We go
     * with Chrome's behavior (which also experimentally seems to match what IE does), but if you
     * actually want to have a good chance of things working, please avoid double-quotes, newlines,
     * percent signs, and the like in your field names.
     */
    internal fun StringBuilder.appendQuotedString(key: String) {
      append('"')
      for (i in 0 until key.length) {
        when (val ch = key[i]) {
          '\n' -> append("%0A")
          '\r' -> append("%0D")
          '"' -> append("%22")
          else -> append(ch)
        }
      }
      append('"')
    }
  }
}
