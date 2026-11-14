/*
 * Copyright (C) 2019 Square, Inc.
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

import app.cash.burst.Burst
import app.cash.burst.burstValues
import assertk.assertThat
import assertk.assertions.containsExactlyInAnyOrder
import assertk.assertions.isEmpty
import assertk.assertions.isNotEmpty
import javax.net.ssl.SSLSocket
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.junit5.StartStop
import okhttp3.sockets.DelegatingSSLSocketFactory
import okhttp3.testing.PlatformRule
import okhttp3.testing.PlatformVersion
import okio.ByteString.Companion.toByteString
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.RegisterExtension

@Burst
class SessionReuseTest {
  @JvmField @RegisterExtension
  var platform = PlatformRule()

  @JvmField @RegisterExtension
  val clientTestRule = OkHttpClientTestRule()

  private val handshakeCertificates = platform.localhostHandshakeCertificates()

  var client = clientTestRule.newClient()

  @StartStop
  private val server = MockWebServer()

  @BeforeEach
  fun setUp() {
    // Default after JDK 14, but we are avoiding tests that assume special setup.
    // System.setProperty("jdk.tls.client.enableSessionTicketExtension", "true")
    // System.setProperty("jdk.tls.server.enableSessionTicketExtension", "true")

    // IllegalStateException: Cannot resume session and session creation is disabled
    platform.assumeNotBouncyCastle()
  }

  @Test
  fun testSessionReuse(tlsVersion: String = burstValues("TLSv1.2", "TLSv1.3")) {
    if (tlsVersion == TlsVersion.TLS_1_3.javaName) {
      assumeTrue(PlatformVersion.majorVersion != 8)
    }

    val sessionIds = mutableListOf<String>()

    enableTls()

    val tlsVersion = TlsVersion.forJavaName(tlsVersion)
    val spec =
      ConnectionSpec
        .Builder(ConnectionSpec.MODERN_TLS)
        .tlsVersions(tlsVersion)
        .build()

    var reuseSession = false

    val sslContext = handshakeCertificates.sslContext()
    val systemSslSocketFactory = sslContext.socketFactory
    val sslSocketFactory =
      object : DelegatingSSLSocketFactory(systemSslSocketFactory) {
        override fun configureSocket(sslSocket: SSLSocket): SSLSocket =
          sslSocket.apply {
            if (reuseSession) {
              this.enableSessionCreation = false
            }
          }
      }

    client =
      client
        .newBuilder()
        .connectionSpecs(listOf(spec))
        .eventListenerFactory(
          clientTestRule.wrap(
            object : EventListener() {
              override fun connectionAcquired(
                call: Call,
                connection: Connection,
              ) {
                val sslSocket = connection.socket() as SSLSocket

                sessionIds.add(
                  sslSocket.session.id
                    .toByteString()
                    .hex(),
                )
              }
            },
          ),
        ).sslSocketFactory(sslSocketFactory, handshakeCertificates.trustManager)
        .build()

    server.enqueue(MockResponse(body = "abc1"))
    server.enqueue(MockResponse(body = "abc2"))

    val request = Request(server.url("/"))

    client.newCall(request).execute().use { response ->
      assertEquals(200, response.code)
    }

    client.connectionPool.evictAll()
    assertEquals(0, client.connectionPool.connectionCount())

    // Force reuse. This appears flaky (30% of the time) even though sessions are reused.
    // javax.net.ssl.SSLHandshakeException: No new session is allowed and no existing
    // session can be resumed
    //
    // Report https://bugs.java.com/bugdatabase/view_bug.do?bug_id=JDK-8264944
    // Sessions improvement https://bugs.java.com/bugdatabase/view_bug.do?bug_id=JDK-8245576
    if (!platform.isJdk9() && !platform.isOpenJsse() && !platform.isJdk8Alpn()) {
      reuseSession = true
    }

    client.newCall(request).execute().use { response ->
      assertEquals(200, response.code)
    }

    assertEquals(2, sessionIds.size)
    val directSessionIds =
      sslContext.clientSessionContext.ids
        .toList()
        .map { it.toByteString().hex() }

    if (platform.isConscrypt()) {
      if (tlsVersion == TlsVersion.TLS_1_3) {
        assertThat(sessionIds[0]).isEmpty()
        assertThat(sessionIds[1]).isEmpty()

        // https://github.com/google/conscrypt/issues/985
        // assertThat(directSessionIds).containsExactlyInAnyOrder(sessionIds[0], sessionIds[1])
      } else {
        assertThat(sessionIds[0]).isNotEmpty()
        assertThat(sessionIds[1]).isNotEmpty()

        assertThat(directSessionIds).containsExactlyInAnyOrder(sessionIds[1])
      }
    } else {
      if (tlsVersion == TlsVersion.TLS_1_3) {
        // We can't rely on the same session id with TLSv1.3 ids.
        assertNotEquals(sessionIds[0], sessionIds[1])
      } else {
        // With TLSv1.2 it is really JDK specific.
        // assertEquals(sessionIds[0], sessionIds[1])
        // assertThat(directSessionIds).contains(sessionIds[0], sessionIds[1])
      }
      assertThat(sessionIds[0]).isNotEmpty()
    }
  }

  private fun enableTls() {
    client =
      client
        .newBuilder()
        .sslSocketFactory(
          handshakeCertificates.sslSocketFactory(),
          handshakeCertificates.trustManager,
        ).build()
    server.useHttps(handshakeCertificates.sslSocketFactory())
  }
}
