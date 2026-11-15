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
import assertk.assertions.isIn
import assertk.assertions.isNull
import java.io.IOException
import java.math.BigInteger
import java.security.AlgorithmParameters
import java.security.KeyFactory
import java.security.KeyPair
import java.security.spec.ECGenParameterSpec
import java.security.spec.ECParameterSpec
import java.security.spec.ECPrivateKeySpec
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLException
import javax.net.ssl.SSLHandshakeException
import javax.net.ssl.SSLPeerUnverifiedException
import kotlin.test.assertFailsWith
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.junit5.StartStop
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate
import okhttp3.tls.decodeCertificatePem
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.RegisterExtension

/**
 * The `emSign Root TLS CA - G3` signed intermediate certificates whose private keys were
 * compromised. (The offending intermediates had private keys that were one byte.)
 *
 * This test sets up the corresponding CA root, one of the compromised intermediates, and makes an
 * HTTP call. This test confirms the call fails with an [SSLException] before any request reaches
 * the server.

 * https://publicobject.com/2026/10/20/mitigating-bad-roots-in-okhttp/
 * https://www.cve.org/CVERecord?id=CVE-2026-111943
 */
class CompromisedIntermediateTest {
  /**
   * Issuer: "CN=emSign Root TLS CA - G3, O=eMudhra Technologies Limited, C=IN"
   * subject: "CN=emSign Root TLS CA - G3, O=eMudhra Technologies Limited, C=IN"
   */
  private val emsignRootTlsCaG3 =
    """
    -----BEGIN CERTIFICATE-----
    MIICKTCCAa+gAwIBAgIQDnYGcvFDRZ/I/gqwvAXjlDAKBggqhkjOPQQDAzBWMQsw
    CQYDVQQGEwJJTjElMCMGA1UECgwcZU11ZGhyYSBUZWNobm9sb2dpZXMgTGltaXRl
    ZDEgMB4GA1UEAwwXZW1TaWduIFJvb3QgVExTIENBIC0gRzMwHhcNMjQwMjA4MDgx
    OTMyWhcNNDkwMjA3MDgxOTMyWjBWMQswCQYDVQQGEwJJTjElMCMGA1UECgwcZU11
    ZGhyYSBUZWNobm9sb2dpZXMgTGltaXRlZDEgMB4GA1UEAwwXZW1TaWduIFJvb3Qg
    VExTIENBIC0gRzMwdjAQBgcqhkjOPQIBBgUrgQQAIgNiAARfD9w/q1cWxL6D9bMG
    H6CQ4u+gAV6GN9s+PwQx9Bl4hC+0iU8QOk8mWO/RwXQy10DCSxT8vSIJClHKp+Ao
    4qJKdYeV0SwZ7LMQziyLfxIUOY6Grja/sQFwLKYf8EdDXAyjQjBAMB0GA1UdDgQW
    BBThOqshfPHjTVSu1HK3/YtZQiCQVDAOBgNVHQ8BAf8EBAMCAQYwDwYDVR0TAQH/
    BAUwAwEB/zAKBggqhkjOPQQDAwNoADBlAjBhCFTkly7Yl7+s4hiw+k6ZJTXT2AiI
    rvmsmfqPr5E7ecwXb6kXA5LTFd6rjwTuV3sCMQC9w6ZSbVq1mveMO6dKFziOzClT
    KCT2RFT6yXtq6nzgRICgBmiXbJAXsmZ8yAwjyWc=
    -----END CERTIFICATE-----
    """.trimIndent().decodeCertificatePem()

