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

import assertk.assertThat
import assertk.assertions.contains
import assertk.assertions.hasSize
import assertk.assertions.isCloseTo
import assertk.assertions.isEqualTo
import assertk.assertions.isFalse
import assertk.assertions.isGreaterThan
import assertk.assertions.isLessThan
import assertk.assertions.isNull
import assertk.assertions.isTrue
import java.io.EOFException
import java.io.IOException
import java.io.InterruptedIOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertFailsWith
import okhttp3.Headers
import okhttp3.Headers.Companion.headersOf
import okhttp3.TestUtil.headerEntries
import okhttp3.TestUtil.repeat
import okhttp3.internal.EMPTY_BYTE_ARRAY
import okhttp3.internal.concurrent.Lockable
import okhttp3.internal.concurrent.TaskFaker
import okhttp3.internal.concurrent.TaskRunner
import okhttp3.internal.concurrent.notifyAll
import okhttp3.internal.concurrent.wait
import okhttp3.internal.concurrent.withLock
import okhttp3.internal.connection.asBufferedSocket
import okio.AsyncTimeout
import okio.Buffer
import okio.BufferedSource
import okio.Source
import okio.buffer
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout

@Timeout(5)
@Tag("Slow")
class Http2ConnectionTest {
  private val peer = MockHttp2Peer()
  private val taskFaker = TaskFaker()

  @AfterEach fun tearDown() {
    peer.close()
    taskFaker.close()
  }

  @Test fun serverPingsClientHttp2() {
    // Write the mocking script.
    peer.sendFrame().settings(Settings())
    peer.acceptFrame() // ACK
    peer.sendFrame().ping(false, 2, 3)
    peer.acceptFrame() // PING
    peer.play()

    // Play it back.
    connect(peer)

    // Verify the peer received what was expected.
    val ping = peer.takeFrame()
    assertThat(ping.type).isEqualTo(Http2.TYPE_PING)
    assertThat(ping.streamId).isEqualTo(0)
    assertThat(ping.payload1).isEqualTo(2)
    assertThat(ping.payload2).isEqualTo(3)
    assertThat(ping.ack).isTrue()
  }

  @Test fun peerHttp2ServerLowersInitialWindowSize() {
    val initial = Settings()
    initial[Settings.INITIAL_WINDOW_SIZE] = 1684
    val shouldntImpactConnection = Settings()
    shouldntImpactConnection[Settings.INITIAL_WINDOW_SIZE] = 3368
    peer.sendFrame().settings(initial)
    peer.acceptFrame() // ACK
    peer.sendFrame().settings(shouldntImpactConnection)
    peer.acceptFrame() // ACK 2
    peer.acceptFrame() // HEADERS
    peer.play()
    val connection = connect(peer)

    // Verify the peer received the second ACK.
    val ackFrame = peer.takeFrame()
    assertThat(ackFrame.type).isEqualTo(Http2.TYPE_SETTINGS)
    assertThat(ackFrame.streamId).isEqualTo(0)
    assertThat(ackFrame.ack).isTrue()

    // This stream was created *after* the connection settings were adjusted.
    val stream = connection.newStream(headerEntries("a", "android"), false)
    assertThat(connection.peerSettings.initialWindowSize).isEqualTo(3368)
    // New Stream is has the most recent initial window size.
    assertThat(stream.writeBytesTotal).isEqualTo(0L)
    assertThat(stream.writeBytesMaximum).isEqualTo(3368L)
  }

  @Test fun peerHttp2ServerZerosCompressionTable() {
    val client = false // Peer is server, so we are client.
    val settings = Settings()
    settings[Settings.HEADER_TABLE_SIZE] = 0
    val connection = connectWithSettings(client, settings)

    // Verify the peer's settings were read and applied.
    assertThat(connection.peerSettings.headerTableSize).isEqualTo(0)
    val writer = connection.writer
    assertThat(writer.hpackWriter.dynamicTableByteCount).isEqualTo(0)
    assertThat(writer.hpackWriter.headerTableSizeSetting).isEqualTo(0)
  }

  @Test fun peerHttp2ClientDisablesPush() {
    val client = false // Peer is client, so we are server.
    val settings = Settings()
    settings[Settings.ENABLE_PUSH] = 0 // The peer client disables push.
    val connection = connectWithSettings(client, settings)

    // verify the peer's settings were read and applied.
    assertThat(connection.peerSettings.getEnablePush(true)).isFalse()
  }

  @Test fun peerIncreasesMaxFrameSize() {
    val newMaxFrameSize = 0x4001
    val settings = Settings()
    settings[Settings.MAX_FRAME_SIZE] = newMaxFrameSize
    val connection = connectWithSettings(true, settings)

    // verify the peer's settings were read and applied.
    assertThat(connection.peerSettings.getMaxFrameSize(-1)).isEqualTo(newMaxFrameSize)
    assertThat(connection.writer.maxDataLength()).isEqualTo(newMaxFrameSize)
  }

  /**
   * Webservers may set the initial window size to zero, which is a special case because it means
   * that we have to flush headers immediately before any request body can be sent.
   * https://github.com/lysine-dev/okhttp/issues/2543
   */
  @Test fun peerSetsZeroFlowControl() {
    peer.setClient(true)

    // Write the mocking script.
    peer.sendFrame().settings(Settings().set(Settings.INITIAL_WINDOW_SIZE, 0))
    peer.acceptFrame() // ACK
    peer.sendFrame().windowUpdate(0, 10) // Increase the connection window size.
    peer.acceptFrame() // PING
    peer.sendFrame().ping(true, Http2Connection.AWAIT_PING, 0)
    peer.acceptFrame() // HEADERS STREAM 3
    peer.sendFrame().windowUpdate(3, 5)
    peer.acceptFrame() // DATA STREAM 3 "abcde"
    peer.sendFrame().windowUpdate(3, 5)
    peer.acceptFrame() // DATA STREAM 3 "fghi"
    peer.play()

    // Play it back.
    val connection = connect(peer)
    connection.writePingAndAwaitPong() // Ensure the SETTINGS have been received.
    val stream = connection.newStream(headerEntries("a", "android"), true)
    val sink = stream.sink.buffer()
    sink.writeUtf8("abcdefghi")
    sink.flush()

    // Verify the peer received what was expected.
    peer.takeFrame() // PING
    val headers = peer.takeFrame()
    assertThat(headers.type).isEqualTo(Http2.TYPE_HEADERS)
    val data1 = peer.takeFrame()
    assertThat(data1.type).isEqualTo(Http2.TYPE_DATA)
    assertThat(data1.streamId).isEqualTo(3)
    assertArrayEquals("abcde".toByteArray(), data1.data)
    val data2 = peer.takeFrame()
    assertThat(data2.type).isEqualTo(Http2.TYPE_DATA)
    assertThat(data2.streamId).isEqualTo(3)
    assertArrayEquals("fghi".toByteArray(), data2.data)
  }

  /**
   * Confirm that we account for discarded data frames. It's possible that data frames are in-flight
   * just prior to us canceling a stream.
   */
  @Test fun discardedDataFramesAreCounted() {
    // Write the mocking script.
    peer.sendFrame().settings(Settings())
    peer.acceptFrame() // ACK
    peer.acceptFrame() // SYN_STREAM 3
    peer.sendFrame().headers(false, 3, headerEntries("a", "apple"))
    peer.sendFrame().data(false, 3, data(1024), 1024)
    peer.acceptFrame() // RST_STREAM
    peer.sendFrame().data(true, 3, data(1024), 1024)
    peer.acceptFrame() // RST_STREAM
    peer.play()
    val connection = connect(peer)
    val stream1 = connection.newStream(headerEntries("b", "bark"), false)
    val source = stream1.source
    val buffer = Buffer()
    while (buffer.size != 1024L) source.read(buffer, 1024)
    stream1.close(ErrorCode.CANCEL, null)
    val frame1 = peer.takeFrame()
    assertThat(frame1.type).isEqualTo(Http2.TYPE_HEADERS)
    val frame2 = peer.takeFrame()
    assertThat(frame2.type).isEqualTo(Http2.TYPE_RST_STREAM)
    val frame3 = peer.takeFrame()
    assertThat(frame3.type).isEqualTo(Http2.TYPE_RST_STREAM)
    assertThat(connection.readBytes.acknowledged).isEqualTo(0L)
    assertThat(connection.readBytes.total).isEqualTo(2048L)
  }

  @Test fun receiveGoAwayHttp2() {
    // Write the mocking script.
    peer.sendFrame().settings(Settings())
    peer.acceptFrame() // ACK
    peer.acceptFrame() // SYN_STREAM 3
    peer.acceptFrame() // SYN_STREAM 5
    peer.sendFrame().goAway(3, ErrorCode.PROTOCOL_ERROR, EMPTY_BYTE_ARRAY)
    peer.acceptFrame() // PING
    peer.sendFrame().ping(true, Http2Connection.AWAIT_PING, 0)
    peer.acceptFrame() // DATA STREAM 3
    peer.play()

    // Play it back.
    val connection = connect(peer)
    val stream1 = connection.newStream(headerEntries("a", "android"), true)
    val stream2 = connection.newStream(headerEntries("b", "banana"), true)
    connection.writePingAndAwaitPong() // Ensure the GO_AWAY that resets stream2 has been received.
    val sink1 = stream1.sink.buffer()
    val sink2 = stream2.sink.buffer()
    sink1.writeUtf8("abc")
    assertFailsWith<IOException> {
      sink2.writeUtf8("abc")
      sink2.flush()
    }.also { expected ->
      assertThat(expected.message).isEqualTo("stream was reset: REFUSED_STREAM")
    }
    sink1.writeUtf8("def")
    sink1.close()
    assertFailsWith<ConnectionShutdownException> {
      connection.newStream(headerEntries("c", "cola"), true)
    }
    assertThat(stream1.isOpen).isTrue()
    assertThat(stream2.isOpen).isFalse()
    assertThat(connection.openStreamCount()).isEqualTo(1)

    // Verify the peer received what was expected.
    val synStream1 = peer.takeFrame()
    assertThat(synStream1.type).isEqualTo(Http2.TYPE_HEADERS)
    val synStream2 = peer.takeFrame()
    assertThat(synStream2.type).isEqualTo(Http2.TYPE_HEADERS)
    val ping = peer.takeFrame()
    assertThat(ping.type).isEqualTo(Http2.TYPE_PING)
    val data1 = peer.takeFrame()
    assertThat(data1.type).isEqualTo(Http2.TYPE_DATA)
    assertThat(data1.streamId).isEqualTo(3)
    assertArrayEquals("abcdef".toByteArray(), data1.data)
  }

