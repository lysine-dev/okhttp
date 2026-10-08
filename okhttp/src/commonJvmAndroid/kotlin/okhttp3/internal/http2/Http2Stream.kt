/*
 * Copyright (C) 2011 The Android Open Source Project
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
package okhttp3.internal.http2

import java.io.EOFException
import java.io.IOException
import java.io.InterruptedIOException
import java.net.SocketTimeoutException
import java.util.ArrayDeque
import okhttp3.Headers
import okhttp3.internal.concurrent.Lockable
import okhttp3.internal.concurrent.assertLockNotHeld
import okhttp3.internal.concurrent.notifyAll
import okhttp3.internal.concurrent.wait
import okhttp3.internal.concurrent.withLock
import okhttp3.internal.http2.flowcontrol.WindowCounter
import okhttp3.internal.toHeaderList
import okio.AsyncTimeout
import okio.Buffer
import okio.BufferedSource
import okio.Sink
import okio.Socket
import okio.Source
import okio.Timeout

/** A logical bidirectional stream. */
@Suppress("NAME_SHADOWING")
class Http2Stream internal constructor(
  val id: Int,
  val connection: Http2Connection,
  outFinished: Boolean,
  inFinished: Boolean,
  headers: Headers?,
) : Lockable,
  Socket {
  // Internal state is guarded by `this`. No long-running or potentially blocking operations are
  // performed while the lock is held.

  /** The bytes consumed and acknowledged by the stream. */
  val readBytes: WindowCounter = WindowCounter(id)

  /** The total number of bytes produced by the application. */
  var writeBytesTotal = 0L
    internal set

  /** The total number of bytes permitted to be produced by incoming `WINDOW_UPDATE` frame. */
  var writeBytesMaximum: Long = connection.peerSettings.initialWindowSize.toLong()
    internal set

  /** Received headers yet to be [taken][takeHeaders]. */
  private val headersQueue = ArrayDeque<Headers>()

  /** True if response headers have been sent or received. */
  private var hasResponseHeaders: Boolean = false

  override val source =
    FramingSource(
      maxByteCount = connection.okHttpSettings.initialWindowSize.toLong(),
      finished = inFinished,
    )
  override val sink =
    FramingSink(
      finished = outFinished,
    )
  internal val readTimeout = StreamTimeout()
  internal val writeTimeout = StreamTimeout()

  /**
   * The reason why this stream was closed, or null if it closed normally or has not yet been
   * closed.
   *
   * If there are multiple reasons to abnormally close this stream (such as both peers closing it
   * near-simultaneously) then this is the first reason known to this peer.
   */
  internal var errorCode: ErrorCode? = null
    get() = withLock { field }

  /** The exception that explains [errorCode]. Null if no exception was provided. */
  internal var errorException: IOException? = null

  init {
    if (headers != null) {
      check(!isLocallyInitiated) { "locally-initiated streams shouldn't have headers yet" }
      headersQueue += headers
    } else {
      check(isLocallyInitiated) { "remotely-initiated streams should have headers" }
    }
  }

  /**
   * Returns true if this stream is open. A stream is open until either:
   *
   *  * A `SYN_RESET` frame abnormally terminates the stream.
   *  * Both input and output streams have transmitted all data and headers.
   *
   * Note that the input stream may continue to yield data even after a stream reports itself as
   * not open. This is because input data is buffered.
   */
  val isOpen: Boolean
    get() {
      withLock {
        if (errorCode != null) {
          return false
        }
        if ((source.finished || source.closed) &&
          (sink.finished || sink.closed) &&
          hasResponseHeaders
        ) {
          return false
        }
        return true
      }
    }

  /** Returns true if this stream was created by this peer. */
  val isLocallyInitiated: Boolean
    get() {
      val streamIsClient = (id and 1) == 1
      return connection.client == streamIsClient
    }

  val isSourceComplete: Boolean
    get() = withLock { source.finished && source.readBuffer.exhausted() }

  /**
   * Removes and returns the stream's received response headers, blocking if necessary until headers
   * have been received. If the returned list contains multiple blocks of headers the blocks will be
   * delimited by 'null'.
   *
   * @param callerIsIdle true if the caller isn't sending any more bytes until the peer responds.
   *     This is true after a `Expect-Continue` request, false for duplex requests, and false for
   *     all other requests.
   */
  @Throws(IOException::class)
  fun takeHeaders(callerIsIdle: Boolean = false): Headers {
    withLock {
      while (headersQueue.isEmpty() && errorCode == null) {
        val doReadTimeout = callerIsIdle || doReadTimeout()
        if (doReadTimeout) {
          readTimeout.enter()
        }
        try {
          waitForIo()
        } finally {
          if (doReadTimeout) {
            readTimeout.exitAndThrowIfTimedOut()
          }
        }
      }
      if (headersQueue.isNotEmpty()) {
        return headersQueue.removeFirst()
      }
      throw errorException ?: StreamResetException(errorCode!!)
    }
  }

  /**
   * Returns the trailers if they're immediately available.
   */
  @Throws(IOException::class)
  fun peekTrailers(): Headers? {
    withLock {
      if (source.finished && source.receiveBuffer.exhausted() && source.readBuffer.exhausted()) {
        return source.trailers ?: Headers.EMPTY
      }
      if (errorCode != null) {
        throw errorException ?: StreamResetException(errorCode!!)
      }
      return null
    }
  }

  /**
   * Sends a reply to an incoming stream.
   *
   * @param outFinished true to eagerly finish the output stream to send data to the remote peer.
   *     Corresponds to `FLAG_FIN`.
   * @param flushHeaders true to force flush the response headers. This should be true unless the
   *     response body exists and will be written immediately.
   */
  @Throws(IOException::class)
  fun writeHeaders(
    responseHeaders: List<Header>,
    outFinished: Boolean,
    flushHeaders: Boolean,
  ) {
    assertLockNotHeld()

    var flushHeaders = flushHeaders
    withLock {
      this.hasResponseHeaders = true
      if (outFinished) {
        this.sink.finished = true
        notifyAll() // Because doReadTimeout() may have changed.
      }
    }

    // Only DATA frames are subject to flow-control. Transmit the HEADER frame if the connection
    // flow-control window is fully depleted.
    if (!flushHeaders) {
      withLock {
        flushHeaders = (connection.writeBytesTotal >= connection.writeBytesMaximum)
      }
    }

    connection.writeHeaders(id, outFinished, responseHeaders)

    if (flushHeaders) {
      connection.flush()
    }
  }

  fun enqueueTrailers(trailers: Headers) {
    withLock {
      check(!sink.finished) { "already finished" }
      require(trailers.size != 0) { "trailers.size() == 0" }
      this.sink.trailers = trailers
    }
  }

  fun readTimeout(): Timeout = readTimeout

  fun writeTimeout(): Timeout = writeTimeout

  /**
   * Abnormally terminate this stream. This blocks until the `RST_STREAM` frame has been
   * transmitted.
   */
  @Throws(IOException::class)
  fun close(
    rstStatusCode: ErrorCode,
    errorException: IOException?,
  ) {
    if (!closeInternal(rstStatusCode, errorException)) {
      return // Already closed.
    }
    connection.writeSynReset(id, rstStatusCode)
  }

  override fun cancel() {
    closeLater(ErrorCode.CANCEL)
  }

  /**
   * Abnormally terminate this stream. This enqueues a `RST_STREAM` frame and returns immediately.
   */
  fun closeLater(errorCode: ErrorCode) {
    if (!closeInternal(errorCode, null)) {
      return // Already closed.
    }
    connection.writeSynResetLater(id, errorCode)
  }

  /** Returns true if this stream was closed. */
  private fun closeInternal(
    errorCode: ErrorCode,
    errorException: IOException?,
  ): Boolean {
    assertLockNotHeld()

    withLock {
      if (this.errorCode != null) {
        return false
      }
      this.errorCode = errorCode
      this.errorException = errorException
      notifyAll()
      if (source.finished && sink.finished) {
        return false
      }
    }

    // Record the reset before removing the stream so the reader thread never sees an unknown stream.
    connection.recordRstStream(id)
    connection.removeStream(id)
    return true
  }

  @Throws(IOException::class)
  fun receiveData(
    source: BufferedSource,
    length: Int,
  ) {
    assertLockNotHeld()

    this.source.receive(source, length.toLong())
  }

  /** Accept headers from the network and store them until the client calls [takeHeaders]. */
  fun receiveHeaders(
    headers: Headers,
    inFinished: Boolean,
  ) {
    assertLockNotHeld()

    val open: Boolean
    withLock {
      if (!hasResponseHeaders ||
        headers[Header.RESPONSE_STATUS_UTF8] != null ||
        headers[Header.TARGET_METHOD_UTF8] != null
      ) {
        hasResponseHeaders = true
        headersQueue += headers
      } else {
        this.source.trailers = headers
      }
      if (inFinished) {
        this.source.finished = true
      }
      open = isOpen
      notifyAll()
    }
    if (!open) {
      connection.removeStream(id)
    }
  }

  fun receiveRstStream(errorCode: ErrorCode) {
    withLock {
      if (this.errorCode == null) {
        this.errorCode = errorCode
        notifyAll()
      }
    }
  }

  /**
   * Returns true if read timeouts should be enforced while reading response headers or body bytes.
   * We always do timeouts in the HTTP server role. For clients, we only do timeouts after the
   * request is transmitted. This is only interesting for duplex calls where the request and
   * response may be interleaved.
   *
   * Read this value only once for each enter/exit pair because its value can change.
   */
  private fun doReadTimeout() = !connection.client || sink.closed || sink.finished

  /**
   * A source that reads the incoming data frames of a stream. Although this class uses
   * synchronization to safely receive incoming data frames, it is not intended for use by multiple
   * readers.
   */
  inner class FramingSource internal constructor(
    /** Maximum number of bytes to buffer before reporting a flow control error. */
    private val maxByteCount: Long,
    /**
     * True if either side has cleanly shut down this stream. We will receive no more bytes beyond
     * those already in the buffer.
     */
    internal var finished: Boolean,
  ) : Source {
    /** Buffer to receive data from the network into. Only accessed by the reader thread. */
    val receiveBuffer = Buffer()

    /** Buffer with readable data. Guarded by Http2Stream.this. */
    val readBuffer = Buffer()

    /**
     * Received trailers. Null unless the server has provided trailers. Undefined until the stream
     * is exhausted. Guarded by Http2Stream.this.
     */
    var trailers: Headers? = null

    /** True if the caller has closed this stream. */
    internal var closed: Boolean = false

    @Throws(IOException::class)
    override fun read(
      sink: Buffer,
      byteCount: Long,
    ): Long {
      require(byteCount >= 0L) { "byteCount < 0: $byteCount" }

      while (true) {
        var tryAgain = false
        var readBytesDelivered = -1L
        var errorExceptionToDeliver: IOException? = null

        // 1. Decide what to do in a synchronized block.

        withLock {
          val doReadTimeout = doReadTimeout()
          if (doReadTimeout) {
            readTimeout.enter()
          }
          try {
            if (errorCode != null && !finished) {
              // Prepare to deliver an error.
              errorExceptionToDeliver = errorException ?: StreamResetException(errorCode!!)
            }

            if (closed) {
              throw IOException("stream closed")
            } else if (readBuffer.size > 0L) {
              // Prepare to read bytes. Start by moving them to the caller's buffer.
              readBytesDelivered = readBuffer.read(sink, minOf(byteCount, readBuffer.size))
              readBytes.update(total = readBytesDelivered)

              val unacknowledgedBytesRead = readBytes.unacknowledged
              if (errorExceptionToDeliver == null &&
                unacknowledgedBytesRead >= connection.okHttpSettings.initialWindowSize / 2
              ) {
                // Flow control: notify the peer that we're ready for more data! Only send a
                // WINDOW_UPDATE if the stream isn't in error.
                connection.writeWindowUpdateLater(id, unacknowledgedBytesRead)
                readBytes.update(acknowledged = unacknowledgedBytesRead)
              }
            } else if (!finished && errorExceptionToDeliver == null) {
              // Nothing to do. Wait until that changes then try again.
              waitForIo()
              tryAgain = true
            }
          } finally {
            if (doReadTimeout) {
              readTimeout.exitAndThrowIfTimedOut()
            }
          }
        }
        connection.flowControlListener.receivingStreamWindowChanged(id, readBytes, readBuffer.size)

        // 2. Do it outside of the synchronized block and timeout.

        if (tryAgain) {
          continue
        }

        if (readBytesDelivered != -1L) {
          return readBytesDelivered
        }

        if (errorExceptionToDeliver != null) {
          // We defer throwing the exception until now so that we can refill the connection
          // flow-control window. This is necessary because we don't transmit window updates until
          // the application reads the data. If we throw this prior to updating the connection
          // flow-control window, we risk having it go to 0 preventing the server from sending data.
          throw errorExceptionToDeliver!!
        }

        return -1L // This source is exhausted.
      }
    }

    private fun updateConnectionFlowControl(read: Long) {
      assertLockNotHeld()

      connection.updateConnectionFlowControl(read)
    }

    /**
     * Accept bytes on the connection's reader thread. This function avoids holding locks while it
     * performs blocking reads for the incoming bytes.
     */
    @Throws(IOException::class)
    internal fun receive(
      source: BufferedSource,
      byteCount: Long,
    ) {
      assertLockNotHeld()

      var remainingByteCount = byteCount

      while (remainingByteCount > 0L) {
        val finished: Boolean
        val flowControlError: Boolean
        withLock {
          finished = this.finished
          flowControlError = remainingByteCount + readBuffer.size > maxByteCount
        }

        // If the peer sends more data than we can handle, discard it and close the connection.
        if (flowControlError) {
          source.skip(remainingByteCount)
          closeLater(ErrorCode.FLOW_CONTROL_ERROR)
          return
        }

        // Discard data received after the stream is finished. It's probably a benign race.
        if (finished) {
          source.skip(remainingByteCount)
          return
        }

        // Fill the receive buffer without holding any locks.
        val read = source.read(receiveBuffer, remainingByteCount)
        if (read == -1L) throw EOFException()
        remainingByteCount -= read

        // Move the received data to the read buffer to the reader can read it. If this source has
        // been closed since this read began we must discard the incoming data and tell the
        // connection we've done so.
        withLock {
          if (closed) {
            receiveBuffer.clear()
          } else {
            val wasEmpty = readBuffer.size == 0L
            readBuffer.writeAll(receiveBuffer)
            if (wasEmpty) {
              notifyAll()
            }
          }
        }
      }

      // Update the connection flow control, as this is a shared resource.
      // Even if our stream doesn't need more data, others might.
      // But delay updating the stream flow control until that stream has been
      // consumed
      updateConnectionFlowControl(byteCount)

      // Notify that buffer size changed
      connection.flowControlListener.receivingStreamWindowChanged(id, readBytes, readBuffer.size)
    }

    override fun timeout(): Timeout = readTimeout

    @Throws(IOException::class)
    override fun close() {
      val bytesDiscarded: Long
      withLock {
        closed = true
        bytesDiscarded = readBuffer.size
        readBuffer.clear()
        notifyAll() // TODO(jwilson): Unnecessary?
      }
      if (bytesDiscarded > 0L) {
        updateConnectionFlowControl(bytesDiscarded)
      }
      cancelStreamIfNecessary()
    }
  }

  @Throws(IOException::class)
  internal fun cancelStreamIfNecessary() {
    assertLockNotHeld()

    val open: Boolean
    val cancel: Boolean
    withLock {
      cancel = !source.finished && source.closed && (sink.finished || sink.closed)
      open = isOpen
    }
    if (cancel) {
      // RST this stream to prevent additional data from being sent. This is safe because the input
      // stream is closed (we won't use any further bytes) and the output stream is either finished
      // or closed (so RSTing both streams doesn't cause harm).
      this@Http2Stream.close(ErrorCode.CANCEL, null)
    } else if (!open) {
      connection.removeStream(id)
    }
  }

  /** A sink that writes outgoing data frames of a stream. This class is not thread safe. */
  inner class FramingSink(
    /** True if either side has cleanly shut down this stream. We shall send no more bytes. */
    var finished: Boolean = false,
  ) : Sink {
    /**
     * Buffer of outgoing data. This batches writes of small writes into this sink as larges frames
     * written to the outgoing connection. Batching saves the (small) framing overhead.
     */
    private val sendBuffer = Buffer()

    /** Trailers to send at the end of the stream. */
    var trailers: Headers? = null

    var closed: Boolean = false

    @Throws(IOException::class)
    override fun write(
      source: Buffer,
      byteCount: Long,
    ) {
      assertLockNotHeld()

      sendBuffer.write(source, byteCount)
      while (sendBuffer.size >= EMIT_BUFFER_SIZE) {
        emitFrame(false)
      }
    }

    /**
     * Emit a single data frame to the connection. The frame's size be limited by this stream's
     * write window. This method will block until the write window is nonempty.
     */
    @Throws(IOException::class)
    private fun emitFrame(outFinishedOnLastFrame: Boolean) {
      val toWrite: Long
      val outFinished: Boolean
      withLock {
        writeTimeout.enter()
        try {
          while (writeBytesTotal >= writeBytesMaximum &&
            !finished &&
            !closed &&
            errorCode == null
          ) {
            waitForIo() // Wait until we receive a WINDOW_UPDATE for this stream.
          }
        } finally {
          writeTimeout.exitAndThrowIfTimedOut()
        }

        checkOutNotClosed() // Kick out if the stream was reset or closed while waiting.
        toWrite = minOf(writeBytesMaximum - writeBytesTotal, sendBuffer.size)
        writeBytesTotal += toWrite
        outFinished = outFinishedOnLastFrame && toWrite == sendBuffer.size
      }

      writeTimeout.enter()
      try {
        connection.writeData(id, outFinished, sendBuffer, toWrite)
      } finally {
        writeTimeout.exitAndThrowIfTimedOut()
      }
    }

    @Throws(IOException::class)
    override fun flush() {
      assertLockNotHeld()

      withLock {
        checkOutNotClosed()
      }
      // TODO(jwilson): flush the connection?!
      while (sendBuffer.size > 0L) {
        emitFrame(false)
        connection.flush()
      }
    }

    override fun timeout(): Timeout = writeTimeout

    @Throws(IOException::class)
    override fun close() {
      assertLockNotHeld()

      val outFinished: Boolean
      withLock {
        if (closed) return
        outFinished = errorCode == null
      }
      if (!sink.finished) {
        // We have 0 or more frames of data, and 0 or more frames of trailers. We need to send at
        // least one frame with the END_STREAM flag set. That must be the last frame, and the
        // trailers must be sent after all of the data.
        val hasData = sendBuffer.size > 0L
        val hasTrailers = trailers != null
        when {
          hasTrailers -> {
            while (sendBuffer.size > 0L) {
              emitFrame(false)
            }
            connection.writeHeaders(id, outFinished, trailers!!.toHeaderList())
          }

          hasData -> {
            while (sendBuffer.size > 0L) {
              emitFrame(true)
            }
          }

          outFinished -> {
            connection.writeData(id, true, null, 0L)
          }
        }
      }
      withLock {
        closed = true
        notifyAll() // Because doReadTimeout() may have changed.
      }
      connection.flush()
      cancelStreamIfNecessary()
    }
  }

  companion object {
    internal const val EMIT_BUFFER_SIZE = 16384L
  }

  /** [delta] will be negative if a settings frame initial window is smaller than the last. */
  fun addBytesToWriteWindow(delta: Long) {
    writeBytesMaximum += delta
    if (delta > 0L) {
      notifyAll()
    }
  }

  @Throws(IOException::class)
  internal fun checkOutNotClosed() {
    when {
      sink.closed -> throw IOException("stream closed")
      sink.finished -> throw IOException("stream finished")
      errorCode != null -> throw errorException ?: StreamResetException(errorCode!!)
    }
  }

  /**
   * Like [Object.wait], but throws an [InterruptedIOException] when interrupted instead of the more
   * awkward [InterruptedException].
   */
  @Throws(InterruptedIOException::class)
  internal fun waitForIo() {
    try {
      wait()
    } catch (_: InterruptedException) {
      Thread.currentThread().interrupt() // Retain interrupted status.
      throw InterruptedIOException()
    }
  }

  /**
   * The Okio timeout watchdog will call [timedOut] if the timeout is reached. In that case we close
   * the stream (asynchronously) which will notify the waiting thread.
   */
  internal inner class StreamTimeout : AsyncTimeout() {
    override fun timedOut() {
      closeLater(ErrorCode.CANCEL)
      connection.sendDegradedPingLater()
    }

    override fun newTimeoutException(cause: IOException?): IOException =
      SocketTimeoutException("timeout").apply {
        if (cause != null) {
          initCause(cause)
        }
      }

    @Throws(IOException::class)
    fun exitAndThrowIfTimedOut() {
      if (exit()) throw newTimeoutException(null)
    }
  }
}
