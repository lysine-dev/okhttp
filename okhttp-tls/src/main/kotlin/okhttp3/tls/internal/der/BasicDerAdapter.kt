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

import java.net.ProtocolException

/**
 * Handles basic types that always use the same tag. This supports optional types and may set a type
 * hint for further adapters to process.
 *
 * Types like ANY and CHOICE that don't have a consistent tag cannot use this.
 */
internal data class BasicDerAdapter<T>(
  private val name: String,
  /** The tag class this adapter expects, or -1 to match any tag class. */
  val tagClass: Int,
  /** The tag this adapter expects, or -1 to match any tag. */
  val tag: Long,
  /** Encode and decode the value once tags are handled. */
  private val codec: Codec<T>,
  /** True if the default value should be used if this value is absent during decoding. */
  val isOptional: Boolean = false,
  /** The value to return if this value is absent. Undefined unless this is optional. */
  val defaultValue: T? = null,
  /** True to set the encoded or decoded value as the type hint for the current SEQUENCE. */
  private val typeHint: Boolean = false,
) : DerAdapter<T> {
  init {
    require(tagClass >= 0)
    require(tag >= 0)
  }

  override fun matches(header: DerHeader): Boolean = header.tagClass == tagClass && header.tag == tag

  override fun fromDer(reader: DerReader): T {
    val peekedHeader = reader.peekHeader()
    if (peekedHeader == null || peekedHeader.tagClass != tagClass || peekedHeader.tag != tag) {
      if (isOptional) return defaultValue as T
      throw ProtocolException("expected $this but was $peekedHeader at $reader")
    }

    val result =
      reader.read(name) {
        codec.decode(reader)
      }

    if (typeHint) {
      reader.typeHint = result
    }

    return result
  }

  override fun toDer(
    writer: DerWriter,
    value: T,
  ) {
    if (typeHint) {
      writer.typeHint = value
    }

    if (isOptional && value == defaultValue) {
      // Nothing to write!
      return
    }

    writer.write(name, tagClass, tag) {
      codec.encode(writer, value)
    }
  }

  /**
   * Returns a copy with a context tag. This should be used when the type is ambiguous on its own.
   * For example, the tags in this schema are 0 and 1:
   *
   * ```
   * Point ::= SEQUENCE {
   *   x [0] INTEGER OPTIONAL,
   *   y [1] INTEGER OPTIONAL
   * }
   * ```
   *
   * You may also specify a tag class like [DerHeader.TAG_CLASS_APPLICATION]. The default tag class
   * is [DerHeader.TAG_CLASS_CONTEXT_SPECIFIC].
   *
   * ```
   * Point ::= SEQUENCE {
   *   x [APPLICATION 0] INTEGER OPTIONAL,
   *   y [APPLICATION 1] INTEGER OPTIONAL
   * }
   * ```
   */
  fun withTag(
    tagClass: Int = DerHeader.TAG_CLASS_CONTEXT_SPECIFIC,
    tag: Long,
  ): BasicDerAdapter<T> = copy(tagClass = tagClass, tag = tag)

  /** Returns a copy of this adapter that doesn't encode values equal to [defaultValue]. */
  fun optional(defaultValue: T? = null): BasicDerAdapter<T> = copy(isOptional = true, defaultValue = defaultValue)

  /**
   * Returns a copy of this adapter that sets the encoded or decoded value as the type hint for the
   * other adapters on this SEQUENCE to interrogate.
   */
  fun asTypeHint(): BasicDerAdapter<T> = copy(typeHint = true)

  override fun toString(): String = "$name [$tagClass/$tag]"

  /** Reads and writes values without knowledge of the enclosing tag, length, or defaults. */
  interface Codec<T> {
    fun decode(reader: DerReader): T

    fun encode(
      writer: DerWriter,
      value: T,
    )
  }
}
