/*
 * Copyright (C) 2019 Square, Inc.
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
package okhttp3

import java.io.IOException
import okhttp3.Interceptor.Chain
import okio.Buffer
import okio.BufferedSink
import okio.ForwardingSink
import okio.Sink
import okio.buffer

/** Rewrites the request body sent to the server to be all uppercase.  */
class UppercaseRequestInterceptor : Interceptor {
  @Throws(IOException::class)
  override fun intercept(chain: Chain): Response = chain.proceed(uppercaseRequest(chain.request()))

  /** Returns a request that transforms `request` to be all uppercase.  */
  private fun uppercaseRequest(request: Request): Request {
    val body = request.body ?: return request
    val uppercaseBody: RequestBody =
      object : ForwardingRequestBody(delegate = body) {
        @Throws(IOException::class)
        override fun writeTo(sink: BufferedSink) {
          delegate().writeTo(uppercaseSink(sink).buffer())
        }
      }
    return request.newBuilder().method(request.method, uppercaseBody).build()
  }

  private fun uppercaseSink(sink: Sink): Sink =
    object : ForwardingSink(sink) {
      @Throws(IOException::class)
      override fun write(
        source: Buffer,
        byteCount: Long,
      ) {
        val bytes = source.readByteString(byteCount)
        delegate.write(
          Buffer()
            .write(bytes.toAsciiUppercase()),
          byteCount,
        )
      }
    }
}
