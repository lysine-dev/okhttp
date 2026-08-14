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
import assertk.assertions.hasMessage
import assertk.assertions.isEqualTo
import assertk.assertions.isNull
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Proxy
import java.util.concurrent.LinkedBlockingQueue
import javax.net.ssl.SSLException
import kotlin.test.assertFailsWith
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
import okio.ByteString
import okio.ByteString.Companion.encodeUtf8
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.RegisterExtension

/**
 * There's no support for Encrypted Client Hello (ECH) in any of our server-side SSL libraries, so
 * we fake it with [FakeNetworkPlatform].
 */
class EchOnFakeNetworkTest {
  private val platform = FakeNetworkPlatform()

  @RegisterExtension
  val platformRule =
    PlatformRule(
      platform = platform,
    )

  @RegisterExtension
  val clientTestRule = OkHttpClientTestRule()

  private val certificateAuthority =
    HeldCertificate
      .Builder()
      .certificateAuthority(0)
      .build()

  private val publicServerCertificate =
    HeldCertificate
      .Builder()
      .signedBy(certificateAuthority)
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
      .signedBy(certificateAuthority)
      .addSubjectAlternativeName("private.ech.example.com")
      .build()
  private val privateServerCertificates =
    HandshakeCertificates
      .Builder()
      .heldCertificate(privateServerCertificate)
      .build()

  private val untrustedServerCertificate =
    HeldCertificate
      .Builder()
      .addSubjectAlternativeName("untrusted.ech.example.com")
      .build()
  private val untrustedServerCertificates =
    HandshakeCertificates
      .Builder()
      .heldCertificate(untrustedServerCertificate)
      .build()

  private val ipAddressServerCertificate =
    HeldCertificate
      .Builder()
      .signedBy(certificateAuthority)
      .addSubjectAlternativeName("10.20.30.40")
      .build()
  private val ipAddressServerCertificates =
    HandshakeCertificates
      .Builder()
      .heldCertificate(ipAddressServerCertificate)
      .build()

  private val clientCertificates =
    HandshakeCertificates
      .Builder()
      .addTrustedCertificate(certificateAuthority.certificate)
      .build()

  private val serverIpAddress = InetAddress.getByName("1:2::3:4")
  private val serverProxyAddress =
    Proxy(
      Proxy.Type.HTTP,
      InetSocketAddress.createUnresolved("proxy.example.com", 443),
    )

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
        addRecord(
          hostname = "proxy.example.com",
          address = serverIpAddress,
        )
        addRecord(
          hostname = "proxy.example.com",
          echConfigList = "proxy ECH list (must not be used!)".encodeUtf8(),
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
    platform.handshaker = handshakerAcceptingEch()

    executeHttpExchange()

    assertThat(events.take())
      .isEqualTo("handshake hostname=private.ech.example.com echConfigList=$echConfigList")
  }

  /**
   * When we do an HTTP `CONNECT` call, the proxy server is the computer that does a DNS lookup for
   * the origin server (and not the client). Confirm that we don't use the proxy server's HTTPS
   * record to handshake with the origin.
   */
  @Test
  fun `ech is not used with proxy`() {
    platform.handshaker = handshakerAcceptingEch()

    client =
      client
        .newBuilder()
        .proxy(serverProxyAddress)
        .build()

    server.enqueue(
      MockResponse
        .Builder()
        .inTunnel()
        .build(),
    )

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

    val connectRequest = server.takeRequest()
    assertThat(connectRequest.method).isEqualTo("CONNECT")
    assertThat(connectRequest.body).isNull()

    val getRequest = server.takeRequest()
    assertThat(getRequest.method).isEqualTo("GET")
    assertThat(getRequest.body).isNull()

    assertThat(events.take())
      .isEqualTo("handshake hostname=private.ech.example.com echConfigList=null")
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
      handshakerWithUpdatedEchConfigList(
        updatedEchConfigList = null,
        attemptLimit = 2,
      )

    executeHttpExchange()

    assertThat(events.take())
      .isEqualTo("handshake hostname=private.ech.example.com echConfigList=$echConfigList")
    assertThat(events.take())
      .isEqualTo("handshake hostname=private.ech.example.com echConfigList=null")
  }

  @Test
  fun `server updates ech config for retry`() {
    val updatedEchConfigList = "new key to encrypt 'private.ech.example.com'".encodeUtf8()
    platform.handshaker =
      handshakerWithUpdatedEchConfigList(
        updatedEchConfigList = updatedEchConfigList,
        attemptLimit = 2,
      )

    executeHttpExchange()

    assertThat(events.take())
      .isEqualTo("handshake hostname=private.ech.example.com echConfigList=$echConfigList")
    assertThat(events.take())
      .isEqualTo("handshake hostname=private.ech.example.com echConfigList=$updatedEchConfigList")
  }

  @Test
  fun `server rejected because public name is not verified`() {
    platform.handshaker = handshakerWithUnverifiedPublicName(attemptLimit = 1)

    val e = failHttpExchange()
    assertThat(e).hasMessage("Encrypted Client Hello (ECH) rejected")

    assertThat(events.take())
      .isEqualTo("handshake hostname=private.ech.example.com echConfigList=$echConfigList")
  }

