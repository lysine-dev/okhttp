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
package okhttp3.internal.http2

import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isFalse
import assertk.assertions.isTrue
import java.io.IOException
import java.util.Arrays
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.pow
import kotlin.test.assertFailsWith
import okhttp3.TestUtil.headerEntries
import okhttp3.internal.EMPTY_BYTE_ARRAY
import okhttp3.internal.http2.Http2.FLAG_COMPRESSED
import okhttp3.internal.http2.Http2.FLAG_END_HEADERS
import okhttp3.internal.http2.Http2.FLAG_END_STREAM
import okhttp3.internal.http2.Http2.FLAG_NONE
import okhttp3.internal.http2.Http2.FLAG_PADDED
import okhttp3.internal.http2.Http2.FLAG_PRIORITY
import okhttp3.internal.http2.Http2.TYPE_GOAWAY
import okio.Buffer
import okio.BufferedSink
import okio.BufferedSource
import okio.ByteString
import okio.ByteString.Companion.decodeHex
import okio.ByteString.Companion.encodeUtf8
import okio.GzipSink
import okio.buffer
import org.junit.jupiter.api.Test

class Http2Test {
  val frame = Buffer()
  val reader = Http2Reader(frame, false)
  val expectedStreamId = 15

  @Test fun unknownFrameTypeSkipped() {
    writeMedium(frame, 4) // has a 4-byte field
    frame.writeByte(99) // type 99
    frame.writeByte(FLAG_NONE)
    frame.writeInt(expectedStreamId)
    frame.writeInt(111111111) // custom data
    reader.nextFrame(requireSettings = false, BaseTestHandler()) // Should not callback.
  }

  @Test fun onlyOneLiteralHeadersFrame() {
    val sentHeaders = headerEntries("name", "value")
    val headerBytes = literalHeaders(sentHeaders)
    writeMedium(frame, headerBytes.size.toInt())
    frame.writeByte(Http2.TYPE_HEADERS)
    frame.writeByte(FLAG_END_HEADERS or FLAG_END_STREAM)
    frame.writeInt(expectedStreamId and 0x7fffffff)
    frame.writeAll(headerBytes)

    // Check writer sends the same bytes.
    assertThat(sendHeaderFrames(true, sentHeaders)).isEqualTo(frame)
    reader.nextFrame(
      requireSettings = false,
      object : BaseTestHandler() {
        override fun headers(
          inFinished: Boolean,
          streamId: Int,
          associatedStreamId: Int,
          headerBlock: List<Header>,
        ) {
          assertThat(inFinished).isTrue()
          assertThat(streamId).isEqualTo(expectedStreamId)
          assertThat(associatedStreamId).isEqualTo(-1)
          assertThat(headerBlock).isEqualTo(sentHeaders)
        }
      },
    )
  }

  @Test fun headersWithPriority() {
    val sentHeaders = headerEntries("name", "value")
    val headerBytes = literalHeaders(sentHeaders)
    writeMedium(frame, (headerBytes.size + 5).toInt())
    frame.writeByte(Http2.TYPE_HEADERS)
    frame.writeByte(FLAG_END_HEADERS or FLAG_PRIORITY)
    frame.writeInt(expectedStreamId and 0x7fffffff)
    frame.writeInt(0) // Independent stream.
    frame.writeByte(255) // Heaviest weight, zero-indexed.
    frame.writeAll(headerBytes)
    reader.nextFrame(
      requireSettings = false,
      object : BaseTestHandler() {
        override fun priority(
          streamId: Int,
          streamDependency: Int,
          weight: Int,
          exclusive: Boolean,
        ) {
          assertThat(streamDependency).isEqualTo(0)
          assertThat(weight).isEqualTo(256)
          assertThat(exclusive).isFalse()
        }

        override fun headers(
          inFinished: Boolean,
          streamId: Int,
          associatedStreamId: Int,
          headerBlock: List<Header>,
        ) {
          assertThat(inFinished).isFalse()
          assertThat(streamId).isEqualTo(expectedStreamId)
          assertThat(associatedStreamId).isEqualTo(-1)
          assertThat(headerBlock).isEqualTo(sentHeaders)
        }
      },
    )
  }

