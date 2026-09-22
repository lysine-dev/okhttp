/*
 * Copyright (C) 2018 Square, Inc.
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
package okhttp3.dnsoverhttps

import java.net.InetAddress
import java.net.UnknownHostException
import okhttp3.Dns
import okhttp3.DnsCache
import okhttp3.HttpUrl
import okhttp3.MediaType
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.dnsoverhttps.internal.DnsOverHttpsQuery
import okhttp3.internal.concurrent.TaskRunner
import okhttp3.internal.dns.StateMachineDnsCall
import okhttp3.internal.dns.execute
import okhttp3.internal.publicsuffix.PublicSuffixDatabase

/**
 * [DNS over HTTPS implementation][doh_spec].
 *
 * > A DNS API client encodes a single DNS query into an HTTP request
 * > using either the HTTP GET or POST method and the other requirements
 * > of this section.  The DNS API server defines the URI used by the
 * > request through the use of a URI Template.
 *
 * [doh_spec]: https://tools.ietf.org/html/draft-ietf-doh-dns-over-https-13
 */
class DnsOverHttps internal constructor(
  private val taskRunner: TaskRunner,
  @get:JvmName("client") val client: OkHttpClient,
  @get:JvmName("url") val url: HttpUrl,
  @get:JvmName("cache") val cache: DnsCache,
  @get:JvmName("includeIPv6") val includeIPv6: Boolean,
  @get:JvmName("includeServiceMetadata") val includeServiceMetadata: Boolean,
  @get:JvmName("post") val post: Boolean,
  @get:JvmName("resolvePrivateAddresses") val resolvePrivateAddresses: Boolean,
  @get:JvmName("resolvePublicAddresses") val resolvePublicAddresses: Boolean,
) : Dns {
  private val queryFactory =
    cache.`-delegate`.wrap(
      DnsOverHttpsQuery.Factory(
        taskRunner = taskRunner,
        resolvePrivateAddresses = resolvePrivateAddresses,
        resolvePublicAddresses = resolvePublicAddresses,
        client = client,
        dnsUrl = url,
        post = post,
      ),
    )

  override fun newCall(request: Dns.Request): Dns.Call =
    StateMachineDnsCall(
      taskRunner = taskRunner,
      request = request,
      queryFactory = queryFactory,
      includeIPv6 = includeIPv6,
      includeServiceMetadata = includeServiceMetadata,
    )

  @Throws(UnknownHostException::class)
  override fun lookup(hostname: String): List<InetAddress> {
    val withoutServiceMetadata =
      DnsOverHttps(
        taskRunner = taskRunner,
        client = client,
        url = url,
        cache = cache,
        includeIPv6 = includeIPv6,
        includeServiceMetadata = false,
        post = post,
        resolvePrivateAddresses = resolvePrivateAddresses,
        resolvePublicAddresses = resolvePublicAddresses,
      )
    val call = withoutServiceMetadata.newCall(Dns.Request(hostname))
    val records = call.execute()
    return records
      .filterIsInstance<Dns.Record.IpAddress>()
      .map { it.address }
  }

  class Builder {
    internal val taskRunner = TaskRunner.INSTANCE
    internal var client: OkHttpClient? = null
    internal var url: HttpUrl? = null
    internal var cache: DnsCache = DnsCache()
    internal var includeIPv6 = true
    internal var includeServiceMetadata = true
    internal var post = false
    internal var systemDns = Dns.SYSTEM
    internal var bootstrapDnsHosts: List<InetAddress>? = null
    internal var resolvePrivateAddresses = false
    internal var resolvePublicAddresses = true

    fun build(): DnsOverHttps {
      val client = this.client ?: throw NullPointerException("client not set")
      return DnsOverHttps(
        taskRunner = taskRunner,
        client = client.newBuilder().dns(buildBootstrapClient(this)).build(),
        url = checkNotNull(url) { "url not set" },
        cache = cache,
        includeIPv6 = includeIPv6,
        includeServiceMetadata = includeServiceMetadata,
        post = post,
        resolvePrivateAddresses = resolvePrivateAddresses,
        resolvePublicAddresses = resolvePublicAddresses,
      )
    }

    fun client(client: OkHttpClient) =
      apply {
        this.client = client
      }

    fun url(url: HttpUrl) =
      apply {
        this.url = url
      }

    fun cache(cache: DnsCache) =
      apply {
        this.cache = cache
      }

    /**
     * True to request [`HTTPS` DNS records](https://datatracker.ietf.org/doc/rfc9460/), which are
     * necessary for [Encrypted Client Hello (ECH)](https://datatracker.ietf.org/doc/rfc9849/).
     */
    fun includeServiceMetadata(includeServiceMetadata: Boolean) =
      apply {
        this.includeServiceMetadata = includeServiceMetadata
      }

    fun includeIPv6(includeIPv6: Boolean) =
      apply {
        this.includeIPv6 = includeIPv6
      }

    fun post(post: Boolean) =
      apply {
        this.post = post
      }

    fun resolvePrivateAddresses(resolvePrivateAddresses: Boolean) =
      apply {
        this.resolvePrivateAddresses = resolvePrivateAddresses
      }

    fun resolvePublicAddresses(resolvePublicAddresses: Boolean) =
      apply {
        this.resolvePublicAddresses = resolvePublicAddresses
      }

    fun bootstrapDnsHosts(bootstrapDnsHosts: List<InetAddress>?) =
      apply {
        this.bootstrapDnsHosts = bootstrapDnsHosts
      }

    fun bootstrapDnsHosts(vararg bootstrapDnsHosts: InetAddress): Builder = bootstrapDnsHosts(bootstrapDnsHosts.toList())

    fun systemDns(systemDns: Dns) =
      apply {
        this.systemDns = systemDns
      }
  }

  companion object {
    val DNS_MESSAGE: MediaType = "application/dns-message".toMediaType()
    const val MAX_RESPONSE_SIZE = 64 * 1024

    private fun buildBootstrapClient(builder: Builder): Dns {
      val hosts = builder.bootstrapDnsHosts

      return if (hosts != null) {
        BootstrapDns(builder.url!!.host, hosts)
      } else {
        builder.systemDns
      }
    }

    internal fun isPrivateHost(host: String): Boolean = PublicSuffixDatabase.get().getEffectiveTldPlusOne(host) == null
  }
}
