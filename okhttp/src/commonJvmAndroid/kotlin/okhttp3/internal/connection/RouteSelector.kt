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
package okhttp3.internal.connection

import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.SocketException
import java.net.UnknownHostException
import okhttp3.Address
import okhttp3.Dns
import okhttp3.HttpUrl
import okhttp3.Route
import okhttp3.internal.canParseAsIpAddress
import okhttp3.internal.dns.LookupDnsCall
import okhttp3.internal.dns.execute
import okhttp3.internal.immutableListOf
import okhttp3.internal.toImmutableList

/**
 * Selects routes to connect to an origin server. Each connection requires a choice of proxy server,
 * IP address, and TLS mode. Connections may also be recycled.
 */
class RouteSelector internal constructor(
  private val address: Address,
  private val routeDatabase: RouteDatabase,
  private val call: RealCall,
  private val fastFallback: Boolean,
) {
  // State for negotiating the next proxy to use.
  private var proxies = emptyList<Proxy>()
  private var nextProxyIndex: Int = 0

  // State for negotiating failed routes
  private val postponedRoutes = mutableListOf<Route>()

  init {
    resetNextProxy(address.url, address.proxy)
  }

  /**
   * Returns true if there's another set of routes to attempt. Every address has at least one route.
   */
  operator fun hasNext(): Boolean = hasNextProxy() || postponedRoutes.isNotEmpty()

  @Throws(IOException::class)
  operator fun next(): Selection {
    if (!hasNext()) throw NoSuchElementException()

    // Compute the next set of routes to attempt.
    val routes = mutableListOf<Route>()
    while (hasNextProxy()) {
      // Postponed routes are always tried last. For example, if we have 2 proxies and all the
      // routes for proxy1 should be postponed, we'll move to proxy2. Only after we've exhausted
      // all the good routes will we attempt the postponed routes.
      val proxyRoutes = nextProxy()
      for (route in proxyRoutes) {
        if (routeDatabase.shouldPostpone(route)) {
          postponedRoutes += route
        } else {
          routes += route
        }
      }

      if (routes.isNotEmpty()) {
        break
      }
    }

    if (routes.isEmpty()) {
      // We've exhausted all Proxies so fallback to the postponed routes.
      routes += postponedRoutes
      postponedRoutes.clear()
    }

    return Selection(routes)
  }

  /** Prepares the proxy servers to try. */
  private fun resetNextProxy(
    url: HttpUrl,
    proxy: Proxy?,
  ) {
    fun selectProxies(): List<Proxy> {
      // If the user specifies a proxy, try that and only that.
      if (proxy != null) return listOf(proxy)

      // If the URI lacks a host (as in "http://</"), don't call the ProxySelector.
      val uri = url.toUri()
      if (uri.host == null) return immutableListOf(Proxy.NO_PROXY)

      // Try each of the ProxySelector choices until one connection succeeds. A misconfigured
      // system proxy (such as one with no port set) can make ProxySelector.select() itself throw
      // IllegalArgumentException; treat that as "no usable proxy" rather than letting it crash.
      val proxiesOrNull =
        try {
          address.proxySelector.select(uri)
        } catch (_: IllegalArgumentException) {
          null
        }
      if (proxiesOrNull.isNullOrEmpty()) return immutableListOf(Proxy.NO_PROXY)

      return proxiesOrNull.toImmutableList()
    }

    call.eventListener.proxySelectStart(call, url)
    proxies = selectProxies()
    nextProxyIndex = 0
    call.eventListener.proxySelectEnd(call, url, proxies)
  }

  /** Returns true if there's another proxy to try. */
  private fun hasNextProxy(): Boolean = nextProxyIndex < proxies.size

  /** Returns the next proxy to try. May be PROXY.NO_PROXY but never null. */
  @Throws(IOException::class)
  private fun nextProxy(): List<Route> {
    if (!hasNextProxy()) {
      throw SocketException(
        "No route to ${address.url.host}; exhausted proxy configurations: $proxies",
      )
    }
    val result = proxies[nextProxyIndex++]
    return nextRoutes(result)
  }

  /** Returns the routes to attempt for [proxy]. */
  @Throws(IOException::class)
  private fun nextRoutes(proxy: Proxy): List<Route> {
    val socketHost: String
    val socketPort: Int
    if (proxy.type() == Proxy.Type.DIRECT || proxy.type() == Proxy.Type.SOCKS) {
      socketHost = address.url.host
      socketPort = address.url.port
    } else {
      val proxyAddress = proxy.address()
      require(proxyAddress is InetSocketAddress) {
        "Proxy.address() is not an InetSocketAddress: ${proxyAddress.javaClass}"
      }
      socketHost = proxyAddress.socketHost
      socketPort = proxyAddress.port
    }

    if (socketPort !in 1..65535) {
      throw SocketException("No route to $socketHost:$socketPort; port is out of range")
    }

    if (proxy.type() == Proxy.Type.SOCKS) {
      return listOf(
        Route(
          address,
          proxy,
          InetSocketAddress.createUnresolved(socketHost, socketPort),
        ),
      )
    }

    if (socketHost.canParseAsIpAddress()) {
      return listOf(
        Route(
          address,
          proxy,
          InetSocketAddress(InetAddress.getByName(socketHost), socketPort),
        ),
      )
    }

    val routes = dnsLookup(proxy, socketHost, socketPort)

    // Try each address for best behavior in mixed IPv4/IPv6 environments.
    return when {
      fastFallback -> reorderForHappyEyeballs(routes)
      else -> routes
    }
  }

  // TODO: switch RouteSelector to be async.

  /**
   * Use the new async [Dns.Call] API, but call it *synchronously* until we change this class to
   * itself be asynchronous.
   *
   * To save threads and context switching, this currently falls back to the synchronous API if the
   * DNS implementation is itself synchronous. When everything is async we won't do that anymore.
   */
  private fun dnsLookup(
    proxy: Proxy,
    socketHost: String,
    socketPort: Int,
  ): List<Route> {
    call.eventListener.dnsStart(
      call = call,
      domainName = socketHost,
    )

    // If it's a 'http://' URL on the default port, do the DNS query for a 'https://' URL on the
    // default port. DNS records that have a port promote HTTP to HTTPS.
    val dnsPort =
      when {
        !address.url.isHttps && socketPort == 80 -> -1
        else -> socketPort
      }

    val dnsRequest = Dns.Request(socketHost, dnsPort)
    val result =
      when (val dnsCall = address.dns.newCall(dnsRequest)) {
        is LookupDnsCall -> {
          val inetAddresses = address.dns.lookup(socketHost)
          inetAddresses.map { inetAddress ->
            Route(
              address,
              proxy,
              InetSocketAddress(inetAddress, socketPort),
            )
          }
        }

        else -> {
          val records = dnsCall.execute()

          val hostnameToServiceMetadata =
            records
              .filterIsInstance<Dns.Record.ServiceMetadata>()
              .associateBy { it.hostname }

          records
            .filterIsInstance<Dns.Record.IpAddress>()
            .map { record ->
              val serviceMetadata = hostnameToServiceMetadata[record.hostname]
              Route(
                address = address,
                proxy = proxy,
                socketAddress = InetSocketAddress(record.address, socketPort),
                echConfigList = serviceMetadata?.echConfigList,
              )
            }
        }
      }

    if (result.isEmpty()) {
      throw UnknownHostException("${address.dns} returned no addresses for $socketHost")
    }

    call.eventListener.dnsEnd(
      call = call,
      domainName = socketHost,
      inetAddressList = result.map { it.socketAddress.address },
    )

    return result
  }

  /** A set of selected Routes. */
  class Selection(
    val routes: List<Route>,
  ) {
    private var nextRouteIndex = 0

    operator fun hasNext(): Boolean = nextRouteIndex < routes.size

    operator fun next(): Route {
      if (!hasNext()) throw NoSuchElementException()
      return routes[nextRouteIndex++]
    }
  }

  companion object {
    /** Obtain a host string containing either an actual host name or a numeric IP address. */
    val InetSocketAddress.socketHost: String
      get() {
        // The InetSocketAddress was specified with a string (either a numeric IP or a host name).
        // If it is a name, all IPs for that name should be tried. If it is an IP address, only that
        // IP address should be tried.
        val address = address ?: return hostName

        // The InetSocketAddress has a specific address: we should only try that address. Therefore,
        // we return the address and ignore any host name that may be available.
        return address.hostAddress
      }
  }
}
