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

import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isNotEqualTo
import assertk.assertions.isNotIn
import java.net.InetAddress
import kotlin.test.Test
import kotlin.test.assertFailsWith
import okio.ByteString.Companion.encodeUtf8

class DnsValuesTest {
  @Test
  fun `request canonical hostname`() {
    assertThat(Dns.Request(hostname = "EXAMPLE.COM").hostname)
      .isEqualTo("example.com")
  }

  @Test
  fun `request invalid hostname`() {
    assertFailsWith<IllegalArgumentException> {
      Dns.Request(hostname = "")
    }
    assertFailsWith<IllegalArgumentException> {
      Dns.Request(hostname = "a".repeat(64))
    }
    assertFailsWith<IllegalArgumentException> {
      Dns.Request(hostname = ("${"a".repeat(55)}.".repeat(5)))
    }
    assertFailsWith<IllegalArgumentException> {
      Dns.Request(hostname = "a?b")
    }
  }

  @Test
  fun `request invalid port`() {
    assertFailsWith<IllegalArgumentException> {
      Dns.Request(hostname = "example.com", port = -2)
    }
    assertFailsWith<IllegalArgumentException> {
      Dns.Request(hostname = "example.com", port = 0)
    }
    assertFailsWith<IllegalArgumentException> {
      Dns.Request(hostname = "example.com", port = 65536)
    }
  }

  @Test
  fun `default port`() {
    assertThat(Dns.Request(hostname = "example.com", port = -1).port)
      .isEqualTo(443)
  }

  @Test
  fun `request equals and hashCode`() {
    assertThat(Dns.Request(hostname = "example.com", port = 443))
      .isEqualTo(Dns.Request(hostname = "example.com", port = 443))
    assertThat(Dns.Request(hostname = "example.com", port = 443).hashCode())
      .isEqualTo(Dns.Request(hostname = "example.com", port = 443).hashCode())
    assertThat(Dns.Request(hostname = "example.com", port = 443))
      .isNotEqualTo(Dns.Request(hostname = "example.com", port = 8443))
    assertThat(Dns.Request(hostname = "example.com", port = 443))
      .isNotEqualTo(Dns.Request(hostname = "example.net", port = 443))
  }

  @Test
  fun `request to string`() {
    assertThat(Dns.Request(hostname = "example.com", port = 443).toString())
      .isEqualTo("example.com")
    assertThat(Dns.Request(hostname = "example.com", port = 8443).toString())
      .isEqualTo("example.com:8443")
  }

  @Test
  fun `ip address record invalid hostname`() {
    val address = InetAddress.getByAddress(byteArrayOf(127, 0, 0, 1))
    assertFailsWith<IllegalArgumentException> {
      Dns.Record.IpAddress(hostname = "", address = address)
    }
    assertFailsWith<IllegalArgumentException> {
      Dns.Record.IpAddress(hostname = "a".repeat(64), address = address)
    }
    assertFailsWith<IllegalArgumentException> {
      Dns.Record.IpAddress(hostname = ("${"a".repeat(55)}.".repeat(5)), address = address)
    }
    assertFailsWith<IllegalArgumentException> {
      Dns.Record.IpAddress(hostname = "a?b", address = address)
    }
  }

  @Test
  fun `ip address equals and hashCode`() {
    val address1 = InetAddress.getByAddress(byteArrayOf(127, 0, 0, 1))
    val address2 = InetAddress.getByAddress(byteArrayOf(127, 0, 0, 2))
    assertThat(Dns.Record.IpAddress(hostname = "example.com", address = address1))
      .isEqualTo(Dns.Record.IpAddress(hostname = "example.com", address = address1))
    assertThat(Dns.Record.IpAddress(hostname = "example.com", address = address1).hashCode())
      .isEqualTo(Dns.Record.IpAddress(hostname = "example.com", address = address1).hashCode())
    assertThat(Dns.Record.IpAddress(hostname = "example.com", address = address1))
      .isNotEqualTo(Dns.Record.IpAddress(hostname = "example.com", address = address2))
    assertThat(Dns.Record.IpAddress(hostname = "example.com", address = address1).hashCode())
      .isNotEqualTo(Dns.Record.IpAddress(hostname = "example.com", address = address2).hashCode())
    assertThat(Dns.Record.IpAddress(hostname = "example.com", address = address1))
      .isNotEqualTo(Dns.Record.IpAddress(hostname = "example.net", address = address1))
    assertThat(Dns.Record.IpAddress(hostname = "example.com", address = address1).hashCode())
      .isNotEqualTo(Dns.Record.IpAddress(hostname = "example.net", address = address1).hashCode())
  }

  @Test
  fun `ip address toString`() {
    val address = InetAddress.getByAddress(byteArrayOf(127, 0, 0, 1))
    assertThat(Dns.Record.IpAddress(hostname = "example.com", address = address).toString())
      .isEqualTo("example.com/127.0.0.1")
  }

