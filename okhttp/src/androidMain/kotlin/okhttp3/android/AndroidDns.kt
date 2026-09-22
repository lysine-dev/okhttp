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
import android.net.DnsResolver
import android.net.Network
import android.os.Build
import android.os.CancellationSignal
import android.security.NetworkSecurityPolicy
import android.security.NetworkSecurityPolicy.DOMAIN_ENCRYPTION_MODE_ENABLED
import android.security.NetworkSecurityPolicy.DOMAIN_ENCRYPTION_MODE_OPPORTUNISTIC
import androidx.annotation.RequiresApi
import java.io.IOException
import java.net.InetAddress
import java.net.UnknownHostException
import java.util.concurrent.Executor
import okhttp3.Dns
import okhttp3.DnsCache
import okhttp3.internal.OkHttpInternalApi
import okhttp3.internal.SuppressSignatureCheck
import okhttp3.internal.concurrent.TaskRunner
import okhttp3.internal.dns.DnsMessage
import okhttp3.internal.dns.DnsMessageReader
import okhttp3.internal.dns.DnsQuery
import okhttp3.internal.dns.Question
import okhttp3.internal.dns.ResourceRecord
import okhttp3.internal.dns.StateMachineDnsCall
import okhttp3.internal.dns.TYPE_A
import okhttp3.internal.dns.TYPE_HTTPS
import okhttp3.internal.dns.execute
import okio.Buffer

/**
 * A [Dns] backed by Android's system resolver, with Encrypted Client Hello (ECH) support from
 * Android's [DnsResolver].
 *
 * IP addresses come from the system resolver — [InetAddress.getAllByName] or [Network.getAllByName]
 * when [network] is non-null. It makes an optional `HTTPS` query for service metadata such as ECH.
 */
