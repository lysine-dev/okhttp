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
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketAddress
import java.net.SocketException
import java.net.SocketOption
import java.util.concurrent.atomic.AtomicReference
import okhttp3.sockets.FakeSslSocket.HandshakeState
import okio.Buffer
import okio.Closeable
import okio.Sink
import okio.Socket as OkioSocket
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
  internal val atomicState = AtomicReference<State>(initialState)
  internal val state: State
    get() = atomicState.get()

  private var socketReadTimeoutMillis: Long = 0

  val connection: FakeConnection?
    get() = (state as? State.Connected)?.connection

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
          connection = connection,
          localAddress = attempt.clientAddress,
          remoteAddress = attempt.serverAddress,
          socket = connection.clientSocket,
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
      if (previous.outputShutdown) previous.connection.close()
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
      if (previous.inputShutdown) previous.connection.close()
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

        is State.Connecting -> {
          previous.attempt.cancel(SocketException("client closed"))
        }

        is State.Connected -> {
          previous.source.cancel()
          previous.sink.cancel()
          previous.connection.close()
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

  /**
   * This represents the lifecycle state of the TCP socket, which includes a nested lifecycle for
   * the TLS handshake. Tracking the TLS lifecycle here is a layering violation, but it lets us
   * easily keep a single atomic state for both layers.
   */
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
    val handshakeState: HandshakeState
      get() = HandshakeState.New

    object New : State

    class Connecting(
      val attempt: ConnectAttempt,
    ) : State

    class Connected(
      val connection: FakeConnection,
      override val localAddress: InetSocketAddress,
      override val remoteAddress: InetSocketAddress,
      override val handshakeState: HandshakeState = HandshakeState.New,
      val socket: OkioSocket,
      val source: SocketSource = SocketSource(socket),
      val sink: SocketSink = SocketSink(socket),
      val inputStream: InputStream = source.buffer().inputStream(),
      val outputStream: OutputStream = sink.buffer().outputStream(),
    ) : State {
      override val bound: Boolean
        get() = true
      override val connected: Boolean
        get() = true
      override val inputShutdown: Boolean
        get() = source.closed
      override val outputShutdown: Boolean
        get() = sink.closed

      /** Note that this yields a new [inputStream] and [outputStream]. */
      fun withHandshakeSuccess(
        handshakeState: HandshakeState.Success,
        socket: OkioSocket,
      ) = Connected(
        connection = connection,
        localAddress = localAddress,
        remoteAddress = remoteAddress,
        handshakeState = handshakeState,
        socket = socket,
      )

      /** Note that this retains the previous [inputStream] and [outputStream]. */
      fun withHandshakeState(handshakeState: HandshakeState) =
        Connected(
          connection = connection,
          localAddress = localAddress,
          remoteAddress = remoteAddress,
          handshakeState = handshakeState,
          socket = socket,
          source = source,
          sink = sink,
          inputStream = inputStream,
          outputStream = outputStream,
        )
    }

    /** A closed socket remembers what happened before it was closed. */
    class Closed(
      override val localAddress: InetSocketAddress?,
      override val remoteAddress: InetSocketAddress?,
      override val handshakeState: HandshakeState,
      override val bound: Boolean,
      override val connected: Boolean,
    ) : State {
      constructor(previous: State) : this(
        localAddress = previous.localAddress,
        remoteAddress = previous.remoteAddress,
        handshakeState = previous.handshakeState,
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

/**
 * This wraps an `okio.Socket` and exposes it as either the source or sink of a `java.net.Socket`.
 *
 * It's made complicated by how [okio.inMemorySocketPair] handles asynchronous cancellation.
 * Canceling an in-memory socket cancels both streams for both peers. In-flight operations
 * immediately fail and all following operations throw an [IOException].
 *
 * To contrast, closing a [java.net.Socket] immediately fails local operations only. The peer can
 * read the remainder of the stream until it is exhausted.
 *
 * Our mitigation is straightforward enough: if the [FakeSocket] is closed while a local operation
 * is in-flight, we trigger a maximally invasive cancel. Otherwise, we do a graceful close.
 */
internal open class SocketStream<T : Closeable>(
  val socket: OkioSocket,
  val stream: T,
) : Closeable {
  val closed: Boolean
    get() = state.get() == StreamState.Closed

  private val state = AtomicReference(StreamState.Ready)

  override fun close() {
    val previous = state.getAndSet(StreamState.Closed)
    if (previous != StreamState.Closed) {
      stream.close()
    }
  }

  /**
   * Equivalent to [close] unless there's a local operation in-flight, in which case the entire
   * socket is interrupted.
   */
  fun cancel() {
    val previous = state.getAndSet(StreamState.Closed)
    if (previous != StreamState.Closed) {
      stream.close()
    }
    if (previous == StreamState.Blocked) {
      socket.cancel()
    }
  }

  protected inline fun <T> blockingOp(block: () -> T): T {
    if (!state.compareAndSet(StreamState.Ready, StreamState.Blocked)) {
      throw IOException("closed")
    }
    try {
      return block()
    } finally {
      // If the state is since closed, that's okay.
      state.compareAndSet(StreamState.Blocked, StreamState.Ready)
    }
  }

  internal enum class StreamState {
    Ready,
    Blocked,
    Closed,
  }
}

internal class SocketSource(
  socket: OkioSocket,
) : SocketStream<Source>(socket, socket.source),
  Source {
  override fun read(
    sink: Buffer,
    byteCount: Long,
  ): Long {
    blockingOp {
      return stream.read(sink, byteCount)
    }
  }

  override fun timeout() = stream.timeout()
}

internal class SocketSink(
  socket: OkioSocket,
) : SocketStream<Sink>(socket, socket.sink),
  Sink {
  override fun write(
    source: Buffer,
    byteCount: Long,
  ) {
    blockingOp {
      stream.write(source, byteCount)
    }
  }

  override fun flush() {
    blockingOp {
      stream.flush()
    }
  }

  override fun timeout() = stream.timeout()
}
