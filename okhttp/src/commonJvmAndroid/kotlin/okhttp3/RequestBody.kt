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

import java.io.File
import java.io.FileDescriptor
import java.io.FileInputStream
import java.io.IOException
import okhttp3.internal.checkOffsetAndCount
import okhttp3.internal.chooseCharset
import okio.BufferedSink
import okio.ByteString
import okio.FileSystem
import okio.HashingSink
import okio.Path
import okio.blackholeSink
import okio.buffer
import okio.source

abstract class RequestBody {
  /** Returns the Content-Type header for this body. */
  abstract fun contentType(): MediaType?

  /**
   * Returns the number of bytes that will be written to sink in a call to [writeTo],
   * or -1 if that count is unknown.
   */
  @Throws(IOException::class)
  open fun contentLength(): Long = -1L

  /** Writes the content of this request to [sink]. This should not close [sink]. */
  @Throws(IOException::class)
  abstract fun writeTo(sink: BufferedSink)

  /**
   * A duplex request body is special in how it is **transmitted** on the network and
   * in the **API contract** between OkHttp and the application.
   *
   * This method returns false unless it is overridden by a subclass.
   *
   * ### Duplex Transmission
   *
   * With regular HTTP calls the request always completes sending before the response may begin
   * receiving. With duplex the request and response may be interleaved! That is, request body bytes
   * may be sent after response headers or body bytes have been received.
   *
   * Though any call may be initiated as a duplex call, only web servers that are specially
   * designed for this nonstandard interaction will use it. As of 2019-01, the only widely-used
   * implementation of this pattern is [gRPC][grpc].
   *
   * Because the encoding of interleaved data is not well-defined for HTTP/1, duplex request
   * bodies may only be used with HTTP/2. Calls to HTTP/1 servers will fail before the HTTP request
   * is transmitted. If you cannot ensure that your client and server both support HTTP/2, do not
   * use this feature.
   *
   * ### Duplex APIs
   *
   * With regular request bodies it is not legal to write bytes to the sink passed to
   * [RequestBody.writeTo] after that method returns. For duplex requests bodies that condition is
   * lifted. Such writes occur on an application-provided thread and may occur concurrently with
   * reads of the [ResponseBody]. For duplex request bodies, [writeTo] should return
   * quickly, possibly by handing off the provided request body to another thread to perform
   * writing.
   *
   * [grpc]: https://github.com/grpc/grpc/blob/master/doc/PROTOCOL-HTTP2.md
   */
  open fun isDuplex(): Boolean = false

  /**
   * Returns true if this body expects at most one call to [writeTo] and can be transmitted
   * at most once. This is typically used when writing the request body is destructive and it is not
   * possible to recreate the request body after it has been sent.
   *
   * This method returns false unless it is overridden by a subclass.
   *
   * By default OkHttp will attempt to retransmit request bodies when the original request fails
   * due to any of:
   *
   *  * A stale connection. The request was made on a reused connection and that reused connection
   *    has since been closed by the server.
   *  * A client timeout (HTTP 408).
   *  * A authorization challenge (HTTP 401 and 407) that is satisfied by the [Authenticator].
   *  * A retryable server failure (HTTP 503 with a `Retry-After: 0` response header).
   *  * A misdirected request (HTTP 421) on a coalesced connection.
   */
  open fun isOneShot(): Boolean = false

  /**
   * Returns the SHA-256 hash of this [RequestBody]
   */
  @Throws(IOException::class)
  fun sha256(): ByteString {
    val hashingSink = HashingSink.sha256(blackholeSink())
    hashingSink.buffer().use {
      this.writeTo(it)
    }
    return hashingSink.hash
  }