@RequiresApi(29)
@SuppressSignatureCheck
class AndroidDns internal constructor(
  internal val dnsResolver: DnsResolver,
  internal val network: Network?,
  dnsCache: DnsCache,
  internal val includeServiceMetadata: Boolean,
  /** Runs inline; the executor only hands off DnsResolver's callbacks. */
  internal val executor: Executor,
  private val taskRunner: TaskRunner,
  lazyNetworkSecurityPolicy: Lazy<NetworkSecurityPolicy>,
) : Dns {
  constructor(
    dnsResolver: DnsResolver = DnsResolver.getInstance(),
    network: Network? = null,
    dnsCache: DnsCache = DnsCache(),
    /**
     * True to also query the `HTTPS` record for service metadata. Keep this on: it enables privacy
     * features such as Encrypted Client Hello (ECH) for the HTTPS call. Set it to false only when
     * you want to disable ECH.
     */
    includeServiceMetadata: Boolean = true,
  ) : this(
    dnsResolver = dnsResolver,
    network = network,
    dnsCache = dnsCache,
    includeServiceMetadata = includeServiceMetadata,
    executor = Executor { it.run() },
    taskRunner = TaskRunner.INSTANCE,
    lazyNetworkSecurityPolicy = lazy { NetworkSecurityPolicy.getInstance() },
  )

  private val networkSecurityPolicy: NetworkSecurityPolicy by lazyNetworkSecurityPolicy

  /** Drives [StateMachineDnsCall] using the system resolver and [DnsResolver]. */
  private val queryFactory =
    dnsCache.`-delegate`.wrap { question ->
      AndroidQuery(question)
    }

  /**
   * Resolves addresses only, for callers using the legacy blocking API. [Dns] cannot carry
   * HTTPS/ECH metadata, so this skips that query rather than paying for it and discarding it.
   */
  override fun lookup(hostname: String): List<InetAddress> =
    call(Dns.Request(hostname), includeServiceMetadata = false)
      .execute()
      .filterIsInstance<Dns.Record.IpAddress>()
      .map { it.address }

  override fun newCall(request: Dns.Request): Dns.Call = call(request, includeServiceMetadata = includeServiceMetadata)

  private fun call(
    request: Dns.Request,
    includeServiceMetadata: Boolean,
  ) = StateMachineDnsCall(
    taskRunner = taskRunner,
    request = request,
    queryFactory = queryFactory,
    // A single `A` query stands in for both families: the system resolver returns IPv4 and
    // IPv6 addresses together, so there's no separate `AAAA` query.
    includeIPv6 = false,
    includeServiceMetadata = includeServiceMetadata && includeServiceMetadataForRequest(request),
  )

  /**
   * Returns true to fetch HTTPS DNS records. Fetching these records requires a network round trip,
   * so we only fetch them if they'll be useful.
   *
   * The main user of these records is Encrypted Client Hello (ECH). Android's HTTPS stack only
   * supports ECH on API 37+, and only if configured via [NetworkSecurityPolicy]. If both are true,
   * we fetch the ECH records.
   */
  private fun includeServiceMetadataForRequest(request: Dns.Request): Boolean {
    if (Build.VERSION.SDK_INT < 37) return false

    return when (networkSecurityPolicy.getDomainEncryptionMode(request.hostname)) {
      DOMAIN_ENCRYPTION_MODE_ENABLED, DOMAIN_ENCRYPTION_MODE_OPPORTUNISTIC -> true
      else -> false
    }
  }

  /** One outstanding transport-layer query. */
  private inner class AndroidQuery(
    private val question: Question,
  ) : DnsQuery {
    /** Only the `HTTPS` query reaches [DnsResolver], which is the API that takes a signal. */
    private val cancellationSignal = if (question.type == TYPE_HTTPS) CancellationSignal() else null

    override fun enqueue(callback: DnsQuery.Callback) {
      when (question.type) {
        TYPE_A -> resolveAddresses(callback)

        TYPE_HTTPS -> queryServiceMetadata(callback)

        // AndroidDns only ever issues `A` and `HTTPS` queries (includeIPv6 = false, so no `AAAA`).
        else -> error("unexpected query type ${question.type}")
      }
    }

    /**
     * Resolves IP addresses through the system resolver. This is a blocking call, so it runs on a
     * [TaskRunner] thread. The addresses are wrapped in a synthetic [DnsMessage] because that's
     * the only shape [StateMachineDnsCall] accepts — the system resolver gives us decoded
     * addresses rather than a wire-format message.
     */
    private fun resolveAddresses(callback: DnsQuery.Callback) {
      taskRunner.newQueue().execute("${question.name} dns", cancelable = false) {
        try {
          val addresses =
            network?.getAllByName(question.name)
              ?: InetAddress.getAllByName(question.name)
          callback.onResponse(response(addresses))
        } catch (e: UnknownHostException) {
          callback.onFailure(e)
        }
      }
    }

    /**
     * Wraps system-resolved [addresses] in a successful [DnsMessage] so they can flow through
     * [DnsQuery.Callback], which only accepts wire-format responses.
     */
    private fun response(addresses: Array<InetAddress>) =
      DnsMessage.response(
        questions = listOf(question),
        answers =
          addresses.map { address ->
            ResourceRecord.IpAddress(
              name = question.name,
              timeToLive = 0,
              address = address,
            )
          },
      )

    /**
     * Asks [DnsResolver] for the `HTTPS` record. The platform has no typed API for this below
     * API 36, so we request the raw message and decode it with OkHttp's own [DnsMessageReader] —
     * the same decoder `DnsOverHttps` uses.
     *
     * Failures are reported to the [DnsQuery.Callback] rather than swallowed, so callers can tell
     * an absent `HTTPS` record from a query that errored.
     *
     * `WrongConstant` is suppressed because OkHttp's [TYPE_HTTPS] is the DNS wire value (65), the
     * same as the platform's `DnsResolver.TYPE_HTTPS`. We use ours so the query stays valid on
     * API 29+; `DnsResolver.TYPE_HTTPS` was only added in API 37.
     */
    @SuppressLint("WrongConstant")
    @Suppress("ktlint:standard:comment-wrapping")
    private fun queryServiceMetadata(callback: DnsQuery.Callback) {
      val dnsResolverCallback =
        object : DnsResolver.Callback<ByteArray> {
          override fun onAnswer(
            answer: ByteArray,
            rcode: Int,
          ) {
            val message =
              try {
                DnsMessageReader(Buffer().write(answer)).read()
              } catch (e: IOException) {
                return callback.onFailure(e)
              }
            // The state machine turns a non-success rcode into a failure of its own.
            callback.onResponse(message)
          }

          override fun onError(e: DnsResolver.DnsException) {
            callback.onFailure(IOException("HTTPS query failed with code ${e.code}", e))
          }
        }

      try {
        dnsResolver.rawQuery(
          /* network = */ network,
          /* domain = */ question.name,
          /* nsClass = */ DnsResolver.CLASS_IN,
          /* nsType = */ TYPE_HTTPS,
          /* flags = */ DnsResolver.FLAG_EMPTY,
          /* executor = */ executor,
          /* cancellationSignal = */ cancellationSignal,
          /* callback = */ dnsResolverCallback,
        )
      } catch (e: SecurityException) {
        // The app lacks INTERNET permission, or network policy forbids the query. The callback
        // won't run, so report the failure here or the state machine never terminates.
        taskRunner.newQueue().execute("${question.name} dns", cancelable = false) {
          callback.onFailure(IOException(e))
        }
      }
    }

    /**
     * Cancels a query. Only the `HTTPS` query reaches [DnsResolver]; the address lookup runs to
     * completion and its result is discarded by [StateMachineDnsCall], which ignores callbacks
     * once canceled.
     */
    override fun cancel() {
      cancellationSignal?.cancel()
    }
  }
}
