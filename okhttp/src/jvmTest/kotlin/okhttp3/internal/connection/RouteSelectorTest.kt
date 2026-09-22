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

import app.cash.burst.Burst
import assertk.assertThat
import assertk.assertions.containsExactly
import assertk.assertions.hasSize
import assertk.assertions.isEqualTo
import assertk.assertions.isFalse
import assertk.assertions.isSameInstanceAs
import assertk.assertions.isTrue
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.ProxySelector
import java.net.SocketAddress
import java.net.URI
import java.net.UnknownHostException
import kotlin.test.assertFailsWith
import okhttp3.Address
import okhttp3.FakeDns
import okhttp3.OkHttpClientTestRule
import okhttp3.Request
import okhttp3.Route
import okhttp3.TestValueFactory
import okhttp3.internal.connection.RouteSelector.Companion.socketHost
import okhttp3.internal.dns.EntryPoint
import okhttp3.internal.dns.ResourceRecord
import okhttp3.internal.dns.forceEntryPoint
import okhttp3.internal.http.RecordingProxySelector
import okhttp3.testing.PlatformRule
import okio.ByteString
import okio.ByteString.Companion.encodeUtf8
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.RegisterExtension

@Burst
class RouteSelectorTest(
  private val entryPoint: EntryPoint = EntryPoint.Lookup,
) {
  @RegisterExtension
  val platform = PlatformRule()

  @RegisterExtension
  val clientTestRule = OkHttpClientTestRule()

  private val dns = FakeDns()
  private val proxySelector = RecordingProxySelector()
  private val uriHost = "hosta"
  private val uriPort = 1003
  private val echConfigList = "this is an encrypted client hello".encodeUtf8()
  private val factory =
    TestValueFactory().apply {
      this.dns = this@RouteSelectorTest.dns.forceEntryPoint(entryPoint)
      this.proxySelector = this@RouteSelectorTest.proxySelector
      this.uriHost = this@RouteSelectorTest.uriHost
      this.uriPort = this@RouteSelectorTest.uriPort
    }

  private lateinit var call: RealCall
  private val routeDatabase = RouteDatabase()

  @BeforeEach fun setUp() {
    call =
      clientTestRule.newClient().newCall(
        Request
          .Builder()
          .url("https://$uriHost:$uriPort/")
          .build(),
      ) as RealCall
  }

  @AfterEach fun tearDown() {
    factory.close()
  }

  @Test fun singleRoute() {
    val address = factory.newAddress()
    val routeSelector = newRouteSelector(address)
    assertThat(routeSelector.hasNext()).isTrue()
    dns[uriHost] = dns.allocate(1)
    val selection = routeSelector.next()
    assertRoute(selection.next(), address, Proxy.NO_PROXY, dns[uriHost][0], uriPort)
    dns.assertRequests(uriHost)
    assertThat(selection.hasNext()).isFalse()
    assertFailsWith<NoSuchElementException> {
      selection.next()
    }
    assertThat(routeSelector.hasNext()).isFalse()
    assertFailsWith<NoSuchElementException> {
      routeSelector.next()
    }
  }

  @Test fun routeReturnsEncryptedClientHelloData() {
    assumeTrue(entryPoint == EntryPoint.NewCall)

    val address = factory.newAddress()
    val routeSelector = newRouteSelector(address)
    assertThat(routeSelector.hasNext()).isTrue()
    dns[uriHost] =
      listOf(
        ResourceRecord.IpAddress(
          name = uriHost,
          timeToLive = 5,
          address = dns.allocate(1).single(),
        ),
        ResourceRecord.Https(
          name = uriHost,
          timeToLive = 5,
          echConfigList = echConfigList,
        ),
      )
    val selection = routeSelector.next()
    assertRoute(selection.next(), address, Proxy.NO_PROXY, dns[uriHost][0], uriPort, echConfigList)
    dns.assertRequests(uriHost)
  }

  /** https://www.rfc-editor.org/rfc/rfc9848.html#section-5.1 */
  @Test fun echAddressesDoNotFallBackToNonEch() {
    assumeTrue(entryPoint == EntryPoint.NewCall)

    val echAddress = dns.allocate(1).single()
    val nonEchAddress = dns.allocate(1).single()
    dns[uriHost] =
      listOf(
        ResourceRecord.Https(
          name = uriHost,
          timeToLive = 5,
          targetName = "ech.$uriHost",
          echConfigList = echConfigList,
        ),
        ResourceRecord.IpAddress(
          name = "ech.$uriHost",
          timeToLive = 5,
          address = echAddress,
        ),
        ResourceRecord.IpAddress(
          name = "non-ech.$uriHost",
          timeToLive = 5,
          address = nonEchAddress,
        ),
      )
    val address = factory.newAddress()
    val routeSelector = newRouteSelector(address)

    val selection = routeSelector.next()

    assertThat(selection.routes).hasSize(1)
    assertRoute(selection.next(), address, Proxy.NO_PROXY, echAddress, uriPort, echConfigList)
    assertThat(selection.hasNext()).isFalse()
    assertThat(routeSelector.hasNext()).isFalse()
    dns.assertRequests(uriHost)
  }

  @Test fun singleRouteReturnsFailedRoute() {
    val address = factory.newAddress()
    var routeSelector = newRouteSelector(address)
    assertThat(routeSelector.hasNext()).isTrue()
    dns[uriHost] = dns.allocate(1)
    var selection = routeSelector.next()
    val route = selection.next()
    routeDatabase.failed(route)
    routeSelector = newRouteSelector(address)
    selection = routeSelector.next()
    assertRoute(selection.next(), address, Proxy.NO_PROXY, dns[uriHost][0], uriPort)
    assertThat(selection.hasNext()).isFalse()
    assertFailsWith<NoSuchElementException> {
      selection.next()
    }
    assertThat(routeSelector.hasNext()).isFalse()
    assertFailsWith<NoSuchElementException> {
      routeSelector.next()
    }
  }

  @Test fun explicitProxyTriesThatProxysAddressesOnly() {
    val address =
      factory.newAddress(
        proxy = proxyA,
      )
    val routeSelector = newRouteSelector(address)
    assertThat(routeSelector.hasNext()).isTrue()
    dns[PROXY_A_HOST] = dns.allocate(2)
    val selection = routeSelector.next()
    assertRoute(selection.next(), address, proxyA, dns[PROXY_A_HOST][0], PROXY_A_PORT)
    assertRoute(selection.next(), address, proxyA, dns[PROXY_A_HOST][1], PROXY_A_PORT)
    assertThat(selection.hasNext()).isFalse()
    assertThat(routeSelector.hasNext()).isFalse()
    dns.assertRequests(PROXY_A_HOST)
    proxySelector.assertRequests() // No proxy selector requests!
  }

  @Test fun explicitDirectProxy() {
    val address =
      factory.newAddress(
        proxy = Proxy.NO_PROXY,
      )
    val routeSelector = newRouteSelector(address)
    assertThat(routeSelector.hasNext()).isTrue()
    dns[uriHost] = dns.allocate(2)
    val selection = routeSelector.next()
    assertRoute(selection.next(), address, Proxy.NO_PROXY, dns[uriHost][0], uriPort)
    assertRoute(selection.next(), address, Proxy.NO_PROXY, dns[uriHost][1], uriPort)
    assertThat(selection.hasNext()).isFalse()
    assertThat(routeSelector.hasNext()).isFalse()
    dns.assertRequests(uriHost)
    proxySelector.assertRequests() // No proxy selector requests!
  }

  /**
   * Don't call through to the proxy selector if we don't have a host name.
   * https://github.com/lysine-dev/okhttp/issues/5770
   */
  @Test fun proxySelectorNotCalledForNullHost() {
    // The string '>' is okay in a hostname in HttpUrl, which does very light hostname validation.
    // It is not okay in URI, and so it's stripped and we get a URI with a null host.
    val bogusHostname = ">"
    val address =
      factory.newAddress(
        uriHost = bogusHostname,
        uriPort = uriPort,
      )
    val routeSelector = newRouteSelector(address)
    assertThat(routeSelector.hasNext()).isTrue()
    dns[bogusHostname] = dns.allocate(1)
    val selection = routeSelector.next()
    assertRoute(selection.next(), address, Proxy.NO_PROXY, dns[bogusHostname][0], uriPort)
    assertThat(selection.hasNext()).isFalse()
    assertThat(routeSelector.hasNext()).isFalse()
    dns.assertRequests(bogusHostname)
    proxySelector.assertRequests() // No proxy selector requests!
  }

  @Test fun proxySelectorReturnsNull() {
    val nullProxySelector: ProxySelector =
      object : ProxySelector() {
        override fun select(uri: URI): List<Proxy>? {
          assertThat(uri.host).isEqualTo(uriHost)
          return null
        }

        override fun connectFailed(
          uri: URI,
          socketAddress: SocketAddress,
          e: IOException,
        ): Unit = throw AssertionError()
      }

    val address =
      factory.newAddress(
        proxySelector = nullProxySelector,
      )
    val routeSelector = newRouteSelector(address)
    assertThat(routeSelector.hasNext()).isTrue()
    dns[uriHost] = dns.allocate(1)
    val selection = routeSelector.next()
    assertRoute(selection.next(), address, Proxy.NO_PROXY, dns[uriHost][0], uriPort)
    dns.assertRequests(uriHost)
    assertThat(selection.hasNext()).isFalse()
    assertThat(routeSelector.hasNext()).isFalse()
  }

  @Test fun proxySelectorThrowingIllegalArgumentExceptionFallsBackToDirect() {
    val brokenProxySelector: ProxySelector =
      object : ProxySelector() {
        override fun select(uri: URI): List<Proxy>? {
          // A system proxy with an unset port makes the platform ProxySelector throw this.
          throw IllegalArgumentException("port out of range:-1")
        }

        override fun connectFailed(
          uri: URI,
          socketAddress: SocketAddress,
          e: IOException,
        ): Unit = throw AssertionError()
      }

    val address =
      factory.newAddress(
        proxySelector = brokenProxySelector,
      )
    val routeSelector = newRouteSelector(address)
    assertThat(routeSelector.hasNext()).isTrue()
    dns[uriHost] = dns.allocate(1)
    val selection = routeSelector.next()
    assertRoute(selection.next(), address, Proxy.NO_PROXY, dns[uriHost][0], uriPort)
    dns.assertRequests(uriHost)
    assertThat(selection.hasNext()).isFalse()
    assertThat(routeSelector.hasNext()).isFalse()
  }

  @Test fun proxySelectorReturnsNoProxies() {
    val address = factory.newAddress()
    val routeSelector = newRouteSelector(address)
    assertThat(routeSelector.hasNext()).isTrue()
    dns[uriHost] = dns.allocate(2)
    val selection = routeSelector.next()
    assertRoute(selection.next(), address, Proxy.NO_PROXY, dns[uriHost][0], uriPort)
    assertRoute(selection.next(), address, Proxy.NO_PROXY, dns[uriHost][1], uriPort)
    assertThat(selection.hasNext()).isFalse()
    assertThat(routeSelector.hasNext()).isFalse()
    dns.assertRequests(uriHost)
    proxySelector.assertRequests(address.url.toUri())
  }

  @Test fun proxySelectorReturnsMultipleProxies() {
    val address = factory.newAddress()
    proxySelector.proxies.add(proxyA)
    proxySelector.proxies.add(proxyB)
    val routeSelector = newRouteSelector(address)
    proxySelector.assertRequests(address.url.toUri())

    // First try the IP addresses of the first proxy, in sequence.
    assertThat(routeSelector.hasNext()).isTrue()
    dns[PROXY_A_HOST] = dns.allocate(2)
    val selection1 = routeSelector.next()
    assertRoute(selection1.next(), address, proxyA, dns[PROXY_A_HOST][0], PROXY_A_PORT)
    assertRoute(selection1.next(), address, proxyA, dns[PROXY_A_HOST][1], PROXY_A_PORT)
    dns.assertRequests(PROXY_A_HOST)
    assertThat(selection1.hasNext()).isFalse()

    // Next try the IP address of the second proxy.
    assertThat(routeSelector.hasNext()).isTrue()
    dns[PROXY_B_HOST] = dns.allocate(1)
    val selection2 = routeSelector.next()
    assertRoute(selection2.next(), address, proxyB, dns[PROXY_B_HOST][0], PROXY_B_PORT)
    dns.assertRequests(PROXY_B_HOST)
    assertThat(selection2.hasNext()).isFalse()

    // No more proxies to try.
    assertThat(routeSelector.hasNext()).isFalse()
  }

  @Test fun proxySelectorDirectConnectionsAreSkipped() {
    val address = factory.newAddress()
    proxySelector.proxies.add(Proxy.NO_PROXY)
    val routeSelector = newRouteSelector(address)
    proxySelector.assertRequests(address.url.toUri())

    // Only the origin server will be attempted.
    assertThat(routeSelector.hasNext()).isTrue()
    dns[uriHost] = dns.allocate(1)
    val selection = routeSelector.next()
    assertRoute(selection.next(), address, Proxy.NO_PROXY, dns[uriHost][0], uriPort)
    dns.assertRequests(uriHost)
    assertThat(selection.hasNext()).isFalse()
    assertThat(routeSelector.hasNext()).isFalse()
  }

  @Test fun proxyDnsFailureContinuesToNextProxy() {
    val address = factory.newAddress()
    proxySelector.proxies.add(proxyA)
    proxySelector.proxies.add(proxyB)
    proxySelector.proxies.add(proxyA)
    val routeSelector = newRouteSelector(address)
    proxySelector.assertRequests(address.url.toUri())
    assertThat(routeSelector.hasNext()).isTrue()
    dns[PROXY_A_HOST] = dns.allocate(1)
    val selection1 = routeSelector.next()
    assertRoute(selection1.next(), address, proxyA, dns[PROXY_A_HOST][0], PROXY_A_PORT)
    dns.assertRequests(PROXY_A_HOST)
    assertThat(selection1.hasNext()).isFalse()
    assertThat(routeSelector.hasNext()).isTrue()
    dns.clear(PROXY_B_HOST)
    assertFailsWith<UnknownHostException> {
      routeSelector.next()
    }
    dns.assertRequests(PROXY_B_HOST)
    assertThat(routeSelector.hasNext()).isTrue()
    dns[PROXY_A_HOST] = dns.allocate(1)
    val selection2 = routeSelector.next()
    assertRoute(selection2.next(), address, proxyA, dns[PROXY_A_HOST][0], PROXY_A_PORT)
    dns.assertRequests(PROXY_A_HOST)
    assertThat(selection2.hasNext()).isFalse()
    assertThat(routeSelector.hasNext()).isFalse()
  }

  @Test fun multipleProxiesMultipleInetAddressesMultipleConfigurations() {
    val address = factory.newHttpsAddress()
    proxySelector.proxies.add(proxyA)
    proxySelector.proxies.add(proxyB)
    val routeSelector = newRouteSelector(address)

    // Proxy A
    dns[PROXY_A_HOST] = dns.allocate(2)
    val selection1 = routeSelector.next()
    assertRoute(selection1.next(), address, proxyA, dns[PROXY_A_HOST][0], PROXY_A_PORT)
    dns.assertRequests(PROXY_A_HOST)
    assertRoute(selection1.next(), address, proxyA, dns[PROXY_A_HOST][1], PROXY_A_PORT)
    assertThat(selection1.hasNext()).isFalse()

    // Proxy B
    dns[PROXY_B_HOST] = dns.allocate(2)
    val selection2 = routeSelector.next()
    assertRoute(selection2.next(), address, proxyB, dns[PROXY_B_HOST][0], PROXY_B_PORT)
    dns.assertRequests(PROXY_B_HOST)
    assertRoute(selection2.next(), address, proxyB, dns[PROXY_B_HOST][1], PROXY_B_PORT)
    assertThat(selection2.hasNext()).isFalse()

    // No more proxies to attempt.
    assertThat(routeSelector.hasNext()).isFalse()
  }

  @Test fun failedRouteWithSingleProxy() {
    val address = factory.newHttpsAddress()
    var routeSelector = newRouteSelector(address)
    val numberOfAddresses = 2
    dns[uriHost] = dns.allocate(numberOfAddresses)

    // Extract the regular sequence of routes from selector.
    val selection1 = routeSelector.next()
    val regularRoutes = selection1.routes

    // Check that we do indeed have more than one route.
    assertThat(regularRoutes.size).isEqualTo(numberOfAddresses)
    // Add first regular route as failed.
    routeDatabase.failed(regularRoutes[0])
    // Reset selector
    routeSelector = newRouteSelector(address)

    // The first selection prioritizes the non-failed routes.
    val selection2 = routeSelector.next()
    assertThat(selection2.next()).isEqualTo(regularRoutes[1])
    assertThat(selection2.hasNext()).isFalse()

    // The second selection will contain all failed routes.
    val selection3 = routeSelector.next()
    assertThat(selection3.next()).isEqualTo(regularRoutes[0])
    assertThat(selection3.hasNext()).isFalse()
    assertThat(routeSelector.hasNext()).isFalse()
  }

  @Test fun failedRouteWithMultipleProxies() {
    val address = factory.newHttpsAddress()
    proxySelector.proxies.add(proxyA)
    proxySelector.proxies.add(proxyB)
    var routeSelector = newRouteSelector(address)
    dns[PROXY_A_HOST] = dns.allocate(1)
    dns[PROXY_B_HOST] = dns.allocate(1)

    // Mark the ProxyA route as failed.
    val selection = routeSelector.next()
    dns.assertRequests(PROXY_A_HOST)
    val route = selection.next()
    assertRoute(route, address, proxyA, dns[PROXY_A_HOST][0], PROXY_A_PORT)
    routeDatabase.failed(route)
    routeSelector = newRouteSelector(address)

    // Confirm we enumerate both proxies, giving preference to the route from ProxyB.
    val selection2 = routeSelector.next()
    dns.assertRequests(PROXY_A_HOST, PROXY_B_HOST)
    assertRoute(selection2.next(), address, proxyB, dns[PROXY_B_HOST][0], PROXY_B_PORT)
    assertThat(selection2.hasNext()).isFalse()

    // Confirm the last selection contains the postponed route from ProxyA.
    val selection3 = routeSelector.next()
    dns.assertRequests()
    assertRoute(selection3.next(), address, proxyA, dns[PROXY_A_HOST][0], PROXY_A_PORT)
    assertThat(selection3.hasNext()).isFalse()
    assertThat(routeSelector.hasNext()).isFalse()
  }

  @Test fun queryForAllSelectedRoutes() {
    val address = factory.newAddress()
    val routeSelector = newRouteSelector(address)
    dns[uriHost] = dns.allocate(2)
    val selection = routeSelector.next()
    dns.assertRequests(uriHost)
    val routes = selection.routes
    assertRoute(routes[0], address, Proxy.NO_PROXY, dns[uriHost][0], uriPort)
    assertRoute(routes[1], address, Proxy.NO_PROXY, dns[uriHost][1], uriPort)
    assertThat(selection.next()).isSameInstanceAs(routes[0])
    assertThat(selection.next()).isSameInstanceAs(routes[1])
    assertThat(selection.hasNext()).isFalse()
    assertThat(routeSelector.hasNext()).isFalse()
  }

  @Test fun addressesNotSortedWhenFastFallbackIsOff() {
    val address =
      factory.newAddress(
        proxy = Proxy.NO_PROXY,
      )
    val routeSelector =
      newRouteSelector(
        address = address,
        fastFallback = false,
      )
    assertThat(routeSelector.hasNext()).isTrue()
    val (ipv6_1, ipv6_2) = dns.allocateIpv6(2)
    val (ipv4_1, ipv4_2) = dns.allocate(2)
    dns[uriHost] = listOf(ipv6_1, ipv6_2, ipv4_1, ipv4_2)

    val selection = routeSelector.next()
    assertThat(selection.routes.map { it.socketAddress.address }).containsExactly(
      ipv6_1,
      ipv6_2,
      ipv4_1,
      ipv4_2,
    )
  }

  @Test fun addressesSortedWhenFastFallbackIsOn() {
    val address =
      factory.newAddress(
        proxy = Proxy.NO_PROXY,
      )
    val routeSelector =
      newRouteSelector(
        address = address,
        fastFallback = true,
      )
    assertThat(routeSelector.hasNext()).isTrue()
    val (ipv6_1, ipv6_2) = dns.allocateIpv6(2)
    val (ipv4_1, ipv4_2) = dns.allocate(2)
    dns[uriHost] = listOf(ipv6_1, ipv6_2, ipv4_1, ipv4_2)

    val selection = routeSelector.next()
    assertThat(selection.routes.map { it.socketAddress.address }).containsExactly(
      ipv6_1,
      ipv4_1,
      ipv6_2,
      ipv4_2,
    )
  }

  @Test fun getHostString() {
    // Name proxy specification.
    var socketAddress = InetSocketAddress.createUnresolved("host", 1234)
    assertThat(socketAddress.socketHost).isEqualTo("host")
    socketAddress = InetSocketAddress.createUnresolved("127.0.0.1", 1234)
    assertThat(socketAddress.socketHost).isEqualTo("127.0.0.1")

    // InetAddress proxy specification.
    socketAddress = InetSocketAddress(InetAddress.getByName("localhost"), 1234)
    assertThat(socketAddress.socketHost).isEqualTo("127.0.0.1")
    socketAddress = InetSocketAddress(InetAddress.getByAddress(byteArrayOf(127, 0, 0, 1)), 1234)
    assertThat(socketAddress.socketHost).isEqualTo("127.0.0.1")
    socketAddress =
      InetSocketAddress(
        InetAddress.getByAddress("foobar", byteArrayOf(127, 0, 0, 1)),
        1234,
      )
    assertThat(socketAddress.socketHost).isEqualTo("127.0.0.1")
  }

  @Test fun routeToString() {
    val ipv4Address =
      InetAddress.getByAddress(
        byteArrayOf(1, 2, 3, 4),
      )
    assertThat(
      Route(
        factory.newAddress(uriHost = "1.2.3.4", uriPort = 1003),
        Proxy.NO_PROXY,
        InetSocketAddress(ipv4Address, 1003),
      ).toString(),
    ).isEqualTo("1.2.3.4:1003")
    assertThat(
      Route(
        factory.newAddress(uriHost = "example.com", uriPort = 1003),
        Proxy.NO_PROXY,
        InetSocketAddress(ipv4Address, 1003),
      ).toString(),
    ).isEqualTo("example.com at 1.2.3.4:1003")
    assertThat(
      Route(
        factory.newAddress(uriHost = "example.com", uriPort = 1003),
        Proxy(Proxy.Type.HTTP, InetSocketAddress.createUnresolved("proxy.example.com", 1003)),
        InetSocketAddress(ipv4Address, 1003),
      ).toString(),
    ).isEqualTo("example.com via proxy 1.2.3.4:1003")
    assertThat(
      Route(
        factory.newAddress(uriHost = "example.com", uriPort = 1003),
        Proxy(Proxy.Type.HTTP, InetSocketAddress.createUnresolved("proxy.example.com", 1003)),
        InetSocketAddress(ipv4Address, 5678),
      ).toString(),
    ).isEqualTo("example.com:1003 via proxy 1.2.3.4:5678")
    assertThat(
      Route(
        factory.newAddress(uriHost = "1.2.3.4", uriPort = 1003),
        Proxy.NO_PROXY,
        InetSocketAddress(ipv4Address, 1003),
        "I am an ECH config".encodeUtf8(),
      ).toString(),
    ).isEqualTo("1.2.3.4:1003 with ECH")
  }

  @Test fun routeToStringIpv6() {
    val ipv6Address =
      InetAddress.getByAddress(
        byteArrayOf(0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 1),
      )
    assertThat(
      Route(
        factory.newAddress(uriHost = "::1", uriPort = 1003),
        Proxy.NO_PROXY,
        InetSocketAddress(ipv6Address, uriPort),
      ).toString(),
    ).isEqualTo("[::1]:1003")
    assertThat(
      Route(
        factory.newAddress(uriHost = "example.com", uriPort = 1003),
        Proxy.NO_PROXY,
        InetSocketAddress(ipv6Address, uriPort),
      ).toString(),
    ).isEqualTo("example.com at [::1]:1003")
    assertThat(
      Route(
        factory.newAddress(uriHost = "example.com", uriPort = 1003),
        Proxy(Proxy.Type.HTTP, InetSocketAddress.createUnresolved("proxy.example.com", 1003)),
        InetSocketAddress(ipv6Address, 5678),
      ).toString(),
    ).isEqualTo("example.com:1003 via proxy [::1]:5678")
    assertThat(
      Route(
        factory.newAddress(uriHost = "::2", uriPort = 1003),
        Proxy(Proxy.Type.HTTP, InetSocketAddress.createUnresolved("proxy.example.com", 1003)),
        InetSocketAddress(ipv6Address, 5678),
      ).toString(),
    ).isEqualTo("[::2]:1003 via proxy [::1]:5678")
  }

  private fun assertRoute(
    route: Route,
    address: Address,
    proxy: Proxy,
    socketAddress: InetAddress,
    socketPort: Int,
    echConfigList: ByteString? = null,
  ) {
    assertThat(route.address).isEqualTo(address)
    assertThat(route.proxy).isEqualTo(proxy)
    assertThat(route.socketAddress.address).isEqualTo(socketAddress)
    assertThat(route.socketAddress.port).isEqualTo(socketPort)
    assertThat(route.echConfigList).isEqualTo(echConfigList)
  }

  private fun newRouteSelector(
    address: Address,
    routeDatabase: RouteDatabase = this.routeDatabase,
    fastFallback: Boolean = false,
    call: RealCall = this.call,
  ): RouteSelector =
    RouteSelector(
      address = address,
      routeDatabase = routeDatabase,
      fastFallback = fastFallback,
      call = call,
    )

  companion object {
    private const val PROXY_A_PORT = 1001
    private const val PROXY_A_HOST = "proxya"
    private val proxyA =
      Proxy(
        Proxy.Type.HTTP,
        InetSocketAddress.createUnresolved(PROXY_A_HOST, PROXY_A_PORT),
      )

    private const val PROXY_B_PORT = 1002
    private const val PROXY_B_HOST = "proxyb"
    private val proxyB =
      Proxy(
        Proxy.Type.HTTP,
        InetSocketAddress.createUnresolved(PROXY_B_HOST, PROXY_B_PORT),
      )
  }
}
