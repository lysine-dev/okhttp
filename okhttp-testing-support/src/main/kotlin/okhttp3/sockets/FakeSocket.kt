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

import java.io.IOException
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketAddress
import java.net.SocketException
import java.net.SocketOption
import java.util.concurrent.atomic.AtomicReference
import okio.Buffer
import okio.Sink
import okio.Source
import okio.buffer

/**
 * A client or server socket.
 *
 * Server sockets are typically created already connected.
 */
internal class FakeSocket(
  val network: FakeNetwork,
  initialState: State = State.New,
) : Socket() {
  private val atomicState = AtomicReference<State>(initialState)
  private val state: State
    get() = atomicState.get()

  private var socketReadTimeoutMillis: Long = 0

  override fun getInputStream() =
    (state as? State.Connected)?.inputStream
      ?: throw IOException("not connected")

  override fun getOutputStream() =
    (state as? State.Connected)?.outputStream
      ?: throw IOException("not connected")

  override fun getRemoteSocketAddress() = state.remoteAddress

  override fun getInetAddress() = state.remoteAddress?.address

  override fun getPort() = state.remoteAddress?.port ?: 0

  override fun getLocalSocketAddress() = state.localAddress

  override fun getLocalAddress() = state.localAddress?.address ?: network.anyAddress

  override fun getLocalPort() = state.localAddress?.port ?: -1

  override fun isBound() = state.bound

  override fun isConnected() = state.connected

  override fun isClosed() = state is State.Closed

  override fun setSoTimeout(soTimeout: Int) {
    if (state is State.Closed) throw SocketException("closed")
    check(soTimeout >= 0)
    this.socketReadTimeoutMillis = soTimeout.toLong()
  }

  override fun getSoTimeout() = socketReadTimeoutMillis.toInt()

  override fun connect(
    endpoint: SocketAddress,
    timeout: Int,
  ) {
    require(timeout >= 0)
    require(endpoint is InetSocketAddress)

    val attempt =
      ConnectAttempt(
        clientAddress = network.nextSocketAddress(),
        serverAddress = endpoint,
      )

    while (true) {
      val previous = state

      if (previous !is State.New) throw SocketException("cannot connect")

      val connectingState = State.Connecting(attempt)
      if (!atomicState.compareAndSet(previous, connectingState)) continue // Lost a race, retry.

      val connection =
        try {
          network.connect(
            attempt = attempt,
            connectTimeoutMillis = timeout.toLong(),
          )
        } catch (e: Throwable) {
          // If the state changed while we were connecting, the other state wins.
          atomicState.compareAndSet(connectingState, previous)
          throw e
        }

      val next =
        State.Connected(
          localAddress = attempt.clientAddress,
          remoteAddress = attempt.serverAddress,
          source = SocketSource(connection.clientSocket.source),
          sink = SocketSink(connection.clientSocket.sink),
        )

      // If the state changed while we were connecting, the other state wins.
      if (!atomicState.compareAndSet(connectingState, next)) {
        connection.clientSocket.cancel()
      }

      break
    }
  }

  override fun isInputShutdown() = state.inputShutdown

  override fun shutdownInput() {
    while (true) {
      val previous = state
      if (previous !is State.Connected) throw SocketException("cannot shutdown input")

      if (previous.outputShutdown) {
        val next = State.Closed(previous)
        if (!atomicState.compareAndSet(previous, next)) continue // Lost a race, retry.
      }

      previous.inputStream.close()
      break
    }
  }

  override fun isOutputShutdown() = state.outputShutdown

  override fun shutdownOutput() {
    while (true) {
      val previous = state
      if (previous !is State.Connected) throw SocketException("cannot shutdown output")

      if (previous.inputShutdown) {
        val next = State.Closed(previous)
        if (!atomicState.compareAndSet(previous, next)) continue // Lost a race, retry.
      }

      previous.outputStream.close()
      break
    }
  }

  override fun close() {
    while (true) {
      val previous = state
      val next = State.Closed(previous)

      if (!atomicState.compareAndSet(previous, next)) continue // Lost a race, retry.

      when (previous) {
        State.New -> {
        }

        is State.Connected -> {
          previous.inputStream.close()
          previous.outputStream.close()
        }

        is State.Connecting -> {
          previous.attempt.cancel(SocketException("client closed"))
        }

        is State.Closed -> {
        }
      }
      break
    }
  }

  override fun connect(endpoint: SocketAddress) = error("unsupported")

  override fun bind(bindpoint: SocketAddress) = error("unsupported")

  override fun getChannel() = error("unsupported")

  override fun setTcpNoDelay(on: Boolean) = error("unsupported")

  override fun getTcpNoDelay() = error("unsupported")

  override fun setSoLinger(
    on: Boolean,
    linger: Int,
  ) = error("unsupported")

  override fun getSoLinger() = error("unsupported")

  override fun sendUrgentData(data: Int) = error("unsupported")

  override fun setOOBInline(on: Boolean) = error("unsupported")

  override fun getOOBInline() = error("unsupported")

  override fun setSendBufferSize(size: Int) = error("unsupported")

  override fun getSendBufferSize() = error("unsupported")

  override fun setReceiveBufferSize(size: Int) = error("unsupported")

  override fun getReceiveBufferSize() = error("unsupported")

  override fun setReuseAddress(reuseAddress: Boolean) = error("unsupported")

  override fun getReuseAddress() = error("unsupported")

  override fun setKeepAlive(on: Boolean) = error("unsupported")

  override fun getKeepAlive() = error("unsupported")

  override fun setTrafficClass(tc: Int) = error("unsupported")

  override fun getTrafficClass() = error("unsupported")

  override fun setPerformancePreferences(
    connectionTime: Int,
    latency: Int,
    bandwidth: Int,
  ) = error("unsupported")

  override fun <T> setOption(
    name: SocketOption<T>,
    value: T?,
  ): Socket = error("unsupported")

  override fun <T> getOption(name: SocketOption<T>) = error("unsupported")

  override fun supportedOptions() = error("unsupported")

  override fun toString() = "FakeSocket"

  sealed interface State {
    val localAddress: InetSocketAddress?
      get() = null
    val remoteAddress: InetSocketAddress?
      get() = null
    val bound: Boolean
      get() = false
    val connected: Boolean
      get() = false
    val inputShutdown: Boolean
      get() = false
    val outputShutdown: Boolean
      get() = false

    object New : State

    class Connecting(
      val attempt: ConnectAttempt,
    ) : State

    class Connected(
      override val localAddress: InetSocketAddress,
      override val remoteAddress: InetSocketAddress,
      val source: SocketSource,
      val sink: SocketSink,
    ) : State {
      val inputStream = source.buffer().inputStream()
      val outputStream = sink.buffer().outputStream()

      override val bound: Boolean
        get() = true
      override val connected: Boolean
        get() = true
      override val inputShutdown: Boolean
        get() = source.delegate == null
      override val outputShutdown: Boolean
        get() = sink.delegate == null
    }

    /** A closed socket remembers what happened before it was closed. */
    class Closed(
      override val localAddress: InetSocketAddress?,
      override val remoteAddress: InetSocketAddress?,
      override val bound: Boolean,
      override val connected: Boolean,
    ) : State {
      constructor(previous: State) : this(
        localAddress = previous.localAddress,
        remoteAddress = previous.remoteAddress,
        bound = previous.bound,
        connected = previous.connected,
      )

      override val inputShutdown: Boolean
        get() = true
      override val outputShutdown: Boolean
        get() = true
    }
  }
}

internal class SocketSource(
  delegate: Source,
) : Source {
  private val timeout = delegate.timeout()

  @Volatile var delegate: Source? = delegate
    private set

  override fun read(
    sink: Buffer,
    byteCount: Long,
  ): Long {
    val delegate = this.delegate ?: throw IOException("closed")
    return delegate.read(sink, byteCount)
  }

  override fun timeout() = timeout

  override fun close() {
    delegate?.close()
    delegate = null
  }
}

internal class SocketSink(
  delegate: Sink,
) : Sink {
  private val timeout = delegate.timeout()

  @Volatile var delegate: Sink? = delegate
    private set

  override fun write(
    source: Buffer,
    byteCount: Long,
  ) {
    val delegate = this.delegate ?: throw IOException("closed")
    delegate.write(source, byteCount)
  }

  override fun flush() {
    val delegate = this.delegate ?: throw IOException("closed")
    delegate.flush()
  }

  override fun timeout() = timeout

  override fun close() {
    delegate?.close()
    delegate = null
  }
}
