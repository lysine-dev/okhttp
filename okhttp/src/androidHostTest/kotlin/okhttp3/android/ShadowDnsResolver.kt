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
package okhttp3.android

import android.net.DnsResolver
import android.net.Network
import android.os.CancellationSignal
import java.util.concurrent.Executor
import okhttp3.FakeDns
import okhttp3.internal.SuppressSignatureCheck
import okhttp3.internal.dns.DnsMessage
import okhttp3.internal.dns.DnsMessageWriter
import okhttp3.internal.dns.Question
import okio.Buffer
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.shadow.api.Shadow

@SuppressSignatureCheck
@Implements(DnsResolver::class)
class ShadowDnsResolver {
  lateinit var dns: FakeDns

  @Implementation
  fun rawQuery(
    network: Network?,
    domain: String,
    nsClass: Int,
    nsType: Int,
    flags: Int,
    executor: Executor,
    cancellationSignal: CancellationSignal?,
    callback: DnsResolver.Callback<ByteArray>,
  ) {
    val response =
      dns.query(
        DnsMessage(
          id = 0,
          flags = flags,
          questions =
            listOf(
              Question(
                name = domain,
                type = nsType,
                `class` = nsClass,
              ),
            ),
        ),
      )

    val responseBytes =
      Buffer().run {
        DnsMessageWriter(this).write(response)
        readByteArray()
      }

    executor.execute {
      callback.onAnswer(responseBytes, response.responseCode)
    }
  }

  companion object {
    fun create(dns: FakeDns): DnsResolver {
      val result = Shadow.newInstanceOf(DnsResolver::class.java)
      val shadow = Shadow.extract<ShadowDnsResolver>(result)
      shadow.dns = dns
      return result
    }
  }
}