  @Test fun readSendsWindowUpdateHttp2() {
    val windowSize = 100
    val windowUpdateThreshold = 50

    // Write the mocking script.
    peer.sendFrame().settings(Settings())
    peer.acceptFrame() // ACK
    peer.acceptFrame() // SYN_STREAM
    peer.sendFrame().headers(false, 3, headerEntries("a", "android"))
    for (i in 0..2) {
      // Send frames of summing to size 50, which is windowUpdateThreshold.
      peer.sendFrame().data(false, 3, data(24), 24)
      peer.sendFrame().data(false, 3, data(25), 25)
      peer.sendFrame().data(false, 3, data(1), 1)
      peer.acceptFrame() // connection WINDOW UPDATE
      peer.acceptFrame() // stream WINDOW UPDATE
    }
    peer.sendFrame().data(true, 3, data(0), 0)
    peer.play()

    // Play it back.
    val connection = connect(peer)
    connection.okHttpSettings[Settings.INITIAL_WINDOW_SIZE] = windowSize
    val stream = connection.newStream(headerEntries("b", "banana"), false)
    assertThat(stream.readBytes.acknowledged).isEqualTo(0L)
    assertThat(stream.readBytes.total).isEqualTo(0L)
    assertThat(stream.takeHeaders()).isEqualTo(headersOf("a", "android"))
    val source = stream.source
    val buffer = Buffer()
    buffer.writeAll(source)
    assertThat(source.read(buffer, 1)).isEqualTo(-1)
    assertThat(buffer.size).isEqualTo(150)
    val synStream = peer.takeFrame()
    assertThat(synStream.type).isEqualTo(Http2.TYPE_HEADERS)
    for (i in 0..2) {
      val windowUpdateStreamIds: MutableList<Int?> = ArrayList(2)
      for (j in 0..1) {
        val windowUpdate = peer.takeFrame()
        assertThat(windowUpdate.type).isEqualTo(Http2.TYPE_WINDOW_UPDATE)
        windowUpdateStreamIds.add(windowUpdate.streamId)
        assertThat(windowUpdate.windowSizeIncrement).isEqualTo(windowUpdateThreshold.toLong())
      }
      // connection
      assertThat(windowUpdateStreamIds).contains(0)
      // stream
      assertThat(windowUpdateStreamIds).contains(3)
    }
  }

  @Test fun serverSendsEmptyDataClientDoesntSendWindowUpdateHttp2() {
    // Write the mocking script.
    peer.sendFrame().settings(Settings())
    peer.acceptFrame() // ACK
    peer.acceptFrame() // SYN_STREAM
    peer.sendFrame().headers(false, 3, headerEntries("a", "android"))
    peer.sendFrame().data(true, 3, data(0), 0)
    peer.play()

    // Play it back.
    val connection = connect(peer)
    val client = connection.newStream(headerEntries("b", "banana"), false)
    assertThat(client.source.read(Buffer(), 1)).isEqualTo(-1)

    // Verify the peer received what was expected.
    val synStream = peer.takeFrame()
    assertThat(synStream.type).isEqualTo(Http2.TYPE_HEADERS)
    assertThat(peer.frameCount()).isEqualTo(5)
  }

  @Test fun clientSendsEmptyDataServerDoesntSendWindowUpdateHttp2() {
    // Write the mocking script.
    peer.sendFrame().settings(Settings())
    peer.acceptFrame() // ACK
    peer.acceptFrame() // SYN_STREAM
    peer.acceptFrame() // DATA
    peer.sendFrame().headers(false, 3, headerEntries("a", "android"))
    peer.play()

    // Play it back.
    val connection = connect(peer)
    val client = connection.newStream(headerEntries("b", "banana"), true)
    val out = client.sink.buffer()
    out.write(EMPTY_BYTE_ARRAY)
    out.flush()
    out.close()

    // Verify the peer received what was expected.
    assertThat(peer.takeFrame().type).isEqualTo(Http2.TYPE_HEADERS)
    assertThat(peer.takeFrame().type).isEqualTo(Http2.TYPE_DATA)
    assertThat(peer.frameCount()).isEqualTo(5)
  }

  @Test fun maxFrameSizeHonored() {
    val buff = ByteArray(peer.maxOutboundDataLength() + 1)
    buff.fill('*'.code.toByte())

    // Write the mocking script.
    peer.sendFrame().settings(Settings())
    peer.acceptFrame() // ACK
    peer.acceptFrame() // SYN_STREAM
    peer.sendFrame().headers(false, 3, headerEntries("a", "android"))
    peer.acceptFrame() // DATA
    peer.acceptFrame() // DATA
    peer.play()

    // Play it back.
    val connection = connect(peer)
    val stream = connection.newStream(headerEntries("b", "banana"), true)
    val out = stream.sink.buffer()
    out.write(buff)
    out.flush()
    out.close()
    val synStream = peer.takeFrame()
    assertThat(synStream.type).isEqualTo(Http2.TYPE_HEADERS)
    var data = peer.takeFrame()
    assertThat(data.data!!.size).isEqualTo(peer.maxOutboundDataLength())
    data = peer.takeFrame()
    assertThat(data.data!!.size).isEqualTo(1)
  }

  @Test fun pushPromiseStream() {
    // Write the mocking script.
    peer.sendFrame().settings(Settings())
    peer.acceptFrame() // ACK
    peer.acceptFrame() // SYN_STREAM
    peer.sendFrame().headers(false, 3, headerEntries("a", "android"))
    val expectedRequestHeaders =
      listOf(
        Header(Header.TARGET_METHOD, "GET"),
        Header(Header.TARGET_SCHEME, "https"),
        Header(Header.TARGET_AUTHORITY, "squareup.com"),
        Header(Header.TARGET_PATH, "/cached"),
      )
    peer.sendFrame().pushPromise(3, 2, expectedRequestHeaders)
    val expectedResponseHeaders =
      listOf(
        Header(Header.RESPONSE_STATUS, "200"),
      )
    peer.sendFrame().headers(true, 2, expectedResponseHeaders)
    peer.sendFrame().data(true, 3, data(0), 0)
    peer.play()
    val observer = RecordingPushObserver()

    // Play it back.
    val connection = connect(peer, observer, Http2Connection.Listener.REFUSE_INCOMING_STREAMS)
    val client = connection.newStream(headerEntries("b", "banana"), false)
    assertThat(client.source.read(Buffer(), 1)).isEqualTo(-1)

    // Verify the peer received what was expected.
    assertThat(peer.takeFrame().type).isEqualTo(Http2.TYPE_HEADERS)
    assertThat(observer.takeEvent()).isEqualTo(expectedRequestHeaders)
    assertThat(observer.takeEvent()).isEqualTo(expectedResponseHeaders)
  }

  @Test fun doublePushPromise() {
    // Write the mocking script.
    peer.sendFrame().settings(Settings())
    peer.acceptFrame() // ACK
    peer.sendFrame().pushPromise(3, 2, headerEntries("a", "android"))
    peer.acceptFrame() // SYN_REPLY
    peer.sendFrame().pushPromise(3, 2, headerEntries("b", "banana"))
    peer.acceptFrame() // RST_STREAM
    peer.play()

    // Play it back.
    val connection = connect(peer)
    connection.newStream(headerEntries("b", "banana"), false)

    // Verify the peer received what was expected.
    assertThat(peer.takeFrame().type).isEqualTo(Http2.TYPE_HEADERS)
    assertThat(peer.takeFrame().errorCode).isEqualTo(ErrorCode.PROTOCOL_ERROR)
  }

  @Test fun pushPromiseStreamsAutomaticallyCancel() {
    // Write the mocking script.
    peer.sendFrame().settings(Settings())
    peer.acceptFrame() // ACK
    peer
      .sendFrame()
      .pushPromise(
        streamId = 3,
        promisedStreamId = 2,
        requestHeaders =
          listOf(
            Header(Header.TARGET_METHOD, "GET"),
            Header(Header.TARGET_SCHEME, "https"),
            Header(Header.TARGET_AUTHORITY, "squareup.com"),
            Header(Header.TARGET_PATH, "/cached"),
          ),
      )
    peer
      .sendFrame()
      .headers(
        outFinished = true,
        streamId = 2,
        headerBlock =
          listOf(
            Header(Header.RESPONSE_STATUS, "200"),
          ),
      )
    peer.acceptFrame() // RST_STREAM
    peer.play()

    // Play it back.
    connect(peer, PushObserver.CANCEL, Http2Connection.Listener.REFUSE_INCOMING_STREAMS)

    // Verify the peer received what was expected.
    val rstStream = peer.takeFrame()
    assertThat(rstStream.type).isEqualTo(Http2.TYPE_RST_STREAM)
    assertThat(rstStream.streamId).isEqualTo(2)
    assertThat(rstStream.errorCode).isEqualTo(ErrorCode.CANCEL)
  }

  /**
   * When writing a set of headers fails due to an `IOException`, make sure the writer is left
   * in a consistent state so the next writer also gets an `IOException` also instead of
   * something worse (like an [IllegalStateException].
   *
   *
   * See https://github.com/lysine-dev/okhttp/issues/1651
   */
  @Test fun socketExceptionWhileWritingHeaders() {
    peer.acceptFrame() // SYN_STREAM.
    peer.play()
    val longString = repeat('a', Http2.INITIAL_MAX_FRAME_SIZE + 1)
    val socket = peer.openSocket()
    val connection =
      Http2Connection
        .Builder(true, TaskRunner.INSTANCE)
        .socket(socket.asBufferedSocket(), "peer")
        .pushObserver(IGNORE)
        .build()
    connection.start(sendConnectionPreface = false)
    socket.shutdownOutput()
    assertFailsWith<IOException> {
      connection.newStream(headerEntries("a", longString), false)
    }
    assertFailsWith<IOException> {
      connection.newStream(headerEntries("b", longString), false)
    }
  }

  @Test fun clientCreatesStreamAndServerReplies() {
    // Write the mocking script.
    peer.sendFrame().settings(Settings())
    peer.acceptFrame() // ACK
    peer.acceptFrame() // SYN_STREAM
    peer.acceptFrame() // DATA
    peer.sendFrame().headers(false, 3, headerEntries("a", "android"))
    peer.sendFrame().data(true, 3, Buffer().writeUtf8("robot"), 5)
    peer.acceptFrame() // PING
    peer.sendFrame().ping(true, Http2Connection.AWAIT_PING, 0) // PING
    peer.play()

    // Play it back.
    val connection = connect(peer)
    val stream = connection.newStream(headerEntries("b", "banana"), true)
    val out = stream.sink.buffer()
    out.writeUtf8("c3po")
    out.close()
    assertThat(stream.takeHeaders()).isEqualTo(headersOf("a", "android"))
    assertStreamData("robot", stream.source)
    connection.writePingAndAwaitPong()
    assertThat(connection.openStreamCount()).isEqualTo(0)

    // Verify the peer received what was expected.
    val synStream = peer.takeFrame()
    assertThat(synStream.type).isEqualTo(Http2.TYPE_HEADERS)
    assertThat(synStream.outFinished).isFalse()
    assertThat(synStream.streamId).isEqualTo(3)
    assertThat(synStream.associatedStreamId).isEqualTo(-1)
    assertThat(synStream.headerBlock).isEqualTo(headerEntries("b", "banana"))
    val requestData = peer.takeFrame()
    assertArrayEquals("c3po".toByteArray(), requestData.data)
  }

