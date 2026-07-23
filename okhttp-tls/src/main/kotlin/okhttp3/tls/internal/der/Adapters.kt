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

import java.math.BigInteger
import java.net.ProtocolException
import java.text.ParseException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.TimeZone
import kotlin.reflect.KClass
import okio.ByteString

/**
 * Built-in adapters for reading standard ASN.1 types.
 */
internal object Adapters {
  val BOOLEAN =
    BasicDerAdapter(
      name = "BOOLEAN",
      tagClass = DerHeader.TAG_CLASS_UNIVERSAL,
      tag = 1L,
      codec =
        object : BasicDerAdapter.Codec<Boolean> {
          override fun decode(reader: DerReader): Boolean = reader.readBoolean()

          override fun encode(
            writer: DerWriter,
            value: Boolean,
          ) = writer.writeBoolean(value)
        },
    )

  val INTEGER_AS_LONG =
    BasicDerAdapter(
      name = "INTEGER",
      tagClass = DerHeader.TAG_CLASS_UNIVERSAL,
      tag = 2L,
      codec =
        object : BasicDerAdapter.Codec<Long> {
          override fun decode(reader: DerReader): Long = reader.readLong()

          override fun encode(
            writer: DerWriter,
            value: Long,
          ) = writer.writeLong(value)
        },
    )

  val INTEGER_AS_BIG_INTEGER =
    BasicDerAdapter(
      name = "INTEGER",
      tagClass = DerHeader.TAG_CLASS_UNIVERSAL,
      tag = 2L,
      codec =
        object : BasicDerAdapter.Codec<BigInteger> {
          override fun decode(reader: DerReader): BigInteger = reader.readBigInteger()

          override fun encode(
            writer: DerWriter,
            value: BigInteger,
          ) = writer.writeBigInteger(value)
        },
    )

  val BIT_STRING =
    BasicDerAdapter(
      name = "BIT STRING",
      tagClass = DerHeader.TAG_CLASS_UNIVERSAL,
      tag = 3L,
      codec =
        object : BasicDerAdapter.Codec<BitString> {
          override fun decode(reader: DerReader): BitString = reader.readBitString()

          override fun encode(
            writer: DerWriter,
            value: BitString,
          ) = writer.writeBitString(value)
        },
    )

  val OCTET_STRING =
    BasicDerAdapter(
      name = "OCTET STRING",
      tagClass = DerHeader.TAG_CLASS_UNIVERSAL,
      tag = 4L,
      codec =
        object : BasicDerAdapter.Codec<ByteString> {
          override fun decode(reader: DerReader): ByteString = reader.readOctetString()

          override fun encode(
            writer: DerWriter,
            value: ByteString,
          ) = writer.writeOctetString(value)
        },
    )

  val NULL =
    BasicDerAdapter(
      name = "NULL",
      tagClass = DerHeader.TAG_CLASS_UNIVERSAL,
      tag = 5L,
      codec =
        object : BasicDerAdapter.Codec<Unit?> {
          override fun decode(reader: DerReader): Unit? = null

          override fun encode(
            writer: DerWriter,
            value: Unit?,
          ) {
          }
        },
    )

  val OBJECT_IDENTIFIER =
    BasicDerAdapter(
      name = "OBJECT IDENTIFIER",
      tagClass = DerHeader.TAG_CLASS_UNIVERSAL,
      tag = 6L,
      codec =
        object : BasicDerAdapter.Codec<String> {
          override fun decode(reader: DerReader): String = reader.readObjectIdentifier()

          override fun encode(
            writer: DerWriter,
            value: String,
          ) = writer.writeObjectIdentifier(value)
        },
    )

  val UTF8_STRING =
    BasicDerAdapter(
      name = "UTF8",
      tagClass = DerHeader.TAG_CLASS_UNIVERSAL,
      tag = 12L,
      codec =
        object : BasicDerAdapter.Codec<String> {
          override fun decode(reader: DerReader): String = reader.readUtf8String()

          override fun encode(
            writer: DerWriter,
            value: String,
          ) = writer.writeUtf8(value)
        },
    )

  /**
   * Permits alphanumerics, spaces, and these:
   *
   * ```
   *   ' () + , - . / : = ?
   * ```
   *
   * TODO(jwilson): constrain to printable string characters.
   */
  val PRINTABLE_STRING =
    BasicDerAdapter(
      name = "PRINTABLE STRING",
      tagClass = DerHeader.TAG_CLASS_UNIVERSAL,
      tag = 19L,
      codec =
        object : BasicDerAdapter.Codec<String> {
          override fun decode(reader: DerReader): String = reader.readUtf8String()

          override fun encode(
            writer: DerWriter,
            value: String,
          ) = writer.writeUtf8(value)
        },
    )

  /**
   * Based on International Alphabet No. 5. Note that there are bytes that IA5 and US-ASCII
   * disagree on interpretation.
   *
   * TODO(jwilson): constrain to IA5 characters.
   */
  val IA5_STRING =
    BasicDerAdapter(
      name = "IA5 STRING",
      tagClass = DerHeader.TAG_CLASS_UNIVERSAL,
      tag = 22L,
      codec =
        object : BasicDerAdapter.Codec<String> {
          override fun decode(reader: DerReader): String = reader.readUtf8String()

          override fun encode(
            writer: DerWriter,
            value: String,
          ) = writer.writeUtf8(value)
        },
    )