  /** Headers are compressed, then framed.  */
  @Test fun headersFrameThenContinuation() {
    val sentHeaders = largeHeaders()
    val headerBlock = literalHeaders(sentHeaders)

    // Write the first headers frame.
    writeMedium(frame, Http2.INITIAL_MAX_FRAME_SIZE)
    frame.writeByte(Http2.TYPE_HEADERS)
    frame.writeByte(FLAG_NONE)
    frame.writeInt(expectedStreamId and 0x7fffffff)
    frame.write(headerBlock, Http2.INITIAL_MAX_FRAME_SIZE.toLong())

    // Write the continuation frame, specifying no more frames are expected.
    writeMedium(frame, headerBlock.size.toInt())
    frame.writeByte(Http2.TYPE_CONTINUATION)
    frame.writeByte(FLAG_END_HEADERS)
    frame.writeInt(expectedStreamId and 0x7fffffff)
    frame.writeAll(headerBlock)

    // Check writer sends the same bytes.
    assertThat(sendHeaderFrames(false, sentHeaders)).isEqualTo(frame)

    // Reading the above frames should result in a concatenated headerBlock.
    reader.nextFrame(
      requireSettings = false,
      object : BaseTestHandler() {
        override fun headers(
          inFinished: Boolean,
          streamId: Int,
          associatedStreamId: Int,
          headerBlock: List<Header>,
        ) {
          assertThat(inFinished).isFalse()
          assertThat(streamId).isEqualTo(expectedStreamId)
          assertThat(associatedStreamId).isEqualTo(-1)
          assertThat(headerBlock).isEqualTo(sentHeaders)
        }
      },
    )
  }

  @Test fun pushPromise() {
    val expectedPromisedStreamId = 11
    val pushPromise =
      listOf(
        Header(Header.TARGET_METHOD, "GET"),
        Header(Header.TARGET_SCHEME, "https"),
        Header(Header.TARGET_AUTHORITY, "squareup.com"),
        Header(Header.TARGET_PATH, "/"),
      )

    // Write the push promise frame, specifying the associated stream ID.
    val headerBytes = literalHeaders(pushPromise)
    writeMedium(frame, (headerBytes.size + 4).toInt())
    frame.writeByte(Http2.TYPE_PUSH_PROMISE)
    frame.writeByte(Http2.FLAG_END_PUSH_PROMISE)
    frame.writeInt(expectedStreamId and 0x7fffffff)
    frame.writeInt(expectedPromisedStreamId and 0x7fffffff)
    frame.writeAll(headerBytes)
    assertThat(sendPushPromiseFrames(expectedPromisedStreamId, pushPromise)).isEqualTo(
      frame,
    )
    reader.nextFrame(
      requireSettings = false,
      object : BaseTestHandler() {
        override fun pushPromise(
          streamId: Int,
          promisedStreamId: Int,
          requestHeaders: List<Header>,
        ) {
          assertThat(streamId).isEqualTo(expectedStreamId)
          assertThat(promisedStreamId).isEqualTo(expectedPromisedStreamId)
          assertThat(requestHeaders).isEqualTo(pushPromise)
        }
      },
    )
  }

  /** Headers are compressed, then framed.  */
  @Test fun pushPromiseThenContinuation() {
    val expectedPromisedStreamId = 11
    val pushPromise = largeHeaders()

    // Decoding the first header will cross frame boundaries.
    val headerBlock = literalHeaders(pushPromise)

    // Write the first headers frame.
    writeMedium(frame, Http2.INITIAL_MAX_FRAME_SIZE)
    frame.writeByte(Http2.TYPE_PUSH_PROMISE)
    frame.writeByte(FLAG_NONE)
    frame.writeInt(expectedStreamId and 0x7fffffff)
    frame.writeInt(expectedPromisedStreamId and 0x7fffffff)
    frame.write(headerBlock, (Http2.INITIAL_MAX_FRAME_SIZE - 4).toLong())

    // Write the continuation frame, specifying no more frames are expected.
    writeMedium(frame, headerBlock.size.toInt())
    frame.writeByte(Http2.TYPE_CONTINUATION)
    frame.writeByte(FLAG_END_HEADERS)
    frame.writeInt(expectedStreamId and 0x7fffffff)
    frame.writeAll(headerBlock)
    assertThat(sendPushPromiseFrames(expectedPromisedStreamId, pushPromise)).isEqualTo(
      frame,
    )

    // Reading the above frames should result in a concatenated headerBlock.
    reader.nextFrame(
      requireSettings = false,
      object : BaseTestHandler() {
        override fun pushPromise(
          streamId: Int,
          promisedStreamId: Int,
          requestHeaders: List<Header>,
        ) {
          assertThat(streamId).isEqualTo(expectedStreamId)
          assertThat(promisedStreamId).isEqualTo(expectedPromisedStreamId)
          assertThat(requestHeaders).isEqualTo(pushPromise)
        }
      },
    )
  }

