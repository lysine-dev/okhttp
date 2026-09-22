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
@file:Suppress("ktlint:standard:filename")

package okhttp3.internal.dns

import java.net.Inet4Address
import java.net.Inet6Address
import okhttp3.Protocol
import okhttp3.internal.OkHttpInternalApi
import okio.Buffer
import okio.ByteString
import okio.ByteString.Companion.encodeUtf8
import okio.utf8Size

/**
 * Encode DNS messages.
 *
 * https://datatracker.ietf.org/doc/html/rfc1035
 */
@OkHttpInternalApi
class DnsMessageWriter(
  private val sink: Buffer,
) {
  /**
   * When compressing name labels, this is the offset into [sink] of the label.
   *
   * Because we write [ResourceRecord.Https] values to an intermediate buffer, the overall offset
   * must be predicted when putting in this map.
   */
  private val labelToOffset = mutableMapOf<ByteString, Long>()
  private val messageStart = sink.size

  fun write(message: DnsMessage) {
    sink.writeShort(message.id.toInt())
    sink.writeShort(message.flags)
    sink.writeShort(message.questions.size)
    sink.writeShort(message.answers.size)
    sink.writeShort(message.authorityRecords.size)
    sink.writeShort(message.additionalRecords.size)

    for (question in message.questions) {
      writeQuestion(question)
    }
    for (resourceRecord in message.answers) {
      writeResourceRecord(resourceRecord)
    }
    for (resourceRecord in message.authorityRecords) {
      writeResourceRecord(resourceRecord)
    }
    for (resourceRecord in message.additionalRecords) {
      writeResourceRecord(resourceRecord)
    }
  }

  private fun writeQuestion(question: Question) {
    sink.writeName(question.name.encodeUtf8())
    sink.writeShort(question.type.toInt())
    sink.writeShort(question.`class`.toInt())
  }

  private tailrec fun Buffer.writeName(
    name: ByteString,
    receiverOffset: Long = 0L,
  ) {
    // Write an empty name.
    if (name.size == 0) {
      writeByte(0)
      return
    }

    // Write a back reference to a previous name.
    val compressedAt = labelToOffset[name]
    if (compressedAt != null) {
      writeShort(compressedAt.toInt() or 0b1100_0000_0000_0000)
      return
    }

    // We're about to write a new name. Remember its offset for future compression.
    labelToOffset[name] = receiverOffset + size - messageStart

    // This is the last label. Write it followed by an empty string.
    val dot = name.indexOf(LABEL_SEPARATOR)
    if (dot == -1) {
      check(name.size < 64) { "label too long" }
      writeByte(name.size)
      write(name)
      writeByte(0)
      return
    }

    // Write one label, then recurse.
    check(dot < 64) { "label too long" }
    writeByte(dot)
    write(name, offset = 0, byteCount = dot)
    writeName(name.substring(dot + 1), receiverOffset)
  }

  private fun writeResourceRecord(resourceRecord: ResourceRecord) {
    when (resourceRecord) {
      is ResourceRecord.Https -> writeHttps(resourceRecord)
      is ResourceRecord.IpAddress -> writeIpAddress(resourceRecord)
    }
  }

  private fun writeIpAddress(record: ResourceRecord.IpAddress) {
    val address = record.address.address
    sink.writeName(record.name.encodeUtf8())
    sink.writeShort(
      when (address.size) {
        4 -> TYPE_A
        16 -> TYPE_AAAA
        else -> error("unexpected address")
      },
    )
    sink.writeShort(CLASS_IN)
    sink.writeInt(record.timeToLive)
    sink.writeShort(address.size)
    sink.write(address)
  }

  /** https://datatracker.ietf.org/doc/rfc9460/ */
  private fun writeHttps(record: ResourceRecord.Https) {
    sink.writeName(record.name.encodeUtf8())
    sink.writeShort(TYPE_HTTPS)
    sink.writeShort(CLASS_IN)
    sink.writeInt(record.timeToLive)

    val content = Buffer()
    content.writeShort(record.priority)
    content.writeName(
      name = record.targetName.encodeUtf8(),
      receiverOffset = sink.size + 2,
    )

    if (record.alpnIds != null) {
      content.writeShort(SERVICE_PARAMETER_ALPN)
      val nonDefaultAlpnIds = record.alpnIds.filter { it != Protocol.HTTP_1_1.toString() }
      content.writeShort(nonDefaultAlpnIds.sumOf { 1 + it.utf8Size() }.toInt())
      for (alpnId in nonDefaultAlpnIds) {
        content.writeByte(alpnId.utf8Size().toInt())
        content.writeUtf8(alpnId)
      }
      if (Protocol.HTTP_1_1.toString() !in record.alpnIds) {
        content.writeShort(SERVICE_PARAMETER_NO_DEFAULT_ALPN)
        content.writeShort(0)
      }
    }

    if (record.port != 443) {
      content.writeShort(SERVICE_PARAMETER_PORT)
      content.writeShort(2)
      content.writeShort(record.port)
    }

    val ipv4Hints = record.ipAddressHints.filterIsInstance<Inet4Address>()
    if (ipv4Hints.isNotEmpty()) {
      content.writeShort(SERVICE_PARAMETER_IPV4_HINT)
      content.writeShort(ipv4Hints.size * 4)
      for (address in ipv4Hints) {
        content.write(address.address)
      }
    }

    // Note that ECH (5) sits between IPV4 (4) hints and IPv6 (6) hints because parameters must be
    // encoded in strict order, and somebody thought it was cute to use 4 for IPv4 and 6 for IPv6.
    if (record.echConfigList != null) {
      content.writeShort(SERVICE_PARAMETER_ECH)
      content.writeShort(record.echConfigList.size)
      content.write(record.echConfigList)
    }

    val ipv6Hints = record.ipAddressHints.filterIsInstance<Inet6Address>()
    if (ipv6Hints.isNotEmpty()) {
      content.writeShort(SERVICE_PARAMETER_IPV6_HINT)
      content.writeShort(ipv6Hints.size * 16)
      for (address in ipv6Hints) {
        content.write(address.address)
      }
    }

    check(content.size < UShort.MAX_VALUE.toLong()) { "record too long" }
    sink.writeShort(content.size.toInt())
    sink.writeAll(content)
  }
}

private val LABEL_SEPARATOR = ".".encodeUtf8()
