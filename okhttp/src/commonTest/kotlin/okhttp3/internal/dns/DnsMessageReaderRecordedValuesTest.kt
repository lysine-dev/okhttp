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
@file:OptIn(OkHttpInternalApi::class)

package okhttp3.internal.dns

import assertk.assertThat
import assertk.assertions.containsExactly
import assertk.assertions.isEqualTo
import java.net.InetAddress
import kotlin.test.Test
import okhttp3.internal.OkHttpInternalApi
import okio.Buffer
import okio.ByteString.Companion.decodeHex

/**
 * We used Cloudflare’s DNS service to fetch HTTPS records for the top 1M domain names, then parsed
 * them all to find some interesting ones.
 *
 * Those are the subjects of this test.
 *
 * We used the Tranco list generated on 13 July 2026.
 * Available at https://tranco-list.eu/list/74JZX.
 */
class DnsMessageReaderRecordedValuesTest {
  @Test
  fun `resource record with compressed suffix`() {
    val reader =
      DnsMessageReader(
        "00008180000100040000000006676f6f676c6503636f6d00001c0001c00c001c00010000003c00102607f8b0" +
          "402318070000000000000071c00c001c00010000003c00102607f8b0402318070000000000000065c00c00" +
          "1c00010000003c00102607f8b040231807000000000000008ac00c001c00010000003c00102607f8b04023" +
          "1807000000000000008b",
      )
    assertThat(reader.read()).isEqualTo(
      DnsMessage(
        id = 0,
        flags = -32384,
        questions =
          listOf(
            Question(
              name = "google.com",
              type = TYPE_AAAA,
            ),
          ),
        answers =
          listOf(
            ResourceRecord.IpAddress(
              name = "google.com",
              timeToLive = 60,
              address = InetAddress.getByName("2607:f8b0:4023:1807:0:0:0:71"),
            ),
            ResourceRecord.IpAddress(
              name = "google.com",
              timeToLive = 60,
              address = InetAddress.getByName("2607:f8b0:4023:1807:0:0:0:65"),
            ),
            ResourceRecord.IpAddress(
              name = "google.com",
              timeToLive = 60,
              address = InetAddress.getByName("2607:f8b0:4023:1807:0:0:0:8a"),
            ),
            ResourceRecord.IpAddress(
              name = "google.com",
              timeToLive = 60,
              address = InetAddress.getByName("2607:f8b0:4023:1807:0:0:0:8b"),
            ),
          ),
      ),
    )
  }

  @Test
  fun `resource record with not compressed suffix`() {
    val reader =
      DnsMessageReader(
        "00008180000100010000000106676f6f676c6503636f6d0000010001c00c000100010000028e0004acd917ee" +
          "00002904d000000000000d000c0009585858585858585858",
      )
    assertThat(reader.read()).isEqualTo(
      DnsMessage(
        id = 0,
        flags = -32384,
        questions =
          listOf(
            Question(
              name = "google.com",
              type = TYPE_A,
            ),
          ),
        answers =
          listOf(
            ResourceRecord.IpAddress(
              name = "google.com",
              timeToLive = 654,
              address = InetAddress.getByName("172.217.23.238"),
            ),
          ),
      ),
    )
  }

  @Test
  fun `ignore cname`() {
    val reader =
      DnsMessageReader(
        "0000818000010002000000000567726170680866616365626f6f6b03636f6d0000010001c00c000500010000" +
          "0a0f000c04737461720463313072c012c030000100010000002e00041f0d5008",
      )
    assertThat(reader.read()).isEqualTo(
      DnsMessage(
        id = 0,
        flags = -32384,
        questions =
          listOf(
            Question(
              name = "graph.facebook.com",
              type = TYPE_A,
            ),
          ),
        answers =
          listOf(
            ResourceRecord.IpAddress(
              name = "star.c10r.facebook.com",
              timeToLive = 46,
              address = InetAddress.getByName("31.13.80.8"),
            ),
          ),
      ),
    )
  }

  @Test
  fun `ignore soa`() {
    val reader =
      DnsMessageReader(
        "0000818300010000000100000e7364666c6b686673646c6b6a646602656500001c0001c01b00060001000004" +
          "ff0038026e7303746c64c01b0a686f73746d61737465720d6565737469696e7465726e6574c01b6a554d2d" +
          "00000e10000003840012750000000e10",
      )
    assertThat(reader.read()).isEqualTo(
      DnsMessage(
        id = 0,
        flags = -32381,
        questions =
          listOf(
            Question(
              name = "sdflkhfsdlkjdf.ee",
              type = TYPE_AAAA,
            ),
          ),
      ),
    )
  }