  @Test fun readRstStreamFrame() {
    writeMedium(frame, 4)
    frame.writeByte(Http2.TYPE_RST_STREAM)
    frame.writeByte(FLAG_NONE)
    frame.writeInt(expectedStreamId and 0x7fffffff)
    frame.writeInt(ErrorCode.PROTOCOL_ERROR.httpCode)
    reader.nextFrame(
      requireSettings = false,
      object : BaseTestHandler() {
        override fun rstStream(
          streamId: Int,
          errorCode: ErrorCode,
        ) {
          assertThat(streamId).isEqualTo(expectedStreamId)
          assertThat(errorCode).isEqualTo(ErrorCode.PROTOCOL_ERROR)
        }
      },
    )
  }

  @Test fun readSettingsFrame() {
    val reducedTableSizeBytes = 16
    writeMedium(frame, 12) // 2 settings * 6 bytes (2 for the code and 4 for the value).
    frame.writeByte(Http2.TYPE_SETTINGS)
    frame.writeByte(FLAG_NONE)
    frame.writeInt(0) // Settings are always on the connection stream 0.
    frame.writeShort(1) // SETTINGS_HEADER_TABLE_SIZE
    frame.writeInt(reducedTableSizeBytes)
    frame.writeShort(2) // SETTINGS_ENABLE_PUSH
    frame.writeInt(0)
    reader.nextFrame(
      requireSettings = false,
      object : BaseTestHandler() {
        override fun settings(
          clearPrevious: Boolean,
          settings: Settings,
        ) {
          // No clearPrevious in HTTP/2.
          assertThat(clearPrevious).isFalse()
          assertThat(settings.headerTableSize).isEqualTo(reducedTableSizeBytes)
          assertThat(settings.getEnablePush(true)).isFalse()
        }
      },
    )
  }

  @Test fun readSettingsFrameInvalidPushValue() {
    writeMedium(frame, 6) // 2 for the code and 4 for the value
    frame.writeByte(Http2.TYPE_SETTINGS)
    frame.writeByte(FLAG_NONE)
    frame.writeInt(0) // Settings are always on the connection stream 0.
    frame.writeShort(2)
    frame.writeInt(2)
    assertFailsWith<IOException> {
      reader.nextFrame(requireSettings = false, BaseTestHandler())
    }.also { expected ->
      assertThat(expected.message).isEqualTo("PROTOCOL_ERROR SETTINGS_ENABLE_PUSH != 0 or 1")
    }
  }

  @Test fun readSettingsFrameUnknownSettingId() {
    writeMedium(frame, 6) // 2 for the code and 4 for the value
    frame.writeByte(Http2.TYPE_SETTINGS)
    frame.writeByte(FLAG_NONE)
    frame.writeInt(0) // Settings are always on the connection stream 0.
    frame.writeShort(7) // old number for SETTINGS_INITIAL_WINDOW_SIZE
    frame.writeInt(1)
    val settingValue = AtomicInteger()
    reader.nextFrame(
      requireSettings = false,
      object : BaseTestHandler() {
        override fun settings(
          clearPrevious: Boolean,
          settings: Settings,
        ) {
          settingValue.set(settings[7])
        }
      },
    )
    assertThat(1).isEqualTo(settingValue.toInt())
  }

  @Test fun readSettingsFrameExperimentalId() {
    writeMedium(frame, 6) // 2 for the code and 4 for the value
    frame.writeByte(Http2.TYPE_SETTINGS)
    frame.writeByte(FLAG_NONE)
    frame.writeInt(0) // Settings are always on the connection stream 0.
    frame.write("f000".decodeHex()) // Id reserved for experimental use.
    frame.writeInt(1)
    reader.nextFrame(
      requireSettings = false,
      object : BaseTestHandler() {
        override fun settings(
          clearPrevious: Boolean,
          settings: Settings,
        ) {
          // no-op
        }
      },
    )
  }

