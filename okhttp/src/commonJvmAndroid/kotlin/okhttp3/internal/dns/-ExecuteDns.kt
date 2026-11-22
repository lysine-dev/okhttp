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
@file:Suppress("ktlint:standard:filename")

package okhttp3.internal.dns

import java.io.IOException
import java.net.UnknownHostException
import java.util.concurrent.LinkedBlockingQueue
import okhttp3.Dns
import okhttp3.internal.OkHttpInternalApi

/**
 * Call our asynchronous API synchronously.
 *
 * This returns normally if at least one [Dns.Record.IpAddress] is returned. Partial failures are
 * silently ignored.
 *
 * This is intended to be transitional only; doing it this way needlessly blocks the caller's
 * thread.
 */
@OkHttpInternalApi
@Throws(UnknownHostException::class)
fun Dns.Call.execute(): List<Dns.Record> {
  val queue = LinkedBlockingQueue<Result<List<Dns.Record>>>()

  enqueue(
    object : Dns.Callback {
      val allRecords = mutableListOf<Dns.Record>()

      override fun onRecords(
        call: Dns.Call,
        last: Boolean,
        records: List<Dns.Record>,
      ) {
        allRecords += records
        if (last) {
          complete()
        }
      }

      override fun onFailure(
        call: Dns.Call,
        e: IOException,
      ) {
        complete(e)
      }

      private fun complete(e: IOException? = null) {
        val result =
          when {
            allRecords.any { it is Dns.Record.IpAddress } -> {
              Result.success(allRecords)
            }

            else -> {
              Result.failure(
                e ?: UnknownHostException("DNS returned no addresses for ${request.hostname}"),
              )
            }
          }
        queue.put(result)
      }
    },
  )

  return queue.take().getOrThrow()
}