  @Test fun serverFinishesStreamWithHeaders() {
    // Write the mocking script.
    peer.sendFrame().settings(Settings())
    peer.acceptFrame() // ACK
    peer.acceptFrame() // SYN_STREAM
    peer.acceptFrame() // PING
    peer.sendFrame().headers(true, 3, headerEntries("headers", "bam"))
    peer.sendFrame().ping(true, Http2Connection.AWAIT_PING, 0) // PONG
    peer.play()

    // Play it back.
    val connection = connect(peer)
    val stream = connection.newStream(headerEntries("a", "artichaut"), false)
    connection.writePingAndAwaitPong()
    assertThat(stream.takeHeaders()).isEqualTo(headersOf("headers", "bam"))
    assertThat(stream.peekTrailers()).isEqualTo(Headers.EMPTY)
    assertThat(connection.openStreamCount()).isEqualTo(0)

    // Verify the peer received what was expected.
    val synStream = peer.takeFrame()
    assertThat(synStream.type).isEqualTo(Http2.TYPE_HEADERS)
    assertThat(synStream.outFinished).isFalse()
    assertThat(synStream.streamId).isEqualTo(3)
    assertThat(synStream.associatedStreamId).isEqualTo(-1)
    assertThat(synStream.headerBlock).isEqualTo(headerEntries("a", "artichaut"))
  }

  @Test fun serverWritesTrailersAndClientReadsTrailers() {
    // Write the mocking script.
    peer.sendFrame().settings(Settings())
    peer.acceptFrame() // ACK
    peer.acceptFrame() // SYN_STREAM
    peer.sendFrame().headers(false, 3, headerEntries("headers", "bam"))
    peer.acceptFrame() // PING
    peer.sendFrame().headers(true, 3, headerEntries("trailers", "boom"))
    peer.sendFrame().ping(true, Http2Connection.AWAIT_PING, 0) // PONG
    peer.play()

    // Play it back.
    val connection = connect(peer)
    val stream = connection.newStream(headerEntries("a", "artichaut"), false)
    assertThat(stream.takeHeaders()).isEqualTo(headersOf("headers", "bam"))
    connection.writePingAndAwaitPong()
    assertThat(stream.peekTrailers()).isEqualTo(headersOf("trailers", "boom"))
    assertThat(connection.openStreamCount()).isEqualTo(0)

    // Verify the peer received what was expected.
    val synStream = peer.takeFrame()
    assertThat(synStream.type).isEqualTo(Http2.TYPE_HEADERS)
    assertThat(synStream.outFinished).isFalse()
    assertThat(synStream.streamId).isEqualTo(3)
    assertThat(synStream.associatedStreamId).isEqualTo(-1)
    assertThat(synStream.headerBlock).isEqualTo(headerEntries("a", "artichaut"))
  }

  /** A server RST_STREAM shouldn't prevent the client from consuming the response body.  */
  @Test fun serverResponseBodyRstStream() {
    // Write the mocking script.
    peer.sendFrame().settings(Settings())
    peer.acceptFrame() // ACK
    peer.acceptFrame() // SYN_STREAM
    peer.acceptFrame() // PING
    peer.sendFrame().headers(false, 3, headerEntries("a", "android"))
    peer.sendFrame().data(true, 3, Buffer().writeUtf8("robot"), 5)
    peer.sendFrame().rstStream(3, ErrorCode.NO_ERROR)
    peer.sendFrame().ping(true, Http2Connection.AWAIT_PING, 0) // PONG
    peer.play()

    // Play it back.
    val connection = connect(peer)
    val stream = connection.newStream(headerEntries(), false)
    connection.writePingAndAwaitPong()
    assertThat(stream.takeHeaders()).isEqualTo(headersOf("a", "android"))
    val source = stream.source.buffer()
    assertThat(source.readUtf8(5)).isEqualTo("robot")
    stream.sink.close()
    assertThat(connection.openStreamCount()).isEqualTo(0)

    // Verify the peer received what was expected.
    val synStream = peer.takeFrame()
    assertThat(synStream.type).isEqualTo(Http2.TYPE_HEADERS)
    val ping = peer.takeFrame()
    assertThat(ping.type).isEqualTo(Http2.TYPE_PING)
  }

  /** A server RST_STREAM shouldn't prevent the client from consuming trailers.  */
  @Test fun serverTrailersRstStream() {
    // Write the mocking script.
    peer.sendFrame().settings(Settings())
    peer.acceptFrame() // ACK
    peer.acceptFrame() // SYN_STREAM
    peer.acceptFrame() // PING
    peer.sendFrame().headers(false, 3, headerEntries("a", "android"))
    peer.sendFrame().headers(true, 3, headerEntries("z", "zebra"))
    peer.sendFrame().rstStream(3, ErrorCode.NO_ERROR)
    peer.sendFrame().ping(true, Http2Connection.AWAIT_PING, 0) // PONG
    peer.play()

    // Play it back.
    val connection = connect(peer)
    val stream = connection.newStream(headerEntries(), true)
    connection.writePingAndAwaitPong()
    assertThat(stream.takeHeaders()).isEqualTo(headersOf("a", "android"))
    stream.sink.close()
    assertThat(stream.peekTrailers()).isEqualTo(headersOf("z", "zebra"))
    assertThat(connection.openStreamCount()).isEqualTo(0)

    // Verify the peer received what was expected.
    val synStream = peer.takeFrame()
    assertThat(synStream.type).isEqualTo(Http2.TYPE_HEADERS)
    val ping = peer.takeFrame()
    assertThat(ping.type).isEqualTo(Http2.TYPE_PING)
  }

  /**
   * A server RST_STREAM shouldn't prevent the client from consuming the response body, even if it
   * follows a truncated request body.
   */
  @Test fun clientRequestBodyServerResponseBodyRstStream() {
    // Write the mocking script.
    peer.sendFrame().settings(Settings())
    peer.acceptFrame() // ACK
    peer.acceptFrame() // SYN_STREAM
    peer.acceptFrame() // PING
    peer.sendFrame().headers(false, 3, headerEntries("a", "android"))
    peer.sendFrame().data(true, 3, Buffer().writeUtf8("robot"), 5)
    peer.sendFrame().rstStream(3, ErrorCode.NO_ERROR)
    peer.sendFrame().ping(true, Http2Connection.AWAIT_PING, 0) // PONG
    peer.play()

    // Play it back.
    val connection = connect(peer)
    val stream = connection.newStream(headerEntries(), true)
    connection.writePingAndAwaitPong()
    val sink = stream.sink.buffer()
    sink.writeUtf8("abc")
    assertFailsWith<StreamResetException> {
      sink.close()
    }.also { expected ->
      assertThat(expected.errorCode).isEqualTo(ErrorCode.NO_ERROR)
    }
    assertThat(stream.takeHeaders()).isEqualTo(headersOf("a", "android"))
    val source = stream.source.buffer()
    assertThat(source.readUtf8(5)).isEqualTo("robot")
    assertThat(connection.openStreamCount()).isEqualTo(0)

    // Verify the peer received what was expected.
    val synStream = peer.takeFrame()
    assertThat(synStream.type).isEqualTo(Http2.TYPE_HEADERS)
    val ping = peer.takeFrame()
    assertThat(ping.type).isEqualTo(Http2.TYPE_PING)
  }

  @Test fun serverWritesTrailersWithData() {
    // We buffer some outbound data and headers and confirm that the END_STREAM flag comes with the
    // headers (and not with the data).

    // Write the mocking script. for the client
    peer.setClient(true)

    // Write the mocking script.
    peer.sendFrame().settings(Settings())
    peer.sendFrame().headers(true, 3, headerEntries("client", "abc"))
    peer.acceptFrame() // ACK
    peer.acceptFrame() // HEADERS STREAM 3
    peer.acceptFrame() // DATA STREAM 3 "abcde"
    peer.acceptFrame() // HEADERS STREAM 3
    peer.play()

    // Play it back.
    val connection = connect(peer)
    val stream = connection.newStream(headerEntries("a", "android"), true)
    stream.enqueueTrailers(headersOf("foo", "bar"))
    val sink = stream.sink.buffer()
    sink.writeUtf8("abcdefghi")
    sink.close()

    // Verify the peer received what was expected.
    val headers1 = peer.takeFrame()
    assertThat(headers1.type).isEqualTo(Http2.TYPE_HEADERS)
    val data1 = peer.takeFrame()
    assertThat(data1.type).isEqualTo(Http2.TYPE_DATA)
    assertThat(data1.streamId).isEqualTo(3)
    assertArrayEquals("abcdefghi".toByteArray(), data1.data)
    assertThat(data1.inFinished).isFalse()
    val headers2 = peer.takeFrame()
    assertThat(headers2.type).isEqualTo(Http2.TYPE_HEADERS)
    assertThat(headers2.inFinished).isTrue()
  }

  @Test fun clientCannotReadTrailersWithoutExhaustingStream() {
    // Write the mocking script.
    peer.sendFrame().settings(Settings())
    peer.acceptFrame() // ACK
    peer.acceptFrame() // SYN_STREAM
    peer.sendFrame().data(false, 3, Buffer().writeUtf8("robot"), 5)
    peer.sendFrame().headers(true, 3, headerEntries("trailers", "boom"))
    peer.acceptFrame() // PING
    peer.sendFrame().ping(true, Http2Connection.AWAIT_PING, 0) // PONG
    peer.play()

    // Play it back.
    val connection = connect(peer)
    val stream = connection.newStream(headerEntries("a", "artichaut"), true)
    connection.writePingAndAwaitPong()
    assertThat(stream.peekTrailers()).isNull()
  }

  @Test fun clientCannotReadTrailersIfTheStreamFailed() {
    // Write the mocking script.
    peer.sendFrame().settings(Settings())
    peer.acceptFrame() // ACK
    peer.acceptFrame() // SYN_STREAM
    peer.sendFrame().rstStream(3, ErrorCode.PROTOCOL_ERROR)
    peer.acceptFrame() // PING
    peer.sendFrame().ping(true, Http2Connection.AWAIT_PING, 0) // PONG
    peer.play()

    // Play it back.
    val connection = connect(peer)
    val stream = connection.newStream(headerEntries("a", "artichaut"), true)
    connection.writePingAndAwaitPong()
    assertFailsWith<StreamResetException> {
      stream.peekTrailers()
    }
  }

  @Test fun serverCannotEnqueueTrailersAfterFinishingTheStream() {
    peer.setClient(true)
    peer.sendFrame().settings(Settings())
    peer.acceptFrame() // ACK
    peer.acceptFrame() // PING
    peer.sendFrame().ping(true, Http2Connection.AWAIT_PING, 0)
    peer.play()

    // Play it back.
    val connection = connect(peer)
    connection.writePingAndAwaitPong()
    val stream = connection.newStream(headerEntries("a", "android"), true)
    // finish the stream
    stream.writeHeaders(headerEntries("b", "berserk"), true, false)
    assertFailsWith<IllegalStateException> {
      stream.enqueueTrailers(headersOf("trailers", "boom"))
    }
  }