  /**
   * A timestamp like "191216030210Z" or "191215190210-0800" for 2019-12-15T19:02:10-08:00. The
   * cutoff of the 2-digit year is 1950-01-01T00:00:00Z.
   */
  val UTC_TIME =
    BasicDerAdapter(
      name = "UTC TIME",
      tagClass = DerHeader.TAG_CLASS_UNIVERSAL,
      tag = 23L,
      codec =
        object : BasicDerAdapter.Codec<Long> {
          override fun decode(reader: DerReader): Long {
            val string = reader.readUtf8String()
            return parseUtcTime(string)
          }

          override fun encode(
            writer: DerWriter,
            value: Long,
          ) {
            val string = formatUtcTime(value)
            return writer.writeUtf8(string)
          }
        },
    )

  internal fun parseUtcTime(string: String): Long {
    val utc = TimeZone.getTimeZone("GMT")
    val dateFormat =
      SimpleDateFormat("yyMMddHHmmss'Z'").apply {
        timeZone = utc
        set2DigitYearStart(Date(-631152000000L)) // 1950-01-01T00:00:00Z.
      }

    try {
      val parsed = dateFormat.parse(string)
      return parsed.time
    } catch (e: ParseException) {
      throw ProtocolException("Failed to parse UTCTime $string")
    }
  }

  internal fun formatUtcTime(date: Long): String {
    val utc = TimeZone.getTimeZone("GMT")
    val dateFormat =
      SimpleDateFormat("yyMMddHHmmss'Z'").apply {
        timeZone = utc
        set2DigitYearStart(Date(-631152000000L)) // 1950-01-01T00:00:00Z.
      }

    return dateFormat.format(date)
  }

  /**
   * A timestamp like "191216030210Z" or "20191215190210-0800" for 2019-12-15T19:02:10-08:00. This
   * is the same as [UTC_TIME] with the exception of the 4-digit year.
   */
  val GENERALIZED_TIME =
    BasicDerAdapter(
      name = "GENERALIZED TIME",
      tagClass = DerHeader.TAG_CLASS_UNIVERSAL,
      tag = 24L,
      codec =
        object : BasicDerAdapter.Codec<Long> {
          override fun decode(reader: DerReader): Long {
            val string = reader.readUtf8String()
            return parseGeneralizedTime(string)
          }

          override fun encode(
            writer: DerWriter,
            value: Long,
          ) {
            val string = formatGeneralizedTime(value)
            return writer.writeUtf8(string)
          }
        },
    )

  /** Decodes any value without interpretation as [AnyValue]. */
  val ANY_VALUE =
    object : DerAdapter<AnyValue> {
      override fun matches(header: DerHeader): Boolean = true

      override fun fromDer(reader: DerReader): AnyValue {
        reader.read("ANY") { header ->
          val bytes = reader.readUnknown()
          return AnyValue(
            tagClass = header.tagClass,
            tag = header.tag,
            constructed = header.constructed,
            length = header.length,
            bytes = bytes,
          )
        }
      }

      override fun toDer(
        writer: DerWriter,
        value: AnyValue,
      ) {
        writer.write("ANY", value.tagClass, value.tag) {
          writer.writeOctetString(value.bytes)
          writer.constructed = value.constructed
        }
      }
    }

  internal fun parseGeneralizedTime(string: String): Long {
    val utc = TimeZone.getTimeZone("GMT")
    val dateFormat =
      SimpleDateFormat("yyyyMMddHHmmss'Z'").apply {
        timeZone = utc
      }

    try {
      val parsed = dateFormat.parse(string)
      return parsed.time
    } catch (e: ParseException) {
      throw ProtocolException("Failed to parse GeneralizedTime $string")
    }
  }

  internal fun formatGeneralizedTime(date: Long): String {
    val utc = TimeZone.getTimeZone("GMT")
    val dateFormat =
      SimpleDateFormat("yyyyMMddHHmmss'Z'").apply {
        timeZone = utc
      }

    return dateFormat.format(date)
  }

  /**
   * Returns a composite adapter for a struct or data class. This may be used for both SEQUENCE and
   * SET types.
   *
   * The fields are specified as a list of member adapters. When decoding, a value for each
   * non-optional member but be included in sequence.
   *
   * TODO: for sets, sort by tag when encoding.
   * TODO: for set ofs, sort by encoded value when encoding.
   */
  fun <T> sequence(
    name: String,
    vararg members: DerAdapter<*>,
    decompose: (T) -> List<*>,
    construct: (List<*>) -> T,
  ): BasicDerAdapter<T> {
    val codec =
      object : BasicDerAdapter.Codec<T> {
        override fun decode(reader: DerReader): T {
          return reader.withTypeHint {
            val list = mutableListOf<Any?>()

            while (list.size < members.size) {
              val member = members[list.size]
              list += member.fromDer(reader)
            }

            if (reader.hasNext()) {
              throw ProtocolException("unexpected ${reader.peekHeader()} at $reader")
            }

            return@withTypeHint construct(list)
          }
        }

        override fun encode(
          writer: DerWriter,
          value: T,
        ) {
          val list = decompose(value)
          writer.withTypeHint {
            for (i in list.indices) {
              val adapter = members[i] as DerAdapter<Any?>
              adapter.toDer(writer, list[i])
            }
          }
        }
      }

    return BasicDerAdapter(
      name = name,
      tagClass = DerHeader.TAG_CLASS_UNIVERSAL,
      tag = 16L,
      codec = codec,
    )
  }