  /**
   * Issuer: "CN=emSign Root TLS CA - G3, O=eMudhra Technologies Limited, C=IN"
   * Subject: "CN=EM OV TLS CA - G3C, O=eMudhra Inc, C=US"
   */
  private val emsignEvOvTlsCaG3c =
    """
    -----BEGIN CERTIFICATE-----
    MIIDaDCCAu6gAwIBAgIPKWkmwiejoVJjkrGUgqUEMAoGCCqGSM49BAMDMFYxCzAJ
    BgNVBAYTAklOMSUwIwYDVQQKDBxlTXVkaHJhIFRlY2hub2xvZ2llcyBMaW1pdGVk
    MSAwHgYDVQQDDBdlbVNpZ24gUm9vdCBUTFMgQ0EgLSBHMzAeFw0yNTA0MzAxNTM3
    NDFaFw00MDA0MjgxODMwMDBaMEAxCzAJBgNVBAYTAlVTMRQwEgYDVQQKDAtlTXVk
    aHJhIEluYzEbMBkGA1UEAwwSRU0gT1YgVExTIENBIC0gRzNDMHYwEAYHKoZIzj0C
    AQYFK4EEACIDYgAEE4JRzVKskpjByKrZdzId65fnCb0LTKCspV3IrVHc/J0ViaFZ
    fjpRIOHv1jHGPhg1ysrimGmmLhYx6KKBgatWYW3EXZGKvAnzqw5jz3kqpNztc4e+
    N7ulaVSfHAKycO1no4IBljCCAZIwHwYDVR0jBBgwFoAU4TqrIXzx401UrtRyt/2L
    WUIgkFQwHQYDVR0OBBYEFDTq6gzryQra+dDF4u2beMmHnhtsMB0GA1UdJQQWMBQG
    CCsGAQUFBwMBBggrBgEFBQcDAjBdBgNVHSAEVjBUMDkGCysGAQQBg44hAQABMCow
    KAYIKwYBBQUHAgEWHGh0dHA6Ly9yZXBvc2l0b3J5LmVtc2lnbi5jb20wDQYLKwYB
    BAGDjiEBAm4wCAYGZ4EMAQICMHUGCCsGAQUFBwEBBGkwZzAkBggrBgEFBQcwAYYY
    aHR0cDovL29jc3AtYy5lbXNpZ24uY29tMD8GCCsGAQUFBzAChjNodHRwOi8vcmVw
    b3NpdG9yeS5lbXNpZ24uY29tL2NlcnRzL0VNT1ZUTFNDQUczQy5wN2MwNwYDVR0f
    BDAwLjAsoCqgKIYmaHR0cDovL2NybC5lbXNpZ24uY29tLz9yb290dGxzY2FnMy5j
    cmwwDgYDVR0PAQH/BAQDAgEGMBIGA1UdEwEB/wQIMAYBAf8CAQAwCgYIKoZIzj0E
    AwMDaAAwZQIxAMSZt2xsPeumRdeSMSQ3CbzIZeQ/q7GDCWfeE1Pq+mhgA6gRM44c
    NVF3xJ+wsxJk+wIwFJbaDMokD8Ei6eNOiA8yUMYvfzQyngmrhZQMdPCK1SewrR79
    4rSb9vGqxZzelQth
    -----END CERTIFICATE-----
    """.trimIndent().decodeCertificatePem()

  private val emsignEvOvTlsCaG3cPrivateKey =
    KeyFactory.getInstance("EC").run {
      val secp384r1 =
        AlgorithmParameters.getInstance("EC").run {
          init(ECGenParameterSpec("secp384r1"))
          getParameterSpec(ECParameterSpec::class.java)
        }
      // The private key for this particular intermediate was 4!
      generatePrivate(ECPrivateKeySpec(BigInteger("4"), secp384r1))
    }

  private val serverCertificate =
    HeldCertificate
      .Builder()
      .signedBy(
        HeldCertificate(
          keyPair = KeyPair(emsignEvOvTlsCaG3c.publicKey, emsignEvOvTlsCaG3cPrivateKey),
          certificate = emsignEvOvTlsCaG3c,
        ),
      ).addSubjectAlternativeName("bank.secure")
      .build()

  @StartStop
  private val server =
    MockWebServer().apply {
      val serverCertificates =
        HandshakeCertificates
          .Builder()
          .heldCertificate(serverCertificate, emsignEvOvTlsCaG3c)
          .build()
      useHttps(serverCertificates.sslSocketFactory())
    }

  private val dns =
    FakeDns().apply {
      set("bank.secure", Dns.SYSTEM.lookup("localhost"))
    }

  private val trustEmsignHandshakeCertificates =
    HandshakeCertificates
      .Builder()
      .addTrustedCertificate(emsignRootTlsCaG3)
      .build()

  @JvmField
  @RegisterExtension
  val clientTestRule = OkHttpClientTestRule()

  /**
   * It is possible some host platforms continue to trust the offending root certificate, and this
   * test will continue to exercise the problem without configuring the `sslSocketFactory`.
   */
  private val client =
    clientTestRule
      .newClientBuilder()
      .dns(dns)
      .sslSocketFactory(
        trustEmsignHandshakeCertificates.sslSocketFactory(),
        trustEmsignHandshakeCertificates.trustManager,
      ).build()

  @Test
  fun test() {
    server.enqueue(MockResponse(body = "hello this is your bank"))

    val call =
      client.newCall(
        Request(
          url = "https://bank.secure:${server.port}/".toHttpUrl(),
          headers = Headers.headersOf("Authorization", "secret-token"),
        ),
      )

    val exception =
      assertFailsWith<IOException> {
        call.execute()
      }

    // Nothing reached the server.
    assertThat(server.takeRequest(100, TimeUnit.MILLISECONDS)).isNull()

    // The exception is as expected.
    val originalException = exception.suppressedExceptions.singleOrNull() ?: exception
    assertThat(originalException::class).isIn(
      SSLHandshakeException::class, // Rejected by Conscrypt.
      SSLPeerUnverifiedException::class, // Rejected by OkHttp.
    )
  }
}