  @Test fun noTrailersFrameYieldsEmptyTrailers() {
    // Write the mocking script.
    peer.sendFrame().settings(Settings())
    peer.acceptFrame() // ACK
    peer.acceptFrame() // SYN_STREAM
    peer.sendFrame().headers(false, 3, headerEntries("headers", "bam"))
    peer.sendFrame().data(true, 3, Buffer().writeUtf8("robot"), 5)
    peer.acceptFrame() // PING
    peer.sendFrame().ping(true, Http2Connection.AWAIT_PING, 0) // PONG
    peer.play()

    // Play it back.
    val connection = connect(peer)
    val stream = connection.newStream(headerEntries("a", "artichaut"), false)
    val source = stream.source.buffer()
    connection.writePingAndAwaitPong()
    assertThat(stream.takeHeaders()).isEqualTo(headersOf("headers", "bam"))
    assertThat(source.readUtf8(5)).isEqualTo("robot")
    assertThat(stream.peekTrailers()).isEqualTo(Headers.EMPTY)
    assertThat(connection.openStreamCount()).isEqualTo(0)

    // Verify the peer received what was expected.
    val synStream = peer.takeFrame()
    assertThat(synStream.type).isEqualTo(Http2.TYPE_HEADERS)
    assertThat(synStream.outFinished).isFalse()
    assertThat(synStream.streamId).isEqualTo(3)
    assertThat(synStream.associatedStreamId).isEqualTo(-1)
    assertThat(synStream.headerBlock).isEqualTo(headerEntries("a", "artichaut"))
  }

  @Test fun serverReadsHeadersDataHeaders() {
    // Write the mocking script.
    peer.sendFrame().settings(Settings())
    peer.acceptFrame() // ACK
    peer.acceptFrame() // SYN_STREAM
    peer.acceptFrame() // DATA
    peer.acceptFrame() // HEADERS
    peer.sendFrame().headers(true, 3, headerEntries("a", "android"))
    peer.acceptFrame() // PING
    peer.sendFrame().ping(true, Http2Connection.AWAIT_PING, 0) // PING
    peer.play()

    // Play it back.
    val connection = connect(peer)
    val stream = connection.newStream(headerEntries("b", "banana"), true)
    val out = stream.sink.buffer()
    out.writeUtf8("c3po")
    out.close()
    stream.writeHeaders(headerEntries("e", "elephant"), false, false)
    connection.writePingAndAwaitPong()
    assertThat(connection.openStreamCount()).isEqualTo(0)

    // Verify the peer received what was expected.
    val synStream = peer.takeFrame()
    assertThat(synStream.type).isEqualTo(Http2.TYPE_HEADERS)
    assertThat(synStream.outFinished).isFalse()
    assertThat(synStream.streamId).isEqualTo(3)
    assertThat(synStream.associatedStreamId).isEqualTo(-1)
    assertThat(synStream.headerBlock).isEqualTo(headerEntries("b", "banana"))
    val requestData = peer.takeFrame()
    assertArrayEquals("c3po".toByteArray(), requestData.data)
    val nextFrame = peer.takeFrame()
    assertThat(nextFrame.headerBlock).isEqualTo(headerEntries("e", "elephant"))
  }

  @Test fun clientCreatesStreamAndServerRepliesWithFin() {
    // Write the mocking script.
    peer.sendFrame().settings(Settings())
    peer.acceptFrame() // ACK
    peer.acceptFrame() // SYN_STREAM
    peer.acceptFrame() // PING
    peer.sendFrame().headers(true, 3, headerEntries("a", "android"))
    peer.sendFrame().ping(true, Http2Connection.AWAIT_PING, 0)
    peer.play()

    // Play it back.
    val connection = connect(peer)
    connection.newStream(headerEntries("b", "banana"), false)
    assertThat(connection.openStreamCount()).isEqualTo(1)
    connection.writePingAndAwaitPong() // Ensure that the SYN_REPLY has been received.
    assertThat(connection.openStreamCount()).isEqualTo(0)

    // Verify the peer received what was expected.
    val synStream = peer.takeFrame()
    assertThat(synStream.type).isEqualTo(Http2.TYPE_HEADERS)
    val ping = peer.takeFrame()
    assertThat(ping.type).isEqualTo(Http2.TYPE_PING)
  }

  @Test fun serverPingsClient() {
    // Write the mocking script.
    peer.sendFrame().settings(Settings())
    peer.acceptFrame() // ACK
    peer.sendFrame().ping(false, 2, 0)
    peer.acceptFrame() // PING
    peer.play()

    // Play it back.
    connect(peer)

    // Verify the peer received what was expected.
    val ping = peer.takeFrame()
    assertThat(ping.streamId).isEqualTo(0)
    assertThat(ping.payload1).isEqualTo(2)
    assertThat(ping.payload2).isEqualTo(0)
    assertThat(ping.ack).isTrue()
  }

  @Test fun clientPingsServer() {
    // Write the mocking script.
    peer.sendFrame().settings(Settings())
    peer.acceptFrame() // ACK
    peer.acceptFrame() // PING
    peer.sendFrame().ping(true, Http2Connection.AWAIT_PING, 5)
    peer.play()

    // Play it back.
    val connection = connect(peer)
    val pingAtNanos = System.nanoTime()
    connection.writePingAndAwaitPong()
    val elapsedNanos = System.nanoTime() - pingAtNanos
    assertThat(elapsedNanos).isGreaterThan(0L)
    assertThat(elapsedNanos).isLessThan(TimeUnit.SECONDS.toNanos(1))

    // Verify the peer received what was expected.
    val pingFrame = peer.takeFrame()
    assertThat(pingFrame.type).isEqualTo(Http2.TYPE_PING)
    assertThat(pingFrame.streamId).isEqualTo(0)
    assertThat(pingFrame.payload1).isEqualTo(Http2Connection.AWAIT_PING)
    assertThat(pingFrame.payload2).isEqualTo(0x4f4b6f6b) // OKok.
    assertThat(pingFrame.ack).isFalse()
  }

  @Test fun unexpectedPongIsNotReturned() {
    // Write the mocking script.
    peer.sendFrame().settings(Settings())
    peer.acceptFrame() // ACK
    peer.sendFrame().ping(false, 2, 0)
    peer.acceptFrame() // PING
    peer.sendFrame().ping(true, 99, 0) // This pong is silently ignored.
    peer.sendFrame().ping(false, 4, 0)
    peer.acceptFrame() // PING
    peer.play()

    // Play it back.
    connect(peer)

    // Verify the peer received what was expected.
    val ping2 = peer.takeFrame()
    assertThat(ping2.payload1).isEqualTo(2)
    val ping4 = peer.takeFrame()
    assertThat(ping4.payload1).isEqualTo(4)
  }

  @Test fun serverSendsSettingsToClient() {
    // Write the mocking script.
    val settings = Settings()
    settings[Settings.MAX_CONCURRENT_STREAMS] = 10
    peer.sendFrame().settings(settings)
    peer.acceptFrame() // ACK
    peer.sendFrame().ping(false, 2, 0)
    peer.acceptFrame() // PING
    peer.play()

    // Play it back.
    val maxConcurrentStreamsUpdated = CountDownLatch(1)
    val maxConcurrentStreams = AtomicInteger()
    val listener: Http2Connection.Listener =
      object : Http2Connection.Listener() {
        override fun onStream(stream: Http2Stream): Unit = throw AssertionError()

        override fun onSettings(
          connection: Http2Connection,
          settings: Settings,
        ) {
          maxConcurrentStreams.set(settings.getMaxConcurrentStreams())
          maxConcurrentStreamsUpdated.countDown()
        }
      }
    val connection = connect(peer, IGNORE, listener)
    connection.withLock {
      assertThat(connection.peerSettings.getMaxConcurrentStreams()).isEqualTo(10)
    }
    maxConcurrentStreamsUpdated.await()
    assertThat(maxConcurrentStreams.get()).isEqualTo(10)
  }

  @Test fun multipleSettingsFramesAreMerged() {
    // Write the mocking script.
    val settings1 = Settings()
    settings1[Settings.HEADER_TABLE_SIZE] = 10000
    settings1[Settings.INITIAL_WINDOW_SIZE] = 20000
    settings1[Settings.MAX_FRAME_SIZE] = 30000
    peer.sendFrame().settings(settings1)
    peer.acceptFrame() // ACK SETTINGS
    val settings2 = Settings()
    settings2[Settings.INITIAL_WINDOW_SIZE] = 40000
    settings2[Settings.MAX_FRAME_SIZE] = 50000
    settings2[Settings.MAX_CONCURRENT_STREAMS] = 60000
    peer.sendFrame().settings(settings2)
    peer.acceptFrame() // ACK SETTINGS
    peer.sendFrame().ping(false, 2, 0)
    peer.acceptFrame() // PING
    peer.play()

    // Play it back.
    val connection = connect(peer)
    assertThat(peer.takeFrame().type).isEqualTo(Http2.TYPE_SETTINGS)
    assertThat(peer.takeFrame().type).isEqualTo(Http2.TYPE_PING)
    connection.withLock {
      assertThat(connection.peerSettings.headerTableSize).isEqualTo(10000)
      assertThat(connection.peerSettings.initialWindowSize).isEqualTo(40000)
      assertThat(connection.peerSettings.getMaxFrameSize(-1)).isEqualTo(50000)
      assertThat(connection.peerSettings.getMaxConcurrentStreams()).isEqualTo(60000)
    }
  }

  @Test fun clearSettingsBeforeMerge() {
    // Write the mocking script.
    val settings1 = Settings()
    settings1[Settings.HEADER_TABLE_SIZE] = 10000
    settings1[Settings.INITIAL_WINDOW_SIZE] = 20000
    settings1[Settings.MAX_FRAME_SIZE] = 30000
    peer.sendFrame().settings(settings1)
    peer.acceptFrame() // ACK
    peer.sendFrame().ping(false, 2, 0)
    peer.acceptFrame()
    peer.play()

    // Play it back.
    val connection = connect(peer)

    // fake a settings frame with clear flag set.
    val settings2 = Settings()
    settings2[Settings.MAX_CONCURRENT_STREAMS] = 60000
    connection.readerRunnable.applyAndAckSettings(true, settings2)
    connection.withLock {
      assertThat(connection.peerSettings.headerTableSize).isEqualTo(-1)
      assertThat(connection.peerSettings.initialWindowSize)
        .isEqualTo(Settings.DEFAULT_INITIAL_WINDOW_SIZE)
      assertThat(connection.peerSettings.getMaxFrameSize(-1)).isEqualTo(-1)
      assertThat(connection.peerSettings.getMaxConcurrentStreams()).isEqualTo(60000)
    }
  }

