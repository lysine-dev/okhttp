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
import kotlin.test.assertFailsWith
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.measureTime
import okhttp3.OkHttpClientTestRule
import okhttp3.internal.concurrent.TaskRunner
import okio.buffer
import okio.sink
import okio.source
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.RegisterExtension

@Tag("Slowish")
class FakeNetworkTest {
  @RegisterExtension
  @JvmField
  val clientTestRule = OkHttpClientTestRule()

  val taskRunner = TaskRunner.INSTANCE
  val network = FakeNetwork()
  val server = network.serverSocketFactory.createServerSocket()
  val serverAddress = network.nextSocketAddress()

  @AfterEach
  fun tearDown() {
    server.close()
  }

  @Test
  fun `happy path`() {
    server.bind(serverAddress, 3)

    taskRunner.schedule("client") {
      network.socketFactory.createSocket().use { socket ->
        socket.connect(serverAddress, 5_000)

        val sink = socket.getOutputStream().sink().buffer()
        sink.writeUtf8("hello from client\n")
        sink.flush()

        val source = socket.getInputStream().source().buffer()
        assertThat(source.readUtf8Line()).isEqualTo("hello from server")
      }
    }

    server.accept().use { socket ->
      val source = socket.getInputStream().source().buffer()
      assertThat(source.readUtf8Line()).isEqualTo("hello from client")

      val sink = socket.getOutputStream().sink().buffer()
      sink.writeUtf8("hello from server\n")
      sink.flush()
    }
  }

  @Test
  fun `cannot bind when already bound by another server`() {
    server.bind(serverAddress, 3)

    val server2 = network.serverSocketFactory.createServerSocket()
    val e =
      assertFailsWith<SocketException> {
        server2.bind(serverAddress, 3)
      }
    assertThat(e).hasMessage("bind collision")

    server2.close()
  }

  @Test
  fun `cannot bind after close`() {
    server.close()

    val e =
      assertFailsWith<SocketException> {
        server.bind(serverAddress, 3)
      }
    assertThat(e).hasMessage("cannot bind")
  }

  @Test
  fun `cannot accept without bind`() {
    val e =
      assertFailsWith<SocketException> {
        server.accept()
      }
    assertThat(e).hasMessage("not bound")
  }

  @Test
  fun `reuse server address`() {
    server.bind(serverAddress, 3)

    taskRunner.schedule("clientA") {
      val client = network.socketFactory.createSocket()
      client.connect(serverAddress, 5_000)
      client.close()
    }

    val acceptedClientA = server.accept()
    acceptedClientA.close()

    server.close()

    val server2 = network.serverSocketFactory.createServerSocket()
    server2.bind(serverAddress, 3)

    taskRunner.schedule("clientB") {
      val client = network.socketFactory.createSocket()
      client.connect(serverAddress, 5_000)
      client.close()
    }

    val acceptedClientB = server2.accept()
    acceptedClientB.close()

    server2.close()
  }

  @Test
  fun `cannot connect after close`() {
    val client = network.socketFactory.createSocket()
    client.close()

    val e =
      assertFailsWith<SocketException> {
        client.connect(serverAddress, 5_000)
      }
    assertThat(e).hasMessage("cannot connect")
  }

  @Test
  fun `connect fails because server is closed`() {
    server.bind(serverAddress, 3)

    taskRunner.schedule("close server later", 250.milliseconds) {
      server.close()
    }

    val client = network.socketFactory.createSocket()
    val elapsed =
      measureTime {
        val e =
          assertFailsWith<SocketException> {
            client.connect(serverAddress, 5_000)
          }
        assertThat(e).hasMessage("server closed")
      }
    assertThat(elapsed).isBetween(200.milliseconds, 350.milliseconds)
  }

  @Test
  fun `connect fails because client is closed`() {
    server.bind(serverAddress, 3)

    val client = network.socketFactory.createSocket()
    taskRunner.schedule("close client later", 250.milliseconds) {
      client.close()
    }

    val elapsed =
      measureTime {
        val e =
          assertFailsWith<SocketException> {
            client.connect(serverAddress, 5_000)
          }
        assertThat(e).hasMessage("client closed")
      }
    assertThat(elapsed).isBetween(200.milliseconds, 350.milliseconds)
  }

  @Test
  fun `accept fails because server is closed`() {
    server.bind(serverAddress, 3)

    taskRunner.schedule("close server later", 250.milliseconds) {
      server.close()
    }

    val elapsed =
      measureTime {
        val e =
          assertFailsWith<SocketException> {
            server.accept()
          }
        assertThat(e).hasMessage("closed")
      }
    assertThat(elapsed).isBetween(200.milliseconds, 350.milliseconds)
  }

  @Test
  fun `connect timeout waiting for accept`() {
    server.bind(serverAddress, 3)

    val client = network.socketFactory.createSocket()
    val elapsed =
      measureTime {
        assertFailsWith<InterruptedIOException> {
          client.connect(serverAddress, 250)
        }
      }
    assertThat(elapsed).isBetween(200.milliseconds, 350.milliseconds)
  }

  /**
   * This is effectively the same as [`connect timeout waiting for accept`], but in this test we're
   * waiting for a slot in the server's backlog.
   */
  @Test
  fun `connect timeout waiting in backlog`() {
    server.bind(serverAddress, 3)

    // Put 3 clients in the server's backlog, all blocked waiting for accept().
    connectExpectingServerClose("clientA")
    connectExpectingServerClose("clientB")
    connectExpectingServerClose("clientC")

    // A 4th client will time out waiting in the backlog. We don't start it for 100 ms so the
    // previously-started clients can fill up the backlog.
    Thread.sleep(100)
    val clientD = network.socketFactory.createSocket()
    val elapsed =
      measureTime {
        assertFailsWith<InterruptedIOException> {
          clientD.connect(serverAddress, 250)
        }
      }
    assertThat(elapsed).isBetween(200.milliseconds, 350.milliseconds)
  }

  private fun connectExpectingServerClose(name: String) {
    val client = network.socketFactory.createSocket()
    taskRunner.schedule(name) {
      val e =
        assertFailsWith<SocketException> {
          client.connect(serverAddress, 5_000)
        }
      assertThat(e).hasMessage("server closed")
    }
  }

  private fun TaskRunner.schedule(
    name: String,
    delay: Duration = 0.milliseconds,
    block: () -> Unit,
  ) {
    newQueue().schedule(name, delay.inWholeNanoseconds) {
      block()
      -1L
    }
  }
}
