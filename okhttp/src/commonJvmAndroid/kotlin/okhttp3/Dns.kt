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
@file:OptIn(OkHttpInternalApi::class)

package okhttp3

import java.io.IOException
import java.net.InetAddress
import java.net.UnknownHostException
import okhttp3.internal.OkHttpInternalApi
import okhttp3.internal.concurrent.TaskRunner
import okhttp3.internal.dns.LookupDnsCall
import okhttp3.internal.platform.Platform
import okhttp3.internal.toCanonicalHost
import okio.ByteString

/**
 * Loads IP addresses and service metadata for a hostname.
 *
 * This interface is exclusively concerned with collecting the information necessary to establish
 * new HTTP and HTTPS connections. Typical implementations will read `A` (IPv4), `AAAA` (IPv6) and
 * `HTTPS` (service metadata) records only. Use a different API to read other record types, or to
 * write DNS records.
 *
 * [Dns.SYSTEM] is the default implementation.
 *
 * Implementations of this interface must be safe for concurrent use.
 */
fun interface Dns {
  /**
   * Returns the IP addresses of `hostname`.
   *
   * If a connection to an address fails, OkHttp will retry the connection with the next address
   * until either a connection is made, the set of IP addresses is exhausted, or a limit is
   * exceeded.
   */
  @Throws(UnknownHostException::class)
  fun lookup(hostname: String): List<InetAddress>

  /** Recursively resolve [request] to learn IP addresses and service metadata to connect. */
  fun newCall(request: Request): Call = LookupDnsCall(TaskRunner.INSTANCE, this, request)

  interface Call {
    val request: Request

    fun enqueue(callback: Callback)

    /** This does nothing if the call is already complete. */
    fun cancel()

    fun isCanceled(): Boolean
  }

  /**
   * Receives a stream of records from the DNS server.
   *
   * Calls to [onRecords] and [onFailure] may be invoked by different threads, but never in
   * parallel: a call will not be made until the prior call has returned.
   */
  interface Callback {
    /**
     * @param last true if this is the last list of records for this address. That is a terminal
     *   event and no further calls to this callback will be made for this call.
     * @param records a possibly-empty set of records received from the name server.
     */
    fun onRecords(
      call: Call,
      last: Boolean,
      records: List<Record>,
    )

    /**
     * This is a terminal event and no further calls to this callback will be made for this call.
     */
    fun onFailure(
      call: Call,
      e: IOException,
    )
  }

  class Request
    @JvmOverloads
    constructor(
      hostname: String,
      port: Int = -1,
    ) {
      /**
       * The host from a URL like `www.publicobject.com` or `ietf.org`. It may also be a
       * Punycode-encoded name like `xn--n3h.net`.
       *
       * Hostnames must satisfy the following constraints:
       *
       *  * All characters must be printable ASCII characters.
       *  * These characters are forbidden: `#`, `%`, `/`, `:`, `?`, `@`, `[`, `\`, and `]`.
       *  * The string length must be in [1..253].
       *  * After splitting the string into `.`-separated labels, each label must be in [1..63]. A
       *    single trailing `.` is permitted.
       */
      @get:JvmName("hostname")
      val hostname: String =
        hostname.toCanonicalHost()
          ?: throw IllegalArgumentException("unexpected hostname: $hostname")

      /**
       * The port to query for record types that support it.
       *
       * In an HTTPS DNS query, the port and hostname are combined like `_8443.api.example.com`. The
       * port segment is omitted if it is 443. This scheme is called
       * [AttrLeaf](https://www.rfc-editor.org/info/rfc8552/).
       */
      @get:JvmName("port")
      val port: Int =
        when (port) {
          -1 -> 443
          in 1..65535 -> port
          else -> throw IllegalArgumentException("unexpected port: $port")
        }

      override fun equals(other: Any?) = other is Request && other.hostname == hostname && other.port == port

      override fun hashCode() = (31 * hostname.hashCode()) + port

      override fun toString(): String =
        when (port) {
          443 -> hostname
          else -> "$hostname:$port"
        }
    }

  sealed class Record private constructor() {
    /**
     * The hostname that this record applies to.
     *
     * A single hostname (`api.example.com`) may be served by multiple supporting servers. For
     * example, this is useful for geographic distribution (`us-east.api.example.com` and
     * `eu-central.api.example.com`), and for change management (`blue.api.example.com`,
     * `green.api.example.com`).
     *
     * This field will be different from [Request.hostname] if it is served by alternate hostname,
     * as above. This will be that original hostname if no alternate hostnames are in use, or if the
     * underlying DNS resolver doesn't expose that data.
     */
    abstract val hostname: String

    /** An IPv4 or IPv6 address for this host. */
    class IpAddress(
      hostname: String,
      @get:JvmName("address") val address: InetAddress,
    ) : Record() {
      @get:JvmName("hostname")
      override val hostname: String =
        hostname.toCanonicalHost()
          ?: throw IllegalArgumentException("unexpected hostname: $hostname")

      override fun equals(other: Any?) = other is IpAddress && other.hostname == hostname && other.address == address

      override fun hashCode() = (31 * hostname.hashCode()) + address.hashCode()

      override fun toString() = "$hostname/${address.hostAddress}"
    }

    /**
     * Advice from the hostname owner on how to connect to maximize compatibility and security.
     *
     * Each [ServiceMetadata] record may apply to all supporting servers, or to a subset of them. If
     * multiple servers are in use (as in the `us-east.api.example.com`
     * [example][Record.hostname]), those hostnames must be themselves resolved to get the IP
     * addresses to connect to. If [ipAddressHints] is non-empty, those addresses are available
     * without to an additional DNS round trip. (The additional round trip is still useful for
     * completeness.)
     *
     * If this record is present on the DNS results for an `http://` request, the client should
     * simulate a 307 redirect to the equivalent `https://` URL. From RFC 9460, part 9.5:
     *
     *    If an HTTPS RR query for this "https" URL re RRs or any compatible ServiceMode HTTPS RRs,
     *    the client SHOULD behave as if it has received an HTTP 307 (Temporary Redirect) status code
     *    with this "https" URL in the "Location" field.
     */
    class ServiceMetadata(
      hostname: String,
      /**
       * The protocols supported by this server. When an input URL's hostname is served by multiple
       * servers, use this to select a server that supports the client's available protocols.
       *
       * This value is created by composing two service params. For the "http" protocol, this takes
       * the `HTTPS` record's `alpn` value and then add the default [Protocol.HTTP_1_1] unless
       * `no-default-alpn` is present.
       *
       * If the service returns an unrecognized [Protocol], that element is discarded.
       */
      alpnIds: List<Protocol>? = null,
      /**
       * The socket should connect to this port, even if the URL has a different port. This will be
       * [Request.port] unless an override is specified.
       */
      port: Int = -1,
      /**
       * The IP of the servers known to support this record. If empty, assume all records with the
       * same [hostname] also support this configuration.
       */
      ipAddressHints: List<InetAddress> = listOf(),
      /**
       * The Encrypted Client Hello (ECH) data. This is encoded according to RFC 9848 and RFC 9849.
       */
      @get:JvmName("echConfigList")
      val echConfigList: ByteString? = null,
    ) : Record() {
      @get:JvmName("hostname")
      override val hostname: String =
        hostname.toCanonicalHost()
          ?: throw IllegalArgumentException("unexpected hostname: $hostname")

      @get:JvmName("port")
      val port: Int =
        when (port) {
          -1 -> 443
          in 1..65535 -> port
          else -> throw IllegalArgumentException("unexpected port: $port")
        }

      @get:JvmName("alpnIds")
      val alpnIds: List<Protocol>? = alpnIds?.toList() // Defensive copy.

      @get:JvmName("ipAddressHints")
      val ipAddressHints: List<InetAddress> = ipAddressHints.toList() // Defensive copy.

      override fun equals(other: Any?): Boolean =
        other is ServiceMetadata &&
          other.hostname == hostname &&
          other.alpnIds == alpnIds &&
          other.port == port &&
          other.ipAddressHints == ipAddressHints &&
          other.echConfigList == echConfigList

      override fun hashCode(): Int {
        var result = 17
        result = 31 * result + hostname.hashCode()
        result = 31 * result + alpnIds.hashCode()
        result = 31 * result + port
        result = 31 * result + ipAddressHints.hashCode()
        result = 31 * result + echConfigList.hashCode()
        return result
      }

      override fun toString(): String =
        buildString(32) {
          append("ServiceMetadata{")
          append(hostname)
          if (alpnIds != null) {
            append(", alpnIds=")
            append(alpnIds)
          }
          if (port != 443) {
            append(", port=")
            append(port)
          }
          if (ipAddressHints.isNotEmpty()) {
            append(", ipAddressHints=[")
            append(ipAddressHints.joinToString { it.hostAddress })
            append("]")
          }
          if (echConfigList != null) {
            append(", echConfigList=")
            append(echConfigList.hex())
          }
          append("}")
        }
    }
  }

  companion object {
    /**
     * The current process's host DNS service.
     *
     * Most custom [Dns] implementations should delegate to this instance.
     */
    @JvmField
    val SYSTEM: Dns =
      object : Dns {
        override fun lookup(hostname: String) = Platform.get().systemDns.lookup(hostname)

        override fun newCall(request: Request) = Platform.get().systemDns.newCall(request)

        override fun toString() = "Dns.SYSTEM"
      }
  }
}
