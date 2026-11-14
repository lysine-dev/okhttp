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

/**
 * The first two bytes of each value is a header that includes its tag (field ID) and length.
 */
internal data class DerHeader(
  /**
   * Namespace of the tag.
   *
   * This value is encoded in bits 7 and 8 of the first byte of each value.
   *
   * ```
   * 0b00xxxxxx Universal
   * 0b01xxxxxx Application
   * 0b10xxxxxx Context-Specific
   * 0b11xxxxxx Private
   * ```
   */
  var tagClass: Int,
  /** Identifies which member in the ASN.1 schema the field holds. */
  var tag: Long,
  /**
   * If the constructed bit is set it indicates that the value is composed of other values that have
   * their own headers.
   *
   * This value is encoded in bit 6 of the first byte of each value.
   *
   * ```
   * 0bxx0xxxxx Primitive
   * 0bxx1xxxxx Constructed
   * ```
   */
  var constructed: Boolean,
  /** Length of the message in bytes, or -1L if its length is unknown at the time of encoding. */
  var length: Long,
) {
  val isEndOfData: Boolean
    get() = tagClass == TAG_CLASS_UNIVERSAL && tag == TAG_END_OF_CONTENTS

  override fun toString(): String = "$tagClass/$tag"

  companion object {
    const val TAG_CLASS_UNIVERSAL = 0b0000_0000
    const val TAG_CLASS_APPLICATION = 0b0100_0000
    const val TAG_CLASS_CONTEXT_SPECIFIC = 0b1000_0000
    const val TAG_CLASS_PRIVATE = 0b1100_0000

    const val TAG_END_OF_CONTENTS = 0L
  }
}
