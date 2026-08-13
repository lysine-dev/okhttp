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
package okhttp3

import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isNull
import java.net.InetAddress
import java.util.concurrent.LinkedBlockingQueue
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.sockets.FakeNetworkEchRejectedException
import okhttp3.sockets.FakeNetworkPlatform
import okhttp3.sockets.Handshaker
import okhttp3.sockets.InsecureHandshaker
import okhttp3.testing.PlatformRule
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate
import okio.ByteString.Companion.encodeUtf8
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.RegisterExtension

class EchOnFakeNetworkTest {
  private val platform = FakeNetworkPlatform()

  @RegisterExtension
  val platformRule =
    PlatformRule(
      platform = platform,
    )

  @RegisterExtension
  val clientTestRule = OkHttpClientTestRule()

  private val publicServerCertificate =
    HeldCertificate
      .Builder()
      .addSubjectAlternativeName("public.ech.example.com")
      .build()
  private val publicServerCertificates =
    HandshakeCertificates
      .Builder()
      .heldCertificate(publicServerCertificate)
      .build()

  private val privateServerCertificate =
    HeldCertificate
      .Builder()
      .addSubjectAlternativeName("private.ech.example.com")
      .build()
  private val privateServerCertificates =
    HandshakeCertificates
      .Builder()
      .heldCertificate(privateServerCertificate)
      .build()

  private val clientCertificates =
    HandshakeCertificates
      .Builder()
      .addTrustedCertificate(privateServerCertificate.certificate)
      .addTrustedCertificate(publicServerCertificate.certificate)
      .build()

  private val serverIpAddress = InetAddress.getByName("1:2::3:4")

  private val echConfigList = "keys to encrypt 'private.ech.example.com'".encodeUtf8()
  private val events = LinkedBlockingQueue<String>()

  private val dns =
    FakeDns()
      .apply {
        addRecord(
          hostname = "private.ech.example.com",
          address = serverIpAddress,
        )
        addRecord(
          hostname = "private.ech.example.com",
          echConfigList = echConfigList,
        )
      }

  // We can't create these until after platformRule runs. Sigh.
  private lateinit var server: MockWebServer
  private lateinit var client: OkHttpClient

  @BeforeEach
  fun setUp() {
    server =
      MockWebServer()
        .apply {
          useHttps(privateServerCertificates.sslSocketFactory())
        }

    client =
      clientTestRule
        .newClientBuilder()
        .dns(dns)
        .sslSocketFactory(
          clientCertificates.sslSocketFactory(),
          clientCertificates.trustManager,
        ).build()

    server.start(serverIpAddress, 443)
  }

  @AfterEach
  fun tearDown() {
    server.close()
  }

  @Test
  fun `server accepts ech`() {
    platform.handshaker =
      object : Handshaker {
        override fun handshake(
          client: Handshaker.ClientInputs,
          server: Handshaker.ServerInputs,
        ): Handshaker.Result {
          events.put("handshake hostname=${client.hostname} echConfigList=${client.echConfigList}")
          return InsecureHandshaker().handshake(client, server)
        }
      }

    server.enqueue(
      MockResponse
        .Builder()
        .body("abc")
        .build(),
    )

    val request =
      Request(
        url = "https://private.ech.example.com/".toHttpUrl(),
      )

    val call = client.newCall(request)

    val response = call.execute()
    assertThat(response.code).isEqualTo(200)
    assertThat(response.body.string()).isEqualTo("abc")

    val recordedRequest = server.takeRequest()
    assertThat(recordedRequest.method).isEqualTo("GET")
    assertThat(recordedRequest.body).isNull()

    assertThat(events.take())
      .isEqualTo("handshake hostname=private.ech.example.com echConfigList=$echConfigList")
  }

  /**
   * The server gets this handshake:
   * ```
   *   ClientHelloOuter=public.ech.example.com
   *   ClientHelloInner=private.ech.example.com
   * ```
   *
   * The server responds with valid certificates for `ClientHelloOuter`, so the client retries
   * the call without ECH.
   *
   * The SSL socket library uses an exception as the mechanism to indicate that ECH wasn't used.
   */
  @Test
  fun `server securely disables ech`() {
    platform.handshaker =
      object : Handshaker {
        val delegate = InsecureHandshaker()
        var handshakeCount = 0

        override fun handshake(
          client: Handshaker.ClientInputs,
          server: Handshaker.ServerInputs,
        ): Handshaker.Result {
          events.put("handshake hostname=${client.hostname} echConfigList=${client.echConfigList}")

          when (handshakeCount++) {
            0 -> {
              val publicClient =
                client.copy(
                  hostname = "public.ech.example.com",
                )
              val publicServer =
                server.copy(
                  keyManager = publicServerCertificates.keyManager,
                )
              val publicNameHandshake = delegate.handshake(publicClient, publicServer)
              return Handshaker.Result.Failure(
                exception =
                  FakeNetworkEchRejectedException(
                    publicName = "public.ech.example.com",
                    nextEchConfigList = null,
                  ),
                clientHandshake = publicNameHandshake.clientHandshake,
                serverHandshake = publicNameHandshake.serverHandshake,
                selectedProtocol = publicNameHandshake.selectedProtocol,
              )
            }

            1 -> {
              return delegate.handshake(client, server)
            }

            else -> {
              error("unexpected handshake")
            }
          }
        }
      }

    server.enqueue(
      MockResponse
        .Builder()
        .body("abc")
        .build(),
    )

    val request =
      Request(
        url = "https://private.ech.example.com/".toHttpUrl(),
      )

    val call = client.newCall(request)

    val response = call.execute()
    assertThat(response.code).isEqualTo(200)
    assertThat(response.body.string()).isEqualTo("abc")

    val recordedRequest = server.takeRequest()
    assertThat(recordedRequest.method).isEqualTo("GET")
    assertThat(recordedRequest.body).isNull()

    assertThat(events.take())
      .isEqualTo("handshake hostname=private.ech.example.com echConfigList=$echConfigList")
    assertThat(events.take())
      .isEqualTo("handshake hostname=private.ech.example.com echConfigList=null")
  }
}
