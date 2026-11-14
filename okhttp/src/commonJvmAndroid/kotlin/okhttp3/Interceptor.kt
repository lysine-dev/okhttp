/*
 * Copyright (C) 2014 Square, Inc.
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
import java.net.Proxy
import java.net.ProxySelector
import java.util.concurrent.TimeUnit
import javax.net.SocketFactory
import javax.net.ssl.HostnameVerifier
import javax.net.ssl.SSLSocketFactory
import javax.net.ssl.X509TrustManager
import okhttp3.internal.tls.CertificateChainCleaner

/**
 * Observes, modifies, and potentially short-circuits requests going out and the corresponding
 * responses coming back in. Typically interceptors add, remove, or transform headers on the request
 * or response.
 *
 * Implementations of this interface throw [IOException] to signal connectivity failures. This
 * includes both natural exceptions such as unreachable servers, as well as synthetic exceptions
 * when responses are of an unexpected type or cannot be decoded.
 *
 * Other exception types cancel the current call:
 *
 *  * For synchronous calls made with [Call.execute], the exception is propagated to the caller.
 *
 *  * For asynchronous calls made with [Call.enqueue], an [IOException] is propagated to the caller
 *    indicating that the call was canceled. The interceptor's exception is delivered to the current
 *    thread's [uncaught exception handler][Thread.UncaughtExceptionHandler]. By default this
 *    crashes the application on Android and prints a stacktrace on the JVM. (Crash reporting
 *    libraries may customize this behavior.)
 *
 * A good way to signal a failure is with a synthetic HTTP response:
 *
 * ```kotlin
 *   @Throws(IOException::class)
 *   override fun intercept(chain: Interceptor.Chain): Response {
 *     if (myConfig.isInvalid()) {
 *       return Response.Builder()
 *           .request(chain.request())
 *           .protocol(Protocol.HTTP_1_1)
 *           .code(400)
 *           .message("client config invalid")
 *           .body("client config invalid".toResponseBody(null))
 *           .build()
 *     }
 *
 *     return chain.proceed(chain.request())
 *   }
 * ```
 */
