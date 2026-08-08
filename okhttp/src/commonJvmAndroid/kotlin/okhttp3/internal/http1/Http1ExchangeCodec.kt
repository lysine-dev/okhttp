/*
 * Copyright (C) 2012 The Android Open Source Project
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
package okhttp3.internal.http1

import java.io.EOFException
import java.io.IOException
import java.net.ProtocolException
import java.util.concurrent.TimeUnit.MILLISECONDS
import okhttp3.Headers
import okhttp3.Headers.Companion.headersOf
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.internal.checkOffsetAndCount
import okhttp3.internal.connection.BufferedSocket
import okhttp3.internal.discard
import okhttp3.internal.headersContentLength
import okhttp3.internal.http.ExchangeCodec
import okhttp3.internal.http.HTTP_CONTINUE
import okhttp3.internal.http.RequestLine
import okhttp3.internal.http.StatusLine
import okhttp3.internal.http.promisesBody
import okhttp3.internal.http.receiveHeaders
import okhttp3.internal.http1.Http1ExchangeCodec.Companion.TRAILERS_RESPONSE_BODY_TRUNCATED
import okhttp3.internal.skipAll
import okio.Buffer
import okio.ForwardingTimeout
import okio.Sink
import okio.Source
import okio.Timeout

/**
 * A socket connection that can be used to send HTTP/1.1 messages. This class strictly enforces the
 * following lifecycle:
 *
 *  1. [Send request headers][writeRequest].
 *  2. Open a sink to write the request body. Either [known][newKnownLengthSink] or
 *     [chunked][newChunkedSink].
 *  3. Write to and then close that sink.
 *  4. [Read response headers][readResponseHeaders].
 *  5. Open a source to read the response body. Either [fixed-length][newFixedLengthSource],
 *     [chunked][newChunkedSource] or [unknown][newUnknownLengthSource].
 *  6. Read from and close that source.
 *
 * Exchanges that do not have a request body may skip creating and closing the request body.
 * Exchanges that do not have a response body can call
 * [newFixedLengthSource(0)][newFixedLengthSource] and may skip reading and closing that source.
 */
