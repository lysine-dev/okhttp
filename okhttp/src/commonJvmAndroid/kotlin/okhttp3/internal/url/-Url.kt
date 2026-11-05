/*
 * Copyright (C) 2022 Square, Inc.
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

package okhttp3.internal.url

import java.nio.charset.Charset
import okhttp3.internal.parseHexDigit
import okio.Buffer

internal val HEX_DIGITS =
  charArrayOf('0', '1', '2', '3', '4', '5', '6', '7', '8', '9', 'A', 'B', 'C', 'D', 'E', 'F')
internal const val USERNAME_ENCODE_SET = " \"':;<=>@[]^`{}|/\\?#"
internal const val PASSWORD_ENCODE_SET = " \"':;<=>@[]^`{}|/\\?#"
internal const val PATH_SEGMENT_ENCODE_SET = " \"<>^`{}|/\\?#"
internal const val PATH_SEGMENT_ENCODE_SET_RAW = " \"<>^`{}|/\\?#[]"
internal const val PATH_SEGMENT_ENCODE_SET_URI = "[]"
internal const val QUERY_ENCODE_SET = " \"'<>#"
internal const val QUERY_COMPONENT_REENCODE_SET = " \"'<>#&="
internal const val QUERY_COMPONENT_ENCODE_SET = " !\"#$&'(),/:;<=>?@[]\\^`{|}~"
internal const val QUERY_COMPONENT_ENCODE_SET_URI = "\\^`{|}"
internal const val FORM_ENCODE_SET = " !\"#$&'()+,/:;<=>?@[\\]^`{|}~"
internal const val FRAGMENT_ENCODE_SET = ""
internal const val FRAGMENT_ENCODE_SET_URI = " \"#<>\\^`{|}"

internal fun Buffer.writeCanonicalized(
  input: String,
  pos: Int,
  limit: Int,
  encodeSet: String,
  alreadyEncoded: Boolean,
  strict: Boolean,
  plusIsSpace: Boolean,
  unicodeAllowed: Boolean,
  charset: Charset?,
) {
  var encodedCharBuffer: Buffer? = null // Lazily allocated.
  var codePoint: Int
  var i = pos
  while (i < limit) {
    codePoint = input.codePointAt(i)
    if (alreadyEncoded &&
      (
        codePoint == '\t'.code ||
          codePoint == '\n'.code ||
          codePoint == '\u000c'.code ||
          codePoint == '\r'.code
      )
    ) {
      // Skip this character.
    } else if (codePoint == ' '.code && encodeSet === FORM_ENCODE_SET) {
      // Encode ' ' as '+'.
      writeUtf8("+")
    } else if (codePoint == '+'.code && plusIsSpace) {
      // Encode '+' as '%2B' since we permit ' ' to be encoded as either '+' or '%20'.
      writeUtf8(if (alreadyEncoded) "+" else "%2B")
    } else if (codePoint < 0x20 ||
      codePoint == 0x7f ||
      codePoint >= 0x80 &&
      !unicodeAllowed ||
      codePoint.toChar() in encodeSet ||
      codePoint == '%'.code &&
      (!alreadyEncoded || strict && !input.isPercentEncoded(i, limit))
    ) {
      // Percent encode this character.
      if (encodedCharBuffer == null) {
        encodedCharBuffer = Buffer()
      }

      if (charset == null || charset == Charsets.UTF_8) {
        encodedCharBuffer.writeUtf8CodePoint(codePoint)
      } else {
        encodedCharBuffer.writeString(input, i, i + Character.charCount(codePoint), charset)
      }

      while (!encodedCharBuffer.exhausted()) {
        val b = encodedCharBuffer.readByte().toInt() and 0xff
        writeByte('%'.code)
        writeByte(HEX_DIGITS[b shr 4 and 0xf].code)
        writeByte(HEX_DIGITS[b and 0xf].code)
      }
    } else {
      // This character doesn't need encoding. Just copy it over.
      writeUtf8CodePoint(codePoint)
    }
    i += Character.charCount(codePoint)
  }
}

/**
 * Returns a substring of `input` on the range `[pos..limit)` with the following
 * transformations:
 *
 *  * Tabs, newlines, form feeds and carriage returns are skipped.
 *
 *  * In queries, ' ' is encoded to '+' and '+' is encoded to "%2B".
 *
 *  * Characters in `encodeSet` are percent-encoded.
 *
 *  * Control characters and non-ASCII characters are percent-encoded.
 *
 *  * All other characters are copied without transformation.
 *
 * @param alreadyEncoded true to leave '%' as-is; false to convert it to '%25'.
 * @param strict true to encode '%' if it is not the prefix of a valid percent encoding.
 * @param plusIsSpace true to encode '+' as "%2B" if it is not already encoded.
 * @param unicodeAllowed true to leave non-ASCII codepoint unencoded.
 * @param charset which charset to use, null equals UTF-8.
 */
