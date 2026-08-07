/*
 * Copyright (c) 2026 OkHttp Authors
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
@file:Suppress("Since15")

package okhttp3.sockets

import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketAddress
import java.net.SocketException
import java.net.SocketOption
import java.util.concurrent.atomic.AtomicReference

internal class FakeServerSocket(
  val network: FakeNetwork,
) : ServerSocket() {
  private val atomicState = AtomicReference<State>(State.New)
  private val state: State
    get() = atomicState.get()

  private var reuseAddress = false

  override fun bind(
    endpoint: SocketAddress,
    backlog: Int,
  ) {
    check(endpoint is InetSocketAddress)

    while (true) {
      val previous = state
      if (previous !is State.New) throw SocketException("cannot bind")

      if (!atomicState.compareAndSet(previous, State.Binding)) continue // Lost a race, retry.

      val boundServer = network.bind(endpoint, backlog)

      if (boundServer == null) {
        check(atomicState.compareAndSet(State.Binding, previous))
        throw SocketException("bind collision")
      }

      val next =
        State.Bound(
          boundServer = boundServer,
        )
      check(atomicState.compareAndSet(State.Binding, next))
      break
    }
  }

  override fun getInetAddress() = (state.endpoint as? InetSocketAddress)?.address

  override fun getLocalPort() = (state.endpoint as? InetSocketAddress)?.port ?: -1

  override fun getLocalSocketAddress() = state.endpoint

  override fun accept(): Socket {
    val boundAddress =
      (state as? State.Bound)?.boundServer
        ?: throw SocketException("not bound")

    val connection = boundAddress.accept()
    val socket =
      FakeSocket(
        network = network,
        initialState =
          FakeSocket.State.Connected(
            localAddress = connection.serverAddress,
            remoteAddress = connection.clientAddress,
            source = SocketSource(connection.serverSocket.source),
            sink = SocketSink(connection.serverSocket.sink),
          ),
      )

    return socket
  }

  override fun close() {
    while (true) {
      val previous = state
      val next = State.Closed(previous)
      if (!atomicState.compareAndSet(previous, next)) continue // Lost a race, retry.

      if (previous is State.Bound) {
        network.cancel(previous.boundServer)
      }
      break
    }
  }

  override fun isBound() = state.bound

  override fun isClosed() = state is State.Closed

  override fun setReuseAddress(reuseAddress: Boolean) {
    if (state is State.Closed) throw SocketException("closed")
    this.reuseAddress = reuseAddress
  }

  override fun getReuseAddress() = reuseAddress

  override fun toString() = "FakeServerSocket"

  override fun bind(endpoint: SocketAddress) = error("unsupported")

  override fun getChannel() = error("unsupported")

  override fun setSoTimeout(timeout: Int) = error("unsupported")

  override fun getSoTimeout() = error("unsupported")

  override fun setReceiveBufferSize(size: Int) = error("unsupported")

  override fun getReceiveBufferSize() = error("unsupported")

  override fun setPerformancePreferences(
    connectionTime: Int,
    latency: Int,
    bandwidth: Int,
  ) = error("unsupported")

  override fun <T> setOption(
    name: SocketOption<T?>,
    value: T?,
  ) = error("unsupported")

  override fun <T> getOption(name: SocketOption<T?>) = error("unsupported")

  override fun supportedOptions() = error("unsupported")

  private sealed interface State {
    val bound: Boolean
      get() = false
    val endpoint: SocketAddress?
      get() = null

    object New : State

    /** Like [New], but while we're attempting to bind an address. */
    object Binding : State

    class Bound(
      val boundServer: BoundServer,
    ) : State {
      override val endpoint: SocketAddress
        get() = boundServer.serverAddress
      override val bound: Boolean
        get() = true
    }

    class Closed(
      override val endpoint: SocketAddress?,
      override val bound: Boolean,
    ) : State {
      constructor(previous: State) : this(
        endpoint = previous.endpoint,
        bound = previous.bound,
      )
    }
  }
}