  @Test
  fun `only two attempts are made`() {
    val echConfigList2 = "key #2 to encrypt 'private.ech.example.com'".encodeUtf8()
    val echConfigList3 = "key #3 to encrypt 'private.ech.example.com'".encodeUtf8()
    platform.handshaker =
      object : Handshaker {
        val delegate = InsecureHandshaker()
        var handshakeCount = 0

        override fun handshake(
          client: Handshaker.ClientInputs,
          server: Handshaker.ServerInputs,
        ): Handshaker.Result {
          events.put("handshake hostname=${client.hostname} echConfigList=${client.echConfigList}")

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
                nextEchConfigList =
                  when (handshakeCount++) {
                    0 -> echConfigList2
                    1 -> echConfigList3
                    else -> error("unexpected handshake")
                  },
              ),
            clientHandshake = publicNameHandshake.clientHandshake,
            serverHandshake = publicNameHandshake.serverHandshake,
            selectedProtocol = publicNameHandshake.selectedProtocol,
          )
        }
      }

    val e = failHttpExchange()
    assertThat(e).hasMessage("Encrypted Client Hello (ECH) rejected")

    assertThat(events.take())
      .isEqualTo("handshake hostname=private.ech.example.com echConfigList=$echConfigList")
    assertThat(events.take())
      .isEqualTo("handshake hostname=private.ech.example.com echConfigList=$echConfigList2")
  }

  @Test
  fun `only two attempts are made even if third attempt would disable ech`() {
    val echConfigList2 = "key #2 to encrypt 'private.ech.example.com'".encodeUtf8()
    platform.handshaker =
      object : Handshaker {
        val delegate = InsecureHandshaker()
        var handshakeCount = 0

        override fun handshake(
          client: Handshaker.ClientInputs,
          server: Handshaker.ServerInputs,
        ): Handshaker.Result {
          events.put("handshake hostname=${client.hostname} echConfigList=${client.echConfigList}")

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
                nextEchConfigList =
                  when (handshakeCount++) {
                    0 -> echConfigList2
                    1 -> null
                    else -> error("unexpected handshake")
                  },
              ),
            clientHandshake = publicNameHandshake.clientHandshake,
            serverHandshake = publicNameHandshake.serverHandshake,
            selectedProtocol = publicNameHandshake.selectedProtocol,
          )
        }
      }

    val e = failHttpExchange()
    assertThat(e).hasMessage("Encrypted Client Hello (ECH) rejected")

    assertThat(events.take())
      .isEqualTo("handshake hostname=private.ech.example.com echConfigList=$echConfigList")
    assertThat(events.take())
      .isEqualTo("handshake hostname=private.ech.example.com echConfigList=$echConfigList2")
  }

  @Test
  fun `no retry if public name is not a DNS hostname`() {
    platform.handshaker =
      object : Handshaker {
        val delegate = InsecureHandshaker()
        var handshakeCount = 0

        override fun handshake(
          client: Handshaker.ClientInputs,
          server: Handshaker.ServerInputs,
        ): Handshaker.Result {
          check(handshakeCount++ == 0)
          events.put("handshake hostname=${client.hostname} echConfigList=${client.echConfigList}")

          val publicClient =
            client.copy(
              hostname = "public.ech.example.com",
            )
          val publicServer =
            server.copy(
              keyManager = ipAddressServerCertificates.keyManager,
            )
          val publicNameHandshake = delegate.handshake(publicClient, publicServer)
          return Handshaker.Result.Failure(
            exception =
              FakeNetworkEchRejectedException(
                publicName = "10.20.30.40",
                nextEchConfigList = null,
              ),
            clientHandshake = publicNameHandshake.clientHandshake,
            serverHandshake = publicNameHandshake.serverHandshake,
            selectedProtocol = publicNameHandshake.selectedProtocol,
          )
        }
      }

    val e = failHttpExchange()
    assertThat(e).hasMessage("Encrypted Client Hello (ECH) rejected")

    assertThat(events.take())
      .isEqualTo("handshake hostname=private.ech.example.com echConfigList=$echConfigList")
  }

  private fun handshakerAcceptingEch() =
    object : Handshaker {
      override fun handshake(
        client: Handshaker.ClientInputs,
        server: Handshaker.ServerInputs,
      ): Handshaker.Result {
        events.put("handshake hostname=${client.hostname} echConfigList=${client.echConfigList}")
        return InsecureHandshaker().handshake(client, server)
      }
    }

  private fun handshakerWithUpdatedEchConfigList(
    updatedEchConfigList: ByteString?,
    attemptLimit: Int,
  ) = object : Handshaker {
    val delegate = InsecureHandshaker()
    var handshakeCount = 0

    override fun handshake(
      client: Handshaker.ClientInputs,
      server: Handshaker.ServerInputs,
    ): Handshaker.Result {
      val attempt = handshakeCount++
      check(attempt <= attemptLimit) { "exceeded attempt limit" }
      events.put("handshake hostname=${client.hostname} echConfigList=${client.echConfigList}")

      when (attempt) {
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
                nextEchConfigList = updatedEchConfigList,
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

  private fun handshakerWithUnverifiedPublicName(attemptLimit: Int) =
    object : Handshaker {
      val delegate = InsecureHandshaker()
      var handshakeCount = 0

      override fun handshake(
        client: Handshaker.ClientInputs,
        server: Handshaker.ServerInputs,
      ): Handshaker.Result {
        val attempt = handshakeCount++
        check(attempt <= attemptLimit) { "exceeded attempt limit" }
        events.put("handshake hostname=${client.hostname} echConfigList=${client.echConfigList}")

        val publicClient =
          client.copy(
            hostname = "public.ech.example.com",
          )
        val publicServer =
          server.copy(
            keyManager = untrustedServerCertificates.keyManager,
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
    }

  private fun executeHttpExchange() {
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
  }

  private fun failHttpExchange(): SSLException {
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

    return assertFailsWith<SSLException> {
      call.execute()
    }
  }
}