internal fun String.canonicalizeWithCharset(
  pos: Int = 0,
  limit: Int = length,
  encodeSet: String,
  alreadyEncoded: Boolean = false,
  strict: Boolean = false,
  plusIsSpace: Boolean = false,
  unicodeAllowed: Boolean = false,
  charset: Charset? = null,
): String {
  var codePoint: Int
  var i = pos
  while (i < limit) {
    codePoint = codePointAt(i)
    if (codePoint < 0x20 ||
      codePoint == 0x7f ||
      codePoint >= 0x80 &&
      !unicodeAllowed ||
      codePoint.toChar() in encodeSet ||
      codePoint == '%'.code &&
      (!alreadyEncoded || strict && !isPercentEncoded(i, limit)) ||
      codePoint == '+'.code &&
      plusIsSpace
    ) {
      // Slow path: the character at i requires encoding!
      val out = Buffer()
      out.writeUtf8(this, pos, i)
      out.writeCanonicalized(
        input = this,
        pos = i,
        limit = limit,
        encodeSet = encodeSet,
        alreadyEncoded = alreadyEncoded,
        strict = strict,
        plusIsSpace = plusIsSpace,
        unicodeAllowed = unicodeAllowed,
        charset = charset,
      )
      return out.readUtf8()
    }
    i += Character.charCount(codePoint)
  }

  // Fast path: no characters in [pos..limit) required encoding.
  return substring(pos, limit)
}

internal fun Buffer.writePercentDecoded(
  encoded: String,
  pos: Int,
  limit: Int,
  plusIsSpace: Boolean,
) {
  var codePoint: Int
  var i = pos
  while (i < limit) {
    codePoint = encoded.codePointAt(i)
    if (codePoint == '%'.code && i + 2 < limit) {
      val d1 = encoded[i + 1].parseHexDigit()
      val d2 = encoded[i + 2].parseHexDigit()
      if (d1 != -1 && d2 != -1) {
        writeByte((d1 shl 4) + d2)
        i += 2
        i += Character.charCount(codePoint)
        continue
      }
    } else if (codePoint == '+'.code && plusIsSpace) {
      writeByte(' '.code)
      i++
      continue
    }
    writeUtf8CodePoint(codePoint)
    i += Character.charCount(codePoint)
  }
}

internal fun String.canonicalize(
  pos: Int = 0,
  limit: Int = length,
  encodeSet: String,
  alreadyEncoded: Boolean = false,
  strict: Boolean = false,
  plusIsSpace: Boolean = false,
  unicodeAllowed: Boolean = false,
): String =
  canonicalizeWithCharset(
    pos = pos,
    limit = limit,
    encodeSet = encodeSet,
    alreadyEncoded = alreadyEncoded,
    strict = strict,
    plusIsSpace = plusIsSpace,
    unicodeAllowed = unicodeAllowed,
  )

internal fun String.percentDecode(
  pos: Int = 0,
  limit: Int = length,
  plusIsSpace: Boolean = false,
): String {
  for (i in pos until limit) {
    val c = this[i]
    if (c == '%' || c == '+' && plusIsSpace) {
      // Slow path: the character at i requires decoding!
      val out = Buffer()
      out.writeUtf8(this, pos, i)
      out.writePercentDecoded(this, pos = i, limit = limit, plusIsSpace = plusIsSpace)
      return out.readUtf8()
    }
  }

  // Fast path: no characters in [pos..limit) required decoding.
  return substring(pos, limit)
}

internal fun String.isPercentEncoded(
  pos: Int,
  limit: Int,
): Boolean =
  pos + 2 < limit &&
    this[pos] == '%' &&
    this[pos + 1].parseHexDigit() != -1 &&
    this[pos + 2].parseHexDigit() != -1