  @Test fun readSettingsFrameNegativeHeaderTableSize() {
    writeMedium(frame, 6) // 2 for the code and 4 for the value
    frame.writeByte(Http2.TYPE_SETTINGS)
    frame.writeByte(FLAG_NONE)
    frame.writeInt(0) // Settings are always on the connection stream 0.
    frame.writeShort(1) // SETTINGS_HEADER_TABLE_SIZE
    frame.writeInt(Int.MIN_VALUE)
    assertFailsWith<IOException> {
      reader.nextFrame(requireSettings = false, BaseTestHandler())
    }.also { expected ->
      assertThat(expected.message).isEqualTo(
        "PROTOCOL_ERROR SETTINGS_HEADER_TABLE_SIZE > 2^31 - 1",
      )
    }
  }

  @Test fun readSettingsFrameNegativeWindowSize() {
    writeMedium(frame, 6) // 2 for the code and 4 for the value
    frame.writeByte(Http2.TYPE_SETTINGS)
    frame.writeByte(FLAG_NONE)
    frame.writeInt(0) // Settings are always on the connection stream 0.
    frame.writeShort(4) // SETTINGS_INITIAL_WINDOW_SIZE
    frame.writeInt(Int.MIN_VALUE)
    assertFailsWith<IOException> {
      reader.nextFrame(requireSettings = false, BaseTestHandler())
    }.also { expected ->
      assertThat(expected.message).isEqualTo(
        "PROTOCOL_ERROR SETTINGS_INITIAL_WINDOW_SIZE > 2^31 - 1",
      )
    }
  }

  @Test fun readSettingsFrameNegativeFrameLength() {
    writeMedium(frame, 6) // 2 for the code and 4 for the value
    frame.writeByte(Http2.TYPE_SETTINGS)
    frame.writeByte(FLAG_NONE)
    frame.writeInt(0) // Settings are always on the connection stream 0.
    frame.writeShort(5) // SETTINGS_MAX_FRAME_SIZE
    frame.writeInt(Int.MIN_VALUE)
    assertFailsWith<IOException> {
      reader.nextFrame(requireSettings = false, BaseTestHandler())
    }.also { expected ->
      assertThat(expected.message).isEqualTo(
        "PROTOCOL_ERROR SETTINGS_MAX_FRAME_SIZE: -2147483648",
      )
    }
  }

  @Test fun readSettingsFrameTooShortFrameLength() {
    writeMedium(frame, 6) // 2 for the code and 4 for the value
    frame.writeByte(Http2.TYPE_SETTINGS)
    frame.writeByte(FLAG_NONE)
    frame.writeInt(0) // Settings are always on the connection stream 0.
    frame.writeShort(5) // SETTINGS_MAX_FRAME_SIZE
    frame.writeInt(2.0.pow(14.0).toInt() - 1)
    assertFailsWith<IOException> {
      reader.nextFrame(requireSettings = false, BaseTestHandler())
    }.also { expected ->
      assertThat(expected.message).isEqualTo("PROTOCOL_ERROR SETTINGS_MAX_FRAME_SIZE: 16383")
    }
  }

  @Test fun readSettingsFrameTooLongFrameLength() {
    writeMedium(frame, 6) // 2 for the code and 4 for the value
    frame.writeByte(Http2.TYPE_SETTINGS)
    frame.writeByte(FLAG_NONE)
    frame.writeInt(0) // Settings are always on the connection stream 0.
    frame.writeShort(5) // SETTINGS_MAX_FRAME_SIZE
    frame.writeInt(2.0.pow(24.0).toInt())
    assertFailsWith<IOException> {
      reader.nextFrame(requireSettings = false, BaseTestHandler())
    }.also { expected ->
      assertThat(expected.message).isEqualTo("PROTOCOL_ERROR SETTINGS_MAX_FRAME_SIZE: 16777216")
    }
  }

