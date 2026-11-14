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
@file:Suppress("ktlint:standard:filename")

package okhttp3.internal

import okhttp3.Headers

/** This is the same as Chrome's limit. */
internal const val HEADER_LIMIT = 256 * 1024L

internal fun Headers.commonName(index: Int): String = namesAndValues.getOrNull(index * 2) ?: throw IndexOutOfBoundsException("name[$index]")

internal fun Headers.commonValue(index: Int): String =
  namesAndValues.getOrNull(index * 2 + 1) ?: throw IndexOutOfBoundsException("value[$index]")

internal fun Headers.commonValues(name: String): List<String> {
  var result: MutableList<String>? = null
  for (i in 0 until size) {
    if (name.equals(name(i), ignoreCase = true)) {
      if (result == null) result = ArrayList(2)
      result.add(value(i))
    }
  }
  return result?.unmodifiable().orEmpty()
}

internal fun Headers.commonIterator(): Iterator<Pair<String, String>> = Array(size) { name(it) to value(it) }.iterator()

internal fun Headers.commonNewBuilder(): Headers.Builder {
  val result = Headers.Builder()
  result.namesAndValues += namesAndValues
  return result
}

internal fun Headers.commonEquals(other: Any?): Boolean = other is Headers && namesAndValues.contentEquals(other.namesAndValues)

internal fun Headers.commonHashCode(): Int = namesAndValues.contentHashCode()

internal fun Headers.commonToString(): String =
  buildString {
    for (i in 0 until size) {
      val name = name(i)
      val value = value(i)
      append(name)
      append(": ")
      append(if (isSensitiveHeader(name)) "██" else value)
      append("\n")
    }
  }

internal fun commonHeadersGet(
  namesAndValues: Array<String>,
  name: String,
): String? {
  for (i in namesAndValues.size - 2 downTo 0 step 2) {
    if (name.equals(namesAndValues[i], ignoreCase = true)) {
      return namesAndValues[i + 1]
    }
  }
  return null
}

internal fun Headers.Builder.commonAdd(
  name: String,
  value: String,
) = apply {
  headersCheckName(name)
  headersCheckValue(value, name)
  commonAddLenient(name, value)
}

internal fun Headers.Builder.commonAddAll(headers: Headers) =
  apply {
    for (i in 0 until headers.size) {
      commonAddLenient(headers.name(i), headers.value(i))
    }
  }

internal fun Headers.Builder.commonAddLenient(
  name: String,
  value: String,
) = apply {
  namesAndValues.add(name)
  namesAndValues.add(value.trim())
}

internal fun Headers.Builder.commonRemoveAll(name: String) =
  apply {
    var i = 0
    while (i < namesAndValues.size) {
      if (name.equals(namesAndValues[i], ignoreCase = true)) {
        namesAndValues.removeAt(i) // name
        namesAndValues.removeAt(i) // value
        i -= 2
      }
      i += 2
    }
  }

/**
 * Set a field with the specified value. If the field is not found, it is added. If the field is
 * found, the existing values are replaced.
 */
internal fun Headers.Builder.commonSet(
  name: String,
  value: String,
) = apply {
  headersCheckName(name)
  headersCheckValue(value, name)
  removeAll(name)
  commonAddLenient(name, value)
}

/** Equivalent to `build().get(name)`, but potentially faster. */
internal fun Headers.Builder.commonGet(name: String): String? {
  for (i in namesAndValues.size - 2 downTo 0 step 2) {
    if (name.equals(namesAndValues[i], ignoreCase = true)) {
      return namesAndValues[i + 1]
    }
  }
  return null
}

internal fun Headers.Builder.commonBuild(): Headers = Headers(namesAndValues.toTypedArray())

internal fun headersCheckName(name: String) {
  require(name.isNotEmpty()) { "name is empty" }
  for (i in name.indices) {
    val c = name[i]
    require(c in '\u0021'..'\u007e') {
      "Unexpected char 0x${c.charCode()} at $i in header name: $name"
    }
  }
}

internal fun headersCheckValue(
  value: String,
  name: String,
) {
  for (i in value.indices) {
    val c = value[i]
    require(c == '\t' || c in '\u0020'..'\u007e') {
      "Unexpected char 0x${c.charCode()} at $i in $name value" +
        (if (isSensitiveHeader(name)) "" else ": $value")
    }
  }
}

private fun Char.charCode() =
  code.toString(16).let {
    if (it.length < 2) {
      "0$it"
    } else {
      it
    }
  }

internal fun commonHeadersOf(vararg inputNamesAndValues: String): Headers {
  require(inputNamesAndValues.size % 2 == 0) { "Expected alternating header names and values" }

  // Make a defensive copy and clean it up.
  val namesAndValues: Array<String> = arrayOf(*inputNamesAndValues)
  for (i in namesAndValues.indices) {
    @Suppress("SENSELESS_COMPARISON")
    require(namesAndValues[i] != null) { "Headers cannot be null" }
    namesAndValues[i] = inputNamesAndValues[i].trim()
  }

  // Check for malformed headers.
  for (i in namesAndValues.indices step 2) {
    val name = namesAndValues[i]
    val value = namesAndValues[i + 1]
    headersCheckName(name)
    headersCheckValue(value, name)
  }

  return Headers(namesAndValues)
}

internal fun Map<String, String>.commonToHeaders(): Headers {
  // Make a defensive copy and clean it up.
  val namesAndValues = arrayOfNulls<String>(size * 2)
  var i = 0
  for ((k, v) in this) {
    val name = k.trim()
    val value = v.trim()
    headersCheckName(name)
    headersCheckValue(value, name)
    namesAndValues[i] = name
    namesAndValues[i + 1] = value
    i += 2
  }

  @Suppress("UNCHECKED_CAST")
  return Headers(namesAndValues as Array<String>)
}
