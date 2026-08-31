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
package okhttp3.containers

import assertk.assertFailure
import assertk.assertThat
import assertk.assertions.isInstanceOf
import assertk.assertions.isLessThan
import com.github.dockerjava.api.model.Capability
import java.io.IOException
import java.time.Duration
import java.util.concurrent.TimeUnit
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import okhttp3.MediaType
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.api.parallel.Isolated
import org.testcontainers.Testcontainers
import org.testcontainers.containers.GenericContainer
import org.testcontainers.containers.wait.strategy.Wait
import org.testcontainers.images.builder.ImageFromDockerfile

/**
 * Reproduces a stateful firewall silently killing a pooled HTTP/2 connection at L3 (no RST, no FIN)
 * while a new stream is being written. A socat tunnel in an Alpine container with `NET_ADMIN`
 * engages `iptables -j DROP` mid-test, after which the next HEADERS write fills the kernel TCP
 * send buffer and blocks indefinitely waiting for ACKs that will never arrive.
 *
 * Asserts the design intent of [OkHttpClient.Builder.pingInterval]: a configured ping interval
 * detects the dead connection and fails the call within a small multiple of the interval.
 *
 * Requires Docker with the `NET_ADMIN` capability (for `iptables -j DROP`).
 */
@Isolated
@Timeout(value = 30, unit = TimeUnit.SECONDS)
class DeadSocketTest {
  private lateinit var rootCa: HeldCertificate
  private lateinit var mockWebServer: MockWebServer
  private lateinit var tunnel: GenericContainer<*>
  private lateinit var client: OkHttpClient

  @BeforeEach
  fun setUp() {
    rootCa =
      HeldCertificate
        .Builder()
        .certificateAuthority(0)
        .build()

    val serverCert =
      HeldCertificate
        .Builder()
        .addSubjectAlternativeName("localhost")
        .signedBy(rootCa)
        .build()

    val serverHandshakeCerts =
      HandshakeCertificates
        .Builder()
        .heldCertificate(serverCert)
        .build()

    mockWebServer = MockWebServer()
    mockWebServer.useHttps(serverHandshakeCerts.sslSocketFactory())
    mockWebServer.protocols = listOf(Protocol.HTTP_2, Protocol.HTTP_1_1)
    mockWebServer.dispatcher =
      object : Dispatcher() {
        override fun dispatch(request: RecordedRequest): MockResponse =
          MockResponse
            .Builder()
            .code(200)
            .body("ok")
            .build()
      }
    mockWebServer.start()

    Testcontainers.exposeHostPorts(mockWebServer.port)

    tunnel =
      GenericContainer(
        ImageFromDockerfile()
          .withDockerfileFromBuilder { builder ->
            builder
              .from("alpine:3")
              .run("apk add --no-cache socat iptables")
              .build()
          },
      ).withCreateContainerCmdModifier { cmd ->
        cmd.hostConfig!!.withCapAdd(Capability.NET_ADMIN)
      }.withExposedPorts(TUNNEL_PORT)
        .withCommand(
          "socat",
          "TCP-LISTEN:$TUNNEL_PORT,fork,reuseaddr",
          "TCP:host.testcontainers.internal:${mockWebServer.port}",
        ).waitingFor(Wait.forListeningPort())
    tunnel.start()

    val clientCerts =
      HandshakeCertificates
        .Builder()
        .addTrustedCertificate(rootCa.certificate)
        .build()

    client =
      OkHttpClient
        .Builder()
        .protocols(listOf(Protocol.HTTP_2, Protocol.HTTP_1_1))
        .pingInterval(Duration.ofSeconds(1))
        // Isolate pingInterval detection latency from connect-retry latency: with retry on, the
        // retry's new TCP+TLS handshake races against connectTimeout through the still-dead tunnel
        // and would dominate the measured elapsed time.
        .retryOnConnectionFailure(false)
        .sslSocketFactory(clientCerts.sslSocketFactory(), clientCerts.trustManager)
        .hostnameVerifier { hostname, _ -> hostname == "localhost" }
        .build()
  }

  @AfterEach
  fun tearDown() {
    client.connectionPool.evictAll()
    client.dispatcher.executorService.shutdown()
    mockWebServer.close()
    tunnel.stop()
  }

  /**
   * Warms up a pooled HTTP/2 connection through a socat tunnel, then engages `iptables -j DROP` to
   * silently kill the connection at L3. The next request carries oversized headers so the HEADERS
   * write overflows the TCP send buffer and blocks. The 1s pingInterval should detect the dead
   * connection within a few intervals.
   */
  @Test
  fun pingIntervalBoundsFailureOnDeadConnection() {
    repeat(3) { i ->
      sendPost("warmup-$i").use { response ->
        check(response.code == 200) { "warm-up $i failed: ${response.code}" }
        response.body.string()
      }
    }

    execInTunnel("iptables", "-I", "INPUT", "-p", "tcp", "--dport", "$TUNNEL_PORT", "-j", "DROP")
    execInTunnel("iptables", "-I", "OUTPUT", "-p", "tcp", "--sport", "$TUNNEL_PORT", "-j", "DROP")

    val startNanos = System.nanoTime()
    assertFailure {
      sendPostWithLargeHeaders().use { it.body.string() }
    }.isInstanceOf<IOException>()
    val elapsedSeconds = (System.nanoTime() - startNanos) / 1_000_000_000.0

    // pingInterval is 1s; design intent is failure within a small multiple of the interval.
    assertThat(elapsedSeconds).isLessThan(3.0)
  }

  private fun sendPost(body: String): Response =
    client
      .newCall(
        Request
          .Builder()
          .url("https://localhost:${tunnel.getMappedPort(TUNNEL_PORT)}/echo")
          .post(body.toRequestBody(JSON))
          .build(),
      ).execute()

  private fun sendPostWithLargeHeaders(): Response =
    client
      .newCall(
        Request
          .Builder()
          .url("https://localhost:${tunnel.getMappedPort(TUNNEL_PORT)}/echo")
          .post("hello".toRequestBody(JSON))
          .header("X-Padding", "X".repeat(LARGE_HEADER_SIZE))
          .build(),
      ).execute()

  private fun execInTunnel(vararg command: String) {
    val result = tunnel.execInContainer(*command)
    check(result.exitCode == 0) { "container exec failed: ${result.stderr}" }
  }

  companion object {
    private const val TUNNEL_PORT = 8443
    private const val LARGE_HEADER_SIZE = 8 * 1024 * 1024
    private val JSON: MediaType = "application/json".toMediaType()
  }
}