  @Test fun bogusDataFrameDoesNotDisruptConnection() {
    // Write the mocking script.
    peer.sendFrame().settings(Settings())
    peer.acceptFrame() // ACK
    peer.sendFrame().data(true, 41, Buffer().writeUtf8("bogus"), 5)
    peer.acceptFrame() // RST_STREAM
    peer.sendFrame().ping(false, 2, 0)
    peer.acceptFrame() // PING
    peer.play()

    // Play it back.
    connect(peer)

    // Verify the peer received what was expected.
    val rstStream = peer.takeFrame()
    assertThat(rstStream.type).isEqualTo(Http2.TYPE_RST_STREAM)
    assertThat(rstStream.streamId).isEqualTo(41)
    assertThat(rstStream.errorCode).isEqualTo(ErrorCode.PROTOCOL_ERROR)
    val ping = peer.takeFrame()
    assertThat(ping.payload1).isEqualTo(2)
  }

  @Test fun bogusReplySilentlyIgnored() {
    // Write the mocking script.
    peer.sendFrame().settings(Settings())
    peer.acceptFrame() // ACK
    peer.sendFrame().headers(false, 41, headerEntries("a", "android"))
    peer.sendFrame().ping(false, 2, 0)
    peer.acceptFrame() // PING
    peer.play()

    // Play it back.
    connect(peer)

    // Verify the peer received what was expected.
    val ping = peer.takeFrame()
    assertThat(ping.payload1).isEqualTo(2)
  }

  @Test fun serverClosesClientOutputStream() {
    // Write the mocking script.
    peer.sendFrame().settings(Settings())
    peer.acceptFrame() // ACK
    peer.acceptFrame() // SYN_STREAM
    peer.sendFrame().rstStream(3, ErrorCode.CANCEL)
    peer.acceptFrame() // PING
    peer.sendFrame().ping(true, Http2Connection.AWAIT_PING, 0)
    peer.play()

    // Play it back.
    val connection = connect(peer)
    val stream = connection.newStream(headerEntries("a", "android"), true)
    val out = stream.sink.buffer()
    connection.writePingAndAwaitPong() // Ensure that the RST_CANCEL has been received.
    assertFailsWith<IOException> {
      out.writeUtf8("square")
      out.flush()
    }.also { expected ->
      assertThat(expected.message).isEqualTo("stream was reset: CANCEL")
    }
    // Close throws because buffered data wasn't flushed.
    assertFailsWith<IOException> {
      out.close()
    }
    assertThat(connection.openStreamCount()).isEqualTo(0)

    // Verify the peer received what was expected.
    val synStream = peer.takeFrame()
    assertThat(synStream.type).isEqualTo(Http2.TYPE_HEADERS)
    assertThat(synStream.inFinished).isFalse()
    assertThat(synStream.outFinished).isFalse()
    val ping = peer.takeFrame()
    assertThat(ping.type).isEqualTo(Http2.TYPE_PING)
  }

  /**
   * Test that the client sends a RST_STREAM if doing so won't disrupt the output stream.
   */
  @Test fun clientClosesClientInputStream() {
    // Write the mocking script.
    peer.sendFrame().settings(Settings())
    peer.acceptFrame() // ACK
    peer.acceptFrame() // SYN_STREAM
    peer.acceptFrame() // RST_STREAM
    peer.play()

    // Play it back.
    val connection = connect(peer)
    val stream = connection.newStream(headerEntries("a", "android"), false)
    val source = stream.source
    val out = stream.sink.buffer()
    source.close()
    assertFailsWith<IOException> {
      source.read(Buffer(), 1)
    }.also { expected ->
      assertThat(expected.message).isEqualTo("stream closed")
    }
    assertFailsWith<IOException> {
      out.writeUtf8("a")
      out.flush()
    }.also { expected ->
      assertThat(expected.message).isEqualTo("stream finished")
    }
    assertThat(connection.openStreamCount()).isEqualTo(0)

    // Verify the peer received what was expected.
    val synStream = peer.takeFrame()
    assertThat(synStream.type).isEqualTo(Http2.TYPE_HEADERS)
    assertThat(synStream.inFinished).isTrue()
    assertThat(synStream.outFinished).isFalse()
    val rstStream = peer.takeFrame()
    assertThat(rstStream.type).isEqualTo(Http2.TYPE_RST_STREAM)
    assertThat(rstStream.errorCode).isEqualTo(ErrorCode.CANCEL)
  }

  /**
   * Test that the client doesn't send a RST_STREAM if doing so will disrupt the output stream.
   */
  @Test fun clientClosesClientInputStreamIfOutputStreamIsClosed() {
    // Write the mocking script.
    peer.sendFrame().settings(Settings())
    peer.acceptFrame() // ACK
    peer.acceptFrame() // SYN_STREAM
    peer.acceptFrame() // DATA
    peer.acceptFrame() // DATA with FLAG_FIN
    peer.acceptFrame() // RST_STREAM
    peer.play()

    // Play it back.
    val connection = connect(peer)
    val stream = connection.newStream(headerEntries("a", "android"), true)
    val source = stream.source
    val out = stream.sink.buffer()
    source.close()
    assertFailsWith<IOException> {
      source.read(Buffer(), 1)
    }.also { expected ->
      assertThat(expected.message).isEqualTo("stream closed")
    }
    out.writeUtf8("square")
    out.flush()
    out.close()
    assertThat(connection.openStreamCount()).isEqualTo(0)

    // Verify the peer received what was expected.
    val synStream = peer.takeFrame()
    assertThat(synStream.type).isEqualTo(Http2.TYPE_HEADERS)
    assertThat(synStream.inFinished).isFalse()
    assertThat(synStream.outFinished).isFalse()
    val data = peer.takeFrame()
    assertThat(data.type).isEqualTo(Http2.TYPE_DATA)
    assertArrayEquals("square".toByteArray(), data.data)
    val fin = peer.takeFrame()
    assertThat(fin.type).isEqualTo(Http2.TYPE_DATA)
    assertThat(fin.inFinished).isTrue()
    assertThat(fin.outFinished).isFalse()
    val rstStream = peer.takeFrame()
    assertThat(rstStream.type).isEqualTo(Http2.TYPE_RST_STREAM)
    assertThat(rstStream.errorCode).isEqualTo(ErrorCode.CANCEL)
  }

  @Test fun serverClosesClientInputStream() {
    // Write the mocking script.
    peer.sendFrame().settings(Settings())
    peer.acceptFrame() // ACK
    peer.acceptFrame() // SYN_STREAM
    peer.sendFrame().headers(false, 3, headerEntries("b", "banana"))
    peer.sendFrame().data(true, 3, Buffer().writeUtf8("square"), 6)
    peer.acceptFrame() // PING
    peer.sendFrame().ping(true, Http2Connection.AWAIT_PING, 0)
    peer.play()

    // Play it back.
    val connection = connect(peer)
    val stream = connection.newStream(headerEntries("a", "android"), false)
    val source = stream.source
    assertStreamData("square", source)
    connection.writePingAndAwaitPong() // Ensure that inFinished has been received.
    assertThat(connection.openStreamCount()).isEqualTo(0)

    // Verify the peer received what was expected.
    val synStream = peer.takeFrame()
    assertThat(synStream.type).isEqualTo(Http2.TYPE_HEADERS)
    assertThat(synStream.inFinished).isTrue()
    assertThat(synStream.outFinished).isFalse()
  }

  @Test fun remoteDoubleSynReply() {
    // Write the mocking script.
    peer.sendFrame().settings(Settings())
    peer.acceptFrame() // ACK
    peer.acceptFrame() // SYN_STREAM
    peer.sendFrame().headers(false, 3, headerEntries("a", "android"))
    peer.acceptFrame() // PING
    peer.sendFrame().headers(false, 3, headerEntries("b", "banana"))
    peer.sendFrame().ping(true, Http2Connection.AWAIT_PING, 0)
    peer.play()

    // Play it back.
    val connection = connect(peer)
    val stream = connection.newStream(headerEntries("c", "cola"), false)
    assertThat(stream.takeHeaders()).isEqualTo(headersOf("a", "android"))
    connection.writePingAndAwaitPong() // Ensure that the 2nd SYN REPLY has been received.

    // Verify the peer received what was expected.
    val synStream = peer.takeFrame()
    assertThat(synStream.type).isEqualTo(Http2.TYPE_HEADERS)
    val ping = peer.takeFrame()
    assertThat(ping.type).isEqualTo(Http2.TYPE_PING)
  }

  @Test fun remoteSendsDataAfterInFinished() {
    // Write the mocking script.
    peer.sendFrame().settings(Settings())
    peer.acceptFrame() // ACK
    peer.acceptFrame() // SYN_STREAM
    peer.sendFrame().headers(false, 3, headerEntries("a", "android"))
    peer.sendFrame().data(true, 3, Buffer().writeUtf8("robot"), 5)
    peer.sendFrame().data(true, 3, Buffer().writeUtf8("c3po"), 4)
    peer.acceptFrame() // RST_STREAM
    peer.sendFrame().ping(false, 2, 0) // Ping just to make sure the stream was fastforwarded.
    peer.acceptFrame() // PING
    peer.play()

    // Play it back.
    val connection = connect(peer)
    val stream = connection.newStream(headerEntries("b", "banana"), false)
    assertThat(stream.takeHeaders()).isEqualTo(headersOf("a", "android"))
    assertStreamData("robot", stream.source)

    // Verify the peer received what was expected.
    val synStream = peer.takeFrame()
    assertThat(synStream.type).isEqualTo(Http2.TYPE_HEADERS)
    val rstStream = peer.takeFrame()
    assertThat(rstStream.type).isEqualTo(Http2.TYPE_RST_STREAM)
    assertThat(rstStream.streamId).isEqualTo(3)
    val ping = peer.takeFrame()
    assertThat(ping.type).isEqualTo(Http2.TYPE_PING)
    assertThat(ping.payload1).isEqualTo(2)
  }

  @Test fun clientDoesNotLimitFlowControl() {
    val dataLength = 16384
    // Write the mocking script.
    peer.sendFrame().settings(Settings())
    peer.acceptFrame() // ACK
    peer.acceptFrame() // SYN_STREAM
    peer.sendFrame().headers(false, 3, headerEntries("b", "banana"))
    peer.sendFrame().data(false, 3, Buffer().write(ByteArray(dataLength)), dataLength)
    peer.sendFrame().data(false, 3, Buffer().write(ByteArray(dataLength)), dataLength)
    peer.sendFrame().data(false, 3, Buffer().write(ByteArray(dataLength)), dataLength)
    peer.sendFrame().data(false, 3, Buffer().write(ByteArray(dataLength)), dataLength)
    peer.sendFrame().data(false, 3, Buffer().write(ByteArray(1)), 1)
    peer.sendFrame().ping(false, 2, 0) // Ping just to make sure the stream was fastforwarded.
    peer.acceptFrame() // PING
    peer.play()

    // Play it back.
    val connection = connect(peer)
    val stream = connection.newStream(headerEntries("a", "android"), false)
    assertThat(stream.takeHeaders()).isEqualTo(headersOf("b", "banana"))

    // Verify the peer received what was expected.
    val synStream = peer.takeFrame()
    assertThat(synStream.type).isEqualTo(Http2.TYPE_HEADERS)
    val ping = peer.takeFrame()
    assertThat(ping.type).isEqualTo(Http2.TYPE_PING)
    assertThat(ping.payload1).isEqualTo(2)
  }

