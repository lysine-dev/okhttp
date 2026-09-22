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
package okhttp3.internal.dns

import java.net.InetAddress
import java.net.UnknownHostException
import java.util.concurrent.BlockingQueue
import java.util.concurrent.LinkedBlockingDeque
import okhttp3.Dns
import okhttp3.dnsoverhttps.DnsOverHttps
import okhttp3.internal.concurrent.TaskRunner
import okio.IOException

sealed interface DnsEvent {
  data class Records(
    val last: Boolean,
    val records: List<Dns.Record>,
  ) : DnsEvent

  data class Failure(
    val e: IOException,
  ) : DnsEvent
}

fun Dns.Call.toEventsQueue(): BlockingQueue<DnsEvent> {
  val result = LinkedBlockingDeque<DnsEvent>()

  enqueue(
    object : Dns.Callback {
      override fun onRecords(
        call: Dns.Call,
        last: Boolean,
        records: List<Dns.Record>,
      ) {
        result.put(DnsEvent.Records(last, records))
      }

      override fun onFailure(
        call: Dns.Call,
        e: IOException,
      ) {
        result.put(DnsEvent.Failure(e))
      }
    },
  )

  return result
}

/**
 * Use Burst to call either the blocking API or the non-blocking API, both with a signature that
 * resembles the [InetAddress.getAllByName] API.
 */
operator fun DnsOverHttps.invoke(
  entryPoint: EntryPoint,
  hostname: String,
): List<InetAddress> =
  when (entryPoint) {
    EntryPoint.Lookup -> {
      lookup(hostname)
    }

    EntryPoint.NewCall -> {
      buildList {
        val dnsEvents = newCall(Dns.Request(hostname)).toEventsQueue()
        while (true) {
          when (val dnsEvent = dnsEvents.take()) {
            is DnsEvent.Failure -> {
              throw dnsEvent.e
            }

            is DnsEvent.Records -> {
              addAll(
                dnsEvent.records
                  .filterIsInstance<Dns.Record.IpAddress>()
                  .map { it.address },
              )

              if (dnsEvent.last) {
                if (isEmpty()) throw UnknownHostException("no results")
                break
              }
            }
          }
        }
      }
    }
  }

/**
 * Force this instance to use the lookup API or ([LookupDnsCall]), or the call API (and definitely
 * not [LookupDnsCall]). This is for tests that want to defeat OkHttp's implementation detection,
 * so we can exercise all code paths.
 */
fun Dns.forceEntryPoint(entryPoint: EntryPoint): Dns {
  return when (entryPoint) {
    EntryPoint.Lookup -> {
      object : Dns by this {
        override fun newCall(request: Dns.Request) = LookupDnsCall(TaskRunner.INSTANCE, this, request)
      }
    }

    EntryPoint.NewCall -> {
      object : Dns by this {
        override fun newCall(request: Dns.Request): Dns.Call {
          val result = this@forceEntryPoint.newCall(request)
          check(result !is LookupDnsCall) {
            "unexpected call implementation on ${this@forceEntryPoint}"
          }
          return result
        }
      }
    }
  }
}

enum class EntryPoint {
  Lookup,
  NewCall,
}