  @Test fun pingRoundTrip() {
    val expectedPayload1 = 7
    val expectedPayload2 = 8
    writeMedium(frame, 8) // length
    frame.writeByte(Http2.TYPE_PING)
    frame.writeByte(Http2.FLAG_ACK)
    frame.writeInt(0) // connection-level
    frame.writeInt(expectedPayload1)
    frame.writeInt(expectedPayload2)

    // Check writer sends the same bytes.
    assertThat(sendPingFrame(true, expectedPayload1, expectedPayload2)).isEqualTo(frame)
    reader.nextFrame(
      requireSettings = false,
      object : BaseTestHandler() {
        override fun ping(
          ack: Boolean,
          payload1: Int,
          payload2: Int,
        ) {
          assertThat(ack).isTrue()
          assertThat(payload1).isEqualTo(expectedPayload1)
          assertThat(payload2).isEqualTo(expectedPayload2)
        }
      },
    )
  }

  @Test fun maxLengthDataFrame() {
    val expectedData = ByteArray(Http2.INITIAL_MAX_FRAME_SIZE)
    Arrays.fill(expectedData, 2.toByte())
    writeMedium(frame, expectedData.size)
    frame.writeByte(Http2.TYPE_DATA)
    frame.writeByte(FLAG_NONE)
    frame.writeInt(expectedStreamId and 0x7fffffff)
    frame.write(expectedData)

    // Check writer sends the same bytes.
    assertThat(sendDataFrame(Buffer().write(expectedData))).isEqualTo(frame)
    reader.nextFrame(
      requireSettings = false,
      object : BaseTestHandler() {
        override fun data(
          inFinished: Boolean,
          streamId: Int,
          source: BufferedSource,
          length: Int,
        ) {
          assertThat(inFinished).isFalse()
          assertThat(streamId).isEqualTo(expectedStreamId)
          assertThat(length).isEqualTo(Http2.INITIAL_MAX_FRAME_SIZE)
          val data = source.readByteString(length.toLong())
          for (b in data.toByteArray()) {
            assertThat(b).isEqualTo(2.toByte())
          }
        }
      },
    )
  }

  @Test fun dataFrameNotAssociateWithStream() {
    val payload = byteArrayOf(0x01, 0x02)
    writeMedium(frame, payload.size)
    frame.writeByte(Http2.TYPE_DATA)
    frame.writeByte(FLAG_NONE)
    frame.writeInt(0)
    frame.write(payload)
    assertFailsWith<IOException> {
      reader.nextFrame(requireSettings = false, BaseTestHandler())
    }.also { expected ->
      assertThat(expected.message).isEqualTo("PROTOCOL_ERROR: TYPE_DATA streamId == 0")
    }
  }

  /** We do not send SETTINGS_COMPRESS_DATA = 1, nor want to. Let's make sure we error.  */
  @Test fun compressedDataFrameWhenSettingDisabled() {
    val expectedData = ByteArray(Http2.INITIAL_MAX_FRAME_SIZE)
    Arrays.fill(expectedData, 2.toByte())
    val zipped = gzip(expectedData)
    val zippedSize = zipped.size.toInt()
    writeMedium(frame, zippedSize)
    frame.writeByte(Http2.TYPE_DATA)
    frame.writeByte(FLAG_COMPRESSED)
    frame.writeInt(expectedStreamId and 0x7fffffff)
    zipped.readAll(frame)
    assertFailsWith<IOException> {
      reader.nextFrame(requireSettings = false, BaseTestHandler())
    }.also { expected ->
      assertThat(expected.message)
        .isEqualTo("PROTOCOL_ERROR: FLAG_COMPRESSED without SETTINGS_COMPRESS_DATA")
    }
  }

  @Test fun readPaddedDataFrame() {
    val dataLength = 1123
    val expectedData = ByteArray(dataLength)
    Arrays.fill(expectedData, 2.toByte())
    val paddingLength = 254
    val padding = ByteArray(paddingLength)
    Arrays.fill(padding, 0.toByte())
    writeMedium(frame, dataLength + paddingLength + 1)
    frame.writeByte(Http2.TYPE_DATA)
    frame.writeByte(FLAG_PADDED)
    frame.writeInt(expectedStreamId and 0x7fffffff)
    frame.writeByte(paddingLength)
    frame.write(expectedData)
    frame.write(padding)
    reader.nextFrame(requireSettings = false, assertData())
    // Padding was skipped.
    assertThat(frame.exhausted()).isTrue()
  }

