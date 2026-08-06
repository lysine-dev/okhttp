/*
 * Copyright (C) 2022 Block, Inc.
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
package okhttp3.internal.connection

import java.io.IOException
import java.net.ConnectException
import java.net.HttpURLConnection
import java.net.NoRouteToHostException
import java.net.ProtocolException
import java.net.Proxy
import java.net.Socket as JavaNetSocket
import java.net.UnknownServiceException
import java.security.cert.X509Certificate
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLPeerUnverifiedException
import javax.net.ssl.SSLSocket
import okhttp3.CertificatePinner
import okhttp3.ConnectionSpec
import okhttp3.Handshake
import okhttp3.Handshake.Companion.handshake
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Route
import okhttp3.internal.closeQuietly
import okhttp3.internal.concurrent.TaskRunner
import okhttp3.internal.concurrent.withLock
import okhttp3.internal.connection.RoutePlanner.ConnectResult
import okhttp3.internal.http.ExchangeCodec
import okhttp3.internal.http1.Http1ExchangeCodec
import okhttp3.internal.platform.Platform
import okhttp3.internal.tls.OkHostnameVerifier
import okhttp3.internal.toHostHeader

/**
 * A single attempt to connect to a remote server, including these steps:
 *
 *  * [TCP handshake][connectSocket]
 *  * Optional [CONNECT tunnels][connectTunnel]. When using an HTTP proxy to reach an HTTPS server
 *    we must send a `CONNECT` request, and handle authorization challenges from the proxy.
 *  * Optional [TLS handshake][connectTls].
 *
 * Each step may fail. If a retry is possible, a new instance is created with the next plan, which
 * will be configured differently.
 */
