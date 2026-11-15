/*
 * Copyright (C) 2013 Square, Inc.
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

import java.net.InetSocketAddress
import java.net.Proxy
import okhttp3.internal.toCanonicalHost
import okio.ByteString

/**
 * The concrete route used by a connection to reach an abstract origin server. When creating a
 * connection the client has many options:
 *
 *  * **HTTP proxy:** a proxy server may be explicitly configured for the client. Otherwise, the
 *    [proxy selector][java.net.ProxySelector] is used. It may return multiple proxies to attempt.
 *  * **IP address:** whether connecting directly to an origin server or a proxy, opening a socket
 *    requires an IP address. The DNS server may return multiple IP addresses to attempt.
 *
 * Each route is a specific selection of these options.
 */
class Route(
  @get:JvmName("address") val address: Address,
  /**
   * Returns the [Proxy] of this route.
   *
   * **Warning:** This may disagree with [Address.proxy] when it is null. When the address's proxy
   * is null, the proxy selector is used.
   */
  @get:JvmName("proxy") val proxy: Proxy,
  @get:JvmName("socketAddress") val socketAddress: InetSocketAddress,
  @get:JvmName("echConfigList") val echConfigList: ByteString?,
) {
  constructor(
    address: Address,
    proxy: Proxy,
    socketAddress: InetSocketAddress,
  ) : this(address, proxy, socketAddress, null)

  @JvmName("-deprecated_address")
  @Deprecated(
    message = "moved to val",
    replaceWith = ReplaceWith(expression = "address"),
    level = DeprecationLevel.ERROR,
  )
  fun address(): Address = address

  @JvmName("-deprecated_proxy")
  @Deprecated(
    message = "moved to val",
    replaceWith = ReplaceWith(expression = "proxy"),
    level = DeprecationLevel.ERROR,
  )
  fun proxy(): Proxy = proxy

  @JvmName("-deprecated_socketAddress")
  @Deprecated(
    message = "moved to val",
    replaceWith = ReplaceWith(expression = "socketAddress"),
    level = DeprecationLevel.ERROR,
  )
  fun socketAddress(): InetSocketAddress = socketAddress

  /**
   * Returns true if this route tunnels HTTPS or HTTP/2 through an HTTP proxy.
   * See [RFC 2817, Section 5.2][rfc_2817].
   *
   * [rfc_2817]: http://www.ietf.org/rfc/rfc2817.txt
   */
  fun requiresTunnel(): Boolean {
    if (proxy.type() != Proxy.Type.HTTP) return false
    return (address.sslSocketFactory != null) ||
      (Protocol.H2_PRIOR_KNOWLEDGE in address.protocols)
  }

  override fun equals(other: Any?): Boolean =
    other is Route &&
      other.address == address &&
      other.proxy == proxy &&
      other.socketAddress == socketAddress &&
      other.echConfigList == echConfigList

  override fun hashCode(): Int {
    var result = 17
    result = 31 * result + address.hashCode()
    result = 31 * result + proxy.hashCode()
    result = 31 * result + socketAddress.hashCode()
    result = 31 * result + echConfigList.hashCode()
    return result
  }

  /**
   * Returns a string with the URL hostname, socket IP address, and socket port, like one of these:
   *
   *  * `example.com:80 at 1.2.3.4:8888`
   *  * `example.com:443 via proxy [::1]:8888`
   *
   * This omits duplicate information when possible.
   */
  override fun toString(): String =
    buildString {
      val addressHostname = address.url.host // Already in canonical form.
      val socketHostname = socketAddress.address?.hostAddress?.toCanonicalHost()

      when {
        ':' in addressHostname -> append("[").append(addressHostname).append("]")
        else -> append(addressHostname)
      }
      if (address.url.port != socketAddress.port || addressHostname == socketHostname) {
        append(":")
        append(address.url.port)
      }

      if (addressHostname != socketHostname) {
        when (proxy) {
          Proxy.NO_PROXY -> append(" at ")
          else -> append(" via proxy ")
        }

        when {
          socketHostname == null -> append("<unresolved>")
          ':' in socketHostname -> append("[").append(socketHostname).append("]")
          else -> append(socketHostname)
        }
        append(":")
        append(socketAddress.port)
      }

      if (echConfigList != null) {
        append(" with ECH")
      }
    }
}
