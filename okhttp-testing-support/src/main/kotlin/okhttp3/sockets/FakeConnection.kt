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

import java.net.InetSocketAddress
import java.net.SocketException
import okhttp3.internal.concurrent.Lockable
import okhttp3.internal.concurrent.notifyAll
import okhttp3.internal.concurrent.withLock
import okio.Socket
import okio.Timeout

/**
 * This class implements a rendezvous point for [Handshaker.ClientInputs] and [Handshaker.ServerInputs]. When they're
 * both provided, the client caller does a handshake and shares the handshake result.
 */
internal class FakeConnection(
  val clientAddress: InetSocketAddress,
  val serverAddress: InetSocketAddress,
  val clientSocket: Socket,
  val serverSocket: Socket,
) : Lockable {
  private var closed = false
  private var serverInputs: Handshaker.ServerInputs? = null
  private var handshakeResult: Result<Handshaker.Result>? = null

  fun handshake(
    handshaker: Handshaker,
    clientInputs: Handshaker.ClientInputs,
    timeout: Timeout,
  ): Handshaker.Result {
    val serverInputs =
      withLock {
        awaitServerInputs(timeout)
      }

    // Destroy the unencrypted socket pair; we'll build a new encrypted one to replace it.
    clientSocket.cancel()
    serverSocket.cancel()

    val result =
      runCatching {
        handshaker.handshake(clientInputs, serverInputs)
      }

    withLock {
      this.handshakeResult = result
      notifyAll()
    }

    return result.getOrThrow()
  }

  private tailrec fun awaitServerInputs(timeout: Timeout): Handshaker.ServerInputs {
    if (closed) throw SocketException("closed")
    serverInputs?.let { return it }
    timeout.waitUntilNotified(this)
    return awaitServerInputs(timeout)
  }

  fun handshake(
    serverInputs: Handshaker.ServerInputs,
    timeout: Timeout,
  ): Handshaker.Result {
    withLock {
      this.serverInputs = serverInputs
      notifyAll()
      return awaitResult(timeout).getOrThrow()
    }
  }

  private tailrec fun awaitResult(timeout: Timeout): Result<Handshaker.Result> {
    if (closed) throw SocketException("closed")
    handshakeResult?.let { return it }
    timeout.waitUntilNotified(this)
    return awaitResult(timeout)
  }

  fun close() {
    withLock {
      closed = true
      notifyAll()
    }
  }

  override fun toString() = "$clientAddress<->$serverAddress"
}
