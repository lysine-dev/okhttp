/*
 * Copyright (C) 2015 Square, Inc.
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

package okhttp3.java.net.cookiejar

import java.io.IOException
import java.net.CookieHandler
import java.net.HttpCookie
import java.util.Collections
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl
import okhttp3.internal.cookieToString
import okhttp3.internal.delimiterOffset
import okhttp3.internal.platform.Platform
import okhttp3.internal.platform.Platform.Companion.WARN
import okhttp3.internal.trimSubstring

/** A cookie jar that delegates to a [java.net.CookieHandler]. */
class JavaNetCookieJar(
  private val cookieHandler: CookieHandler,
) : CookieJar {
  override fun saveFromResponse(
    url: HttpUrl,
    cookies: List<Cookie>,
  ) {
    val cookieStrings = mutableListOf<String>()
    for (cookie in cookies) {
      cookieStrings.add(cookieToString(cookie, true))
    }
    val multimap = mapOf("Set-Cookie" to cookieStrings)
    try {
      cookieHandler.put(url.toUri(), multimap)
    } catch (e: IOException) {
      Platform.get().log("Saving cookies failed for " + url.resolve("/...")!!, WARN, e)
    }
  }

  override fun loadForRequest(url: HttpUrl): List<Cookie> {
    val cookieHeaders =
      try {
        // The RI passes all headers. We don't have 'em, so we don't pass 'em!
        cookieHandler.get(url.toUri(), emptyMap<String, List<String>>())
      } catch (e: IOException) {
        Platform.get().log("Loading cookies failed for " + url.resolve("/...")!!, WARN, e)
        return emptyList()
      }

    var cookies: MutableList<Cookie>? = null
    for ((key, value) in cookieHeaders) {
      if (("Cookie".equals(key, ignoreCase = true) || "Cookie2".equals(key, ignoreCase = true)) &&
        value.isNotEmpty()
      ) {
        for (header in value) {
          if (cookies == null) cookies = mutableListOf()
          cookies.addAll(decodeHeaderAsJavaNetCookies(url, header))
        }
      }
    }

    return if (cookies != null) {
      Collections.unmodifiableList(cookies)
    } else {
      emptyList()
    }
  }

  /**
   * Convert a request header to OkHttp's cookies via [HttpCookie]. That extra step handles
   * multiple cookies in a single request header, which [Cookie.parse] doesn't support.
   */
  private fun decodeHeaderAsJavaNetCookies(
    url: HttpUrl,
    header: String,
  ): List<Cookie> {
    val result = mutableListOf<Cookie>()
    var pos = 0
    val limit = header.length
    var pairEnd: Int
    while (pos < limit) {
      pairEnd = header.delimiterOffset(";,", pos, limit)
      val equalsSign = header.delimiterOffset('=', pos, pairEnd)
      val name = header.trimSubstring(pos, equalsSign)
      if (name.startsWith("$")) {
        pos = pairEnd + 1
        continue
      }

      // We have either name=value or just a name.
      var value =
        if (equalsSign < pairEnd) {
          header.trimSubstring(equalsSign + 1, pairEnd)
        } else {
          ""
        }

      // If the value is "quoted", drop the quotes.
      if (value.startsWith("\"") && value.endsWith("\"") && value.length >= 2) {
        value = value.substring(1, value.length - 1)
      }

      // Minimal normalisation so Cookie.Builder doesn't crash on values like "abc123 ".
      value = value.trim()

      result.add(
        Cookie
          .Builder()
          .name(name)
          .value(value)
          .domain(url.host)
          .build(),
      )
      pos = pairEnd + 1
    }
    return result
  }
}