  @Test fun readPaddedDataFrameZeroPadding() {
    val dataLength = 1123
    val expectedData = ByteArray(dataLength)
    Arrays.fill(expectedData, 2.toByte())
    writeMedium(frame, dataLength + 1)
    frame.writeByte(Http2.TYPE_DATA)
    frame.writeByte(FLAG_PADDED)
    frame.writeInt(expectedStreamId and 0x7fffffff)
    frame.writeByte(0)
    frame.write(expectedData)
    reader.nextFrame(requireSettings = false, assertData())
  }

  @Test fun readPaddedHeadersFrame() {
    val paddingLength = 254
    val padding = ByteArray(paddingLength)
    Arrays.fill(padding, 0.toByte())
    val headerBlock = literalHeaders(headerEntries("foo", "barrr", "baz", "qux"))
    writeMedium(frame, headerBlock.size.toInt() + paddingLength + 1)
    frame.writeByte(Http2.TYPE_HEADERS)
    frame.writeByte(FLAG_END_HEADERS or FLAG_PADDED)
    frame.writeInt(expectedStreamId and 0x7fffffff)
    frame.writeByte(paddingLength)
    frame.writeAll(headerBlock)
    frame.write(padding)
    reader.nextFrame(requireSettings = false, assertHeaderBlock())
    // Padding was skipped.
    assertThat(frame.exhausted()).isTrue()
  }

  @Test fun readPaddedHeadersFrameZeroPadding() {
    val headerBlock = literalHeaders(headerEntries("foo", "barrr", "baz", "qux"))
    writeMedium(frame, headerBlock.size.toInt() + 1)
    frame.writeByte(Http2.TYPE_HEADERS)
    frame.writeByte(FLAG_END_HEADERS or FLAG_PADDED)
    frame.writeInt(expectedStreamId and 0x7fffffff)
    frame.writeByte(0)
    frame.writeAll(headerBlock)
    reader.nextFrame(requireSettings = false, assertHeaderBlock())
  }

  /** Headers are compressed, then framed.  */
  @Test fun readPaddedHeadersFrameThenContinuation() {
    val paddingLength = 254
    val padding = ByteArray(paddingLength)
    Arrays.fill(padding, 0.toByte())

    // Decoding the first header will cross frame boundaries.
    val headerBlock = literalHeaders(headerEntries("foo", "barrr", "baz", "qux"))

    // Write the first headers frame.
    writeMedium(frame, (headerBlock.size / 2).toInt() + paddingLength + 1)
    frame.writeByte(Http2.TYPE_HEADERS)
    frame.writeByte(FLAG_PADDED)
    frame.writeInt(expectedStreamId and 0x7fffffff)
    frame.writeByte(paddingLength)
    frame.write(headerBlock, headerBlock.size / 2)
    frame.write(padding)

    // Write the continuation frame, specifying no more frames are expected.
    writeMedium(frame, headerBlock.size.toInt())
    frame.writeByte(Http2.TYPE_CONTINUATION)
    frame.writeByte(FLAG_END_HEADERS)
    frame.writeInt(expectedStreamId and 0x7fffffff)
    frame.writeAll(headerBlock)
    reader.nextFrame(requireSettings = false, assertHeaderBlock())
    assertThat(frame.exhausted()).isTrue()
  }

  @Test fun tooLargeDataFrame() {
    assertFailsWith<IllegalArgumentException> {
      sendDataFrame(Buffer().write(ByteArray(0x1000000)))
    }.also { expected ->
      assertThat(expected.message).isEqualTo("FRAME_SIZE_ERROR length > 16384: 16777216")
    }
  }

  @Test fun windowUpdateRoundTrip() {
    val expectedWindowSizeIncrement: Long = 0x7fffffff
    writeMedium(frame, 4) // length
    frame.writeByte(Http2.TYPE_WINDOW_UPDATE)
    frame.writeByte(FLAG_NONE)
    frame.writeInt(expectedStreamId)
    frame.writeInt(expectedWindowSizeIncrement.toInt())

    // Check writer sends the same bytes.
    assertThat(windowUpdate(expectedWindowSizeIncrement)).isEqualTo(frame)
    reader.nextFrame(
      requireSettings = false,
      object : BaseTestHandler() {
        override fun windowUpdate(
          streamId: Int,
          windowSizeIncrement: Long,
        ) {
          assertThat(streamId).isEqualTo(expectedStreamId)
          assertThat(windowSizeIncrement).isEqualTo(expectedWindowSizeIncrement)
        }
      },
    )
  }

