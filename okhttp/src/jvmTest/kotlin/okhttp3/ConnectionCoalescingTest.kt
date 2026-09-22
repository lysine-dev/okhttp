/*
 * Copyright (C) 2017 Square, Inc.
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
import assertk.fail
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Proxy
import java.security.cert.X509Certificate
import java.util.Arrays
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import javax.net.ssl.HostnameVerifier
import javax.net.ssl.SSLPeerUnverifiedException
import javax.net.ssl.SSLSession
import javax.net.ssl.X509TrustManager
import kotlin.test.assertFailsWith
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.junit5.StartStop
import okhttp3.CertificatePinner.Companion.pin
import okhttp3.testing.PlatformRule
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.RegisterExtension

@Tag("Slowish")
class ConnectionCoalescingTest {
  @RegisterExtension
  val platform = PlatformRule()

  @RegisterExtension
  val clientTestRule = OkHttpClientTestRule()

  @StartStop
  private val server = MockWebServer()

  private lateinit var client: OkHttpClient
  private lateinit var rootCa: HeldCertificate
  private lateinit var certificate: HeldCertificate
  private val dns = FakeDns()
  private lateinit var url: HttpUrl
  private lateinit var serverIps: List<InetAddress>

  @BeforeEach
  fun setUp() {
    platform.assumeHttp2Support()
    platform.assumeNotBouncyCastle()
    rootCa =
      HeldCertificate
        .Builder()
        .serialNumber(1L)
        .certificateAuthority(0)
        .commonName("root")
        .build()
    certificate =
      HeldCertificate
        .Builder()
        .signedBy(rootCa)
        .serialNumber(2L)
        .commonName(server.hostName)
        .addSubjectAlternativeName(server.hostName)
        .addSubjectAlternativeName("san.com")
        .addSubjectAlternativeName("*.wildcard.com")
        .addSubjectAlternativeName("differentdns.com")
        .build()
    serverIps = listOf(server.socketAddress.address)
    dns[server.hostName] = serverIps
    dns["san.com"] = serverIps
    dns["nonsan.com"] = serverIps
    dns["www.wildcard.com"] = serverIps
    dns["differentdns.com"] = listOf<InetAddress>()
    val handshakeCertificates =
      HandshakeCertificates
        .Builder()
        .addTrustedCertificate(rootCa.certificate)
        .build()
    client =
      clientTestRule
        .newClientBuilder()
        .fastFallback(false) // Avoid data races.
        .dns(dns)
        .sslSocketFactory(
          handshakeCertificates.sslSocketFactory(),
          handshakeCertificates.trustManager,
        ).build()
    val serverHandshakeCertificates =
      HandshakeCertificates
        .Builder()
        .heldCertificate(certificate)
        .build()
    server.useHttps(serverHandshakeCertificates.sslSocketFactory())
    url = server.url("/robots.txt")
  }

  /**
   * Test connecting to the main host then an alternative, although only subject alternative names
   * are used if present no special consideration of common name.
   */
  @Test
  fun commonThenAlternative() {
    server.enqueue(MockResponse())
    server.enqueue(MockResponse())
    assert200Http2Response(execute(url), server.hostName)
    val sanUrl = url.newBuilder().host("san.com").build()
    assert200Http2Response(execute(sanUrl), "san.com")
    assertThat(client.connectionPool.connectionCount()).isEqualTo(1)
  }

  /**
   * Test connecting to an alternative host then common name, although only subject alternative
   * names are used if present no special consideration of common name.
   */
  @Test
  fun alternativeThenCommon() {
    server.enqueue(MockResponse())
    server.enqueue(MockResponse())
    val sanUrl = url.newBuilder().host("san.com").build()
    assert200Http2Response(execute(sanUrl), "san.com")
    assert200Http2Response(execute(url), server.hostName)
    assertThat(client.connectionPool.connectionCount()).isEqualTo(1)
  }

  /** Test a previously coalesced connection that's no longer healthy.  */
  @Test
  fun staleCoalescedConnection() {
    server.enqueue(MockResponse())
    server.enqueue(MockResponse())
    val connection = AtomicReference<Connection?>()
    client =
      client
        .newBuilder()
        .addNetworkInterceptor(
          Interceptor { chain: Interceptor.Chain? ->
            connection.set(chain!!.connection())
            chain.proceed(chain.request())
          },
        ).build()
    dns["san.com"] = Dns.SYSTEM.lookup(server.hostName).subList(0, 1)
    assert200Http2Response(execute(url), server.hostName)

    // Simulate a stale connection in the pool.
    connection.get()!!.socket().close()
    val sanUrl = url.newBuilder().host("san.com").build()
    assert200Http2Response(execute(sanUrl), "san.com")
    assertThat(client.connectionPool.connectionCount()).isEqualTo(1)
  }

  /**
   * This is an extraordinary test case. Here's what it's trying to simulate.
   * - 2 requests happen concurrently to a host that can be coalesced onto a single connection.
   * - Both request discover no existing connection. They both make a connection.
   * - The first request "wins the race".
   * - The second request discovers it "lost the race" and closes the connection it just opened.
   * - The second request uses the coalesced connection from request1.
   * - The coalesced connection is violently closed after servicing the first request.
   * - The second request discovers the coalesced connection is unhealthy just after acquiring it.
   */
  @Test
  fun coalescedConnectionDestroyedAfterAcquire() {
    server.enqueue(MockResponse())
    server.enqueue(MockResponse())
    dns["san.com"] = Dns.SYSTEM.lookup(server.hostName).subList(0, 1)
    val sanUrl = url.newBuilder().host("san.com").build()
    val latch1 = CountDownLatch(1)
    val latch2 = CountDownLatch(1)
    val latch3 = CountDownLatch(1)
    val latch4 = CountDownLatch(1)
    val listener1: EventListener =
      object : EventListener() {
        override fun connectStart(
          call: Call,
          inetSocketAddress: InetSocketAddress,
          proxy: Proxy,
        ) {
          try {
            // Wait for request2 to guarantee we make 2 separate connections to the server.
            latch1.await()
          } catch (e: InterruptedException) {
            throw AssertionError(e)
          }
        }

        override fun connectionAcquired(
          call: Call,
          connection: Connection,
        ) {
          // We have the connection and it's in the pool. Let request2 proceed to make a connection.
          latch2.countDown()
        }
      }
    val request2Listener: EventListener =
      object : EventListener() {
        override fun connectStart(
          call: Call,
          inetSocketAddress: InetSocketAddress,
          proxy: Proxy,
        ) {
          // Let request1 proceed to make a connection.
          latch1.countDown()
          try {
            // Wait until request1 makes the connection and puts it in the connection pool.
            latch2.await()
          } catch (e: InterruptedException) {
            throw AssertionError(e)
          }
        }

        override fun connectionAcquired(
          call: Call,
          connection: Connection,
        ) {
          // We obtained the coalesced connection. Let request1 violently destroy it.
          latch3.countDown()
          try {
            latch4.await()
          } catch (e: InterruptedException) {
            throw AssertionError(e)
          }
        }
      }

    // Get a reference to the connection so we can violently destroy it.
    val connection = AtomicReference<Connection?>()
    val client1 =
      client
        .newBuilder()
        .addNetworkInterceptor(
          Interceptor { chain: Interceptor.Chain? ->
            connection.set(chain!!.connection())
            chain.proceed(chain.request())
          },
        ).eventListenerFactory(clientTestRule.wrap(listener1))
        .build()
    val request = Request.Builder().url(sanUrl).build()
    val call1 = client1.newCall(request)
    call1.enqueue(
      object : Callback {
        @Throws(IOException::class)
        override fun onResponse(
          call: Call,
          response: Response,
        ) {
          try {
            // Wait until request2 acquires the connection before we destroy it violently.
            latch3.await()
          } catch (e: InterruptedException) {
            throw AssertionError(e)
          }
          assert200Http2Response(response, "san.com")
          connection.get()!!.socket().close()
          latch4.countDown()
        }

        override fun onFailure(
          call: Call,
          e: IOException,
        ) {
          fail("")
        }
      },
    )
    val client2 =
      client
        .newBuilder()
        .eventListenerFactory(clientTestRule.wrap(request2Listener))
        .build()
    val call2 = client2.newCall(request)
    val response = call2.execute()
    assert200Http2Response(response, "san.com")
  }

  /** If the existing connection matches a SAN but not a match for DNS then skip.  */
  @Test
  fun skipsWhenDnsDontMatch() {
    server.enqueue(MockResponse())
    assert200Http2Response(execute(url), server.hostName)
    val differentDnsUrl = url.newBuilder().host("differentdns.com").build()
    assertFailsWith<IOException> {
      execute(differentDnsUrl)
    }
  }

  @Test
  fun skipsOnRedirectWhenDnsDontMatch() {
    server.enqueue(
      MockResponse
        .Builder()
        .code(301)
        .addHeader("Location", url.newBuilder().host("differentdns.com").build())
        .build(),
    )
    server.enqueue(
      MockResponse
        .Builder()
        .body("unexpected call")
        .build(),
    )
    assertFailsWith<IOException> {
      val response = execute(url)
      response.close()
    }
  }

  /** Not in the certificate SAN.  */
  @Test
  fun skipsWhenNotSubjectAltName() {
    server.enqueue(MockResponse())
    server.enqueue(MockResponse())
    assert200Http2Response(execute(url), server.hostName)
    val nonsanUrl = url.newBuilder().host("nonsan.com").build()
    assertFailsWith<SSLPeerUnverifiedException> {
      execute(nonsanUrl)
    }
  }

  @Test
  fun skipsOnRedirectWhenNotSubjectAltName() {
    server.enqueue(
      MockResponse
        .Builder()
        .code(301)
        .addHeader("Location", url.newBuilder().host("nonsan.com").build())
        .build(),
    )
    server.enqueue(MockResponse())
    assertFailsWith<SSLPeerUnverifiedException> {
      val response = execute(url)
      response.close()
    }
  }

  /** Can still coalesce when pinning is used if pins match.  */
  @Test
  fun coalescesWhenCertificatePinsMatch() {
    val pinner =
      CertificatePinner
        .Builder()
        .add("san.com", pin(certificate.certificate))
        .build()
    client = client.newBuilder().certificatePinner(pinner).build()
    server.enqueue(MockResponse())
    server.enqueue(MockResponse())
    assert200Http2Response(execute(url), server.hostName)
    val sanUrl = url.newBuilder().host("san.com").build()
    assert200Http2Response(execute(sanUrl), "san.com")
    assertThat(client.connectionPool.connectionCount()).isEqualTo(1)
  }

  /** Certificate pinning used and not a match will avoid coalescing and try to connect.  */
  @Test
  fun skipsWhenCertificatePinningFails() {
    val pinner =
      CertificatePinner
        .Builder()
        .add("san.com", "sha1/afwiKY3RxoMmLkuRW1l7QsPZTJPwDS2pdDROQjXw8ig=")
        .build()
    client = client.newBuilder().certificatePinner(pinner).build()
    server.enqueue(MockResponse())
    assert200Http2Response(execute(url), server.hostName)
    val sanUrl = url.newBuilder().host("san.com").build()
    assertFailsWith<IOException> {
      execute(sanUrl)
    }
  }

  @Test
  fun skipsOnRedirectWhenCertificatePinningFails() {
    val pinner =
      CertificatePinner
        .Builder()
        .add("san.com", "sha1/afwiKY3RxoMmLkuRW1l7QsPZTJPwDS2pdDROQjXw8ig=")
        .build()
    client = client.newBuilder().certificatePinner(pinner).build()
    server.enqueue(
      MockResponse
        .Builder()
        .code(301)
        .addHeader("Location", url.newBuilder().host("san.com").build())
        .build(),
    )
    server.enqueue(MockResponse())
    assertFailsWith<SSLPeerUnverifiedException> {
      execute(url)
    }
  }

  /**
   * Skips coalescing when hostname verifier is overridden since the intention of the hostname
   * verification is a black box.
   */
  @Test
  fun skipsWhenHostnameVerifierUsed() {
    val verifier = HostnameVerifier { name: String?, session: SSLSession? -> true }
    client = client.newBuilder().hostnameVerifier(verifier).build()
    server.enqueue(MockResponse())
    server.enqueue(MockResponse())
    assert200Http2Response(execute(url), server.hostName)
    val sanUrl = url.newBuilder().host("san.com").build()
    assert200Http2Response(execute(sanUrl), "san.com")
    assertThat(client.connectionPool.connectionCount()).isEqualTo(2)
  }

  @Test
  fun skipsOnRedirectWhenHostnameVerifierUsed() {
    val verifier = HostnameVerifier { name: String?, session: SSLSession? -> true }
    client = client.newBuilder().hostnameVerifier(verifier).build()
    server.enqueue(
      MockResponse
        .Builder()
        .code(301)
        .addHeader("Location", url.newBuilder().host("san.com").build())
        .build(),
    )
    server.enqueue(MockResponse())
    assert200Http2Response(execute(url), "san.com")
    assertThat(client.connectionPool.connectionCount()).isEqualTo(2)
    val c0e0 = server.takeRequest()
    assertThat(c0e0.connectionIndex).isEqualTo(0) // Fresh connection.
    val c1e0 = server.takeRequest()
    assertThat(c1e0.connectionIndex).isEqualTo(1) // Fresh connection.
  }

  /**
   * Check we would use an existing connection to a later DNS result instead of connecting to the
   * first DNS result for the first time.
   */
  @Test
  fun prefersExistingCompatible() {
    server.enqueue(MockResponse())
    server.enqueue(MockResponse())
    val connectCount = AtomicInteger()
    val listener: EventListener =
      object : EventListener() {
        override fun connectStart(
          call: Call,
          inetSocketAddress: InetSocketAddress,
          proxy: Proxy,
        ) {
          connectCount.getAndIncrement()
        }
      }
    client =
      client
        .newBuilder()
        .eventListenerFactory(clientTestRule.wrap(listener))
        .build()
    assert200Http2Response(execute(url), server.hostName)
    val sanUrl = url.newBuilder().host("san.com").build()
    dns["san.com"] =
      Arrays.asList(
        InetAddress.getByAddress("san.com", byteArrayOf(0, 0, 0, 0)),
        serverIps[0],
      )
    assert200Http2Response(execute(sanUrl), "san.com")
    assertThat(client.connectionPool.connectionCount()).isEqualTo(1)
    assertThat(connectCount.get()).isEqualTo(1)
  }

  /** Check that wildcard SANs are supported.  */
  @Test
  fun commonThenWildcard() {
    server.enqueue(MockResponse())
    server.enqueue(MockResponse())
    assert200Http2Response(execute(url), server.hostName)
    val sanUrl = url.newBuilder().host("www.wildcard.com").build()
    assert200Http2Response(execute(sanUrl), "www.wildcard.com")
    assertThat(client.connectionPool.connectionCount()).isEqualTo(1)
  }

  /** Network interceptors check for changes to target.  */
  @Test
  fun worksWithNetworkInterceptors() {
    client =
      client
        .newBuilder()
        .addNetworkInterceptor(
          Interceptor { chain: Interceptor.Chain? ->
            chain!!.proceed(
              chain.request(),
            )
          },
        ).build()
    server.enqueue(MockResponse())
    server.enqueue(MockResponse())
    assert200Http2Response(execute(url), server.hostName)
    val sanUrl = url.newBuilder().host("san.com").build()
    assert200Http2Response(execute(sanUrl), "san.com")
    assertThat(client.connectionPool.connectionCount()).isEqualTo(1)
  }

  @Test
  fun misdirectedRequestResponseCode() {
    server.enqueue(
      MockResponse
        .Builder()
        .body("seed connection")
        .build(),
    )
    server.enqueue(
      MockResponse
        .Builder()
        .code(421)
        .body("misdirected!")
        .build(),
    )
    server.enqueue(
      MockResponse
        .Builder()
        .body("after misdirect")
        .build(),
    )

    // Seed the connection pool.
    assert200Http2Response(execute(url), server.hostName)

    // Use the coalesced connection which should retry on a fresh connection.
    val sanUrl =
      url
        .newBuilder()
        .host("san.com")
        .build()
    execute(sanUrl).use { response ->
      assertThat(response.code).isEqualTo(200)
      assertThat(response.priorResponse!!.code).isEqualTo(421)
      assertThat(response.body.string()).isEqualTo("after misdirect")
    }
    val c0e0 = server.takeRequest()
    assertThat(c0e0.connectionIndex).isEqualTo(0)
    assertThat(c0e0.exchangeIndex).isEqualTo(0)
    val c0e1 = server.takeRequest()
    assertThat(c0e1.connectionIndex).isEqualTo(0)
    assertThat(c0e1.exchangeIndex).isEqualTo(1)
    val c1e0 = server.takeRequest()
    assertThat(c1e0.connectionIndex).isEqualTo(1) // Fresh connection.
    assertThat(c1e0.exchangeIndex).isEqualTo(0)
    assertThat(client.connectionPool.connectionCount()).isEqualTo(2)
  }

  /**
   * Won't coalesce if we can't clean certs e.g. a dev setup.
   */
  @Test
  fun redirectWithDevSetup() {
    val trustManager: X509TrustManager =
      object : X509TrustManager {
        override fun checkClientTrusted(
          x509Certificates: Array<X509Certificate>,
          s: String,
        ) {
        }

        override fun checkServerTrusted(
          x509Certificates: Array<X509Certificate>,
          s: String,
        ) {
        }

        override fun getAcceptedIssuers(): Array<X509Certificate> = arrayOf()
      }
    client =
      client
        .newBuilder()
        .sslSocketFactory(client.sslSocketFactory, trustManager)
        .build()
    server.enqueue(MockResponse())
    server.enqueue(MockResponse())
    assert200Http2Response(execute(url), server.hostName)
    val sanUrl = url.newBuilder().host("san.com").build()
    assert200Http2Response(execute(sanUrl), "san.com")
    assertThat(client.connectionPool.connectionCount()).isEqualTo(2)
  }

  private fun execute(url: HttpUrl) = client.newCall(Request(url = url)).execute()

  private fun assert200Http2Response(
    response: Response,
    expectedHost: String,
  ) {
    assertThat(response.code).isEqualTo(200)
    assertThat(response.request.url.host).isEqualTo(expectedHost)
    assertThat(response.protocol).isEqualTo(Protocol.HTTP_2)
    response.body.close()
  }
}
