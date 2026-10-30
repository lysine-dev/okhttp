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
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.BlockingQueue
import java.util.concurrent.Executors
import java.util.concurrent.LinkedBlockingQueue
import java.util.logging.Logger
import okhttp3.TestUtil.threadFactory
import okhttp3.internal.closeQuietly
import okio.Buffer
import okio.BufferedSource
import okio.ByteString
import okio.buffer
import okio.source

/** Replays prerecorded outgoing frames and records incoming frames.  */
class MockHttp2Peer : Closeable {
  private var frameCount = 0
  private var client = false
  private val bytesOut = Buffer()
  private var writer = Http2Writer(bytesOut, client)
  private val outFrames: MutableList<OutFrame> = ArrayList()
  private val inFrames: BlockingQueue<InFrame> = LinkedBlockingQueue()
  private var port = 0
  private val executor = Executors.newSingleThreadExecutor(threadFactory("MockHttp2Peer"))
  private var serverSocket: ServerSocket? = null
  private var socket: Socket? = null

  fun setClient(client: Boolean) {
    if (this.client == client) return
    this.client = client
    writer = Http2Writer(bytesOut, client)
  }

  fun acceptFrame() {
    frameCount++
  }

  /** Maximum length of an outbound data frame.  */
  fun maxOutboundDataLength(): Int = writer.maxDataLength()

  /** Count of frames sent or received.  */
  fun frameCount(): Int = frameCount

  fun sendFrame(): Http2Writer {
    outFrames.add(OutFrame(frameCount++, bytesOut.size, false))
    return writer
  }

  fun sendPaddedData(
    inFinished: Boolean,
    streamId: Int,
    data: Buffer,
    byteCount: Int,
    padding: Int,
  ) {
    require(padding in 0..255)
    val flags = Http2.FLAG_PADDED or if (inFinished) Http2.FLAG_END_STREAM else 0
    sendFrame().frameHeader(streamId, byteCount + padding + 1, Http2.TYPE_DATA, flags)
    bytesOut.writeByte(padding)
    bytesOut.write(data, byteCount.toLong())
    bytesOut.write(ByteArray(padding))
  }

  /**
   * Shortens the last frame from its original length to `length`. This will cause the peer to
   * close the socket as soon as this frame has been written; otherwise the peer stays open until
   * explicitly closed.
   */
  fun truncateLastFrame(length: Int): Http2Writer {
    val lastFrame = outFrames.removeAt(outFrames.size - 1)
    require(length < bytesOut.size - lastFrame.start)

    // Move everything from bytesOut into a new buffer.
    val fullBuffer = Buffer()
    bytesOut.read(fullBuffer, bytesOut.size)

    // Copy back all but what we're truncating.
    fullBuffer.read(bytesOut, lastFrame.start + length)
    outFrames.add(OutFrame(lastFrame.sequence, lastFrame.start, true))
    return writer
  }

  fun takeFrame(): InFrame = inFrames.take()

  fun play() {
    check(serverSocket == null)
    serverSocket = ServerSocket()
    serverSocket!!.reuseAddress = false
    serverSocket!!.bind(InetSocketAddress("localhost", 0), 1)
    port = serverSocket!!.localPort
    executor.execute {
      try {
        readAndWriteFrames()
      } catch (e: IOException) {
        this@MockHttp2Peer.closeQuietly()
        logger.info("${this@MockHttp2Peer} done: ${e.message}")
      }
    }
  }

  private fun readAndWriteFrames() {
    check(socket == null)
    val socket = serverSocket!!.accept()!!
    this.socket = socket

    // Bail out now if this instance was closed while waiting for the socket to accept.
    synchronized(this) {
      if (executor.isShutdown) {
        socket.close()
        return
      }
    }
    val outputStream = socket.getOutputStream()
    val inputStream = socket.getInputStream()
    val reader = Http2Reader(inputStream.source().buffer(), client)
    val outFramesIterator: Iterator<OutFrame> = outFrames.iterator()
    val outBytes = bytesOut.readByteArray()
    var nextOutFrame: OutFrame? = null
    for (i in 0 until frameCount) {
      if (nextOutFrame == null && outFramesIterator.hasNext()) {
        nextOutFrame = outFramesIterator.next()
      }

      if (nextOutFrame != null && nextOutFrame.sequence == i) {
        val start = nextOutFrame.start
        var truncated: Boolean
        var end: Long
        if (outFramesIterator.hasNext()) {
          nextOutFrame = outFramesIterator.next()
          end = nextOutFrame.start
          truncated = false
        } else {
          end = outBytes.size.toLong()
          truncated = nextOutFrame.truncated
        }

        // Write a frame.
        val length = (end - start).toInt()
        outputStream.write(outBytes, start.toInt(), length)

        // If the last frame was truncated, immediately close the connection.
        if (truncated) {
          socket.close()
        }
      } else {
        // read a frame
        val inFrame = InFrame(i, reader)
        reader.nextFrame(false, inFrame)
        inFrames.add(inFrame)
      }
    }
  }

