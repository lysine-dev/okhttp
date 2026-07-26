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

import android.annotation.SuppressLint
import android.net.Network
import android.os.Build
import java.net.InetAddress
import okhttp3.Dns
import okhttp3.Interceptor
import okhttp3.Response
import okhttp3.android.EchAwareDns

/**
 * Interceptor that supports Network Pinning on Android via Request tags.
 *
 * Apply as an Application [Interceptor] and based on [okhttp3.Request.tag] with type [Network],
 * the appropriate [okhttp3.Dns] and [javax.net.SocketFactory] will be configured.
 *
 * Copied from https://github.com/square/okhttp/pull/8376, which proposed this as library code.
 */
@SuppressLint("NewApi")
class AndroidNetworkPinning : Interceptor {
  override fun intercept(chain: Interceptor.Chain): Response {
    val request = chain.request()

    val pinnedNetwork = request.tag<Network>()

    val effectiveChain =
      if (pinnedNetwork != null) {
        chain
          .withSocketFactory(pinnedNetwork.socketFactory)
          .withDns(dnsForNetwork(pinnedNetwork))
      } else {
        chain
      }

    return effectiveChain.proceed(request)
  }

  /**
   * ECH needs the `HTTPS` record, which is only reachable through `DnsResolver.rawQuery()` and only
   * consulted by the platform from API 37. Below that there's nothing to gain from the extra query,
   * so [AndroidNetworkDns] does the plain address lookup.
   */
  private fun dnsForNetwork(network: Network): Dns =
    when {
      Build.VERSION.SDK_INT >= 37 -> EchAwareDns.forNetwork(network)
      else -> AndroidNetworkDns(network)
    }
}

/**
 * A [Dns] scoped to [network], used below API 37 where there's no ECH to resolve for.
 *
 * [Network.getAllByName] is the whole implementation: it resolves on that network and nothing else,
 * with no service metadata.
 */
class AndroidNetworkDns(
  private val network: Network,
) : Dns {
  override fun lookup(hostname: String): List<InetAddress> = network.getAllByName(hostname).toList()
}
