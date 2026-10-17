/*
 *  Licensed to the Apache Software Foundation (ASF) under one or more
 *  contributor license agreements.  See the NOTICE file distributed with
 *  this work for additional information regarding copyright ownership.
 *  The ASF licenses this file to You under the Apache License, Version 2.0
 *  (the "License"); you may not use this file except in compliance with
 *  the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */
package okhttp3

import java.io.IOException
import java.net.HttpURLConnection.HTTP_GATEWAY_TIMEOUT
import java.net.HttpURLConnection.HTTP_NOT_MODIFIED
import java.util.concurrent.TimeUnit.MILLISECONDS
import okhttp3.internal.cache.CacheRequest
import okhttp3.internal.cache.CacheStrategy
import okhttp3.internal.closeQuietly
import okhttp3.internal.discard
import okhttp3.internal.http.ExchangeCodec
import okhttp3.internal.http.HttpMethod
import okhttp3.internal.http.RealResponseBody
import okhttp3.internal.http.promisesBody
import okhttp3.internal.stripBody
import okio.Buffer
import okio.Source
import okio.Timeout
import okio.buffer

/**
 * Serves requests from, and writes responses to, the provided [Cache].
 *
 * **IMPORTANT**: you do **NOT** usually need to use this class to benefit from OkHttp caching, as
 * [OkHttpClient] internally calls this interceptor by default, immediately after application
 * interceptors. This class is only useful in narrow, advanced cases, e.g. alongside a
 * short-circuiting application interceptor. This will not apply to most users.
 */
class CacheInterceptor : Interceptor {
  @Throws(IOException::class)
  override fun intercept(chain: Interceptor.Chain): Response {
    val call = chain.call()
    val cache = chain.cache
    val cacheCandidate = cache?.get(chain.request().requestForCache())

    val now = System.currentTimeMillis()

    val strategy = CacheStrategy.Factory(now, chain.request(), cacheCandidate).compute()
    val networkRequest = strategy.networkRequest
    val cacheResponse = strategy.cacheResponse

    cache?.trackResponse(strategy)

    if (cacheCandidate != null && cacheResponse == null) {
      // The cache candidate wasn't applicable. Close it.
      cacheCandidate.body.closeQuietly()
    }

    // If we're forbidden from using the network and the cache is insufficient, fail.
    if (networkRequest == null && cacheResponse == null) {
      return Response
        .Builder()
        .request(chain.request())
        .protocol(Protocol.HTTP_1_1)
        .code(HTTP_GATEWAY_TIMEOUT)
        .message("Unsatisfiable Request (only-if-cached)")
        .sentRequestAtMillis(-1L)
        .receivedResponseAtMillis(System.currentTimeMillis())
        .build()
        .also {
          chain.eventListener.satisfactionFailure(call, it)
        }
    }

    // If we don't need the network, we're done.
    if (networkRequest == null) {
      return cacheResponse!!
        .newBuilder()
        .cacheResponse(cacheResponse.stripBody())
        .build()
        .also {
          chain.eventListener.cacheHit(call, it)
        }
    }

    if (cacheResponse != null) {
      chain.eventListener.cacheConditionalHit(call, cacheResponse)
    } else if (cache != null) {
      chain.eventListener.cacheMiss(call)
    }

    var networkResponse: Response? = null
    try {
      networkResponse = chain.proceed(networkRequest)
    } finally {
      // If we're crashing on I/O or otherwise, don't leak the cache body.
      if (networkResponse == null && cacheCandidate != null) {
        cacheCandidate.body.closeQuietly()
      }
    }

    // If we have a cache response too, then we're doing a conditional get.
    if (cacheResponse != null) {
      if (networkResponse?.code == HTTP_NOT_MODIFIED) {
        val response =
          cacheResponse
            .newBuilder()
            .headers(combine(cacheResponse.headers, networkResponse.headers))
            .sentRequestAtMillis(networkResponse.sentRequestAtMillis)
            .receivedResponseAtMillis(networkResponse.receivedResponseAtMillis)
            .cacheResponse(cacheResponse.stripBody())
            .networkResponse(networkResponse.stripBody())
            .build()

        networkResponse.body.close()

        // Update the cache after combining headers but before stripping the
        // Content-Encoding header (as performed by initContentStream()).
        cache!!.trackConditionalCacheHit()
        cache.update(cacheResponse, response)
        return response.also {
          chain.eventListener.cacheHit(call, it)
        }
      } else {
        cacheResponse.body.closeQuietly()
      }
    }

    val response =
      networkResponse!!
        .newBuilder()
        .cacheResponse(cacheResponse?.stripBody())
        .networkResponse(networkResponse.stripBody())
        .build()

    if (cache != null) {
      val cacheNetworkRequest = networkRequest.requestForCache()

      if (response.promisesBody() && CacheStrategy.isCacheable(response, cacheNetworkRequest)) {
        // Offer this request to the cache.
        val cacheRequest = cache.put(response.newBuilder().request(cacheNetworkRequest).build())
        return cacheWritingResponse(cacheRequest, response).also {
          if (cacheResponse != null) {
            // This will log a conditional cache miss only.
            chain.eventListener.cacheMiss(call)
          }
        }
      }

      if (HttpMethod.invalidatesCache(networkRequest.method)) {
        try {
          cache.remove(networkRequest)
        } catch (_: IOException) {
          // The cache cannot be written.
        }
      }
    }

    return response
  }

