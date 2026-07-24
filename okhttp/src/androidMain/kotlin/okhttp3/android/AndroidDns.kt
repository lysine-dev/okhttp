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

import android.net.DnsResolver
import android.net.Network
import android.os.CancellationSignal
import androidx.annotation.RequiresApi
import java.io.IOException
import java.net.InetAddress
import java.net.UnknownHostException
import java.util.concurrent.Executor
import okhttp3.Dns
import okhttp3.internal.OkHttpInternalApi
import okhttp3.internal.SuppressSignatureCheck
import okhttp3.internal.concurrent.Task
import okhttp3.internal.concurrent.TaskRunner
import okhttp3.internal.dns.DnsMessage
import okhttp3.internal.dns.DnsMessageReader
import okhttp3.internal.dns.Question
import okhttp3.internal.dns.ResourceRecord
import okhttp3.internal.dns.StateMachineDnsCall
import okhttp3.internal.dns.TYPE_A
import okhttp3.internal.dns.TYPE_HTTPS
import okhttp3.internal.dns.execute
import okio.Buffer

/**
 * A [Dns] backed by Android's system resolver, with ECH support from Android's [DnsResolver].
 *
 * IP addresses come from the system resolver — [InetAddress.getAllByName], or
 * [Network.getAllByName] when a [network] is set. This drives OkHttp's [StateMachineDnsCall] as a
 * transport: a single `A` query stands in for the blocking address lookup (which returns both
 * address families), plus an optional `HTTPS` query for service metadata such as ECH.
 */
@RequiresApi(29)
@SuppressSignatureCheck
class AndroidDns
  @JvmOverloads
  constructor(
    private val dnsResolver: DnsResolver = DnsResolver.getInstance(),
    private val network: Network? = null,
    /**
     * True to also query the `HTTPS` record for service metadata such as ECH. Set this to false to
     * save a query when only IP addresses are needed.
     */
    private val includeServiceMetadata: Boolean = true,
    // Runs inline; the executor only hands off DnsResolver's callbacks.
    private val executor: Executor = Executor { it.run() },
  ) : Dns,
    StateMachineDnsCall.Transport<AndroidDns.Query> {
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
    ): Dns.Call =
      StateMachineDnsCall(
        request = request,
        transport = this,
        canceledException = null,
        // A single `A` query stands in for both families: the system resolver returns IPv4 and
        // IPv6 addresses together, so there's no separate `AAAA` query.
        includeIPv6 = false,
        includeServiceMetadata = includeServiceMetadata,
      )

    override fun newQuery(question: Question) = Query(question)

    override fun enqueue(
      query: Query,
      callback: StateMachineDnsCall.Transport.Callback<Query>,
    ) {
      when (query.type) {
        TYPE_A -> resolveAddresses(query.hostname, callback)
        else -> queryServiceMetadata(query, callback)
      }
    }

    /**
     * Cancels a query. Only the `HTTPS` query reaches [DnsResolver]; the address lookup runs to
     * completion and its result is discarded by [StateMachineDnsCall], which ignores callbacks once
     * canceled.
     */
    override fun cancel(query: Query) {
      query.cancellationSignal?.cancel()
    }

    /**
     * Resolves IP addresses through the system resolver. This is a blocking call, so it runs on a
     * [TaskRunner] thread. The addresses are wrapped in a synthetic [DnsMessage] because that's the
     * only shape [StateMachineDnsCall] accepts — the system resolver gives us decoded addresses
     * rather than a wire-format message.
     */
    private fun resolveAddresses(
      hostname: String,
      callback: StateMachineDnsCall.Transport.Callback<Query>,
    ) {
      TaskRunner.INSTANCE.newQueue().schedule(
        object : Task("$hostname address lookup", cancelable = false) {
          override fun runOnce(): Long {
            try {
              val addresses =
                when (network) {
                  null -> InetAddress.getAllByName(hostname)
                  else -> network.getAllByName(hostname)
                }
              callback.onResponse(addresses.toDnsMessage(hostname))
            } catch (e: UnknownHostException) {
              callback.onFailure(e)
            }
            return -1L
          }
        },
      )
    }

    /**
     * Asks [DnsResolver] for the `HTTPS` record. The platform has no typed API for this below
     * API 36, so we request the raw message and decode it with OkHttp's own [DnsMessageReader] —
     * the same decoder `DnsOverHttps` uses.
     *
     * Failures are reported to the [StateMachineDnsCall] rather than swallowed, so callers can tell
     * an absent `HTTPS` record from a query that errored. Any addresses already resolved are still
     * delivered first.
     */
    @Suppress("ktlint:standard:comment-wrapping")
    private fun queryServiceMetadata(
      query: Query,
      callback: StateMachineDnsCall.Transport.Callback<Query>,
    ) {
      val queryCallback =
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
          /* domain = */ query.hostname,
          /* nsClass = */ DnsResolver.CLASS_IN,
          /* nsType = */ TYPE_HTTPS,
          /* flags = */ DnsResolver.FLAG_EMPTY,
          /* executor = */ executor,
          /* cancellationSignal = */ query.cancellationSignal,
          /* callback = */ queryCallback,
        )
      } catch (e: SecurityException) {
        // The app lacks INTERNET permission, or network policy forbids the query. The callback
        // won't run, so report the failure here or the state machine never terminates.
        callback.onFailure(IOException(e))
      }
    }

    /** One outstanding transport-layer query. */
    class Query(
      question: Question,
    ) {
      val hostname: String = question.name
      val type: Int = question.type

      /** Only the `HTTPS` query reaches [DnsResolver], which is the API that takes a signal. */
      val cancellationSignal = if (type == TYPE_A) null else CancellationSignal()
    }
  }

/**
 * Wraps system-resolved [addresses] in a successful [DnsMessage] so they can flow through
 * [StateMachineDnsCall], which only accepts wire-format responses.
 */
private fun Array<InetAddress>.toDnsMessage(hostname: String): DnsMessage =
  DnsMessage(
    id = 0,
    // QR = 1 (Response), RCODE = 0 (Success). The state machine only reads the response code.
    flags = 0b1___0000__0__0__1__0_000__0000,
    questions = listOf(Question(hostname, TYPE_A)),
    answers = map { ResourceRecord.IpAddress(name = hostname, timeToLive = 0, address = it) },
  )
