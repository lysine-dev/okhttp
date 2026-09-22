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

import okio.inMemorySocketPair

/** Returns a two-element array containing mutually-connected sockets. */
internal fun FakeNetwork.socketPair(): Array<FakeSocket> {
  val (clientOkioSocket, serverOkioSocket) = inMemorySocketPair(maxBufferSize = 1024 * 1024)
  val connection =
    FakeConnection(
      clientAddress = nextSocketAddress(),
      serverAddress = nextSocketAddress(),
      clientSocket = clientOkioSocket,
      serverSocket = serverOkioSocket,
    )

  val clientJavaNetSocket =
    FakeSocket(
      network = this,
      initialState =
        FakeSocket.State.Connected(
          connection = connection,
          localAddress = connection.clientAddress,
          remoteAddress = connection.serverAddress,
          socket = connection.clientSocket,
        ),
    )

  val serverJavaNetSocket =
    FakeSocket(
      network = this,
      initialState =
        FakeSocket.State.Connected(
          connection = connection,
          localAddress = connection.serverAddress,
          remoteAddress = connection.clientAddress,
          socket = connection.serverSocket,
        ),
    )

  return arrayOf(clientJavaNetSocket, serverJavaNetSocket)
}

internal fun FakeTls.clientSocket(
  socket: FakeSocket,
  serverHostname: String = "testing.lysine.dev",
  serverPort: Int = 443,
): FakeSslSocket = sslSocketFactory.createSocket(socket, serverHostname, serverPort, true) as FakeSslSocket

internal fun FakeTls.serverSocket(
  socket: FakeSocket,
  clientPort: Int = 1024,
): FakeSslSocket {
  val result = sslSocketFactory.createSocket(socket, null, clientPort, true) as FakeSslSocket
  result.useClientMode = false
  return result
}
