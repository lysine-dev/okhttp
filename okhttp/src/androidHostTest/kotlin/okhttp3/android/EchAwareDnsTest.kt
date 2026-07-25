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
import android.net.Network
import android.security.NetworkSecurityPolicy
import android.security.NetworkSecurityPolicy.DOMAIN_ENCRYPTION_MODE_DISABLED
import android.security.NetworkSecurityPolicy.DOMAIN_ENCRYPTION_MODE_ENABLED
import android.security.NetworkSecurityPolicy.DOMAIN_ENCRYPTION_MODE_OPPORTUNISTIC
import android.security.NetworkSecurityPolicy.DOMAIN_ENCRYPTION_MODE_UNKNOWN
import assertk.assertThat
import assertk.assertions.containsExactly
import assertk.assertions.isEmpty
import assertk.assertions.isEqualTo
import assertk.assertions.isFalse
import assertk.assertions.isTrue
import java.net.InetAddress
import okhttp3.Dns
import okhttp3.FakeDns
import okhttp3.android.ShadowNetworkSecurityPolicy.Companion.newNetworkSecurityPolicy
import okhttp3.internal.OkHttpInternalApi
import okhttp3.internal.SuppressSignatureCheck
import okhttp3.internal.dns.ResourceRecord
import okhttp3.internal.dns.execute
import okio.ByteString
import okio.ByteString.Companion.encodeUtf8
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowNetwork

@SuppressLint("NewApi")
@SuppressSignatureCheck
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [37], shadows = [ShadowNetworkSecurityPolicy::class])
class EchAwareDnsTest {
  private val echAddress = InetAddress.getByAddress("publicobject.com", byteArrayOf(1, 1, 1, 1))
  private val addressOnlyAddress = InetAddress.getByAddress("publicobject.com", byteArrayOf(2, 2, 2, 2))
  private val echConfigList = "ech config list".encodeUtf8()

  /** Carries service metadata, as [AndroidDns] does when it queries the `HTTPS` record. */
  private val echDns =
    FakeDns().apply {
      setRecords("publicobject.com", echAddress, echConfigList)
    }

  private val addressOnlyDns =
    FakeDns().apply {
      setRecords("publicobject.com", addressOnlyAddress)
    }

  @Test
  fun encryptionEnabledUsesEchDns() {
    val records = recordsFor(DOMAIN_ENCRYPTION_MODE_ENABLED)

    assertThat(records.addresses()).containsExactly(echAddress)
    assertThat(records.echConfigLists()).containsExactly(echConfigList)
  }

  @Test
  fun encryptionOpportunisticUsesEchDns() {
    val records = recordsFor(DOMAIN_ENCRYPTION_MODE_OPPORTUNISTIC)

    assertThat(records.addresses()).containsExactly(echAddress)
    assertThat(records.echConfigLists()).containsExactly(echConfigList)
  }

  @Test
  fun encryptionDisabledUsesAddressOnlyDns() {
    val records = recordsFor(DOMAIN_ENCRYPTION_MODE_DISABLED)

    assertThat(records.addresses()).containsExactly(addressOnlyAddress)
    assertThat(records.echConfigLists()).isEmpty()
  }

  @Test
  fun encryptionUnknownUsesAddressOnlyDns() {
    val records = recordsFor(DOMAIN_ENCRYPTION_MODE_UNKNOWN)

    assertThat(records.addresses()).containsExactly(addressOnlyAddress)
    assertThat(records.echConfigLists()).isEmpty()
  }

  /** Hosts the policy says nothing about don't pay for an `HTTPS` query. */
  @Test
  fun unconfiguredHostUsesAddressOnlyDns() {
    val records = echAwareDns(newNetworkSecurityPolicy()).recordsFor("publicobject.com")

    assertThat(records.addresses()).containsExactly(addressOnlyAddress)
    assertThat(records.echConfigLists()).isEmpty()
  }

  /** Each host is judged on its own policy, even though both are available from [echDns]. */
  @Test
  fun policyIsPerHost() {
    val deniedEchAddress = InetAddress.getByAddress("denied.example.com", byteArrayOf(1, 1, 1, 2))
    val deniedEchConfigList = "denied ech config list".encodeUtf8()
    val deniedAddress = InetAddress.getByAddress("denied.example.com", byteArrayOf(2, 2, 2, 2))
    echDns.setRecords("denied.example.com", deniedEchAddress, deniedEchConfigList)
    addressOnlyDns.setRecords("denied.example.com", deniedAddress)

    val dns =
      echAwareDns(
        newNetworkSecurityPolicy(
          "publicobject.com" to DOMAIN_ENCRYPTION_MODE_ENABLED,
          "denied.example.com" to DOMAIN_ENCRYPTION_MODE_DISABLED,
        ),
      )

    val allowedRecords = dns.recordsFor("publicobject.com")
    assertThat(allowedRecords.addresses()).containsExactly(echAddress)
    assertThat(allowedRecords.echConfigLists()).containsExactly(echConfigList)

    val deniedRecords = dns.recordsFor("denied.example.com")
    assertThat(deniedRecords.addresses()).containsExactly(deniedAddress)
    assertThat(deniedRecords.echConfigLists()).isEmpty()
  }

  /** [Dns.lookup] can't carry HTTPS records, so it never pays for the `HTTPS` query. */
  @Test
  fun lookupAlwaysUsesAddressOnlyDns() {
    val dns = echAwareDns(newNetworkSecurityPolicy("publicobject.com" to DOMAIN_ENCRYPTION_MODE_ENABLED))

    assertThat(dns.lookup("publicobject.com")).containsExactly(addressOnlyAddress)
  }

  /** Both arms of [EchAwareDns.forNetwork] resolve on the network, not just the ECH one. */
  @Test
  fun forNetworkScopesBothArms() {
    val network = ShadowNetwork.newInstance(1234)

    val dns = EchAwareDns.forNetwork(network)

    val echDns = dns.echDns as AndroidDns
    assertThat(echDns.network).isEqualTo(network)
    assertThat(echDns.includeServiceMetadata).isTrue()

    val addressOnlyDns = dns.addressOnlyDns as AndroidDns
    assertThat(addressOnlyDns.network).isEqualTo(network)
    assertThat(addressOnlyDns.includeServiceMetadata).isFalse()
  }

  private fun recordsFor(domainEncryptionMode: Int): List<Dns.Record> =
    echAwareDns(newNetworkSecurityPolicy("publicobject.com" to domainEncryptionMode))
      .recordsFor("publicobject.com")

  private fun Dns.recordsFor(hostname: String): List<Dns.Record> = newCall(Dns.Request(hostname)).execute()

  private fun List<Dns.Record>.addresses() = filterIsInstance<Dns.Record.IpAddress>().map { it.address }

  private fun List<Dns.Record>.echConfigLists() = filterIsInstance<Dns.Record.ServiceMetadata>().mapNotNull { it.echConfigList }

  private fun echAwareDns(policy: NetworkSecurityPolicy) =
    EchAwareDns(
      echDns = echDns,
      addressOnlyDns = addressOnlyDns,
      policy = policy,
    )
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