class Http1ExchangeCodec(
  /** The client that configures this stream. May be null for HTTPS proxy tunnels. */
  private val client: OkHttpClient?,
  override val carrier: ExchangeCodec.Carrier,
  override val socket: BufferedSocket,
) : ExchangeCodec {
  private var state = STATE_IDLE
  private val headersReader = HeadersReader(socket.source)

  private val Response.isChunked: Boolean
    get() = "chunked".equals(header("Transfer-Encoding"), ignoreCase = true)

  private val Request.isChunked: Boolean
    get() = "chunked".equals(header("Transfer-Encoding"), ignoreCase = true)

  /**
   * Trailers received when the response body became exhausted.
   *
   * If the response body was successfully read until the end, this is the headers that followed,
   * or empty headers if there were none that followed.
   *
   * If the response body was closed prematurely or failed with an error, this will be the sentinel
   * value [TRAILERS_RESPONSE_BODY_TRUNCATED]. In that case attempts to read the trailers should not
   * return the value but instead throw an exception.
   */
  private var trailers: Headers? = null

  override val isResponseComplete: Boolean
    get() = state == STATE_CLOSED

  override fun createRequestBody(
    request: Request,
    contentLength: Long,
  ): Sink =
    when {
      request.body?.isDuplex() == true -> {
        throw ProtocolException(
          "Duplex connections are not supported for HTTP/1",
        )
      }

      request.isChunked -> {
        newChunkedSink()
      }

      // Stream a request body of unknown length.
      contentLength != -1L -> {
        newKnownLengthSink()
      }

      // Stream a request body of a known length.
      else -> {
        // Stream a request body of a known length.
        throw IllegalStateException(
          "Cannot stream a request body without chunked encoding or a known content length!",
        )
      }
    }

  override fun cancel() {
    carrier.cancel()
  }

  /**
   * Prepares the HTTP headers and sends them to the server.
   *
   * For streaming requests with a body, headers must be prepared **before** the output stream has
   * been written to. Otherwise the body would need to be buffered!
   *
   * For non-streaming requests with a body, headers must be prepared **after** the output stream
   * has been written to and closed. This ensures that the `Content-Length` header field receives
   * the proper value.
   */
  override fun writeRequestHeaders(request: Request) {
    val requestLine = RequestLine.get(request, carrier.route.proxy.type())
    writeRequest(request.headers, requestLine)
  }

  override fun reportedContentLength(response: Response): Long =
    when {
      !response.promisesBody() -> 0L
      response.isChunked -> -1L
      else -> response.headersContentLength()
    }

  override fun openResponseBodySource(response: Response): Source =
    when {
      !response.promisesBody() -> {
        newFixedLengthSource(response.request.url, 0)
      }

      response.isChunked -> {
        newChunkedSource(response.request.url)
      }

      else -> {
        val contentLength = response.headersContentLength()
        if (contentLength != -1L) {
          newFixedLengthSource(response.request.url, contentLength)
        } else {
          newUnknownLengthSource(response.request.url)
        }
      }
    }

  override fun peekTrailers(): Headers? {
    if (trailers === TRAILERS_RESPONSE_BODY_TRUNCATED) {
      throw IOException("Trailers cannot be read because the response body was truncated")
    }
    check(state == STATE_READING_RESPONSE_BODY || state == STATE_CLOSED) {
      "Trailers cannot be read because the state is $state"
    }
    return trailers
  }

  override fun flushRequest() {
    socket.sink.flush()
  }

  override fun finishRequest() {
    socket.sink.flush()
  }

  /** Returns bytes of a request header for sending on an HTTP transport. */
  fun writeRequest(
    headers: Headers,
    requestLine: String,
  ) {
    check(state == STATE_IDLE) { "state: $state" }
    socket.sink.writeUtf8(requestLine).writeUtf8("\r\n")
    for (i in 0 until headers.size) {
      socket.sink
        .writeUtf8(headers.name(i))
        .writeUtf8(": ")
        .writeUtf8(headers.value(i))
        .writeUtf8("\r\n")
    }
    socket.sink.writeUtf8("\r\n")
    state = STATE_OPEN_REQUEST_BODY
  }

  override fun readResponseHeaders(expectContinue: Boolean): Response.Builder? {
    check(
      state == STATE_IDLE ||
        state == STATE_OPEN_REQUEST_BODY ||
        state == STATE_WRITING_REQUEST_BODY ||
        state == STATE_READ_RESPONSE_HEADERS,
    ) {
      "state: $state"
    }

    try {
      val statusLine = StatusLine.parse(headersReader.readLine())

      val responseBuilder =
        Response
          .Builder()
          .protocol(statusLine.protocol)
          .code(statusLine.code)
          .message(statusLine.message)
          .headers(headersReader.readHeaders())

      return when {
        expectContinue && statusLine.code == HTTP_CONTINUE -> {
          null
        }

        statusLine.code == HTTP_CONTINUE -> {
          state = STATE_READ_RESPONSE_HEADERS
          responseBuilder
        }

        statusLine.code in (102 until 200) -> {
          // Processing and Early Hints will mean a second headers are coming.
          // Treat others the same for now
          state = STATE_READ_RESPONSE_HEADERS
          responseBuilder
        }

        else -> {
          state = STATE_OPEN_RESPONSE_BODY
          responseBuilder
        }
      }
    } catch (e: EOFException) {
      // Provide more context if the server ends the stream before sending a response.
      val address =
        carrier.route.address.url
          .redact()
      throw IOException("unexpected end of stream on $address", e)
    }
  }

  private fun newChunkedSink(): Sink {
    check(state == STATE_OPEN_REQUEST_BODY) { "state: $state" }
    state = STATE_WRITING_REQUEST_BODY
    return ChunkedSink()
  }

  private fun newKnownLengthSink(): Sink {
    check(state == STATE_OPEN_REQUEST_BODY) { "state: $state" }
    state = STATE_WRITING_REQUEST_BODY
    return KnownLengthSink()
  }

  private fun newFixedLengthSource(
    url: HttpUrl,
    length: Long,
  ): Source {
    check(state == STATE_OPEN_RESPONSE_BODY) { "state: $state" }
    state = STATE_READING_RESPONSE_BODY
    return FixedLengthSource(url, length)
  }

  private fun newChunkedSource(url: HttpUrl): Source {
    check(state == STATE_OPEN_RESPONSE_BODY) { "state: $state" }
    state = STATE_READING_RESPONSE_BODY
    return ChunkedSource(url)
  }

  private fun newUnknownLengthSource(url: HttpUrl): Source {
    check(state == STATE_OPEN_RESPONSE_BODY) { "state: $state" }
    state = STATE_READING_RESPONSE_BODY
    carrier.prohibitNewExchanges()
    return UnknownLengthSource(url)
  }

  /**
   * Sets the delegate of `timeout` to [Timeout.NONE] and resets its underlying timeout
   * to the default configuration. Use this to avoid unexpected sharing of timeouts between pooled
   * connections.
   */
  private fun detachTimeout(timeout: ForwardingTimeout) {
    val oldDelegate = timeout.delegate
    timeout.setDelegate(Timeout.NONE)
    oldDelegate.clearDeadline()
    oldDelegate.clearTimeout()
  }

  /**
   * The response body from a CONNECT should be empty, but if it is not then we should consume it
   * before proceeding.
   */
  fun skipConnectBody(response: Response) {
    val contentLength = response.headersContentLength()
    if (contentLength == -1L) return
    val body = newFixedLengthSource(response.request.url, contentLength)
    body.skipAll(Int.MAX_VALUE, MILLISECONDS)
    body.close()
  }

  /** An HTTP request body. */
  private inner class KnownLengthSink : Sink {
    private val timeout = ForwardingTimeout(socket.sink.timeout())
    private var closed: Boolean = false

    override fun timeout(): Timeout = timeout

    override fun write(
      source: Buffer,
      byteCount: Long,
    ) {
      check(!closed) { "closed" }
      checkOffsetAndCount(source.size, 0, byteCount)
      socket.sink.write(source, byteCount)
    }

    override fun flush() {
      if (closed) return // Don't throw; this stream might have been closed on the caller's behalf.
      socket.sink.flush()
    }

    override fun close() {
      if (closed) return
      closed = true
      detachTimeout(timeout)
      state = STATE_READ_RESPONSE_HEADERS
    }
  }

  /**
   * An HTTP body with alternating chunk sizes and chunk bodies. It is the caller's responsibility
   * to buffer chunks; typically by using a buffered sink with this sink.
   */
  private inner class ChunkedSink : Sink {
    private val timeout = ForwardingTimeout(socket.sink.timeout())
    private var closed: Boolean = false

    override fun timeout(): Timeout = timeout

    override fun write(
      source: Buffer,
      byteCount: Long,
    ) {
      check(!closed) { "closed" }
      if (byteCount == 0L) return

      with(socket.sink) {
        writeHexadecimalUnsignedLong(byteCount)
        writeUtf8("\r\n")
        write(source, byteCount)
        writeUtf8("\r\n")
      }
    }

    @Synchronized
    override fun flush() {
      if (closed) return // Don't throw; this stream might have been closed on the caller's behalf.
      socket.sink.flush()
    }

    @Synchronized
    override fun close() {
      if (closed) return
      closed = true
      socket.sink.writeUtf8("0\r\n\r\n")
      detachTimeout(timeout)
      state = STATE_READ_RESPONSE_HEADERS
    }
  }

  private abstract inner class AbstractSource(
    val url: HttpUrl,
  ) : Source {
    protected val timeout = ForwardingTimeout(socket.source.timeout())
    protected var closed: Boolean = false

    override fun timeout(): Timeout = timeout

    override fun read(
      sink: Buffer,
      byteCount: Long,
    ): Long =
      try {
        socket.source.read(sink, byteCount)
      } catch (e: IOException) {
        carrier.prohibitNewExchanges()
        responseBodyComplete(TRAILERS_RESPONSE_BODY_TRUNCATED)
        throw e
      }

    /**
     * Closes the cache entry and makes the socket available for reuse. This should be invoked when
     * the end of the body has been reached.
     */
    fun responseBodyComplete(trailers: Headers) {
      if (state == STATE_CLOSED) return
      if (state != STATE_READING_RESPONSE_BODY) throw IllegalStateException("state: $state")

      detachTimeout(timeout)

      this@Http1ExchangeCodec.trailers = trailers
      state = STATE_CLOSED
      if (trailers.size > 0) {
        client?.cookieJar?.receiveHeaders(url, trailers)
      }
    }
  }

  /** An HTTP body with a fixed length specified in advance. */
  private inner class FixedLengthSource(
    url: HttpUrl,
    private var bytesRemaining: Long,
  ) : AbstractSource(url) {
    init {
      if (bytesRemaining == 0L) {
        responseBodyComplete(trailers = Headers.EMPTY)
      }
    }

    override fun read(
      sink: Buffer,
      byteCount: Long,
    ): Long {
      require(byteCount >= 0L) { "byteCount < 0: $byteCount" }
      check(!closed) { "closed" }
      if (bytesRemaining == 0L) return -1

      val read = super.read(sink, minOf(bytesRemaining, byteCount))
      if (read == -1L) {
        carrier.prohibitNewExchanges() // The server didn't supply the promised content length.
        val e = ProtocolException("unexpected end of stream")
        responseBodyComplete(TRAILERS_RESPONSE_BODY_TRUNCATED)
        throw e
      }

      bytesRemaining -= read
      if (bytesRemaining == 0L) {
        responseBodyComplete(trailers = Headers.EMPTY)
      }
      return read
    }

    override fun close() {
      if (closed) return

      if (bytesRemaining != 0L &&
        !discard(ExchangeCodec.DISCARD_STREAM_TIMEOUT_MILLIS, MILLISECONDS)
      ) {
        carrier.prohibitNewExchanges() // Unread bytes remain on the stream.
        responseBodyComplete(TRAILERS_RESPONSE_BODY_TRUNCATED)
      }

      closed = true
    }
  }

  /** An HTTP body with alternating chunk sizes and chunk bodies. */
  private inner class ChunkedSource(
    url: HttpUrl,
  ) : AbstractSource(url) {
    private var bytesRemainingInChunk = NO_CHUNK_YET
    private var hasMoreChunks = true

    override fun read(
      sink: Buffer,
      byteCount: Long,
    ): Long {
      require(byteCount >= 0L) { "byteCount < 0: $byteCount" }
      check(!closed) { "closed" }
      if (!hasMoreChunks) return -1

      if (bytesRemainingInChunk == 0L || bytesRemainingInChunk == NO_CHUNK_YET) {
        readChunkSize()
        if (!hasMoreChunks) return -1
      }

      val read = super.read(sink, minOf(byteCount, bytesRemainingInChunk))
      if (read == -1L) {
        carrier.prohibitNewExchanges() // The server didn't supply the promised chunk length.
        val e = ProtocolException("unexpected end of stream")
        responseBodyComplete(TRAILERS_RESPONSE_BODY_TRUNCATED)
        throw e
      }
      bytesRemainingInChunk -= read
      return read
    }

    private fun readChunkSize() {
      // Read the suffix of the previous chunk.
      if (bytesRemainingInChunk != NO_CHUNK_YET) {
        socket.source.readUtf8LineStrict()
      }
      try {
        bytesRemainingInChunk = socket.source.readHexadecimalUnsignedLong()
        val extensions = socket.source.readUtf8LineStrict().trim()
        if ((bytesRemainingInChunk < 0L) || (extensions.isNotEmpty() && !extensions.startsWith(";"))) {
          throw ProtocolException(
            "expected chunk size and optional extensions" +
              " but was \"$bytesRemainingInChunk$extensions\"",
          )
        }
      } catch (e: NumberFormatException) {
        throw ProtocolException(e.message)
      }

      if (bytesRemainingInChunk == 0L) {
        hasMoreChunks = false
        val trailers = headersReader.readHeaders()
        responseBodyComplete(trailers)
      }
    }

    override fun close() {
      if (closed) return
      if (hasMoreChunks &&
        !discard(ExchangeCodec.DISCARD_STREAM_TIMEOUT_MILLIS, MILLISECONDS)
      ) {
        carrier.prohibitNewExchanges() // Unread bytes remain on the stream.
        responseBodyComplete(TRAILERS_RESPONSE_BODY_TRUNCATED)
      }
      closed = true
    }
  }

  /** An HTTP message body terminated by the end of the underlying stream. */
  private inner class UnknownLengthSource(
    url: HttpUrl,
  ) : AbstractSource(url) {
    private var inputExhausted: Boolean = false

    override fun read(
      sink: Buffer,
      byteCount: Long,
    ): Long {
      require(byteCount >= 0L) { "byteCount < 0: $byteCount" }
      check(!closed) { "closed" }
      if (inputExhausted) return -1

      val read = super.read(sink, byteCount)
      if (read == -1L) {
        inputExhausted = true
        responseBodyComplete(trailers = Headers.EMPTY)
        return -1
      }
      return read
    }

    override fun close() {
      if (closed) return
      if (!inputExhausted) {
        responseBodyComplete(TRAILERS_RESPONSE_BODY_TRUNCATED)
      }
      closed = true
    }
  }

  companion object {
    private const val NO_CHUNK_YET = -1L

    private const val STATE_IDLE = 0 // Idle connections are ready to write request headers.
    private const val STATE_OPEN_REQUEST_BODY = 1
    private const val STATE_WRITING_REQUEST_BODY = 2
    private const val STATE_READ_RESPONSE_HEADERS = 3
    private const val STATE_OPEN_RESPONSE_BODY = 4
    private const val STATE_READING_RESPONSE_BODY = 5
    private const val STATE_CLOSED = 6

    private val TRAILERS_RESPONSE_BODY_TRUNCATED = headersOf("OkHttp-Response-Body", "Truncated")
  }
}
