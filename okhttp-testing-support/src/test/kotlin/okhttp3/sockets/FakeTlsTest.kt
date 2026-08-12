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

import assertk.assertThat
import assertk.assertions.hasMessage
import assertk.assertions.isBetween
import assertk.assertions.isEqualTo
import java.io.InterruptedIOException
import java.net.SocketException
import javax.net.ssl.SSLHandshakeException
import kotlin.test.assertFailsWith
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.measureTime
import okhttp3.OkHttpClientTestRule
import okhttp3.internal.concurrent.TaskRunner
import okhttp3.internal.concurrent.schedule
import okhttp3.sockets.Handshaker.ClientInputs
import okhttp3.sockets.Handshaker.ServerInputs
import okhttp3.tls.internal.TlsUtil
import okio.buffer
import okio.sink
import okio.source
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.RegisterExtension

@Tag("Slowish")
class FakeTlsTest {
  @RegisterExtension
  @JvmField
  val clientTestRule = OkHttpClientTestRule()

  val taskRunner = TaskRunner.INSTANCE
  val network = FakeNetwork()

  val clientTls =
    FakeTls(
      handshaker = InsecureHandshaker(),
      handshakeCertificates = TlsUtil.localhost(),
    )
  val serverTls =
    FakeTls(
      handshaker = InsecureHandshaker(),
      handshakeCertificates = TlsUtil.localhost(),
    )

  @Test
  fun `happy path`() {
    val (clientSocket, serverSocket) = network.socketPair()
    val clientSslSocket = clientTls.clientSocket(clientSocket)
    val serverSslSocket = serverTls.serverSocket(serverSocket)

    taskRunner.schedule("client") {
      clientSslSocket.startHandshake()
      clientSslSocket.use { socket ->
        val sink = socket.getOutputStream().sink().buffer()
        sink.writeUtf8("hello from client\n")
        sink.flush()

        val source = socket.getInputStream().source().buffer()
        assertThat(source.readUtf8Line()).isEqualTo("hello from server")
      }
    }

    serverSslSocket.startHandshake()
    serverSslSocket.use { socket ->
      val source = socket.getInputStream().source().buffer()
      assertThat(source.readUtf8Line()).isEqualTo("hello from client")

      val sink = socket.getOutputStream().sink().buffer()
      sink.writeUtf8("hello from server\n")
      sink.flush()
    }
  }

  @Test
  fun `handshake fails`() {
    val (clientSocket, serverSocket) = network.socketPair()

    val clientTls =
      FakeTls(
        handshaker =
          object : Handshaker {
            private var handshakeCount = 0

            override fun handshake(
              client: ClientInputs,
              server: ServerInputs,
            ): Handshaker.Result {
              assertThat(handshakeCount++).isEqualTo(0)
              throw SSLHandshakeException("boom!")
            }
          },
        handshakeCertificates = TlsUtil.localhost(),
      )

    val clientSslSocket = clientTls.clientSocket(clientSocket)
    val serverSslSocket = serverTls.serverSocket(serverSocket)

    taskRunner.schedule("client") {
      val e =
        assertFailsWith<SSLHandshakeException> {
          clientSslSocket.startHandshake()
        }
      assertThat(e).hasMessage("boom!")
    }

    val e =
      assertFailsWith<SSLHandshakeException> {
        serverSslSocket.startHandshake()
      }
    assertThat(e).hasMessage("boom!")

    // Exception is memoized.
    assertFailsWith<SSLHandshakeException> {
      serverSslSocket.getSession()
    }

    clientSslSocket.close()
    serverSslSocket.close()
  }

  @Test
  fun `connection closed during successful handshake`() {
    val (clientSocket, serverSocket) = network.socketPair()

    val clientTls =
      FakeTls(
        handshaker =
          object : Handshaker {
            override fun handshake(
              client: ClientInputs,
              server: ServerInputs,
            ): Handshaker.Result {
              clientSocket.close()
              return InsecureHandshaker().handshake(client, server)
            }
          },
        handshakeCertificates = TlsUtil.localhost(),
      )

    val clientSslSocket = clientTls.clientSocket(clientSocket)
    val serverSslSocket = serverTls.serverSocket(serverSocket)

    taskRunner.schedule("server") {
      val e =
        assertFailsWith<SocketException> {
          serverSslSocket.startHandshake()
        }
      assertThat(e).hasMessage("closed")
    }

    val e =
      assertFailsWith<SocketException> {
        clientSslSocket.startHandshake()
      }
    assertThat(e).hasMessage("closed")
  }

  @Test
  fun `client handshake timeout`() {
    val (clientSocket, _) = network.socketPair()
    clientSocket.soTimeout = 250

    val clientSslSocket = clientTls.clientSocket(clientSocket)

    val elapsed =
      measureTime {
        assertFailsWith<InterruptedIOException> {
          clientSslSocket.startHandshake()
        }
      }
    assertThat(elapsed).isBetween(200.milliseconds, 350.milliseconds)
  }

  @Test
  fun `client handshake fails because server is closed`() {
    val (clientSocket, serverSocket) = network.socketPair()
    clientSocket.soTimeout = 5_000

    val clientSslSocket = clientTls.clientSocket(clientSocket)

    taskRunner.schedule("close server later", 250.milliseconds) {
      serverSocket.close()
    }

    val elapsed =
      measureTime {
        assertFailsWith<SocketException> {
          clientSslSocket.startHandshake()
        }
      }
    assertThat(elapsed).isBetween(200.milliseconds, 350.milliseconds)
  }

  @Test
  fun `client handshake fails because client is closed`() {
    val (clientSocket, _) = network.socketPair()
    clientSocket.soTimeout = 5_000

    val clientSslSocket = clientTls.clientSocket(clientSocket)

    taskRunner.schedule("close client later", 250.milliseconds) {
      clientSocket.close()
    }

    val elapsed =
      measureTime {
        assertFailsWith<SocketException> {
          clientSslSocket.startHandshake()
        }
      }
    assertThat(elapsed).isBetween(200.milliseconds, 350.milliseconds)
  }

  @Test
  fun `server handshake timeout`() {
    val (_, serverSocket) = network.socketPair()
    serverSocket.soTimeout = 250

    val serverSslSocket = serverTls.clientSocket(serverSocket)

    val elapsed =
      measureTime {
        assertFailsWith<InterruptedIOException> {
          serverSslSocket.startHandshake()
        }
      }
    assertThat(elapsed).isBetween(200.milliseconds, 350.milliseconds)
  }

  @Test
  fun `server handshake fails because server is closed`() {
    val (_, serverSocket) = network.socketPair()
    serverSocket.soTimeout = 5_000

    val serverSslSocket = serverTls.clientSocket(serverSocket)

    taskRunner.schedule("close server later", 250.milliseconds) {
      serverSocket.close()
    }

    val elapsed =
      measureTime {
        assertFailsWith<SocketException> {
          serverSslSocket.startHandshake()
        }
      }
    assertThat(elapsed).isBetween(200.milliseconds, 350.milliseconds)
  }

  @Test
  fun `server handshake fails because client is closed`() {
    val (clientSocket, serverSocket) = network.socketPair()
    serverSocket.soTimeout = 5_000

    val serverSslSocket = serverTls.clientSocket(serverSocket)

    taskRunner.schedule("close server later", 250.milliseconds) {
      clientSocket.close()
    }

    val elapsed =
      measureTime {
        assertFailsWith<SocketException> {
          serverSslSocket.startHandshake()
        }
      }
    assertThat(elapsed).isBetween(200.milliseconds, 350.milliseconds)
  }
}
