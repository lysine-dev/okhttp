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

package okhttp3.dnsoverhttps.internal

import java.io.IOException
import java.net.ProtocolException
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.Response
import okhttp3.dnsoverhttps.DnsOverHttps.Companion.DNS_MESSAGE
import okhttp3.dnsoverhttps.DnsOverHttps.Companion.MAX_RESPONSE_SIZE
import okhttp3.internal.OkHttpInternalApi
import okhttp3.internal.dns.DnsMessage
import okhttp3.internal.dns.DnsMessageReader
import okhttp3.internal.dns.DnsMessageWriter
import okhttp3.internal.dns.DnsQuery
import okhttp3.internal.dns.Question
import okhttp3.internal.platform.Platform
import okio.Buffer
import okio.BufferedSink

@OkHttpInternalApi
internal class DnsOverHttpsQuery(
  val call: Call,
) : DnsQuery {
  override fun enqueue(callback: DnsQuery.Callback) {
    call.enqueue(
      object : Callback {
        override fun onFailure(
          call: Call,
          e: IOException,
        ) {
          callback.onFailure(e)
        }

        override fun onResponse(
          call: Call,
          response: Response,
        ) {
          val dnsMessage =
            try {
              decodeResponse(response)
            } catch (e: IOException) {
              return callback.onFailure(e)
            }

          callback.onResponse(dnsMessage)
        }
      },
    )
  }

  override fun cancel() {
    call.cancel()
  }

  class Factory(
    private val client: OkHttpClient,
    private val dnsUrl: HttpUrl,
    private val post: Boolean,
  ) : DnsQuery.Factory {
    override fun newQuery(question: Question): DnsQuery {
      val dnsMessage = DnsMessage.query(question)
      return DnsOverHttpsQuery(
        call =
          client.newCall(
            request =
              Request
                .Builder()
                .header("Accept", DNS_MESSAGE.toString())
                .apply {
                  if (post) {
                    url(dnsUrl)
                    cacheUrlOverride(
                      dnsUrl
                        .newBuilder()
                        .addQueryParameter("hostname", question.name)
                        .addQueryParameter("type", question.type.toString())
                        .build(),
                    )
                    post(QueryRequestBody(dnsMessage))
                  } else {
                    val requestUrl =
                      dnsUrl
                        .newBuilder()
                        .addQueryParameter("dns", dnsMessage.asQueryParameter())
                        .build()
                    url(requestUrl)
                  }
                }.build(),
          ),
      )
    }
  }
}

internal fun DnsMessage.asQueryParameter(): String {
  val buffer = Buffer()
  DnsMessageWriter(buffer).write(this@asQueryParameter)
  return buffer.readByteString().base64Url(includePadding = false)
}

internal class QueryRequestBody(
  private val query: DnsMessage,
) : RequestBody() {
  override fun contentType() = DNS_MESSAGE

  override fun writeTo(sink: BufferedSink) {
    DnsMessageWriter(sink.buffer).write(query)
    sink.emitCompleteSegments()
  }
}

@Throws(IOException::class)
internal fun decodeResponse(response: Response): DnsMessage {
  if (
    response.cacheResponse == null &&
    response.protocol !== Protocol.HTTP_2 &&
    response.protocol !== Protocol.QUIC
  ) {
    Platform.get().log("Unexpected protocol: ${response.protocol}", Platform.WARN)
  }

  response.use {
    if (!response.isSuccessful) {
      throw IOException("response: ${response.code} ${response.message}")
    }

    val body = response.body
    if (body.contentLength() > MAX_RESPONSE_SIZE) {
      throw ProtocolException(
        "response size exceeds limit ($MAX_RESPONSE_SIZE bytes): ${body.contentLength()} bytes",
      )
    }

    return DnsMessageReader(body.source()).read()
  }
}
