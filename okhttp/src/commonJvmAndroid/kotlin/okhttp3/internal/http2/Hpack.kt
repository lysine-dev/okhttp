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

import java.io.IOException
import java.util.Arrays
import okhttp3.internal.HEADER_LIMIT
import okhttp3.internal.and
import okhttp3.internal.http2.Header.Companion.RESPONSE_STATUS
import okhttp3.internal.http2.Header.Companion.TARGET_AUTHORITY
import okhttp3.internal.http2.Header.Companion.TARGET_METHOD
import okhttp3.internal.http2.Header.Companion.TARGET_PATH
import okhttp3.internal.http2.Header.Companion.TARGET_SCHEME
import okhttp3.internal.unmodifiable
import okio.Buffer
import okio.BufferedSource
import okio.ByteString
import okio.Source
import okio.buffer

/**
 * Read and write HPACK v10.
 *
 * http://tools.ietf.org/html/draft-ietf-httpbis-header-compression-12
 *
 * This implementation uses an array for the dynamic table and a list for indexed entries. Dynamic
 * entries are added to the array, starting in the last position moving forward. When the array
 * fills, it is doubled.
 */
@Suppress("NAME_SHADOWING")
object Hpack {
  private const val PREFIX_4_BITS = 0x0f
  private const val PREFIX_5_BITS = 0x1f
  private const val PREFIX_6_BITS = 0x3f
  private const val PREFIX_7_BITS = 0x7f

  /**
   * Per-entry overhead of a header list entry, as defined by RFC 9113 section 6.5.2.
   */
  private const val HEADER_ENTRY_OVERHEAD = 32

  private const val SETTINGS_HEADER_TABLE_SIZE = 4_096

  /**
   * The decoder has ultimate control of the maximum size of the dynamic table but we can choose
   * to use less. We'll put a cap at 16K. This is arbitrary but should be enough for most purposes.
   */
  private const val SETTINGS_HEADER_TABLE_SIZE_LIMIT = 16_384

  val STATIC_HEADER_TABLE =
    arrayOf(
      Header(TARGET_AUTHORITY, ""),
      Header(TARGET_METHOD, "GET"),
      Header(TARGET_METHOD, "POST"),
      Header(TARGET_PATH, "/"),
      Header(TARGET_PATH, "/index.html"),
      Header(TARGET_SCHEME, "http"),
      Header(TARGET_SCHEME, "https"),
      Header(RESPONSE_STATUS, "200"),
      Header(RESPONSE_STATUS, "204"),
      Header(RESPONSE_STATUS, "206"),
      Header(RESPONSE_STATUS, "304"),
      Header(RESPONSE_STATUS, "400"),
      Header(RESPONSE_STATUS, "404"),
      Header(RESPONSE_STATUS, "500"),
      Header("accept-charset", ""),
      Header("accept-encoding", "gzip, deflate"),
      Header("accept-language", ""),
      Header("accept-ranges", ""),
      Header("accept", ""),
      Header("access-control-allow-origin", ""),
      Header("age", ""),
      Header("allow", ""),
      Header("authorization", ""),
      Header("cache-control", ""),
      Header("content-disposition", ""),
      Header("content-encoding", ""),
      Header("content-language", ""),
      Header("content-length", ""),
      Header("content-location", ""),
      Header("content-range", ""),
      Header("content-type", ""),
      Header("cookie", ""),
      Header("date", ""),
      Header("etag", ""),
      Header("expect", ""),
      Header("expires", ""),
      Header("from", ""),
      Header("host", ""),
      Header("if-match", ""),
      Header("if-modified-since", ""),
      Header("if-none-match", ""),
      Header("if-range", ""),
      Header("if-unmodified-since", ""),
      Header("last-modified", ""),
      Header("link", ""),
      Header("location", ""),
      Header("max-forwards", ""),
      Header("proxy-authenticate", ""),
      Header("proxy-authorization", ""),
      Header("range", ""),
      Header("referer", ""),
      Header("refresh", ""),
      Header("retry-after", ""),
      Header("server", ""),
      Header("set-cookie", ""),
      Header("strict-transport-security", ""),
      Header("transfer-encoding", ""),
      Header("user-agent", ""),
      Header("vary", ""),
      Header("via", ""),
      Header("www-authenticate", ""),
    )

