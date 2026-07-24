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
@file:Suppress("ktlint:standard:filename")

package okhttp3.internal.dns

import java.io.IOException
import java.net.UnknownHostException
import java.util.concurrent.atomic.AtomicReference
import okhttp3.Dns
import okhttp3.Protocol
import okhttp3.internal.OkHttpInternalApi

/**
 * An application-layer [Dns.Call] that performs multiple transport-layer [DnsQuery]s in parallel.
 * This delegates to a query factory for the transport, like UDP or DNS over HTTPS.
 *
 * Concurrency
 * -----------
 *
 * A few things conspire to make concurrency tricky:
 *
 *  * Each transport-layer [DnsQuery.Callback]s are executed in parallel.
 *  * Application layer [Dns.Callback]s must be serialized.
 *  * We don't want to use locks to guard access to [Dns.Callback] functions.
 *
 * Each time we receive data for the callback (in the form of records or an exception), we either
 * immediately call the callback with that data (on a dispatcher thread), or queue it for the thread
 * that's busy calling the callback.
 *
 * After calling a callback, the caller must check to see if there's more data queued to deliver,
 * and deliver that also.
 *
 * The potentially surprising outcome of this strategy is the thread that performed the `TYPE_AAAA`
 * DNS request may also deliver the `TYPE_A` records to the callback, or vice versa.
 *
 * If a thread is intending to call the callback, it sets [State.Running.lockHeld] to true while
 * that call is executing.
 */
