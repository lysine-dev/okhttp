/*
 * Copyright (C) 2026 Square, Inc.
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

import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isInstanceOf
import assertk.assertions.isNotNull
import java.io.EOFException
import java.net.ProtocolException
import kotlin.test.assertFailsWith
import okhttp3.internal.HEADER_LIMIT
import okio.Buffer
import org.junit.jupiter.api.Test

class HeadersReaderTest {
  @Test
  fun readsNormalHeaders() {
    val source =
      Buffer().writeUtf8(
        "Content-Type: text/plain\r\n" +
          "Content-Length: 0\r\n" +
          "\r\n",
      )
    val headers = HeadersReader(source).readHeaders()
    assertThat(headers.size).isEqualTo(2)
    assertThat(headers["Content-Type"]).isEqualTo("text/plain")
    assertThat(headers["Content-Length"]).isEqualTo("0")
  }

  /** A header line larger than the limit reports the limit clearly instead of a raw okio dump. */
  @Test
  fun headerLineExceedingLimitReportsLimit() {
    val source = Buffer().writeUtf8("a".repeat((HEADER_LIMIT + 1).toInt()))
    val exception =
      assertFailsWith<ProtocolException> {
        HeadersReader(source).readHeaders()
      }
    assertThat(exception.message).isEqualTo("response headers exceed the ${HEADER_LIMIT / 1024} KiB limit")
    assertThat(exception.cause).isNotNull().isInstanceOf(EOFException::class.java)
  }

  /**
   * A response that is truncated before the limit is genuine end-of-stream, not an oversized
   * header, so it must still surface as an [EOFException] rather than the limit message.
   */
  @Test
  fun truncatedHeadersBeforeLimitStillThrowEof() {
    val source = Buffer().writeUtf8("Content-Type: text/pl")
    assertFailsWith<EOFException> {
      HeadersReader(source).readHeaders()
    }
  }
}
