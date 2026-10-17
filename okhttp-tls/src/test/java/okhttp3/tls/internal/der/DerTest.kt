/*
 * Copyright (C) 2020 Square, Inc.
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
package okhttp3.tls.internal.der

import assertk.assertThat
import assertk.assertions.hasMessage
import assertk.assertions.isEqualTo
import assertk.assertions.isFalse
import assertk.assertions.isNull
import assertk.assertions.isTrue
import java.math.BigInteger
import java.net.InetAddress
import java.net.ProtocolException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.TimeZone
import kotlin.test.assertFailsWith
import okhttp3.tls.internal.der.CertificateAdapters.generalNameDnsName
import okhttp3.tls.internal.der.CertificateAdapters.generalNameIpAddress
import okhttp3.tls.internal.der.ObjectIdentifiers.BASIC_CONSTRAINTS
import okhttp3.tls.internal.der.ObjectIdentifiers.COMMON_NAME
import okhttp3.tls.internal.der.ObjectIdentifiers.SHA256_WITH_RSA_ENCRYPTION
import okhttp3.tls.internal.der.ObjectIdentifiers.SUBJECT_ALTERNATIVE_NAME
import okio.Buffer
import okio.ByteString.Companion.decodeHex
import okio.ByteString.Companion.encodeUtf8
import okio.ByteString.Companion.toByteString
import org.junit.jupiter.api.Disabled
import org.junit.jupiter.api.Test

internal class DerTest {
  @Test fun `decode tag and length`() {
    val buffer =
      Buffer()
        .writeByte(0b00011110)
        .writeByte(0b10000001)
        .writeByte(0b11001001)

    val derReader = DerReader(buffer)

    derReader.read("test") { header ->
      assertThat(header.tagClass).isEqualTo(DerHeader.TAG_CLASS_UNIVERSAL)
      assertThat(header.tag).isEqualTo(30)
      assertThat(header.constructed).isFalse()
      assertThat(header.length).isEqualTo(201)
    }

    assertThat(derReader.hasNext()).isFalse()
  }

  @Test fun `decode length encoded with leading zero byte`() {
    val buffer =
      Buffer()
        .writeByte(0b00000010)
        .writeByte(0b10000010)
        .writeByte(0b00000000)
        .writeByte(0b01111111)

    val derReader = DerReader(buffer)

    assertFailsWith<ProtocolException> {
      derReader.read("test") {}
    }.also { expected ->
      assertThat(expected.message).isEqualTo("invalid encoding for length")
    }
  }

  @Test fun `decode length not encoded in shortest form possible`() {
    val buffer =
      Buffer()
        .writeByte(0b00000010)
        .writeByte(0b10000001)
        .writeByte(0b01111111)

    val derReader = DerReader(buffer)

    assertFailsWith<ProtocolException> {
      derReader.read("test") {}
    }.also { expected ->
      assertThat(expected.message).isEqualTo("invalid encoding for length")
    }
  }

  @Test fun `decode length equal to Long MAX_VALUE`() {
    val buffer =
      Buffer()
        .writeByte(0b00000010)
        .writeByte(0b10001000)
        .writeByte(0b01111111)
        .writeByte(0b11111111)
        .writeByte(0b11111111)
        .writeByte(0b11111111)
        .writeByte(0b11111111)
        .writeByte(0b11111111)
        .writeByte(0b11111111)
        .writeByte(0b11111111)

    val derReader = DerReader(buffer)

    val header = derReader.readHeader()
    assertThat(header.length).isEqualTo(Long.MAX_VALUE)
  }

  @Test fun `decode length overflowing Long`() {
    val buffer =
      Buffer()
        .writeByte(0b00000010)
        .writeByte(0b10001000)
        .writeByte(0b10000000)
        .writeByte(0b00000000)
        .writeByte(0b00000000)
        .writeByte(0b00000000)
        .writeByte(0b00000000)
        .writeByte(0b00000000)
        .writeByte(0b00000000)
        .writeByte(0b00000000)

    val derReader = DerReader(buffer)

    assertFailsWith<ProtocolException> {
      derReader.read("test") {}
    }.also { expected ->
      assertThat(expected.message).isEqualTo("length > Long.MAX_VALUE")
    }
  }

  @Test fun `decode length encoded with more than 8 bytes`() {
    val buffer =
      Buffer()
        .writeByte(0b00000010)
        .writeByte(0b10001001)
        .writeByte(0b11111111)
        .writeByte(0b11111111)
        .writeByte(0b11111111)
        .writeByte(0b11111111)
        .writeByte(0b11111111)
        .writeByte(0b11111111)
        .writeByte(0b11111111)
        .writeByte(0b11111111)
        .writeByte(0b11111111)
        .writeByte(0b11111111)

    val derReader = DerReader(buffer)

    assertFailsWith<ProtocolException> {
      derReader.read("test") {}
    }.also { expected ->
      assertThat(expected.message)
        .isEqualTo("length encoded with more than 8 bytes is not supported")
    }
  }

  @Test fun `encode tag and length`() {
    val buffer = Buffer()
    val derWriter = DerWriter(buffer)

    derWriter.write("test", tagClass = DerHeader.TAG_CLASS_UNIVERSAL, tag = 30L) {
      derWriter.writeUtf8("a".repeat(201))
    }

    assertThat(buffer.readByteString(3)).isEqualTo("1e81c9".decodeHex())
    assertThat(buffer.readUtf8()).isEqualTo("a".repeat(201))
  }

  @Test fun `decode primitive bit string`() {
    val buffer =
      Buffer()
        .write("0307040A3B5F291CD0".decodeHex())

    val derReader = DerReader(buffer)

    derReader.read("test") { header ->
      assertThat(header.tag).isEqualTo(3L)
      assertThat(derReader.readBitString()).isEqualTo(BitString("0A3B5F291CD0".decodeHex(), 4))
    }

    assertThat(derReader.hasNext()).isFalse()
  }

  @Test fun `encode primitive bit string`() {
    val buffer = Buffer()
    val derWriter = DerWriter(buffer)

    derWriter.write("test", tagClass = DerHeader.TAG_CLASS_UNIVERSAL, tag = 3L) {
      derWriter.writeBitString(BitString("0A3B5F291CD0".decodeHex(), 4))
    }

    assertThat(buffer.readByteString()).isEqualTo("0307040A3B5F291CD0".decodeHex())
  }

  @Test fun `decode primitive string`() {
    val buffer =
      Buffer()
        .write("1A054A6F6E6573".decodeHex())

    val derReader = DerReader(buffer)

    derReader.read("test") { header ->
      assertThat(header.tag).isEqualTo(26L)
      assertThat(header.constructed).isFalse()
      assertThat(header.tagClass).isEqualTo(DerHeader.TAG_CLASS_UNIVERSAL)
      assertThat(derReader.readOctetString()).isEqualTo("Jones".encodeUtf8())
    }

    assertThat(derReader.hasNext()).isFalse()
  }

  @Test fun `encode primitive string`() {
    val buffer = Buffer()
    val derWriter = DerWriter(buffer)

    derWriter.write("test", tagClass = DerHeader.TAG_CLASS_UNIVERSAL, tag = 26L) {
      derWriter.writeOctetString("Jones".encodeUtf8())
    }

    assertThat(buffer.readByteString()).isEqualTo("1A054A6F6E6573".decodeHex())
  }

  @Test fun `decode implicit prefixed type`() {
    // Type1 ::= VisibleString
    // Type2 ::= [APPLICATION 3] IMPLICIT Type1
    val buffer =
      Buffer()
        .write("43054A6F6E6573".decodeHex())

    val derReader = DerReader(buffer)

    derReader.read("test") { header ->
      assertThat(header.tag).isEqualTo(3L)
      assertThat(header.tagClass).isEqualTo(DerHeader.TAG_CLASS_APPLICATION)
      assertThat(derReader.readOctetString()).isEqualTo("Jones".encodeUtf8())
    }

    assertThat(derReader.hasNext()).isFalse()
  }

  @Test fun `encode implicit prefixed type`() {
    // Type1 ::= VisibleString
    // Type2 ::= [APPLICATION 3] IMPLICIT Type1
    val buffer = Buffer()
    val derWriter = DerWriter(buffer)

    derWriter.write("test", tagClass = DerHeader.TAG_CLASS_APPLICATION, tag = 3L) {
      derWriter.writeOctetString("Jones".encodeUtf8())
    }

    assertThat(buffer.readByteString()).isEqualTo("43054A6F6E6573".decodeHex())
  }

  @Test fun `decode tagged implicit prefixed type`() {
    // Type1 ::= VisibleString
    // Type2 ::= [APPLICATION 3] IMPLICIT Type1
    // Type3 ::= [2] Type2
    val buffer =
      Buffer()
        .write("A20743054A6F6E6573".decodeHex())

    val derReader = DerReader(buffer)

    derReader.read("test") { header ->
      assertThat(header.tag).isEqualTo(2L)
      assertThat(header.tagClass).isEqualTo(DerHeader.TAG_CLASS_CONTEXT_SPECIFIC)
      assertThat(header.length).isEqualTo(7L)

      derReader.read("test") { header ->
        assertThat(header.tag).isEqualTo(3L)
        assertThat(header.tagClass).isEqualTo(DerHeader.TAG_CLASS_APPLICATION)
        assertThat(header.length).isEqualTo(5L)
        assertThat(derReader.readOctetString()).isEqualTo("Jones".encodeUtf8())
      }

      assertThat(derReader.hasNext()).isFalse()
    }

    assertThat(derReader.hasNext()).isFalse()
  }

  @Test fun `encode tagged implicit prefixed type`() {
    // Type1 ::= VisibleString
    // Type2 ::= [APPLICATION 3] IMPLICIT Type1
    // Type3 ::= [2] Type2
    val buffer = Buffer()
    val derWriter = DerWriter(buffer)

    derWriter.write("test", tagClass = DerHeader.TAG_CLASS_CONTEXT_SPECIFIC, tag = 2L) {
      derWriter.write("test", tagClass = DerHeader.TAG_CLASS_APPLICATION, tag = 3L) {
        derWriter.writeOctetString("Jones".encodeUtf8())
      }
    }

    assertThat(buffer.readByteString()).isEqualTo("A20743054A6F6E6573".decodeHex())
  }

  @Test fun `decode implicit tagged implicit prefixed type`() {
    // Type1 ::= VisibleString
    // Type2 ::= [APPLICATION 3] IMPLICIT Type1
    // Type3 ::= [2] Type2
    // Type4 ::= [APPLICATION 7] IMPLICIT Type3
    val buffer =
      Buffer()
        .write("670743054A6F6E6573".decodeHex())

    val derReader = DerReader(buffer)

    derReader.read("test") { header ->
      assertThat(header.tag).isEqualTo(7L)
      assertThat(header.tagClass).isEqualTo(DerHeader.TAG_CLASS_APPLICATION)
      assertThat(header.length).isEqualTo(7L)

      derReader.read("test") { header2 ->
        assertThat(header2.tag).isEqualTo(3L)
        assertThat(header2.tagClass).isEqualTo(DerHeader.TAG_CLASS_APPLICATION)
        assertThat(header2.length).isEqualTo(5L)
        assertThat(derReader.readOctetString()).isEqualTo("Jones".encodeUtf8())
      }

      assertThat(derReader.hasNext()).isFalse()
    }

    assertThat(derReader.hasNext()).isFalse()
  }

  @Test fun `encode implicit tagged implicit prefixed type`() {
    // Type1 ::= VisibleString
    // Type2 ::= [APPLICATION 3] IMPLICIT Type1
    // Type3 ::= [2] Type2
    // Type4 ::= [APPLICATION 7] IMPLICIT Type3
    val buffer = Buffer()
    val derWriter = DerWriter(buffer)

    derWriter.write("test", tagClass = DerHeader.TAG_CLASS_APPLICATION, tag = 7L) {
      derWriter.write("test", tagClass = DerHeader.TAG_CLASS_APPLICATION, tag = 3L) {
        derWriter.writeOctetString("Jones".encodeUtf8())
      }
    }

    assertThat(buffer.readByteString()).isEqualTo("670743054A6F6E6573".decodeHex())
  }

  @Test fun `decode implicit implicit prefixed type`() {
    // Type1 ::= VisibleString
    // Type2 ::= [APPLICATION 3] IMPLICIT Type1
    // Type5 ::= [2] IMPLICIT Type2
    val buffer =
      Buffer()
        .write("82054A6F6E6573".decodeHex())

    val derReader = DerReader(buffer)

    derReader.read("test") { header ->
      assertThat(header.tag).isEqualTo(2L)
      assertThat(header.tagClass).isEqualTo(DerHeader.TAG_CLASS_CONTEXT_SPECIFIC)
      assertThat(header.length).isEqualTo(5L)
      assertThat(derReader.readOctetString()).isEqualTo("Jones".encodeUtf8())
    }

    assertThat(derReader.hasNext()).isFalse()
  }

  @Test fun `encode implicit implicit prefixed type`() {
    // Type1 ::= VisibleString
    // Type2 ::= [APPLICATION 3] IMPLICIT Type1
    // Type5 ::= [2] IMPLICIT Type2
    val buffer = Buffer()
    val derWriter = DerWriter(buffer)

    derWriter.write(
      name = "test",
      tagClass = DerHeader.TAG_CLASS_CONTEXT_SPECIFIC,
      tag = 2L,
    ) {
      derWriter.writeOctetString("Jones".encodeUtf8())
    }

    assertThat(buffer.readByteString()).isEqualTo("82054A6F6E6573".decodeHex())
  }

  @Test fun `decode object identifier without adapter`() {
    val buffer =
      Buffer()
        .write("0603883703".decodeHex())

    val derReader = DerReader(buffer)

    derReader.read("test") { header ->
      assertThat(header.tag).isEqualTo(6L)
      assertThat(header.tagClass).isEqualTo(DerHeader.TAG_CLASS_UNIVERSAL)
      assertThat(header.length).isEqualTo(3L)
      assertThat(derReader.readObjectIdentifier()).isEqualTo("2.999.3")
    }

    assertThat(derReader.hasNext()).isFalse()
  }

  @Test fun `decode object identifier with overflowing subidentifier`() {
    val buffer =
      Buffer()
        .write("060a81808080808080808000".decodeHex())

    val derReader = DerReader(buffer)

    assertFailsWith<ProtocolException> {
      derReader.read("test") { derReader.readObjectIdentifier() }
    }.also { expected ->
      assertThat(expected.message).isEqualTo("variable length long > Long.MAX_VALUE")
    }
  }

  @Test fun `encode object identifier without adapter`() {
    val buffer = Buffer()
    val derWriter = DerWriter(buffer)

    derWriter.write(
      name = "test",
      tagClass = DerHeader.TAG_CLASS_UNIVERSAL,
      tag = 6L,
    ) {
      derWriter.writeObjectIdentifier("2.999.3")
    }

    assertThat(buffer.readByteString()).isEqualTo("0603883703".decodeHex())
  }

  @Test fun `decode relative object identifier`() {
    val buffer =
      Buffer()
        .write("0D04c27B0302".decodeHex())

    val derReader = DerReader(buffer)

    derReader.read("test") { header ->
      assertThat(header.tag).isEqualTo(13L)
      assertThat(header.tagClass).isEqualTo(DerHeader.TAG_CLASS_UNIVERSAL)
      assertThat(header.length).isEqualTo(4L)
      assertThat(derReader.readRelativeObjectIdentifier()).isEqualTo("8571.3.2")
    }

    assertThat(derReader.hasNext()).isFalse()
  }

  @Test fun `encode relative object identifier`() {
    val buffer = Buffer()
    val derWriter = DerWriter(buffer)

    derWriter.write(
      name = "test",
      tagClass = DerHeader.TAG_CLASS_UNIVERSAL,
      tag = 13L,
    ) {
      derWriter.writeRelativeObjectIdentifier("8571.3.2")
    }

    assertThat(buffer.readByteString()).isEqualTo("0D04c27B0302".decodeHex())
  }

  @Test fun `decode raw sequence`() {
    val buffer =
      Buffer()
        .write("300A".decodeHex())
        .write("1505".decodeHex())
        .write("Smith".encodeUtf8())
        .write("01".decodeHex())
        .write("01".decodeHex())
        .write("FF".decodeHex())

    val derReader = DerReader(buffer)

    derReader.read("test") { header ->
      assertThat(header.tag).isEqualTo(16L)

      derReader.read("test") { header2 ->
        assertThat(header2.tag).isEqualTo(21L)
        assertThat(derReader.readOctetString()).isEqualTo("Smith".encodeUtf8())
      }

      derReader.read("test") { header3 ->
        assertThat(header3.tag).isEqualTo(1L)
        assertThat(derReader.readBoolean()).isTrue()
      }

      assertThat(derReader.hasNext()).isFalse()
    }

    assertThat(derReader.hasNext()).isFalse()
  }

  @Test fun `encode raw sequence`() {
    val buffer = Buffer()
    val derWriter = DerWriter(buffer)

    derWriter.write(
      name = "test",
      tagClass = DerHeader.TAG_CLASS_UNIVERSAL,
      tag = 16L,
    ) {
      derWriter.write(
        name = "test",
        tagClass = DerHeader.TAG_CLASS_UNIVERSAL,
        tag = 21L,
      ) {
        derWriter.writeOctetString("Smith".encodeUtf8())
      }

      derWriter.write(
        name = "test",
        tagClass = DerHeader.TAG_CLASS_UNIVERSAL,
        tag = 1L,
      ) {
        derWriter.writeBoolean(true)
      }
    }

    assertThat(buffer.readByteString()).isEqualTo("300a1505536d6974680101ff".decodeHex())
  }

  @Test fun `sequence of`() {
    val bytes = "3009020107020108020109".decodeHex()
    val sequenceOf = listOf(7L, 8L, 9L)
    val adapter = Adapters.INTEGER_AS_LONG.asSequenceOf()
    assertThat(adapter.fromDer(bytes)).isEqualTo(sequenceOf)
    assertThat(adapter.toDer(sequenceOf)).isEqualTo(bytes)
  }

  @Test fun `point with only x set`() {
    val bytes = "3003800109".decodeHex()
    val point = Point(9L, null)
    assertThat(Point.ADAPTER.fromDer(bytes)).isEqualTo(point)
    assertThat(Point.ADAPTER.toDer(point)).isEqualTo(bytes)
  }

  @Test fun `point with only y set`() {
    val bytes = "3003810109".decodeHex()
    val point = Point(null, 9L)
    assertThat(Point.ADAPTER.fromDer(bytes)).isEqualTo(point)
    assertThat(Point.ADAPTER.toDer(point)).isEqualTo(bytes)
  }

  @Test fun `point with both fields set`() {
    val bytes = "3006800109810109".decodeHex()
    val point = Point(9L, 9L)
    assertThat(Point.ADAPTER.fromDer(bytes)).isEqualTo(point)
    assertThat(Point.ADAPTER.toDer(point)).isEqualTo(bytes)
  }

  @Test fun `implicit tag`() {
    // [5] IMPLICIT UTF8String
    val bytes = "85026869".decodeHex()
    val implicitAdapter = Adapters.UTF8_STRING.withTag(tag = 5L)
    assertThat(implicitAdapter.fromDer(bytes)).isEqualTo("hi")
    assertThat(implicitAdapter.toDer("hi")).isEqualTo(bytes)
  }

  @Test fun `encode implicit`() {
    // [5] IMPLICIT UTF8String
    val implicitAdapter = Adapters.UTF8_STRING.withTag(tag = 5L)
    val string = implicitAdapter.fromDer("85026869".decodeHex())
    assertThat(string).isEqualTo("hi")
  }

  @Test fun `explicit tag`() {
    // [5] EXPLICIT UTF8String
    val bytes = "A5040C026869".decodeHex()
    val explicitAdapter = Adapters.UTF8_STRING.withExplicitBox(tag = 5L)
    assertThat(explicitAdapter.fromDer(bytes)).isEqualTo("hi")
    assertThat(explicitAdapter.toDer("hi")).isEqualTo(bytes)
  }

  @Test fun `boolean`() {
    val bytes = "0101FF".decodeHex()
    assertThat(Adapters.BOOLEAN.fromDer(bytes)).isEqualTo(true)
    assertThat(Adapters.BOOLEAN.toDer(true)).isEqualTo(bytes)
  }

  @Test fun `positive integer`() {
    val bytes = "020132".decodeHex()
    assertThat(Adapters.INTEGER_AS_LONG.toDer(50L)).isEqualTo(bytes)
    assertThat(Adapters.INTEGER_AS_LONG.fromDer(bytes)).isEqualTo(50L)
  }

  @Test fun `decode negative integer`() {
    val bytes = "02019c".decodeHex()
    assertThat(Adapters.INTEGER_AS_LONG.fromDer(bytes)).isEqualTo(-100L)
    assertThat(Adapters.INTEGER_AS_LONG.toDer(-100L)).isEqualTo(bytes)
  }

  @Test fun `five byte integer`() {
    val bytes = "02058000000001".decodeHex()
    assertThat(Adapters.INTEGER_AS_LONG.fromDer(bytes)).isEqualTo(-549755813887L)
    assertThat(Adapters.INTEGER_AS_LONG.toDer(-549755813887L)).isEqualTo(bytes)
  }

  @Test fun `eight zeros`() {
    val bytes = "020200ff".decodeHex()
    assertThat(Adapters.INTEGER_AS_LONG.fromDer(bytes)).isEqualTo(255)
    assertThat(Adapters.INTEGER_AS_LONG.toDer(255)).isEqualTo(bytes)
  }

  @Test fun `eight ones`() {
    val bytes = "0201ff".decodeHex()
    assertThat(Adapters.INTEGER_AS_LONG.toDer(-1L)).isEqualTo(bytes)
    assertThat(Adapters.INTEGER_AS_LONG.fromDer(bytes)).isEqualTo(-1L)
  }

  @Test fun `last byte all zeros`() {
    val bytes = "0202ff00".decodeHex()
    assertThat(Adapters.INTEGER_AS_LONG.toDer(-256L)).isEqualTo(bytes)
    assertThat(Adapters.INTEGER_AS_LONG.fromDer(bytes)).isEqualTo(-256L)
  }

  @Test fun `max long`() {
    val bytes = "02087fffffffffffffff".decodeHex()
    assertThat(Adapters.INTEGER_AS_LONG.fromDer(bytes)).isEqualTo(Long.MAX_VALUE)
    assertThat(Adapters.INTEGER_AS_LONG.toDer(Long.MAX_VALUE)).isEqualTo(bytes)
  }

  @Test fun `min long`() {
    val bytes = "02088000000000000000".decodeHex()
    assertThat(Adapters.INTEGER_AS_LONG.fromDer(bytes)).isEqualTo(Long.MIN_VALUE)
    assertThat(Adapters.INTEGER_AS_LONG.toDer(Long.MIN_VALUE)).isEqualTo(bytes)
  }

  @Test fun `bigger than max long`() {
    val bytes = "0209008000000000000001".decodeHex()
    val bigInteger = BigInteger("9223372036854775809")
    assertThat(Adapters.INTEGER_AS_BIG_INTEGER.fromDer(bytes)).isEqualTo(bigInteger)
    assertThat(Adapters.INTEGER_AS_BIG_INTEGER.toDer(bigInteger)).isEqualTo(bytes)
  }

  @Test fun `utf8 string`() {
    val bytes = "0c04f09f988e".decodeHex()
    assertThat(Adapters.UTF8_STRING.fromDer(bytes)).isEqualTo("\uD83D\uDE0E")
    assertThat(Adapters.UTF8_STRING.toDer("\uD83D\uDE0E")).isEqualTo(bytes)
  }

  @Test fun `ia5 string`() {
    val bytes = "16026869".decodeHex()
    assertThat(Adapters.IA5_STRING.fromDer(bytes)).isEqualTo("hi")
    assertThat(Adapters.IA5_STRING.toDer("hi")).isEqualTo(bytes)
  }

  @Test fun `printable string`() {
    val bytes = "13026869".decodeHex()
    assertThat(Adapters.PRINTABLE_STRING.fromDer(bytes)).isEqualTo("hi")
    assertThat(Adapters.PRINTABLE_STRING.toDer("hi")).isEqualTo(bytes)
  }

  @Test fun `cannot decode utc time with offset`() {
    assertFailsWith<ProtocolException> {
      Adapters.UTC_TIME.fromDer("17113139313231353139303231302d30383030".decodeHex())
    }.also { expected ->
      assertThat(expected).hasMessage("Failed to parse UTCTime 191215190210-0800")
    }
  }

  @Test fun `utc time`() {
    val bytes = "170d3139313231363033303231305a".decodeHex()
    val utcTime = date("2019-12-16T03:02:10.000+0000").time
    assertThat(Adapters.UTC_TIME.toDer(utcTime)).isEqualTo(bytes)
    assertThat(Adapters.UTC_TIME.fromDer(bytes)).isEqualTo(utcTime)
  }

  @Test fun `cannot decode malformed utc time`() {
    val bytes = "170d3139313231362333303231305a".decodeHex()
    assertFailsWith<ProtocolException> {
      Adapters.UTC_TIME.fromDer(bytes)
    }.also { expected ->
      assertThat(expected).hasMessage("Failed to parse UTCTime 191216#30210Z")
    }
  }

  @Test fun `cannot decode generalized time with offset`() {
    assertFailsWith<ProtocolException> {
      Adapters.GENERALIZED_TIME.fromDer("181332303139313231353139303231302d30383030".decodeHex())
    }.also { expected ->
      assertThat(expected).hasMessage("Failed to parse GeneralizedTime 20191215190210-0800")
    }
  }

  @Test fun `generalized time`() {
    val bytes = "180f32303139313231363033303231305a".decodeHex()
    val generalizedTime = date("2019-12-16T03:02:10.000+0000").time
    assertThat(Adapters.GENERALIZED_TIME.fromDer(bytes)).isEqualTo(generalizedTime)
    assertThat(Adapters.GENERALIZED_TIME.toDer(generalizedTime)).isEqualTo(bytes)
  }

  @Test fun `cannot decode malformed generalized time`() {
    val bytes = "180f32303139313231362333303231305a".decodeHex()
    assertFailsWith<ProtocolException> {
      Adapters.GENERALIZED_TIME.fromDer(bytes)
    }.also { expected ->
      assertThat(expected).hasMessage("Failed to parse GeneralizedTime 20191216#30210Z")
    }
  }

  @Test fun `parse utc time`() {
    assertThat(Adapters.parseUtcTime("920521000000Z"))
      .isEqualTo(date("1992-05-21T00:00:00.000+0000").time)
    assertThat(Adapters.parseUtcTime("920622123421Z"))
      .isEqualTo(date("1992-06-22T12:34:21.000+0000").time)
    assertThat(Adapters.parseUtcTime("920722132100Z"))
      .isEqualTo(date("1992-07-22T13:21:00.000+0000").time)
  }

  @Test fun `decode utc time two digit year cutoff is 1950`() {
    assertThat(Adapters.parseUtcTime("500101000000Z"))
      .isEqualTo(date("1950-01-01T00:00:00.000+0000").time)
    assertThat(Adapters.parseUtcTime("500101010000Z"))
      .isEqualTo(date("1950-01-01T01:00:00.000+0000").time)

    assertThat(Adapters.parseUtcTime("491231225959Z"))
      .isEqualTo(date("2049-12-31T22:59:59.000+0000").time)
    assertThat(Adapters.parseUtcTime("491231235959Z"))
      .isEqualTo(date("2049-12-31T23:59:59.000+0000").time)
  }

  @Test fun `encode utc time two digit year cutoff is 1950`() {
    assertThat(Adapters.formatUtcTime(date("1950-01-01T00:00:00.000+0000").time))
      .isEqualTo("500101000000Z")
    assertThat(Adapters.formatUtcTime(date("2049-12-31T23:59:59.000+0000").time))
      .isEqualTo("491231235959Z")
  }

  @Test fun `parse generalized time`() {
    assertThat(Adapters.parseGeneralizedTime("18990101000000Z"))
      .isEqualTo(date("1899-01-01T00:00:00.000+0000").time)
    assertThat(Adapters.parseGeneralizedTime("19500101000000Z"))
      .isEqualTo(date("1950-01-01T00:00:00.000+0000").time)
    assertThat(Adapters.parseGeneralizedTime("20500101000000Z"))
      .isEqualTo(date("2050-01-01T00:00:00.000+0000").time)
    assertThat(Adapters.parseGeneralizedTime("20990101000000Z"))
      .isEqualTo(date("2099-01-01T00:00:00.000+0000").time)
    assertThat(Adapters.parseGeneralizedTime("19920521000000Z"))
      .isEqualTo(date("1992-05-21T00:00:00.000+0000").time)
    assertThat(Adapters.parseGeneralizedTime("19920622123421Z"))
      .isEqualTo(date("1992-06-22T12:34:21.000+0000").time)
  }

  @Disabled("fractional seconds are not implemented")
  @Test
  fun `parse generalized time with fractional seconds`() {
    assertThat(Adapters.parseGeneralizedTime("19920722132100.3Z"))
      .isEqualTo(date("1992-07-22T13:21:00.300+0000").time)
  }

  @Test fun `format generalized time`() {
    assertThat(Adapters.formatGeneralizedTime(date("1899-01-01T00:00:00.000+0000").time))
      .isEqualTo("18990101000000Z")
    assertThat(Adapters.formatGeneralizedTime(date("1950-01-01T00:00:00.000+0000").time))
      .isEqualTo("19500101000000Z")
    assertThat(Adapters.formatGeneralizedTime(date("2050-01-01T00:00:00.000+0000").time))
      .isEqualTo("20500101000000Z")
    assertThat(Adapters.formatGeneralizedTime(date("2099-01-01T00:00:00.000+0000").time))
      .isEqualTo("20990101000000Z")
  }

  @Test fun `decode object identifier`() {
    val bytes = "06092a864886f70d01010b".decodeHex()
    assertThat(Adapters.OBJECT_IDENTIFIER.fromDer(bytes)).isEqualTo(SHA256_WITH_RSA_ENCRYPTION)
    assertThat(Adapters.OBJECT_IDENTIFIER.toDer(SHA256_WITH_RSA_ENCRYPTION)).isEqualTo(bytes)
  }

  @Test fun `null value`() {
    val bytes = "0500".decodeHex()
    assertThat(Adapters.NULL.fromDer(bytes)).isNull()
    assertThat(Adapters.NULL.toDer(null)).isEqualTo(bytes)
  }

  @Test fun `sequence algorithm`() {
    val bytes = "300d06092a864886f70d01010b0500".decodeHex()
    val algorithmIdentifier =
      AlgorithmIdentifier(
        algorithm = SHA256_WITH_RSA_ENCRYPTION,
        parameters = null,
      )
    assertThat(CertificateAdapters.algorithmIdentifier.fromDer(bytes))
      .isEqualTo(algorithmIdentifier)
    assertThat(CertificateAdapters.algorithmIdentifier.toDer(algorithmIdentifier))
      .isEqualTo(bytes)
  }

  @Test fun `bit string`() {
    val bytes = "0304066e5dc0".decodeHex()
    val bitString = BitString("6e5dc0".decodeHex(), 6)

    assertThat(Adapters.BIT_STRING.fromDer(bytes)).isEqualTo(bitString)
    assertThat(Adapters.BIT_STRING.toDer(bitString)).isEqualTo(bytes)
  }

  @Test fun `cannot decode empty bit string`() {
    val bytes = "0300".decodeHex()
    assertFailsWith<ProtocolException> {
      Adapters.BIT_STRING.fromDer(bytes)
    }.also { expected ->
      assertThat(expected).hasMessage("malformed bit string")
    }
  }

  @Test fun `octet string`() {
    val bytes = "0404030206A0".decodeHex()
    val octetString = "030206A0".decodeHex()
    assertThat(Adapters.OCTET_STRING.fromDer(bytes)).isEqualTo(octetString)
    assertThat(Adapters.OCTET_STRING.toDer(octetString)).isEqualTo(bytes)
  }

  @Test fun `cannot decode constructed octet string`() {
    assertFailsWith<ProtocolException> {
      Adapters.OCTET_STRING.fromDer(
        "2410040668656c6c6f200406776f726c6421".decodeHex(),
      )
    }.also { expected ->
      assertThat(expected).hasMessage("constructed octet strings not supported for DER")
    }
  }

  @Test fun `cannot decode constructed bit string`() {
    assertFailsWith<ProtocolException> {
      Adapters.BIT_STRING.fromDer(
        "231203070068656c6c6f20030700776f726c6421".decodeHex(),
      )
    }.also { expected ->
      assertThat(expected).hasMessage("constructed bit strings not supported for DER")
    }
  }

  @Test fun `cannot decode constructed string`() {
    assertFailsWith<ProtocolException> {
      Adapters.UTF8_STRING.fromDer(
        "2c100c0668656c6c6f200c06776f726c6421".decodeHex(),
      )
    }.also { expected ->
      assertThat(expected).hasMessage("constructed strings not supported for DER")
    }
  }

  @Test fun `cannot decode indefinite length bit string`() {
    assertFailsWith<ProtocolException> {
      Adapters.BIT_STRING.fromDer(
        "23800303000A3B0305045F291CD00000".decodeHex(),
      )
    }.also { expected ->
      assertThat(expected).hasMessage("indefinite length not permitted for DER")
    }
  }

  @Test fun `cannot decode constructed octet string in enclosing sequence`() {
    val buffer =
      Buffer()
        .write("3A0904034A6F6E04026573".decodeHex())
    val derReader = DerReader(buffer)
    assertFailsWith<Exception> {
      derReader.read("test") {
        derReader.readOctetString()
      }
    }.also { expected ->
      assertThat(expected).hasMessage("constructed octet strings not supported for DER")
    }
  }

  @Test fun `choice IP address`() {
    val bytes = "8704c0a80201".decodeHex()
    val localhost = InetAddress.getByName("192.168.2.1").address.toByteString()
    assertThat(CertificateAdapters.generalName.fromDer(bytes))
      .isEqualTo(generalNameIpAddress to localhost)
    assertThat(CertificateAdapters.generalName.toDer(generalNameIpAddress to localhost))
      .isEqualTo(bytes)
  }

  @Test fun `choice dns`() {
    val bytes = "820b6578616d706c652e636f6d".decodeHex()
    assertThat(CertificateAdapters.generalName.fromDer(bytes))
      .isEqualTo(generalNameDnsName to "example.com")
    assertThat(CertificateAdapters.generalName.toDer(generalNameDnsName to "example.com"))
      .isEqualTo(bytes)
  }

  @Test fun `extension with type hint for basic constraints`() {
    val extension =
      Extension(
        BASIC_CONSTRAINTS,
        false,
        BasicConstraints(true, 4),
      )
    val bytes = "300f0603551d13040830060101ff020104".decodeHex()

    assertThat(CertificateAdapters.extension.toDer(extension))
      .isEqualTo(bytes)
    assertThat(CertificateAdapters.extension.fromDer(bytes))
      .isEqualTo(extension)
  }

  @Test fun `extension with type hint for subject alternative names`() {
    val extension =
      Extension(
        SUBJECT_ALTERNATIVE_NAME,
        false,
        listOf(
          generalNameDnsName to "cash.app",
          generalNameDnsName to "www.cash.app",
        ),
      )
    val bytes = "30210603551d11041a30188208636173682e617070820c7777772e636173682e617070".decodeHex()

    assertThat(CertificateAdapters.extension.toDer(extension))
      .isEqualTo(bytes)
    assertThat(CertificateAdapters.extension.fromDer(bytes))
      .isEqualTo(extension)
  }

  @Test fun `extension with unknown type hint`() {
    val extension =
      Extension(
        // common name is not an extension.
        COMMON_NAME,
        false,
        "3006800109810109".decodeHex(),
      )
    val bytes = "300f060355040304083006800109810109".decodeHex()

    assertThat(CertificateAdapters.extension.toDer(extension))
      .isEqualTo(bytes)
    assertThat(CertificateAdapters.extension.fromDer(bytes))
      .isEqualTo(extension)
  }

  /** Tags larger than 30 are a special case. */
  @Test fun `large tag`() {
    val bytes = "df83fb6800".decodeHex()

    val adapter = Adapters.NULL.withTag(tagClass = DerHeader.TAG_CLASS_PRIVATE, tag = 65_000L)
    assertThat(adapter.toDer(null)).isEqualTo(bytes)
    assertThat(adapter.fromDer(bytes)).isNull()
  }

  /** Make the claimed length of a nested object larger than the enclosing object. */
  @Test fun `large object inside small object`() {
    val bytes = "301b300d06092a864886f70d010101050003847fffffff000504030201".decodeHex()
    assertFailsWith<ProtocolException> {
      CertificateAdapters.subjectPublicKeyInfo.fromDer(bytes)
    }.also { expected ->
      assertThat(expected.message).isEqualTo("enclosed object too large")
    }
  }

  /** Object identifiers are nominally self-delimiting. Outrun the limit with one. */
  @Test fun `variable length long outruns limit`() {
    val bytes = "060229ffffff7f".decodeHex()
    assertFailsWith<ProtocolException> {
      Adapters.OBJECT_IDENTIFIER.fromDer(bytes)
    }.also { expected ->
      assertThat(expected.message).isEqualTo("unexpected byte count at OBJECT IDENTIFIER")
    }
  }

  /**
   * ```
   * Point ::= SEQUENCE {
   *   x [0] INTEGER OPTIONAL,
   *   y [1] INTEGER OPTIONAL
   * }
   * ```
   */
  data class Point(
    val x: Long?,
    val y: Long?,
  ) {
    companion object {
      val ADAPTER =
        Adapters.sequence(
          "Point",
          Adapters.INTEGER_AS_LONG.withTag(tag = 0L).optional(),
          Adapters.INTEGER_AS_LONG.withTag(tag = 1L).optional(),
          decompose = { listOf(it.x, it.y) },
          construct = { Point(it[0] as Long?, it[1] as Long?) },
        )
    }
  }

  private fun date(s: String): Date =
    SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSZ").run {
      timeZone = TimeZone.getTimeZone("GMT")
      parse(s)
    }
}
