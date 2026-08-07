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

import java.net.InetAddress
import okhttp3.Protocol
import okhttp3.internal.OkHttpInternalApi
import okhttp3.internal.skipAll
import okio.Buffer
import okio.BufferedSource
import okio.ByteString
import okio.ProtocolException
import okio.buffer
import okio.limit
import okio.readUByte
import okio.readUShort

/**
 * Decode DNS messages, which are symmetric for requests and responses.
 *
 * https://datatracker.ietf.org/doc/html/rfc1035
 */
@OkHttpInternalApi
class DnsMessageReader(
  source: BufferedSource,
) {
  /**
   * Keep a reference to the source at its initial offset, so we can implement name compression.
   * That mechanism requires us to backtrack in the message.
   */
  private val sourceOffsetZero = source
  private val source = source.peek()

  fun read(): DnsMessage {
    val id = source.readShort()
    val flags = source.readShort().toInt()

    val questionCount = source.readUShort().toInt()
    val answerCount = source.readUShort().toInt()
    val authorityRecordCount = source.readUShort().toInt()
    val additionalRecordCount = source.readUShort().toInt()

    val questions = ArrayList<Question>(questionCount)
    for (i in 0 until questionCount) {
      questions += source.readQuestion()
    }

    val answers = ArrayList<ResourceRecord>(answerCount)
    for (i in 0 until answerCount) {
      answers += source.readResourceRecord() ?: continue
    }

    val authorityRecords = ArrayList<ResourceRecord>(authorityRecordCount)
    for (i in 0 until authorityRecordCount) {
      authorityRecords += source.readResourceRecord() ?: continue
    }

    val additionalRecords = ArrayList<ResourceRecord>(additionalRecordCount)
    for (i in 0 until additionalRecordCount) {
      additionalRecords += source.readResourceRecord() ?: continue
    }

    if (!source.exhausted()) {
      throw ProtocolException("unexpected trailing data in message")
    }

    sourceOffsetZero.skipAll()

    return DnsMessage(
      id = id,
      flags = flags,
      questions = questions,
      answers = answers,
      authorityRecords = authorityRecords,
      additionalRecords = additionalRecords,
    )
  }

  private fun BufferedSource.readQuestion(): Question {
    val name = readName()
    val type = readShort().toInt()
    val `class` = readShort().toInt()
    return Question(
      name = name,
      type = type,
      `class` = `class`,
    )
  }

  private fun BufferedSource.readName(): String {
    val result = Buffer()
    readName(result)
    return result.readUtf8()
  }

  private tailrec fun BufferedSource.readName(
    sink: Buffer,
    maxOffset: Int = Int.MAX_VALUE,
  ) {
    while (true) {
      val labelTypeAndLength = readUByte().toInt()
      val labelType = labelTypeAndLength and 0b11000000
      val labelLength = labelTypeAndLength and 0b00111111
      when (labelType) {
        // Inline value.
        0b00_000000 -> {
          if (labelLength == 0) return
          if (sink.size > 0L) sink.writeByte('.'.code)
          sink.write(this, labelLength.toLong())
        }

        // Compressed suffix.
        0b11_000000 -> {
          val offset = (labelLength shl 8) or readUByte().toInt()

          // Pointers may only refer to prior occurrences.
          if (offset >= maxOffset) {
            throw ProtocolException("malformed DNS message")
          }

          val offsetSource = sourceOffsetZero.peek()
          offsetSource.skip(offset.toLong())
          return offsetSource.readName(sink, maxOffset = offset)
        }

        0b01_000000, 0b10_000000 -> {
          throw ProtocolException("unsupported label type")
        }
      }
    }
  }

  /** Returns null if this record type is unsupported. */
  fun BufferedSource.readResourceRecord(): ResourceRecord? {
    val name = readName()
    val type = readShort().toInt()
    val `class` = readShort().toInt()
    val timeToLive = readInt()
    val recordDataLength = readUShort().toLong()

    when {
      `class` == CLASS_IN && type == TYPE_A -> {
        if (recordDataLength != 4L) throw ProtocolException("unexpected record length")
        val address = InetAddress.getByAddress(readByteArray(4L))
        return ResourceRecord.IpAddress(
          name = name,
          timeToLive = timeToLive,
          address = address,
        )
      }

      `class` == CLASS_IN && type == TYPE_AAAA -> {
        if (recordDataLength != 16L) throw ProtocolException("unexpected record length")
        val address = InetAddress.getByAddress(readByteArray(16L))
        return ResourceRecord.IpAddress(
          name = name,
          timeToLive = timeToLive,
          address = address,
        )
      }

      `class` == CLASS_IN && type == TYPE_HTTPS -> {
        return limit(recordDataLength)
          .buffer()
          .readHttpsResourceRecord(name, timeToLive)
      }

      else -> {
        skip(recordDataLength)
        return null
      }
    }
  }

  /** https://datatracker.ietf.org/doc/rfc9460/ */
  private fun BufferedSource.readHttpsResourceRecord(
    name: String,
    timeToLive: Int,
  ): ResourceRecord.Https {
    val svcPriority = readUShort()
    val targetName = readName()
    var lastKey = -1
    var alpnIds: MutableList<String>? = null
    var noDefaultAlpn = false
    var port = 443
    var ipAddressHints: MutableList<InetAddress>? = null
    var echConfigList: ByteString? = null

    while (!exhausted()) {
      val key = readUShort().toInt()
      if (key <= lastKey) throw ProtocolException("malformed HTTPS resource record")
      lastKey = key
      val valueLength = readUShort().toLong()
      when (key) {
        SERVICE_PARAMETER_MANDATORY -> {
          if (valueLength % 2 != 0L) throw ProtocolException("malformed HTTPS / mandatory")
          for (i in 0 until valueLength step 2) {
            val serviceParameterKey = readUShort().toInt()
            if (serviceParameterKey !in SERVICE_PARAMETER_MANDATORY..SERVICE_PARAMETER_IPV6_HINT) {
              throw ProtocolException("unsupported HTTPS mandatory parameter $serviceParameterKey")
            }
          }
        }

        SERVICE_PARAMETER_ALPN -> {
          alpnIds = mutableListOf()
          var pos = 0L
          while (pos < valueLength) {
            val alpnIdLength = readUByte().toLong()
            pos++
            if (pos + alpnIdLength > valueLength) {
              throw ProtocolException("malformed HTTPS / alpn")
            }
            alpnIds += readUtf8(alpnIdLength)
            pos += alpnIdLength
          }
        }

        SERVICE_PARAMETER_NO_DEFAULT_ALPN -> {
          if (valueLength != 0L) throw ProtocolException("malformed HTTPS / no-default-alpn")
          noDefaultAlpn = true
        }

        SERVICE_PARAMETER_PORT -> {
          if (valueLength != 2L) throw ProtocolException("malformed HTTPS / port")
          port = readUShort().toInt()
        }

        SERVICE_PARAMETER_IPV4_HINT -> {
          ipAddressHints = mutableListOf()
          if (valueLength % 4 != 0L) throw ProtocolException("malformed HTTPS / ipv4hint")
          for (i in 0 until valueLength step 4) {
            ipAddressHints += InetAddress.getByAddress(readByteArray(4))
          }
        }

        SERVICE_PARAMETER_ECH -> {
          echConfigList = readByteString(valueLength)
        }

        SERVICE_PARAMETER_IPV6_HINT -> {
          if (ipAddressHints == null) ipAddressHints = mutableListOf()
          if (valueLength % 16 != 0L) throw ProtocolException("malformed HTTPS / ipv6hint")
          for (i in 0 until valueLength step 16) {
            ipAddressHints += InetAddress.getByAddress(readByteArray(16))
          }
        }

        // Skip an unknown parameter.
        else -> {
          skip(valueLength)
        }
      }
    }

    if (noDefaultAlpn) {
      if (alpnIds == null) throw ProtocolException("malformed HTTPS / no-default-alpn")
    } else if (alpnIds != null && Protocol.HTTP_1_1.toString() !in alpnIds) {
      alpnIds += Protocol.HTTP_1_1.toString()
    }

    return ResourceRecord.Https(
      name = name,
      timeToLive = timeToLive,
      priority = svcPriority.toInt(),
      targetName = targetName,
      alpnIds = alpnIds,
      port = port,
      ipAddressHints = ipAddressHints ?: listOf(),
      echConfigList = echConfigList,
    )
  }
}