class ConnectPlan internal constructor(
  private val taskRunner: TaskRunner,
  private val connectionPool: RealConnectionPool,
  private val readTimeoutMillis: Int,
  private val writeTimeoutMillis: Int,
  private val socketConnectTimeoutMillis: Int,
  private val socketReadTimeoutMillis: Int,
  private val pingIntervalMillis: Int,
  private val retryOnConnectionFailure: Boolean,
  private val call: RealCall,
  private val routePlanner: RealRoutePlanner,
  // Specifics to this plan.
  override val route: Route,
  internal val routes: List<Route>?,
  private val attempt: Int,
  private val tunnelRequest: Request?,
  internal val connectionSpecIndex: Int,
  internal val isTlsFallback: Boolean,
) : RoutePlanner.Plan,
  ExchangeCodec.Carrier {
  /** True if this connect was canceled; typically because it lost a race. */
  @Volatile private var canceled = false

  // These properties are initialized by connect() and never reassigned.

  /** The low-level TCP socket. */
  private var rawSocket: JavaNetSocket? = null

  /**
   * The application layer socket. Either an [SSLSocket] layered over [rawSocket], or [rawSocket]
   * itself if this connection does not use SSL.
   */
  internal var javaNetSocket: JavaNetSocket? = null
  private var handshake: Handshake? = null
  private var protocol: Protocol? = null
  private lateinit var socket: BufferedSocket
  private var connection: RealConnection? = null

  /** True if this connection is ready for use, including TCP, tunnels, and TLS. */
  override val isReady: Boolean
    get() = protocol != null

  private fun copy(
    attempt: Int = this.attempt,
    tunnelRequest: Request? = this.tunnelRequest,
    connectionSpecIndex: Int = this.connectionSpecIndex,
    isTlsFallback: Boolean = this.isTlsFallback,
  ): ConnectPlan =
    ConnectPlan(
      taskRunner = taskRunner,
      connectionPool = connectionPool,
      readTimeoutMillis = readTimeoutMillis,
      writeTimeoutMillis = writeTimeoutMillis,
      socketConnectTimeoutMillis = socketConnectTimeoutMillis,
      socketReadTimeoutMillis = socketReadTimeoutMillis,
      pingIntervalMillis = pingIntervalMillis,
      retryOnConnectionFailure = retryOnConnectionFailure,
      call = call,
      routePlanner = routePlanner,
      route = route,
      routes = routes,
      attempt = attempt,
      tunnelRequest = tunnelRequest,
      connectionSpecIndex = connectionSpecIndex,
      isTlsFallback = isTlsFallback,
    )

  override fun connectTcp(): ConnectResult {
    check(rawSocket == null) { "TCP already connected" }

    var success = false

    // Tell the call about the connecting call so async cancels work.
    call.plansToCancel += this
    try {
      call.eventListener.connectStart(call, route.socketAddress, route.proxy)
      connectionPool.connectionListener.connectStart(route, call)

      connectSocket()
      success = true
      return ConnectResult(plan = this)
    } catch (e: IOException) {
      // If we used the ProxySelector, and got a IOException during connect, report the failure.
      if (route.address.proxy == null && route.proxy.type() != Proxy.Type.DIRECT) {
        route.address.proxySelector.connectFailed(
          route.address.url.toUri(),
          route.proxy.address(),
          e,
        )
      }
      call.eventListener.connectFailed(call, route.socketAddress, route.proxy, null, e)
      connectionPool.connectionListener.connectFailed(route, call, e)
      return ConnectResult(plan = this, throwable = e)
    } finally {
      call.plansToCancel -= this
      if (!success) {
        rawSocket?.closeQuietly()
      }
    }
  }

  override fun connectTlsEtc(): ConnectResult {
    val rawSocket = requireNotNull(rawSocket) { "TCP not connected" }
    check(!isReady) { "already connected" }

    val connectionSpecs = route.address.connectionSpecs
    var retryTlsConnection: ConnectPlan? = null
    var success = false

    // Tell the call about the connecting call so async cancels work.
    call.plansToCancel += this
    try {
      if (tunnelRequest != null) {
        val tunnelResult = connectTunnel()

        // Tunnel didn't work. Start it all again.
        if (tunnelResult.nextPlan != null || tunnelResult.throwable != null) {
          return tunnelResult
        }
      }

      if (route.address.sslSocketFactory != null) {
        // Assume the server won't send a TLS ServerHello until we send a TLS ClientHello. If
        // that happens, then we will have buffered bytes that are needed by the SSLSocket!
        // This check is imperfect: it doesn't tell us whether a handshake will succeed, just
        // that it will almost certainly fail because the proxy has sent unexpected data.
        if (!socket.source.buffer.exhausted() || !socket.sink.buffer.exhausted()) {
          throw IOException("TLS tunnel buffered too many bytes!")
        }

        call.eventListener.secureConnectStart(call)

        // Create the wrapper over the connected socket.
        val sslSocket =
          route.address.sslSocketFactory.createSocket(
            rawSocket,
            route.address.url.host,
            route.address.url.port,
            // autoClose:
            true,
          ) as SSLSocket

        val tlsEquipPlan = planWithCurrentOrInitialConnectionSpec(connectionSpecs, sslSocket)
        val connectionSpec = connectionSpecs[tlsEquipPlan.connectionSpecIndex]

        // Figure out the next connection spec in case we need a retry.
        retryTlsConnection = tlsEquipPlan.nextConnectionSpec(connectionSpecs, sslSocket)

        connectionSpec.apply(sslSocket, isFallback = tlsEquipPlan.isTlsFallback)
        connectTls(sslSocket, connectionSpec)
        call.eventListener.secureConnectEnd(call, handshake)
      } else {
        javaNetSocket = rawSocket
        protocol =
          when {
            Protocol.H2_PRIOR_KNOWLEDGE in route.address.protocols -> Protocol.H2_PRIOR_KNOWLEDGE
            else -> Protocol.HTTP_1_1
          }
      }

      val connection =
        RealConnection(
          taskRunner = taskRunner,
          connectionPool = connectionPool,
          route = route,
          rawSocket = rawSocket,
          javaNetSocket = javaNetSocket!!,
          handshake = handshake,
          protocol = protocol!!,
          socket = socket,
          pingIntervalMillis = pingIntervalMillis,
          connectionListener = connectionPool.connectionListener,
        )
      this.connection = connection
      connection.start()

      // Success.
      call.eventListener.connectEnd(call, route.socketAddress, route.proxy, protocol)
      success = true
      return ConnectResult(plan = this)
    } catch (e: IOException) {
      call.eventListener.connectFailed(call, route.socketAddress, route.proxy, null, e)
      connectionPool.connectionListener.connectFailed(route, call, e)

      if (!retryOnConnectionFailure || !retryTlsHandshake(e)) {
        retryTlsConnection = null
      }

      return ConnectResult(
        plan = this,
        nextPlan = retryTlsConnection,
        throwable = e,
      )
    } finally {
      call.plansToCancel -= this
      if (!success) {
        javaNetSocket?.closeQuietly()
        rawSocket.closeQuietly()
      }
    }
  }

  /** Does all the work necessary to build a full HTTP or HTTPS connection on a raw socket. */
  @Throws(IOException::class)
  private fun connectSocket() {
    val rawSocket =
      when (route.proxy.type()) {
        Proxy.Type.DIRECT, Proxy.Type.HTTP -> route.address.socketFactory.createSocket()!!
        else -> JavaNetSocket(route.proxy)
      }
    this.rawSocket = rawSocket

    // Handle the race where cancel() precedes connectSocket(). We don't want to miss a cancel.
    if (canceled) {
      throw IOException("canceled")
    }

    rawSocket.soTimeout = socketReadTimeoutMillis
    try {
      Platform.get().connectSocket(rawSocket, route.socketAddress, socketConnectTimeoutMillis)
    } catch (e: ConnectException) {
      throw ConnectException("Failed to connect to ${route.socketAddress}").apply {
        initCause(e)
      }
    }

    // The following try/catch block is a pseudo hacky way to get around a crash on Android 7.0
    // More details:
    // https://github.com/lysine-dev/okhttp/issues/3245
    // https://android-review.googlesource.com/#/c/271775/
    try {
      this.socket = rawSocket.asBufferedSocket()
    } catch (npe: NullPointerException) {
      if (npe.message == NPE_THROW_WITH_NULL) {
        throw IOException(npe)
      }
    }
  }

  /**
   * Does all the work to build an HTTPS connection over a proxy tunnel. The catch here is that a
   * proxy server can issue an auth challenge and then close the connection.
   *
   * @return the next plan to attempt, or null if no further attempt should be made either because
   *     we've successfully connected or because no further attempts should be made.
   */
  @Throws(IOException::class)
  internal fun connectTunnel(): ConnectResult {
    val nextTunnelRequest =
      createTunnel()
        ?: return ConnectResult(plan = this) // Success.

    // The proxy decided to close the connection after an auth challenge. Retry with different
    // auth credentials.
    rawSocket?.closeQuietly()

    val nextAttempt = attempt + 1
    return when {
      nextAttempt < MAX_TUNNEL_ATTEMPTS -> {
        call.eventListener.connectEnd(call, route.socketAddress, route.proxy, null)
        ConnectResult(
          plan = this,
          nextPlan =
            copy(
              attempt = nextAttempt,
              tunnelRequest = nextTunnelRequest,
            ),
        )
      }

      else -> {
        val failure =
          ProtocolException(
            "Too many tunnel connections attempted: $MAX_TUNNEL_ATTEMPTS",
          )
        call.eventListener.connectFailed(call, route.socketAddress, route.proxy, null, failure)
        connectionPool.connectionListener.connectFailed(route, call, failure)
        return ConnectResult(plan = this, throwable = failure)
      }
    }
  }

  @Throws(IOException::class)
  private fun connectTls(
    sslSocket: SSLSocket,
    connectionSpec: ConnectionSpec,
  ) {
    val address = route.address
    var success = false
    try {
      if (connectionSpec.supportsTlsExtensions) {
        Platform.get().configureTlsExtensions(
          sslSocket = sslSocket,
          hostname = address.url.host,
          protocols = address.protocols,
          echConfigList = route.echConfigList,
        )
      }

      // Force handshake. This can throw!
      sslSocket.startHandshake()
      // block for session establishment
      val sslSocketSession = sslSocket.session
      val unverifiedHandshake = sslSocketSession.handshake()

      // Verify that the socket's certificates are acceptable for the target host.
      if (!address.hostnameVerifier!!.verify(address.url.host, sslSocketSession)) {
        val peerCertificates = unverifiedHandshake.peerCertificates
        if (peerCertificates.isNotEmpty()) {
          val cert = peerCertificates[0] as X509Certificate
          throw SSLPeerUnverifiedException(
            """
            |Hostname ${address.url.host} not verified:
            |    certificate: ${CertificatePinner.pin(cert)}
            |    DN: ${cert.subjectDN.name}
            |    subjectAltNames: ${OkHostnameVerifier.allSubjectAltNames(cert)}
            """.trimMargin(),
          )
        } else {
          throw SSLPeerUnverifiedException(
            "Hostname ${address.url.host} not verified (no certificates)",
          )
        }
      }

      val certificatePinner = address.certificatePinner!!

      val handshake =
        Handshake(
          unverifiedHandshake.tlsVersion,
          unverifiedHandshake.cipherSuite,
          unverifiedHandshake.localCertificates,
        ) {
          certificatePinner.certificateChainCleaner!!.clean(
            unverifiedHandshake.peerCertificates,
            address.url.host,
          )
        }
      this.handshake = handshake

      // Check that the certificate pinner is satisfied by the certificates presented.
      certificatePinner.check(address.url.host) {
        handshake.peerCertificates.map { it as X509Certificate }
      }

      // Success! Save the handshake and the ALPN protocol.
      val maybeProtocol =
        if (connectionSpec.supportsTlsExtensions) {
          Platform.get().getSelectedProtocol(sslSocket)
        } else {
          null
        }
      javaNetSocket = sslSocket
      val rawSocket = requireNotNull(rawSocket) { "TCP not connected" }
      val sslBuffered = sslSocket.asBufferedSocket()
      // Close the raw TCP socket before the SSL layer: SSLSocket.close() may block for many
      // seconds attempting to send close_notify on a half-open connection.
      socket =
        object : BufferedSocket by sslBuffered {
          override fun cancel() {
            rawSocket.closeQuietly()
            sslBuffered.cancel()
          }
        }
      protocol = if (maybeProtocol != null) Protocol.get(maybeProtocol) else Protocol.HTTP_1_1
      success = true
    } finally {
      Platform.get().afterHandshake(sslSocket)
      if (!success) {
        sslSocket.closeQuietly()
      }
    }
  }

  /**
   * To make an HTTPS connection over an HTTP proxy, send an unencrypted CONNECT request to create
   * the proxy connection. This may need to be retried if the proxy requires authorization.
   */
  @Throws(IOException::class)
  private fun createTunnel(): Request? {
    var nextRequest = tunnelRequest!!
    // Make an SSL Tunnel on the first message pair of each SSL + proxy connection.
    val url = route.address.url
    val requestLine = "CONNECT ${url.toHostHeader(includeDefaultPort = true)} HTTP/1.1"
    while (true) {
      val tunnelCodec =
        Http1ExchangeCodec(
          // No client for CONNECT tunnels:
          client = null,
          carrier = this,
          socket = socket,
        )
      socket.source.timeout().timeout(readTimeoutMillis.toLong(), TimeUnit.MILLISECONDS)
      socket.sink.timeout().timeout(writeTimeoutMillis.toLong(), TimeUnit.MILLISECONDS)
      tunnelCodec.writeRequest(nextRequest.headers, requestLine)
      tunnelCodec.finishRequest()
      val response =
        tunnelCodec
          .readResponseHeaders(false)!!
          .request(nextRequest)
          .build()
      tunnelCodec.skipConnectBody(response)

      when (response.code) {
        HttpURLConnection.HTTP_OK -> {
          return null
        }

        HttpURLConnection.HTTP_PROXY_AUTH -> {
          nextRequest = route.address.proxyAuthenticator.authenticate(route, response)
            ?: throw IOException("Failed to authenticate with proxy")

          if ("close".equals(response.header("Connection"), ignoreCase = true)) {
            return nextRequest
          }
        }

        else -> {
          throw IOException("Unexpected response code for CONNECT: ${response.code}")
        }
      }
    }
  }

  /**
   * Returns this if its [connectionSpecIndex] is defined, or a new connection with it defined
   * otherwise.
   */
  @Throws(IOException::class)
  internal fun planWithCurrentOrInitialConnectionSpec(
    connectionSpecs: List<ConnectionSpec>,
    sslSocket: SSLSocket,
  ): ConnectPlan {
    if (connectionSpecIndex != -1) return this
    return nextConnectionSpec(connectionSpecs, sslSocket)
      ?: throw UnknownServiceException(
        "Unable to find acceptable protocols." +
          " isFallback=$isTlsFallback," +
          " modes=$connectionSpecs," +
          " supported protocols=${sslSocket.enabledProtocols!!.contentToString()}",
      )
  }

  /**
   * Returns a copy of this connection with the next connection spec to try, or null if no other
   * compatible connection specs are available.
   */
  internal fun nextConnectionSpec(
    connectionSpecs: List<ConnectionSpec>,
    sslSocket: SSLSocket,
  ): ConnectPlan? {
    for (i in connectionSpecIndex + 1 until connectionSpecs.size) {
      if (connectionSpecs[i].isCompatible(sslSocket)) {
        return copy(connectionSpecIndex = i, isTlsFallback = (connectionSpecIndex != -1))
      }
    }
    return null
  }

  /** Returns the connection to use, which might be different from [connection]. */
  override fun handleSuccess(): RealConnection {
    call.client.routeDatabase.connected(route)

    val connection = this.connection!!
    connection.connectionListener.connectEnd(connection, route, call)

    // If we raced another call connecting to this host, coalesce the connections. This makes for
    // 3 different lookups in the connection pool!
    val pooled3 = routePlanner.planReusePooledConnection(this, routes)
    if (pooled3 != null) return pooled3.connection

    connection.withLock {
      connectionPool.put(connection)
      call.acquireConnectionNoEvents(connection)
    }

    call.eventListener.connectionAcquired(call, connection)
    connection.connectionListener.connectionAcquired(connection, call)
    return connection
  }

  override fun trackFailure(
    call: RealCall,
    e: IOException?,
  ) {
    // Do nothing.
  }

  override fun noNewExchanges() {
    // Do nothing.
  }

  override fun cancel() {
    canceled = true
    // Close the raw socket so we don't end up doing synchronous I/O.
    rawSocket?.closeQuietly()
  }

  override fun retry(): RoutePlanner.Plan =
    ConnectPlan(
      taskRunner = taskRunner,
      connectionPool = connectionPool,
      readTimeoutMillis = readTimeoutMillis,
      writeTimeoutMillis = writeTimeoutMillis,
      socketConnectTimeoutMillis = socketConnectTimeoutMillis,
      socketReadTimeoutMillis = socketReadTimeoutMillis,
      pingIntervalMillis = pingIntervalMillis,
      retryOnConnectionFailure = retryOnConnectionFailure,
      call = call,
      routePlanner = routePlanner,
      route = route,
      routes = routes,
      attempt = attempt,
      tunnelRequest = tunnelRequest,
      connectionSpecIndex = connectionSpecIndex,
      isTlsFallback = isTlsFallback,
    )

  fun closeQuietly() {
    javaNetSocket?.closeQuietly()
  }

  companion object {
    private const val NPE_THROW_WITH_NULL = "throw with null exception"
    private const val MAX_TUNNEL_ATTEMPTS = 21
  }
}
