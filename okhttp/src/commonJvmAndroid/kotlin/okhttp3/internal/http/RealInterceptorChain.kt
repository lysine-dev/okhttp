/*
 * Copyright (C) 2016 Square, Inc.
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
import java.net.Proxy
import java.net.ProxySelector
import java.util.concurrent.TimeUnit
import javax.net.SocketFactory
import javax.net.ssl.HostnameVerifier
import javax.net.ssl.SSLSocketFactory
import javax.net.ssl.X509TrustManager
import okhttp3.Address
import okhttp3.Authenticator
import okhttp3.Cache
import okhttp3.Call
import okhttp3.CertificatePinner
import okhttp3.Connection
import okhttp3.ConnectionPool
import okhttp3.CookieJar
import okhttp3.Dns
import okhttp3.EventListener
import okhttp3.HttpUrl
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.internal.checkDuration
import okhttp3.internal.connection.Exchange
import okhttp3.internal.connection.RealCall
import okhttp3.internal.tls.CertificateChainCleaner

/**
 * A concrete interceptor chain that carries the entire interceptor chain: all application
 * interceptors, the OkHttp core, all network interceptors, and finally the network caller.
 *
 * If the chain is for an application interceptor then [exchange] must be null. Otherwise it is for
 * a network interceptor and [exchange] must be non-null.
 */