  @Test fun remoteSendsRefusedStreamBeforeReplyHeaders() {
    // Write the mocking script.
    peer.sendFrame().settings(Settings())
    peer.acceptFrame() // ACK
    peer.acceptFrame() // SYN_STREAM
    peer.sendFrame().rstStream(3, ErrorCode.REFUSED_STREAM)
    peer.sendFrame().ping(false, 2, 0)
    peer.acceptFrame() // PING
    peer.play()

    // Play it back.
    val connection = connect(peer)
    val stream = connection.newStream(headerEntries("a", "android"), false)
    assertFailsWith<IOException> {
      stream.takeHeaders()
    }.also { expected ->
      assertThat(expected.message).isEqualTo("stream was reset: REFUSED_STREAM")
    }
    assertThat(connection.openStreamCount()).isEqualTo(0)

    // Verify the peer received what was expected.
    val synStream = peer.takeFrame()
    assertThat(synStream.type).isEqualTo(Http2.TYPE_HEADERS)
    val ping = peer.takeFrame()
    assertThat(ping.type).isEqualTo(Http2.TYPE_PING)
    assertThat(ping.payload1).isEqualTo(2)
  }

  @Test fun receiveGoAway() {
    // Write the mocking script.
    peer.sendFrame().settings(Settings())
    peer.acceptFrame() // ACK
    peer.acceptFrame() // SYN_STREAM 1
    peer.acceptFrame() // SYN_STREAM 3
    peer.acceptFrame() // PING.
    peer.sendFrame().goAway(3, ErrorCode.PROTOCOL_ERROR, EMPTY_BYTE_ARRAY)
    peer.sendFrame().ping(true, Http2Connection.AWAIT_PING, 0)
    peer.acceptFrame() // DATA STREAM 1
    peer.play()

    // Play it back.
    val connection = connect(peer)
    val stream1 = connection.newStream(headerEntries("a", "android"), true)
    val stream2 = connection.newStream(headerEntries("b", "banana"), true)
    connection.writePingAndAwaitPong() // Ensure the GO_AWAY that resets stream2 has been received.
    val sink1 = stream1.sink.buffer()
    val sink2 = stream2.sink.buffer()
    sink1.writeUtf8("abc")
    assertFailsWith<IOException> {
      sink2.writeUtf8("abc")
      sink2.flush()
    }.also { expected ->
      assertThat(expected.message).isEqualTo("stream was reset: REFUSED_STREAM")
    }
    sink1.writeUtf8("def")
    sink1.close()
    assertFailsWith<ConnectionShutdownException> {
      connection.newStream(headerEntries("c", "cola"), false)
    }
    assertThat(stream1.isOpen).isTrue()
    assertThat(stream2.isOpen).isFalse()
    assertThat(connection.openStreamCount()).isEqualTo(1)

    // Verify the peer received what was expected.
    val synStream1 = peer.takeFrame()
    assertThat(synStream1.type).isEqualTo(Http2.TYPE_HEADERS)
    val synStream2 = peer.takeFrame()
    assertThat(synStream2.type).isEqualTo(Http2.TYPE_HEADERS)
    val ping = peer.takeFrame()
    assertThat(ping.type).isEqualTo(Http2.TYPE_PING)
    val data1 = peer.takeFrame()
    assertThat(data1.type).isEqualTo(Http2.TYPE_DATA)
    assertThat(data1.streamId).isEqualTo(3)
    assertArrayEquals("abcdef".toByteArray(), data1.data)
  }

  @Test fun sendGoAway() {
    // Write the mocking script.
    peer.sendFrame().settings(Settings())
    peer.acceptFrame() // ACK
    peer.acceptFrame() // SYN_STREAM 1
    peer.acceptFrame() // GOAWAY
    peer.acceptFrame() // PING
    peer.sendFrame().headers(false, 2, headerEntries("b", "b")) // Should be ignored!
    peer.sendFrame().ping(true, Http2Connection.AWAIT_PING, 0)
    peer.play()

    // Play it back.
    val connection = connect(peer)
    connection.newStream(headerEntries("a", "android"), false)
    connection.withLock {
      if (!connection.isHealthy(System.nanoTime())) {
        throw ConnectionShutdownException()
      }
    }
    connection.writePing()
    connection.shutdown(ErrorCode.PROTOCOL_ERROR)
    assertThat(connection.openStreamCount()).isEqualTo(1)
    connection.awaitPong() // Prevent the peer from exiting prematurely.

    // Verify the peer received what was expected.
    val synStream1 = peer.takeFrame()
    assertThat(synStream1.type).isEqualTo(Http2.TYPE_HEADERS)
    val pingFrame = peer.takeFrame()
    assertThat(pingFrame.type).isEqualTo(Http2.TYPE_PING)
    val goaway = peer.takeFrame()
    assertThat(goaway.type).isEqualTo(Http2.TYPE_GOAWAY)
    assertThat(goaway.streamId).isEqualTo(0)
    assertThat(goaway.errorCode).isEqualTo(ErrorCode.PROTOCOL_ERROR)
  }

  @Test fun close() {
    // Write the mocking script.
    peer.sendFrame().settings(Settings())
    peer.acceptFrame() // ACK
    peer.acceptFrame() // SYN_STREAM
    peer.acceptFrame() // GOAWAY
    peer.acceptFrame() // RST_STREAM
    peer.play()

    // Play it back.
    val connection = connect(peer)
    val stream = connection.newStream(headerEntries("a", "android"), false)
    assertThat(connection.openStreamCount()).isEqualTo(1)
    connection.close()
    assertThat(connection.openStreamCount()).isEqualTo(0)
    assertFailsWith<ConnectionShutdownException> {
      connection.newStream(headerEntries("b", "banana"), false)
    }
    val sink = stream.sink.buffer()
    assertFailsWith<IOException> {
      sink.writeByte(0)
      sink.flush()
    }.also { expected ->
      assertThat(expected.message).isEqualTo("stream finished")
    }
    assertFailsWith<IOException> {
      stream.source.read(Buffer(), 1)
    }.also { expected ->
      assertThat(expected.message).isEqualTo("stream was reset: CANCEL")
    }

    // Verify the peer received what was expected.
    val synStream = peer.takeFrame()
    assertThat(synStream.type).isEqualTo(Http2.TYPE_HEADERS)
    val goaway = peer.takeFrame()
    assertThat(goaway.type).isEqualTo(Http2.TYPE_GOAWAY)
    val rstStream = peer.takeFrame()
    assertThat(rstStream.type).isEqualTo(Http2.TYPE_RST_STREAM)
    assertThat(rstStream.streamId).isEqualTo(3)
  }

  @Test fun getResponseHeadersTimesOut() {
    // Write the mocking script.
    peer.sendFrame().settings(Settings())
    peer.acceptFrame() // ACK
    peer.acceptFrame() // SYN_STREAM
    peer.acceptFrame() // RST_STREAM
    peer.play()

    // Play it back.
    val connection = connect(peer)
    val stream = connection.newStream(headerEntries("b", "banana"), false)
    stream.readTimeout().timeout(500, TimeUnit.MILLISECONDS)
    val startNanos = System.nanoTime()
    assertFailsWith<InterruptedIOException> {
      stream.takeHeaders()
    }
    val elapsedNanos = System.nanoTime() - startNanos
    awaitWatchdogIdle()
    // 200ms delta
    assertThat(TimeUnit.NANOSECONDS.toMillis(elapsedNanos).toDouble())
      .isCloseTo(500.0, 200.0)
    assertThat(connection.openStreamCount()).isEqualTo(0)

    // Verify the peer received what was expected.
    assertThat(peer.takeFrame().type).isEqualTo(Http2.TYPE_HEADERS)
    assertThat(peer.takeFrame().type).isEqualTo(Http2.TYPE_RST_STREAM)
  }

  /**
   * Confirm that the client times out if the server stalls after 3 bytes. After the timeout the
   * connection is still considered healthy while we await the degraded pong. When that doesn't
   * arrive the connection goes unhealthy.
   */
  @Test fun readTimesOut() {
    // Write the mocking script.
    peer.sendFrame().settings(Settings())
    peer.acceptFrame() // ACK
    peer.acceptFrame() // SYN_STREAM
    peer.sendFrame().headers(false, 3, headerEntries("a", "android"))
    peer.sendFrame().data(false, 3, Buffer().writeUtf8("abc"), 3)
    peer.acceptFrame() // RST_STREAM
    peer.acceptFrame() // DEGRADED PING
    peer.acceptFrame() // AWAIT PING
    peer.sendFrame().ping(true, Http2Connection.DEGRADED_PING, 1) // DEGRADED PONG
    peer.sendFrame().ping(true, Http2Connection.AWAIT_PING, 0) // AWAIT PONG
    peer.play()

    // Play it back.
    val connection = connect(peer)
    val stream = connection.newStream(headerEntries("b", "banana"), false)
    stream.readTimeout().timeout(500, TimeUnit.MILLISECONDS)
    val source = stream.source.buffer()
    source.require(3)
    val startNanos = System.nanoTime()
    assertFailsWith<InterruptedIOException> {
      source.require(4)
    }
    val elapsedNanos = System.nanoTime() - startNanos
    awaitWatchdogIdle()
    // 200ms delta
    assertThat(TimeUnit.NANOSECONDS.toMillis(elapsedNanos).toDouble())
      .isCloseTo(500.0, 200.0)
    assertThat(connection.openStreamCount()).isEqualTo(0)

    // When the timeout is sent the connection doesn't immediately go unhealthy.
    assertThat(connection.isHealthy(System.nanoTime())).isTrue()

    // But if the ping doesn't arrive, the connection goes unhealthy.
    Thread.sleep(TimeUnit.NANOSECONDS.toMillis(Http2Connection.DEGRADED_PONG_TIMEOUT_NS.toLong()))
    assertThat(connection.isHealthy(System.nanoTime())).isFalse()

    // When a pong does arrive, the connection becomes healthy again.
    connection.writePingAndAwaitPong()
    assertThat(connection.isHealthy(System.nanoTime())).isTrue()

    // Verify the peer received what was expected.
    assertThat(peer.takeFrame().type).isEqualTo(Http2.TYPE_HEADERS)
    assertThat(peer.takeFrame().type).isEqualTo(Http2.TYPE_RST_STREAM)
    assertThat(peer.takeFrame().type).isEqualTo(Http2.TYPE_PING)
    assertThat(peer.takeFrame().type).isEqualTo(Http2.TYPE_PING)
  }

