/*
 * Copyright (c) 2025 OkHttp Authors
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

import app.cash.burst.Burst
import app.cash.burst.burstValues
import assertk.assertFailure
import assertk.assertThat
import assertk.assertions.hasMessage
import assertk.assertions.isFailure
import assertk.assertions.isFalse
import assertk.assertions.isNotSameInstanceAs
import assertk.assertions.isTrue
import java.io.FilterInputStream
import java.io.FilterOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.ProxySelector
import java.net.Socket
import java.net.SocketAddress
import java.net.URI
import java.security.cert.X509Certificate
import java.util.Locale.getDefault
import java.util.concurrent.TimeUnit
import javax.net.SocketFactory
import javax.net.ssl.HostnameVerifier
import javax.net.ssl.SSLException
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory
import javax.net.ssl.X509TrustManager
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.nanoseconds
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.junit5.StartStop
import okhttp3.CertificatePinner.Companion.pin
import okhttp3.Headers.Companion.headersOf
import okhttp3.internal.connection.ConnectionListener
import okhttp3.internal.platform.Platform
import okhttp3.sockets.DelegatingSSLSocketFactory
import okhttp3.sockets.DelegatingSocketFactory
import okhttp3.testing.PlatformRule
import okio.BufferedSink
import okio.ForwardingFileSystem
import okio.IOException
import okio.Path
import okio.Path.Companion.toPath
import okio.fakefilesystem.FakeFileSystem
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.RegisterExtension

@Burst
class InterceptorOverridesTest {
  @RegisterExtension
  val platform = PlatformRule()

  @StartStop
  private val server = MockWebServer()

  // Can't use test instance with overrides
  private var client = OkHttpClient.Builder().build()

  private val handshakeCertificates = platform.localhostHandshakeCertificates()

  /**
   * Test that we can override in a Application Interceptor, purely by seeing that the chain reports
   * the override in a Network Interceptor.
   */
  @Test
  fun testOverrideInApplicationInterceptor(
    override: OverrideParam =
      burstValues(
        OverrideParam.Authenticator,
        OverrideParam.Cache,
        OverrideParam.CertificatePinner,
        OverrideParam.ConnectTimeout,
        OverrideParam.ConnectionPool,
        OverrideParam.CookieJar,
        OverrideParam.Dns,
        OverrideParam.HostnameVerifier,
        OverrideParam.Proxy,
        OverrideParam.ProxyAuthenticator,
        OverrideParam.ProxySelector,
        OverrideParam.ReadTimeout,
        OverrideParam.RetryOnConnectionFailure,
        OverrideParam.SocketFactory,
        OverrideParam.SslSocketFactory,
        OverrideParam.WriteTimeout,
        OverrideParam.X509TrustManager,
      ),
    isDefault: Boolean,
  ) {
    fun <T> Override<T>.testApplicationInterceptor(chain: Interceptor.Chain): Response {
      val defaultValue = chain.value()
      assertThat(isDefaultValue(chain.value())).isTrue()
      val withOverride = chain.withOverride(nonDefaultValue)
      assertThat(chain).isNotSameInstanceAs(withOverride)
      assertThat(isDefaultValue(withOverride.value())).isFalse()

      return if (isDefault) {
        val withDefault = withOverride.withOverride(defaultValue)
        assertThat(isDefaultValue(withDefault.value())).isTrue()
        withOverride.proceed(chain.request())
      } else {
        withOverride.proceed(chain.request())
      }
    }

    with(override.override) {
      client =
        client
          .newBuilder()
          .addInterceptor { chain ->
            testApplicationInterceptor(chain)
          }.addNetworkInterceptor { chain ->
            assertThat(isDefaultValue(chain.value())).isFalse()
            chain.proceed(chain.request())
          }.build()

      server.enqueue(
        MockResponse(),
      )
      val response = client.newCall(Request(server.url("/"))).execute()
      response.close()
    }
  }

  /**
   * Test that we can't override in a Network Interceptor, which will throw an exception.
   */
  @Test
  fun testOverrideInNetworkInterceptor(
    override: OverrideParam =
      burstValues(
        OverrideParam.Authenticator,
        OverrideParam.Cache,
        OverrideParam.CertificatePinner,
        OverrideParam.ConnectTimeout,
        OverrideParam.ConnectionPool,
        OverrideParam.CookieJar,
        OverrideParam.Dns,
        OverrideParam.HostnameVerifier,
        OverrideParam.Proxy,
        OverrideParam.ProxyAuthenticator,
        OverrideParam.ProxySelector,
        OverrideParam.ReadTimeout,
        OverrideParam.RetryOnConnectionFailure,
        OverrideParam.SocketFactory,
        OverrideParam.SslSocketFactory,
        OverrideParam.WriteTimeout,
        OverrideParam.X509TrustManager,
      ),
  ) {
    with(override.override) {
      client =
        client
          .newBuilder()
          .addNetworkInterceptor { chain ->
            assertThat(isDefaultValue(chain.value())).isTrue()

            assertFailure {
              chain.withOverride(
                nonDefaultValue,
              )
            }.hasMessage("${override.paramName} can't be adjusted in a network interceptor")

            chain.proceed(chain.request())
          }.build()

      server.enqueue(
        MockResponse(),
      )
      val response = client.newCall(Request(server.url("/"))).execute()
      response.close()
    }
  }

  /**
   * Test that if we set a bad implementation on the OkHttpClient directly, that we can avoid the failure
   * by setting a good override.
   */
  @Test
  fun testOverrideBadImplementation(
    override: OverrideParam =
      burstValues(
        OverrideParam.Authenticator,
        OverrideParam.Cache,
        OverrideParam.CertificatePinner,
        OverrideParam.ConnectTimeout,
        OverrideParam.ConnectionPool,
        OverrideParam.CookieJar,
        OverrideParam.Dns,
        OverrideParam.HostnameVerifier,
        OverrideParam.Proxy,
        OverrideParam.ProxyAuthenticator,
        OverrideParam.ProxySelector,
        OverrideParam.ReadTimeout,
        OverrideParam.RetryOnConnectionFailure,
        OverrideParam.SocketFactory,
        OverrideParam.SslSocketFactory,
        OverrideParam.WriteTimeout,
        OverrideParam.X509TrustManager,
      ),
    testItFails: Boolean = false,
  ) {
    when (override) {
      OverrideParam.ProxyAuthenticator -> {
        client = client.newBuilder().proxy(server.proxyAddress).build()

        server.enqueue(
          MockResponse
            .Builder()
            .code(407)
            .headers(headersOf("Proxy-Authenticate", "Basic realm=\"localhost\""))
            .inTunnel()
            .build(),
        )

        overrideBadImplementation(override = override.override, testItFails = testItFails)
      }

      OverrideParam.Authenticator -> {
        server.enqueue(
          MockResponse.Builder().code(401).build(),
        )

        overrideBadImplementation(override = override.override, testItFails = testItFails)
      }

      OverrideParam.RetryOnConnectionFailure -> {
        enableTls()
        var first = true
        client =
          client
            .newBuilder()
            .connectionSpecs(listOf(ConnectionSpec.RESTRICTED_TLS, ConnectionSpec.MODERN_TLS))
            .eventListener(
              object : EventListener() {
                override fun secureConnectEnd(
                  call: Call,
                  handshake: Handshake?,
                ) {
                  if (first) {
                    first = false
                    throw SSLException("")
                  }
                }
              },
            ).build()

        overrideBadImplementation(
          override = Override.RetryOnConnectionFailureOverride,
          testItFails = testItFails,
          badValue = false,
          goodValue = true,
        )
      }

      OverrideParam.SslSocketFactory -> {
        enableTls()
        overrideBadImplementation(
          override = Override.SslSocketFactoryOverride,
          testItFails = testItFails,
          goodValue = handshakeCertificates.sslSocketFactory(),
        )
      }

      OverrideParam.X509TrustManager -> {
        enableTls()
        overrideBadImplementation(
          override = Override.X509TrustManagerOverride,
          testItFails = testItFails,
          goodValue = handshakeCertificates.trustManager,
        )
      }

      OverrideParam.HostnameVerifier -> {
        enableTls()
        overrideBadImplementation(override = override.override, testItFails = testItFails)
      }

      OverrideParam.WriteTimeout -> {
        val body =
          object : RequestBody() {
            override fun contentType(): MediaType? = null

            override fun writeTo(sink: BufferedSink) {
              if (sink
                  .timeout()
                  .timeoutNanos()
                  .nanoseconds.inWholeMilliseconds == 10L
              ) {
                throw IOException()
              }
            }
          }
        overrideBadImplementation(override = override.override, testItFails = testItFails, body = body)
      }

      OverrideParam.ReadTimeout -> {
        client =
          client
            .newBuilder()
            .socketFactory(
              DelayingSocketFactory(onRead = {
                Thread.sleep(100L)
              }),
            ).build()

        overrideBadImplementation(override = override.override, testItFails = testItFails)
      }

      OverrideParam.ConnectTimeout -> {
        client =
          client
            .newBuilder()
            .socketFactory(
              DelayingSocketFactory(onConnect = { timeout ->
                if (timeout == 10) {
                  throw IOException()
                }
              }),
            ).build()

        overrideBadImplementation(override = override.override, testItFails = testItFails)
      }

      OverrideParam.CertificatePinner -> {
        enableTls()

        val pinner =
          CertificatePinner
            .Builder()
            .add(server.hostName, pin(handshakeCertificates.trustManager.acceptedIssuers.first()))
            .build()

        overrideBadImplementation(
          override = Override.CertificatePinnerOverride,
          testItFails = testItFails,
          goodValue = pinner,
        )
      }

      else -> {
        overrideBadImplementation(override = override.override, testItFails = testItFails)
      }
    }
  }

  private fun <T> overrideBadImplementation(
    override: Override<T>,
    testItFails: Boolean,
    badValue: T = override.badValue,
    goodValue: T = override.nonDefaultValue,
    body: RequestBody? = null,
  ) {
    with(override) {
      client =
        client
          .newBuilder()
          // Set the bad override directly on the client
          .withOverride(badValue)
          .addInterceptor { chain ->
            // the only way to stop a bad override of a client is with a good override of an interceptor
            chain
              .run {
                if (testItFails) {
                  this
                } else {
                  withOverride(goodValue)
                }
              }.proceed(chain.request())
          }.build()

      server.enqueue(
        MockResponse(),
      )
      val call = client.newCall(Request(server.url("/"), body = body))
      val result = runCatching { call.execute().body.bytes() }

      if (testItFails) {
        assertThat(result).isFailure()
      } else {
        result.getOrThrow()
      }
    }
  }

  enum class OverrideParam(
    val override: Override<*>,
  ) {
    Authenticator(Override.AuthenticatorOverride),
    Cache(Override.CacheOverride),
    CertificatePinner(Override.CertificatePinnerOverride),
    ConnectTimeout(
      Override.ConnectTimeoutOverride,
    ) {
      override val paramName: String
        get() = "Timeouts"
    },
    ConnectionPool(Override.ConnectionPoolOverride),
    CookieJar(Override.CookieJarOverride),
    Dns(Override.DnsOverride),
    HostnameVerifier(
      Override.HostnameVerifierOverride,
    ),
    Proxy(Override.ProxyOverride),
    ProxyAuthenticator(Override.ProxyAuthenticatorOverride),
    ProxySelector(Override.ProxySelectorOverride),
    ReadTimeout(
      Override.ReadTimeoutOverride,
    ) {
      override val paramName: String
        get() = "Timeouts"
    },
    RetryOnConnectionFailure(Override.RetryOnConnectionFailureOverride),
    SocketFactory(Override.SocketFactoryOverride),
    SslSocketFactory(
      Override.SslSocketFactoryOverride,
    ),
    WriteTimeout(Override.WriteTimeoutOverride) {
      override val paramName: String
        get() = "Timeouts"
    },
    X509TrustManager(Override.X509TrustManagerOverride), ;

    open val paramName: String
      get() = override.paramName ?: name.replaceFirstChar { it.lowercase(getDefault()) }
  }

  class DelayingSocketFactory(
    val onConnect: Socket.(timeout: Int) -> Unit = {},
    val onRead: Socket.() -> Unit = {},
    val onWrite: Socket.() -> Unit = {},
  ) : DelegatingSocketFactory(getDefault()) {
    override fun createSocket(): Socket {
      return object : Socket() {
        override fun connect(
          endpoint: SocketAddress?,
          timeout: Int,
        ) {
          onConnect(timeout)
          super.connect(endpoint, timeout)
        }

        override fun getInputStream(): InputStream {
          return object : FilterInputStream(super.inputStream) {
            override fun read(
              b: ByteArray?,
              off: Int,
              len: Int,
            ): Int {
              onRead()
              return super.read(b, off, len)
            }
          }
        }

        override fun getOutputStream(): OutputStream =
          object : FilterOutputStream(super.outputStream) {
            override fun write(
              b: ByteArray?,
              off: Int,
              len: Int,
            ) {
              onWrite()
              super.write(b, off, len)
            }
          }
      }
    }
  }

  sealed interface Override<T> {
    fun Interceptor.Chain.value(): T

    fun Interceptor.Chain.withOverride(value: T): Interceptor.Chain

    fun OkHttpClient.Builder.withOverride(value: T): OkHttpClient.Builder

    val paramName: String?
      get() = null

    val nonDefaultValue: T

    val badValue: T

    fun isDefaultValue(value: T): Boolean

    object DnsOverride : Override<Dns> {
      override fun Interceptor.Chain.value(): Dns = dns

      override fun Interceptor.Chain.withOverride(value: Dns): Interceptor.Chain = withDns(value)

      override fun OkHttpClient.Builder.withOverride(value: Dns): OkHttpClient.Builder = dns(value)

      override val nonDefaultValue: Dns = Dns { Dns.SYSTEM.lookup(it) }

      override val badValue: Dns = Dns { TODO() }

      override fun isDefaultValue(value: Dns): Boolean = value === Dns.SYSTEM
    }

    object SocketFactoryOverride : Override<SocketFactory> {
      override fun Interceptor.Chain.value(): SocketFactory = socketFactory

      override fun Interceptor.Chain.withOverride(value: SocketFactory): Interceptor.Chain = withSocketFactory(value)

      override fun OkHttpClient.Builder.withOverride(value: SocketFactory): OkHttpClient.Builder = socketFactory(value)

      override val nonDefaultValue: SocketFactory = object : DelegatingSocketFactory(getDefault()) {}

      override val badValue: SocketFactory =
        object : DelegatingSocketFactory(getDefault()) {
          override fun configureSocket(socket: Socket): Socket = TODO()
        }

      override fun isDefaultValue(value: SocketFactory): Boolean = value === SocketFactory.getDefault()
    }

    object AuthenticatorOverride : Override<Authenticator> {
      override fun Interceptor.Chain.value(): Authenticator = authenticator

      override fun Interceptor.Chain.withOverride(value: Authenticator): Interceptor.Chain = withAuthenticator(value)

      override fun OkHttpClient.Builder.withOverride(value: Authenticator): OkHttpClient.Builder = authenticator(value)

      override val nonDefaultValue: Authenticator = Authenticator { route, response -> response.request }

      override val badValue: Authenticator = Authenticator { route, response -> TODO() }

      override fun isDefaultValue(value: Authenticator): Boolean = value === Authenticator.NONE
    }

    object CookieJarOverride : Override<CookieJar> {
      override fun Interceptor.Chain.value(): CookieJar = cookieJar

      override fun Interceptor.Chain.withOverride(value: CookieJar): Interceptor.Chain = withCookieJar(value)

      override fun OkHttpClient.Builder.withOverride(value: CookieJar): OkHttpClient.Builder = cookieJar(value)

      override val nonDefaultValue: CookieJar =
        object : CookieJar {
          override fun saveFromResponse(
            url: HttpUrl,
            cookies: List<Cookie>,
          ) {
          }

          override fun loadForRequest(url: HttpUrl): List<Cookie> = emptyList()
        }

      override val badValue: CookieJar =
        object : CookieJar {
          override fun saveFromResponse(
            url: HttpUrl,
            cookies: List<Cookie>,
          ) {
          }

          override fun loadForRequest(url: HttpUrl): List<Cookie> = TODO()
        }

      override fun isDefaultValue(value: CookieJar): Boolean = value === CookieJar.NO_COOKIES
    }

    object CacheOverride : Override<Cache?> {
      override fun Interceptor.Chain.value(): Cache? = cache

      override fun Interceptor.Chain.withOverride(value: Cache?): Interceptor.Chain = withCache(value)

      override fun OkHttpClient.Builder.withOverride(value: Cache?): OkHttpClient.Builder = cache(value)

      override val nonDefaultValue: Cache = Cache(FakeFileSystem(), "/cash".toPath(), 1)

      override val badValue: Cache =
        Cache(
          object : ForwardingFileSystem(FakeFileSystem()) {
            override fun onPathParameter(
              path: Path,
              functionName: String,
              parameterName: String,
            ): Path = TODO()
          },
          "/cash".toPath(),
          1,
        )

      override fun isDefaultValue(value: Cache?): Boolean = value == null
    }

    object ProxyOverride : Override<Proxy?> {
      override fun Interceptor.Chain.value(): java.net.Proxy? = proxy

      override fun Interceptor.Chain.withOverride(value: java.net.Proxy?): Interceptor.Chain = withProxy(value)

      override fun OkHttpClient.Builder.withOverride(value: java.net.Proxy?): OkHttpClient.Builder = proxy(value)

      override val nonDefaultValue: java.net.Proxy? = java.net.Proxy.NO_PROXY

      override val badValue: java.net.Proxy? =
        java.net.Proxy(Proxy.Type.HTTP, InetSocketAddress.createUnresolved("proxy.example.com", 1003))

      override fun isDefaultValue(value: java.net.Proxy?): Boolean = value == null
    }

    object ProxySelectorOverride : Override<ProxySelector> {
      override fun Interceptor.Chain.value(): ProxySelector = proxySelector

      override fun Interceptor.Chain.withOverride(value: ProxySelector): Interceptor.Chain = withProxySelector(value)

      override fun OkHttpClient.Builder.withOverride(value: ProxySelector): OkHttpClient.Builder = proxySelector(value)

      override val nonDefaultValue: ProxySelector =
        object : ProxySelector() {
          override fun select(uri: URI?): MutableList<Proxy> = mutableListOf(java.net.Proxy.NO_PROXY)

          override fun connectFailed(
            uri: URI?,
            sa: SocketAddress?,
            ioe: java.io.IOException?,
          ) {
          }
        }

      override val badValue: ProxySelector =
        object : ProxySelector() {
          override fun select(uri: URI?): MutableList<Proxy> = TODO()

          override fun connectFailed(
            uri: URI?,
            sa: SocketAddress?,
            ioe: java.io.IOException?,
          ) {
          }
        }

      override fun isDefaultValue(value: ProxySelector): Boolean = value === ProxySelector.getDefault()
    }

    object ProxyAuthenticatorOverride : Override<Authenticator> {
      override fun Interceptor.Chain.value(): Authenticator = proxyAuthenticator

      override fun Interceptor.Chain.withOverride(value: Authenticator): Interceptor.Chain = withProxyAuthenticator(value)

      override fun OkHttpClient.Builder.withOverride(value: Authenticator): OkHttpClient.Builder = proxyAuthenticator(value)

      override val nonDefaultValue: Authenticator = Authenticator { route, response -> response.request }

      override val badValue: Authenticator = Authenticator { route, response -> TODO() }

      override fun isDefaultValue(value: Authenticator): Boolean = value === Authenticator.NONE
    }

    object SslSocketFactoryOverride : Override<SSLSocketFactory?> {
      override fun Interceptor.Chain.value(): SSLSocketFactory? = sslSocketFactoryOrNull

      override fun Interceptor.Chain.withOverride(value: SSLSocketFactory?): Interceptor.Chain =
        withSslSocketFactory(value, x509TrustManagerOrNull)

      override fun OkHttpClient.Builder.withOverride(value: SSLSocketFactory?): OkHttpClient.Builder =
        sslSocketFactory(value!!, x509TrustManagerOrNull!!)

      override val nonDefaultValue: SSLSocketFactory =
        object :
          DelegatingSSLSocketFactory(Platform.get().newSslSocketFactory(Platform.get().platformTrustManager())) {}

      override val badValue: SSLSocketFactory =
        object : DelegatingSSLSocketFactory(Platform.get().newSslSocketFactory(Platform.get().platformTrustManager())) {
          override fun configureSocket(sslSocket: SSLSocket): SSLSocket = TODO()
        }

      override fun isDefaultValue(value: SSLSocketFactory?): Boolean = value !is DelegatingSSLSocketFactory
    }

    object X509TrustManagerOverride : Override<X509TrustManager?> {
      override val paramName: String = "sslSocketFactory"

      override fun Interceptor.Chain.value(): X509TrustManager? = x509TrustManagerOrNull

      override fun Interceptor.Chain.withOverride(value: X509TrustManager?): Interceptor.Chain =
        withSslSocketFactory(Platform.get().newSslSocketFactory(value!!), value)

      override fun OkHttpClient.Builder.withOverride(value: X509TrustManager?): OkHttpClient.Builder =
        sslSocketFactory(Platform.get().newSslSocketFactory(value!!), value)

      override val nonDefaultValue: X509TrustManager =
        object : X509TrustManager {
          override fun checkClientTrusted(
            x509Certificates: Array<X509Certificate>,
            s: String,
          ) {
          }

          override fun checkServerTrusted(
            x509Certificates: Array<X509Certificate>,
            s: String,
          ) {
          }

          override fun getAcceptedIssuers(): Array<X509Certificate> = arrayOf()
        }

      override val badValue: X509TrustManager =
        object : X509TrustManager {
          override fun checkClientTrusted(
            x509Certificates: Array<X509Certificate>,
            s: String,
          ) {
          }

          override fun checkServerTrusted(
            x509Certificates: Array<X509Certificate>,
            s: String,
          ) {
            TODO()
          }

          override fun getAcceptedIssuers(): Array<X509Certificate> = arrayOf()
        }

      override fun isDefaultValue(value: X509TrustManager?): Boolean =
        !value
          ?.javaClass
          ?.name
          .orEmpty()
          .startsWith("okhttp")
    }

    object HostnameVerifierOverride : Override<HostnameVerifier> {
      override fun Interceptor.Chain.value(): HostnameVerifier = hostnameVerifier

      override fun Interceptor.Chain.withOverride(value: HostnameVerifier): Interceptor.Chain = withHostnameVerifier(value)

      override fun OkHttpClient.Builder.withOverride(value: HostnameVerifier): OkHttpClient.Builder = hostnameVerifier(value)

      override val nonDefaultValue: HostnameVerifier = HostnameVerifier { _, _ -> true }

      override val badValue: HostnameVerifier = HostnameVerifier { _, _ -> TODO() }

      override fun isDefaultValue(value: HostnameVerifier): Boolean = value === okhttp3.internal.tls.OkHostnameVerifier
    }

    object CertificatePinnerOverride : Override<CertificatePinner> {
      override fun Interceptor.Chain.value(): CertificatePinner = certificatePinner

      override fun Interceptor.Chain.withOverride(value: CertificatePinner): Interceptor.Chain = withCertificatePinner(value)

      override fun OkHttpClient.Builder.withOverride(value: CertificatePinner): OkHttpClient.Builder = certificatePinner(value)

      override val nonDefaultValue: CertificatePinner =
        CertificatePinner
          .Builder()
          .add("publicobject.com", "sha256/afwiKY3RxoMmLkuRW1l7QsPZTJPwDS2pdDROQjXw8ig=")
          .build()

      override val badValue: CertificatePinner =
        CertificatePinner.Builder().add("localhost", "sha256/afwiKY3RxoMmLkuRW1l7QsPZTJPwDS2pdDROQjXw8ig=").build()

      override fun isDefaultValue(value: CertificatePinner): Boolean = value.pins.isEmpty()
    }

    object ConnectionPoolOverride : Override<ConnectionPool> {
      override fun Interceptor.Chain.value(): ConnectionPool = connectionPool

      override fun Interceptor.Chain.withOverride(value: ConnectionPool): Interceptor.Chain = withConnectionPool(value)

      override fun OkHttpClient.Builder.withOverride(value: ConnectionPool): OkHttpClient.Builder = connectionPool(value)

      override val nonDefaultValue: ConnectionPool = ConnectionPool(keepAliveDuration = 1, timeUnit = TimeUnit.MINUTES)

      override val badValue: ConnectionPool =
        ConnectionPool(
          keepAliveDuration = 1,
          timeUnit = TimeUnit.MINUTES,
          connectionListener =
            object : ConnectionListener() {
              override fun connectStart(
                route: Route,
                call: Call,
              ): Unit = TODO()
            },
        )

      override fun isDefaultValue(value: ConnectionPool): Boolean = value.delegate.keepAliveDurationNs == 5.minutes.inWholeNanoseconds
    }

    object ConnectTimeoutOverride : Override<Int> {
      override fun Interceptor.Chain.value(): Int = connectTimeoutMillis()

      override fun Interceptor.Chain.withOverride(value: Int): Interceptor.Chain = withConnectTimeout(value, TimeUnit.MILLISECONDS)

      override fun OkHttpClient.Builder.withOverride(value: Int): OkHttpClient.Builder =
        connectTimeout(value.toLong(), TimeUnit.MILLISECONDS)

      override val nonDefaultValue: Int = 5000

      override val badValue: Int
        get() = 10

      override fun isDefaultValue(value: Int): Boolean = value == 10000
    }

    object ReadTimeoutOverride : Override<Int> {
      override fun Interceptor.Chain.value(): Int = readTimeoutMillis()

      override fun Interceptor.Chain.withOverride(value: Int): Interceptor.Chain = withReadTimeout(value, TimeUnit.MILLISECONDS)

      override fun OkHttpClient.Builder.withOverride(value: Int): OkHttpClient.Builder = readTimeout(value.toLong(), TimeUnit.MILLISECONDS)

      override val nonDefaultValue: Int = 5000

      override val badValue: Int
        get() = 10

      override fun isDefaultValue(value: Int): Boolean = value == 10000
    }

    object WriteTimeoutOverride : Override<Int> {
      override fun Interceptor.Chain.value(): Int = writeTimeoutMillis()

      override fun Interceptor.Chain.withOverride(value: Int): Interceptor.Chain = withWriteTimeout(value, TimeUnit.MILLISECONDS)

      override fun OkHttpClient.Builder.withOverride(value: Int): OkHttpClient.Builder = writeTimeout(value.toLong(), TimeUnit.MILLISECONDS)

      override val nonDefaultValue: Int = 5000

      override val badValue: Int
        get() = 10

      override fun isDefaultValue(value: Int): Boolean = value == 10000
    }

    object RetryOnConnectionFailureOverride : Override<Boolean> {
      override fun Interceptor.Chain.value(): Boolean = retryOnConnectionFailure

      override fun Interceptor.Chain.withOverride(value: Boolean): Interceptor.Chain = withRetryOnConnectionFailure(value)

      override fun OkHttpClient.Builder.withOverride(value: Boolean): OkHttpClient.Builder = retryOnConnectionFailure(value)

      override val nonDefaultValue: Boolean = false

      override val badValue: Boolean
        get() = false

      override fun isDefaultValue(value: Boolean): Boolean = value
    }
  }

  private fun enableTls() {
    client =
      client
        .newBuilder()
        .sslSocketFactory(
          handshakeCertificates.sslSocketFactory(),
          handshakeCertificates.trustManager,
        ).build()
    server.useHttps(handshakeCertificates.sslSocketFactory())
  }
}
