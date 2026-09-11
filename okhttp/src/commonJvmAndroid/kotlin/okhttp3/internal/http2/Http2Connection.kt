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

import java.io.Closeable
import java.io.IOException
import java.io.InterruptedIOException
import java.util.concurrent.TimeUnit
import okhttp3.Headers
import okhttp3.internal.EMPTY_BYTE_ARRAY
import okhttp3.internal.closeQuietly
import okhttp3.internal.concurrent.Lockable
import okhttp3.internal.concurrent.TaskRunner
import okhttp3.internal.concurrent.assertLockNotHeld
import okhttp3.internal.concurrent.notifyAll
import okhttp3.internal.concurrent.wait
import okhttp3.internal.concurrent.withLock
import okhttp3.internal.connection.BufferedSocket
import okhttp3.internal.http2.ErrorCode.REFUSED_STREAM
import okhttp3.internal.http2.Settings.Companion.DEFAULT_INITIAL_WINDOW_SIZE
import okhttp3.internal.http2.flowcontrol.WindowCounter
import okhttp3.internal.ignoreIoExceptions
import okhttp3.internal.okHttpName
import okhttp3.internal.platform.Platform
import okhttp3.internal.platform.Platform.Companion.INFO
import okhttp3.internal.toHeaders
import okio.Buffer
import okio.BufferedSource
import okio.ByteString

/**
 * A socket connection to a remote peer. A connection hosts streams which can send and receive
 * data.
 *
 * Many methods in this API are **synchronous:** the call is completed before the method returns.
 * This is typical for Java but atypical for HTTP/2. This is motivated by exception transparency:
 * an [IOException] that was triggered by a certain caller can be caught and handled by that caller.
 */