  val NAME_TO_FIRST_INDEX = nameToFirstIndex()

  // http://tools.ietf.org/html/draft-ietf-httpbis-header-compression-12#section-3.1
  class Reader
    @JvmOverloads
    constructor(
      source: Source,
      private val headerTableSizeSetting: Int,
      private var maxDynamicTableByteCount: Int = headerTableSizeSetting,
    ) {
      private val headerList = mutableListOf<Header>()
      private var headerListByteCount = 0L
      private val source: BufferedSource = source.buffer()

      // Visible for testing.
      @JvmField var dynamicTable = arrayOfNulls<Header>(8)

      // Array is populated back to front, so new entries always have lowest index.
      private var nextHeaderIndex = dynamicTable.size - 1

      @JvmField var headerCount = 0

      @JvmField var dynamicTableByteCount = 0

      fun getAndResetHeaderList(): List<Header> {
        val result = headerList.toList()
        headerList.clear()
        headerListByteCount = 0L
        return result
      }

      fun maxDynamicTableByteCount(): Int = maxDynamicTableByteCount

      private fun adjustDynamicTableByteCount() {
        if (maxDynamicTableByteCount < dynamicTableByteCount) {
          if (maxDynamicTableByteCount == 0) {
            clearDynamicTable()
          } else {
            evictToRecoverBytes(dynamicTableByteCount - maxDynamicTableByteCount)
          }
        }
      }

      private fun clearDynamicTable() {
        dynamicTable.fill(null)
        nextHeaderIndex = dynamicTable.size - 1
        headerCount = 0
        dynamicTableByteCount = 0
      }

      /** Returns the count of entries evicted. */
      private fun evictToRecoverBytes(bytesToRecover: Int): Int {
        var bytesToRecover = bytesToRecover
        var entriesToEvict = 0
        if (bytesToRecover > 0) {
          // determine how many headers need to be evicted.
          var j = dynamicTable.size - 1
          while (j >= nextHeaderIndex && bytesToRecover > 0) {
            val toEvict = dynamicTable[j]!!
            bytesToRecover -= toEvict.hpackSize
            dynamicTableByteCount -= toEvict.hpackSize
            headerCount--
            entriesToEvict++
            j--
          }
          System.arraycopy(
            dynamicTable,
            nextHeaderIndex + 1,
            dynamicTable,
            nextHeaderIndex + 1 + entriesToEvict,
            headerCount,
          )
          nextHeaderIndex += entriesToEvict
        }
        return entriesToEvict
      }

      /**
       * Read `byteCount` bytes of headers from the source stream. This implementation does not
       * propagate the never indexed flag of a header.
       */
      @Throws(IOException::class)
      fun readHeaders() {
        while (!source.exhausted()) {
          val b = source.readByte() and 0xff
          when {
            b == 0x80 -> {
              // 10000000
              throw IOException("index == 0")
            }

            b and 0x80 == 0x80 -> {
              // 1NNNNNNN
              val index = readInt(b, PREFIX_7_BITS)
              readIndexedHeader(index - 1)
            }

            b == 0x40 -> {
              // 01000000
              readLiteralHeaderWithIncrementalIndexingNewName()
            }

            b and 0x40 == 0x40 -> {
              // 01NNNNNN
              val index = readInt(b, PREFIX_6_BITS)
              readLiteralHeaderWithIncrementalIndexingIndexedName(index - 1)
            }

            b and 0x20 == 0x20 -> {
              // 001NNNNN
              maxDynamicTableByteCount = readInt(b, PREFIX_5_BITS)
              if (maxDynamicTableByteCount < 0 || maxDynamicTableByteCount > headerTableSizeSetting) {
                throw IOException("Invalid dynamic table size update $maxDynamicTableByteCount")
              }
              adjustDynamicTableByteCount()
            }

            b == 0x10 || b == 0 -> {
              // 000?0000 - Ignore never indexed bit.
              readLiteralHeaderWithoutIndexingNewName()
            }

            else -> {
              // 000?NNNN - Ignore never indexed bit.
              val index = readInt(b, PREFIX_4_BITS)
              readLiteralHeaderWithoutIndexingIndexedName(index - 1)
            }
          }
        }
      }

      @Throws(IOException::class)
      private fun readIndexedHeader(index: Int) {
        if (isStaticHeader(index)) {
          val staticEntry = STATIC_HEADER_TABLE[index]
          addHeader(staticEntry)
        } else {
          val dynamicTableIndex = dynamicTableIndex(index - STATIC_HEADER_TABLE.size)
          if (dynamicTableIndex < 0 || dynamicTableIndex >= dynamicTable.size) {
            throw IOException("Header index too large ${index + 1}")
          }
          addHeader(dynamicTable[dynamicTableIndex]!!)
        }
      }

      // referencedHeaders is relative to nextHeaderIndex + 1.
      private fun dynamicTableIndex(index: Int): Int = nextHeaderIndex + 1 + index

      @Throws(IOException::class)
      private fun readLiteralHeaderWithoutIndexingIndexedName(index: Int) {
        val name = getName(index)
        val value = readByteString()
        addHeader(Header(name, value))
      }

      @Throws(IOException::class)
      private fun readLiteralHeaderWithoutIndexingNewName() {
        val name = checkLowercase(readByteString())
        val value = readByteString()
        addHeader(Header(name, value))
      }

      @Throws(IOException::class)
      private fun readLiteralHeaderWithIncrementalIndexingIndexedName(nameIndex: Int) {
        val name = getName(nameIndex)
        val value = readByteString()
        insertIntoDynamicTable(-1, Header(name, value))
      }

      @Throws(IOException::class)
      private fun readLiteralHeaderWithIncrementalIndexingNewName() {
        val name = checkLowercase(readByteString())
        val value = readByteString()
        insertIntoDynamicTable(-1, Header(name, value))
      }

      @Throws(IOException::class)
      private fun getName(index: Int): ByteString =
        if (isStaticHeader(index)) {
          STATIC_HEADER_TABLE[index].name
        } else {
          val dynamicTableIndex = dynamicTableIndex(index - STATIC_HEADER_TABLE.size)
          if (dynamicTableIndex < 0 || dynamicTableIndex >= dynamicTable.size) {
            throw IOException("Header index too large ${index + 1}")
          }

          dynamicTable[dynamicTableIndex]!!.name
        }

      private fun isStaticHeader(index: Int): Boolean = index >= 0 && index <= STATIC_HEADER_TABLE.size - 1

      /** index == -1 when new. */
      private fun insertIntoDynamicTable(
        index: Int,
        entry: Header,
      ) {
        var index = index
        addHeader(entry)

        var delta = entry.hpackSize
        if (index != -1) { // Index -1 == new header.
          delta -= dynamicTable[dynamicTableIndex(index)]!!.hpackSize
        }

        // if the new or replacement header is too big, drop all entries.
        if (delta > maxDynamicTableByteCount) {
          clearDynamicTable()
          return
        }

        // Evict headers to the required length.
        val bytesToRecover = dynamicTableByteCount + delta - maxDynamicTableByteCount
        val entriesEvicted = evictToRecoverBytes(bytesToRecover)

        if (index == -1) { // Adding a value to the dynamic table.
          if (headerCount + 1 > dynamicTable.size) { // Need to grow the dynamic table.
            val doubled = arrayOfNulls<Header>(dynamicTable.size * 2)
            System.arraycopy(dynamicTable, 0, doubled, dynamicTable.size, dynamicTable.size)
            nextHeaderIndex = dynamicTable.size - 1
            dynamicTable = doubled
          }
          index = nextHeaderIndex--
          dynamicTable[index] = entry
          headerCount++
        } else { // Replace value at same position.
          index += dynamicTableIndex(index) + entriesEvicted
          dynamicTable[index] = entry
        }
        dynamicTableByteCount += delta
      }

      @Throws(IOException::class)
      private fun readByte(): Int = source.readByte() and 0xff

      @Throws(IOException::class)
      fun readInt(
        firstByte: Int,
        prefixMask: Int,
      ): Int {
        val prefix = firstByte and prefixMask
        if (prefix < prefixMask) {
          return prefix // This was a single byte value.
        }

        // This is a multibyte value. Read 7 bits at a time.
        var result = prefixMask.toLong()
        var shift = 0
        var byteCount = 0
        while (true) {
          // An Int.MAX_VALUE payload needs at most 5 continuation bytes after the prefix.
          if (byteCount == 5) {
            throw IOException("HPACK integer overflow")
          }
          val b = readByte()
          byteCount++
          val increment = (b and 0x7f).toLong() shl shift
          if (increment > Int.MAX_VALUE.toLong() - result) {
            throw IOException("HPACK integer overflow")
          }
          result += increment
          if (b and 0x80 == 0) break
          shift += 7
        }
        return result.toInt()
      }

      /** Reads a potentially Huffman encoded byte string. */
      @Throws(IOException::class)
      fun readByteString(): ByteString {
        val firstByte = readByte()
        val huffmanDecode = firstByte and 0x80 == 0x80 // 1NNNNNNN
        val length = readInt(firstByte, PREFIX_7_BITS).toLong()

        // If the compressed or decompressed length exceeds the limit, don't even bother.
        if (headerListByteCount + length > HEADER_LIMIT) {
          throw IOException("header byte count limit of $HEADER_LIMIT exceeded")
        }

        return if (huffmanDecode) {
          val decodeBuffer = Buffer()
          Huffman.decode(source, length, decodeBuffer)
          decodeBuffer.readByteString()
        } else {
          source.readByteString(length)
        }
      }

      @Throws(IOException::class)
      private fun addHeader(header: Header) {
        headerList.add(header)

        // Include the per-entry overhead of RFC 9113 section 6.5.2, so the accounting
        // also grows for headers whose name and value are both empty.
        val headerSize = header.name.size + header.value.size + HEADER_ENTRY_OVERHEAD
        val newHeaderListSize = headerListByteCount + headerSize
        headerListByteCount = newHeaderListSize
        if (newHeaderListSize > HEADER_LIMIT) {
          throw IOException("header byte count limit of $HEADER_LIMIT exceeded")
        }
      }
    }

