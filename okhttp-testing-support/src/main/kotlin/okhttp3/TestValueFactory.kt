/*
 * Copyright (C) 2022 Square, Inc.
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
@file:Suppress(
  "CANNOT_OVERRIDE_INVISIBLE_MEMBER",
  "INVISIBLE_MEMBER",
  "INVISIBLE_REFERENCE",
)

package okhttp3

import java.io.Closeable
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.ProxySelector
import java.net.Socket
import java.util.concurrent.TimeUnit
import javax.net.ssl.HostnameVerifier
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLSocketFactory
import okhttp3.internal.RecordingOkAuthenticator
import okhttp3.internal.concurrent.TaskFaker
import okhttp3.internal.concurrent.TaskRunner
import okhttp3.internal.concurrent.withLock
import okhttp3.internal.connection.ConnectionListener
import okhttp3.internal.connection.RealCall
import okhttp3.internal.connection.RealConnection
import okhttp3.internal.connection.RealConnectionPool
import okhttp3.internal.connection.RealRoutePlanner
import okhttp3.internal.http.RealInterceptorChain
import okhttp3.internal.http.RecordingProxySelector
import okhttp3.internal.platform.Platform
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.internal.TlsUtil.localhost

/**
 * OkHttp is usually tested with functional tests: these use public APIs to confirm behavior against
 * MockWebServer. In cases where logic is particularly tricky, we use unit tests. This class makes
 * it easy to get sample values to use in such tests.
 *
 * This class is pretty fast and loose with default values: it attempts to provide values that are
 * well-formed, but doesn't guarantee values are internally consistent. Callers must take care to
 * configure the factory when sample values impact the correctness of the test.
 */
class TestValueFactory : Closeable {
  var taskFaker: TaskFaker = TaskFaker()
  var taskRunner: TaskRunner = taskFaker.taskRunner
  var dns: Dns = Dns.SYSTEM
  var proxy: Proxy = Proxy.NO_PROXY
  var proxySelector: ProxySelector = RecordingProxySelector()
  var proxyAuthenticator: Authenticator = RecordingOkAuthenticator("password", null)
  var connectionSpecs: List<ConnectionSpec> =
    listOf(
      ConnectionSpec.MODERN_TLS,
      ConnectionSpec.COMPATIBLE_TLS,
      ConnectionSpec.CLEARTEXT,
    )
  var protocols: List<Protocol> =
    listOf(
      Protocol.HTTP_1_1,
    )
  var handshakeCertificates: HandshakeCertificates = localhost()
  var sslSocketFactory: SSLSocketFactory? = handshakeCertificates.sslSocketFactory()
  var hostnameVerifier: HostnameVerifier? = HttpsURLConnection.getDefaultHostnameVerifier()
  var uriHost: String = "example.com"
  var uriPort: Int = 1

  fun newConnection(
    pool: RealConnectionPool,
    route: Route,
    idleAtNanos: Long = Long.MAX_VALUE,
    taskRunner: TaskRunner = this.taskRunner,
  ): RealConnection {
    val result =
      RealConnection.newTestConnection(
        taskRunner = taskRunner,
        connectionPool = pool,
        route = route,
        socket = Socket(),
        idleAtNs = idleAtNanos,
      )
    result.withLock { pool.put(result) }
    return result
  }

  fun newConnectionPool(
    taskRunner: TaskRunner = this.taskRunner,
    maxIdleConnections: Int = Int.MAX_VALUE,
  ): RealConnectionPool =
    RealConnectionPool(
      taskRunner = taskRunner,
      maxIdleConnections = maxIdleConnections,
      keepAliveDuration = 100L,
      timeUnit = TimeUnit.NANOSECONDS,
      connectionListener = ConnectionListener.NONE,
    )

  /** Returns an address that's without an SSL socket factory or hostname verifier.  */
  fun newAddress(
    uriHost: String = this.uriHost,
    uriPort: Int = this.uriPort,
    proxy: Proxy? = null,
    proxySelector: ProxySelector = this.proxySelector,
  ): Address =
    Address(
      uriHost = uriHost,
      uriPort = uriPort,
      dns = dns,
      socketFactory = Platform.get().socketFactory,
      sslSocketFactory = null,
      hostnameVerifier = null,
      certificatePinner = null,
      proxyAuthenticator = proxyAuthenticator,
      proxy = proxy,
      protocols = protocols,
      connectionSpecs = connectionSpecs,
      proxySelector = proxySelector,
    )

  fun newHttpsAddress(
    uriHost: String = this.uriHost,
    uriPort: Int = this.uriPort,
    proxy: Proxy? = null,
    proxySelector: ProxySelector = this.proxySelector,
    sslSocketFactory: SSLSocketFactory? = this.sslSocketFactory,
    hostnameVerifier: HostnameVerifier? = this.hostnameVerifier,
  ): Address =
    Address(
      uriHost = uriHost,
      uriPort = uriPort,
      dns = dns,
      socketFactory = Platform.get().socketFactory,
      sslSocketFactory = sslSocketFactory,
      hostnameVerifier = hostnameVerifier,
      certificatePinner = null,
      proxyAuthenticator = proxyAuthenticator,
      proxy = proxy,
      protocols = protocols,
      connectionSpecs = connectionSpecs,
      proxySelector = proxySelector,
    )

  fun newRoute(
    address: Address = newAddress(),
    proxy: Proxy = this.proxy,
    socketAddress: InetSocketAddress = InetSocketAddress.createUnresolved(uriHost, uriPort),
  ): Route =
    Route(
      address = address,
      proxy = proxy,
      socketAddress = socketAddress,
    )

  fun newChain(call: RealCall): RealInterceptorChain =
    RealInterceptorChain(
      call = call,
      interceptors = listOf(),
      index = 0,
      exchange = null,
      request = call.request(),
    )

  fun newRoutePlanner(
    client: OkHttpClient,
    address: Address = newAddress(),
  ): RealRoutePlanner {
    val call = RealCall(client, Request(address.url), forWebSocket = false)
    val chain = newChain(call)
    return RealRoutePlanner(
      taskRunner = client.taskRunner,
      connectionPool = client.connectionPool.delegate,
      readTimeoutMillis = client.readTimeoutMillis,
      writeTimeoutMillis = client.writeTimeoutMillis,
      socketConnectTimeoutMillis = chain.connectTimeoutMillis,
      socketReadTimeoutMillis = chain.readTimeoutMillis,
      pingIntervalMillis = client.pingIntervalMillis,
      retryOnConnectionFailure = client.retryOnConnectionFailure,
      fastFallback = client.fastFallback,
      address = address,
      routeDatabase = client.routeDatabase,
      call = call,
      request = call.request(),
    )
  }

  override fun close() {
    taskFaker.close()
  }
}
