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

@file:JvmName("Internal")
@file:Suppress("ktlint:standard:filename")
@file:OptIn(OkHttpInternalApi::class)

package okhttp3.internal

// Exposes Kotlin-internal APIs to Java test code and code in other modules.

import java.nio.charset.Charset
import javax.net.ssl.SSLSocket
import okhttp3.Cache
import okhttp3.CipherSuite
import okhttp3.ConnectionPool
import okhttp3.ConnectionSpec
import okhttp3.Cookie
import okhttp3.Headers
import okhttp3.HttpUrl
import okhttp3.MediaType
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.internal.concurrent.TaskRunner
import okhttp3.internal.connection.ConnectionListener
import okhttp3.internal.connection.RealConnection

// Exposes Kotlin-internal APIs to Java test code and code in other modules.

internal fun parseCookie(
  currentTimeMillis: Long,
  url: HttpUrl,
  setCookie: String,
): Cookie? = Cookie.parse(currentTimeMillis, url, setCookie)

internal fun cookieToString(
  cookie: Cookie,
  forObsoleteRfc2965: Boolean,
): String = cookie.toString(forObsoleteRfc2965)

internal fun addHeaderLenient(
  builder: Headers.Builder,
  line: String,
): Headers.Builder = builder.addLenient(line)

internal fun addHeaderLenient(
  builder: Headers.Builder,
  name: String,
  value: String,
): Headers.Builder = builder.addLenient(name, value)

internal fun cacheGet(
  cache: Cache,
  request: Request,
): Response? = cache.get(request)

internal fun applyConnectionSpec(
  connectionSpec: ConnectionSpec,
  sslSocket: SSLSocket,
  isFallback: Boolean,
) = connectionSpec.apply(sslSocket, isFallback)

internal fun ConnectionSpec.effectiveCipherSuites(socketEnabledCipherSuites: Array<String>): Array<String> =
  if (cipherSuitesAsString != null) {
    // 3 options here for ordering
    // 1) Legacy Platform - based on the Platform/Provider existing ordering in
    // sslSocket.enabledCipherSuites
    // 2) OkHttp Client - based on MODERN_TLS source code ordering
    // 3) Caller specified but assuming the visible defaults in MODERN_CIPHER_SUITES are rejigged
    // to match legacy i.e. the platform/provider
    //
    // Opting for 2 here and keeping MODERN_TLS in line with secure browsers.
    cipherSuitesAsString.intersect(socketEnabledCipherSuites, CipherSuite.ORDER_BY_NAME)
  } else {
    socketEnabledCipherSuites
  }

internal fun MediaType?.chooseCharset(): Pair<Charset, MediaType?> {
  var charset: Charset = Charsets.UTF_8
  var finalContentType: MediaType? = this
  if (this != null) {
    val resolvedCharset = this.charset()
    if (resolvedCharset == null) {
      charset = Charsets.UTF_8
      finalContentType = "$this; charset=utf-8".toMediaTypeOrNull()
    } else {
      charset = resolvedCharset
    }
  }
  return charset to finalContentType
}

internal fun MediaType?.charsetOrUtf8(): Charset = this?.charset() ?: Charsets.UTF_8

internal val Response.connection: RealConnection
  get() = this.exchange!!.connection

internal fun OkHttpClient.Builder.taskRunnerInternal(taskRunner: TaskRunner) = this.taskRunner(taskRunner)

internal fun buildConnectionPool(
  connectionListener: ConnectionListener,
  taskRunner: TaskRunner,
): ConnectionPool =
  ConnectionPool(
    connectionListener = connectionListener,
    taskRunner = taskRunner,
  )
