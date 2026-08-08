/*
 * Copyright (C) 2019 Square, Inc.
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
package okhttp3.internal.connection

import java.io.IOException
import java.net.ProtocolException
import okhttp3.EventListener
import okhttp3.Headers
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody
import okhttp3.internal.http.ExchangeCodec
import okhttp3.internal.http.RealResponseBody
import okio.Buffer
import okio.ForwardingSink
import okio.ForwardingSource
import okio.Sink
import okio.Socket
import okio.Source
import okio.buffer

/**
 * Transmits a single HTTP request and a response pair. This layers connection management and events
 * on [ExchangeCodec], which handles the actual I/O.
 */
class Exchange(
  internal val call: RealCall,
  internal val finder: ExchangeFinder,
  private val codec: ExchangeCodec,
) {
  /** True if the request body need not complete before the response body starts. */
  internal var isDuplex: Boolean = false
    private set

  /** True if there was an exception on the connection to the peer. */
  internal var hasFailure: Boolean = false
    private set

  internal val connection: RealConnection
    get() = codec.carrier as? RealConnection ?: error("no connection for CONNECT tunnels")

  internal val isCoalescedConnection: Boolean
    get() = finder.routePlanner.address.url.host != codec.carrier.route.address.url.host

  @Throws(IOException::class)
  fun writeRequestHeaders(request: Request) {
    try {
      call.eventListener.requestHeadersStart(call)
      codec.writeRequestHeaders(request)
      call.eventListener.requestHeadersEnd(call, request)
    } catch (e: IOException) {
      call.eventListener.requestFailed(call, e)
      trackFailure(e)
      throw e
    }
  }

  @Throws(IOException::class)
  fun createRequestBody(
    request: Request,
    duplex: Boolean,
  ): Sink {
    this.isDuplex = duplex
    val contentLength = request.body!!.contentLength()
    call.eventListener.requestBodyStart(call)
    val rawRequestBody = codec.createRequestBody(request, contentLength)
    return RequestBodySink(
      delegate = rawRequestBody,
      contentLength = contentLength,
      isSocket = false,
    )
  }

  @Throws(IOException::class)
  fun flushRequest() {
    try {
      codec.flushRequest()
    } catch (e: IOException) {
      call.eventListener.requestFailed(call, e)
      trackFailure(e)
      throw e
    }
  }

  @Throws(IOException::class)
  fun finishRequest() {
    try {
      codec.finishRequest()
    } catch (e: IOException) {
      call.eventListener.requestFailed(call, e)
      trackFailure(e)
      throw e
    }
  }

  fun responseHeadersStart() {
    call.eventListener.responseHeadersStart(call)
  }

  @Throws(IOException::class)
  fun readResponseHeaders(expectContinue: Boolean): Response.Builder? {
    try {
      val result = codec.readResponseHeaders(expectContinue)
      result?.initExchange(this)
      return result
    } catch (e: IOException) {
      call.eventListener.responseFailed(call, e)
      trackFailure(e)
      throw e
    }
  }

  fun responseHeadersEnd(response: Response) {
    call.eventListener.responseHeadersEnd(call, response)
  }

  @Throws(IOException::class)
  fun openResponseBody(response: Response): ResponseBody {
    try {
      val contentType = response.header("Content-Type")
      val contentLength = codec.reportedContentLength(response)
      val rawSource = codec.openResponseBodySource(response)
      val source =
        ResponseBodySource(
          delegate = rawSource,
          contentLength = contentLength,
          isSocket = false,
        )
      return RealResponseBody(contentType, contentLength, source.buffer())
    } catch (e: IOException) {
      call.eventListener.responseFailed(call, e)
      trackFailure(e)
      throw e
    }
  }

  @Throws(IOException::class)
  fun peekTrailers(): Headers? = codec.peekTrailers()

  fun upgradeToSocket(): Socket {
    call.upgradeToSocket()
    (codec.carrier as RealConnection).useAsSocket()

    return object : Socket {
      override fun cancel() {
        this@Exchange.cancel()
      }

      override val sink =
        RequestBodySink(
          delegate = codec.socket.sink,
          contentLength = -1L,
          isSocket = true,
        )
      override val source =
        ResponseBodySource(
          delegate = codec.socket.source,
          contentLength = -1L,
          isSocket = true,
        )
    }
  }

  fun noNewExchangesOnConnection() {
    codec.carrier.prohibitNewExchanges()
  }

  fun cancel() {
    codec.cancel()
  }

  /**
   * Revoke this exchange's access to streams. This is necessary when a follow-up request is
   * required but the preceding exchange hasn't completed yet.
   */
  fun detachWithViolence() {
    codec.cancel()
    call.messageDone(
      exchange = this,
      requestDone = true,
      responseDone = true,
      socketSinkDone = true,
      socketSourceDone = true,
      e = null,
    )
  }

  private fun trackFailure(e: IOException) {
    hasFailure = true
    codec.carrier.trackFailure(call, e)
  }

  /** If [e] is non-null, this will return a non-null value. */
  fun bodyComplete(
    bytesRead: Long = -1L,
    isSocket: Boolean,
    responseDone: Boolean = false,
    requestDone: Boolean = false,
    e: IOException?,
  ): IOException? {
    if (e != null) {
      trackFailure(e)
    }
    if (requestDone) {
      if (e != null) {
        call.eventListener.requestFailed(call, e)
      } else {
        call.eventListener.requestBodyEnd(call, bytesRead)
      }
    }
    if (responseDone) {
      if (e != null) {
        call.eventListener.responseFailed(call, e)
      } else {
        call.eventListener.responseBodyEnd(call, bytesRead)
      }
    }
    return call.messageDone(
      exchange = this,
      requestDone = requestDone && !isSocket,
      responseDone = responseDone && !isSocket,
      socketSinkDone = requestDone && isSocket,
      socketSourceDone = responseDone && isSocket,
      e = e,
    )
  }

  fun noRequestBody() {
    call.messageDone(
      exchange = this,
      requestDone = true,
      e = null,
    )
  }

  /** A request body that fires events when it completes. */
  private inner class RequestBodySink(
    delegate: Sink,
    /** The exact number of bytes to be written, or -1L if that is unknown. */
    private val contentLength: Long,
    private val isSocket: Boolean,
  ) : ForwardingSink(delegate) {
    private var completed = false
    private var bytesReceived = 0L
    private var invokeStartEvent = isSocket
    private var closed = false

    @Throws(IOException::class)
    override fun write(
      source: Buffer,
      byteCount: Long,
    ) {
      check(!closed) { "closed" }
      if (contentLength != -1L && bytesReceived + byteCount > contentLength) {
        throw ProtocolException(
          "expected $contentLength bytes but received ${bytesReceived + byteCount}",
        )
      }
      try {
        if (invokeStartEvent) {
          invokeStartEvent = false
          call.eventListener.requestBodyStart(call)
        }
        super.write(source, byteCount)
        this.bytesReceived += byteCount
      } catch (e: IOException) {
        throw complete(e)!!
      }
    }

    @Throws(IOException::class)
    override fun flush() {
      try {
        super.flush()
      } catch (e: IOException) {
        throw complete(e)!!
      }
    }

    @Throws(IOException::class)
    override fun close() {
      if (closed) return
      closed = true
      if (contentLength != -1L && bytesReceived != contentLength) {
        throw ProtocolException("unexpected end of stream")
      }
      try {
        super.close()
        complete(null)
      } catch (e: IOException) {
        throw complete(e)!!
      }
    }

    /** If [e] is non-null, this will return a non-null value. */
    private fun complete(e: IOException?): IOException? {
      if (completed) return e
      completed = true
      return bodyComplete(
        bytesRead = bytesReceived,
        isSocket = isSocket,
        requestDone = true,
        e = e,
      )
    }
  }

  /** A response body that fires events when it completes. */
  internal inner class ResponseBodySource(
    delegate: Source,
    private val contentLength: Long,
    private val isSocket: Boolean,
  ) : ForwardingSource(delegate) {
    private var bytesReceived = 0L
    private var invokeStartEvent = true
    private var completed = false
    private var closed = false

    init {
      if (contentLength == 0L) {
        complete(null)
      }
    }

    @Throws(IOException::class)
    override fun read(
      sink: Buffer,
      byteCount: Long,
    ): Long {
      check(!closed) { "closed" }
      try {
        val read = delegate.read(sink, byteCount)

        if (invokeStartEvent) {
          invokeStartEvent = false
          call.eventListener.responseBodyStart(call)
        }

        if (read == -1L) {
          complete(null)
          return -1L
        }

        val newBytesReceived = bytesReceived + read
        if (contentLength != -1L && newBytesReceived > contentLength) {
          throw ProtocolException("expected $contentLength bytes but received $newBytesReceived")
        }

        bytesReceived = newBytesReceived
        if (codec.isResponseComplete) {
          complete(null)
        }

        return read
      } catch (e: IOException) {
        throw complete(e)!!
      }
    }

    @Throws(IOException::class)
    override fun close() {
      if (closed) return
      closed = true
      try {
        super.close()
        complete(null)
      } catch (e: IOException) {
        throw complete(e)!!
      }
    }

    /** If [e] is non-null, this will return a non-null value. */
    fun complete(e: IOException?): IOException? {
      if (completed) return e
      completed = true
      // If the body is closed without reading any bytes send a responseBodyStart() now.
      if (e == null && invokeStartEvent) {
        invokeStartEvent = false
        call.eventListener.responseBodyStart(call)
      }
      return bodyComplete(
        bytesRead = bytesReceived,
        isSocket = isSocket,
        responseDone = true,
        e = e,
      )
    }
  }
}