  @Test fun badWindowSizeIncrement() {
    assertFailsWith<IllegalArgumentException> {
      windowUpdate(0)
    }.also { expected ->
      assertThat(expected.message)
        .isEqualTo("windowSizeIncrement == 0 || windowSizeIncrement > 0x7fffffffL: 0")
    }
    assertFailsWith<IllegalArgumentException> {
      windowUpdate(0x80000000L)
    }.also { expected ->
      assertThat(expected.message)
        .isEqualTo("windowSizeIncrement == 0 || windowSizeIncrement > 0x7fffffffL: 2147483648")
    }
  }

  @Test fun goAwayWithoutDebugDataRoundTrip() {
    val expectedError = ErrorCode.PROTOCOL_ERROR
    writeMedium(frame, 8) // Without debug data there's only 2 32-bit fields.
    frame.writeByte(TYPE_GOAWAY)
    frame.writeByte(FLAG_NONE)
    frame.writeInt(0) // connection-scope
    frame.writeInt(expectedStreamId) // last good stream.
    frame.writeInt(expectedError.httpCode)

    // Check writer sends the same bytes.
    assertThat(sendGoAway(expectedStreamId, expectedError, EMPTY_BYTE_ARRAY))
      .isEqualTo(frame)
    reader.nextFrame(
      requireSettings = false,
      object : BaseTestHandler() {
        override fun goAway(
          lastGoodStreamId: Int,
          errorCode: ErrorCode,
          debugData: ByteString,
        ) {
          assertThat(lastGoodStreamId).isEqualTo(expectedStreamId)
          assertThat(errorCode).isEqualTo(expectedError)
          assertThat(debugData.size).isEqualTo(0)
        }
      },
    )
  }

  @Test fun goAwayWithDebugDataRoundTrip() {
    val expectedError = ErrorCode.PROTOCOL_ERROR
    val expectedData: ByteString = "abcdefgh".encodeUtf8()

    // Compose the expected GOAWAY frame without debug data.
    writeMedium(frame, 8 + expectedData.size)
    frame.writeByte(TYPE_GOAWAY)
    frame.writeByte(FLAG_NONE)
    frame.writeInt(0) // connection-scope
    frame.writeInt(0) // never read any stream!
    frame.writeInt(expectedError.httpCode)
    frame.write(expectedData.toByteArray())

    // Check writer sends the same bytes.
    assertThat(sendGoAway(0, expectedError, expectedData.toByteArray())).isEqualTo(frame)
    reader.nextFrame(
      requireSettings = false,
      object : BaseTestHandler() {
        override fun goAway(
          lastGoodStreamId: Int,
          errorCode: ErrorCode,
          debugData: ByteString,
        ) {
          assertThat(lastGoodStreamId).isEqualTo(0)
          assertThat(errorCode).isEqualTo(expectedError)
          assertThat(debugData).isEqualTo(expectedData)
        }
      },
    )
  }

  @Test fun frameSizeError() {
    val writer = Http2Writer(Buffer(), true)
    assertFailsWith<IllegalArgumentException> {
      writer.frameHeader(0, 16777216, Http2.TYPE_DATA, FLAG_NONE)
    }.also { expected ->
      // TODO: real max is based on settings between 16384 and 16777215
      assertThat(expected.message).isEqualTo("FRAME_SIZE_ERROR length > 16384: 16777216")
    }
  }

  @Test fun ackSettingsAppliesMaxFrameSize() {
    val newMaxFrameSize = 16777215
    val writer = Http2Writer(Buffer(), true)
    writer.applyAndAckSettings(Settings().set(Settings.MAX_FRAME_SIZE, newMaxFrameSize))
    assertThat(writer.maxDataLength()).isEqualTo(newMaxFrameSize)
    writer.frameHeader(0, newMaxFrameSize, Http2.TYPE_DATA, FLAG_NONE)
  }

