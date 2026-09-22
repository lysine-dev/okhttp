/*
 * Copyright (c) 2026 OkHttp Authors
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
@file:OptIn(OkHttpInternalApi::class)

package okhttp3.internal.dns

import assertk.assertThat
import assertk.assertions.hasMessage
import assertk.assertions.isEqualTo
import java.net.InetAddress
import java.net.ProtocolException
import kotlin.test.Test
import kotlin.test.assertFailsWith
import okhttp3.internal.OkHttpInternalApi
import okio.Buffer
import okio.ByteString.Companion.decodeHex

class DnsMessageReaderWriterTest {
  @Test
  fun `ipv4 round trip`() {
    assertRoundTrip(
      DnsMessage(
        id = 65432.toShort(),
        flags = -32384,
        questions =
          listOf(
            Question(
              name = "lysine.dev",
              type = TYPE_A,
            ),
          ),
        answers =
          listOf(
            ResourceRecord.IpAddress(
              name = "lysine.dev",
              timeToLive = 0,
              address = InetAddress.getByName("172.217.23.238"),
            ),
            ResourceRecord.IpAddress(
              name = "lysine.dev",
              timeToLive = 86_400,
              address = InetAddress.getByName("31.13.80.8"),
            ),
          ),
      ),
    )
  }

  @Test
  fun `ipv6 round trip`() {
    assertRoundTrip(
      DnsMessage(
        id = 65432.toShort(),
        flags = -32384,
        questions =
          listOf(
            Question(
              name = "lysine.dev",
              type = TYPE_AAAA,
            ),
          ),
        answers =
          listOf(
            ResourceRecord.IpAddress(
              name = "lysine.dev",
              timeToLive = 60,
              address = InetAddress.getByName("2607:f8b0:4023:1807:0:0:0:71"),
            ),
            ResourceRecord.IpAddress(
              name = "lysine.dev",
              timeToLive = 300,
              address = InetAddress.getByName("2607:f8b0:4023:1807:0:0:0:8b"),
            ),
          ),
      ),
    )
  }

  /**
   * This also confirms that name compression accepts elements from [ResourceRecord.Https] records
   * and uses those records.
   */
  @Test
  fun `https round trip`() {
    assertRoundTrip(
      DnsMessage(
        id = 65432.toShort(),
        flags = -32384,
        questions =
          listOf(
            Question(
              name = "lysine.dev",
              type = TYPE_HTTPS,
            ),
          ),
        answers =
          listOf(
            ResourceRecord.Https(
              name = "lysine.dev",
              timeToLive = 60,
              priority = 2,
              targetName = "ca-west.cdn.lysine.dev",
              alpnIds = listOf("h2", "http/1.1"),
              port = 8443,
              ipAddressHints =
                listOf(
                  InetAddress.getByName("172.217.23.238"),
                  InetAddress.getByName("31.13.80.8"),
                  InetAddress.getByName("2607:f8b0:4023:1807:0:0:0:71"),
                  InetAddress.getByName("2607:f8b0:4023:1807:0:0:0:8b"),
                ),
              echConfigList =
                """
                003dfe0d0039aa00200020a4a7bb34b77c43336c3a2931dd28c87d008218a99b44f1f0aa8a82537d487d
                43000400010001000a676f6f676c652e636f6d0000
                """.decodeHex(ignoreWhitespace = true),
            ),
            ResourceRecord.Https(
              name = "lysine.dev",
              timeToLive = 300,
              priority = 1,
              targetName = "ca-east.cdn.lysine.dev",
              alpnIds = listOf("h2"),
            ),
          ),
      ),
    )
  }

  @Test
  fun `unbounded name compression`() {
    val buffer = Buffer()
    buffer.write(
      """
      000081800001000100000000066c7973696e65c00c000100010363646ec00c000100010000000000040a141e28
      """.decodeHex(ignoreWhitespace = true),
    )

    val reader = DnsMessageReader(buffer)
    val e =
      assertFailsWith<ProtocolException> {
        reader.read()
      }
    assertThat(e).hasMessage("malformed DNS message")
  }

  /**
   * An RDLENGTH is a 16-bit unsigned field, so a record may legitimately declare up to 65535 bytes
   * of data. A value with the high bit set must still be skipped for record types we don't decode,
   * otherwise the reader loses sync with the stream.
   */
  @Test
  fun `unsupported record with high-bit record length is skipped`() {
    val buffer =
      Buffer().apply {
        writeShort(0x0000) // id
        writeShort(0x8180) // flags
        writeShort(1) // question count
        writeShort(1) // answer count
        writeShort(0) // authority record count
        writeShort(0) // additional record count

        // Question: "a", type A, class IN.
        writeByte(1)
        writeByte('a'.code)
        writeByte(0)
        writeShort(TYPE_A)
        writeShort(CLASS_IN)

        // Answer with an unsupported type and 0x8000 bytes of record data.
        writeShort(0xc00c) // Name compressed to the question's name.
        writeShort(16) // TYPE_TXT, which this reader doesn't decode.
        writeShort(CLASS_IN)
        writeInt(0) // time to live
        writeShort(0x8000) // record data length, 32768
        write(ByteArray(0x8000))
      }

    assertThat(DnsMessageReader(buffer).read()).isEqualTo(
      DnsMessage(
        id = 0,
        flags = -32384,
        questions =
          listOf(
            Question(
              name = "a",
              type = TYPE_A,
            ),
          ),
        answers = listOf(),
      ),
    )
  }

  @Test
  fun `rejects mandatory service parameter with unsupported key`() {
    // An HTTPS record whose mandatory value is the single 2-octet SvcParamKey 0x0101 (257), which
    // OkHttp does not support. RFC 9460 requires such a record be treated as malformed.
    val buffer = Buffer()
    buffer.write(
      "0000818000000001000000000000410001000000000009000100000000020101".decodeHex(),
    )
    assertFailsWith<ProtocolException> {
      DnsMessageReader(buffer).read()
    }
  }

  @Test
  fun `rejects odd length mandatory service parameter`() {
    // The mandatory value has an odd length, so it can't be a list of 2-octet SvcParamKeys.
    val buffer = Buffer()
    buffer.write(
      "00008180000000010000000000004100010000000000080001000000000103".decodeHex(),
    )
    assertFailsWith<ProtocolException> {
      DnsMessageReader(buffer).read()
    }
  }

  private fun assertRoundTrip(message: DnsMessage) {
    val buffer = Buffer()
    DnsMessageWriter(buffer).write(message)
    assertThat(DnsMessageReader(buffer).read()).isEqualTo(message)
  }
}