fun interface Interceptor {
  @Throws(IOException::class)
  fun intercept(chain: Chain): Response

  companion object {
    /**
     * Constructs an interceptor for a lambda. This compact syntax is most useful for inline
     * interceptors.
     *
     * ```kotlin
     * val interceptor = Interceptor { chain: Interceptor.Chain ->
     *     chain.proceed(chain.request())
     * }
     * ```
     */
    inline operator fun invoke(crossinline block: (chain: Chain) -> Response): Interceptor = Interceptor { block(it) }
  }

  interface Chain {
    fun request(): Request

    @Throws(IOException::class)
    fun proceed(request: Request): Response

    /**
     * Returns the connection the request will be executed on. This is only available in the chains
     * of network interceptors. For application interceptors this is always null.
     */
    fun connection(): Connection?

    /**
     * Returns the `Call` to which this chain belongs.
     */
    fun call(): Call

    /**
     * Returns the connect timeout in milliseconds.
     */
    fun connectTimeoutMillis(): Int

    /**
     * Returns a new chain with the specified connect timeout.
     */
    fun withConnectTimeout(
      timeout: Int,
      unit: TimeUnit,
    ): Chain

    /**
     * Returns the read timeout in milliseconds.
     */
    fun readTimeoutMillis(): Int

    /**
     * Returns a new chain with the specified read timeout.
     */
    fun withReadTimeout(
      timeout: Int,
      unit: TimeUnit,
    ): Chain

    /**
     * Returns the write timeout in milliseconds.
     */
    fun writeTimeoutMillis(): Int

    /**
     * Returns a new chain with the specified write timeout.
     */
    fun withWriteTimeout(
      timeout: Int,
      unit: TimeUnit,
    ): Chain

    val followSslRedirects: Boolean

    val followRedirects: Boolean

    /**
     * Get the [DNS] instance for the OkHttpClient, or an override from the Call.Chain.
     */
    val dns: Dns

    /**
     * Override the [DNS] for the Call.Chain.
     *
     * @throws IllegalStateException if this is a Network Interceptor, since the override is too late.
     */
    fun withDns(dns: Dns): Chain

    /**
     * Returns the [SocketFactory] for the OkHttpClient, or an override from the Call.Chain.
     */
    val socketFactory: SocketFactory

    /**
     * Override the [SocketFactory] for the Call.Chain.
     *
     * @throws IllegalStateException if this is a Network Interceptor, since the override is too late.
     */
    fun withSocketFactory(socketFactory: SocketFactory): Chain

    /**
     * Returns true if the call should retry on connection failures.
     */
    val retryOnConnectionFailure: Boolean

    /**
     * Returns a new chain with the specified retry on connection failure setting.
     */
    fun withRetryOnConnectionFailure(retryOnConnectionFailure: Boolean): Chain

    /**
     * Returns the [Authenticator] for the OkHttpClient, or an override from the Call.Chain.
     */
    val authenticator: Authenticator

    /**
     * Override the [Authenticator] for the Call.Chain.
     *
     * @throws IllegalStateException if this is a Network Interceptor, since the override is too late.
     */
    fun withAuthenticator(authenticator: Authenticator): Chain

    /**
     * Returns the [CookieJar] for the OkHttpClient, or an override from the Call.Chain.
     */
    val cookieJar: CookieJar

    /**
     * Returns a new chain with the specified [CookieJar].
     */
    fun withCookieJar(cookieJar: CookieJar): Chain

    /**
     * Returns the [Cache] for the OkHttpClient, or an override from the Call.Chain.
     */
    val cache: Cache?

    /**
     * Override the [Cache] for the Call.Chain.
     *
     * @throws IllegalStateException if this is a Network Interceptor, since the override is too late.
     */
    fun withCache(cache: Cache?): Chain

    /**
     * Returns the [Proxy] for the OkHttpClient, or an override from the Call.Chain.
     */
    val proxy: Proxy?

    /**
     * Returns a new chain with the specified [Proxy].
     */
    fun withProxy(proxy: Proxy?): Chain

    /**
     * Returns the [ProxySelector] for the OkHttpClient, or an override from the Call.Chain.
     */
    val proxySelector: ProxySelector

    /**
     * Override the [ProxySelector] for the Call.Chain.
     *
     * @throws IllegalStateException if this is a Network Interceptor, since the override is too late.
     */
    fun withProxySelector(proxySelector: ProxySelector): Chain

    /**
     * Returns the proxy [Authenticator] for the OkHttpClient, or an override from the Call.Chain.
     */
    val proxyAuthenticator: Authenticator

    /**
     * Returns a new chain with the specified proxy [Authenticator].
     */
    fun withProxyAuthenticator(proxyAuthenticator: Authenticator): Chain

    /**
     * Returns the [SSLSocketFactory] for the OkHttpClient, or an override from the Call.Chain.
     */
    val sslSocketFactoryOrNull: SSLSocketFactory?

    /**
     * Returns a new chain with the specified [SSLSocketFactory].
     *
     * @throws IllegalStateException if this is a Network Interceptor, since the override is too late.
     */
    fun withSslSocketFactory(
      sslSocketFactory: SSLSocketFactory?,
      x509TrustManager: X509TrustManager?,
    ): Chain

    /**
     * Returns the [X509TrustManager] for the OkHttpClient, or an override from the Call.Chain.
     */
    val x509TrustManagerOrNull: X509TrustManager?

    /**
     * Returns the [HostnameVerifier] for the OkHttpClient, or an override from the Call.Chain.
     */
    val hostnameVerifier: HostnameVerifier

    /**
     * Override the [HostnameVerifier] for the Call.Chain.
     *
     * @throws IllegalStateException if this is a Network Interceptor, since the override is too late.
     */
    fun withHostnameVerifier(hostnameVerifier: HostnameVerifier): Chain

    /**
     * Returns the [CertificatePinner] for the OkHttpClient, or an override from the Call.Chain.
     */
    val certificatePinner: CertificatePinner

    /**
     * Returns a new chain with the specified [CertificatePinner].
     */
    fun withCertificatePinner(certificatePinner: CertificatePinner): Chain

    /**
     * Returns the [ConnectionPool] for the OkHttpClient, or an override from the Call.Chain.
     */
    val connectionPool: ConnectionPool

    /**
     * Returns a new chain with the specified [ConnectionPool].
     */
    fun withConnectionPool(connectionPool: ConnectionPool): Chain

    val eventListener: EventListener
  }
}