  @Test fun writeTimesOutAwaitingStreamWindow() {
    // Set the peer's receive window to 5 bytes!
    val peerSettings = Settings().set(Settings.INITIAL_WINDOW_SIZE, 5)

    // Write the mocking script.
    peer.sendFrame().settings(peerSettings)
    peer.acceptFrame() // ACK SETTINGS
    peer.acceptFrame() // PING
    peer.sendFrame().ping(true, Http2Connection.AWAIT_PING, 0)
    peer.acceptFrame() // SYN_STREAM
    peer.sendFrame().headers(false, 3, headerEntries("a", "android"))
    peer.acceptFrame() // DATA
    peer.acceptFrame() // RST_STREAM
    peer.play()

    // Play it back.
    val connection = connect(peer)
    connection.writePingAndAwaitPong() // Make sure settings have been received.
    val stream = connection.newStream(headerEntries("b", "banana"), true)
    val sink = stream.sink
    sink.write(Buffer().writeUtf8("abcde"), 5)
    stream.writeTimeout().timeout(500, TimeUnit.MILLISECONDS)
    val startNanos = System.nanoTime()
    sink.write(Buffer().writeUtf8("f"), 1)
    assertFailsWith<InterruptedIOException> {
      sink.flush() // This will time out waiting on the write window.
    }
    val elapsedNanos = System.nanoTime() - startNanos
    awaitWatchdogIdle()
    // 200ms delta
    assertThat(TimeUnit.NANOSECONDS.toMillis(elapsedNanos).toDouble())
      .isCloseTo(500.0, 200.0)
    assertThat(connection.openStreamCount()).isEqualTo(0)

    // Verify the peer received what was expected.
    assertThat(peer.takeFrame().type).isEqualTo(Http2.TYPE_PING)
    assertThat(peer.takeFrame().type).isEqualTo(Http2.TYPE_HEADERS)
    assertThat(peer.takeFrame().type).isEqualTo(Http2.TYPE_DATA)
    assertThat(peer.takeFrame().type).isEqualTo(Http2.TYPE_RST_STREAM)
  }

  @Test fun writeTimesOutAwaitingConnectionWindow() {
    // Set the peer's receive window to 5 bytes. Give the stream 5 bytes back, so only the
    // connection-level window is applicable.
    val peerSettings = Settings().set(Settings.INITIAL_WINDOW_SIZE, 5)

    // Write the mocking script.
    peer.sendFrame().settings(peerSettings)
    peer.acceptFrame() // ACK SETTINGS
    peer.acceptFrame() // PING
    peer.sendFrame().ping(true, Http2Connection.AWAIT_PING, 0)
    peer.acceptFrame() // SYN_STREAM
    peer.sendFrame().headers(false, 3, headerEntries("a", "android"))
    peer.acceptFrame() // PING
    peer.sendFrame().ping(true, Http2Connection.AWAIT_PING, 0)
    peer.acceptFrame() // DATA
    peer.acceptFrame() // RST_STREAM
    peer.play()

    // Play it back.
    val connection = connect(peer)
    connection.writePingAndAwaitPong() // Make sure settings have been acked.
    val stream = connection.newStream(headerEntries("b", "banana"), true)
    connection.writePingAndAwaitPong() // Make sure the window update has been received.
    val sink = stream.sink
    stream.writeTimeout().timeout(500, TimeUnit.MILLISECONDS)
    sink.write(Buffer().writeUtf8("abcdef"), 6)
    val startNanos = System.nanoTime()
    assertFailsWith<InterruptedIOException> {
      sink.flush() // This will time out waiting on the write window.
    }
    val elapsedNanos = System.nanoTime() - startNanos
    awaitWatchdogIdle()
    // 200ms delta
    assertThat(TimeUnit.NANOSECONDS.toMillis(elapsedNanos).toDouble())
      .isCloseTo(500.0, 200.0)
    assertThat(connection.openStreamCount()).isEqualTo(0)

    // Verify the peer received what was expected.
    assertThat(peer.takeFrame().type).isEqualTo(Http2.TYPE_PING)
    assertThat(peer.takeFrame().type).isEqualTo(Http2.TYPE_HEADERS)
    assertThat(peer.takeFrame().type).isEqualTo(Http2.TYPE_PING)
    assertThat(peer.takeFrame().type).isEqualTo(Http2.TYPE_DATA)
    assertThat(peer.takeFrame().type).isEqualTo(Http2.TYPE_RST_STREAM)
  }

  @Test fun outgoingWritesAreBatched() {
    // Write the mocking script.
    peer.sendFrame().settings(Settings())
    peer.acceptFrame() // ACK
    peer.acceptFrame() // SYN_STREAM
    peer.sendFrame().headers(false, 3, headerEntries("a", "android"))
    peer.acceptFrame() // DATA
    peer.play()

    // Play it back.
    val connection = connect(peer)
    val stream = connection.newStream(headerEntries("b", "banana"), true)

    // two outgoing writes
    val sink = stream.sink
    sink.write(Buffer().writeUtf8("abcde"), 5)
    sink.write(Buffer().writeUtf8("fghij"), 5)
    sink.close()

    // verify the peer received one incoming frame
    assertThat(peer.takeFrame().type).isEqualTo(Http2.TYPE_HEADERS)
    val data = peer.takeFrame()
    assertThat(data.type).isEqualTo(Http2.TYPE_DATA)
    assertArrayEquals("abcdefghij".toByteArray(), data.data)
    assertThat(data.inFinished).isTrue()
  }

  @Test fun headers() {
    // Write the mocking script.
    peer.sendFrame().settings(Settings())
    peer.acceptFrame() // ACK
    peer.acceptFrame() // SYN_STREAM
    peer.acceptFrame() // PING
    peer
      .sendFrame()
      .headers(false, 3, headerEntries(Header.RESPONSE_STATUS_UTF8, "HTTP/1.1 100"))
    peer
      .sendFrame()
      .headers(false, 3, headerEntries(Header.RESPONSE_STATUS_UTF8, "HTTP/1.1 200"))
    peer.sendFrame().ping(true, Http2Connection.AWAIT_PING, 0)
    peer.play()

    // Play it back.
    val connection = connect(peer)
    val stream = connection.newStream(headerEntries("b", "banana"), true)
    connection.writePingAndAwaitPong() // Ensure that the HEADERS has been received.
    assertThat(stream.takeHeaders())
      .isEqualTo(headersOf(Header.RESPONSE_STATUS_UTF8, "HTTP/1.1 100"))
    assertThat(stream.takeHeaders())
      .isEqualTo(headersOf(Header.RESPONSE_STATUS_UTF8, "HTTP/1.1 200"))

    // Verify the peer received what was expected.
    val synStream = peer.takeFrame()
    assertThat(synStream.type).isEqualTo(Http2.TYPE_HEADERS)
    val ping = peer.takeFrame()
    assertThat(ping.type).isEqualTo(Http2.TYPE_PING)
  }

  @Test fun readMultipleSetsOfResponseHeaders() {
    // Write the mocking script.
    peer.sendFrame().settings(Settings())
    peer.acceptFrame() // ACK
    peer.acceptFrame() // SYN_STREAM
    peer.sendFrame().headers(false, 3, headerEntries("a", "android"))
    peer.acceptFrame() // PING
    peer.sendFrame().headers(true, 3, headerEntries("c", "cola"))
    peer.sendFrame().ping(true, Http2Connection.AWAIT_PING, 0) // PONG
    peer.play()

    // Play it back.
    val connection = connect(peer)
    val stream = connection.newStream(headerEntries("b", "banana"), true)
    stream.connection.flush()
    assertThat(stream.takeHeaders()).isEqualTo(headersOf("a", "android"))
    connection.writePingAndAwaitPong()
    assertThat(stream.peekTrailers()).isEqualTo(headersOf("c", "cola"))

    // Verify the peer received what was expected.
    assertThat(peer.takeFrame().type).isEqualTo(Http2.TYPE_HEADERS)
    assertThat(peer.takeFrame().type).isEqualTo(Http2.TYPE_PING)
  }

  @Test fun readSendsWindowUpdate() {
    val windowSize = 100
    val windowUpdateThreshold = 50

    // Write the mocking script.
    peer.sendFrame().settings(Settings())
    peer.acceptFrame() // ACK
    peer.acceptFrame() // SYN_STREAM
    peer.sendFrame().headers(false, 3, headerEntries("a", "android"))
    for (i in 0..2) {
      // Send frames of summing to size 50, which is windowUpdateThreshold.
      peer.sendFrame().data(false, 3, data(24), 24)
      peer.sendFrame().data(false, 3, data(25), 25)
      peer.sendFrame().data(false, 3, data(1), 1)
      peer.acceptFrame() // connection WINDOW UPDATE
      peer.acceptFrame() // stream WINDOW UPDATE
    }
    peer.sendFrame().data(true, 3, data(0), 0)
    peer.play()

    // Play it back.
    val connection = connect(peer)
    connection.okHttpSettings[Settings.INITIAL_WINDOW_SIZE] = windowSize
    val stream = connection.newStream(headerEntries("b", "banana"), false)
    assertThat(stream.readBytes.acknowledged).isEqualTo(0L)
    assertThat(stream.readBytes.total).isEqualTo(0L)
    assertThat(stream.takeHeaders()).isEqualTo(headersOf("a", "android"))
    val source = stream.source
    val buffer = Buffer()
    buffer.writeAll(source)
    assertThat(source.read(buffer, 1)).isEqualTo(-1)
    assertThat(buffer.size).isEqualTo(150)
    val synStream = peer.takeFrame()
    assertThat(synStream.type).isEqualTo(Http2.TYPE_HEADERS)
    for (i in 0..2) {
      val windowUpdateStreamIds: MutableList<Int?> = ArrayList(2)
      for (j in 0..1) {
        val windowUpdate = peer.takeFrame()
        assertThat(windowUpdate.type).isEqualTo(Http2.TYPE_WINDOW_UPDATE)
        windowUpdateStreamIds.add(windowUpdate.streamId)
        assertThat(windowUpdate.windowSizeIncrement)
          .isEqualTo(windowUpdateThreshold.toLong())
      }
      // connection
      assertThat(windowUpdateStreamIds).contains(0)
      // stream
      assertThat(windowUpdateStreamIds).contains(3)
    }
  }

  @Test fun serverSendsEmptyDataClientDoesntSendWindowUpdate() {
    // Write the mocking script.
    peer.sendFrame().settings(Settings())
    peer.acceptFrame() // ACK
    peer.acceptFrame() // SYN_STREAM
    peer.sendFrame().headers(false, 3, headerEntries("a", "android"))
    peer.sendFrame().data(true, 3, data(0), 0)
    peer.play()

    // Play it back.
    val connection = connect(peer)
    val client = connection.newStream(headerEntries("b", "banana"), false)
    assertThat(client.source.read(Buffer(), 1)).isEqualTo(-1)

    // Verify the peer received what was expected.
    val synStream = peer.takeFrame()
    assertThat(synStream.type).isEqualTo(Http2.TYPE_HEADERS)
    assertThat(peer.frameCount()).isEqualTo(5)
  }

