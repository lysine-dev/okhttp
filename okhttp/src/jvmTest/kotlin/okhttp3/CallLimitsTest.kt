/*
 * Copyright (c) 2026 OkHttp Authors
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

import app.cash.burst.Burst
import app.cash.burst.burstValues
import java.io.IOException
import java.util.Random
import kotlin.test.assertFailsWith
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.junit5.StartStop
import okhttp3.testing.PlatformRule
import okio.ByteString.Companion.toByteString
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.RegisterExtension

@Burst
class CallLimitsTest(
  private val protocol: Protocol = burstValues(Protocol.H2_PRIOR_KNOWLEDGE, Protocol.HTTP_1_1),
) {
  @RegisterExtension
  val platform = PlatformRule()

  @RegisterExtension
  val clientTestRule = OkHttpClientTestRule()

  @StartStop
  private val server =
    MockWebServer().apply {
      protocols = listOf(protocol)
    }

  private var client =
    clientTestRule
      .newClientBuilder()
      .protocols(listOf(protocol))
      .build()

  @Test
  fun largeStatusLine() {
    assumeTrue(protocol == Protocol.HTTP_1_1)

    server.enqueue(
      MockResponse
        .Builder()
        .status("HTTP/1.1 200 ${"O".repeat(256 * 1024)}K")
        .body("I'm not even supposed to be here today.")
        .build(),
    )
    val call = client.newCall(Request(url = server.url("/")))
    assertFailsWith<IOException> {
      call.execute()
    }
  }

  /** Use a header that exceeds the limits on its own. */
  @Test
  fun largeResponseHeader() {
    server.enqueue(
      MockResponse
        .Builder()
        .addHeader("Set-Cookie", "a=${"A".repeat(256 * 1024)}")
        .body("I'm not even supposed to be here today.")
        .build(),
    )
    val call = client.newCall(Request(url = server.url("/")))
    assertFailsWith<IOException> {
      call.execute()
    }
  }

  /** Use a header that is large even when it is compressed. */
  @Test
  fun largeCompressedResponseHeader() {
    server.enqueue(
      MockResponse
        .Builder()
        .addHeader("Set-Cookie", "a=${randomString(256 * 1024)}")
        .body("I'm not even supposed to be here today.")
        .build(),
    )
    val call = client.newCall(Request(url = server.url("/")))
    assertFailsWith<IOException> {
      call.execute()
    }
  }

  /** A collection of headers that collectively exceed the limits. */
  @Test
  fun largeResponseHeadersList() {
    server.enqueue(
      MockResponse
        .Builder()
        .addHeader("Set-Cookie", "a=${"A".repeat(255 * 1024)}")
        .addHeader("Set-Cookie", "b=${"B".repeat(1 * 1024)}")
        .body("I'm not even supposed to be here today.")
        .build(),
    )
    val call = client.newCall(Request(url = server.url("/")))
    assertFailsWith<IOException> {
      call.execute()
    }
  }

  private fun randomString(length: Int): String {
    val byteArray = ByteArray(length)
    Random(0).nextBytes(byteArray)
    return byteArray.toByteString().base64()
  }
}
