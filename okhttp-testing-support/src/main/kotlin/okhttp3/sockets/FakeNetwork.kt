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
package okhttp3.sockets

import java.net.ConnectException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.SocketAddress
import java.net.SocketException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import javax.net.ServerSocketFactory
import javax.net.SocketFactory
import okhttp3.internal.concurrent.Lockable
import okhttp3.internal.concurrent.notifyAll
import okhttp3.internal.concurrent.wait
import okhttp3.internal.concurrent.withLock
import okhttp3.internal.connection.asBufferedSocket
import okio.Buffer
import okio.Socket
import okio.Timeout
import okio.inMemorySocketPair

/**
 * A complete in-memory socket system, suitable for testing OkHttp without using any operating
 * system TCP sockets.
 */
class FakeNetwork {
  private val boundServers = ConcurrentHashMap<SocketAddress, BoundServer>()

  private val nextIpv4Address =
    Buffer().run {
      writeByte(192)
      writeByte(168)
      writeByte(0)
      writeByte(1)
      AtomicInteger(readInt())
    }

  private var nextPort = AtomicInteger(5_000)

  internal val anyAddress: InetAddress
    get() = InetAddress.getByAddress(byteArrayOf(0, 0, 0, 0))

  /** Generate a new unique address. */
  internal fun nextSocketAddress(): InetSocketAddress {
    val ipv4AddressInt = nextIpv4Address.getAndIncrement()
    val ipv4AddressBytes =
      Buffer()
        .writeInt(ipv4AddressInt)
        .readByteArray()
    return InetSocketAddress(
      InetAddress.getByAddress(ipv4AddressBytes),
      nextPort(),
    )
  }

  /** Generate a new unique port. */
  fun nextPort() = nextPort.getAndIncrement()

  val serverSocketFactory =
    object : ServerSocketFactory() {
      override fun createServerSocket() = FakeServerSocket(this@FakeNetwork)

      override fun createServerSocket(port: Int) = error("unsupported")

      override fun createServerSocket(
        port: Int,
        backlog: Int,
      ) = error("unsupported")

      override fun createServerSocket(
        port: Int,
        backlog: Int,
        ifAddress: InetAddress?,
      ) = error("unsupported")
    }

  val socketFactory =
    object : SocketFactory() {
      override fun createSocket() = FakeSocket(this@FakeNetwork)

      override fun createSocket(
        host: String,
        port: Int,
      ) = error("unsupported")

      override fun createSocket(
        host: String?,
        port: Int,
        localHost: InetAddress?,
        localPort: Int,
      ) = error("unsupported")

      override fun createSocket(
        host: InetAddress?,
        port: Int,
      ) = error("unsupported")

      override fun createSocket(
        address: InetAddress?,
        port: Int,
        localAddress: InetAddress?,
        localPort: Int,
      ) = error("unsupported")
    }

  internal fun connect(
    attempt: ConnectAttempt,
    connectTimeoutMillis: Long,
  ): FakeConnection {
    val boundServer =
      boundServers[attempt.serverAddress]
        ?: throw ConnectException("no server bound")

    val timeout =
      Timeout()
        .deadline(connectTimeoutMillis, TimeUnit.MILLISECONDS)
    boundServer.enqueue(timeout, attempt)
    return attempt.await(timeout)
  }

  /** Returns non-null if the bind was successful. */
  internal fun bind(
    endpoint: InetSocketAddress,
    backlog: Int,
  ): BoundServer? {
    val serverAddress =
      when (endpoint.port) {
        0 -> InetSocketAddress(endpoint.address, nextPort())
        else -> endpoint
      }

    val result = BoundServer(serverAddress, backlog)
    val collision = boundServers.putIfAbsent(serverAddress, result)
    if (collision != null) return null
    return result
  }

  internal fun cancel(server: BoundServer) {
    boundServers.remove(server.serverAddress, server)
    server.close()
  }
}

/**
 * Attempt to connect to a server.
 *
 * Either the client or the server can asynchronously cancel this attempt while the caller is
 * awaiting its result.
 *
 * This uses `synchronized` to guard [result]. Calls to [await] wait on this lock until a result is
 * sent.
 */
internal class ConnectAttempt(
  val clientAddress: InetSocketAddress,
  val serverAddress: InetSocketAddress,
) : Lockable {
  private var result: Result<FakeConnection>? = null

  fun cancel(e: SocketException) {
    withLock {
      if (result != null) return
      result = Result.failure(e)
      notifyAll()
    }
  }

  fun complete(connection: FakeConnection) {
    withLock {
      if (result != null) return
      result = Result.success(connection)
      notifyAll()
    }
  }

  fun await(timeout: Timeout): FakeConnection {
    withLock {
      while (true) {
        val result = result
        if (result != null) return result.getOrThrow()

        timeout.waitUntilNotified(this)
      }
    }
  }

  override fun toString() = "Connect@$serverAddress"
}

/**
 * Matches inbound connection attempts with calls to [accept].
 *
 * This uses `synchronized` to guard [attempts] and [closed]. Calls to [accept] wait on this lock
 * until a connection attempt is enqueued.
 */
internal class BoundServer(
  val serverAddress: InetSocketAddress,
  val maxBacklogSize: Int,
) : Lockable {
  private val attempts = ArrayDeque<ConnectAttempt>()
  private var closed = false

  fun enqueue(
    timeout: Timeout,
    attempt: ConnectAttempt,
  ) {
    withLock {
      while (attempts.size >= maxBacklogSize && !closed) {
        timeout.waitUntilNotified(this) // Wait until there's room in the backlog.
      }
      if (closed) throw ConnectException("closed")
      notifyAll()
      attempts += attempt
    }
  }

  fun accept(): FakeConnection {
    val attempt =
      withLock {
        while (attempts.isEmpty() && !closed) {
          wait() // Wait for a connection attempt.
        }
        if (closed) throw SocketException("closed")
        notifyAll()
        attempts.removeFirst()
      }

    val (clientSocket, serverSocket) = inMemorySocketPair(maxBufferSize = 1024 * 1024)
    val connection =
      FakeConnection(
        clientAddress = attempt.clientAddress,
        serverAddress = attempt.serverAddress,
        clientSocket = clientSocket.asBufferedSocket(),
        serverSocket = serverSocket.asBufferedSocket(),
      )

    attempt.complete(connection)
    return connection
  }

  fun close() {
    val attemptsToCancel =
      withLock {
        notifyAll()
        closed = true
        attempts
          .toList()
          .also { attempts.clear() }
      }
    for (attempt in attemptsToCancel) {
      attempt.cancel(SocketException("server closed"))
    }
  }

  override fun toString() = "Server@$serverAddress"
}

internal class FakeConnection(
  val clientAddress: InetSocketAddress,
  val serverAddress: InetSocketAddress,
  val clientSocket: Socket,
  val serverSocket: Socket,
) {
  override fun toString() = "$clientAddress<->$serverAddress"
}
