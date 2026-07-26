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
package okhttp.android.test

import android.content.Context
import android.net.ConnectivityManager
import android.net.ConnectivityManager.NetworkCallback
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET
import android.net.NetworkCapabilities.NET_CAPABILITY_VALIDATED
import android.net.NetworkCapabilities.TRANSPORT_CELLULAR
import android.net.NetworkCapabilities.TRANSPORT_WIFI
import android.net.NetworkRequest
import android.os.Build
import androidx.test.core.app.ApplicationProvider
import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isIn
import assertk.assertions.isNotEqualTo
import assertk.assertions.isNotIn
import assertk.assertions.isNotNull
import java.io.IOException
import java.net.ConnectException
import java.net.InetAddress
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit.SECONDS
import okhttp3.Call
import okhttp3.Connection
import okhttp3.EventListener
import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assumptions.abort
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test

/**
 * Confirms a call pinned to a [Network] really goes over it, by checking the connected socket's
 * local address against that network's own link addresses.
 *
 * Pinning goes through [AndroidNetworkPinning], which needs no more than API 23 here — unlike ECH,
 * which needs 37.
 *
 * ## Typical Emulator
 *
 * ```
 * $ adb shell "dumpsys connectivity | grep -E 'Active default network|network\{10[01]\}  handle|Requests:'"
 * Active default network: 100
 *   NetworkAgentInfo{network{100}  handle{432902426637}  ni{WIFI CONNECTED ...
 *     Requests: REQUEST:35 LISTEN:40 BACKGROUND_REQUEST:0 total:75
 *   NetworkAgentInfo{network{101}  handle{437197393933}  ni{MOBILE[NR] CONNECTED ...
 *     Requests: REQUEST:0 LISTEN:12 BACKGROUND_REQUEST:1 total:13
 * ```
 *
 * Network 100 is `wlan0` (10.0.2.16) and network 101 is `eth0` (10.0.2.15); both use NAT through the
 * emulator host. `REQUEST:0` means nothing holds a request for it, AKA a *background* network.
 * So need to request the network.
 */
@Tag("Remote")
class AndroidNetworksTest {
  private val connectivityManager =
    ApplicationProvider
      .getApplicationContext<Context>()
      .getSystemService(ConnectivityManager::class.java)

  private val networkCallbacks = mutableListOf<NetworkCallback>()

  private lateinit var client: OkHttpClient

  @BeforeEach
  fun setUp() {
    // ConnectivityManager.getActiveNetwork() is API 23. Everything else here — getAllByName,
    // getSocketFactory, requestNetwork, getLinkProperties — is API 21.
    assumeTrue(Build.VERSION.SDK_INT >= 23)

    client = OkHttpClient()
  }

  @AfterEach
  fun tearDown() {
    for (callback in networkCallbacks) {
      connectivityManager.unregisterNetworkCallback(callback)
    }
    networkCallbacks.clear()
  }

  /** The network the system would have picked anyway — network 100, Wi-Fi — named explicitly. */
  @Test
  fun defaultNetworkCarriesTheCall() {
    val network = connectivityManager.activeNetwork
    assertThat(network).isNotNull()

    val capabilities = capabilitiesOf(network)
    assertThat(capabilities?.hasCapability(NET_CAPABILITY_INTERNET)).isEqualTo(true)
    assertThat(capabilities?.hasCapability(NET_CAPABILITY_VALIDATED)).isEqualTo(true)

    assertThat(localAddressOverNetwork(network!!)).isIn(*linkAddressesOf(network))
  }

  /**
   * A network that is not the default one — network 101, cellular, on the emulator. The test skips
   * on a device that only has the one network, but it does not skip if the system hands back the
   * default: that would mean this ran twice over the same network while appearing to cover two.
   */
  @Test
  fun nonDefaultNetworkCarriesTheCall() {
    val default = connectivityManager.activeNetwork
    val (transport, transportName) =
      when {
        capabilitiesOf(default)?.hasTransport(TRANSPORT_CELLULAR) == true -> TRANSPORT_WIFI to "Wi-Fi"
        else -> TRANSPORT_CELLULAR to "cellular"
      }

    val network = requestNetwork(transport)
    assumeTrue(network != null, "no $transportName network alongside the default one")

    assertThat(network).isNotEqualTo(default)
    assertThat(capabilitiesOf(network)?.hasTransport(transport)).isEqualTo(true)

    val localAddress = localAddressOverNetwork(network!!)
    assertThat(localAddress).isIn(*linkAddressesOf(network))
    // The point of the test: a different interface from the one the call would have taken.
    assertThat(localAddress).isNotIn(*linkAddressesOf(default!!))
  }

  /**
   * Makes a call pinned to [network] and returns the local address its socket ended up with.
   * [AndroidNetworkPinning] pins both halves off the request tag: DNS and the socket factory.
   * Without the socket factory a request resolved on one network still connects over the default
   * one.
   */
  private fun localAddressOverNetwork(network: Network): InetAddress? {
    var localAddress: InetAddress? = null

    val networkClient =
      client
        .newBuilder()
        .addInterceptor(AndroidNetworkPinning())
        .eventListener(
          object : EventListener() {
            override fun connectionAcquired(
              call: Call,
              connection: Connection,
            ) {
              localAddress = connection.socket().localAddress
            }
          },
        ).build()

    val request =
      Request
        .Builder()
        .url(URL)
        .tag<Network>(network)
        .build()

    try {
      networkClient.newCall(request).execute().use { response ->
        assertThat(response.code).isEqualTo(200)
      }
    } catch (e: IOException) {
      // Skipped flaky emulator rather than failed
      if (e.isNetworkUnusable()) {
        abort<Nothing>("$network could not reach $URL: ${e.message}")
      }
      throw e
    }

    return localAddress
  }

  /**
   * True if the call never got off the device: nothing resolved, or nothing connected. Checks the
   * causes and the suppressed attempts too, since an address that loses the happy-eyeballs race is
   * reported as a suppressed exception of the one that failed last.
   */
  private fun Throwable.isNetworkUnusable(): Boolean =
    this is UnknownHostException ||
      this is SocketTimeoutException ||
      this is ConnectException ||
      cause?.isNetworkUnusable() == true ||
      suppressed.any { it.isNetworkUnusable() }

  /**
   * Asks for a network on [transport] and returns it once it is available, or null if none turns up
   * in time. The callback stays registered until [tearDown], because releasing the request is what
   * would drop the network back into the background and break the calls made over it.
   */
  private fun requestNetwork(transport: Int): Network? {
    val request =
      NetworkRequest
        .Builder()
        .addCapability(NET_CAPABILITY_INTERNET)
        .addTransportType(transport)
        .build()

    val available = LinkedBlockingQueue<Network>()
    val callback =
      object : NetworkCallback() {
        override fun onAvailable(network: Network) {
          available.offer(network)
        }
      }

    connectivityManager.requestNetwork(request, callback)
    networkCallbacks += callback

    return available.poll(20, SECONDS)
  }

  /** The addresses assigned to [network]'s interface, such as 10.0.2.16 for the emulator's Wi-Fi. */
  private fun linkAddressesOf(network: Network): Array<InetAddress> =
    connectivityManager
      .getLinkProperties(network)
      ?.linkAddresses
      .orEmpty()
      .map { it.address }
      .toTypedArray()

  private fun capabilitiesOf(network: Network?): NetworkCapabilities? = connectivityManager.getNetworkCapabilities(network)

  companion object {
    /**
     * A single `A` record and no `AAAA`. Emulators are flaky for IPv6, avoid it.
     */
    private const val URL = "https://publicobject.com/robots.txt"
  }
}