  /** https://www.rfc-editor.org/info/rfc6891/ */
  @Test
  fun `ignore DNS extensions`() {
    val reader =
      DnsMessageReader(
        "00008180000100010000000106676f6f676c6503636f6d0000010001c00c000100010000028e0004acd917ee" +
          "00002904d000000000000d000c0009585858585858585858",
      )
    assertThat(reader.read()).isEqualTo(
      DnsMessage(
        id = 0,
        flags = -32384,
        questions =
          listOf(
            Question(
              name = "google.com",
              type = TYPE_A,
            ),
          ),
        answers =
          listOf(
            ResourceRecord.IpAddress(
              name = "google.com",
              timeToLive = 654,
              address = InetAddress.getByName("172.217.23.238"),
            ),
          ),
      ),
    )
  }

  /** This site's HTTPS record claims it to be h2-only. */
  @Test
  fun `https no-default-alpn`() {
    val reader =
      DnsMessageReader(
        "00008180000100010000000008796c696c61757461036f72670000410001c00c004100010000012c000e0001" +
          "000001000302683200020000",
      )
    assertThat(reader.read().answers.filterIsInstance<ResourceRecord.Https>())
      .containsExactly(
        ResourceRecord.Https(
          name = "ylilauta.org",
          timeToLive = 300,
          alpnIds = listOf("h2"),
        ),
      )
  }

  /** The only domain that specifies [ResourceRecord.Https.port] sets it to the default. Sigh. */
  @Test
  fun `https explicit port`() {
    val reader =
      DnsMessageReader(
        "0000818000010001000000000d6169726e65777a65616c616e6402636f026e7a0000410001c00c0041000100" +
          "0002580010000100000100030268320003000201bb",
      )
    assertThat(reader.read().answers.filterIsInstance<ResourceRecord.Https>())
      .containsExactly(
        ResourceRecord.Https(
          name = "airnewzealand.co.nz",
          timeToLive = 600,
          alpnIds = listOf("h2", "http/1.1"),
        ),
      )
  }

  @Test
  fun `https multiple records`() {
    val reader =
      DnsMessageReader(
        "00008180000100020000000005666263646e036e65740000410001c00c004100010000039c000d0001000001" +
          "0006026832026833c00c004100010000039c0032000209737461722d6d696e690866616c6c6261636b0463" +
          "3130720866616365626f6f6b03636f6d0000010006026832026833",
      )
    assertThat(reader.read().answers.filterIsInstance<ResourceRecord.Https>())
      .containsExactly(
        ResourceRecord.Https(
          name = "fbcdn.net",
          timeToLive = 924,
          alpnIds = listOf("h2", "h3", "http/1.1"),
        ),
        ResourceRecord.Https(
          name = "fbcdn.net",
          timeToLive = 924,
          priority = 2,
          targetName = "star-mini.fallback.c10r.facebook.com",
          alpnIds = listOf("h2", "h3", "http/1.1"),
        ),
      )
  }

  /** If [ResourceRecord.Https.priority] is 0, all parameters should be ignored. */
  @Test
  fun `https alias mode`() {
    val reader =
      DnsMessageReader(
        "000081800001000100000000076869737461747303636f6d0000410001c00c004100010000003c0022000007" +
          "6869737461747303636f6d0363646e0a636c6f7564666c617265036e657400",
      )
    assertThat(reader.read().answers.filterIsInstance<ResourceRecord.Https>())
      .containsExactly(
        ResourceRecord.Https(
          name = "histats.com",
          timeToLive = 60,
          priority = 0,
          targetName = "histats.com.cdn.cloudflare.net",
        ),
      )
  }

  @Test
  fun `https target cycle`() {
    val reader =
      DnsMessageReader(
        "00008180000100010000000006626565626f6d03636f6d0000410001c00c0041000100093a80000e00000662" +
          "6565626f6d03636f6d00",
      )
    assertThat(reader.read().answers.filterIsInstance<ResourceRecord.Https>())
      .containsExactly(
        ResourceRecord.Https(
          name = "beebom.com",
          timeToLive = 604800,
          priority = 0,
          targetName = "beebom.com",
        ),
      )
  }

