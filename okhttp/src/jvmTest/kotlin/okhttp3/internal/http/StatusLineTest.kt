/*
 * Copyright (C) 2012 Square, Inc.
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
package okhttp3.internal.http

import assertk.assertThat
import assertk.assertions.isEqualTo
import java.net.ProtocolException
import kotlin.test.assertFailsWith
import okhttp3.Protocol
import okhttp3.internal.http.StatusLine.Companion.parse
import org.junit.jupiter.api.Test

class StatusLineTest {
  @Test
  fun parse() {
    val message = "Temporary Redirect"
    val version = 1
    val code = 200
    val statusLine = parse("HTTP/1.$version $code $message")
    assertThat(statusLine.message).isEqualTo(message)
    assertThat(statusLine.protocol).isEqualTo(Protocol.HTTP_1_1)
    assertThat(statusLine.code).isEqualTo(code)
  }

  @Test
  fun emptyMessage() {
    val version = 1
    val code = 503
    val statusLine = parse("HTTP/1.$version $code ")
    assertThat(statusLine.message).isEqualTo("")
    assertThat(statusLine.protocol).isEqualTo(Protocol.HTTP_1_1)
    assertThat(statusLine.code).isEqualTo(code)
  }

  /**
   * This is not defined in the protocol but some servers won't add the leading empty space when the
   * message is empty. http://www.w3.org/Protocols/rfc2616/rfc2616-sec6.html#sec6.1
   */
  @Test
  fun emptyMessageAndNoLeadingSpace() {
    val version = 1
    val code = 503
    val statusLine = parse("HTTP/1.$version $code")
    assertThat(statusLine.message).isEqualTo("")
    assertThat(statusLine.protocol).isEqualTo(Protocol.HTTP_1_1)
    assertThat(statusLine.code).isEqualTo(code)
  }

  // https://github.com/lysine-dev/okhttp/issues/386
  @Test
  fun shoutcast() {
    val statusLine = parse("ICY 200 OK")
    assertThat(statusLine.message).isEqualTo("OK")
    assertThat(statusLine.protocol).isEqualTo(Protocol.HTTP_1_0)
    assertThat(statusLine.code).isEqualTo(200)
  }

  @Test
  fun missingProtocol() {
    assertInvalid("")
    assertInvalid(" ")
    assertInvalid("200 OK")
    assertInvalid(" 200 OK")
  }

  @Test
  fun protocolVersions() {
    assertInvalid("HTTP/2.0 200 OK")
    assertInvalid("HTTP/2.1 200 OK")
    assertInvalid("HTTP/-.1 200 OK")
    assertInvalid("HTTP/1.- 200 OK")
    assertInvalid("HTTP/0.1 200 OK")
    assertInvalid("HTTP/101 200 OK")
    assertInvalid("HTTP/1.1_200 OK")
  }

  @Test
  fun nonThreeDigitCode() {
    assertInvalid("HTTP/1.1  OK")
    assertInvalid("HTTP/1.1 2 OK")
    assertInvalid("HTTP/1.1 20 OK")
    assertInvalid("HTTP/1.1 2000 OK")
    assertInvalid("HTTP/1.1 two OK")
    assertInvalid("HTTP/1.1 2")
    assertInvalid("HTTP/1.1 2000")
    assertInvalid("HTTP/1.1 two")
  }

  @Test
  fun truncated() {
    assertInvalid("")
    assertInvalid("H")
    assertInvalid("HTTP/1")
    assertInvalid("HTTP/1.")
    assertInvalid("HTTP/1.1")
    assertInvalid("HTTP/1.1 ")
    assertInvalid("HTTP/1.1 2")
    assertInvalid("HTTP/1.1 20")
  }

  @Test
  fun wrongMessageDelimiter() {
    assertInvalid("HTTP/1.1 200_")
  }

  private fun assertInvalid(statusLine: String) {
    assertFailsWith<ProtocolException> {
      parse(statusLine)
    }
  }
}
