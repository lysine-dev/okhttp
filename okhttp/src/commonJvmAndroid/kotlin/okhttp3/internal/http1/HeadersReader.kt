/*
 * Copyright (C) 2012 The Android Open Source Project
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
package okhttp3.internal.http1

import java.io.EOFException
import java.net.ProtocolException
import okhttp3.Headers
import okhttp3.internal.HEADER_LIMIT
import okio.BufferedSource

/**
 * Parse all headers delimited by "\r\n" until an empty line. This throws if headers exceed 256 KiB.
 */
class HeadersReader(
  val source: BufferedSource,
) {
  private var headerLimit = HEADER_LIMIT

  /** Read a single line counted against the header size limit. */
  fun readLine(): String {
    val line =
      try {
        source.readUtf8LineStrict(headerLimit)
      } catch (e: EOFException) {
        // readUtf8LineStrict() throws EOFException both when the line exceeds the byte limit
        // and when the stream ends before a line terminator. Only the former means the response
        // head is too large; a genuinely truncated response must still surface as an EOFException.
        if (source.buffer.size >= headerLimit) {
          throw ProtocolException(
            "response headers exceed the ${HEADER_LIMIT / 1024} KiB limit",
          ).initCause(e)
        }
        throw e
      }
    headerLimit -= line.length.toLong()
    return line
  }

  /** Reads headers or trailers. */
  fun readHeaders(): Headers {
    val result = Headers.Builder()
    while (true) {
      val line = readLine()
      if (line.isEmpty()) break
      result.addLenient(line)
    }
    return result.build()
  }
}
