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
import okhttp3.internal.OkHttpInternalApi

/**
 * A transport-layer DNS query for a single record type.
 *
 * This is different from `Dns.Call` which returns multiple record types.
 */
@OkHttpInternalApi
interface DnsQuery {
  fun enqueue(callback: Callback)

  fun cancel()

  interface Callback {
    fun onFailure(e: IOException)

    fun onResponse(dnsResponse: DnsMessage)
  }

  fun interface Factory {
    fun newQuery(question: Question): DnsQuery
  }
}
