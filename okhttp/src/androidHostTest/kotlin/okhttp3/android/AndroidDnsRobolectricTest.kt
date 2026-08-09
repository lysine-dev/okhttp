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

package okhttp3.android

import android.annotation.SuppressLint
import android.security.NetworkSecurityPolicy.DOMAIN_ENCRYPTION_MODE_DISABLED
import android.security.NetworkSecurityPolicy.DOMAIN_ENCRYPTION_MODE_ENABLED
import android.security.NetworkSecurityPolicy.DOMAIN_ENCRYPTION_MODE_OPPORTUNISTIC
import android.security.NetworkSecurityPolicy.DOMAIN_ENCRYPTION_MODE_UNKNOWN
import assertk.assertThat
import assertk.assertions.containsExactly
import assertk.assertions.hasSize
import assertk.assertions.isEmpty
import java.net.InetAddress
import okhttp3.Dns
import okhttp3.DnsCache
import okhttp3.FakeDns
import okhttp3.internal.OkHttpInternalApi
import okhttp3.internal.SuppressSignatureCheck
import okhttp3.internal.concurrent.TaskRunner
import okhttp3.internal.dns.ResourceRecord
import okhttp3.internal.dns.execute
import okio.ByteString
import okio.ByteString.Companion.encodeUtf8
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@SuppressLint("NewApi")
@SuppressSignatureCheck
@RunWith(RobolectricTestRunner::class)
@Config(
  sdk = [37],
  shadows = [
    ShadowDnsResolver::class,
    ShadowNetwork::class,
    ShadowNetworkSecurityPolicy::class,
  ],
)
class AndroidDnsRobolectricTest {
  private val echConfigList = "ech config list".encodeUtf8()

  private val dnsServer =
    FakeDns().apply {
      setRecords(
        hostname = "publicobject.com",
        address = InetAddress.getByName("10.20.30.40"),
        echConfigList = echConfigList,
      )
    }

  private val domainEncryptionModes =
    mutableMapOf(
      "publicobject.com" to DOMAIN_ENCRYPTION_MODE_ENABLED,
    )

  private val dnsCache = DnsCache()

  private val androidDns =
    AndroidDns(
      dnsResolver = ShadowDnsResolver.create(dnsServer),
      network = ShadowNetwork.create(dnsServer),
      dnsCache = dnsCache,
      includeServiceMetadata = true,
      executor = { it.run() },
      taskRunner = TaskRunner.INSTANCE,
      lazyNetworkSecurityPolicy =
        lazy {
          ShadowNetworkSecurityPolicy.create(domainEncryptionModes)
        },
    )

  @Test
  fun happyPath() {
    val records = androidDns.recordsFor("publicobject.com")
    assertThat(records.addresses()).containsExactly(InetAddress.getByName("10.20.30.40"))
    assertThat(records.echConfigLists()).containsExactly(echConfigList)
    assertThat(dnsServer.takeAllRequests()).hasSize(2)
  }

  @Test
  fun disabledPolicySkipsHttpsMetadata() {
    domainEncryptionModes["publicobject.com"] = DOMAIN_ENCRYPTION_MODE_DISABLED
    val records = androidDns.recordsFor("publicobject.com")
    assertThat(records.addresses()).containsExactly(InetAddress.getByName("10.20.30.40"))
    assertThat(records.echConfigLists()).isEmpty()
  }

  @Test
  fun unknownPolicySkipsHttpsMetadata() {
    domainEncryptionModes["publicobject.com"] = DOMAIN_ENCRYPTION_MODE_UNKNOWN
    val records = androidDns.recordsFor("publicobject.com")
    assertThat(records.addresses()).containsExactly(InetAddress.getByName("10.20.30.40"))
    assertThat(records.echConfigLists()).isEmpty()
  }

  @Test
  fun opportunisticPolicyIncludesHttpsMetadata() {
    domainEncryptionModes["publicobject.com"] = DOMAIN_ENCRYPTION_MODE_OPPORTUNISTIC
    val records = androidDns.recordsFor("publicobject.com")
    assertThat(records.addresses()).containsExactly(InetAddress.getByName("10.20.30.40"))
    assertThat(records.echConfigLists()).containsExactly(echConfigList)
  }

  @Test
  fun policyIsPerHost() {
    val deniedEchConfigList = "denied ech config list".encodeUtf8()
    dnsServer.setRecords(
      hostname = "denied.example.com",
      address = InetAddress.getByName("1:2::3:4"),
      echConfigList = deniedEchConfigList,
    )
    domainEncryptionModes["denied.example.com"] = DOMAIN_ENCRYPTION_MODE_DISABLED

    val recordsA = androidDns.recordsFor("publicobject.com")
    assertThat(recordsA.addresses()).containsExactly(InetAddress.getByName("10.20.30.40"))
    assertThat(recordsA.echConfigLists()).containsExactly(echConfigList)

    val recordsB = androidDns.recordsFor("denied.example.com")
    assertThat(recordsB.addresses()).containsExactly(InetAddress.getByName("1:2::3:4"))
    assertThat(recordsB.echConfigLists()).isEmpty()
  }

  @Test
  fun lookupDoesNotRequestServiceMetadata() {
    val addresses = androidDns.lookup("publicobject.com")
    assertThat(addresses).containsExactly(InetAddress.getByName("10.20.30.40"))
    assertThat(dnsServer.takeAllRequests()).hasSize(1)
  }

  private fun Dns.recordsFor(hostname: String): List<Dns.Record> = newCall(Dns.Request(hostname)).execute()

  private fun List<Dns.Record>.addresses() = filterIsInstance<Dns.Record.IpAddress>().map { it.address }

  private fun List<Dns.Record>.echConfigLists() = filterIsInstance<Dns.Record.ServiceMetadata>().mapNotNull { it.echConfigList }
}

/** Serves [address] for [hostname], plus an `HTTPS` record when [echConfigList] is non-null. */
private fun FakeDns.setRecords(
  hostname: String,
  address: InetAddress,
  echConfigList: ByteString? = null,
) {
  this[hostname] =
    listOfNotNull(
      echConfigList?.let {
        ResourceRecord.Https(
          name = hostname,
          timeToLive = 5,
          echConfigList = it,
        )
      },
      ResourceRecord.IpAddress(
        name = hostname,
        timeToLive = 5,
        address = address,
      ),
    )
}