@OkHttpInternalApi
class StateMachineDnsCall(
  override val request: Dns.Request,
  private val queryFactory: DnsQuery.Factory,
  private val canceledException: IOException?,
  private val includeIPv6: Boolean,
  private val includeServiceMetadata: Boolean,
) : Dns.Call {
  private val state = AtomicReference<State>(State.Idle())

  override fun isCanceled() = state.get().canceled

  override fun enqueue(callback: Dns.Callback) {
    val questions =
      buildList {
        if (includeServiceMetadata) {
          add(Question(request.hostname, TYPE_HTTPS))
        }
        if (includeIPv6) {
          add(Question(request.hostname, TYPE_AAAA))
        }
        add(Question(request.hostname, TYPE_A))
      }

    val queries =
      questions.map { question ->
        queryFactory.newQuery(question)
      }

    while (true) {
      val previous =
        state.get() as? State.Idle
          ?: error("already enqueued")

      val next =
        State.Running(
          canceled = previous.canceled,
          callback = callback,
          runningQueries = queries,
        )

      if (!state.compareAndSet(previous, next)) continue // Lost a race, retry.

      for (query in queries) {
        if (previous.canceled || canceledException != null) {
          query.cancel()
        }

        query.enqueue(
          callback =
            object : DnsQuery.Callback {
              override fun onResponse(dnsResponse: DnsMessage) {
                updateStateAndCallCallbacks(
                  completedQuery = query,
                  dnsResponse = dnsResponse,
                )
              }

              override fun onFailure(e: IOException) {
                updateStateAndCallCallbacks(
                  completedQuery = query,
                  newException = e,
                )
              }
            },
        )
      }

      return
    }
  }

  override fun cancel() {
    while (true) {
      val previous = state.get()
      val next = previous.cancel()
      if (!state.compareAndSet(previous, next)) continue // Lost a race, retry.

      if (previous is State.Running) {
        for (query in previous.runningQueries) {
          query.cancel()
        }
      }
      return
    }
  }

  private fun updateStateAndCallCallbacks(
    completedQuery: DnsQuery,
    dnsResponse: DnsMessage,
  ) {
    val resourceRecords =
      try {
        when (dnsResponse.responseCode) {
          RESPONSE_CODE_SUCCESS -> dnsResponse.answers
          RESPONSE_CODE_SERVER_FAILURE -> throw UnknownHostException("DNS server failure")
          else -> throw UnknownHostException()
        }
      } catch (e: IOException) {
        return updateStateAndCallCallbacks(
          completedQuery = completedQuery,
          newException = e,
        )
      }

    val dnsRecords =
      resourceRecords.map { resourceRecord ->
        when (resourceRecord) {
          is ResourceRecord.Https -> {
            Dns.Record.ServiceMetadata(
              hostname = resourceRecord.targetName.takeIf { it != "" } ?: request.hostname,
              alpnIds =
                resourceRecord.alpnIds?.mapNotNull { alpnId ->
                  try {
                    Protocol.get(alpnId)
                  } catch (_: IOException) {
                    null // Skip unrecognized ALPN ID.
                  }
                },
              port = resourceRecord.port,
              ipAddressHints = resourceRecord.ipAddressHints,
              echConfigList = resourceRecord.echConfigList,
            )
          }

          is ResourceRecord.IpAddress -> {
            Dns.Record.IpAddress(
              hostname = request.hostname,
              address = resourceRecord.address,
            )
          }
        }
      }

    updateStateAndCallCallbacks(
      completedQuery = completedQuery,
      newRecords = dnsRecords,
    )
  }

  private tailrec fun updateStateAndCallCallbacks(
    completedQuery: DnsQuery? = null,
    newRecords: List<Dns.Record> = listOf(),
    newException: IOException? = null,
    lockHeldByThisThread: Boolean = false,
  ) {
    while (true) {
      val previous =
        state.get() as? State.Running
          ?: return // Already complete or canceled; nothing to do.

      val newRunningQueries =
        when {
          completedQuery != null -> previous.runningQueries - completedQuery
          else -> previous.runningQueries
        }

      val allExceptions =
        when {
          canceledException != null -> listOf(canceledException)
          newException != null -> previous.pendingExceptions + newException
          else -> previous.pendingExceptions
        }

      val allRecords =
        when {
          newRecords.isNotEmpty() -> previous.pendingRecords + newRecords
          else -> previous.pendingRecords
        }

      val last = newRunningQueries.isEmpty()
      val lockHeldByAnotherThread = !lockHeldByThisThread && previous.lockHeld

      // There's a few reasons why we might not call any callbacks:
      //  - There's no more records or failures to emit immediately
      //  - Another thread is already calling the callbacks.
      // In such cases, hand off any new work to that other thread and be done.
      if ((!last && allRecords.isEmpty()) || lockHeldByAnotherThread) {
        val next =
          State.Running(
            canceled = previous.canceled,
            callback = previous.callback,
            runningQueries = newRunningQueries,
            lockHeld = lockHeldByAnotherThread,
            pendingRecords = allRecords,
            pendingExceptions = allExceptions,
          )
        if (!state.compareAndSet(previous, next)) continue // Lost a race, retry.
        return
      }

      // We need to call a callback. Take the lock and the records.
      val next =
        when {
          last -> {
            State.Complete(previous.canceled)
          }

          else -> {
            State.Running(
              canceled = previous.canceled,
              callback = previous.callback,
              runningQueries = newRunningQueries,
              lockHeld = true,
              pendingRecords = listOf(),
              pendingExceptions = allExceptions,
            )
          }
        }
      if (!state.compareAndSet(previous, next)) continue // Lost a race, retry.

      val lastAndNoExceptions = last && allExceptions.isEmpty()
      if (allRecords.isNotEmpty() || lastAndNoExceptions) {
        previous.callback.onRecords(
          call = this,
          last = lastAndNoExceptions,
          records = allRecords,
        )
      }

      if (last && allExceptions.isNotEmpty()) {
        previous.callback.onFailure(
          call = this,
          exceptions = allExceptions,
        )
      }

      // Success, attempt to release the held lock. This might also process more events if some
      // were enqueued while the callback was executing.
      return updateStateAndCallCallbacks(
        lockHeldByThisThread = true,
      )
    }
  }

  private sealed interface State {
    val canceled: Boolean

    class Idle(
      override val canceled: Boolean = false,
    ) : State {
      override fun cancel() = Idle(canceled = true)
    }

    class Running(
      override val canceled: Boolean,
      val callback: Dns.Callback,
      val lockHeld: Boolean = false,
      val runningQueries: List<DnsQuery>,
      val pendingRecords: List<Dns.Record> = listOf(),
      val pendingExceptions: List<IOException> = listOf(),
    ) : State {
      init {
        check(pendingRecords.isEmpty() || lockHeld)
      }

      override fun cancel() =
        Running(
          canceled = true,
          callback = callback,
          lockHeld = lockHeld,
          runningQueries = runningQueries,
          pendingRecords = pendingRecords,
          pendingExceptions = pendingExceptions,
        )
    }

    class Complete(
      override val canceled: Boolean,
    ) : State {
      override fun cancel() = Idle(canceled = true)
    }

    fun cancel(): State
  }
}

internal fun Dns.Callback.onFailure(
  call: Dns.Call,
  exceptions: List<IOException>,
) {
  val firstException = exceptions.first()
  for (i in 1 until exceptions.size) {
    firstException.addSuppressed(exceptions[i])
  }

  onFailure(call, firstException)
}
