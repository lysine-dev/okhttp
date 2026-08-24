/*
 * Copyright (C) 2013 Square, Inc.
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
package okhttp3

import java.nio.charset.Charset

/**
 * An [RFC 2045][rfc_2045] Media Type, appropriate to describe the content type of an HTTP request
 * or response body.
 *
 * [rfc_2045]: http://tools.ietf.org/html/rfc2045
 */
class MediaType internal constructor(
  internal val mediaType: String,
  /**
   * Returns the high-level media type, such as "text", "image", "audio", "video", or "application".
   */
  @get:JvmName("type") val type: String,
  /**
   * Returns a specific media subtype, such as "plain" or "png", "mpeg", "mp4" or "xml".
   */
  @get:JvmName("subtype") val subtype: String,
  /** Alternating parameter names with their values, like `["charset", "utf-8"]`. */
  private val parameterNamesAndValues: Array<String>,
) {
  /**
   * Returns the charset of this media type, or [defaultValue] if either this media type doesn't
   * specify a charset, or if its charset is unsupported by the current runtime.
   */
  @JvmOverloads
  fun charset(defaultValue: Charset? = null): Charset? {
    val charset = parameter("charset") ?: return defaultValue
    return try {
      Charset.forName(charset)
    } catch (_: IllegalArgumentException) {
      defaultValue // This charset is invalid or unsupported. Give up.
    }
  }

  /**
   * Returns the parameter [name] of this media type, or null if this media type does not define
   * such a parameter.
   */
  fun parameter(name: String): String? {
    for (i in parameterNamesAndValues.indices step 2) {
      if (parameterNamesAndValues[i].equals(name, ignoreCase = true)) {
        return parameterNamesAndValues[i + 1]
      }
    }
    return null
  }

  @JvmName("-deprecated_type")
  @Deprecated(
    message = "moved to val",
    replaceWith = ReplaceWith(expression = "type"),
    level = DeprecationLevel.ERROR,
  )
  fun type(): String = type

  @JvmName("-deprecated_subtype")
  @Deprecated(
    message = "moved to val",
    replaceWith = ReplaceWith(expression = "subtype"),
    level = DeprecationLevel.ERROR,
  )
  fun subtype(): String = subtype

  /**
   * Returns the encoded media type, like "text/plain; charset=utf-8", appropriate for use in a
   * Content-Type header.
   */
  override fun toString(): String = mediaType

  override fun equals(other: Any?): Boolean = other is MediaType && other.mediaType == mediaType

  override fun hashCode(): Int = mediaType.hashCode()

  companion object {
    private const val TOKEN = "([a-zA-Z0-9-!#$%&'*+.^_`{|}~]+)"
    private const val QUOTED = "\"([^\"\\r\\n]*)\""
    private val TYPE_SUBTYPE = Regex("$TOKEN/$TOKEN")
    private val PARAMETER = Regex(";\\s*(?:$TOKEN=(?:$TOKEN|$QUOTED))?")

    /**
     * Returns a media type for this string.
     *
     * @throws IllegalArgumentException if this is not a well-formed media type.
     */
    @JvmStatic
    @JvmName("get")
    fun String.toMediaType(): MediaType {
      val typeSubtype =
        TYPE_SUBTYPE.matchAt(this, 0)
          ?: throw IllegalArgumentException("No subtype found for: \"$this\"")
      val type = typeSubtype.groupValues[1].lowercase()
      val subtype = typeSubtype.groupValues[2].lowercase()

      val parameterNamesAndValues = mutableListOf<String>()
      var s = typeSubtype.range.last + 1
      while (s < length) {
        val parameter = PARAMETER.matchAt(this, s)
        require(parameter != null) {
          "Parameter is not formatted correctly: \"${substring(s)}\" for: \"$this\""
        }

        val name = parameter.groups[1]?.value
        if (name == null) {
          s = parameter.range.last + 1
          continue
        }

        val token = parameter.groups[2]?.value
        val value =
          when {
            token == null -> {
              // Value is "double-quoted". That's valid and our regex group already strips the quotes.
              parameter.groups[3]!!.value
            }

            token.startsWith('\'') && token.endsWith('\'') && token.length > 2 -> {
              // If the token is 'single-quoted' it's invalid! But we're lenient and strip the quotes.
              token.substring(1, token.length - 1)
            }

            else -> {
              token
            }
          }

        parameterNamesAndValues += name
        parameterNamesAndValues += value
        s = parameter.range.last + 1
      }

      return MediaType(this, type, subtype, parameterNamesAndValues.toTypedArray())
    }

    /** Returns a media type for this, or null if this is not a well-formed media type. */
    @JvmStatic
    @JvmName("parse")
    fun String.toMediaTypeOrNull(): MediaType? =
      try {
        toMediaType()
      } catch (_: IllegalArgumentException) {
        null
      }

    @JvmName("-deprecated_get")
    @Deprecated(
      message = "moved to extension function",
      replaceWith =
        ReplaceWith(
          expression = "mediaType.toMediaType()",
          imports = ["okhttp3.MediaType.Companion.toMediaType"],
        ),
      level = DeprecationLevel.ERROR,
    )
    fun get(mediaType: String): MediaType = mediaType.toMediaType()

    @JvmName("-deprecated_parse")
    @Deprecated(
      message = "moved to extension function",
      replaceWith =
        ReplaceWith(
          expression = "mediaType.toMediaTypeOrNull()",
          imports = ["okhttp3.MediaType.Companion.toMediaTypeOrNull"],
        ),
      level = DeprecationLevel.ERROR,
    )
    fun parse(mediaType: String): MediaType? = mediaType.toMediaTypeOrNull()
  }
}
