/*
 * Copyright (C) 2012 The Android Open Source Project
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
package okhttp3.internal.http

import java.io.IOException
import okhttp3.Headers
import okhttp3.Request
import okhttp3.Response
import okhttp3.Route
import okhttp3.internal.connection.RealCall
import okio.Sink
import okio.Socket
import okio.Source

/** Encodes HTTP requests and decodes HTTP responses. */
interface ExchangeCodec {
  /** The connection or CONNECT tunnel that owns this codec. */
  val carrier: Carrier

  /** Returns true if the response body and (possibly empty) trailers have been received. */
  val isResponseComplete: Boolean

  /** The socket that carries this exchange. */
  val socket: Socket

  /** Returns an output stream where the request body can be streamed. */
  @Throws(IOException::class)
  fun createRequestBody(
    request: Request,
    contentLength: Long,
  ): Sink

  /** This should update the HTTP engine's sentRequestMillis field. */
  @Throws(IOException::class)
  fun writeRequestHeaders(request: Request)

  /** Flush the request to the underlying socket. */
  @Throws(IOException::class)
  fun flushRequest()

  /** Flush the request to the underlying socket and signal no more bytes will be transmitted. */
  @Throws(IOException::class)
  fun finishRequest()

  /**
   * Parses bytes of a response header from an HTTP transport.
   *
   * @param expectContinue true to return null if this is an intermediate response with a "100"
   * response code. Otherwise this method never returns null.
   */
  @Throws(IOException::class)
  fun readResponseHeaders(expectContinue: Boolean): Response.Builder?

  @Throws(IOException::class)
  fun reportedContentLength(response: Response): Long

  @Throws(IOException::class)
  fun openResponseBodySource(response: Response): Source

  /** Returns the trailers after the HTTP response if they're ready. May be empty. */
  @Throws(IOException::class)
  fun peekTrailers(): Headers?

  /**
   * Cancel this stream. Resources held by this stream will be cleaned up, though not synchronously.
   * That may happen later by the connection pool thread.
   */
  fun cancel()

  /**
   * Carries an exchange. This is usually a connection, but it could also be a connect plan for
   * CONNECT tunnels. Note that CONNECT tunnels are significantly less capable than connections.
   */
  interface Carrier {
    val route: Route

    fun trackFailure(
      call: RealCall,
      e: IOException?,
    )

    fun prohibitNewExchanges()

    fun cancel()
  }

  companion object {
    /**
     * The timeout to use while discarding a stream of input data. Since this is used for connection
     * reuse, this timeout should be significantly less than the time it takes to establish a new
     * connection.
     */
    const val DISCARD_STREAM_TIMEOUT_MILLIS = 100
  }
}