  companion object {
    /** Empty request body with no content-type. */
    @JvmField
    val EMPTY: RequestBody = ByteString.EMPTY.toRequestBody()

    /**
     * Returns a new request body that transmits this string. If [contentType] is non-null and lacks
     * a charset, this will use UTF-8.
     */
    @JvmStatic
    @JvmName("create")
    fun String.toRequestBody(contentType: MediaType? = null): RequestBody {
      val (charset, finalContentType) = contentType.chooseCharset()
      val bytes = toByteArray(charset)
      return bytes.toRequestBody(finalContentType, 0, bytes.size)
    }

    @JvmStatic
    @JvmName("create")
    fun ByteString.toRequestBody(contentType: MediaType? = null): RequestBody =
      object : RequestBody() {
        override fun contentType() = contentType

        override fun contentLength() = size.toLong()

        override fun writeTo(sink: BufferedSink) {
          sink.write(this@toRequestBody)
        }
      }

    /** Returns a new request body that transmits this. */
    @JvmStatic
    @JvmName("create")
    fun FileDescriptor.toRequestBody(contentType: MediaType? = null): RequestBody =
      object : RequestBody() {
        override fun contentType() = contentType

        override fun isOneShot(): Boolean = true

        override fun writeTo(sink: BufferedSink) {
          FileInputStream(this@toRequestBody).use {
            sink.buffer.writeAll(it.source())
          }
        }
      }

    /** Returns a new request body that transmits this. */
    @JvmOverloads
    @JvmStatic
    @JvmName("create")
    fun ByteArray.toRequestBody(
      contentType: MediaType? = null,
      offset: Int = 0,
      byteCount: Int = size,
    ): RequestBody {
      checkOffsetAndCount(size.toLong(), offset.toLong(), byteCount.toLong())
      return object : RequestBody() {
        override fun contentType() = contentType

        override fun contentLength() = byteCount.toLong()

        override fun writeTo(sink: BufferedSink) {
          sink.write(this@toRequestBody, offset, byteCount)
        }
      }
    }

    /** Returns a new request body that transmits the content of this. */
    @JvmStatic
    @JvmName("create")
    fun File.asRequestBody(contentType: MediaType? = null): RequestBody =
      object : RequestBody() {
        override fun contentType() = contentType

        override fun contentLength() = length()

        override fun writeTo(sink: BufferedSink) {
          source().use { source -> sink.writeAll(source) }
        }
      }

    /** Returns a new request body that transmits the content of this. */
    @JvmStatic
    @JvmName("create")
    fun Path.asRequestBody(
      fileSystem: FileSystem,
      contentType: MediaType? = null,
    ): RequestBody =
      object : RequestBody() {
        override fun contentType() = contentType

        override fun contentLength() = fileSystem.metadata(this@asRequestBody).size ?: -1

        override fun writeTo(sink: BufferedSink) {
          fileSystem.source(this@asRequestBody).use { source -> sink.writeAll(source) }
        }
      }

    @JvmStatic
    @Deprecated(
      message = "Moved to extension function. Put the 'content' argument first to fix Java",
      replaceWith =
        ReplaceWith(
          expression = "content.toRequestBody(contentType)",
          imports = ["okhttp3.RequestBody.Companion.toRequestBody"],
        ),
      level = DeprecationLevel.WARNING,
    )
    fun create(
      contentType: MediaType?,
      content: String,
    ): RequestBody = content.toRequestBody(contentType)

    @JvmStatic
    @Deprecated(
      message = "Moved to extension function. Put the 'content' argument first to fix Java",
      replaceWith =
        ReplaceWith(
          expression = "content.toRequestBody(contentType)",
          imports = ["okhttp3.RequestBody.Companion.toRequestBody"],
        ),
      level = DeprecationLevel.WARNING,
    )
    fun create(
      contentType: MediaType?,
      content: ByteString,
    ): RequestBody = content.toRequestBody(contentType)

    @JvmOverloads
    @JvmStatic
    @Deprecated(
      message = "Moved to extension function. Put the 'content' argument first to fix Java",
      replaceWith =
        ReplaceWith(
          expression = "content.toRequestBody(contentType, offset, byteCount)",
          imports = ["okhttp3.RequestBody.Companion.toRequestBody"],
        ),
      level = DeprecationLevel.WARNING,
    )
    fun create(
      contentType: MediaType?,
      content: ByteArray,
      offset: Int = 0,
      byteCount: Int = content.size,
    ): RequestBody = content.toRequestBody(contentType, offset, byteCount)

    @JvmStatic
    @Deprecated(
      message = "Moved to extension function. Put the 'file' argument first to fix Java",
      replaceWith =
        ReplaceWith(
          expression = "file.asRequestBody(contentType)",
          imports = ["okhttp3.RequestBody.Companion.asRequestBody"],
        ),
      level = DeprecationLevel.WARNING,
    )
    fun create(
      contentType: MediaType?,
      file: File,
    ): RequestBody = file.asRequestBody(contentType)
  }
}