  fun openSocket(): Socket = Socket("localhost", port)

  @Synchronized override fun close() {
    executor.shutdown()
    socket?.closeQuietly()
    serverSocket?.closeQuietly()
  }

  override fun toString(): String = "MockHttp2Peer[$port]"

  private class OutFrame(
    val sequence: Int,
    val start: Long,
    val truncated: Boolean,
  )

  class InFrame(
    val sequence: Int,
    val reader: Http2Reader,
  ) : Http2Reader.Handler {
    @JvmField var type = -1
    var clearPrevious = false

    @JvmField var outFinished = false

    @JvmField var inFinished = false

    @JvmField var streamId = 0

    @JvmField var associatedStreamId = 0

    @JvmField var errorCode: ErrorCode? = null

    @JvmField var windowSizeIncrement: Long = 0

    @JvmField var headerBlock: List<Header>? = null

    @JvmField var data: ByteArray? = null

    @JvmField var padding = 0

    @JvmField var settings: Settings? = null

    @JvmField var ack = false

    @JvmField var payload1 = 0

    @JvmField var payload2 = 0

    override fun settings(
      clearPrevious: Boolean,
      settings: Settings,
    ) {
      check(type == -1)
      this.type = Http2.TYPE_SETTINGS
      this.clearPrevious = clearPrevious
      this.settings = settings
    }

    override fun ackSettings() {
      check(type == -1)
      this.type = Http2.TYPE_SETTINGS
      this.ack = true
    }

    override fun headers(
      inFinished: Boolean,
      streamId: Int,
      associatedStreamId: Int,
      headerBlock: List<Header>,
    ) {
      check(type == -1)
      this.type = Http2.TYPE_HEADERS
      this.inFinished = inFinished
      this.streamId = streamId
      this.associatedStreamId = associatedStreamId
      this.headerBlock = headerBlock
    }

    override fun data(
      inFinished: Boolean,
      streamId: Int,
      source: BufferedSource,
      length: Int,
    ) {
      check(type == -1)
      this.type = Http2.TYPE_DATA
      this.inFinished = inFinished
      this.streamId = streamId
      this.data = source.readByteString(length.toLong()).toByteArray()
    }

    override fun dataPadding(
      streamId: Int,
      length: Int,
    ) {
      check(type == Http2.TYPE_DATA)
      check(this.streamId == streamId)
      this.padding = length
    }

    override fun rstStream(
      streamId: Int,
      errorCode: ErrorCode,
    ) {
      check(type == -1)
      this.type = Http2.TYPE_RST_STREAM
      this.streamId = streamId
      this.errorCode = errorCode
    }

    override fun ping(
      ack: Boolean,
      payload1: Int,
      payload2: Int,
    ) {
      check(type == -1)
      type = Http2.TYPE_PING
      this.ack = ack
      this.payload1 = payload1
      this.payload2 = payload2
    }

    override fun goAway(
      lastGoodStreamId: Int,
      errorCode: ErrorCode,
      debugData: ByteString,
    ) {
      check(type == -1)
      this.type = Http2.TYPE_GOAWAY
      this.streamId = lastGoodStreamId
      this.errorCode = errorCode
      this.data = debugData.toByteArray()
    }

    override fun windowUpdate(
      streamId: Int,
      windowSizeIncrement: Long,
    ) {
      check(type == -1)
      this.type = Http2.TYPE_WINDOW_UPDATE
      this.streamId = streamId
      this.windowSizeIncrement = windowSizeIncrement
    }

    override fun priority(
      streamId: Int,
      streamDependency: Int,
      weight: Int,
      exclusive: Boolean,
    ): Unit = throw UnsupportedOperationException()

    override fun pushPromise(
      streamId: Int,
      associatedStreamId: Int,
      headerBlock: List<Header>,
    ) {
      this.type = Http2.TYPE_PUSH_PROMISE
      this.streamId = streamId
      this.associatedStreamId = associatedStreamId
      this.headerBlock = headerBlock
    }

    override fun alternateService(
      streamId: Int,
      origin: String,
      protocol: ByteString,
      host: String,
      port: Int,
      maxAge: Long,
    ): Unit = throw UnsupportedOperationException()
  }

  companion object {
    private val logger = Logger.getLogger(MockHttp2Peer::class.java.name)
  }
}