  private fun nameToFirstIndex(): Map<ByteString, Int> {
    val result = LinkedHashMap<ByteString, Int>(STATIC_HEADER_TABLE.size, 1.0F)
    for (i in STATIC_HEADER_TABLE.indices) {
      if (!result.containsKey(STATIC_HEADER_TABLE[i].name)) {
        result[STATIC_HEADER_TABLE[i].name] = i
      }
    }
    return result.unmodifiable()
  }

  class Writer
    @JvmOverloads
    constructor(
      @JvmField var headerTableSizeSetting: Int = SETTINGS_HEADER_TABLE_SIZE,
      private val useCompression: Boolean = true,
      private val out: Buffer,
    ) {
      /**
       * In the scenario where the dynamic table size changes multiple times between transmission of
       * header blocks, we need to keep track of the smallest value in that interval.
       */
      private var smallestHeaderTableSizeSetting = Integer.MAX_VALUE
      private var emitDynamicTableSizeUpdate: Boolean = false

      @JvmField var maxDynamicTableByteCount: Int = headerTableSizeSetting

      // Visible for testing.
      @JvmField var dynamicTable = arrayOfNulls<Header>(8)

      // Array is populated back to front, so new entries always have lowest index.
      private var nextHeaderIndex = dynamicTable.size - 1

      @JvmField var headerCount = 0

      @JvmField var dynamicTableByteCount = 0

      private fun clearDynamicTable() {
        dynamicTable.fill(null)
        nextHeaderIndex = dynamicTable.size - 1
        headerCount = 0
        dynamicTableByteCount = 0
      }

      /** Returns the count of entries evicted. */
      private fun evictToRecoverBytes(bytesToRecover: Int): Int {
        var bytesToRecover = bytesToRecover
        var entriesToEvict = 0
        if (bytesToRecover > 0) {
          // determine how many headers need to be evicted.
          var j = dynamicTable.size - 1
          while (j >= nextHeaderIndex && bytesToRecover > 0) {
            bytesToRecover -= dynamicTable[j]!!.hpackSize
            dynamicTableByteCount -= dynamicTable[j]!!.hpackSize
            headerCount--
            entriesToEvict++
            j--
          }
          System.arraycopy(
            dynamicTable,
            nextHeaderIndex + 1,
            dynamicTable,
            nextHeaderIndex + 1 + entriesToEvict,
            headerCount,
          )
          Arrays.fill(dynamicTable, nextHeaderIndex + 1, nextHeaderIndex + 1 + entriesToEvict, null)
          nextHeaderIndex += entriesToEvict
        }
        return entriesToEvict
      }

      private fun insertIntoDynamicTable(entry: Header) {
        val delta = entry.hpackSize

        // if the new or replacement header is too big, drop all entries.
        if (delta > maxDynamicTableByteCount) {
          clearDynamicTable()
          return
        }

        // Evict headers to the required length.
        val bytesToRecover = dynamicTableByteCount + delta - maxDynamicTableByteCount
        evictToRecoverBytes(bytesToRecover)

        if (headerCount + 1 > dynamicTable.size) { // Need to grow the dynamic table.
          val doubled = arrayOfNulls<Header>(dynamicTable.size * 2)
          System.arraycopy(dynamicTable, 0, doubled, dynamicTable.size, dynamicTable.size)
          nextHeaderIndex = dynamicTable.size - 1
          dynamicTable = doubled
        }
        val index = nextHeaderIndex--
        dynamicTable[index] = entry
        headerCount++
        dynamicTableByteCount += delta
      }

      /**
       * This does not use "never indexed" semantics for sensitive headers.
       *
       * http://tools.ietf.org/html/draft-ietf-httpbis-header-compression-12#section-6.2.3
       */
      @Throws(IOException::class)
      fun writeHeaders(headerBlock: List<Header>) {
        if (emitDynamicTableSizeUpdate) {
          if (smallestHeaderTableSizeSetting < maxDynamicTableByteCount) {
            // Multiple dynamic table size updates!
            writeInt(smallestHeaderTableSizeSetting, PREFIX_5_BITS, 0x20)
          }
          emitDynamicTableSizeUpdate = false
          smallestHeaderTableSizeSetting = Integer.MAX_VALUE
          writeInt(maxDynamicTableByteCount, PREFIX_5_BITS, 0x20)
        }

        for (i in 0 until headerBlock.size) {
          val header = headerBlock[i]
          val name = header.name.toAsciiLowercase()
          val value = header.value
          var headerIndex = -1
          var headerNameIndex = -1

          val staticIndex = NAME_TO_FIRST_INDEX[name]
          if (staticIndex != null) {
            headerNameIndex = staticIndex + 1
            if (headerNameIndex in 2..7) {
              // Only search a subset of the static header table. Most entries have an empty value, so
              // it's unnecessary to waste cycles looking at them. This check is built on the
              // observation that the header entries we care about are in adjacent pairs, and we
              // always know the first index of the pair.
              if (STATIC_HEADER_TABLE[headerNameIndex - 1].value == value) {
                headerIndex = headerNameIndex
              } else if (STATIC_HEADER_TABLE[headerNameIndex].value == value) {
                headerIndex = headerNameIndex + 1
              }
            }
          }

          if (headerIndex == -1) {
            for (j in nextHeaderIndex + 1 until dynamicTable.size) {
              if (dynamicTable[j]!!.name == name) {
                if (dynamicTable[j]!!.value == value) {
                  headerIndex = j - nextHeaderIndex + STATIC_HEADER_TABLE.size
                  break
                } else if (headerNameIndex == -1) {
                  headerNameIndex = j - nextHeaderIndex + STATIC_HEADER_TABLE.size
                }
              }
            }
          }

          when {
            headerIndex != -1 -> {
              // Indexed Header Field.
              writeInt(headerIndex, PREFIX_7_BITS, 0x80)
            }

            headerNameIndex == -1 -> {
              // Literal Header Field with Incremental Indexing - New Name.
              out.writeByte(0x40)
              writeByteString(name)
              writeByteString(value)
              insertIntoDynamicTable(header)
            }

            name.startsWith(Header.PSEUDO_PREFIX) && TARGET_AUTHORITY != name -> {
              // Follow Chromes lead - only include the :authority pseudo header, but exclude all other
              // pseudo headers. Literal Header Field without Indexing - Indexed Name.
              writeInt(headerNameIndex, PREFIX_4_BITS, 0)
              writeByteString(value)
            }

            else -> {
              // Literal Header Field with Incremental Indexing - Indexed Name.
              writeInt(headerNameIndex, PREFIX_6_BITS, 0x40)
              writeByteString(value)
              insertIntoDynamicTable(header)
            }
          }
        }
      }

      // http://tools.ietf.org/html/draft-ietf-httpbis-header-compression-12#section-4.1.1
      fun writeInt(
        value: Int,
        prefixMask: Int,
        bits: Int,
      ) {
        var value = value
        // Write the raw value for a single byte value.
        if (value < prefixMask) {
          out.writeByte(bits or value)
          return
        }

        // Write the mask to start a multibyte value.
        out.writeByte(bits or prefixMask)
        value -= prefixMask

        // Write 7 bits at a time 'til we're done.
        while (value >= 0x80) {
          val b = value and 0x7f
          out.writeByte(b or 0x80)
          value = value ushr 7
        }
        out.writeByte(value)
      }

      @Throws(IOException::class)
      fun writeByteString(data: ByteString) {
        if (useCompression && Huffman.encodedLength(data) < data.size) {
          val huffmanBuffer = Buffer()
          Huffman.encode(data, huffmanBuffer)
          val huffmanBytes = huffmanBuffer.readByteString()
          writeInt(huffmanBytes.size, PREFIX_7_BITS, 0x80)
          out.write(huffmanBytes)
        } else {
          writeInt(data.size, PREFIX_7_BITS, 0)
          out.write(data)
        }
      }

      fun resizeHeaderTable(headerTableSizeSetting: Int) {
        this.headerTableSizeSetting = headerTableSizeSetting
        val effectiveHeaderTableSize = minOf(headerTableSizeSetting, SETTINGS_HEADER_TABLE_SIZE_LIMIT)

        if (maxDynamicTableByteCount == effectiveHeaderTableSize) return // No change.

        if (effectiveHeaderTableSize < maxDynamicTableByteCount) {
          smallestHeaderTableSizeSetting =
            minOf(smallestHeaderTableSizeSetting, effectiveHeaderTableSize)
        }
        emitDynamicTableSizeUpdate = true
        maxDynamicTableByteCount = effectiveHeaderTableSize
        adjustDynamicTableByteCount()
      }

      private fun adjustDynamicTableByteCount() {
        if (maxDynamicTableByteCount < dynamicTableByteCount) {
          if (maxDynamicTableByteCount == 0) {
            clearDynamicTable()
          } else {
            evictToRecoverBytes(dynamicTableByteCount - maxDynamicTableByteCount)
          }
        }
      }
    }

  /**
   * An HTTP/2 response cannot contain uppercase header characters and must be treated as
   * malformed.
   */
  @Throws(IOException::class)
  fun checkLowercase(name: ByteString): ByteString {
    for (i in 0 until name.size) {
      if (name[i] in 'A'.code.toByte()..'Z'.code.toByte()) {
        throw IOException("PROTOCOL_ERROR response malformed: mixed case name: ${name.utf8()}")
      }
    }
    return name
  }
}
