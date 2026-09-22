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
package okhttp3.internal.connection

import java.io.IOException
import java.net.HttpURLConnection
import java.net.Socket
import java.net.UnknownServiceException
import okhttp3.Address
import okhttp3.ConnectionSpec
import okhttp3.HttpUrl
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.Route
import okhttp3.internal.OkHttpInternalApi
import okhttp3.internal.USER_AGENT
import okhttp3.internal.canReuseConnectionFor
import okhttp3.internal.closeQuietly
import okhttp3.internal.concurrent.TaskRunner
import okhttp3.internal.concurrent.withLock
import okhttp3.internal.connection.RoutePlanner.Plan
import okhttp3.internal.platform.Platform
import okhttp3.internal.toHostHeader

@OkHttpInternalApi
class RealRoutePlanner internal constructor(
  private val taskRunner: TaskRunner,
  private val connectionPool: RealConnectionPool,
  private val readTimeoutMillis: Int,
  private val writeTimeoutMillis: Int,
  private val socketConnectTimeoutMillis: Int,
  private val socketReadTimeoutMillis: Int,
  private val pingIntervalMillis: Int,
  private val retryOnConnectionFailure: Boolean,
  private val fastFallback: Boolean,
  override val address: Address,
  private val routeDatabase: RouteDatabase,
  private val call: RealCall,
  request: Request,
) : RoutePlanner {
  private val doExtensiveHealthChecks = request.method != "GET"
  private var routeSelection: RouteSelector.Selection? = null
  private var routeSelector: RouteSelector? = null
  private var nextRouteToTry: Route? = null

  override val deferredPlans = ArrayDeque<Plan>()

  override fun isCanceled(): Boolean = call.isCanceled()

  @Throws(IOException::class)
  override fun plan(): Plan {
    val reuseCallConnection = planReuseCallConnection()
    if (reuseCallConnection != null) return reuseCallConnection

    // Attempt to get a connection from the pool.
    val pooled1 = planReusePooledConnection()
    if (pooled1 != null) return pooled1

    // Attempt a deferred plan before new routes.
    if (deferredPlans.isNotEmpty()) return deferredPlans.removeFirst()

    // Do blocking calls to plan a route for a new connection.
    val connect = planConnect()

    // Now that we have a set of IP addresses, make another attempt at getting a connection from
    // the pool. We have a better chance of matching thanks to connection coalescing.
    val pooled2 = planReusePooledConnection(connect, connect.routes)
    if (pooled2 != null) return pooled2

    return connect
  }

  /**
   * Returns the connection already attached to the call if it's eligible for a new exchange.
   *
   * If the call's connection exists and is eligible for another exchange, it is returned. If it
   * exists but cannot be used for another exchange, it is closed and this returns null.
   */
  private fun planReuseCallConnection(): ReusePlan? {
    // This may be mutated by releaseConnectionNoEvents()!
    val candidate = call.connection ?: return null

    // Make sure this connection is healthy & eligible for new exchanges. If it's no longer needed
    // then we're on the hook to close it.
    val healthy = candidate.isHealthy(doExtensiveHealthChecks)
    var noNewExchangesEvent = false
    val toClose: Socket? =
      candidate.withLock {
        when {
          !healthy -> {
            noNewExchangesEvent = !candidate.noNewExchanges
            candidate.noNewExchanges = true
            call.releaseConnectionNoEvents()
          }

          candidate.noNewExchanges || !sameHostAndPort(candidate.route().address.url) -> {
            call.releaseConnectionNoEvents()
          }

          else -> {
            null
          }
        }
      }

    // If the call's connection wasn't released, reuse it. We don't call connectionAcquired() here
    // because we already acquired it.
    if (call.connection != null) {
      check(toClose == null)
      return ReusePlan(candidate)
    }

    // The call's connection was released.
    toClose?.closeQuietly()
    call.eventListener.connectionReleased(call, candidate)
    candidate.connectionListener.connectionReleased(candidate, call)
    if (toClose != null) {
      candidate.connectionListener.connectionClosed(candidate)
    } else if (noNewExchangesEvent) {
      candidate.connectionListener.noNewExchanges(candidate)
    }
    return null
  }

  /** Plans to make a new connection by deciding which route to try next. */
  @Throws(IOException::class)
  internal fun planConnect(): ConnectPlan {
    // Use a route from a preceding coalesced connection.
    val localNextRouteToTry = nextRouteToTry
    if (localNextRouteToTry != null) {
      nextRouteToTry = null
      return planConnectToRoute(localNextRouteToTry)
    }

    // Use a route from an existing route selection.
    val existingRouteSelection = routeSelection
    if (existingRouteSelection != null && existingRouteSelection.hasNext()) {
      return planConnectToRoute(existingRouteSelection.next())
    }

    // Decide which proxy to use, if any. This may block in ProxySelector.select().
    var newRouteSelector = routeSelector
    if (newRouteSelector == null) {
      newRouteSelector =
        RouteSelector(
          address = address,
          routeDatabase = routeDatabase,
          call = call,
          fastFallback = fastFallback,
        )
      routeSelector = newRouteSelector
    }

    // List available IP addresses for the current proxy. This may block in Dns.lookup().
    if (!newRouteSelector.hasNext()) throw IOException("exhausted all routes")
    val newRouteSelection = newRouteSelector.next()
    routeSelection = newRouteSelection

    if (isCanceled()) throw IOException("Canceled")

    return planConnectToRoute(newRouteSelection.next(), newRouteSelection.routes)
  }

  /**
   * Returns a plan to reuse a pooled connection, or null if the pool doesn't have a connection for
   * this address.
   *
   * If [planToReplace] is non-null, this will swap it for a pooled connection if that pooled
   * connection uses HTTP/2. That results in fewer sockets overall and thus fewer TCP slow starts.
   */
  internal fun planReusePooledConnection(
    planToReplace: ConnectPlan? = null,
    routes: List<Route>? = null,
  ): ReusePlan? {
    val result =
      connectionPool.callAcquirePooledConnection(
        doExtensiveHealthChecks = doExtensiveHealthChecks,
        address = address,
        call = call,
        routes = routes,
        requireMultiplexed = planToReplace != null && planToReplace.isReady,
      ) ?: return null

    // If we coalesced our connection, remember the replaced connection's route. That way if the
    // coalesced connection later fails we don't waste a valid route.
    if (planToReplace != null) {
      nextRouteToTry = planToReplace.route
      planToReplace.closeQuietly()
    }

    call.eventListener.connectionAcquired(call, result)
    result.connectionListener.connectionAcquired(result, call)
    return ReusePlan(result)
  }

  /** Returns a plan for the first attempt at [route]. This throws if no plan is possible. */
  @Throws(IOException::class)
  internal fun planConnectToRoute(
    route: Route,
    routes: List<Route>? = null,
  ): ConnectPlan {
    if (route.address.sslSocketFactory == null) {
      if (ConnectionSpec.CLEARTEXT !in route.address.connectionSpecs) {
        throw UnknownServiceException("CLEARTEXT communication not enabled for client")
      }

      val host = route.address.url.host
      if (!Platform.get().isCleartextTrafficPermitted(host)) {
        throw UnknownServiceException(
          "CLEARTEXT communication to $host not permitted by network security policy",
        )
      }
    } else {
      if (Protocol.H2_PRIOR_KNOWLEDGE in route.address.protocols) {
        throw UnknownServiceException("H2_PRIOR_KNOWLEDGE cannot be used with HTTPS")
      }
    }

    val tunnelRequest =
      when {
        route.requiresTunnel() -> createTunnelRequest(route)
        else -> null
      }

    return ConnectPlan(
      taskRunner = taskRunner,
      connectionPool = connectionPool,
      readTimeoutMillis = readTimeoutMillis,
      writeTimeoutMillis = writeTimeoutMillis,
      socketConnectTimeoutMillis = socketConnectTimeoutMillis,
      socketReadTimeoutMillis = socketReadTimeoutMillis,
      pingIntervalMillis = pingIntervalMillis,
      retryOnConnectionFailure = retryOnConnectionFailure,
      call = call,
      routePlanner = this,
      route = route,
      routes = routes,
      attempt = 0,
      tunnelRequest = tunnelRequest,
      connectionSpecIndex = -1,
      isTlsFallback = false,
    )
  }

  /**
   * Returns a request that creates a TLS tunnel via an HTTP proxy. Everything in the tunnel request
   * is sent unencrypted to the proxy server, so tunnels include only the minimum set of headers.
   * This avoids sending potentially sensitive data like HTTP cookies to the proxy unencrypted.
   *
   * In order to support preemptive authentication we pass a fake "Auth Failed" response to the
   * authenticator. This gives the authenticator the option to customize the CONNECT request. It can
   * decline to do so by returning null, in which case OkHttp will use it as-is.
   */
  @Throws(IOException::class)
  private fun createTunnelRequest(route: Route): Request {
    val proxyConnectRequest =
      Request
        .Builder()
        .url(route.address.url)
        .method("CONNECT", null)
        .header("Host", route.address.url.toHostHeader(includeDefaultPort = true))
        .header("Proxy-Connection", "Keep-Alive") // For HTTP/1.0 proxies like Squid.
        .header("User-Agent", USER_AGENT)
        .build()

    val fakeAuthChallengeResponse =
      Response
        .Builder()
        .request(proxyConnectRequest)
        .protocol(Protocol.HTTP_1_1)
        .code(HttpURLConnection.HTTP_PROXY_AUTH)
        .message("Preemptive Authenticate")
        .sentRequestAtMillis(-1L)
        .receivedResponseAtMillis(-1L)
        .header("Proxy-Authenticate", "OkHttp-Preemptive")
        .build()

    val authenticatedRequest =
      route.address.proxyAuthenticator
        .authenticate(route, fakeAuthChallengeResponse)

    return authenticatedRequest ?: proxyConnectRequest
  }

  override fun hasNext(failedConnection: RealConnection?): Boolean {
    if (deferredPlans.isNotEmpty()) {
      return true
    }

    if (nextRouteToTry != null) {
      return true
    }

    if (failedConnection != null) {
      val retryRoute = retryRoute(failedConnection)
      if (retryRoute != null) {
        // Lock in the route because retryRoute() is racy and we don't want to call it twice.
        nextRouteToTry = retryRoute
        return true
      }
    }

    // If we have a routes left, use 'em.
    if (routeSelection?.hasNext() == true) return true

    // If we haven't initialized the route selector yet, assume it'll have at least one route.
    val localRouteSelector = routeSelector ?: return true

    // If we do have a route selector, use its routes.
    return localRouteSelector.hasNext()
  }

  /**
   * Return the route from [connection] if it should be retried, even if the connection itself is
   * unhealthy. The biggest gotcha here is that we shouldn't reuse routes from coalesced
   * connections.
   */
  private fun retryRoute(connection: RealConnection): Route? =
    connection.withLock {
      when {
        connection.routeFailureCount != 0 -> null

        // This route is still in use.
        !connection.noNewExchanges -> null

        !connection
          .route()
          .address.url
          .canReuseConnectionFor(address.url) -> null

        else -> connection.route()
      }
    }

  override fun sameHostAndPort(url: HttpUrl): Boolean {
    val routeUrl = address.url
    return url.port == routeUrl.port && url.host == routeUrl.host
  }
}