class RealInterceptorChain(
  internal val call: RealCall,
  private val interceptors: List<Interceptor>,
  private val index: Int,
  internal val exchange: Exchange?,
  internal val request: Request,
  internal val connectTimeoutMillis: Int,
  internal val readTimeoutMillis: Int,
  internal val writeTimeoutMillis: Int,
  override val authenticator: Authenticator,
  override val cache: Cache?,
  override val certificatePinner: CertificatePinner,
  override val connectionPool: ConnectionPool,
  override val cookieJar: CookieJar,
  override val dns: Dns,
  override val hostnameVerifier: HostnameVerifier,
  override val proxy: Proxy?,
  override val proxyAuthenticator: Authenticator,
  override val proxySelector: ProxySelector,
  override val retryOnConnectionFailure: Boolean,
  override val socketFactory: SocketFactory,
  override val sslSocketFactoryOrNull: SSLSocketFactory?,
  override val x509TrustManagerOrNull: X509TrustManager?,
  val certificateChainCleaner: CertificateChainCleaner?,
) : Interceptor.Chain {
  internal constructor(
    call: RealCall,
    interceptors: List<Interceptor>,
    index: Int,
    exchange: Nothing?,
    request: Request,
    client: OkHttpClient = call.client,
  ) : this(
    call = call,
    interceptors = interceptors,
    index = index,
    exchange = exchange,
    request = request,
    connectTimeoutMillis = client.connectTimeoutMillis,
    readTimeoutMillis = client.readTimeoutMillis,
    writeTimeoutMillis = client.writeTimeoutMillis,
    authenticator = client.authenticator,
    cache = client.cache,
    certificatePinner = client.certificatePinner,
    connectionPool = client.connectionPool,
    cookieJar = client.cookieJar,
    dns = client.dns,
    hostnameVerifier = client.hostnameVerifier,
    proxy = client.proxy,
    proxyAuthenticator = client.proxyAuthenticator,
    proxySelector = client.proxySelector,
    retryOnConnectionFailure = client.retryOnConnectionFailure,
    socketFactory = client.socketFactory,
    sslSocketFactoryOrNull = client.sslSocketFactoryOrNull,
    x509TrustManagerOrNull = client.x509TrustManager,
    certificateChainCleaner = client.certificateChainCleaner,
  )

  private var calls: Int = 0

  internal fun copy(
    index: Int = this.index,
    exchange: Exchange? = this.exchange,
    request: Request = this.request,
    connectTimeoutMillis: Int = this.connectTimeoutMillis,
    readTimeoutMillis: Int = this.readTimeoutMillis,
    writeTimeoutMillis: Int = this.writeTimeoutMillis,
    authenticator: Authenticator = this.authenticator,
    cache: Cache? = this.cache,
    certificatePinner: CertificatePinner = this.certificatePinner,
    connectionPool: ConnectionPool = this.connectionPool,
    cookieJar: CookieJar = this.cookieJar,
    dns: Dns = this.dns,
    hostnameVerifier: HostnameVerifier = this.hostnameVerifier,
    proxy: Proxy? = this.proxy,
    proxyAuthenticator: Authenticator = this.proxyAuthenticator,
    proxySelector: ProxySelector = this.proxySelector,
    retryOnConnectionFailure: Boolean = this.retryOnConnectionFailure,
    socketFactory: SocketFactory = this.socketFactory,
    sslSocketFactory: SSLSocketFactory? = this.sslSocketFactoryOrNull,
    x509TrustManager: X509TrustManager? = this.x509TrustManagerOrNull,
    certificateChainCleaner: CertificateChainCleaner? = this.certificateChainCleaner,
  ) = RealInterceptorChain(
    call = call,
    interceptors = interceptors,
    index = index,
    exchange = exchange,
    request = request,
    connectTimeoutMillis = connectTimeoutMillis,
    readTimeoutMillis = readTimeoutMillis,
    writeTimeoutMillis = writeTimeoutMillis,
    authenticator = authenticator,
    cache = cache,
    certificatePinner = certificatePinner,
    connectionPool = connectionPool,
    cookieJar = cookieJar,
    dns = dns,
    hostnameVerifier = hostnameVerifier,
    proxy = proxy,
    proxyAuthenticator = proxyAuthenticator,
    proxySelector = proxySelector,
    retryOnConnectionFailure = retryOnConnectionFailure,
    socketFactory = socketFactory,
    sslSocketFactoryOrNull = sslSocketFactory,
    x509TrustManagerOrNull = x509TrustManager,
    certificateChainCleaner = certificateChainCleaner,
  )

  override val eventListener: EventListener
    get() = call.eventListener

  override val followSslRedirects: Boolean
    get() = call.client.followSslRedirects

  override val followRedirects: Boolean
    get() = call.client.followRedirects

  override fun connection(): Connection? = exchange?.connection

  override fun connectTimeoutMillis(): Int = connectTimeoutMillis

  override fun withConnectTimeout(
    timeout: Int,
    unit: TimeUnit,
  ): Interceptor.Chain {
    check(exchange == null) { "Timeouts can't be adjusted in a network interceptor" }

    return copy(connectTimeoutMillis = checkDuration("connectTimeout", timeout.toLong(), unit))
  }

  override fun readTimeoutMillis(): Int = readTimeoutMillis

  override fun withReadTimeout(
    timeout: Int,
    unit: TimeUnit,
  ): Interceptor.Chain {
    check(exchange == null) { "Timeouts can't be adjusted in a network interceptor" }

    return copy(readTimeoutMillis = checkDuration("readTimeout", timeout.toLong(), unit))
  }

  override fun writeTimeoutMillis(): Int = writeTimeoutMillis

  override fun withWriteTimeout(
    timeout: Int,
    unit: TimeUnit,
  ): Interceptor.Chain {
    check(exchange == null) { "Timeouts can't be adjusted in a network interceptor" }

    return copy(writeTimeoutMillis = checkDuration("writeTimeout", timeout.toLong(), unit))
  }

  override fun withDns(dns: Dns): Interceptor.Chain {
    check(exchange == null) { "dns can't be adjusted in a network interceptor" }

    return copy(dns = dns)
  }

  override fun withSocketFactory(socketFactory: SocketFactory): Interceptor.Chain {
    check(exchange == null) { "socketFactory can't be adjusted in a network interceptor" }

    return copy(socketFactory = socketFactory)
  }

  override fun withRetryOnConnectionFailure(retryOnConnectionFailure: Boolean): Interceptor.Chain {
    check(exchange == null) { "retryOnConnectionFailure can't be adjusted in a network interceptor" }

    return copy(retryOnConnectionFailure = retryOnConnectionFailure)
  }

  override fun withAuthenticator(authenticator: Authenticator): Interceptor.Chain {
    check(exchange == null) { "authenticator can't be adjusted in a network interceptor" }

    return copy(authenticator = authenticator)
  }

  override fun withCookieJar(cookieJar: CookieJar): Interceptor.Chain {
    check(exchange == null) { "cookieJar can't be adjusted in a network interceptor" }

    return copy(cookieJar = cookieJar)
  }

  override fun withCache(cache: Cache?): Interceptor.Chain {
    check(exchange == null) { "cache can't be adjusted in a network interceptor" }

    return copy(cache = cache)
  }

  override fun withProxy(proxy: Proxy?): Interceptor.Chain {
    check(exchange == null) { "proxy can't be adjusted in a network interceptor" }

    return copy(proxy = proxy)
  }

  override fun withProxySelector(proxySelector: ProxySelector): Interceptor.Chain {
    check(exchange == null) { "proxySelector can't be adjusted in a network interceptor" }

    return copy(proxySelector = proxySelector)
  }

  override fun withProxyAuthenticator(proxyAuthenticator: Authenticator): Interceptor.Chain {
    check(exchange == null) { "proxyAuthenticator can't be adjusted in a network interceptor" }

    return copy(proxyAuthenticator = proxyAuthenticator)
  }

  override fun withSslSocketFactory(
    sslSocketFactory: SSLSocketFactory?,
    x509TrustManager: X509TrustManager?,
  ): Interceptor.Chain {
    check(exchange == null) { "sslSocketFactory can't be adjusted in a network interceptor" }

    if (sslSocketFactory != null && x509TrustManager != null) {
      val newCertificateChainCleaner = CertificateChainCleaner.get(x509TrustManager)
      return copy(
        sslSocketFactory = sslSocketFactory,
        x509TrustManager = x509TrustManager,
        certificateChainCleaner = newCertificateChainCleaner,
        certificatePinner = certificatePinner.withCertificateChainCleaner(newCertificateChainCleaner),
      )
    } else {
      return copy(
        sslSocketFactory = null,
        x509TrustManager = null,
        certificateChainCleaner = null,
      )
    }
  }

  override fun withHostnameVerifier(hostnameVerifier: HostnameVerifier): Interceptor.Chain {
    check(exchange == null) { "hostnameVerifier can't be adjusted in a network interceptor" }

    return copy(hostnameVerifier = hostnameVerifier)
  }

  override fun withCertificatePinner(certificatePinner: CertificatePinner): Interceptor.Chain {
    check(exchange == null) { "certificatePinner can't be adjusted in a network interceptor" }

    val newCertificatePinner =
      if (certificateChainCleaner != null) {
        certificatePinner.withCertificateChainCleaner(certificateChainCleaner)
      } else {
        certificatePinner
      }

    return copy(certificatePinner = newCertificatePinner)
  }

  override fun withConnectionPool(connectionPool: ConnectionPool): Interceptor.Chain {
    check(exchange == null) { "connectionPool can't be adjusted in a network interceptor" }

    return copy(connectionPool = connectionPool)
  }

  override fun call(): Call = call

  override fun request(): Request = request

  @Throws(IOException::class)
  override fun proceed(request: Request): Response {
    check(index < interceptors.size)

    calls++

    if (exchange != null) {
      check(exchange.finder.routePlanner.sameHostAndPort(request.url)) {
        "network interceptor ${interceptors[index - 1]} must retain the same host and port"
      }
      check(calls == 1) {
        "network interceptor ${interceptors[index - 1]} must call proceed() exactly once"
      }
    }

    // Call the next interceptor in the chain.
    val next = copy(index = index + 1, request = request)
    val interceptor = interceptors[index]

    @Suppress("USELESS_ELVIS")
    val response =
      interceptor.intercept(next) ?: throw NullPointerException(
        "interceptor $interceptor returned null",
      )

    if (exchange != null) {
      check(index + 1 >= interceptors.size || next.calls == 1) {
        "network interceptor $interceptor must call proceed() exactly once"
      }
    }

    return response
  }

  /**
   * Creates an [Address] of out of the provided [HttpUrl]
   * that uses this client’s DNS, TLS, and proxy configuration.
   */
  fun address(url: HttpUrl): Address {
    var useSslSocketFactory: SSLSocketFactory? = null
    var useHostnameVerifier: HostnameVerifier? = null
    var useCertificatePinner: CertificatePinner? = null
    if (url.isHttps) {
      useSslSocketFactory = this.sslSocketFactoryOrNull
      useHostnameVerifier = this.hostnameVerifier
      useCertificatePinner = this.certificatePinner
    }

    return Address(
      uriHost = url.host,
      uriPort = url.port,
      dns = dns,
      socketFactory = socketFactory,
      sslSocketFactory = useSslSocketFactory,
      hostnameVerifier = useHostnameVerifier,
      certificatePinner = useCertificatePinner,
      proxyAuthenticator = proxyAuthenticator,
      proxy = proxy,
      protocols = call.client.protocols,
      connectionSpecs = call.client.connectionSpecs,
      proxySelector = proxySelector,
    )
  }
}
