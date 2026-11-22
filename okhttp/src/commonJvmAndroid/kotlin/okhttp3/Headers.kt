/*
 *  Licensed to the Apache Software Foundation (ASF) under one or more
 *  contributor license agreements.  See the NOTICE file distributed with
 *  this work for additional information regarding copyright ownership.
 *  The ASF licenses this file to You under the Apache License, Version 2.0
 *  (the "License"); you may not use this file except in compliance with
 *  the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */

package okhttp3

import java.time.Instant
import java.util.Date
import java.util.Locale
import java.util.TreeMap
import java.util.TreeSet
import okhttp3.internal.commonAdd
import okhttp3.internal.commonAddAll
import okhttp3.internal.commonAddLenient
import okhttp3.internal.commonBuild
import okhttp3.internal.commonEquals
import okhttp3.internal.commonGet
import okhttp3.internal.commonHashCode
import okhttp3.internal.commonHeadersGet
import okhttp3.internal.commonHeadersOf
import okhttp3.internal.commonIterator
import okhttp3.internal.commonName
import okhttp3.internal.commonNewBuilder
import okhttp3.internal.commonRemoveAll
import okhttp3.internal.commonSet
import okhttp3.internal.commonToHeaders
import okhttp3.internal.commonToString
import okhttp3.internal.commonValue
import okhttp3.internal.commonValues
import okhttp3.internal.headersCheckName
import okhttp3.internal.http.toHttpDateOrNull
import okhttp3.internal.http.toHttpDateString
import okhttp3.internal.unmodifiable
import org.codehaus.mojo.animal_sniffer.IgnoreJRERequirement

/**
 * The header fields of a single HTTP message. Values are uninterpreted strings; use `Request` and
 * `Response` for interpreted headers. This class maintains the order of the header fields within
 * the HTTP message.
 *
 * This class tracks header values line-by-line. A field with multiple comma- separated values on
 * the same line will be treated as a field with a single value by this class. It is the caller's
 * responsibility to detect and split on commas if their field permits multiple values. This
 * simplifies use of single-valued fields whose values routinely contain commas, such as cookies or
 * dates.
 *
 * This class trims whitespace from values. It never returns values with leading or trailing
 * whitespace.
 *
 * Instances of this class are immutable. Use [Builder] to create instances.
 */
