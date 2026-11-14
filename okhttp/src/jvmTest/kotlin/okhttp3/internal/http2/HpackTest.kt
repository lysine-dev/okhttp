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
import assertk.assertions.containsExactly
import assertk.assertions.isEqualTo
import assertk.assertions.isNull
import java.io.IOException
import java.util.Arrays
import kotlin.test.assertFailsWith
import okhttp3.TestUtil.headerEntries
import okio.Buffer
import okio.ByteString
import okio.ByteString.Companion.decodeHex
import okio.ByteString.Companion.encodeUtf8
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class HpackTest {
  private val bytesIn = Buffer()
  private var hpackReader: Hpack.Reader? = null
  private val bytesOut = Buffer()
  private var hpackWriter: Hpack.Writer? = null

  /**
   * Each header list entry must account for the RFC 9113 section 6.5.2 per-entry overhead,
   * including headers whose name and value are both empty. Otherwise a peer could flood
   * zero-length literal header fields and grow the header list without ever exceeding
   * [HEADER_LIMIT].
   */
  @Test
  fun manyEmptyHeadersExceedByteLimit() {
    repeat(8193) {
      bytesIn.writeByte(0x00) // Literal Header Field without Indexing, new name.
      bytesIn.writeByte(0x00) // Name length 0.
      bytesIn.writeByte(0x00) // Value length 0.
    }
    assertFailsWith<IOException> {
      hpackReader!!.readHeaders()
    }
  }

  @BeforeEach
  fun reset() {
    hpackReader = newReader(bytesIn)
    hpackWriter = Hpack.Writer(4096, false, bytesOut)
  }

  /**
   * Variable-length quantity special cases strings which are longer than 127 bytes.  Values such as
   * cookies can be 4KiB, and should be possible to send.
   *
   *  http://tools.ietf.org/html/draft-ietf-httpbis-header-compression-12#section-5.2
   */
  @Test
  fun largeHeaderValue() {
    val value = CharArray(4096)
    Arrays.fill(value, '!')
    val headerBlock = headerEntries("cookie", String(value))
    hpackWriter!!.writeHeaders(headerBlock)
    bytesIn.writeAll(bytesOut)
    hpackReader!!.readHeaders()
    assertThat(hpackReader!!.headerCount).isEqualTo(0)
    assertThat(hpackReader!!.getAndResetHeaderList()).isEqualTo(headerBlock)
  }

  /**
   * HPACK has a max header table size, which can be smaller than the max header message. Ensure the
   * larger header content is not lost.
   */
  @Test
  fun tooLargeToHPackIsStillEmitted() {
    bytesIn.writeByte(0x21) // Dynamic table size update (size = 1).
    bytesIn.writeByte(0x00) // Literal indexed
    bytesIn.writeByte(0x0a) // Literal name (len = 10)
    bytesIn.writeUtf8("custom-key")
    bytesIn.writeByte(0x0d) // Literal value (len = 13)
    bytesIn.writeUtf8("custom-header")
    hpackReader!!.readHeaders()
    assertThat(hpackReader!!.headerCount).isEqualTo(0)
    assertThat(hpackReader!!.getAndResetHeaderList()).isEqualTo(
      headerEntries("custom-key", "custom-header"),
    )
  }

  /** Oldest entries are evicted to support newer ones.  */
  @Test
  fun writerEviction() {
    val headerBlock =
      headerEntries(
        "custom-foo",
        "custom-header",
        "custom-bar",
        "custom-header",
        "custom-baz",
        "custom-header",
      )
    bytesIn.writeByte(0x40) // Literal indexed
    bytesIn.writeByte(0x0a) // Literal name (len = 10)
    bytesIn.writeUtf8("custom-foo")
    bytesIn.writeByte(0x0d) // Literal value (len = 13)
    bytesIn.writeUtf8("custom-header")
    bytesIn.writeByte(0x40) // Literal indexed
    bytesIn.writeByte(0x0a) // Literal name (len = 10)
    bytesIn.writeUtf8("custom-bar")
    bytesIn.writeByte(0x0d) // Literal value (len = 13)
    bytesIn.writeUtf8("custom-header")
    bytesIn.writeByte(0x40) // Literal indexed
    bytesIn.writeByte(0x0a) // Literal name (len = 10)
    bytesIn.writeUtf8("custom-baz")
    bytesIn.writeByte(0x0d) // Literal value (len = 13)
    bytesIn.writeUtf8("custom-header")

    // Set to only support 110 bytes (enough for 2 headers).
    // Use a new Writer because we don't support change the dynamic table
    // size after Writer constructed.
    val writer = Hpack.Writer(110, false, bytesOut)
    writer.writeHeaders(headerBlock)
    assertThat(bytesOut).isEqualTo(bytesIn)
    assertThat(writer.headerCount).isEqualTo(2)
    val tableLength = writer.dynamicTable.size
    var entry = writer.dynamicTable[tableLength - 1]!!
    checkEntry(entry, "custom-bar", "custom-header", 55)
    entry = writer.dynamicTable[tableLength - 2]!!
    checkEntry(entry, "custom-baz", "custom-header", 55)
  }

  @Test
  fun readerEviction() {
    val headerBlock =
      headerEntries(
        "custom-foo",
        "custom-header",
        "custom-bar",
        "custom-header",
        "custom-baz",
        "custom-header",
      )

    // Set to only support 110 bytes (enough for 2 headers).
    bytesIn.writeByte(0x3F) // Dynamic table size update (size = 110).
    bytesIn.writeByte(0x4F)
    bytesIn.writeByte(0x40) // Literal indexed
    bytesIn.writeByte(0x0a) // Literal name (len = 10)
    bytesIn.writeUtf8("custom-foo")
    bytesIn.writeByte(0x0d) // Literal value (len = 13)
    bytesIn.writeUtf8("custom-header")
    bytesIn.writeByte(0x40) // Literal indexed
    bytesIn.writeByte(0x0a) // Literal name (len = 10)
    bytesIn.writeUtf8("custom-bar")
    bytesIn.writeByte(0x0d) // Literal value (len = 13)
    bytesIn.writeUtf8("custom-header")
    bytesIn.writeByte(0x40) // Literal indexed
    bytesIn.writeByte(0x0a) // Literal name (len = 10)
    bytesIn.writeUtf8("custom-baz")
    bytesIn.writeByte(0x0d) // Literal value (len = 13)
    bytesIn.writeUtf8("custom-header")
    hpackReader!!.readHeaders()
    assertThat(hpackReader!!.headerCount).isEqualTo(2)
    val entry1 = hpackReader!!.dynamicTable[readerHeaderTableLength() - 1]!!
    checkEntry(entry1, "custom-bar", "custom-header", 55)
    val entry2 = hpackReader!!.dynamicTable[readerHeaderTableLength() - 2]!!
    checkEntry(entry2, "custom-baz", "custom-header", 55)

    // Once a header field is decoded and added to the reconstructed header
    // list, it cannot be removed from it. Hence, foo is here.
    assertThat(hpackReader!!.getAndResetHeaderList()).isEqualTo(headerBlock)

    // Simulate receiving a small dynamic table size update, that implies eviction.
    bytesIn.writeByte(0x3F) // Dynamic table size update (size = 55).
    bytesIn.writeByte(0x18)
    hpackReader!!.readHeaders()
    assertThat(hpackReader!!.headerCount).isEqualTo(1)
  }

  /** Header table backing array is initially 8 long, let's ensure it grows.  */
  @Test
  fun dynamicallyGrowsBeyond64Entries() {
    // Lots of headers need more room!
    hpackReader = Hpack.Reader(bytesIn, 16384, 4096)
    bytesIn.writeByte(0x3F) // Dynamic table size update (size = 16384).
    bytesIn.writeByte(0xE1)
    bytesIn.writeByte(0x7F)
    for (i in 0..255) {
      bytesIn.writeByte(0x40) // Literal indexed
      bytesIn.writeByte(0x0a) // Literal name (len = 10)
      bytesIn.writeUtf8("custom-foo")
      bytesIn.writeByte(0x0d) // Literal value (len = 13)
      bytesIn.writeUtf8("custom-header")
    }
    hpackReader!!.readHeaders()
    assertThat(hpackReader!!.headerCount).isEqualTo(256)
  }

  @Test
  fun huffmanDecodingSupported() {
    bytesIn.writeByte(0x44) // == Literal indexed ==
    // Indexed name (idx = 4) -> :path
    bytesIn.writeByte(0x8c) // Literal value Huffman encoded 12 bytes
    // decodes to www.example.com which is length 15
    bytesIn.write("f1e3c2e5f23a6ba0ab90f4ff".decodeHex())
    hpackReader!!.readHeaders()
    assertThat(hpackReader!!.headerCount).isEqualTo(1)
    assertThat(hpackReader!!.dynamicTableByteCount).isEqualTo(52)
    val entry = hpackReader!!.dynamicTable[readerHeaderTableLength() - 1]!!
    checkEntry(entry, ":path", "www.example.com", 52)
  }

  /**
   * http://tools.ietf.org/html/draft-ietf-httpbis-header-compression-12#appendix-C.2.1
   */
  @Test
  fun readLiteralHeaderFieldWithIndexing() {
    bytesIn.writeByte(0x40) // Literal indexed
    bytesIn.writeByte(0x0a) // Literal name (len = 10)
    bytesIn.writeUtf8("custom-key")
    bytesIn.writeByte(0x0d) // Literal value (len = 13)
    bytesIn.writeUtf8("custom-header")
    hpackReader!!.readHeaders()
    assertThat(hpackReader!!.headerCount).isEqualTo(1)
    assertThat(hpackReader!!.dynamicTableByteCount).isEqualTo(55)
    val entry = hpackReader!!.dynamicTable[readerHeaderTableLength() - 1]!!
    checkEntry(entry, "custom-key", "custom-header", 55)
    assertThat(hpackReader!!.getAndResetHeaderList()).isEqualTo(
      headerEntries("custom-key", "custom-header"),
    )
  }

  /**
   * http://tools.ietf.org/html/draft-ietf-httpbis-header-compression-12#appendix-C.2.2
   */
  @Test
  fun literalHeaderFieldWithoutIndexingIndexedName() {
    val headerBlock = headerEntries(":path", "/sample/path")
    bytesIn.writeByte(0x04) // == Literal not indexed ==
    // Indexed name (idx = 4) -> :path
    bytesIn.writeByte(0x0c) // Literal value (len = 12)
    bytesIn.writeUtf8("/sample/path")
    hpackWriter!!.writeHeaders(headerBlock)
    assertThat(bytesOut).isEqualTo(bytesIn)
    hpackReader!!.readHeaders()
    assertThat(hpackReader!!.headerCount).isEqualTo(0)
    assertThat(hpackReader!!.getAndResetHeaderList()).isEqualTo(headerBlock)
  }

  @Test
  fun literalHeaderFieldWithoutIndexingNewName() {
    val headerBlock = headerEntries("custom-key", "custom-header")
    bytesIn.writeByte(0x00) // Not indexed
    bytesIn.writeByte(0x0a) // Literal name (len = 10)
    bytesIn.writeUtf8("custom-key")
    bytesIn.writeByte(0x0d) // Literal value (len = 13)
    bytesIn.writeUtf8("custom-header")
    hpackReader!!.readHeaders()
    assertThat(hpackReader!!.headerCount).isEqualTo(0)
    assertThat(hpackReader!!.getAndResetHeaderList()).isEqualTo(headerBlock)
  }

  @Test
  fun literalHeaderFieldNeverIndexedIndexedName() {
    bytesIn.writeByte(0x14) // == Literal never indexed ==
    // Indexed name (idx = 4) -> :path
    bytesIn.writeByte(0x0c) // Literal value (len = 12)
    bytesIn.writeUtf8("/sample/path")
    hpackReader!!.readHeaders()
    assertThat(hpackReader!!.headerCount).isEqualTo(0)
    assertThat(hpackReader!!.getAndResetHeaderList()).isEqualTo(
      headerEntries(":path", "/sample/path"),
    )
  }

  @Test
  fun literalHeaderFieldNeverIndexedNewName() {
    val headerBlock = headerEntries("custom-key", "custom-header")
    bytesIn.writeByte(0x10) // Never indexed
    bytesIn.writeByte(0x0a) // Literal name (len = 10)
    bytesIn.writeUtf8("custom-key")
    bytesIn.writeByte(0x0d) // Literal value (len = 13)
    bytesIn.writeUtf8("custom-header")
    hpackReader!!.readHeaders()
    assertThat(hpackReader!!.headerCount).isEqualTo(0)
    assertThat(hpackReader!!.getAndResetHeaderList()).isEqualTo(headerBlock)
  }

  @Test
  fun literalHeaderFieldWithIncrementalIndexingIndexedName() {
    val headerBlock = headerEntries(":path", "/sample/path")
    bytesIn.writeByte(0x44) // Indexed name (idx = 4) -> :path
    bytesIn.writeByte(0x0c) // Literal value (len = 12)
    bytesIn.writeUtf8("/sample/path")
    hpackReader!!.readHeaders()
    assertThat(hpackReader!!.headerCount).isEqualTo(1)
    assertThat(hpackReader!!.getAndResetHeaderList()).isEqualTo(headerBlock)
  }

  @Test
  fun literalHeaderFieldWithIncrementalIndexingNewName() {
    val headerBlock = headerEntries("custom-key", "custom-header")
    bytesIn.writeByte(0x40) // Never indexed
    bytesIn.writeByte(0x0a) // Literal name (len = 10)
    bytesIn.writeUtf8("custom-key")
    bytesIn.writeByte(0x0d) // Literal value (len = 13)
    bytesIn.writeUtf8("custom-header")
    hpackWriter!!.writeHeaders(headerBlock)
    assertThat(bytesOut).isEqualTo(bytesIn)
    assertThat(hpackWriter!!.headerCount).isEqualTo(1)
    val entry = hpackWriter!!.dynamicTable[hpackWriter!!.dynamicTable.size - 1]!!
    checkEntry(entry, "custom-key", "custom-header", 55)
    hpackReader!!.readHeaders()
    assertThat(hpackReader!!.headerCount).isEqualTo(1)
    assertThat(hpackReader!!.getAndResetHeaderList()).isEqualTo(headerBlock)
  }

  @Test
  fun theSameHeaderAfterOneIncrementalIndexed() {
    val headerBlock =
      headerEntries(
        "custom-key",
        "custom-header",
        "custom-key",
        "custom-header",
      )
    bytesIn.writeByte(0x40) // Never indexed
    bytesIn.writeByte(0x0a) // Literal name (len = 10)
    bytesIn.writeUtf8("custom-key")
    bytesIn.writeByte(0x0d) // Literal value (len = 13)
    bytesIn.writeUtf8("custom-header")
    bytesIn.writeByte(0xbe) // Indexed name and value (idx = 63)
    hpackWriter!!.writeHeaders(headerBlock)
    assertThat(bytesOut).isEqualTo(bytesIn)
    assertThat(hpackWriter!!.headerCount).isEqualTo(1)
    val entry = hpackWriter!!.dynamicTable[hpackWriter!!.dynamicTable.size - 1]!!
    checkEntry(entry, "custom-key", "custom-header", 55)
    hpackReader!!.readHeaders()
    assertThat(hpackReader!!.headerCount).isEqualTo(1)
    assertThat(hpackReader!!.getAndResetHeaderList()).isEqualTo(headerBlock)
  }

  @Test
  fun staticHeaderIsNotCopiedIntoTheIndexedTable() {
    bytesIn.writeByte(0x82) // == Indexed - Add ==
    // idx = 2 -> :method: GET
    hpackReader!!.readHeaders()
    assertThat(hpackReader!!.headerCount).isEqualTo(0)
    assertThat(hpackReader!!.dynamicTableByteCount).isEqualTo(0)
    assertThat(hpackReader!!.dynamicTable[readerHeaderTableLength() - 1]).isNull()
    assertThat(hpackReader!!.getAndResetHeaderList()).isEqualTo(
      headerEntries(":method", "GET"),
    )
  }

  // Example taken from twitter/hpack DecoderTest.testUnusedIndex
  @Test
  fun readIndexedHeaderFieldIndex0() {
    bytesIn.writeByte(0x80) // == Indexed - Add idx = 0
    assertFailsWith<IOException> {
      hpackReader!!.readHeaders()
    }.also { expected ->
      assertThat(expected.message).isEqualTo("index == 0")
    }
  }

  // Example taken from twitter/hpack DecoderTest.testIllegalIndex
  @Test
  fun readIndexedHeaderFieldTooLargeIndex() {
    bytesIn.writeShort(0xff00) // == Indexed - Add idx = 127
    assertFailsWith<IOException> {
      hpackReader!!.readHeaders()
    }.also { expected ->
      assertThat(expected.message).isEqualTo("Header index too large 127")
    }
  }

  // Example taken from twitter/hpack DecoderTest.testInsidiousIndex
  @Test
  fun readIndexedHeaderFieldInsidiousIndex() {
    bytesIn.writeByte(0xff) // == Indexed - Add ==
    bytesIn.write("8080808008".decodeHex()) // idx = -2147483521
    assertFailsWith<IOException> {
      hpackReader!!.readHeaders()
    }.also { expected ->
      assertThat(expected.message).isEqualTo("HPACK integer overflow")
    }
  }

  // Example taken from twitter/hpack DecoderTest.testHeaderTableSizeUpdate
  @Test
  fun minMaxHeaderTableSize() {
    bytesIn.writeByte(0x20)
    hpackReader!!.readHeaders()
    assertThat(hpackReader!!.maxDynamicTableByteCount()).isEqualTo(0)
    bytesIn.writeByte(0x3f) // encode size 4096
    bytesIn.writeByte(0xe1)
    bytesIn.writeByte(0x1f)
    hpackReader!!.readHeaders()
    assertThat(hpackReader!!.maxDynamicTableByteCount()).isEqualTo(4096)
  }

  // Example taken from twitter/hpack DecoderTest.testIllegalHeaderTableSizeUpdate
  @Test
  fun cannotSetTableSizeLargerThanSettingsValue() {
    bytesIn.writeByte(0x3f) // encode size 4097
    bytesIn.writeByte(0xe2)
    bytesIn.writeByte(0x1f)
    assertFailsWith<IOException> {
      hpackReader!!.readHeaders()
    }.also { expected ->
      assertThat(expected.message).isEqualTo("Invalid dynamic table size update 4097")
    }
  }

  // Example taken from twitter/hpack DecoderTest.testInsidiousMaxHeaderSize
  @Test
  fun readHeaderTableStateChangeInsidiousMaxHeaderByteCount() {
    bytesIn.writeByte(0x3f)
    bytesIn.write("e1ffffff07".decodeHex()) // count = -2147483648
    assertFailsWith<IOException> {
      hpackReader!!.readHeaders()
    }.also { expected ->
      assertThat(expected.message).isEqualTo("HPACK integer overflow")
    }
  }

  @Test
  fun dynamicTableSizeUpdateRejectsWrappedSmallPositive() {
    bytesIn.writeByte(0x3f)
    bytesIn.write("ffffffff8f00".decodeHex())
    assertFailsWith<IOException> {
      hpackReader!!.readHeaders()
    }.also { expected ->
      assertThat(expected.message).isEqualTo("HPACK integer overflow")
    }
  }

  /**
   * http://tools.ietf.org/html/draft-ietf-httpbis-header-compression-12#appendix-C.2.4
   */
  @Test
  fun readIndexedHeaderFieldFromStaticTableWithoutBuffering() {
    bytesIn.writeByte(0x20) // Dynamic table size update (size = 0).
    bytesIn.writeByte(0x82) // == Indexed - Add ==
    // idx = 2 -> :method: GET
    hpackReader!!.readHeaders()

    // Not buffered in header table.
    assertThat(hpackReader!!.headerCount).isEqualTo(0)
    assertThat(hpackReader!!.getAndResetHeaderList()).isEqualTo(
      headerEntries(":method", "GET"),
    )
  }

  @Test
  fun readLiteralHeaderWithIncrementalIndexingStaticName() {
    bytesIn.writeByte(0x7d) // == Literal indexed ==
    // Indexed name (idx = 60) -> "www-authenticate"
    bytesIn.writeByte(0x05) // Literal value (len = 5)
    bytesIn.writeUtf8("Basic")
    hpackReader!!.readHeaders()
    assertThat(hpackReader!!.getAndResetHeaderList())
      .containsExactly(Header("www-authenticate", "Basic"))
  }

  @Test
  fun readLiteralHeaderWithIncrementalIndexingDynamicName() {
    bytesIn.writeByte(0x40)
    bytesIn.writeByte(0x0a) // Literal name (len = 10)
    bytesIn.writeUtf8("custom-foo")
    bytesIn.writeByte(0x05) // Literal value (len = 5)
    bytesIn.writeUtf8("Basic")
    bytesIn.writeByte(0x7e)
    bytesIn.writeByte(0x06) // Literal value (len = 6)
    bytesIn.writeUtf8("Basic2")
    hpackReader!!.readHeaders()
    assertThat(hpackReader!!.getAndResetHeaderList()).containsExactly(
      Header("custom-foo", "Basic"),
      Header("custom-foo", "Basic2"),
    )
  }

  /**
   * http://tools.ietf.org/html/draft-ietf-httpbis-header-compression-12#appendix-C.2
   */
  @Test
  fun readRequestExamplesWithoutHuffman() {
    firstRequestWithoutHuffman()
    hpackReader!!.readHeaders()
    checkReadFirstRequestWithoutHuffman()
    secondRequestWithoutHuffman()
    hpackReader!!.readHeaders()
    checkReadSecondRequestWithoutHuffman()
    thirdRequestWithoutHuffman()
    hpackReader!!.readHeaders()
    checkReadThirdRequestWithoutHuffman()
  }

  @Test
  fun readFailingRequestExample() {
    bytesIn.writeByte(0x82) // == Indexed - Add ==
    // idx = 2 -> :method: GET
    bytesIn.writeByte(0x86) // == Indexed - Add ==
    // idx = 7 -> :scheme: http
    bytesIn.writeByte(0x84) // == Indexed - Add ==
    bytesIn.writeByte(0x7f) // == Bad index! ==

    // Indexed name (idx = 4) -> :authority
    bytesIn.writeByte(0x0f) // Literal value (len = 15)
    bytesIn.writeUtf8("www.example.com")
    assertFailsWith<IOException> {
      hpackReader!!.readHeaders()
    }.also { expected ->
      assertThat(expected.message).isEqualTo("Header index too large 78")
    }
  }

  private fun firstRequestWithoutHuffman() {
    bytesIn.writeByte(0x82) // == Indexed - Add ==
    // idx = 2 -> :method: GET
    bytesIn.writeByte(0x86) // == Indexed - Add ==
    // idx = 7 -> :scheme: http
    bytesIn.writeByte(0x84) // == Indexed - Add ==
    // idx = 6 -> :path: /
    bytesIn.writeByte(0x41) // == Literal indexed ==
    // Indexed name (idx = 4) -> :authority
    bytesIn.writeByte(0x0f) // Literal value (len = 15)
    bytesIn.writeUtf8("www.example.com")
  }

  private fun checkReadFirstRequestWithoutHuffman() {
    assertThat(hpackReader!!.headerCount).isEqualTo(1)

    // [  1] (s =  57) :authority: www.example.com
    val entry = hpackReader!!.dynamicTable[readerHeaderTableLength() - 1]!!
    checkEntry(entry, ":authority", "www.example.com", 57)

    // Table size: 57
    assertThat(hpackReader!!.dynamicTableByteCount).isEqualTo(57)

    // Decoded header list:
    assertThat(hpackReader!!.getAndResetHeaderList()).isEqualTo(
      headerEntries(
        ":method",
        "GET",
        ":scheme",
        "http",
        ":path",
        "/",
        ":authority",
        "www.example.com",
      ),
    )
  }

  private fun secondRequestWithoutHuffman() {
    bytesIn.writeByte(0x82) // == Indexed - Add ==
    // idx = 2 -> :method: GET
    bytesIn.writeByte(0x86) // == Indexed - Add ==
    // idx = 7 -> :scheme: http
    bytesIn.writeByte(0x84) // == Indexed - Add ==
    // idx = 6 -> :path: /
    bytesIn.writeByte(0xbe) // == Indexed - Add ==
    // Indexed name (idx = 62) -> :authority: www.example.com
    bytesIn.writeByte(0x58) // == Literal indexed ==
    // Indexed name (idx = 24) -> cache-control
    bytesIn.writeByte(0x08) // Literal value (len = 8)
    bytesIn.writeUtf8("no-cache")
  }

  private fun checkReadSecondRequestWithoutHuffman() {
    assertThat(hpackReader!!.headerCount).isEqualTo(2)

    // [  1] (s =  53) cache-control: no-cache
    var entry = hpackReader!!.dynamicTable[readerHeaderTableLength() - 2]!!
    checkEntry(entry, "cache-control", "no-cache", 53)

    // [  2] (s =  57) :authority: www.example.com
    entry = hpackReader!!.dynamicTable[readerHeaderTableLength() - 1]!!
    checkEntry(entry, ":authority", "www.example.com", 57)

    // Table size: 110
    assertThat(hpackReader!!.dynamicTableByteCount).isEqualTo(110)

    // Decoded header list:
    assertThat(hpackReader!!.getAndResetHeaderList()).isEqualTo(
      headerEntries(
        ":method",
        "GET",
        ":scheme",
        "http",
        ":path",
        "/",
        ":authority",
        "www.example.com",
        "cache-control",
        "no-cache",
      ),
    )
  }

  private fun thirdRequestWithoutHuffman() {
    bytesIn.writeByte(0x82) // == Indexed - Add ==
    // idx = 2 -> :method: GET
    bytesIn.writeByte(0x87) // == Indexed - Add ==
    // idx = 7 -> :scheme: http
    bytesIn.writeByte(0x85) // == Indexed - Add ==
    // idx = 5 -> :path: /index.html
    bytesIn.writeByte(0xbf) // == Indexed - Add ==
    // Indexed name (idx = 63) -> :authority: www.example.com
    bytesIn.writeByte(0x40) // Literal indexed
    bytesIn.writeByte(0x0a) // Literal name (len = 10)
    bytesIn.writeUtf8("custom-key")
    bytesIn.writeByte(0x0c) // Literal value (len = 12)
    bytesIn.writeUtf8("custom-value")
  }

  private fun checkReadThirdRequestWithoutHuffman() {
    assertThat(hpackReader!!.headerCount).isEqualTo(3)

    // [  1] (s =  54) custom-key: custom-value
    var entry = hpackReader!!.dynamicTable[readerHeaderTableLength() - 3]!!
    checkEntry(entry, "custom-key", "custom-value", 54)

    // [  2] (s =  53) cache-control: no-cache
    entry = hpackReader!!.dynamicTable[readerHeaderTableLength() - 2]!!
    checkEntry(entry, "cache-control", "no-cache", 53)

    // [  3] (s =  57) :authority: www.example.com
    entry = hpackReader!!.dynamicTable[readerHeaderTableLength() - 1]!!
    checkEntry(entry, ":authority", "www.example.com", 57)

    // Table size: 164
    assertThat(hpackReader!!.dynamicTableByteCount).isEqualTo(164)

    // Decoded header list:
    assertThat(hpackReader!!.getAndResetHeaderList()).isEqualTo(
      headerEntries(
        ":method",
        "GET",
        ":scheme",
        "https",
        ":path",
        "/index.html",
        ":authority",
        "www.example.com",
        "custom-key",
        "custom-value",
      ),
    )
  }

  /**
   * http://tools.ietf.org/html/draft-ietf-httpbis-header-compression-12#appendix-C.4
   */
  @Test
  fun readRequestExamplesWithHuffman() {
    firstRequestWithHuffman()
    hpackReader!!.readHeaders()
    checkReadFirstRequestWithHuffman()
    secondRequestWithHuffman()
    hpackReader!!.readHeaders()
    checkReadSecondRequestWithHuffman()
    thirdRequestWithHuffman()
    hpackReader!!.readHeaders()
    checkReadThirdRequestWithHuffman()
  }

  private fun firstRequestWithHuffman() {
    bytesIn.writeByte(0x82) // == Indexed - Add ==
    // idx = 2 -> :method: GET
    bytesIn.writeByte(0x86) // == Indexed - Add ==
    // idx = 6 -> :scheme: http
    bytesIn.writeByte(0x84) // == Indexed - Add ==
    // idx = 4 -> :path: /
    bytesIn.writeByte(0x41) // == Literal indexed ==
    // Indexed name (idx = 1) -> :authority
    bytesIn.writeByte(0x8c) // Literal value Huffman encoded 12 bytes
    // decodes to www.example.com which is length 15
    bytesIn.write("f1e3c2e5f23a6ba0ab90f4ff".decodeHex())
  }

  private fun checkReadFirstRequestWithHuffman() {
    assertThat(hpackReader!!.headerCount).isEqualTo(1)

    // [  1] (s =  57) :authority: www.example.com
    val entry = hpackReader!!.dynamicTable[readerHeaderTableLength() - 1]!!
    checkEntry(entry, ":authority", "www.example.com", 57)

    // Table size: 57
    assertThat(hpackReader!!.dynamicTableByteCount).isEqualTo(57)

    // Decoded header list:
    assertThat(hpackReader!!.getAndResetHeaderList()).isEqualTo(
      headerEntries(
        ":method",
        "GET",
        ":scheme",
        "http",
        ":path",
        "/",
        ":authority",
        "www.example.com",
      ),
    )
  }

  private fun secondRequestWithHuffman() {
    bytesIn.writeByte(0x82) // == Indexed - Add ==
    // idx = 2 -> :method: GET
    bytesIn.writeByte(0x86) // == Indexed - Add ==
    // idx = 6 -> :scheme: http
    bytesIn.writeByte(0x84) // == Indexed - Add ==
    // idx = 4 -> :path: /
    bytesIn.writeByte(0xbe) // == Indexed - Add ==
    // idx = 62 -> :authority: www.example.com
    bytesIn.writeByte(0x58) // == Literal indexed ==
    // Indexed name (idx = 24) -> cache-control
    bytesIn.writeByte(0x86) // Literal value Huffman encoded 6 bytes
    // decodes to no-cache which is length 8
    bytesIn.write("a8eb10649cbf".decodeHex())
  }

  private fun checkReadSecondRequestWithHuffman() {
    assertThat(hpackReader!!.headerCount).isEqualTo(2)

    // [  1] (s =  53) cache-control: no-cache
    var entry = hpackReader!!.dynamicTable[readerHeaderTableLength() - 2]!!
    checkEntry(entry, "cache-control", "no-cache", 53)

    // [  2] (s =  57) :authority: www.example.com
    entry = hpackReader!!.dynamicTable[readerHeaderTableLength() - 1]!!
    checkEntry(entry, ":authority", "www.example.com", 57)

    // Table size: 110
    assertThat(hpackReader!!.dynamicTableByteCount).isEqualTo(110)

    // Decoded header list:
    assertThat(hpackReader!!.getAndResetHeaderList()).isEqualTo(
      headerEntries(
        ":method",
        "GET",
        ":scheme",
        "http",
        ":path",
        "/",
        ":authority",
        "www.example.com",
        "cache-control",
        "no-cache",
      ),
    )
  }

  private fun thirdRequestWithHuffman() {
    bytesIn.writeByte(0x82) // == Indexed - Add ==
    // idx = 2 -> :method: GET
    bytesIn.writeByte(0x87) // == Indexed - Add ==
    // idx = 7 -> :scheme: https
    bytesIn.writeByte(0x85) // == Indexed - Add ==
    // idx = 5 -> :path: /index.html
    bytesIn.writeByte(0xbf) // == Indexed - Add ==
    // idx = 63 -> :authority: www.example.com
    bytesIn.writeByte(0x40) // Literal indexed
    bytesIn.writeByte(0x88) // Literal name Huffman encoded 8 bytes
    // decodes to custom-key which is length 10
    bytesIn.write("25a849e95ba97d7f".decodeHex())
    bytesIn.writeByte(0x89) // Literal value Huffman encoded 9 bytes
    // decodes to custom-value which is length 12
    bytesIn.write("25a849e95bb8e8b4bf".decodeHex())
  }

  private fun checkReadThirdRequestWithHuffman() {
    assertThat(hpackReader!!.headerCount).isEqualTo(3)

    // [  1] (s =  54) custom-key: custom-value
    var entry = hpackReader!!.dynamicTable[readerHeaderTableLength() - 3]!!
    checkEntry(entry, "custom-key", "custom-value", 54)

    // [  2] (s =  53) cache-control: no-cache
    entry = hpackReader!!.dynamicTable[readerHeaderTableLength() - 2]!!
    checkEntry(entry, "cache-control", "no-cache", 53)

    // [  3] (s =  57) :authority: www.example.com
    entry = hpackReader!!.dynamicTable[readerHeaderTableLength() - 1]!!
    checkEntry(entry, ":authority", "www.example.com", 57)

    // Table size: 164
    assertThat(hpackReader!!.dynamicTableByteCount).isEqualTo(164)

    // Decoded header list:
    assertThat(hpackReader!!.getAndResetHeaderList()).isEqualTo(
      headerEntries(
        ":method",
        "GET",
        ":scheme",
        "https",
        ":path",
        "/index.html",
        ":authority",
        "www.example.com",
        "custom-key",
        "custom-value",
      ),
    )
  }

  @Test
  fun readSingleByteInt() {
    assertThat(newReader(byteStream()).readInt(10, 31)).isEqualTo(10)
    assertThat(newReader(byteStream()).readInt(0xe0 or 10, 31)).isEqualTo(10)
  }

  @Test
  fun readMultibyteInt() {
    assertThat(newReader(byteStream(154, 10)).readInt(31, 31)).isEqualTo(1337)
  }

  @Test
  fun writeSingleByteInt() {
    hpackWriter!!.writeInt(10, 31, 0)
    assertBytes(10)
    hpackWriter!!.writeInt(10, 31, 0xe0)
    assertBytes(0xe0 or 10)
  }

  @Test
  fun writeMultibyteInt() {
    hpackWriter!!.writeInt(1337, 31, 0)
    assertBytes(31, 154, 10)
    hpackWriter!!.writeInt(1337, 31, 0xe0)
    assertBytes(0xe0 or 31, 154, 10)
  }

  @Test
  fun max31BitValue() {
    hpackWriter!!.writeInt(0x7fffffff, 31, 0)
    assertBytes(31, 224, 255, 255, 255, 7)
    assertThat(newReader(byteStream(224, 255, 255, 255, 7)).readInt(31, 31))
      .isEqualTo(0x7fffffff)
  }

  @Test
  fun overflowingValueRejected() {
    assertFailsWith<IOException> {
      newReader(Buffer().write("8080808008".decodeHex())).readInt(0xff, 0x7f)
    }.also { expected ->
      assertThat(expected.message).isEqualTo("HPACK integer overflow")
    }
  }

  @Test
  fun tooManyContinuationBytesRejected() {
    assertFailsWith<IOException> {
      newReader(Buffer().write("808080808001".decodeHex())).readInt(0x1f, 0x1f)
    }.also { expected ->
      assertThat(expected.message).isEqualTo("HPACK integer overflow")
    }
  }

  @Test
  fun stringLengthOverflowRejected() {
    val source = Buffer()
    source.writeByte(0x00)
    source.writeByte(0x7f)
    source.write("8080808008".decodeHex())

    assertFailsWith<IOException> {
      newReader(source).readHeaders()
    }.also { expected ->
      assertThat(expected.message).isEqualTo("HPACK integer overflow")
    }
  }

  @Test
  fun prefixMask() {
    hpackWriter!!.writeInt(31, 31, 0)
    assertBytes(31, 0)
    assertThat(newReader(byteStream(0)).readInt(31, 31)).isEqualTo(31)
  }

  @Test
  fun prefixMaskMinusOne() {
    hpackWriter!!.writeInt(30, 31, 0)
    assertBytes(30)
    assertThat(newReader(byteStream(0)).readInt(31, 31)).isEqualTo(31)
  }

  @Test
  fun zero() {
    hpackWriter!!.writeInt(0, 31, 0)
    assertBytes(0)
    assertThat(newReader(byteStream()).readInt(0, 31)).isEqualTo(0)
  }

  @Test
  fun lowercaseHeaderNameBeforeEmit() {
    hpackWriter!!.writeHeaders(listOf(Header("FoO", "BaR")))
    assertBytes(0x40, 3, 'f'.code, 'o'.code, 'o'.code, 3, 'B'.code, 'a'.code, 'R'.code)
  }

  @Test
  fun mixedCaseHeaderNameIsMalformed() {
    assertFailsWith<IOException> {
      newReader(
        byteStream(
          0,
          3,
          'F'.code,
          'o'.code,
          'o'.code,
          3,
          'B'.code,
          'a'.code,
          'R'.code,
        ),
      ).readHeaders()
    }.also { expected ->
      assertThat(expected.message).isEqualTo(
        "PROTOCOL_ERROR response malformed: mixed case name: Foo",
      )
    }
  }

  @Test
  fun emptyHeaderName() {
    hpackWriter!!.writeByteString("".encodeUtf8())
    assertBytes(0)
    assertThat(newReader(byteStream(0)).readByteString())
      .isEqualTo(ByteString.EMPTY)
  }

  @Test
  fun emitsDynamicTableSizeUpdate() {
    hpackWriter!!.resizeHeaderTable(2048)
    hpackWriter!!.writeHeaders(listOf(Header("foo", "bar")))
    assertBytes(
      // Dynamic table size update (size = 2048).
      0x3F,
      0xE1,
      0xF,
      0x40,
      3,
      'f'.code,
      'o'.code,
      'o'.code,
      3,
      'b'.code,
      'a'.code,
      'r'.code,
    )
    hpackWriter!!.resizeHeaderTable(8192)
    hpackWriter!!.writeHeaders(listOf(Header("bar", "foo")))
    assertBytes(
      // Dynamic table size update (size = 8192).
      0x3F,
      0xE1,
      0x3F,
      0x40,
      3,
      'b'.code,
      'a'.code,
      'r'.code,
      3,
      'f'.code,
      'o'.code,
      'o'.code,
    )

    // No more dynamic table updates should be emitted.
    hpackWriter!!.writeHeaders(listOf(Header("far", "boo")))
    assertBytes(0x40, 3, 'f'.code, 'a'.code, 'r'.code, 3, 'b'.code, 'o'.code, 'o'.code)
  }

  @Test
  fun noDynamicTableSizeUpdateWhenSizeIsEqual() {
    val currentSize = hpackWriter!!.headerTableSizeSetting
    hpackWriter!!.resizeHeaderTable(currentSize)
    hpackWriter!!.writeHeaders(listOf(Header("foo", "bar")))
    assertBytes(0x40, 3, 'f'.code, 'o'.code, 'o'.code, 3, 'b'.code, 'a'.code, 'r'.code)
  }

  @Test
  fun growDynamicTableSize() {
    hpackWriter!!.resizeHeaderTable(8192)
    hpackWriter!!.resizeHeaderTable(16384)
    hpackWriter!!.writeHeaders(listOf(Header("foo", "bar")))
    assertBytes(
      // Dynamic table size update (size = 16384).
      0x3F,
      0xE1,
      0x7F,
      0x40,
      3,
      'f'.code,
      'o'.code,
      'o'.code,
      3,
      'b'.code,
      'a'.code,
      'r'.code,
    )
  }

  @Test
  fun shrinkDynamicTableSize() {
    hpackWriter!!.resizeHeaderTable(2048)
    hpackWriter!!.resizeHeaderTable(0)
    hpackWriter!!.writeHeaders(listOf(Header("foo", "bar")))
    assertBytes(
      // Dynamic size update (size = 0).
      0x20,
      0x40,
      3,
      'f'.code,
      'o'.code,
      'o'.code,
      3,
      'b'.code,
      'a'.code,
      'r'.code,
    )
  }

  @Test
  fun manyDynamicTableSizeChanges() {
    hpackWriter!!.resizeHeaderTable(16384)
    hpackWriter!!.resizeHeaderTable(8096)
    hpackWriter!!.resizeHeaderTable(0)
    hpackWriter!!.resizeHeaderTable(4096)
    hpackWriter!!.resizeHeaderTable(2048)
    hpackWriter!!.writeHeaders(listOf(Header("foo", "bar")))
    assertBytes(
      // Dynamic size update (size = 0).
      0x20,
      // Dynamic size update (size = 2048).
      0x3F,
      0xE1,
      0xF,
      0x40,
      3,
      'f'.code,
      'o'.code,
      'o'.code,
      3,
      'b'.code,
      'a'.code,
      'r'.code,
    )
  }

  @Test
  fun dynamicTableEvictionWhenSizeLowered() {
    val headerBlock =
      headerEntries(
        "custom-key1",
        "custom-header",
        "custom-key2",
        "custom-header",
      )
    hpackWriter!!.writeHeaders(headerBlock)
    assertThat(hpackWriter!!.headerCount).isEqualTo(2)
    hpackWriter!!.resizeHeaderTable(56)
    assertThat(hpackWriter!!.headerCount).isEqualTo(1)
    hpackWriter!!.resizeHeaderTable(0)
    assertThat(hpackWriter!!.headerCount).isEqualTo(0)
  }

  @Test
  fun noEvictionOnDynamicTableSizeIncrease() {
    val headerBlock =
      headerEntries(
        "custom-key1",
        "custom-header",
        "custom-key2",
        "custom-header",
      )
    hpackWriter!!.writeHeaders(headerBlock)
    assertThat(hpackWriter!!.headerCount).isEqualTo(2)
    hpackWriter!!.resizeHeaderTable(8192)
    assertThat(hpackWriter!!.headerCount).isEqualTo(2)
  }

  @Test
  fun dynamicTableSizeHasAnUpperBound() {
    hpackWriter!!.resizeHeaderTable(1048576)
    assertThat(hpackWriter!!.maxDynamicTableByteCount).isEqualTo(16384)
  }

  @Test
  fun huffmanEncode() {
    hpackWriter = Hpack.Writer(4096, true, bytesOut)
    hpackWriter!!.writeHeaders(headerEntries("foo", "bar"))
    val expected =
      Buffer()
        .writeByte(0x40) // Literal header, new name.
        .writeByte(0x82) // String literal is Huffman encoded (len = 2).
        .writeByte(0x94) // 'foo' Huffman encoded.
        .writeByte(0xE7)
        .writeByte(3) // String literal not Huffman encoded (len = 3).
        .writeByte('b'.code)
        .writeByte('a'.code)
        .writeByte('r'.code)
        .readByteString()
    val actual = bytesOut.readByteString()
    assertThat(actual).isEqualTo(expected)
  }

  @Test
  fun staticTableIndexedHeaders() {
    hpackWriter!!.writeHeaders(headerEntries(":method", "GET"))
    assertBytes(0x82)
    assertThat(hpackWriter!!.headerCount).isEqualTo(0)
    hpackWriter!!.writeHeaders(headerEntries(":method", "POST"))
    assertBytes(0x83)
    assertThat(hpackWriter!!.headerCount).isEqualTo(0)
    hpackWriter!!.writeHeaders(headerEntries(":path", "/"))
    assertBytes(0x84)
    assertThat(hpackWriter!!.headerCount).isEqualTo(0)
    hpackWriter!!.writeHeaders(headerEntries(":path", "/index.html"))
    assertBytes(0x85)
    assertThat(hpackWriter!!.headerCount).isEqualTo(0)
    hpackWriter!!.writeHeaders(headerEntries(":scheme", "http"))
    assertBytes(0x86)
    assertThat(hpackWriter!!.headerCount).isEqualTo(0)
    hpackWriter!!.writeHeaders(headerEntries(":scheme", "https"))
    assertBytes(0x87)
    assertThat(hpackWriter!!.headerCount).isEqualTo(0)
  }

  @Test
  fun dynamicTableIndexedHeader() {
    hpackWriter!!.writeHeaders(headerEntries("custom-key", "custom-header"))
    assertBytes(
      0x40,
      10,
      'c'.code,
      'u'.code,
      's'.code,
      't'.code,
      'o'.code,
      'm'.code,
      '-'.code,
      'k'.code,
      'e'.code,
      'y'.code,
      13,
      'c'.code,
      'u'.code,
      's'.code,
      't'.code,
      'o'.code,
      'm'.code,
      '-'.code,
      'h'.code,
      'e'.code,
      'a'.code,
      'd'.code,
      'e'.code,
      'r'.code,
    )
    assertThat(hpackWriter!!.headerCount).isEqualTo(1)
    hpackWriter!!.writeHeaders(headerEntries("custom-key", "custom-header"))
    assertBytes(0xbe)
    assertThat(hpackWriter!!.headerCount).isEqualTo(1)
  }

  @Test
  fun doNotIndexPseudoHeaders() {
    hpackWriter!!.writeHeaders(headerEntries(":method", "PUT"))
    assertBytes(0x02, 3, 'P'.code, 'U'.code, 'T'.code)
    assertThat(hpackWriter!!.headerCount).isEqualTo(0)
    hpackWriter!!.writeHeaders(headerEntries(":path", "/okhttp"))
    assertBytes(0x04, 7, '/'.code, 'o'.code, 'k'.code, 'h'.code, 't'.code, 't'.code, 'p'.code)
    assertThat(hpackWriter!!.headerCount).isEqualTo(0)
  }

  @Test
  fun incrementalIndexingWithAuthorityPseudoHeader() {
    hpackWriter!!.writeHeaders(headerEntries(":authority", "foo.com"))
    assertBytes(0x41, 7, 'f'.code, 'o'.code, 'o'.code, '.'.code, 'c'.code, 'o'.code, 'm'.code)
    assertThat(hpackWriter!!.headerCount).isEqualTo(1)
    hpackWriter!!.writeHeaders(headerEntries(":authority", "foo.com"))
    assertBytes(0xbe)
    assertThat(hpackWriter!!.headerCount).isEqualTo(1)

    // If the :authority header somehow changes, it should be re-added to the dynamic table.
    hpackWriter!!.writeHeaders(headerEntries(":authority", "bar.com"))
    assertBytes(0x41, 7, 'b'.code, 'a'.code, 'r'.code, '.'.code, 'c'.code, 'o'.code, 'm'.code)
    assertThat(hpackWriter!!.headerCount).isEqualTo(2)
    hpackWriter!!.writeHeaders(headerEntries(":authority", "bar.com"))
    assertBytes(0xbe)
    assertThat(hpackWriter!!.headerCount).isEqualTo(2)
  }

  @Test
  fun incrementalIndexingWithStaticTableIndexedName() {
    hpackWriter!!.writeHeaders(headerEntries("accept-encoding", "gzip"))
    assertBytes(0x50, 4, 'g'.code, 'z'.code, 'i'.code, 'p'.code)
    assertThat(hpackWriter!!.headerCount).isEqualTo(1)
    hpackWriter!!.writeHeaders(headerEntries("accept-encoding", "gzip"))
    assertBytes(0xbe)
    assertThat(hpackWriter!!.headerCount).isEqualTo(1)
  }

  @Test
  fun incrementalIndexingWithDynamicTableIndexedName() {
    hpackWriter!!.writeHeaders(headerEntries("foo", "bar"))
    assertBytes(0x40, 3, 'f'.code, 'o'.code, 'o'.code, 3, 'b'.code, 'a'.code, 'r'.code)
    assertThat(hpackWriter!!.headerCount).isEqualTo(1)
    hpackWriter!!.writeHeaders(headerEntries("foo", "bar1"))
    assertBytes(0x7e, 4, 'b'.code, 'a'.code, 'r'.code, '1'.code)
    assertThat(hpackWriter!!.headerCount).isEqualTo(2)
    hpackWriter!!.writeHeaders(headerEntries("foo", "bar1"))
    assertBytes(0xbe)
    assertThat(hpackWriter!!.headerCount).isEqualTo(2)
  }

  private fun newReader(source: Buffer): Hpack.Reader = Hpack.Reader(source, 4096)

  private fun byteStream(vararg bytes: Int): Buffer = Buffer().write(intArrayToByteArray(bytes))

  private fun checkEntry(
    entry: Header,
    name: String,
    value: String,
    size: Int,
  ) {
    assertThat(entry.name.utf8()).isEqualTo(name)
    assertThat(entry.value.utf8()).isEqualTo(value)
    assertThat(entry.hpackSize).isEqualTo(size)
  }

  private fun assertBytes(vararg bytes: Int) {
    val expected = intArrayToByteArray(bytes)
    val actual = bytesOut.readByteString()
    assertThat(actual).isEqualTo(expected)
  }

  private fun intArrayToByteArray(bytes: IntArray): ByteString {
    val data = ByteArray(bytes.size)
    for (i in bytes.indices) {
      data[i] = bytes[i].toByte()
    }
    return ByteString.of(*data)
  }

  private fun readerHeaderTableLength(): Int = hpackReader!!.dynamicTable.size
}
