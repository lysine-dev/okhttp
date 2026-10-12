/*
 * Copyright (C) 2015 Square, Inc.
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

package okhttp3.logging

import java.io.IOException
import java.nio.charset.Charset
import java.util.TreeSet
import java.util.concurrent.TimeUnit
import okhttp3.Headers
import okhttp3.HttpUrl
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Response
import okhttp3.internal.UnreadableResponseBody
import okhttp3.internal.charsetOrUtf8
import okhttp3.internal.http.promisesBody
import okhttp3.internal.isProbablyUtf8
import okhttp3.internal.platform.Platform
import okio.Buffer
import okio.GzipSource

/**
 * An OkHttp interceptor which logs request and response information. Can be applied as an
 * [application interceptor][OkHttpClient.interceptors] or as a [OkHttpClient.networkInterceptors].
 *
 * The format of the logs created by this class should not be considered stable and may
 * change slightly between releases. If you need a stable logging format, use your own interceptor.
 */
class HttpLoggingInterceptor
  @JvmOverloads
  constructor(
    private val logger: Logger = Logger.DEFAULT,
  ) : Interceptor {
    @Volatile private var headersToRedact = emptySet<String>()

    @Volatile private var queryParamsNameToRedact = emptySet<String>()

    @set:JvmName("level")
    @Volatile
    var level = Level.NONE

    enum class Level {
      /** No logs. */
      NONE,

      /**
       * Logs request and response lines.
       *
       * Example:
       * ```
       * --> POST /greeting http/1.1 (3-byte body)
       *
       * <-- 200 OK (22ms, 6-byte body)
       * ```
       */
      BASIC,

      /**
       * Logs request and response lines and their respective headers.
       *
       * Example:
       * ```
       * --> POST /greeting http/1.1
       * Host: example.com
       * Content-Type: plain/text
       * Content-Length: 3
       * --> END POST
       *
       * <-- 200 OK (22ms)
       * Content-Type: plain/text
       * Content-Length: 6
       * <-- END HTTP
       * ```
       */
      HEADERS,

      /**
       * Logs request and response lines and their respective headers and bodies (if present).
       *
       * Example:
       * ```
       * --> POST /greeting http/1.1
       * Host: example.com
       * Content-Type: plain/text
       * Content-Length: 3
       *
       * Hi?
       * --> END POST
       *
       * <-- 200 OK (22ms)
       * Content-Type: plain/text
       * Content-Length: 6
       *
       * Hello!
       * <-- END HTTP
       * ```
       */
      BODY,
    }

    fun interface Logger {
      fun log(message: String)

      companion object {
        /** A [Logger] defaults output appropriate for the current platform. */
        @JvmField
        val DEFAULT: Logger = DefaultLogger()

        private class DefaultLogger : Logger {
          override fun log(message: String) {
            Platform.get().log(message)
          }
        }
      }
    }

    fun redactHeader(name: String) {
      val newHeadersToRedact = TreeSet(String.CASE_INSENSITIVE_ORDER)
      newHeadersToRedact += headersToRedact
      newHeadersToRedact += name
      headersToRedact = newHeadersToRedact
    }

    fun redactQueryParams(vararg name: String) {
      val newQueryParamsNameToRedact = TreeSet(String.CASE_INSENSITIVE_ORDER)
      newQueryParamsNameToRedact += queryParamsNameToRedact
      newQueryParamsNameToRedact.addAll(name)
      queryParamsNameToRedact = newQueryParamsNameToRedact
    }

    /**
     * Sets the level and returns this.
     *
     * This was deprecated in OkHttp 4.0 in favor of the [level] val. In OkHttp 4.3 it is
     * un-deprecated because Java callers can't chain when assigning Kotlin vals. (The getter remains
     * deprecated).
     */
    fun setLevel(level: Level) =
      apply {
        this.level = level
      }

    @JvmName("-deprecated_level")
    @Deprecated(
      message = "moved to var",
      replaceWith = ReplaceWith(expression = "level"),
      level = DeprecationLevel.ERROR,
    )
    fun getLevel(): Level = level

    @Throws(IOException::class)
    override fun intercept(chain: Interceptor.Chain): Response {
      val level = this.level

      val request = chain.request()
      if (level == Level.NONE) {
        return chain.proceed(request)
      }

      val logBody = level == Level.BODY
      val logHeaders = logBody || level == Level.HEADERS

      val requestBody = request.body

      val connection = chain.connection()
      var requestStartMessage =
        ("--> ${request.method} ${redactUrl(request.url)}${if (connection != null) " " + connection.protocol() else ""}")
      if (!logHeaders && requestBody != null) {
        requestStartMessage += " (${requestBody.contentLength()}-byte body)"
      }
      logger.log(requestStartMessage)

      if (logHeaders) {
        val headers = request.headers

        if (requestBody != null) {
          // Request body headers are only present when installed as a network interceptor. When not
          // already present, force them to be included (if available) so their values are known.
          requestBody.contentType()?.let {
            if (headers["Content-Type"] == null) {
              logger.log("Content-Type: $it")
            }
          }
          if (requestBody.contentLength() != -1L) {
            if (headers["Content-Length"] == null) {
              logger.log("Content-Length: ${requestBody.contentLength()}")
            }
          }
        }

        for (i in 0 until headers.size) {
          logHeader(headers, i)
        }

        if (!logBody || requestBody == null) {
          logger.log("--> END ${request.method}")
        } else if (bodyHasUnknownEncoding(request.headers)) {
          logger.log("--> END ${request.method} (encoded body omitted)")
        } else if (requestBody.isDuplex()) {
          logger.log("--> END ${request.method} (duplex request body omitted)")
        } else if (requestBody.isOneShot()) {
          logger.log("--> END ${request.method} (one-shot body omitted)")
        } else {
          var buffer = Buffer()
          requestBody.writeTo(buffer)

          var gzippedLength: Long? = null
          if ("gzip".equals(headers["Content-Encoding"], ignoreCase = true)) {
            gzippedLength = buffer.size
            GzipSource(buffer).use { gzippedResponseBody ->
              buffer = Buffer()
              buffer.writeAll(gzippedResponseBody)
            }
          }

          val charset: Charset = requestBody.contentType().charsetOrUtf8()

          logger.log("")
          if (!buffer.isProbablyUtf8(16L)) {
            logger.log(
              "--> END ${request.method} (binary ${requestBody.contentLength()}-byte body omitted)",
            )
          } else if (gzippedLength != null) {
            logger.log("--> END ${request.method} (${buffer.size}-byte, $gzippedLength-gzipped-byte body)")
          } else {
            logger.log(buffer.readString(charset))
            logger.log("--> END ${request.method} (${requestBody.contentLength()}-byte body)")
          }
        }
      }

      val startNs = System.nanoTime()
      val response: Response
      try {
        response = chain.proceed(request)
      } catch (e: Exception) {
        val tookMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startNs)
        logger.log(
          buildString {
            append("<-- HTTP FAILED: $e.")
            append(" ${redactUrl(request.url)} (${tookMs}ms)")
          },
        )
        throw e
      }

      val tookMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startNs)

      val responseBody = response.body!!
      val contentLength = responseBody.contentLength()
      val bodySize = if (contentLength != -1L) "$contentLength-byte" else "unknown-length"
      logger.log(
        buildString {
          append("<-- ${response.code}")
          if (response.message.isNotEmpty()) append(" ${response.message}")
          append(" ${redactUrl(response.request.url)} (${tookMs}ms")
          if (!logHeaders) append(", $bodySize body")
          append(")")
        },
      )

      if (logHeaders) {
        val headers = response.headers
        for (i in 0 until headers.size) {
          logHeader(headers, i)
        }

        if (!logBody || !response.promisesBody()) {
          logger.log("<-- END HTTP")
        } else if (bodyHasUnknownEncoding(response.headers)) {
          logger.log("<-- END HTTP (encoded body omitted)")
        } else if (bodyIsStreaming(response)) {
          logger.log("<-- END HTTP (streaming)")
        } else if (responseBody is UnreadableResponseBody) {
          logger.log("<-- END HTTP (unreadable body)")
        } else {
          val source = responseBody.source()
          source.request(Long.MAX_VALUE) // Buffer the entire body.

          val totalMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startNs)

          var buffer = source.buffer

          var gzippedLength: Long? = null
          if ("gzip".equals(headers["Content-Encoding"], ignoreCase = true)) {
            gzippedLength = buffer.size
            GzipSource(buffer.clone()).use { gzippedResponseBody ->
              buffer = Buffer()
              buffer.writeAll(gzippedResponseBody)
            }
          }

          val charset: Charset = responseBody.contentType().charsetOrUtf8()

          if (!buffer.isProbablyUtf8(16L)) {
            logger.log("")
            logger.log("<-- END HTTP (${totalMs}ms, binary ${buffer.size}-byte body omitted)")
            return response
          }

          if (contentLength != 0L) {
            logger.log("")
            logger.log(buffer.clone().readString(charset))
          }

          logger.log(
            buildString {
              append("<-- END HTTP (${totalMs}ms, ${buffer.size}-byte")
              if (gzippedLength != null) append(", $gzippedLength-gzipped-byte")
              append(" body)")
            },
          )
        }
      }

      return response
    }

    internal fun redactUrl(url: HttpUrl): String {
      if (queryParamsNameToRedact.isEmpty() || url.querySize == 0) {
        return url.toString()
      }
      return url
        .newBuilder()
        .query(null)
        .apply {
          for (i in 0 until url.querySize) {
            val parameterName = url.queryParameterName(i)
            val newValue = if (parameterName in queryParamsNameToRedact) "██" else url.queryParameterValue(i)

            addEncodedQueryParameter(parameterName, newValue)
          }
        }.toString()
    }

    private fun logHeader(
      headers: Headers,
      i: Int,
    ) {
      val value = if (headers.name(i) in headersToRedact) "██" else headers.value(i)
      logger.log(headers.name(i) + ": " + value)
    }

    private fun bodyHasUnknownEncoding(headers: Headers): Boolean {
      val contentEncoding = headers["Content-Encoding"] ?: return false
      return !contentEncoding.equals("identity", ignoreCase = true) &&
        !contentEncoding.equals("gzip", ignoreCase = true) &&
        !contentEncoding.equals("br", ignoreCase = true)
    }

    private fun bodyIsStreaming(response: Response): Boolean {
      val contentType = response.body.contentType()
      return contentType != null && contentType.type == "text" && contentType.subtype == "event-stream"
    }

    companion object
  }
