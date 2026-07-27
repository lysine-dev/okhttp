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

import java.net.InetAddress
import okhttp3.internal.OkHttpInternalApi
import okio.ByteString

@OkHttpInternalApi
data class DnsMessage(
  val id: Short,
  val flags: Int,
  val questions: List<Question>,
  val answers: List<ResourceRecord> = listOf(),
  val authorityRecords: List<ResourceRecord> = listOf(),
  val additionalRecords: List<ResourceRecord> = listOf(),
) {
  val responseCode: Int
    get() = (flags and 0b0000_0000_0000_1111)

  companion object {
    fun query(question: Question): DnsMessage {
      //     QR = 0 (Query)
      //     RD = 1 (Recursion Desired)
      // OPCODE = 0 (standard query)
      //           QR OPCODE AA TC RD RA   Z RCODE
      val flags = 0b0___0000__0__0__1__0_000__0000
      return DnsMessage(
        id = 0,
        flags = flags,
        questions = listOf(question),
      )
    }

    fun response(
      id: Short = 0,
      questions: List<Question>,
      answers: List<ResourceRecord>,
    ): DnsMessage {
      //     QR = 1 (Response)
      // OPCODE = 0 (standard query)
      //  RCODE = 0 (success)
      //           QR OPCODE AA TC RD RA   Z RCODE
      val flags = 0b1___0000__0__0__0__0_000__0000

      return DnsMessage(
        id = id,
        flags = flags,
        questions = questions,
        answers = answers,
      )
    }
  }
}

@OkHttpInternalApi
data class Question(
  val name: String,
  val type: Int,
  val `class`: Int = CLASS_IN,
)

@OkHttpInternalApi
sealed interface ResourceRecord {
  val name: String
  val timeToLive: Int

  data class IpAddress(
    override val name: String,
    override val timeToLive: Int,
    val address: InetAddress,
  ) : ResourceRecord

  data class Https(
    override val name: String,
    override val timeToLive: Int,
    val priority: Int = 1,
    val targetName: String = "",
    val alpnIds: List<String>? = null,
    var port: Int = 443,
    val ipAddressHints: List<InetAddress> = listOf(),
    val echConfigList: ByteString? = null,
  ) : ResourceRecord
}

/** https://www.iana.org/assignments/dns-parameters/dns-parameters.xhtml#dns-parameters-4 */
@OkHttpInternalApi
const val TYPE_A = 1

@OkHttpInternalApi
const val TYPE_AAAA = 28

@OkHttpInternalApi
const val TYPE_HTTPS = 65

/** https://www.iana.org/assignments/dns-parameters/dns-parameters.xhtml#dns-parameters-2 */
@OkHttpInternalApi
const val CLASS_IN = 1

/** https://www.iana.org/assignments/dns-parameters/dns-parameters.xhtml#dns-parameters-6 */
internal const val RESPONSE_CODE_SUCCESS = 0
internal const val RESPONSE_CODE_SERVER_FAILURE = 2

/** https://www.iana.org/assignments/dns-svcb/dns-svcb.xhtml */
internal const val SERVICE_PARAMETER_MANDATORY = 0
internal const val SERVICE_PARAMETER_ALPN = 1
internal const val SERVICE_PARAMETER_NO_DEFAULT_ALPN = 2
internal const val SERVICE_PARAMETER_PORT = 3
internal const val SERVICE_PARAMETER_IPV4_HINT = 4
internal const val SERVICE_PARAMETER_ECH = 5
internal const val SERVICE_PARAMETER_IPV6_HINT = 6