  @Test fun streamIdHasReservedBit() {
    val writer = Http2Writer(Buffer(), true)
    assertFailsWith<IllegalArgumentException> {
      var streamId = 3
      streamId = streamId or (1L shl 31).toInt() // set reserved bit
      writer.frameHeader(streamId, Http2.INITIAL_MAX_FRAME_SIZE, Http2.TYPE_DATA, FLAG_NONE)
    }.also { expected ->
      assertThat(expected.message).isEqualTo("reserved bit set: -2147483645")
    }
  }

  private fun literalHeaders(sentHeaders: List<Header>): Buffer {
    val out = Buffer()
    Hpack.Writer(out = out).writeHeaders(sentHeaders)
    return out
  }

  private fun sendHeaderFrames(
    outFinished: Boolean,
    headers: List<Header>,
  ): Buffer {
    val out = Buffer()
    Http2Writer(out, true).headers(outFinished, expectedStreamId, headers)
    return out
  }

  private fun sendPushPromiseFrames(
    streamId: Int,
    headers: List<Header>,
  ): Buffer {
    val out = Buffer()
    Http2Writer(out, true).pushPromise(expectedStreamId, streamId, headers)
    return out
  }

  private fun sendPingFrame(
    ack: Boolean,
    payload1: Int,
    payload2: Int,
  ): Buffer {
    val out = Buffer()
    Http2Writer(out, true).ping(ack, payload1, payload2)
    return out
  }

  private fun sendGoAway(
    lastGoodStreamId: Int,
    errorCode: ErrorCode,
    debugData: ByteArray,
  ): Buffer {
    val out = Buffer()
    Http2Writer(out, true).goAway(lastGoodStreamId, errorCode, debugData)
    return out
  }

  private fun sendDataFrame(data: Buffer): Buffer {
    val out = Buffer()
    Http2Writer(out, true).dataFrame(expectedStreamId, FLAG_NONE, data, data.size.toInt())
    return out
  }

  private fun windowUpdate(windowSizeIncrement: Long): Buffer {
    val out = Buffer()
    Http2Writer(out, true).windowUpdate(expectedStreamId, windowSizeIncrement)
    return out
  }

  private fun assertHeaderBlock(): Http2Reader.Handler =
    object : BaseTestHandler() {
      override fun headers(
        inFinished: Boolean,
        streamId: Int,
        associatedStreamId: Int,
        headerBlock: List<Header>,
      ) {
        assertThat(inFinished).isFalse()
        assertThat(streamId).isEqualTo(expectedStreamId)
        assertThat(associatedStreamId).isEqualTo(-1)
        assertThat(headerBlock).isEqualTo(headerEntries("foo", "barrr", "baz", "qux"))
      }
    }

  private fun assertData(): Http2Reader.Handler =
    object : BaseTestHandler() {
      override fun data(
        inFinished: Boolean,
        streamId: Int,
        source: BufferedSource,
        length: Int,
      ) {
        assertThat(inFinished).isFalse()
        assertThat(streamId).isEqualTo(expectedStreamId)
        assertThat(length).isEqualTo(1123)
        val data = source.readByteString(length.toLong())
        for (b in data.toByteArray()) {
          assertThat(b).isEqualTo(2.toByte())
        }
      }
    }

  private fun gzip(data: ByteArray): Buffer {
    val buffer = Buffer()
    GzipSink(buffer).buffer().write(data).close()
    return buffer
  }

  /** Create a sufficiently large header set to overflow INITIAL_MAX_FRAME_SIZE bytes.  */
  private fun largeHeaders(): List<Header> {
    val nameValues = arrayOfNulls<String>(32)
    val chars = CharArray(512)
    var i = 0
    while (i < nameValues.size) {
      Arrays.fill(chars, i.toChar())
      val string = String(chars)
      nameValues[i++] = string
      nameValues[i++] = string
    }
    return headerEntries(*nameValues)
  }

  private fun writeMedium(
    sink: BufferedSink,
    i: Int,
  ) {
    sink.writeByte(i ushr 16 and 0xff)
    sink.writeByte(i ushr 8 and 0xff)
    sink.writeByte(i and 0xff)
  }
}