  /** Returns an adapter that decodes as the first of a list of available types. */
  fun choice(vararg choices: DerAdapter<*>): DerAdapter<Pair<DerAdapter<*>, Any?>> {
    return object : DerAdapter<Pair<DerAdapter<*>, Any?>> {
      override fun matches(header: DerHeader): Boolean = true

      override fun fromDer(reader: DerReader): Pair<DerAdapter<*>, Any?> {
        val peekedHeader =
          reader.peekHeader()
            ?: throw ProtocolException("expected a value at $reader")

        val choice =
          choices.firstOrNull { it.matches(peekedHeader) }
            ?: throw ProtocolException(
              "expected a matching choice but was $peekedHeader at $reader",
            )

        return choice to choice.fromDer(reader)
      }

      override fun toDer(
        writer: DerWriter,
        value: Pair<DerAdapter<*>, Any?>,
      ) {
        val (adapter, v) = value
        (adapter as DerAdapter<Any?>).toDer(writer, v)
      }

      override fun toString(): String = choices.joinToString(separator = " OR ")
    }
  }

  /**
   * This decodes a value into its contents using a preceding member of the same SEQUENCE. For
   * example, extensions type IDs specify what types to use for the corresponding values.
   *
   * If the hint is unknown [chooser] should return null which will cause the value to be decoded as
   * an opaque byte string.
   *
   * This may optionally wrap the contents in a tag.
   */
  fun usingTypeHint(chooser: (Any?) -> DerAdapter<*>?): DerAdapter<Any?> {
    return object : DerAdapter<Any?> {
      override fun matches(header: DerHeader): Boolean = true

      override fun toDer(
        writer: DerWriter,
        value: Any?,
      ) {
        // If we don't understand this hint, encode the body as a byte string. The byte string
        // will include a tag and length header as a prefix.
        val adapter = chooser(writer.typeHint) as DerAdapter<Any?>?
        when {
          adapter != null -> adapter.toDer(writer, value)
          value != null -> writer.writeOctetString(value as ByteString)
          // else: unknown type with null value — field is absent, write nothing
        }
      }

      override fun fromDer(reader: DerReader): Any? {
        val adapter = chooser(reader.typeHint) as DerAdapter<Any?>?
        return when {
          adapter != null -> adapter.fromDer(reader)
          else -> reader.readUnknown()
        }
      }
    }
  }

  /**
   * Object class to adapter type. This approach limits us to one adapter per Kotlin class, which
   * might be too few for values like UTF_STRING and OBJECT_IDENTIFIER that share a Kotlin class but
   * have very different ASN.1 interpretations.
   */
  private val defaultAnyChoices =
    listOf(
      Boolean::class to BOOLEAN,
      BigInteger::class to INTEGER_AS_BIG_INTEGER,
      BitString::class to BIT_STRING,
      ByteString::class to OCTET_STRING,
      Unit::class to NULL,
      Nothing::class to OBJECT_IDENTIFIER,
      Nothing::class to UTF8_STRING,
      String::class to PRINTABLE_STRING,
      Nothing::class to IA5_STRING,
      Nothing::class to UTC_TIME,
      Long::class to GENERALIZED_TIME,
      AnyValue::class to ANY_VALUE,
    )

  fun any(
    vararg choices: Pair<KClass<*>, DerAdapter<*>> = defaultAnyChoices.toTypedArray(),
    isOptional: Boolean = false,
    optionalValue: Any? = null,
  ): DerAdapter<Any?> {
    return object : DerAdapter<Any?> {
      override fun matches(header: DerHeader): Boolean = true

      override fun toDer(
        writer: DerWriter,
        value: Any?,
      ) {
        when {
          isOptional && value == optionalValue -> {
            // Write nothing.
          }

          else -> {
            for ((type, adapter) in choices) {
              if (type.isInstance(value) || (value == null && type == Unit::class)) {
                (adapter as DerAdapter<Any?>).toDer(writer, value)
                return
              }
            }
          }
        }
      }

      override fun fromDer(reader: DerReader): Any? {
        if (isOptional && !reader.hasNext()) return optionalValue

        val peekedHeader =
          reader.peekHeader()
            ?: throw ProtocolException("expected a value at $reader")
        for ((_, adapter) in choices) {
          if (adapter.matches(peekedHeader)) {
            return adapter.fromDer(reader)
          }
        }

        throw ProtocolException("expected any but was $peekedHeader at $reader")
      }
    }
  }
}