  /**
   * Returns a new source that writes bytes to [cacheRequest] as they are read by the source
   * consumer. This is careful to discard bytes left over when the stream is closed; otherwise we
   * may never exhaust the source stream and therefore not complete the cached response.
   */
  @Throws(IOException::class)
  private fun cacheWritingResponse(
    cacheRequest: CacheRequest?,
    response: Response,
  ): Response {
    // Some apps return a null body; for compatibility we treat that like a null cache request.
    if (cacheRequest == null) return response
    val cacheBodyUnbuffered = cacheRequest.body()

    val source = response.body.source()
    val cacheBody = cacheBodyUnbuffered.buffer()

    val cacheWritingSource =
      object : Source {
        private var cacheRequestClosed = false

        @Throws(IOException::class)
        override fun read(
          sink: Buffer,
          byteCount: Long,
        ): Long {
          val bytesRead: Long
          try {
            bytesRead = source.read(sink, byteCount)
          } catch (e: IOException) {
            if (!cacheRequestClosed) {
              cacheRequestClosed = true
              cacheRequest.abort() // Failed to write a complete cache response.
            }
            throw e
          }

          if (bytesRead == -1L) {
            if (!cacheRequestClosed) {
              cacheRequestClosed = true
              cacheBody.close() // The cache response is complete!
            }
            return -1
          }

          sink.copyTo(cacheBody.buffer, sink.size - bytesRead, bytesRead)
          cacheBody.emitCompleteSegments()
          return bytesRead
        }

        override fun timeout(): Timeout = source.timeout()

        @Throws(IOException::class)
        override fun close() {
          if (!cacheRequestClosed &&
            !discard(ExchangeCodec.DISCARD_STREAM_TIMEOUT_MILLIS, MILLISECONDS)
          ) {
            cacheRequestClosed = true
            cacheRequest.abort()
          }
          source.close()
        }
      }

    val contentType = response.header("Content-Type")
    val contentLength = response.body.contentLength()
    return response
      .newBuilder()
      .body(RealResponseBody(contentType, contentLength, cacheWritingSource.buffer()))
      .build()
  }

  /** Combines cached headers with a network headers as defined by RFC 7234, 4.3.4. */
  private fun combine(
    cachedHeaders: Headers,
    networkHeaders: Headers,
  ): Headers {
    val result = Headers.Builder()

    for (index in 0 until cachedHeaders.size) {
      val fieldName = cachedHeaders.name(index)
      val value = cachedHeaders.value(index)
      if ("Warning".equals(fieldName, ignoreCase = true) && value.startsWith("1")) {
        // Drop 100-level freshness warnings.
        continue
      }
      if (isContentSpecificHeader(fieldName) ||
        !isEndToEnd(fieldName) ||
        networkHeaders[fieldName] == null
      ) {
        result.addLenient(fieldName, value)
      }
    }

    for (index in 0 until networkHeaders.size) {
      val fieldName = networkHeaders.name(index)
      if (!isContentSpecificHeader(fieldName) && isEndToEnd(fieldName)) {
        result.addLenient(fieldName, networkHeaders.value(index))
      }
    }

    return result.build()
  }

  /**
   * Returns true if [fieldName] is an end-to-end HTTP header, as defined by RFC 2616,
   * 13.5.1.
   */
  private fun isEndToEnd(fieldName: String): Boolean =
    !"Connection".equals(fieldName, ignoreCase = true) &&
      !"Keep-Alive".equals(fieldName, ignoreCase = true) &&
      !"Proxy-Authenticate".equals(fieldName, ignoreCase = true) &&
      !"Proxy-Authorization".equals(fieldName, ignoreCase = true) &&
      !"TE".equals(fieldName, ignoreCase = true) &&
      !"Trailers".equals(fieldName, ignoreCase = true) &&
      !"Transfer-Encoding".equals(fieldName, ignoreCase = true) &&
      !"Upgrade".equals(fieldName, ignoreCase = true)

  /**
   * Returns true if [fieldName] is content specific and therefore should always be used
   * from cached headers.
   */
  private fun isContentSpecificHeader(fieldName: String): Boolean =
    "Content-Length".equals(fieldName, ignoreCase = true) ||
      "Content-Encoding".equals(fieldName, ignoreCase = true) ||
      "Content-Type".equals(fieldName, ignoreCase = true)
}

private fun Request.requestForCache(): Request {
  val cacheUrlOverride = cacheUrlOverride

  // Allow POST and QUERY caching only when there is a cacheUrlOverride
  return if (cacheUrlOverride != null && (HttpMethod.isCacheable(method) || method == "POST")) {
    newBuilder()
      .get()
      .url(cacheUrlOverride)
      .cacheUrlOverride(null)
      .build()
  } else {
    this
  }
}