  @Test
  fun `https target is not a hostname`() {
    val reader =
      DnsMessageReader(
        "00008180000100010000000007617572616b6c65036465760000410001c00c00410001000002580009000005" +
          "3c6e696c3e00",
      )
    assertThat(reader.read().answers.filterIsInstance<ResourceRecord.Https>())
      .containsExactly(
        ResourceRecord.Https(
          name = "aurakle.dev",
          timeToLive = 600,
          priority = 0,
          targetName = "<nil>",
        ),
      )
  }

  @Test
  fun `https target is an IP address`() {
    val reader =
      DnsMessageReader(
        "00008180000100010000000004756e7a6503636f6d02706b0000410001c00c004100010000012c0010000002" +
          "32330332323702333802363500",
      )
    assertThat(reader.read().answers.filterIsInstance<ResourceRecord.Https>())
      .containsExactly(
        ResourceRecord.Https(
          name = "unze.com.pk",
          timeToLive = 300,
          priority = 0,
          targetName = "23.227.38.65",
        ),
      )
  }

  /** The spec says you shouldn't include 'http/1.1' in your alpn parameter, but this one does. */
  @Test
  fun `https default alpn is already in the alpn list`() {
    val reader =
      DnsMessageReader(
        "0000818000010002000000000770726f736f647902696d0000410001c00c004100010000012c001e00010000" +
          "010003026832000600102a00109803a000000000000000000001c00c004100010000012c00180002000001" +
          "000908687474702f312e3100040004b07ef24a",
      )
    assertThat(reader.read().answers.filterIsInstance<ResourceRecord.Https>())
      .containsExactly(
        ResourceRecord.Https(
          name = "prosody.im",
          timeToLive = 300,
          priority = 1,
          alpnIds = listOf("h2", "http/1.1"),
          ipAddressHints = listOf(InetAddress.getByName("2a00:1098:3a0:0:0:0:0:1")),
        ),
        ResourceRecord.Https(
          name = "prosody.im",
          timeToLive = 300,
          priority = 2,
          alpnIds = listOf("http/1.1"),
          ipAddressHints = listOf(InetAddress.getByName("176.126.242.74")),
        ),
      )
  }

  /** This record uses the `mandatory` service parameter. */
  @Test
  fun `https mandatory parameter`() {
    val reader =
      DnsMessageReader(
        "000081800001000100000000056d7467616c03636f6d0000410001c00c0041000100093a8000530001000000" +
          "00020001000100030268330005003f003dfe0d0039aa00200020a4a7bb34b77c43336c3a2931dd28c87d00" +
          "8218a99b44f1f0aa8a82537d487d43000400010001000a676f6f676c652e636f6d0000",
      )
    assertThat(reader.read().answers.filterIsInstance<ResourceRecord.Https>())
      .containsExactly(
        ResourceRecord.Https(
          name = "mtgal.com",
          timeToLive = 604800,
          alpnIds = listOf("h3", "http/1.1"),
          echConfigList =
            """
            003dfe0d0039aa00200020a4a7bb34b77c43336c3a2931dd28c87d008218a99b44f1f0aa8a82537d487d4300
            0400010001000a676f6f676c652e636f6d0000
            """.decodeHex(ignoreWhitespace = true),
        ),
      )
  }

  /** This record includes an unrecognized parameter (7), presumably a misconfiguration. */
  @Test
  fun `https unknown service parameter`() {
    val reader =
      DnsMessageReader(
        "0000818000010001000000000b706c616e616c746f6e6574036e65740262720000410001c00c004100010000" +
          "012c005c000105646e7372310b706c616e616c746f6e6574036e6574026272000001000602683202683300" +
          "03000201bb00040004be6d50fb0006001028041088000000000000000000000012000700102f646e732d71" +
          "756572797b3f646e737d",
      )
    assertThat(reader.read().answers.filterIsInstance<ResourceRecord.Https>())
      .containsExactly(
        ResourceRecord.Https(
          name = "planaltonet.net.br",
          timeToLive = 300,
          targetName = "dnsr1.planaltonet.net.br",
          alpnIds = listOf("h2", "h3", "http/1.1"),
          ipAddressHints =
            listOf(
              InetAddress.getByName("190.109.80.251"),
              InetAddress.getByName("2804:1088:0:0:0:0:0:12"),
            ),
        ),
      )
  }

  private fun DnsMessageReader(hex: String) = DnsMessageReader(Buffer().write(hex.decodeHex()))
}
