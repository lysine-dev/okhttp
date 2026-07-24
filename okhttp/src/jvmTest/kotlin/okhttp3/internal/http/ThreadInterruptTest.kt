/*
 * Copyright (C) 2014 Square, Inc.
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
package okhttp3.internal.http

import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isIn
import assertk.assertions.isInstanceOf
import assertk.assertions.isNotNull
import assertk.assertions.matchesPredicate
import assertk.assertions.startsWith
import assertk.fail
import java.io.IOException
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.util.concurrent.CompletableFuture
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.TimeUnit
import kotlin.test.assertFailsWith
import okhttp3.Call
import okhttp3.Callback
import okhttp3.DelegatingServerSocketFactory
import okhttp3.DelegatingSocketFactory
import okhttp3.OkHttpClient
import okhttp3.OkHttpClientTestRule
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.Response
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import okhttp3.testing.PlatformRule
import okio.Buffer
import okio.BufferedSink
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.RepeatedTest
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.RegisterExtension

@Tag("Slowish")
class ThreadInterruptTest {
  @RegisterExtension
  val platform = PlatformRule()

  @RegisterExtension
  val clientTestRule = OkHttpClientTestRule()
  private lateinit var server: MockWebServer
  private lateinit var client: OkHttpClient

  @BeforeEach
  fun setUp() {
    // Sockets on some platforms can have large buffers that mean writes do not block when
    // required. These socket factories explicitly set the buffer sizes on sockets created.
    server = MockWebServer()
    server.serverSocketFactory =
      object : DelegatingServerSocketFactory(getDefault()) {
        @Throws(SocketException::class)
        override fun configureServerSocket(serverSocket: ServerSocket): ServerSocket {
          serverSocket.setReceiveBufferSize(SOCKET_BUFFER_SIZE)
          return serverSocket
        }
      }
    client =
      clientTestRule
        .newClientBuilder()
        .socketFactory(
          object : DelegatingSocketFactory(getDefault()) {
            @Throws(IOException::class)
            override fun configureSocket(socket: Socket): Socket {
              socket.setSendBufferSize(SOCKET_BUFFER_SIZE)
              socket.setReceiveBufferSize(SOCKET_BUFFER_SIZE)
              return socket
            }
          },
        ).build()
  }

  @AfterEach
  fun tearDown() {
    Thread.interrupted() // Clear interrupted state.
  }

  @Test
  fun interruptWritingRequestBody() {
    server.enqueue(MockResponse())
    server.start()
    val call =
      client.newCall(
        Request
          .Builder()
          .url(server.url("/"))
          .post(
            object : RequestBody() {
              override fun contentType() = null

              override fun writeTo(sink: BufferedSink) {
                for (i in 0..9) {
                  sink.writeByte(0)
                  sink.flush()
                  sleep(100)
                }
                fail("Expected connection to be closed")
              }
            },
          ).build(),
      )
    interruptLater(500)
    assertFailsWith<IOException> {
      call.execute()
    }
  }

  @Test
  fun interruptReadingResponseBody() {
    val responseBodySize = 8 * 1024 * 1024 // 8 MiB.
    server.enqueue(
      MockResponse()
        .setBody(Buffer().write(ByteArray(responseBodySize)))
        .throttleBody((64 * 1024).toLong(), 125, TimeUnit.MILLISECONDS),
    ) // 500 Kbps
    server.start()
    val call =
      client.newCall(
        Request
          .Builder()
          .url(server.url("/"))
          .build(),
      )
    val response = call.execute()
    interruptLater(500)
    val responseBody = response.body.byteStream()
    val buffer = ByteArray(1024)
    assertFailsWith<IOException> {
      while (responseBody.read(buffer) != -1) {
      }
    }
    responseBody.close()
  }

  @RepeatedTest(100)
  fun forciblyStopDispatcher() {
    client =
      client
        .newBuilder()
        .fastFallback(true)
        .build()

    val callFailure = CompletableFuture<Exception>()

    server.enqueue(
      MockResponse()
        .setSocketPolicy(SocketPolicy.STALL_SOCKET_AT_START),
    )
    server.start()
    val call =
      client.newCall(
        Request
          .Builder()
          .url(server.url("/"))
          .build(),
      )
    call.enqueue(
      object : Callback {
        override fun onFailure(
          call: Call,
          e: okio.IOException,
        ) {
          callFailure.complete(e)
        }

        override fun onResponse(
          call: Call,
          response: Response,
        ) {
        }
      },
    )

    // This should fail the Call, but not cause an unhandled Exception bubbling up
    client.dispatcher.executorService.shutdownNow()

    val exception = callFailure.get(5, TimeUnit.SECONDS)
    assertThat(exception.message)
      .isNotNull()
      .startsWith("canceled due to")
    assertThat(exception).isInstanceOf<IOException>()
    assertThat(exception.cause)
      .isNotNull()
      .matchesPredicate { it is InterruptedException || it is RejectedExecutionException }
    assertThat(clientTestRule.takeUncaughtException())
      .matchesPredicate { it == null || it is RejectedExecutionException }
  }

  private fun sleep(delayMillis: Int) {
    try {
      Thread.sleep(delayMillis.toLong())
    } catch (e: InterruptedException) {
      Thread.currentThread().interrupt()
    }
  }

  private fun interruptLater(delayMillis: Int) {
    val toInterrupt = Thread.currentThread()
    val interruptingCow =
      Thread {
        sleep(delayMillis)
        toInterrupt.interrupt()
      }
    interruptingCow.start()
  }

  companion object {
    // The size of the socket buffers in bytes.
    private const val SOCKET_BUFFER_SIZE = 256 * 1024
  }
}