@Suppress("NAME_SHADOWING")
class Headers internal constructor(
  internal val namesAndValues: Array<String>,
) : Iterable<Pair<String, String>> {
  /** Returns the last value corresponding to the specified field, or null. */
  operator fun get(name: String): String? = commonHeadersGet(namesAndValues, name)

  /**
   * Returns the last value corresponding to the specified field parsed as an HTTP date, or null if
   * either the field is absent or cannot be parsed as a date.
   */
  fun getDate(name: String): Date? = get(name)?.toHttpDateOrNull()

  /**
   * Returns the last value corresponding to the specified field parsed as an HTTP date, or null if
   * either the field is absent or cannot be parsed as a date.
   */
  @Suppress("NewApi")
  @IgnoreJRERequirement // Only programs that already have Instant will use this.
  fun getInstant(name: String): Instant? = getDate(name)?.toInstant()

  /** Returns the number of field values. */
  @get:JvmName("size")
  val size: Int
    get() = namesAndValues.size / 2

  @JvmName("-deprecated_size")
  @Deprecated(
    message = "moved to val",
    replaceWith = ReplaceWith(expression = "size"),
    level = DeprecationLevel.ERROR,
  )
  fun size(): Int = size

  /** Returns the field at `position`. */
  fun name(index: Int): String = commonName(index)

  /** Returns the value at `index`. */
  fun value(index: Int): String = commonValue(index)

  /** Returns an immutable case-insensitive set of header names. */
  fun names(): Set<String> {
    val result = TreeSet(String.CASE_INSENSITIVE_ORDER)
    for (i in 0 until size) {
      result.add(name(i))
    }
    return result.unmodifiable()
  }

  /** Returns an immutable list of the header values for `name`. */
  fun values(name: String): List<String> = commonValues(name)

  /**
   * Returns the number of bytes required to encode these headers using HTTP/1.1. This is also the
   * approximate size of HTTP/2 headers before they are compressed with HPACK. This value is
   * intended to be used as a metric: smaller headers are more efficient to encode and transmit.
   */
  fun byteCount(): Long {
    // Each header name has 2 bytes of overhead for ': ' and every header value has 2 bytes of
    // overhead for '\r\n'.
    var result = (namesAndValues.size * 2).toLong()

    for (i in 0 until namesAndValues.size) {
      result += namesAndValues[i].length.toLong()
    }

    return result
  }

  override operator fun iterator(): Iterator<Pair<String, String>> = commonIterator()

  fun newBuilder(): Builder = commonNewBuilder()

  /**
   * Returns true if `other` is a `Headers` object with the same headers, with the same casing, in
   * the same order. Note that two headers instances may be *semantically* equal but not equal
   * according to this method. In particular, none of the following sets of headers are equal
   * according to this method:
   *
   * 1. Original
   * ```
   * Content-Type: text/html
   * Content-Length: 50
   * ```
   *
   * 2. Different order
   *
   * ```
   * Content-Length: 50
   * Content-Type: text/html
   * ```
   *
   * 3. Different case
   *
   * ```
   * content-type: text/html
   * content-length: 50
   * ```
   *
   * 4. Different values
   *
   * ```
   * Content-Type: text/html
   * Content-Length: 050
   * ```
   *
   * Applications that require semantically equal headers should convert them into a canonical form
   * before comparing them for equality.
   */
  override fun equals(other: Any?): Boolean = commonEquals(other)

  override fun hashCode(): Int = commonHashCode()

  /**
   * Returns header names and values. The names and values are separated by `: ` and each pair is
   * followed by a newline character `\n`.
   *
   * Since OkHttp 5 this redacts these sensitive headers:
   *
   *  * `Authorization`
   *  * `Cookie`
   *  * `Proxy-Authorization`
   *  * `Set-Cookie`
   */
  override fun toString(): String = commonToString()

  fun toMultimap(): Map<String, List<String>> {
    val result = TreeMap<String, MutableList<String>>(String.CASE_INSENSITIVE_ORDER)
    for (i in 0 until size) {
      val name = name(i).lowercase(Locale.US)
      var values: MutableList<String>? = result[name]
      if (values == null) {
        values = ArrayList(2)
        result[name] = values
      }
      values.add(value(i))
    }
    return result
  }

  class Builder {
    internal val namesAndValues: MutableList<String> = ArrayList(20)

    /**
     * Add a header line without any validation. Only appropriate for headers from the remote peer
     * or cache.
     */
    internal fun addLenient(line: String) =
      apply {
        val index = line.indexOf(':', 1)
        when {
          index != -1 -> {
            addLenient(line.substring(0, index), line.substring(index + 1))
          }

          line[0] == ':' -> {
            // Work around empty header names and header names that start with a colon (created by old
            // broken SPDY versions of the response cache).
            addLenient("", line.substring(1)) // Empty header name.
          }

          else -> {
            // No header name.
            addLenient("", line)
          }
        }
      }

    /** Add an header line containing a field name, a literal colon, and a value. */
    fun add(line: String) =
      apply {
        val index = line.indexOf(':')
        require(index != -1) { "Unexpected header: $line" }
        add(line.substring(0, index).trim(), line.substring(index + 1))
      }

    /**
     * Add a header with the specified name and value. Does validation of header names and values.
     */
    fun add(
      name: String,
      value: String,
    ) = commonAdd(name, value)

    /**
     * Add a header with the specified name and value. Does validation of header names, allowing
     * non-ASCII values.
     */
    fun addUnsafeNonAscii(
      name: String,
      value: String,
    ) = apply {
      headersCheckName(name)
      addLenient(name, value)
    }

    /**
     * Adds all headers from an existing collection.
     */
    fun addAll(headers: Headers) = commonAddAll(headers)

    /**
     * Add a header with the specified name and formatted date. Does validation of header names and
     * value.
     */
    fun add(
      name: String,
      value: Date,
    ) = add(name, value.toHttpDateString())

    /**
     * Add a header with the specified name and formatted instant. Does validation of header names
     * and value.
     */
    @Suppress("NewApi")
    @IgnoreJRERequirement // Only programs that already have Instant will use this.
    fun add(
      name: String,
      value: Instant,
    ) = add(name, Date.from(value))

    /**
     * Set a field with the specified date. If the field is not found, it is added. If the field is
     * found, the existing values are replaced.
     */
    operator fun set(
      name: String,
      value: Date,
    ) = set(name, value.toHttpDateString())

    /**
     * Set a field with the specified instant. If the field is not found, it is added. If the field
     * is found, the existing values are replaced.
     */
    @Suppress("NewApi")
    @IgnoreJRERequirement // Only programs that already have Instant will use this.
    operator fun set(name: String, value: Instant) = set(name, Date.from(value))

    /**
     * Add a field with the specified value without any validation. Only appropriate for headers
     * from the remote peer or cache.
     */
    internal fun addLenient(
      name: String,
      value: String,
    ) = commonAddLenient(name, value)

    fun removeAll(name: String) = commonRemoveAll(name)

    /**
     * Set a field with the specified value. If the field is not found, it is added. If the field is
     * found, the existing values are replaced.
     */
    operator fun set(
      name: String,
      value: String,
    ) = commonSet(name, value)

    /** Equivalent to `build().get(name)`, but potentially faster. */
    operator fun get(name: String): String? = commonGet(name)

    fun build(): Headers = commonBuild()
  }

  companion object {
    /** Empty headers. */
    @JvmField
    val EMPTY = Headers(emptyArray())

    /**
     * Returns headers for the alternating header names and values. There must be an even number of
     * arguments, and they must alternate between header names and values.
     */
    @JvmStatic
    @JvmName("of")
    fun headersOf(vararg namesAndValues: String): Headers = commonHeadersOf(*namesAndValues)

    @JvmName("-deprecated_of")
    @Deprecated(
      message = "function name changed",
      replaceWith = ReplaceWith(expression = "headersOf(*namesAndValues)"),
      level = DeprecationLevel.ERROR,
    )
    fun of(vararg namesAndValues: String): Headers = headersOf(*namesAndValues)

    /** Returns headers for the header names and values in the [Map]. */
    @JvmStatic
    @JvmName("of")
    fun Map<String, String>.toHeaders(): Headers = commonToHeaders()

    @JvmName("-deprecated_of")
    @Deprecated(
      message = "function moved to extension",
      replaceWith = ReplaceWith(expression = "headers.toHeaders()"),
      level = DeprecationLevel.ERROR,
    )
    fun of(headers: Map<String, String>): Headers = headers.toHeaders()
  }
}