  @Test fun clientSendsEmptyDataServerDoesntSendWindowUpdate() {
    // Write the mocking script.
    peer.sendFrame().settings(Settings())
    peer.acceptFrame() // ACK
    peer.acceptFrame() // SYN_STREAM
    peer.acceptFrame() // DATA
    peer.sendFrame().headers(false, 3, headerEntries("a", "android"))
    peer.play()

    // Play it back.
    val connection = connect(peer)
    val client = connection.newStream(headerEntries("b", "banana"), true)
    val out = client.sink.buffer()
    out.write(EMPTY_BYTE_ARRAY)
    out.flush()
    out.close()

    // Verify the peer received what was expected.
    assertThat(peer.takeFrame().type).isEqualTo(Http2.TYPE_HEADERS)
    assertThat(peer.takeFrame().type).isEqualTo(Http2.TYPE_DATA)
    assertThat(peer.frameCount()).isEqualTo(5)
  }

  @Test fun testTruncatedDataFrame() {
    // Write the mocking script.
    peer.sendFrame().settings(Settings())
    peer.acceptFrame() // ACK
    peer.acceptFrame() // SYN_STREAM
    peer.sendFrame().headers(false, 3, headerEntries("a", "android"))
    peer.sendFrame().data(false, 3, data(1024), 1024)
    peer.truncateLastFrame(8 + 100)
    peer.play()

    // Play it back.
    val connection = connect(peer)
    val stream = connection.newStream(headerEntries("b", "banana"), false)
    assertThat(stream.takeHeaders()).isEqualTo(headersOf("a", "android"))
    val source = stream.source
    assertFailsWith<EOFException> {
      source.buffer().readByteString(101)
    }
  }

  @Test fun blockedStreamDoesntStarveNewStream() {
    val framesThatFillWindow =
      roundUp(Settings.DEFAULT_INITIAL_WINDOW_SIZE, peer.maxOutboundDataLength())

    // Write the mocking script. This accepts more data frames than necessary!
    peer.sendFrame().settings(Settings())
    peer.acceptFrame() // SETTINGS ACK
    peer.acceptFrame() // SYN_STREAM on stream 1
    for (i in 0 until framesThatFillWindow) {
      peer.acceptFrame() // DATA on stream 1
    }
    peer.acceptFrame() // SYN_STREAM on stream 2
    peer.acceptFrame() // DATA on stream 2
    peer.play()

    // Play it back.
    val connection = connect(peer)
    val stream1 = connection.newStream(headerEntries("a", "apple"), true)
    val out1 = stream1.sink.buffer()
    out1.write(ByteArray(Settings.DEFAULT_INITIAL_WINDOW_SIZE))
    out1.flush()

    // Check that we've filled the window for both the stream and also the connection.
    assertThat(connection.writeBytesTotal)
      .isEqualTo(Settings.DEFAULT_INITIAL_WINDOW_SIZE.toLong())
    assertThat(connection.writeBytesMaximum)
      .isEqualTo(Settings.DEFAULT_INITIAL_WINDOW_SIZE.toLong())
    assertThat(stream1.writeBytesTotal)
      .isEqualTo(Settings.DEFAULT_INITIAL_WINDOW_SIZE.toLong())
    assertThat(stream1.writeBytesMaximum)
      .isEqualTo(Settings.DEFAULT_INITIAL_WINDOW_SIZE.toLong())

    // receiving a window update on the connection will unblock new streams.
    connection.readerRunnable.windowUpdate(0, 3)
    assertThat(connection.writeBytesTotal)
      .isEqualTo(Settings.DEFAULT_INITIAL_WINDOW_SIZE.toLong())
    assertThat(connection.writeBytesMaximum)
      .isEqualTo((Settings.DEFAULT_INITIAL_WINDOW_SIZE + 3).toLong())
    assertThat(stream1.writeBytesTotal)
      .isEqualTo(Settings.DEFAULT_INITIAL_WINDOW_SIZE.toLong())
    assertThat(stream1.writeBytesMaximum)
      .isEqualTo(Settings.DEFAULT_INITIAL_WINDOW_SIZE.toLong())

    // Another stream should be able to send data even though 1 is blocked.
    val stream2 = connection.newStream(headerEntries("b", "banana"), true)
    val out2 = stream2.sink.buffer()
    out2.writeUtf8("foo")
    out2.flush()
    assertThat(connection.writeBytesTotal)
      .isEqualTo((Settings.DEFAULT_INITIAL_WINDOW_SIZE + 3).toLong())
    assertThat(connection.writeBytesMaximum)
      .isEqualTo((Settings.DEFAULT_INITIAL_WINDOW_SIZE + 3).toLong())
    assertThat(stream1.writeBytesTotal)
      .isEqualTo(Settings.DEFAULT_INITIAL_WINDOW_SIZE.toLong())
    assertThat(stream1.writeBytesMaximum)
      .isEqualTo(Settings.DEFAULT_INITIAL_WINDOW_SIZE.toLong())
    assertThat(stream2.writeBytesTotal).isEqualTo(3L)
    assertThat(stream2.writeBytesMaximum)
      .isEqualTo(Settings.DEFAULT_INITIAL_WINDOW_SIZE.toLong())
  }

  @Test fun remoteOmitsInitialSettings() {
    // Write the mocking script. Note no SETTINGS frame is sent or acknowledged.
    peer.acceptFrame() // SYN_STREAM
    peer.sendFrame().headers(false, 3, headerEntries("a", "android"))
    peer.acceptFrame() // GOAWAY
    peer.play()
    val connection =
      Http2Connection
        .Builder(true, TaskRunner.INSTANCE)
        .socket(peer.openSocket().asBufferedSocket(), "peer")
        .build()
    connection.start(sendConnectionPreface = false)
    val stream = connection.newStream(headerEntries("b", "banana"), false)
    assertFailsWith<IOException> {
      stream.takeHeaders()
    }.also { expected ->
      assertThat(expected.message).isEqualTo("Expected a SETTINGS frame but was HEADERS")
    }

    // Verify the peer received what was expected.
    val synStream = peer.takeFrame()
    assertThat(synStream.type).isEqualTo(Http2.TYPE_HEADERS)
    val goaway = peer.takeFrame()
    assertThat(goaway.type).isEqualTo(Http2.TYPE_GOAWAY)
    assertThat(goaway.errorCode).isEqualTo(ErrorCode.PROTOCOL_ERROR)
  }

  @Test fun connectionUsesTaskRunner() {
    peer.acceptFrame() // SYN_STREAM.
    peer.play()
    val taskRunner = taskFaker.taskRunner
    val connection =
      Http2Connection
        .Builder(true, taskRunner)
        .socket(peer.openSocket().asBufferedSocket(), "peer")
        .pushObserver(IGNORE)
        .build()
    connection.start(sendConnectionPreface = false)
    val queues = taskRunner.activeQueues()
    assertThat(queues).hasSize(1)
  }

  private fun data(byteCount: Int): Buffer = Buffer().write(ByteArray(byteCount))

  private fun assertStreamData(
    expected: String?,
    source: Source?,
  ) {
    val actual = source!!.buffer().readUtf8()
    assertThat(actual).isEqualTo(expected)
  }

  /**
   * Returns true when all work currently in progress by the watchdog have completed. This method
   * creates more work for the watchdog and waits for that work to be executed. When it is, we know
   * work that preceded this call is complete.
   */
  private fun awaitWatchdogIdle() {
    val latch = CountDownLatch(1)
    val watchdogJob: AsyncTimeout =
      object : AsyncTimeout() {
        override fun timedOut() {
          latch.countDown()
        }
      }
    watchdogJob.deadlineNanoTime(System.nanoTime()) // Due immediately!
    watchdogJob.enter()
    latch.await()
  }

  private fun connectWithSettings(
    client: Boolean,
    settings: Settings?,
  ): Http2Connection {
    peer.setClient(client)
    peer.sendFrame().settings(settings!!)
    peer.acceptFrame() // ACK
    peer.play()
    return connect(peer)
  }

  /** Builds a new connection to `peer` with settings acked.  */
  private fun connect(
    peer: MockHttp2Peer,
    pushObserver: PushObserver = IGNORE,
    listener: Http2Connection.Listener = Http2Connection.Listener.REFUSE_INCOMING_STREAMS,
  ): Http2Connection {
    val connection =
      Http2Connection
        .Builder(true, TaskRunner.INSTANCE)
        .socket(peer.openSocket().asBufferedSocket(), "peer")
        .pushObserver(pushObserver)
        .listener(listener)
        .build()
    connection.start(sendConnectionPreface = false)

    // verify the peer received the ACK
    val ackFrame = peer.takeFrame()
    assertThat(ackFrame.type).isEqualTo(Http2.TYPE_SETTINGS)
    assertThat(ackFrame.streamId).isEqualTo(0)
    assertThat(ackFrame.ack).isTrue()
    return connection
  }

  private class RecordingPushObserver :
    PushObserver,
    Lockable {
    val events = mutableListOf<Any>()

    @Synchronized fun takeEvent(): Any {
      while (events.isEmpty()) {
        wait()
      }
      return events.removeAt(0)
    }

    @Synchronized override fun onRequest(
      streamId: Int,
      requestHeaders: List<Header>,
    ): Boolean {
      assertThat(streamId).isEqualTo(2)
      events.add(requestHeaders)
      notifyAll()
      return false
    }

    @Synchronized override fun onHeaders(
      streamId: Int,
      responseHeaders: List<Header>,
      last: Boolean,
    ): Boolean {
      assertThat(streamId).isEqualTo(2)
      assertThat(last).isTrue()
      events.add(responseHeaders)
      notifyAll()
      return false
    }

    @Synchronized override fun onData(
      streamId: Int,
      source: BufferedSource,
      byteCount: Int,
      last: Boolean,
    ): Boolean {
      events.add(AssertionError("onData"))
      notifyAll()
      return false
    }

    @Synchronized override fun onReset(
      streamId: Int,
      errorCode: ErrorCode,
    ) {
      events.add(AssertionError("onReset"))
      notifyAll()
    }
  }

  companion object {
    fun roundUp(
      num: Int,
      divisor: Int,
    ): Int = (num + divisor - 1) / divisor

    val IGNORE =
      object : PushObserver {
        override fun onRequest(
          streamId: Int,
          requestHeaders: List<Header>,
        ) = false

        override fun onHeaders(
          streamId: Int,
          responseHeaders: List<Header>,
          last: Boolean,
        ) = false

        override fun onData(
          streamId: Int,
          source: BufferedSource,
          byteCount: Int,
          last: Boolean,
        ): Boolean {
          source.skip(byteCount.toLong())
          return false
        }

        override fun onReset(
          streamId: Int,
          errorCode: ErrorCode,
        ) {}
      }
  }
}