@Suppress("NAME_SHADOWING")
class Http2Connection internal constructor(
  builder: Builder,
) : Closeable,
  Lockable {
  // Internal state of this connection is guarded by 'lock'. No blocking operations may be
  // performed while holding this lock!
  //
  // Socket writes are guarded by frameWriter.
  //
  // Socket reads are unguarded but are only made by the reader thread.
  //
  // Certain operations (like SYN_STREAM) need to synchronize on both the frameWriter (to do
  // blocking I/O) and this (to create streams). Such operations must synchronize on 'this' last.
  // This ensures that we never wait for a blocking operation while holding 'this'.

  /** True if this peer initiated the connection. */
  internal val client: Boolean = builder.client

  /** User code to run in response to incoming streams or settings. */
  internal val listener: Listener = builder.listener
  internal val streams = mutableMapOf<Int, Http2Stream>()
  internal val connectionName: String = builder.connectionName
  internal var lastGoodStreamId = 0

  /** http://tools.ietf.org/html/draft-ietf-httpbis-http2-17#section-5.1.1 */
  internal var nextStreamId = if (builder.client) 3 else 2

  private var isShutdown = false

  /** For scheduling everything asynchronous. */
  private val taskRunner = builder.taskRunner

  /** Asynchronously writes frames to the outgoing socket. */
  private val writerQueue = taskRunner.newQueue()

  /** Ensures push promise callbacks events are sent in order per stream. */
  private val pushQueue = taskRunner.newQueue()

  /** Notifies the listener of settings changes. */
  private val settingsListenerQueue = taskRunner.newQueue()

  /** User code to run in response to push promise events. */
  private val pushObserver: PushObserver = builder.pushObserver

  // Total number of pings send and received of the corresponding types. All guarded by this.
  private var intervalPingsSent = 0L
  private var intervalPongsReceived = 0L
  private var degradedPingsSent = 0L
  private var degradedPongsReceived = 0L
  private var awaitPingsSent = 0L
  private var awaitPongsReceived = 0L

  /** Consider this connection to be unhealthy if a degraded pong isn't received by this time. */
  private var degradedPongDeadlineNs = 0L

  internal val flowControlListener: FlowControlListener = builder.flowControlListener

  /** Settings we communicate to the peer. */
  val okHttpSettings =
    Settings().apply {
      // Flow control was designed more for servers, or proxies than edge clients. If we are a client,
      // set the flow control window to 16MiB.  This avoids thrashing window updates every 64KiB, yet
      // small enough to avoid blowing up the heap.
      if (builder.client) {
        set(Settings.INITIAL_WINDOW_SIZE, OKHTTP_CLIENT_WINDOW_SIZE)
      }
    }

  /**
   * Settings we receive from the peer. Changes to the field are guarded by this. The instance is
   * never mutated once it has been assigned.
   */
  var peerSettings = DEFAULT_SETTINGS

  /** The bytes consumed and acknowledged by the application. */
  val readBytes: WindowCounter = WindowCounter(streamId = 0)

  /** The total number of bytes produced by the application. */
  var writeBytesTotal = 0L
    private set

  /** The total number of bytes permitted to be produced according to `WINDOW_UPDATE` frames. */
  var writeBytesMaximum: Long = peerSettings.initialWindowSize.toLong()
    private set

  internal val socket: BufferedSocket = builder.socket
  val writer = Http2Writer(socket.sink, client)

  // Visible for testing
  val readerRunnable = ReaderRunnable(Http2Reader(socket.source, client))

  // Guarded by this.
  private val currentPushRequests = mutableSetOf<Int>()

  init {
    if (builder.pingIntervalMillis != 0) {
      val pingIntervalNanos = TimeUnit.MILLISECONDS.toNanos(builder.pingIntervalMillis.toLong())
      writerQueue.schedule("$connectionName ping", pingIntervalNanos) {
        val failDueToMissingPong =
          withLock {
            if (intervalPongsReceived < intervalPingsSent) {
              return@withLock true
            } else {
              intervalPingsSent++
              return@withLock false
            }
          }
        if (failDueToMissingPong) {
          failConnection(null)
          return@schedule -1L
        } else {
          writePing(false, INTERVAL_PING, 0)
          return@schedule pingIntervalNanos
        }
      }
    }
  }

  /**
   * Returns the number of [open streams][Http2Stream.isOpen] on this connection.
   */
  fun openStreamCount(): Int = withLock { streams.size }

  fun getStream(id: Int): Http2Stream? = withLock { streams[id] }

  internal fun removeStream(streamId: Int): Http2Stream? {
    withLock {
      val stream = streams.remove(streamId)

      // The removed stream may be blocked on a connection-wide window update.
      notifyAll()

      return stream
    }
  }

  internal fun updateConnectionFlowControl(read: Long) {
    withLock {
      readBytes.update(total = read)
      val readBytesToAcknowledge = readBytes.unacknowledged
      if (readBytesToAcknowledge >= okHttpSettings.initialWindowSize / 2) {
        writeWindowUpdateLater(0, readBytesToAcknowledge)
        readBytes.update(acknowledged = readBytesToAcknowledge)
      }
      flowControlListener.receivingConnectionWindowChanged(readBytes)
    }
  }

  /**
   * Returns a new server-initiated stream.
   *
   * @param associatedStreamId the stream that triggered the sender to create this stream.
   * @param out true to create an output stream that we can use to send data to the remote peer.
   *     Corresponds to `FLAG_FIN`.
   */
  @Throws(IOException::class)
  fun pushStream(
    associatedStreamId: Int,
    requestHeaders: List<Header>,
    out: Boolean,
  ): Http2Stream {
    check(!client) { "Client cannot push requests." }
    return newStream(associatedStreamId, requestHeaders, out)
  }

  /**
   * Returns a new locally-initiated stream.
   *
   * @param out true to create an output stream that we can use to send data to the remote peer.
   *     Corresponds to `FLAG_FIN`.
   */
  @Throws(IOException::class)
  fun newStream(
    requestHeaders: List<Header>,
    out: Boolean,
  ): Http2Stream = newStream(0, requestHeaders, out)

  @Throws(IOException::class)
  private fun newStream(
    associatedStreamId: Int,
    requestHeaders: List<Header>,
    out: Boolean,
  ): Http2Stream {
    val outFinished = !out
    val inFinished = false
    val flushHeaders: Boolean
    val stream: Http2Stream
    val streamId: Int

    writer.withLock {
      withLock {
        if (nextStreamId > Int.MAX_VALUE / 2) {
          shutdown(REFUSED_STREAM)
        }
        if (isShutdown) {
          throw ConnectionShutdownException()
        }
        streamId = nextStreamId
        nextStreamId += 2
        stream = Http2Stream(streamId, this, outFinished, inFinished, null)
        flushHeaders = !out ||
          writeBytesTotal >= writeBytesMaximum ||
          stream.writeBytesTotal >= stream.writeBytesMaximum
        if (stream.isOpen) {
          streams[streamId] = stream
        }
      }
      if (associatedStreamId == 0) {
        writer.headers(outFinished, streamId, requestHeaders)
      } else {
        require(!client) { "client streams shouldn't have associated stream IDs" }
        // HTTP/2 has a PUSH_PROMISE frame.
        writer.pushPromise(associatedStreamId, streamId, requestHeaders)
      }
    }

    if (flushHeaders) {
      writer.flush()
    }

    return stream
  }

  @Throws(IOException::class)
  internal fun writeHeaders(
    streamId: Int,
    outFinished: Boolean,
    alternating: List<Header>,
  ) {
    writer.headers(outFinished, streamId, alternating)
  }

  /**
   * Callers of this method are not thread safe, and sometimes on application threads. Most often,
   * this method will be called to send a buffer worth of data to the peer.
   *
   * Writes are subject to the write window of the stream and the connection. Until there is a
   * window sufficient to send [byteCount], the caller will block. For example, a user of
   * `HttpURLConnection` who flushes more bytes to the output stream than the connection's write
   * window will block.
   *
   * Zero [byteCount] writes are not subject to flow control and will not block. The only use case
   * for zero [byteCount] is closing a flushed output stream.
   */
  @Throws(IOException::class)
  fun writeData(
    streamId: Int,
    outFinished: Boolean,
    buffer: Buffer?,
    byteCount: Long,
  ) {
    // Empty data frames are not flow-controlled.
    if (byteCount == 0L) {
      writer.data(outFinished, streamId, buffer, 0)
      return
    }

    var byteCount = byteCount
    while (byteCount > 0L) {
      var toWrite: Int
      withLock {
        try {
          while (writeBytesTotal >= writeBytesMaximum) {
            // Before blocking, confirm that the stream we're writing is still open. It's possible
            // that the stream has since been closed (such as if this write timed out.)
            if (!streams.containsKey(streamId)) {
              throw IOException("stream closed")
            }
            wait() // Wait until we receive a WINDOW_UPDATE.
          }
        } catch (e: InterruptedException) {
          Thread.currentThread().interrupt() // Retain interrupted status.
          throw InterruptedIOException()
        }

        toWrite = minOf(byteCount, writeBytesMaximum - writeBytesTotal).toInt()
        toWrite = minOf(toWrite, writer.maxDataLength())
        writeBytesTotal += toWrite.toLong()
      }

      byteCount -= toWrite.toLong()
      writer.data(outFinished && byteCount == 0L, streamId, buffer, toWrite)
    }
  }

  internal fun writeSynResetLater(
    streamId: Int,
    errorCode: ErrorCode,
  ) {
    writerQueue.execute("$connectionName[$streamId] writeSynReset") {
      try {
        writeSynReset(streamId, errorCode)
      } catch (e: IOException) {
        failConnection(e)
      }
    }
  }

  @Throws(IOException::class)
  internal fun writeSynReset(
    streamId: Int,
    statusCode: ErrorCode,
  ) {
    writer.rstStream(streamId, statusCode)
  }

  internal fun writeWindowUpdateLater(
    streamId: Int,
    unacknowledgedBytesRead: Long,
  ) {
    writerQueue.execute("$connectionName[$streamId] windowUpdate") {
      try {
        writer.windowUpdate(streamId, unacknowledgedBytesRead)
      } catch (e: IOException) {
        failConnection(e)
      }
    }
  }

  fun writePing(
    reply: Boolean,
    payload1: Int,
    payload2: Int,
  ) {
    try {
      writer.ping(reply, payload1, payload2)
    } catch (e: IOException) {
      failConnection(e)
    }
  }

  /** For testing: sends a ping and waits for a pong. */
  @Throws(InterruptedException::class)
  fun writePingAndAwaitPong() {
    writePing()
    awaitPong()
  }

  /** For testing: sends a ping to be awaited with [awaitPong]. */
  @Throws(InterruptedException::class)
  fun writePing() {
    withLock {
      awaitPingsSent++
    }

    // 0x4f 0x4b 0x6f 0x6b is "OKok".
    writePing(false, AWAIT_PING, 0x4f4b6f6b)
  }

  /** For testing: awaits a pong. */
  @Throws(InterruptedException::class)
  fun awaitPong() {
    withLock {
      while (awaitPongsReceived < awaitPingsSent) {
        wait()
      }
    }
  }

  @Throws(IOException::class)
  fun flush() {
    writer.flush()
  }

  /**
   * Degrades this connection such that new streams can neither be created locally, nor accepted
   * from the remote peer. Existing streams are not impacted. This is intended to permit an endpoint
   * to gracefully stop accepting new requests without harming previously established streams.
   */
  @Throws(IOException::class)
  fun shutdown(statusCode: ErrorCode) {
    writer.withLock {
      val lastGoodStreamId: Int
      withLock {
        if (isShutdown) {
          return
        }
        isShutdown = true
        lastGoodStreamId = this.lastGoodStreamId
      }
      // TODO: propagate exception message into debugData.
      // TODO: configure a timeout on the reader so that it doesn’t block forever.
      writer.goAway(lastGoodStreamId, statusCode, EMPTY_BYTE_ARRAY)
    }
  }

  /**
   * Closes this connection. This cancels all open streams and unanswered pings. It closes the
   * underlying input and output streams and shuts down internal task queues.
   */
  override fun close() {
    close(ErrorCode.NO_ERROR, ErrorCode.CANCEL, null)
  }

  internal fun cancel() {
    socket.cancel()
  }

  internal fun close(
    connectionCode: ErrorCode,
    streamCode: ErrorCode,
    cause: IOException?,
  ) {
    assertLockNotHeld()

    ignoreIoExceptions {
      shutdown(connectionCode)
    }

    var streamsToClose: Array<Http2Stream>? = null
    withLock {
      if (streams.isNotEmpty()) {
        streamsToClose = streams.values.toTypedArray()
        streams.clear()
      }
    }

    streamsToClose?.forEach { stream ->
      ignoreIoExceptions {
        stream.close(streamCode, cause)
      }
    }

    // Close the writer to release its resources (such as deflaters).
    ignoreIoExceptions {
      writer.close()
    }

    // Cancel the socket to break out the reader thread, which will clean up after itself.
    ignoreIoExceptions {
      socket.cancel()
    }

    // Release the threads.
    writerQueue.shutdown()
    pushQueue.shutdown()
    settingsListenerQueue.shutdown()
  }

  private fun failConnection(e: IOException?) {
    close(ErrorCode.PROTOCOL_ERROR, ErrorCode.PROTOCOL_ERROR, e)
  }

  /**
   * Sends any initial frames and starts reading frames from the remote peer. This should be called
   * after [Builder.build] for all new connections.
   *
   * @param sendConnectionPreface true to send connection preface frames. This should always be true
   *     except for in tests that don't check for a connection preface.
   * @param taskRunner the TaskRunner to use, daemon by default.
   */
  @Throws(IOException::class)
  @JvmOverloads
  fun start(sendConnectionPreface: Boolean = true) {
    if (sendConnectionPreface) {
      writer.connectionPreface()
      writer.settings(okHttpSettings)
      val windowSize = okHttpSettings.initialWindowSize
      if (windowSize != DEFAULT_INITIAL_WINDOW_SIZE) {
        writer.windowUpdate(0, (windowSize - DEFAULT_INITIAL_WINDOW_SIZE).toLong())
      }
    }
    // Thread doesn't use client Dispatcher, since it is scoped potentially across clients via
    // ConnectionPool.
    taskRunner.newQueue().execute(name = connectionName, block = readerRunnable)
  }

  /** Merges [settings] into this peer's settings and sends them to the remote peer. */
  @Throws(IOException::class)
  fun setSettings(settings: Settings) {
    writer.withLock {
      withLock {
        if (isShutdown) {
          throw ConnectionShutdownException()
        }
        okHttpSettings.merge(settings)
      }
      writer.settings(settings)
    }
  }

  fun isHealthy(nowNs: Long): Boolean {
    withLock {
      if (isShutdown) return false

      // A degraded pong is overdue.
      if (degradedPongsReceived < degradedPingsSent && nowNs >= degradedPongDeadlineNs) return false

      return true
    }
  }

  /**
   * HTTP/2 can have both stream timeouts (due to a problem with a single stream) and connection
   * timeouts (due to a problem with the transport). When a stream times out we don't know whether
   * the problem impacts just one stream or the entire connection.
   *
   * To differentiate the two cases we ping the server when a stream times out. If the overall
   * connection is fine the ping will receive a pong; otherwise it won't.
   *
   * The deadline to respond to this ping attempts to limit the cost of being wrong. If it is too
   * long, streams created while we await the pong will reuse broken connections and inevitably
   * fail. If it is too short, slow connections will be marked as failed and extra TCP and TLS
   * handshakes will be required.
   *
   * The deadline is currently hardcoded. We may make this configurable in the future!
   */
  internal fun sendDegradedPingLater() {
    withLock {
      if (degradedPongsReceived < degradedPingsSent) return // Already awaiting a degraded pong.
      degradedPingsSent++
      degradedPongDeadlineNs = System.nanoTime() + DEGRADED_PONG_TIMEOUT_NS
    }
    writerQueue.execute("$connectionName ping") {
      writePing(false, DEGRADED_PING, 0)
    }
  }

  class Builder(
    /** True if this peer initiated the connection; false if this peer accepted the connection. */
    internal var client: Boolean,
    internal val taskRunner: TaskRunner,
  ) {
    internal lateinit var socket: BufferedSocket
    internal lateinit var connectionName: String
    internal var listener = Listener.REFUSE_INCOMING_STREAMS
    internal var pushObserver = PushObserver.CANCEL
    internal var pingIntervalMillis: Int = 0
    internal var flowControlListener: FlowControlListener = FlowControlListener.None

    @Throws(IOException::class)
    fun socket(
      socket: BufferedSocket,
      peerName: String,
    ) = apply {
      this.socket = socket
      this.connectionName =
        when {
          client -> "$okHttpName $peerName"
          else -> "MockWebServer $peerName"
        }
    }

    fun listener(listener: Listener) =
      apply {
        this.listener = listener
      }

    fun pushObserver(pushObserver: PushObserver) =
      apply {
        this.pushObserver = pushObserver
      }

    fun pingIntervalMillis(pingIntervalMillis: Int) =
      apply {
        this.pingIntervalMillis = pingIntervalMillis
      }

    fun flowControlListener(flowControlListener: FlowControlListener) =
      apply {
        this.flowControlListener = flowControlListener
      }

    fun build(): Http2Connection = Http2Connection(this)
  }

  /**
   * Methods in this class must not lock FrameWriter. If a method needs to write a frame, create an
   * async task to do so.
   */
  inner class ReaderRunnable internal constructor(
    internal val reader: Http2Reader,
  ) : Http2Reader.Handler,
    () -> Unit {
    override fun invoke() {
      var connectionErrorCode = ErrorCode.INTERNAL_ERROR
      var streamErrorCode = ErrorCode.INTERNAL_ERROR
      var errorException: IOException? = null
      try {
        reader.readConnectionPreface(this)
        while (reader.nextFrame(false, this)) {
        }
        connectionErrorCode = ErrorCode.NO_ERROR
        streamErrorCode = ErrorCode.CANCEL
      } catch (e: IOException) {
        errorException = e
        connectionErrorCode = ErrorCode.PROTOCOL_ERROR
        streamErrorCode = ErrorCode.PROTOCOL_ERROR
      } finally {
        close(connectionErrorCode, streamErrorCode, errorException)
        reader.closeQuietly()
      }
    }

    @Throws(IOException::class)
    override fun data(
      inFinished: Boolean,
      streamId: Int,
      source: BufferedSource,
      length: Int,
    ) {
      if (pushedStream(streamId)) {
        pushDataLater(streamId, source, length, inFinished)
        return
      }
      val dataStream = getStream(streamId)
      if (dataStream == null) {
        writeSynResetLater(streamId, ErrorCode.PROTOCOL_ERROR)
        updateConnectionFlowControl(length.toLong())
        source.skip(length.toLong())
        return
      }
      dataStream.receiveData(source, length)
      if (inFinished) {
        dataStream.receiveHeaders(Headers.EMPTY, true)
      }
    }

    override fun headers(
      inFinished: Boolean,
      streamId: Int,
      associatedStreamId: Int,
      headerBlock: List<Header>,
    ) {
      if (pushedStream(streamId)) {
        pushHeadersLater(streamId, headerBlock, inFinished)
        return
      }
      val stream: Http2Stream?
      withLock {
        stream = getStream(streamId)

        if (stream == null) {
          // If we're shutdown, don't bother with this stream.
          if (isShutdown) return

          // If the stream ID is less than the last created ID, assume it's already closed.
          if (streamId <= lastGoodStreamId) return

          // If the stream ID is in the client's namespace, assume it's already closed.
          if (streamId % 2 == nextStreamId % 2) return

          // Create a stream.
          val headers = headerBlock.toHeaders()
          val newStream = Http2Stream(streamId, this@Http2Connection, false, inFinished, headers)
          lastGoodStreamId = streamId
          streams[streamId] = newStream

          // Use a different task queue for each stream because they should be handled in parallel.
          taskRunner.newQueue().execute("$connectionName[$streamId] onStream") {
            try {
              listener.onStream(newStream)
            } catch (e: IOException) {
              Platform.get().log("Http2Connection.Listener failure for $connectionName", INFO, e)
              ignoreIoExceptions {
                newStream.close(ErrorCode.PROTOCOL_ERROR, e)
              }
            }
          }
          return
        }
      }

      // Update an existing stream.
      stream!!.receiveHeaders(headerBlock.toHeaders(), inFinished)
    }

    override fun rstStream(
      streamId: Int,
      errorCode: ErrorCode,
    ) {
      if (pushedStream(streamId)) {
        pushResetLater(streamId, errorCode)
        return
      }
      val rstStream = removeStream(streamId)
      rstStream?.receiveRstStream(errorCode)
    }

    override fun settings(
      clearPrevious: Boolean,
      settings: Settings,
    ) {
      writerQueue.execute("$connectionName applyAndAckSettings") {
        applyAndAckSettings(clearPrevious, settings)
      }
    }

    /**
     * Apply inbound settings and send an acknowledgement to the peer that provided them.
     *
     * We need to apply the settings and ack them atomically. This is because some HTTP/2
     * implementations (nghttp2) forbid peers from taking advantage of settings before they have
     * acknowledged! In particular, we shouldn't send frames that assume a new `initialWindowSize`
     * until we send the frame that acknowledges this new size.
     *
     * Since we can't ACK settings on the current reader thread (the reader thread can't write) we
     * execute all peer settings logic on the writer thread. This relies on the fact that the
     * writer task queue won't reorder tasks; otherwise settings could be applied in the opposite
     * order than received.
     */
    fun applyAndAckSettings(
      clearPrevious: Boolean,
      settings: Settings,
    ) {
      var delta: Long
      var streamsToNotify: Array<Http2Stream>?
      var newPeerSettings: Settings
      writer.withLock {
        withLock {
          val previousPeerSettings = peerSettings
          newPeerSettings =
            if (clearPrevious) {
              settings
            } else {
              Settings().apply {
                merge(previousPeerSettings)
                merge(settings)
              }
            }

          val peerInitialWindowSize = newPeerSettings.initialWindowSize.toLong()
          delta = peerInitialWindowSize - previousPeerSettings.initialWindowSize.toLong()
          streamsToNotify =
            when {
              delta == 0L || streams.isEmpty() -> null

              // No adjustment is necessary.
              else -> streams.values.toTypedArray()
            }

          peerSettings = newPeerSettings

          settingsListenerQueue.execute("$connectionName onSettings") {
            listener.onSettings(this@Http2Connection, newPeerSettings)
          }
        }
        try {
          writer.applyAndAckSettings(newPeerSettings)
        } catch (e: IOException) {
          failConnection(e)
        }
      }
      if (streamsToNotify != null) {
        for (stream in streamsToNotify) {
          stream.withLock {
            stream.addBytesToWriteWindow(delta)
          }
        }
      }
    }

    override fun ackSettings() {
      // TODO: If we don't get this callback after sending settings to the peer, SETTINGS_TIMEOUT.
    }

    override fun ping(
      ack: Boolean,
      payload1: Int,
      payload2: Int,
    ) {
      if (ack) {
        withLock {
          when (payload1) {
            INTERVAL_PING -> {
              intervalPongsReceived++
            }

            DEGRADED_PING -> {
              degradedPongsReceived++
            }

            AWAIT_PING -> {
              awaitPongsReceived++
              notifyAll()
            }

            else -> {
              // Ignore an unexpected pong.
            }
          }
        }
      } else {
        // Send a reply to a client ping if this is a server and vice versa.
        writerQueue.execute("$connectionName ping") {
          writePing(true, payload1, payload2)
        }
      }
    }

    override fun goAway(
      lastGoodStreamId: Int,
      errorCode: ErrorCode,
      debugData: ByteString,
    ) {
      if (debugData.size > 0) {
        // TODO: log the debugData
      }

      // Copy the streams first. We don't want to hold a lock when we call receiveRstStream().
      val streamsCopy: Array<Http2Stream>
      withLock {
        streamsCopy = streams.values.toTypedArray()
        isShutdown = true
      }

      // Fail all streams created after the last good stream ID.
      for (http2Stream in streamsCopy) {
        if (http2Stream.id > lastGoodStreamId && http2Stream.isLocallyInitiated) {
          http2Stream.receiveRstStream(REFUSED_STREAM)
          removeStream(http2Stream.id)
        }
      }
    }

    override fun windowUpdate(
      streamId: Int,
      windowSizeIncrement: Long,
    ) {
      if (streamId == 0) {
        withLock {
          writeBytesMaximum += windowSizeIncrement
          notifyAll()
        }
      } else {
        val stream = getStream(streamId)
        if (stream != null) {
          stream.withLock {
            stream.addBytesToWriteWindow(windowSizeIncrement)
          }
        }
      }
    }

    override fun priority(
      streamId: Int,
      streamDependency: Int,
      weight: Int,
      exclusive: Boolean,
    ) {
      // TODO: honor priority.
    }

    override fun pushPromise(
      streamId: Int,
      promisedStreamId: Int,
      requestHeaders: List<Header>,
    ) {
      pushRequestLater(promisedStreamId, requestHeaders)
    }

    override fun alternateService(
      streamId: Int,
      origin: String,
      protocol: ByteString,
      host: String,
      port: Int,
      maxAge: Long,
    ) {
      // TODO: register alternate service.
    }
  }

  /** Even, positive numbered streams are pushed streams in HTTP/2. */
  internal fun pushedStream(streamId: Int): Boolean = streamId != 0 && streamId and 1 == 0

  internal fun pushRequestLater(
    streamId: Int,
    requestHeaders: List<Header>,
  ) {
    withLock {
      if (streamId in currentPushRequests) {
        writeSynResetLater(streamId, ErrorCode.PROTOCOL_ERROR)
        return
      }
      currentPushRequests.add(streamId)
    }
    pushQueue.execute("$connectionName[$streamId] onRequest") {
      val cancel = pushObserver.onRequest(streamId, requestHeaders)
      ignoreIoExceptions {
        if (cancel) {
          writer.rstStream(streamId, ErrorCode.CANCEL)
          withLock {
            currentPushRequests.remove(streamId)
          }
        }
      }
    }
  }

  internal fun pushHeadersLater(
    streamId: Int,
    requestHeaders: List<Header>,
    inFinished: Boolean,
  ) {
    pushQueue.execute("$connectionName[$streamId] onHeaders") {
      val cancel = pushObserver.onHeaders(streamId, requestHeaders, inFinished)
      ignoreIoExceptions {
        if (cancel) writer.rstStream(streamId, ErrorCode.CANCEL)
        if (cancel || inFinished) {
          withLock {
            currentPushRequests.remove(streamId)
          }
        }
      }
    }
  }

  /**
   * Eagerly reads `byteCount` bytes from the source before launching a background task to
   * process the data.  This avoids corrupting the stream.
   */
  @Throws(IOException::class)
  internal fun pushDataLater(
    streamId: Int,
    source: BufferedSource,
    byteCount: Int,
    inFinished: Boolean,
  ) {
    val buffer = Buffer()
    source.require(byteCount.toLong()) // Eagerly read the frame before firing client thread.
    source.read(buffer, byteCount.toLong())
    pushQueue.execute("$connectionName[$streamId] onData") {
      ignoreIoExceptions {
        val cancel = pushObserver.onData(streamId, buffer, byteCount, inFinished)
        if (cancel) writer.rstStream(streamId, ErrorCode.CANCEL)
        if (cancel || inFinished) {
          withLock {
            currentPushRequests.remove(streamId)
          }
        }
      }
    }
  }

  internal fun pushResetLater(
    streamId: Int,
    errorCode: ErrorCode,
  ) {
    pushQueue.execute("$connectionName[$streamId] onReset") {
      pushObserver.onReset(streamId, errorCode)
      withLock {
        currentPushRequests.remove(streamId)
      }
    }
  }

  /** Listener of streams and settings initiated by the peer. */
  abstract class Listener {
    /**
     * Handle a new stream from this connection's peer. Implementations should respond by either
     * [replying to the stream][Http2Stream.writeHeaders] or [closing it][Http2Stream.close]. This
     * response does not need to be synchronous.
     *
     * Multiple calls to this method may be made concurrently.
     */
    @Throws(IOException::class)
    abstract fun onStream(stream: Http2Stream)

    /**
     * Notification that the connection's peer's settings may have changed to [settings].
     * Implementations should take appropriate action to handle the updated settings.
     *
     * Methods to this method may be made concurrently with [onStream]. But a calls to this method
     * are serialized.
     */
    open fun onSettings(
      connection: Http2Connection,
      settings: Settings,
    ) {}

    companion object {
      @JvmField
      val REFUSE_INCOMING_STREAMS: Listener =
        object : Listener() {
          @Throws(IOException::class)
          override fun onStream(stream: Http2Stream) {
            stream.close(REFUSED_STREAM, null)
          }
        }
    }
  }

  companion object {
    const val OKHTTP_CLIENT_WINDOW_SIZE = 16 * 1024 * 1024

    val DEFAULT_SETTINGS =
      Settings().apply {
        set(Settings.INITIAL_WINDOW_SIZE, DEFAULT_INITIAL_WINDOW_SIZE)
        set(Settings.MAX_FRAME_SIZE, Http2.INITIAL_MAX_FRAME_SIZE)
      }

    const val INTERVAL_PING = 1
    const val DEGRADED_PING = 2
    const val AWAIT_PING = 3
    const val DEGRADED_PONG_TIMEOUT_NS = 1_000_000_000 // 1 second.
  }
}