  @Test
  fun `service metadata invalid hostname`() {
    assertFailsWith<IllegalArgumentException> {
      Dns.Record.ServiceMetadata(hostname = "")
    }
    assertFailsWith<IllegalArgumentException> {
      Dns.Record.ServiceMetadata(hostname = "a".repeat(64))
    }
    assertFailsWith<IllegalArgumentException> {
      Dns.Record.ServiceMetadata(hostname = ("${"a".repeat(55)}.".repeat(5)))
    }
    assertFailsWith<IllegalArgumentException> {
      Dns.Record.ServiceMetadata(hostname = "a?b")
    }
  }

  @Test
  fun `service metadata invalid port`() {
    assertFailsWith<IllegalArgumentException> {
      Dns.Record.ServiceMetadata(hostname = "example.com", port = -2)
    }
    assertFailsWith<IllegalArgumentException> {
      Dns.Record.ServiceMetadata(hostname = "example.com", port = 0)
    }
    assertFailsWith<IllegalArgumentException> {
      Dns.Record.ServiceMetadata(hostname = "example.com", port = 65536)
    }
  }

  @Test
  fun `service metadata equals and hashCode`() {
    val original =
      Dns.Record.ServiceMetadata(
        hostname = "example.com",
        port = 443,
        alpnIds = listOf(Protocol.HTTP_1_1, Protocol.HTTP_2),
        ipAddressHints = listOf(InetAddress.getByAddress(byteArrayOf(127, 0, 0, 1))),
        echConfigList = "hello I am ECH config".encodeUtf8(),
      )
    assertThat(original).isEqualTo(
      Dns.Record.ServiceMetadata(
        hostname = "example.com",
        port = 443,
        alpnIds = listOf(Protocol.HTTP_1_1, Protocol.HTTP_2),
        ipAddressHints = listOf(InetAddress.getByAddress(byteArrayOf(127, 0, 0, 1))),
        echConfigList = "hello I am ECH config".encodeUtf8(),
      ),
    )
    assertThat(original.hashCode()).isEqualTo(
      Dns.Record
        .ServiceMetadata(
          hostname = "example.com",
          port = 443,
          alpnIds = listOf(Protocol.HTTP_1_1, Protocol.HTTP_2),
          ipAddressHints = listOf(InetAddress.getByAddress(byteArrayOf(127, 0, 0, 1))),
          echConfigList = "hello I am ECH config".encodeUtf8(),
        ).hashCode(),
    )
    assertThat(original).isNotIn(
      Dns.Record.ServiceMetadata(
        hostname = "example.net",
        port = 443,
        alpnIds = listOf(Protocol.HTTP_1_1, Protocol.HTTP_2),
        ipAddressHints = listOf(InetAddress.getByAddress(byteArrayOf(127, 0, 0, 1))),
        echConfigList = "hello I am ECH config".encodeUtf8(),
      ),
      Dns.Record.ServiceMetadata(
        hostname = "example.com",
        port = 8443,
        alpnIds = listOf(Protocol.HTTP_1_1, Protocol.HTTP_2),
        ipAddressHints = listOf(InetAddress.getByAddress(byteArrayOf(127, 0, 0, 1))),
        echConfigList = "hello I am ECH config".encodeUtf8(),
      ),
      Dns.Record.ServiceMetadata(
        hostname = "example.com",
        port = 443,
        alpnIds = listOf(Protocol.HTTP_1_1),
        ipAddressHints = listOf(InetAddress.getByAddress(byteArrayOf(127, 0, 0, 1))),
        echConfigList = "hello I am ECH config".encodeUtf8(),
      ),
      Dns.Record.ServiceMetadata(
        hostname = "example.com",
        port = 443,
        alpnIds = listOf(Protocol.HTTP_1_1, Protocol.HTTP_2),
        ipAddressHints = listOf(InetAddress.getByAddress(byteArrayOf(127, 0, 0, 2))),
        echConfigList = "hello I am ECH config".encodeUtf8(),
      ),
      Dns.Record.ServiceMetadata(
        hostname = "example.com",
        port = 443,
        alpnIds = listOf(Protocol.HTTP_1_1, Protocol.HTTP_2),
        ipAddressHints = listOf(InetAddress.getByAddress(byteArrayOf(127, 0, 0, 1))),
        echConfigList = "hello I am also ECH config".encodeUtf8(),
      ),
    )
  }

  @Test
  fun `service metadata toString`() {
    val empty =
      Dns.Record.ServiceMetadata(
        hostname = "example.com",
      )
    assertThat(empty.toString()).isEqualTo(
      "ServiceMetadata{example.com}",
    )
    val full =
      Dns.Record.ServiceMetadata(
        hostname = "example.com",
        port = 443,
        alpnIds = listOf(Protocol.HTTP_1_1, Protocol.HTTP_2),
        ipAddressHints = listOf(InetAddress.getByAddress(byteArrayOf(127, 0, 0, 1))),
        echConfigList = "hello I am ECH config".encodeUtf8(),
      )
    assertThat(full.toString()).isEqualTo(
      "ServiceMetadata{example.com, alpnIds=[http/1.1, h2], ipAddressHints=[127.0.0.1], " +
        "echConfigList=68656c6c6f204920616d2045434820636f6e666967}",
    )
  }
}
