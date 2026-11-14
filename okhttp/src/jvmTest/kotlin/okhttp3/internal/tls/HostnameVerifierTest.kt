/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements. See the NOTICE file distributed with this
 * work for additional information regarding copyright ownership. The ASF
 * licenses this file to You under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 */
package okhttp3.internal.tls

import assertk.assertThat
import assertk.assertions.containsExactly
import assertk.assertions.isEqualTo
import assertk.assertions.isFalse
import assertk.assertions.isInstanceOf
import assertk.assertions.isTrue
import java.io.ByteArrayInputStream
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import javax.net.ssl.SSLSession
import javax.security.auth.x500.X500Principal
import okhttp3.FakeSSLSession
import okhttp3.OkHttpClient
import okhttp3.internal.canParseAsIpAddress
import okhttp3.internal.platform.Platform.Companion.isAndroid
import okhttp3.testing.PlatformRule
import okhttp3.tls.HeldCertificate
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.RegisterExtension

/**
 * Tests for our hostname verifier. Most of these tests are from AOSP, which itself includes tests
 * from the Apache HTTP Client test suite.
 */
class HostnameVerifierTest {
  private val verifier = OkHostnameVerifier

  @RegisterExtension
  var platform = PlatformRule()

  @Test fun verify() {
    val session = FakeSSLSession()
    assertThat(verifier.verify("localhost", session)).isFalse()
  }

  @Test fun verifyCn() {
    // CN=foo.com
    val session =
      session(
        """
        -----BEGIN CERTIFICATE-----
        MIIERjCCAy6gAwIBAgIJAIz+EYMBU6aQMA0GCSqGSIb3DQEBBQUAMIGiMQswCQYD
        VQQGEwJDQTELMAkGA1UECBMCQkMxEjAQBgNVBAcTCVZhbmNvdXZlcjEWMBQGA1UE
        ChMNd3d3LmN1Y2JjLmNvbTEUMBIGA1UECxQLY29tbW9uc19zc2wxHTAbBgNVBAMU
        FGRlbW9faW50ZXJtZWRpYXRlX2NhMSUwIwYJKoZIhvcNAQkBFhZqdWxpdXNkYXZp
        ZXNAZ21haWwuY29tMB4XDTA2MTIxMTE1MzE0MVoXDTI4MTEwNTE1MzE0MVowgaQx
        CzAJBgNVBAYTAlVTMREwDwYDVQQIEwhNYXJ5bGFuZDEUMBIGA1UEBxMLRm9yZXN0
        IEhpbGwxFzAVBgNVBAoTDmh0dHBjb21wb25lbnRzMRowGAYDVQQLExF0ZXN0IGNl
        cnRpZmljYXRlczEQMA4GA1UEAxMHZm9vLmNvbTElMCMGCSqGSIb3DQEJARYWanVs
        aXVzZGF2aWVzQGdtYWlsLmNvbTCCASIwDQYJKoZIhvcNAQEBBQADggEPADCCAQoC
        ggEBAMhjr5aCPoyp0R1iroWAfnEyBMGYWoCidH96yGPFjYLowez5aYKY1IOKTY2B
        lYho4O84X244QrZTRl8kQbYtxnGh4gSCD+Z8gjZ/gMvLUlhqOb+WXPAUHMB39GRy
        zerA/ZtrlUqf+lKo0uWcocxeRc771KN8cPH3nHZ0rV0Hx4ZAZy6U4xxObe4rtSVY
        07hNKXAb2odnVqgzcYiDkLV8ilvEmoNWMWrp8UBqkTcpEhYhCYp3cTkgJwMSuqv8
        BqnGd87xQU3FVZI4tbtkB+KzjD9zz8QCDJAfDjZHR03KNQ5mxOgXwxwKw6lGMaiV
        JTxpTKqym93whYk93l3ocEe55c0CAwEAAaN7MHkwCQYDVR0TBAIwADAsBglghkgB
        hvhCAQ0EHxYdT3BlblNTTCBHZW5lcmF0ZWQgQ2VydGlmaWNhdGUwHQYDVR0OBBYE
        FJ8Ud78/OrbKOIJCSBYs2tDLXofYMB8GA1UdIwQYMBaAFHua2o+QmU5S0qzbswNS
        yoemDT4NMA0GCSqGSIb3DQEBBQUAA4IBAQC3jRmEya6sQCkmieULcvx8zz1euCk9
        fSez7BEtki8+dmfMXe3K7sH0lI8f4jJR0rbSCjpmCQLYmzC3NxBKeJOW0RcjNBpO
        c2JlGO9auXv2GDP4IYiXElLJ6VSqc8WvDikv0JmCCWm0Zga+bZbR/EWN5DeEtFdF
        815CLpJZNcYwiYwGy/CVQ7w2TnXlG+mraZOz+owr+cL6J/ZesbdEWfjoS1+cUEhE
        HwlNrAu8jlZ2UqSgskSWlhYdMTAP9CPHiUv9N7FcT58Itv/I4fKREINQYjDpvQcx
        SaTYb9dr5sB4WLNglk7zxDtM80H518VvihTcP7FHL+Gn6g4j5fkI98+S
        -----END CERTIFICATE-----
        """.trimIndent(),
      )
    assertThat(verifier.verify("foo.com", session)).isFalse()
    assertThat(verifier.verify("a.foo.com", session)).isFalse()
    assertThat(verifier.verify("bar.com", session)).isFalse()
  }

  @Test fun verifyNonAsciiCn() {
    // CN=&#x82b1;&#x5b50;.co.jp
    val session =
      session(
        """
        -----BEGIN CERTIFICATE-----
        MIIESzCCAzOgAwIBAgIJAIz+EYMBU6aTMA0GCSqGSIb3DQEBBQUAMIGiMQswCQYD
        VQQGEwJDQTELMAkGA1UECBMCQkMxEjAQBgNVBAcTCVZhbmNvdXZlcjEWMBQGA1UE
        ChMNd3d3LmN1Y2JjLmNvbTEUMBIGA1UECxQLY29tbW9uc19zc2wxHTAbBgNVBAMU
        FGRlbW9faW50ZXJtZWRpYXRlX2NhMSUwIwYJKoZIhvcNAQkBFhZqdWxpdXNkYXZp
        ZXNAZ21haWwuY29tMB4XDTA2MTIxMTE1NDIxNVoXDTI4MTEwNTE1NDIxNVowgakx
        CzAJBgNVBAYTAlVTMREwDwYDVQQIDAhNYXJ5bGFuZDEUMBIGA1UEBwwLRm9yZXN0
        IEhpbGwxFzAVBgNVBAoMDmh0dHBjb21wb25lbnRzMRowGAYDVQQLDBF0ZXN0IGNl
        cnRpZmljYXRlczEVMBMGA1UEAwwM6Iqx5a2QLmNvLmpwMSUwIwYJKoZIhvcNAQkB
        FhZqdWxpdXNkYXZpZXNAZ21haWwuY29tMIIBIjANBgkqhkiG9w0BAQEFAAOCAQ8A
        MIIBCgKCAQEAyGOvloI+jKnRHWKuhYB+cTIEwZhagKJ0f3rIY8WNgujB7PlpgpjU
        g4pNjYGViGjg7zhfbjhCtlNGXyRBti3GcaHiBIIP5nyCNn+Ay8tSWGo5v5Zc8BQc
        wHf0ZHLN6sD9m2uVSp/6UqjS5ZyhzF5FzvvUo3xw8fecdnStXQfHhkBnLpTjHE5t
        7iu1JVjTuE0pcBvah2dWqDNxiIOQtXyKW8Sag1YxaunxQGqRNykSFiEJindxOSAn
        AxK6q/wGqcZ3zvFBTcVVkji1u2QH4rOMP3PPxAIMkB8ONkdHTco1DmbE6BfDHArD
        qUYxqJUlPGlMqrKb3fCFiT3eXehwR7nlzQIDAQABo3sweTAJBgNVHRMEAjAAMCwG
        CWCGSAGG+EIBDQQfFh1PcGVuU1NMIEdlbmVyYXRlZCBDZXJ0aWZpY2F0ZTAdBgNV
        HQ4EFgQUnxR3vz86tso4gkJIFiza0Mteh9gwHwYDVR0jBBgwFoAUe5raj5CZTlLS
        rNuzA1LKh6YNPg0wDQYJKoZIhvcNAQEFBQADggEBALJ27i3okV/KvlDp6KMID3gd
        ITl68PyItzzx+SquF8gahMh016NX73z/oVZoVUNdftla8wPUB1GwIkAnGkhQ9LHK
        spBdbRiCj0gMmLCsX8SrjFvr7cYb2cK6J/fJe92l1tg/7Y4o7V/s4JBe/cy9U9w8
        a0ctuDmEBCgC784JMDtT67klRfr/2LlqWhlOEq7pUFxRLbhpquaAHSOjmIcWnVpw
        9BsO7qe46hidgn39hKh1WjKK2VcL/3YRsC4wUi0PBtFW6ScMCuMhgIRXSPU55Rae
        UIlOdPjjr1SUNWGId1rD7W16Scpwnknn310FNxFMHVI0GTGFkNdkilNCFJcIoRA=
        -----END CERTIFICATE-----
        """.trimIndent(),
      )
    assertThat(verifier.verify("\u82b1\u5b50.co.jp", session)).isFalse()
    assertThat(verifier.verify("a.\u82b1\u5b50.co.jp", session)).isFalse()
  }

  @Test fun verifySubjectAlt() {
    // CN=foo.com, subjectAlt=bar.com
    val session =
      session(
        """
        -----BEGIN CERTIFICATE-----
        MIIEXDCCA0SgAwIBAgIJAIz+EYMBU6aRMA0GCSqGSIb3DQEBBQUAMIGiMQswCQYD
        VQQGEwJDQTELMAkGA1UECBMCQkMxEjAQBgNVBAcTCVZhbmNvdXZlcjEWMBQGA1UE
        ChMNd3d3LmN1Y2JjLmNvbTEUMBIGA1UECxQLY29tbW9uc19zc2wxHTAbBgNVBAMU
        FGRlbW9faW50ZXJtZWRpYXRlX2NhMSUwIwYJKoZIhvcNAQkBFhZqdWxpdXNkYXZp
        ZXNAZ21haWwuY29tMB4XDTA2MTIxMTE1MzYyOVoXDTI4MTEwNTE1MzYyOVowgaQx
        CzAJBgNVBAYTAlVTMREwDwYDVQQIEwhNYXJ5bGFuZDEUMBIGA1UEBxMLRm9yZXN0
        IEhpbGwxFzAVBgNVBAoTDmh0dHBjb21wb25lbnRzMRowGAYDVQQLExF0ZXN0IGNl
        cnRpZmljYXRlczEQMA4GA1UEAxMHZm9vLmNvbTElMCMGCSqGSIb3DQEJARYWanVs
        aXVzZGF2aWVzQGdtYWlsLmNvbTCCASIwDQYJKoZIhvcNAQEBBQADggEPADCCAQoC
        ggEBAMhjr5aCPoyp0R1iroWAfnEyBMGYWoCidH96yGPFjYLowez5aYKY1IOKTY2B
        lYho4O84X244QrZTRl8kQbYtxnGh4gSCD+Z8gjZ/gMvLUlhqOb+WXPAUHMB39GRy
        zerA/ZtrlUqf+lKo0uWcocxeRc771KN8cPH3nHZ0rV0Hx4ZAZy6U4xxObe4rtSVY
        07hNKXAb2odnVqgzcYiDkLV8ilvEmoNWMWrp8UBqkTcpEhYhCYp3cTkgJwMSuqv8
        BqnGd87xQU3FVZI4tbtkB+KzjD9zz8QCDJAfDjZHR03KNQ5mxOgXwxwKw6lGMaiV
        JTxpTKqym93whYk93l3ocEe55c0CAwEAAaOBkDCBjTAJBgNVHRMEAjAAMCwGCWCG
        SAGG+EIBDQQfFh1PcGVuU1NMIEdlbmVyYXRlZCBDZXJ0aWZpY2F0ZTAdBgNVHQ4E
        FgQUnxR3vz86tso4gkJIFiza0Mteh9gwHwYDVR0jBBgwFoAUe5raj5CZTlLSrNuz
        A1LKh6YNPg0wEgYDVR0RBAswCYIHYmFyLmNvbTANBgkqhkiG9w0BAQUFAAOCAQEA
        dQyprNZBmVnvuVWjV42sey/PTfkYShJwy1j0/jcFZR/ypZUovpiHGDO1DgL3Y3IP
        zVQ26uhUsSw6G0gGRiaBDe/0LUclXZoJzXX1qpS55OadxW73brziS0sxRgGrZE/d
        3g5kkio6IED47OP6wYnlmZ7EKP9cqjWwlnvHnnUcZ2SscoLNYs9rN9ccp8tuq2by
        88OyhKwGjJfhOudqfTNZcDzRHx4Fzm7UsVaycVw4uDmhEHJrAsmMPpj/+XRK9/42
        2xq+8bc6HojdtbCyug/fvBZvZqQXSmU8m8IVcMmWMz0ZQO8ee3QkBHMZfCy7P/kr
        VbWx/uETImUu+NZg22ewEw==
        -----END CERTIFICATE-----
        """.trimIndent(),
      )
    assertThat(verifier.verify("foo.com", session)).isFalse()
    assertThat(verifier.verify("a.foo.com", session)).isFalse()
    assertThat(verifier.verify("bar.com", session)).isTrue()
    assertThat(verifier.verify("a.bar.com", session)).isFalse()
  }

  /**
   * Ignored due to incompatibilities between Android and Java on how non-ASCII subject alt names
   * are parsed. Android fails to parse these, which means we fall back to the CN. The RI does parse
   * them, so the CN is unused.
   */
  @Test fun verifyNonAsciiSubjectAlt() {
    // Expecting actual:
    //  ["bar.com", "è±å­.co.jp"]
    // to contain exactly (and in same order):
    //  ["bar.com", "������.co.jp"]
    platform.assumeNotBouncyCastle()

    // CN=foo.com, subjectAlt=bar.com, subjectAlt=&#x82b1;&#x5b50;.co.jp
    // (hanako.co.jp in kanji)
    val session =
      session(
        """
        -----BEGIN CERTIFICATE-----
        MIIEajCCA1KgAwIBAgIJAIz+EYMBU6aSMA0GCSqGSIb3DQEBBQUAMIGiMQswCQYD
        VQQGEwJDQTELMAkGA1UECBMCQkMxEjAQBgNVBAcTCVZhbmNvdXZlcjEWMBQGA1UE
        ChMNd3d3LmN1Y2JjLmNvbTEUMBIGA1UECxQLY29tbW9uc19zc2wxHTAbBgNVBAMU
        FGRlbW9faW50ZXJtZWRpYXRlX2NhMSUwIwYJKoZIhvcNAQkBFhZqdWxpdXNkYXZp
        ZXNAZ21haWwuY29tMB4XDTA2MTIxMTE1MzgxM1oXDTI4MTEwNTE1MzgxM1owgaQx
        CzAJBgNVBAYTAlVTMREwDwYDVQQIEwhNYXJ5bGFuZDEUMBIGA1UEBxMLRm9yZXN0
        IEhpbGwxFzAVBgNVBAoTDmh0dHBjb21wb25lbnRzMRowGAYDVQQLExF0ZXN0IGNl
        cnRpZmljYXRlczEQMA4GA1UEAxMHZm9vLmNvbTElMCMGCSqGSIb3DQEJARYWanVs
        aXVzZGF2aWVzQGdtYWlsLmNvbTCCASIwDQYJKoZIhvcNAQEBBQADggEPADCCAQoC
        ggEBAMhjr5aCPoyp0R1iroWAfnEyBMGYWoCidH96yGPFjYLowez5aYKY1IOKTY2B
        lYho4O84X244QrZTRl8kQbYtxnGh4gSCD+Z8gjZ/gMvLUlhqOb+WXPAUHMB39GRy
        zerA/ZtrlUqf+lKo0uWcocxeRc771KN8cPH3nHZ0rV0Hx4ZAZy6U4xxObe4rtSVY
        07hNKXAb2odnVqgzcYiDkLV8ilvEmoNWMWrp8UBqkTcpEhYhCYp3cTkgJwMSuqv8
        BqnGd87xQU3FVZI4tbtkB+KzjD9zz8QCDJAfDjZHR03KNQ5mxOgXwxwKw6lGMaiV
        JTxpTKqym93whYk93l3ocEe55c0CAwEAAaOBnjCBmzAJBgNVHRMEAjAAMCwGCWCG
        SAGG+EIBDQQfFh1PcGVuU1NMIEdlbmVyYXRlZCBDZXJ0aWZpY2F0ZTAdBgNVHQ4E
        FgQUnxR3vz86tso4gkJIFiza0Mteh9gwHwYDVR0jBBgwFoAUe5raj5CZTlLSrNuz
        A1LKh6YNPg0wIAYDVR0RBBkwF4IHYmFyLmNvbYIM6Iqx5a2QLmNvLmpwMA0GCSqG
        SIb3DQEBBQUAA4IBAQBeZs7ZIYyKtdnVxVvdLgwySEPOE4pBSXii7XYv0Q9QUvG/
        ++gFGQh89HhABzA1mVUjH5dJTQqSLFvRfqTHqLpxSxSWqMHnvRM4cPBkIRp/XlMK
        PlXadYtJLPTgpbgvulA1ickC9EwlNYWnowZ4uxnfsMghW4HskBqaV+PnQ8Zvy3L0
        12c7Cg4mKKS5pb1HdRuiD2opZ+Hc77gRQLvtWNS8jQvd/iTbh6fuvTKfAOFoXw22
        sWIKHYrmhCIRshUNohGXv50m2o+1w9oWmQ6Dkq7lCjfXfUB4wIbggJjpyEtbNqBt
        j4MC2x5rfsLKKqToKmNE7pFEgqwe8//Aar1b+Qj+
        -----END CERTIFICATE-----
        """.trimIndent(),
      )
    val peerCertificate = session.peerCertificates[0] as X509Certificate

    if (isAndroid || platform.isConscrypt()) {
      assertThat(certificateSANs(peerCertificate)).containsExactly("bar.com")
    } else {
      assertThat(certificateSANs(peerCertificate)).containsExactly("bar.com", "������.co.jp")
    }

    assertThat(verifier.verify("foo.com", session)).isFalse()
    assertThat(verifier.verify("a.foo.com", session)).isFalse()
    // these checks test alternative subjects. The test data contains an
    // alternative subject starting with a japanese kanji character. This is
    // not supported by Android because the underlying implementation from
    // harmony follows the definition from rfc 1034 page 10 for alternative
    // subject names. This causes the code to drop all alternative subjects.
    assertThat(verifier.verify("bar.com", session)).isTrue()
    assertThat(verifier.verify("a.bar.com", session)).isFalse()
    assertThat(verifier.verify("a.\u82b1\u5b50.co.jp", session)).isFalse()
  }

  @Test fun verifySubjectAltOnly() {
    // subjectAlt=foo.com
    val session =
      session(
        """
        -----BEGIN CERTIFICATE-----
        MIIESjCCAzKgAwIBAgIJAIz+EYMBU6aYMA0GCSqGSIb3DQEBBQUAMIGiMQswCQYD
        VQQGEwJDQTELMAkGA1UECBMCQkMxEjAQBgNVBAcTCVZhbmNvdXZlcjEWMBQGA1UE
        ChMNd3d3LmN1Y2JjLmNvbTEUMBIGA1UECxQLY29tbW9uc19zc2wxHTAbBgNVBAMU
        FGRlbW9faW50ZXJtZWRpYXRlX2NhMSUwIwYJKoZIhvcNAQkBFhZqdWxpdXNkYXZp
        ZXNAZ21haWwuY29tMB4XDTA2MTIxMTE2MjYxMFoXDTI4MTEwNTE2MjYxMFowgZIx
        CzAJBgNVBAYTAlVTMREwDwYDVQQIDAhNYXJ5bGFuZDEUMBIGA1UEBwwLRm9yZXN0
        IEhpbGwxFzAVBgNVBAoMDmh0dHBjb21wb25lbnRzMRowGAYDVQQLDBF0ZXN0IGNl
        cnRpZmljYXRlczElMCMGCSqGSIb3DQEJARYWanVsaXVzZGF2aWVzQGdtYWlsLmNv
        bTCCASIwDQYJKoZIhvcNAQEBBQADggEPADCCAQoCggEBAMhjr5aCPoyp0R1iroWA
        fnEyBMGYWoCidH96yGPFjYLowez5aYKY1IOKTY2BlYho4O84X244QrZTRl8kQbYt
        xnGh4gSCD+Z8gjZ/gMvLUlhqOb+WXPAUHMB39GRyzerA/ZtrlUqf+lKo0uWcocxe
        Rc771KN8cPH3nHZ0rV0Hx4ZAZy6U4xxObe4rtSVY07hNKXAb2odnVqgzcYiDkLV8
        ilvEmoNWMWrp8UBqkTcpEhYhCYp3cTkgJwMSuqv8BqnGd87xQU3FVZI4tbtkB+Kz
        jD9zz8QCDJAfDjZHR03KNQ5mxOgXwxwKw6lGMaiVJTxpTKqym93whYk93l3ocEe5
        5c0CAwEAAaOBkDCBjTAJBgNVHRMEAjAAMCwGCWCGSAGG+EIBDQQfFh1PcGVuU1NM
        IEdlbmVyYXRlZCBDZXJ0aWZpY2F0ZTAdBgNVHQ4EFgQUnxR3vz86tso4gkJIFiza
        0Mteh9gwHwYDVR0jBBgwFoAUe5raj5CZTlLSrNuzA1LKh6YNPg0wEgYDVR0RBAsw
        CYIHZm9vLmNvbTANBgkqhkiG9w0BAQUFAAOCAQEAjl78oMjzFdsMy6F1sGg/IkO8
        tF5yUgPgFYrs41yzAca7IQu6G9qtFDJz/7ehh/9HoG+oqCCIHPuIOmS7Sd0wnkyJ
        Y7Y04jVXIb3a6f6AgBkEFP1nOT0z6kjT7vkA5LJ2y3MiDcXuRNMSta5PYVnrX8aZ
        yiqVUNi40peuZ2R8mAUSBvWgD7z2qWhF8YgDb7wWaFjg53I36vWKn90ZEti3wNCw
        qAVqixM+J0qJmQStgAc53i2aTMvAQu3A3snvH/PHTBo+5UL72n9S1kZyNCsVf1Qo
        n8jKTiRriEM+fMFlcgQP284EBFzYHyCXFb9O/hMjK2+6mY9euMB1U1aFFzM/Bg==
        -----END CERTIFICATE-----
        """.trimIndent(),
      )
    assertThat(verifier.verify("foo.com", session)).isTrue()
    assertThat(verifier.verify("a.foo.com", session)).isFalse()
    assertThat(verifier.verify("foo.com", session)).isTrue()
    assertThat(verifier.verify("a.foo.com", session)).isFalse()
  }

  @Test fun verifyMultipleCn() {
    // CN=foo.com, CN=bar.com, CN=&#x82b1;&#x5b50;.co.jp
    val session =
      session(
        """
        -----BEGIN CERTIFICATE-----
        MIIEbzCCA1egAwIBAgIJAIz+EYMBU6aXMA0GCSqGSIb3DQEBBQUAMIGiMQswCQYD
        VQQGEwJDQTELMAkGA1UECBMCQkMxEjAQBgNVBAcTCVZhbmNvdXZlcjEWMBQGA1UE
        ChMNd3d3LmN1Y2JjLmNvbTEUMBIGA1UECxQLY29tbW9uc19zc2wxHTAbBgNVBAMU
        FGRlbW9faW50ZXJtZWRpYXRlX2NhMSUwIwYJKoZIhvcNAQkBFhZqdWxpdXNkYXZp
        ZXNAZ21haWwuY29tMB4XDTA2MTIxMTE2MTk0NVoXDTI4MTEwNTE2MTk0NVowgc0x
        CzAJBgNVBAYTAlVTMREwDwYDVQQIDAhNYXJ5bGFuZDEUMBIGA1UEBwwLRm9yZXN0
        IEhpbGwxFzAVBgNVBAoMDmh0dHBjb21wb25lbnRzMRowGAYDVQQLDBF0ZXN0IGNl
        cnRpZmljYXRlczEQMA4GA1UEAwwHZm9vLmNvbTEQMA4GA1UEAwwHYmFyLmNvbTEV
        MBMGA1UEAwwM6Iqx5a2QLmNvLmpwMSUwIwYJKoZIhvcNAQkBFhZqdWxpdXNkYXZp
        ZXNAZ21haWwuY29tMIIBIjANBgkqhkiG9w0BAQEFAAOCAQ8AMIIBCgKCAQEAyGOv
        loI+jKnRHWKuhYB+cTIEwZhagKJ0f3rIY8WNgujB7PlpgpjUg4pNjYGViGjg7zhf
        bjhCtlNGXyRBti3GcaHiBIIP5nyCNn+Ay8tSWGo5v5Zc8BQcwHf0ZHLN6sD9m2uV
        Sp/6UqjS5ZyhzF5FzvvUo3xw8fecdnStXQfHhkBnLpTjHE5t7iu1JVjTuE0pcBva
        h2dWqDNxiIOQtXyKW8Sag1YxaunxQGqRNykSFiEJindxOSAnAxK6q/wGqcZ3zvFB
        TcVVkji1u2QH4rOMP3PPxAIMkB8ONkdHTco1DmbE6BfDHArDqUYxqJUlPGlMqrKb
        3fCFiT3eXehwR7nlzQIDAQABo3sweTAJBgNVHRMEAjAAMCwGCWCGSAGG+EIBDQQf
        Fh1PcGVuU1NMIEdlbmVyYXRlZCBDZXJ0aWZpY2F0ZTAdBgNVHQ4EFgQUnxR3vz86
        tso4gkJIFiza0Mteh9gwHwYDVR0jBBgwFoAUe5raj5CZTlLSrNuzA1LKh6YNPg0w
        DQYJKoZIhvcNAQEFBQADggEBAGuZb8ai1NO2j4v3y9TLZvd5s0vh5/TE7n7RX+8U
        y37OL5k7x9nt0mM1TyAKxlCcY+9h6frue8MemZIILSIvMrtzccqNz0V1WKgA+Orf
        uUrabmn+CxHF5gpy6g1Qs2IjVYWA5f7FROn/J+Ad8gJYc1azOWCLQqSyfpNRLSvY
        EriQFEV63XvkJ8JrG62b+2OT2lqT4OO07gSPetppdlSa8NBSKP6Aro9RIX1ZjUZQ
        SpQFCfo02NO0uNRDPUdJx2huycdNb+AXHaO7eXevDLJ+QnqImIzxWiY6zLOdzjjI
        VBMkLHmnP7SjGSQ3XA4ByrQOxfOUTyLyE7NuemhHppuQPxE=
        -----END CERTIFICATE-----
        """.trimIndent(),
      )
    assertThat(verifier.verify("foo.com", session)).isFalse()
    assertThat(verifier.verify("a.foo.com", session)).isFalse()
    assertThat(verifier.verify("bar.com", session)).isFalse()
    assertThat(verifier.verify("a.bar.com", session)).isFalse()
    assertThat(verifier.verify("\u82b1\u5b50.co.jp", session)).isFalse()
    assertThat(verifier.verify("a.\u82b1\u5b50.co.jp", session)).isFalse()
  }

  @Test fun verifyWilcardCn() {
    // CN=*.foo.com
    val session =
      session(
        """
        -----BEGIN CERTIFICATE-----
        MIIESDCCAzCgAwIBAgIJAIz+EYMBU6aUMA0GCSqGSIb3DQEBBQUAMIGiMQswCQYD
        VQQGEwJDQTELMAkGA1UECBMCQkMxEjAQBgNVBAcTCVZhbmNvdXZlcjEWMBQGA1UE
        ChMNd3d3LmN1Y2JjLmNvbTEUMBIGA1UECxQLY29tbW9uc19zc2wxHTAbBgNVBAMU
        FGRlbW9faW50ZXJtZWRpYXRlX2NhMSUwIwYJKoZIhvcNAQkBFhZqdWxpdXNkYXZp
        ZXNAZ21haWwuY29tMB4XDTA2MTIxMTE2MTU1NVoXDTI4MTEwNTE2MTU1NVowgaYx
        CzAJBgNVBAYTAlVTMREwDwYDVQQIEwhNYXJ5bGFuZDEUMBIGA1UEBxMLRm9yZXN0
        IEhpbGwxFzAVBgNVBAoTDmh0dHBjb21wb25lbnRzMRowGAYDVQQLExF0ZXN0IGNl
        cnRpZmljYXRlczESMBAGA1UEAxQJKi5mb28uY29tMSUwIwYJKoZIhvcNAQkBFhZq
        dWxpdXNkYXZpZXNAZ21haWwuY29tMIIBIjANBgkqhkiG9w0BAQEFAAOCAQ8AMIIB
        CgKCAQEAyGOvloI+jKnRHWKuhYB+cTIEwZhagKJ0f3rIY8WNgujB7PlpgpjUg4pN
        jYGViGjg7zhfbjhCtlNGXyRBti3GcaHiBIIP5nyCNn+Ay8tSWGo5v5Zc8BQcwHf0
        ZHLN6sD9m2uVSp/6UqjS5ZyhzF5FzvvUo3xw8fecdnStXQfHhkBnLpTjHE5t7iu1
        JVjTuE0pcBvah2dWqDNxiIOQtXyKW8Sag1YxaunxQGqRNykSFiEJindxOSAnAxK6
        q/wGqcZ3zvFBTcVVkji1u2QH4rOMP3PPxAIMkB8ONkdHTco1DmbE6BfDHArDqUYx
        qJUlPGlMqrKb3fCFiT3eXehwR7nlzQIDAQABo3sweTAJBgNVHRMEAjAAMCwGCWCG
        SAGG+EIBDQQfFh1PcGVuU1NMIEdlbmVyYXRlZCBDZXJ0aWZpY2F0ZTAdBgNVHQ4E
        FgQUnxR3vz86tso4gkJIFiza0Mteh9gwHwYDVR0jBBgwFoAUe5raj5CZTlLSrNuz
        A1LKh6YNPg0wDQYJKoZIhvcNAQEFBQADggEBAH0ipG6J561UKUfgkeW7GvYwW98B
        N1ZooWX+JEEZK7+Pf/96d3Ij0rw9ACfN4bpfnCq0VUNZVSYB+GthQ2zYuz7tf/UY
        A6nxVgR/IjG69BmsBl92uFO7JTNtHztuiPqBn59pt+vNx4yPvno7zmxsfI7jv0ww
        yfs+0FNm7FwdsC1k47GBSOaGw38kuIVWqXSAbL4EX9GkryGGOKGNh0qvAENCdRSB
        G9Z6tyMbmfRY+dLSh3a9JwoEcBUso6EWYBakLbq4nG/nvYdYvG9ehrnLVwZFL82e
        l3Q/RK95bnA6cuRClGusLad0e6bjkBzx/VQ3VarDEpAkTLUGVAa0CLXtnyc=
        -----END CERTIFICATE-----
        """.trimIndent(),
      )
    assertThat(verifier.verify("foo.com", session)).isFalse()
    assertThat(verifier.verify("www.foo.com", session)).isFalse()
    assertThat(verifier.verify("\u82b1\u5b50.foo.com", session)).isFalse()
    assertThat(verifier.verify("a.b.foo.com", session)).isFalse()
  }

  @Test fun verifyWilcardCnOnTld() {
    // It's the CA's responsibility to not issue broad-matching certificates!
    // CN=*.co.jp
    val session =
      session(
        """
        -----BEGIN CERTIFICATE-----
        MIIERjCCAy6gAwIBAgIJAIz+EYMBU6aVMA0GCSqGSIb3DQEBBQUAMIGiMQswCQYD
        VQQGEwJDQTELMAkGA1UECBMCQkMxEjAQBgNVBAcTCVZhbmNvdXZlcjEWMBQGA1UE
        ChMNd3d3LmN1Y2JjLmNvbTEUMBIGA1UECxQLY29tbW9uc19zc2wxHTAbBgNVBAMU
        FGRlbW9faW50ZXJtZWRpYXRlX2NhMSUwIwYJKoZIhvcNAQkBFhZqdWxpdXNkYXZp
        ZXNAZ21haWwuY29tMB4XDTA2MTIxMTE2MTYzMFoXDTI4MTEwNTE2MTYzMFowgaQx
        CzAJBgNVBAYTAlVTMREwDwYDVQQIEwhNYXJ5bGFuZDEUMBIGA1UEBxMLRm9yZXN0
        IEhpbGwxFzAVBgNVBAoTDmh0dHBjb21wb25lbnRzMRowGAYDVQQLExF0ZXN0IGNl
        cnRpZmljYXRlczEQMA4GA1UEAxQHKi5jby5qcDElMCMGCSqGSIb3DQEJARYWanVs
        aXVzZGF2aWVzQGdtYWlsLmNvbTCCASIwDQYJKoZIhvcNAQEBBQADggEPADCCAQoC
        ggEBAMhjr5aCPoyp0R1iroWAfnEyBMGYWoCidH96yGPFjYLowez5aYKY1IOKTY2B
        lYho4O84X244QrZTRl8kQbYtxnGh4gSCD+Z8gjZ/gMvLUlhqOb+WXPAUHMB39GRy
        zerA/ZtrlUqf+lKo0uWcocxeRc771KN8cPH3nHZ0rV0Hx4ZAZy6U4xxObe4rtSVY
        07hNKXAb2odnVqgzcYiDkLV8ilvEmoNWMWrp8UBqkTcpEhYhCYp3cTkgJwMSuqv8
        BqnGd87xQU3FVZI4tbtkB+KzjD9zz8QCDJAfDjZHR03KNQ5mxOgXwxwKw6lGMaiV
        JTxpTKqym93whYk93l3ocEe55c0CAwEAAaN7MHkwCQYDVR0TBAIwADAsBglghkgB
        hvhCAQ0EHxYdT3BlblNTTCBHZW5lcmF0ZWQgQ2VydGlmaWNhdGUwHQYDVR0OBBYE
        FJ8Ud78/OrbKOIJCSBYs2tDLXofYMB8GA1UdIwQYMBaAFHua2o+QmU5S0qzbswNS
        yoemDT4NMA0GCSqGSIb3DQEBBQUAA4IBAQA0sWglVlMx2zNGvUqFC73XtREwii53
        CfMM6mtf2+f3k/d8KXhLNySrg8RRlN11zgmpPaLtbdTLrmG4UdAHHYr8O4y2BBmE
        1cxNfGxxechgF8HX10QV4dkyzp6Z1cfwvCeMrT5G/V1pejago0ayXx+GPLbWlNeZ
        S+Kl0m3p+QplXujtwG5fYcIpaGpiYraBLx3Tadih39QN65CnAh/zRDhLCUzKyt9l
        UGPLEUDzRHMPHLnSqT1n5UU5UDRytbjJPXzF+l/+WZIsanefWLsxnkgAuZe/oMMF
        EJMryEzOjg4Tfuc5qM0EXoPcQ/JlheaxZ40p2IyHqbsWV4MRYuFH4bkM
        -----END CERTIFICATE-----
        """.trimIndent(),
      )
    assertThat(verifier.verify("foo.co.jp", session)).isFalse()
    assertThat(verifier.verify("\u82b1\u5b50.co.jp", session)).isFalse()
  }

  /**
   * Previously ignored due to incompatibilities between Android and Java on how non-ASCII subject
   * alt names are parsed. Android fails to parse these, which means we fall back to the CN.
   * The RI does parse them, so the CN is unused.
   */
  @Test fun testWilcardNonAsciiSubjectAlt() {
    // Expecting actual:
    //  ["*.bar.com", "*.è±å­.co.jp"]
    // to contain exactly (and in same order):
    //  ["*.bar.com", "*.������.co.jp"]
    platform.assumeNotBouncyCastle()

    // CN=*.foo.com, subjectAlt=*.bar.com, subjectAlt=*.&#x82b1;&#x5b50;.co.jp
    // (*.hanako.co.jp in kanji)
    val session =
      session(
        """
        -----BEGIN CERTIFICATE-----
        MIIEcDCCA1igAwIBAgIJAIz+EYMBU6aWMA0GCSqGSIb3DQEBBQUAMIGiMQswCQYD
        VQQGEwJDQTELMAkGA1UECBMCQkMxEjAQBgNVBAcTCVZhbmNvdXZlcjEWMBQGA1UE
        ChMNd3d3LmN1Y2JjLmNvbTEUMBIGA1UECxQLY29tbW9uc19zc2wxHTAbBgNVBAMU
        FGRlbW9faW50ZXJtZWRpYXRlX2NhMSUwIwYJKoZIhvcNAQkBFhZqdWxpdXNkYXZp
        ZXNAZ21haWwuY29tMB4XDTA2MTIxMTE2MTczMVoXDTI4MTEwNTE2MTczMVowgaYx
        CzAJBgNVBAYTAlVTMREwDwYDVQQIEwhNYXJ5bGFuZDEUMBIGA1UEBxMLRm9yZXN0
        IEhpbGwxFzAVBgNVBAoTDmh0dHBjb21wb25lbnRzMRowGAYDVQQLExF0ZXN0IGNl
        cnRpZmljYXRlczESMBAGA1UEAxQJKi5mb28uY29tMSUwIwYJKoZIhvcNAQkBFhZq
        dWxpdXNkYXZpZXNAZ21haWwuY29tMIIBIjANBgkqhkiG9w0BAQEFAAOCAQ8AMIIB
        CgKCAQEAyGOvloI+jKnRHWKuhYB+cTIEwZhagKJ0f3rIY8WNgujB7PlpgpjUg4pN
        jYGViGjg7zhfbjhCtlNGXyRBti3GcaHiBIIP5nyCNn+Ay8tSWGo5v5Zc8BQcwHf0
        ZHLN6sD9m2uVSp/6UqjS5ZyhzF5FzvvUo3xw8fecdnStXQfHhkBnLpTjHE5t7iu1
        JVjTuE0pcBvah2dWqDNxiIOQtXyKW8Sag1YxaunxQGqRNykSFiEJindxOSAnAxK6
        q/wGqcZ3zvFBTcVVkji1u2QH4rOMP3PPxAIMkB8ONkdHTco1DmbE6BfDHArDqUYx
        qJUlPGlMqrKb3fCFiT3eXehwR7nlzQIDAQABo4GiMIGfMAkGA1UdEwQCMAAwLAYJ
        YIZIAYb4QgENBB8WHU9wZW5TU0wgR2VuZXJhdGVkIENlcnRpZmljYXRlMB0GA1Ud
        DgQWBBSfFHe/Pzq2yjiCQkgWLNrQy16H2DAfBgNVHSMEGDAWgBR7mtqPkJlOUtKs
        27MDUsqHpg0+DTAkBgNVHREEHTAbggkqLmJhci5jb22CDiou6Iqx5a2QLmNvLmpw
        MA0GCSqGSIb3DQEBBQUAA4IBAQBobWC+D5/lx6YhX64CwZ26XLjxaE0S415ajbBq
        DK7lz+Rg7zOE3GsTAMi+ldUYnhyz0wDiXB8UwKXl0SDToB2Z4GOgqQjAqoMmrP0u
        WB6Y6dpkfd1qDRUzI120zPYgSdsXjHW9q2H77iV238hqIU7qCvEz+lfqqWEY504z
        hYNlknbUnR525ItosEVwXFBJTkZ3Yw8gg02c19yi8TAh5Li3Ad8XQmmSJMWBV4XK
        qFr0AIZKBlg6NZZFf/0dP9zcKhzSriW27bY0XfzA6GSiRDXrDjgXq6baRT6YwgIg
        pgJsDbJtZfHnV1nd3M6zOtQPm1TIQpNmMMMd/DPrGcUQerD3
        -----END CERTIFICATE-----
        """.trimIndent(),
      )
    val peerCertificate = session.peerCertificates[0] as X509Certificate
    if (isAndroid || platform.isConscrypt()) {
      assertThat(certificateSANs(peerCertificate)).containsExactly("*.bar.com")
    } else {
      assertThat(certificateSANs(peerCertificate))
        .containsExactly("*.bar.com", "*.������.co.jp")
    }

    // try the foo.com variations
    assertThat(verifier.verify("foo.com", session)).isFalse()
    assertThat(verifier.verify("www.foo.com", session)).isFalse()
    assertThat(verifier.verify("\u82b1\u5b50.foo.com", session)).isFalse()
    assertThat(verifier.verify("a.b.foo.com", session)).isFalse()
    // these checks test alternative subjects. The test data contains an
    // alternative subject starting with a japanese kanji character. This is
    // not supported by Android because the underlying implementation from
    // harmony follows the definition from rfc 1034 page 10 for alternative
    // subject names. This causes the code to drop all alternative subjects.
    assertThat(verifier.verify("bar.com", session)).isFalse()
    assertThat(verifier.verify("www.bar.com", session)).isTrue()
    assertThat(verifier.verify("\u82b1\u5b50.bar.com", session)).isFalse()
    assertThat(verifier.verify("a.b.bar.com", session)).isFalse()
  }

  @Test fun subjectAltUsesLocalDomainAndIp() {
    // cat cert.cnf
    // [req]
    // distinguished_name=distinguished_name
    // req_extensions=req_extensions
    // x509_extensions=x509_extensions
    // [distinguished_name]
    // [req_extensions]
    // [x509_extensions]
    // subjectAltName=DNS:localhost.localdomain,DNS:localhost,IP:127.0.0.1
    //
    // $ openssl req -x509 -nodes -days 36500 -subj '/CN=localhost' -config ./cert.cnf \
    //     -newkey rsa:512 -out cert.pem
    val certificate =
      certificate(
        """
        -----BEGIN CERTIFICATE-----
        MIIBWDCCAQKgAwIBAgIJANS1EtICX2AZMA0GCSqGSIb3DQEBBQUAMBQxEjAQBgNV
        BAMTCWxvY2FsaG9zdDAgFw0xMjAxMDIxOTA4NThaGA8yMTExMTIwOTE5MDg1OFow
        FDESMBAGA1UEAxMJbG9jYWxob3N0MFwwDQYJKoZIhvcNAQEBBQADSwAwSAJBAPpt
        atK8r4/hf4hSIs0os/BSlQLbRBaK9AfBReM4QdAklcQqe6CHsStKfI8pp0zs7Ptg
        PmMdpbttL0O7mUboBC8CAwEAAaM1MDMwMQYDVR0RBCowKIIVbG9jYWxob3N0Lmxv
        Y2FsZG9tYWlugglsb2NhbGhvc3SHBH8AAAEwDQYJKoZIhvcNAQEFBQADQQD0ntfL
        DCzOCv9Ma6Lv5o5jcYWVxvBSTsnt22hsJpWD1K7iY9lbkLwl0ivn73pG2evsAn9G
        X8YKH52fnHsCrhSD
        -----END CERTIFICATE-----
        """.trimIndent(),
      )
    assertThat(certificate.subjectX500Principal).isEqualTo(
      X500Principal("CN=localhost"),
    )
    val session = FakeSSLSession(certificate)
    assertThat(verifier.verify("localhost", session)).isTrue()
    assertThat(verifier.verify("localhost.localdomain", session)).isTrue()
    assertThat(verifier.verify("local.host", session)).isFalse()
    assertThat(verifier.verify("127.0.0.1", session)).isTrue()
    assertThat(verifier.verify("127.0.0.2", session)).isFalse()
  }

  @Test fun wildcardsCannotMatchIpAddresses() {
    // openssl req -x509 -nodes -days 36500 -subj '/CN=*.0.0.1' -newkey rsa:512 -out cert.pem
    val session =
      session(
        """
        -----BEGIN CERTIFICATE-----
        MIIBkjCCATygAwIBAgIJAMdemqOwd/BEMA0GCSqGSIb3DQEBBQUAMBIxEDAOBgNV
        BAMUByouMC4wLjEwIBcNMTAxMjIwMTY0NDI1WhgPMjExMDExMjYxNjQ0MjVaMBIx
        EDAOBgNVBAMUByouMC4wLjEwXDANBgkqhkiG9w0BAQEFAANLADBIAkEAqY8c9Qrt
        YPWCvb7lclI+aDHM6fgbJcHsS9Zg8nUOh5dWrS7AgeA25wyaokFl4plBbbHQe2j+
        cCjsRiJIcQo9HwIDAQABo3MwcTAdBgNVHQ4EFgQUJ436TZPJvwCBKklZZqIvt1Yt
        JjEwQgYDVR0jBDswOYAUJ436TZPJvwCBKklZZqIvt1YtJjGhFqQUMBIxEDAOBgNV
        BAMUByouMC4wLjGCCQDHXpqjsHfwRDAMBgNVHRMEBTADAQH/MA0GCSqGSIb3DQEB
        BQUAA0EAk9i88xdjWoewqvE+iMC9tD2obMchgFDaHH0ogxxiRaIKeEly3g0uGxIt
        fl2WRY8hb4x+zRrwsFaLEpdEvqcjOQ==
        -----END CERTIFICATE-----
        """.trimIndent(),
      )
    assertThat(verifier.verify("127.0.0.1", session)).isFalse()
  }

  /**
   * Earlier implementations of Android's hostname verifier required that wildcard names wouldn't
   * match "*.com" or similar. This was a nonstandard check that we've since dropped. It is the CA's
   * responsibility to not hand out certificates that match so broadly.
   */
  @Test fun wildcardsDoesNotNeedTwoDots() {
    // openssl req -x509 -nodes -days 36500 -subj '/CN=*.com' -newkey rsa:512 -out cert.pem
    val session =
      session(
        """
        -----BEGIN CERTIFICATE-----
        MIIBjDCCATagAwIBAgIJAOVulXCSu6HuMA0GCSqGSIb3DQEBBQUAMBAxDjAMBgNV
        BAMUBSouY29tMCAXDTEwMTIyMDE2NDkzOFoYDzIxMTAxMTI2MTY0OTM4WjAQMQ4w
        DAYDVQQDFAUqLmNvbTBcMA0GCSqGSIb3DQEBAQUAA0sAMEgCQQDJd8xqni+h7Iaz
        ypItivs9kPuiJUqVz+SuJ1C05SFc3PmlRCvwSIfhyD67fHcbMdl+A/LrIjhhKZJe
        1joO0+pFAgMBAAGjcTBvMB0GA1UdDgQWBBS4Iuzf5w8JdCp+EtBfdFNudf6+YzBA
        BgNVHSMEOTA3gBS4Iuzf5w8JdCp+EtBfdFNudf6+Y6EUpBIwEDEOMAwGA1UEAxQF
        Ki5jb22CCQDlbpVwkruh7jAMBgNVHRMEBTADAQH/MA0GCSqGSIb3DQEBBQUAA0EA
        U6LFxmZr31lFyis2/T68PpjAppc0DpNQuA2m/Y7oTHBDi55Fw6HVHCw3lucuWZ5d
        qUYo4ES548JdpQtcLrW2sA==
        -----END CERTIFICATE-----
        """.trimIndent(),
      )
    assertThat(verifier.verify("google.com", session)).isFalse()
  }

  @Test fun subjectAltName() {
    // $ cat ./cert.cnf
    // [req]
    // distinguished_name=distinguished_name
    // req_extensions=req_extensions
    // x509_extensions=x509_extensions
    // [distinguished_name]
    // [req_extensions]
    // [x509_extensions]
    // subjectAltName=DNS:bar.com,DNS:baz.com
    //
    // $ openssl req -x509 -nodes -days 36500 -subj '/CN=foo.com' -config ./cert.cnf \
    //     -newkey rsa:512 -out cert.pem
    val session =
      session(
        """
        -----BEGIN CERTIFICATE-----
        MIIBPTCB6KADAgECAgkA7zoHaaqNGHQwDQYJKoZIhvcNAQEFBQAwEjEQMA4GA1UE
        AxMHZm9vLmNvbTAgFw0xMDEyMjAxODM5MzZaGA8yMTEwMTEyNjE4MzkzNlowEjEQ
        MA4GA1UEAxMHZm9vLmNvbTBcMA0GCSqGSIb3DQEBAQUAA0sAMEgCQQC+gmoSxF+8
        hbV+rgRQqHIJd50216OWQJbU3BvdlPbca779NYO4+UZWTFdBM8BdQqs3H4B5Agvp
        y7HeSff1F7XRAgMBAAGjHzAdMBsGA1UdEQQUMBKCB2Jhci5jb22CB2Jhei5jb20w
        DQYJKoZIhvcNAQEFBQADQQBXpZZPOY2Dy1lGG81JTr8L4or9jpKacD7n51eS8iqI
        oTznPNuXHU5bFN0AAGX2ij47f/EahqTpo5RdS95P4sVm
        -----END CERTIFICATE-----
        """.trimIndent(),
      )
    assertThat(verifier.verify("foo.com", session)).isFalse()
    assertThat(verifier.verify("bar.com", session)).isTrue()
    assertThat(verifier.verify("baz.com", session)).isTrue()
    assertThat(verifier.verify("a.foo.com", session)).isFalse()
    assertThat(verifier.verify("quux.com", session)).isFalse()
  }

  @Test fun subjectAltNameWithWildcard() {
    // $ cat ./cert.cnf
    // [req]
    // distinguished_name=distinguished_name
    // req_extensions=req_extensions
    // x509_extensions=x509_extensions
    // [distinguished_name]
    // [req_extensions]
    // [x509_extensions]
    // subjectAltName=DNS:bar.com,DNS:*.baz.com
    //
    // $ openssl req -x509 -nodes -days 36500 -subj '/CN=foo.com' -config ./cert.cnf \
    //     -newkey rsa:512 -out cert.pem
    val session =
      session(
        """
        -----BEGIN CERTIFICATE-----
        MIIBPzCB6qADAgECAgkAnv/7Jv5r7pMwDQYJKoZIhvcNAQEFBQAwEjEQMA4GA1UE
        AxMHZm9vLmNvbTAgFw0xMDEyMjAxODQ2MDFaGA8yMTEwMTEyNjE4NDYwMVowEjEQ
        MA4GA1UEAxMHZm9vLmNvbTBcMA0GCSqGSIb3DQEBAQUAA0sAMEgCQQDAz2YXnyog
        YdYLSFr/OEgSumtwqtZKJTB4wqTW/eKbBCEzxnyUMxWZIqUGu353PzwfOuWp2re3
        nvVV+QDYQlh9AgMBAAGjITAfMB0GA1UdEQQWMBSCB2Jhci5jb22CCSouYmF6LmNv
        bTANBgkqhkiG9w0BAQUFAANBAB8yrSl8zqy07i0SNYx2B/FnvQY734pxioaqFWfO
        Bqo1ZZl/9aPHEWIwBrxYNVB0SGu/kkbt/vxqOjzzrkXukmI=
        -----END CERTIFICATE-----
        """.trimIndent(),
      )
    assertThat(verifier.verify("foo.com", session)).isFalse()
    assertThat(verifier.verify("bar.com", session)).isTrue()
    assertThat(verifier.verify("a.baz.com", session)).isTrue()
    assertThat(verifier.verify("baz.com", session)).isFalse()
    assertThat(verifier.verify("a.foo.com", session)).isFalse()
    assertThat(verifier.verify("a.bar.com", session)).isFalse()
    assertThat(verifier.verify("quux.com", session)).isFalse()
  }

  @Test fun subjectAltNameWithIPAddresses() {
    // $ cat ./cert.cnf
    // [req]
    // distinguished_name=distinguished_name
    // req_extensions=req_extensions
    // x509_extensions=x509_extensions
    // [distinguished_name]
    // [req_extensions]
    // [x509_extensions]
    // subjectAltName=IP:0:0:0:0:0:0:0:1,IP:2a03:2880:f003:c07:face:b00c::2,IP:0::5,IP:192.168.1.1
    //
    // $ openssl req -x509 -nodes -days 36500 -subj '/CN=foo.com' -config ./cert.cnf \
    //     -newkey rsa:512 -out cert.pem
    val session =
      session(
        """
        -----BEGIN CERTIFICATE-----
        MIIBaDCCARKgAwIBAgIJALxN+AOBVGwQMA0GCSqGSIb3DQEBCwUAMBIxEDAOBgNV
        BAMMB2Zvby5jb20wIBcNMjAwMzIyMTEwNDI4WhgPMjEyMDAyMjcxMTA0MjhaMBIx
        EDAOBgNVBAMMB2Zvby5jb20wXDANBgkqhkiG9w0BAQEFAANLADBIAkEAlnVbVfQ9
        4aYjrPCcFuxOpjXuvyOc9Hcha4K7TfXyfsrjhAvCjCBIT/TiLOUVF3sx4yoCAtX8
        wmt404tTbKD6UwIDAQABo0kwRzBFBgNVHREEPjA8hxAAAAAAAAAAAAAAAAAAAAAB
        hxAqAyiA8AMMB/rOsAwAAAAChxAAAAAAAAAAAAAAAAAAAAAFhwTAqAEBMA0GCSqG
        SIb3DQEBCwUAA0EAPSOYHJh7hB4ElBqTCAFW+T5Y7mXsv9nQjBJ7w0YIw83V2PEI
        3KbBIyGTrqHD6lG8QGZy+yNkIcRlodG8OfQRUg==
        -----END CERTIFICATE-----
        """.trimIndent(),
      )
    assertThat(verifier.verify("foo.com", session)).isFalse()
    assertThat(verifier.verify("::1", session)).isTrue()
    assertThat(verifier.verify("::2", session)).isFalse()
    assertThat(verifier.verify("::5", session)).isTrue()
    assertThat(verifier.verify("2a03:2880:f003:c07:face:b00c::2", session)).isTrue()
    assertThat(verifier.verify("2a03:2880:f003:c07:face:b00c:0:2", session)).isTrue()
    assertThat(verifier.verify("2a03:2880:f003:c07:FACE:B00C:0:2", session)).isTrue()
    assertThat(verifier.verify("2a03:2880:f003:c07:face:b00c:0:3", session)).isFalse()
    assertThat(verifier.verify("127.0.0.1", session)).isFalse()
    assertThat(verifier.verify("192.168.1.1", session)).isTrue()
    assertThat(verifier.verify("::ffff:192.168.1.1", session)).isTrue()
    assertThat(verifier.verify("0:0:0:0:0:FFFF:C0A8:0101", session)).isTrue()
  }

  @Test fun generatedCertificate() {
    val heldCertificate =
      HeldCertificate
        .Builder()
        .commonName("Foo Corp")
        .addSubjectAlternativeName("foo.com")
        .build()
    val session = session(heldCertificate.certificatePem())
    assertThat(verifier.verify("foo.com", session)).isTrue()
    assertThat(verifier.verify("bar.com", session)).isFalse()
  }

  @Test fun specialKInHostname() {
    // https://github.com/apache/httpcomponents-client/commit/303e435d7949652ea77a6c50df1c548682476b6e
    // https://www.gosecure.net/blog/2020/10/27/weakness-in-java-tls-host-verification/
    val heldCertificate =
      HeldCertificate
        .Builder()
        .commonName("Foo Corp")
        .addSubjectAlternativeName("k.com")
        .addSubjectAlternativeName("tel.com")
        .build()
    val session = session(heldCertificate.certificatePem())
    assertThat(verifier.verify("foo.com", session)).isFalse()
    assertThat(verifier.verify("bar.com", session)).isFalse()
    assertThat(verifier.verify("k.com", session)).isTrue()
    assertThat(verifier.verify("K.com", session)).isTrue()
    assertThat(verifier.verify("\u2121.com", session)).isFalse()
    assertThat(verifier.verify("℡.com", session)).isFalse()

    // These should ideally be false, but we know that hostname is usually already checked by us
    assertThat(verifier.verify("\u212A.com", session)).isFalse()
    // Kelvin character below
    assertThat(verifier.verify("K.com", session)).isFalse()
  }

  @Test fun specialKInCert() {
    // https://github.com/apache/httpcomponents-client/commit/303e435d7949652ea77a6c50df1c548682476b6e
    // https://www.gosecure.net/blog/2020/10/27/weakness-in-java-tls-host-verification/
    val heldCertificate =
      HeldCertificate
        .Builder()
        .commonName("Foo Corp")
        .addSubjectAlternativeName("\u2121.com")
        .addSubjectAlternativeName("\u212A.com")
        .build()
    val session = session(heldCertificate.certificatePem())
    assertThat(verifier.verify("foo.com", session)).isFalse()
    assertThat(verifier.verify("bar.com", session)).isFalse()
    assertThat(verifier.verify("k.com", session)).isFalse()
    assertThat(verifier.verify("K.com", session)).isFalse()
    assertThat(verifier.verify("tel.com", session)).isFalse()
    assertThat(verifier.verify("k.com", session)).isFalse()
  }

  @Test fun specialKInExternalCert() {
    // OpenJDK related test.
    platform.assumeNotConscrypt()

    // Expecting actual:
    //  ["â¡.com", "âª.com"]
    // to contain exactly (and in same order):
    //  ["���.com", "���.com"]
    platform.assumeNotBouncyCastle()

    // $ cat ./cert.cnf
    // [req]
    // distinguished_name=distinguished_name
    // req_extensions=req_extensions
    // x509_extensions=x509_extensions
    // [distinguished_name]
    // [req_extensions]
    // [x509_extensions]
    // subjectAltName=DNS:℡.com,DNS:K.com
    //
    // $ openssl req -x509 -nodes -days 36500 -subj '/CN=foo.com' -config ./cert.cnf \
    //     -newkey rsa:512 -out cert.pem
    val session =
      session(
        """
        -----BEGIN CERTIFICATE-----
        MIIBSDCB86ADAgECAhRLR4TGgXBegg0np90FZ1KPeWpDtjANBgkqhkiG9w0BAQsF
        ADASMRAwDgYDVQQDDAdmb28uY29tMCAXDTIwMTAyOTA2NTkwNVoYDzIxMjAxMDA1
        MDY1OTA1WjASMRAwDgYDVQQDDAdmb28uY29tMFwwDQYJKoZIhvcNAQEBBQADSwAw
        SAJBALQcTVW9aW++ClIV9/9iSzijsPvQGEu/FQOjIycSrSIheZyZmR8bluSNBq0C
        9fpalRKZb0S2tlCTi5WoX8d3K30CAwEAAaMfMB0wGwYDVR0RBBQwEoIH4oShLmNv
        bYIH4oSqLmNvbTANBgkqhkiG9w0BAQsFAANBAA1+/eDvSUGv78iEjNW+1w3OPAwt
        Ij1qLQ/YI8OogZPMk7YY46/ydWWp7UpD47zy/vKmm4pOc8Glc8MoDD6UADs=
        -----END CERTIFICATE-----
        """.trimIndent(),
      )
    val peerCertificate = session.peerCertificates[0] as X509Certificate
    if (isAndroid) {
      assertThat(certificateSANs(peerCertificate)).containsExactly()
    } else {
      assertThat(certificateSANs(peerCertificate)).containsExactly("���.com", "���.com")
    }
    assertThat(verifier.verify("tel.com", session)).isFalse()
    assertThat(verifier.verify("k.com", session)).isFalse()
    assertThat(verifier.verify("foo.com", session)).isFalse()
    assertThat(verifier.verify("bar.com", session)).isFalse()
    assertThat(verifier.verify("k.com", session)).isFalse()
    assertThat(verifier.verify("K.com", session)).isFalse()
  }

  private fun certificateSANs(peerCertificate: X509Certificate): List<String> =
    when (val subjectAlternativeNames = peerCertificate.subjectAlternativeNames) {
      null -> listOf()
      else -> subjectAlternativeNames.map { c: List<*> -> c[1] as String }
    }

  @Test fun replacementCharacter() {
    // $ cat ./cert.cnf
    // [req]
    // distinguished_name=distinguished_name
    // req_extensions=req_extensions
    // x509_extensions=x509_extensions
    // [distinguished_name]
    // [req_extensions]
    // [x509_extensions]
    // subjectAltName=DNS:℡.com,DNS:K.com
    //
    // $ openssl req -x509 -nodes -days 36500 -subj '/CN=foo.com' -config ./cert.cnf \
    //     -newkey rsa:512 -out cert.pem
    val session =
      session(
        """
        -----BEGIN CERTIFICATE-----
        MIIBSDCB86ADAgECAhRLR4TGgXBegg0np90FZ1KPeWpDtjANBgkqhkiG9w0BAQsF
        ADASMRAwDgYDVQQDDAdmb28uY29tMCAXDTIwMTAyOTA2NTkwNVoYDzIxMjAxMDA1
        MDY1OTA1WjASMRAwDgYDVQQDDAdmb28uY29tMFwwDQYJKoZIhvcNAQEBBQADSwAw
        SAJBALQcTVW9aW++ClIV9/9iSzijsPvQGEu/FQOjIycSrSIheZyZmR8bluSNBq0C
        9fpalRKZb0S2tlCTi5WoX8d3K30CAwEAAaMfMB0wGwYDVR0RBBQwEoIH4oShLmNv
        bYIH4oSqLmNvbTANBgkqhkiG9w0BAQsFAANBAA1+/eDvSUGv78iEjNW+1w3OPAwt
        Ij1qLQ/YI8OogZPMk7YY46/ydWWp7UpD47zy/vKmm4pOc8Glc8MoDD6UADs=
        -----END CERTIFICATE-----
        """.trimIndent(),
      )

    // Replacement characters are deliberate, from certificate loading.
    assertThat(verifier.verify("���.com", session)).isFalse()
    assertThat(verifier.verify("℡.com", session)).isFalse()
  }

  @Test fun malformedIpAddressAndMalformedSubjectAltName() {
    // $ cat ./cert.cnf
    // [req]
    // distinguished_name=distinguished_name
    // x509_extensions=x509_extensions
    // [distinguished_name]
    // [x509_extensions]
    // # An iPAddress holding an address and a netmask. Java reads it back as
    // # '1.2.3.4/255.255.255.0', which is not a host name.
    // subjectAltName=DER:30:0a:87:08:01:02:03:04:ff:ff:ff:00
    //
    // $ openssl req -x509 -nodes -days 36500 -subj '/CN=foo.com' -config ./cert.cnf \
    //     -newkey rsa:2048 -out cert.pem
    val session =
      session(
        """
        -----BEGIN CERTIFICATE-----
        MIIC6jCCAdKgAwIBAgIUOs0TfFZ8c478WBRA5JhYDmCY27kwDQYJKoZIhvcNAQEL
        BQAwEjEQMA4GA1UEAwwHZm9vLmNvbTAgFw0yNjA3MjkxNzQzMzZaGA8yMTI2MDcw
        NTE3NDMzNlowEjEQMA4GA1UEAwwHZm9vLmNvbTCCASIwDQYJKoZIhvcNAQEBBQAD
        ggEPADCCAQoCggEBAKRuEQYMeSBJkGt4Xah86p1TthHyTrQDqp2n2UMUFAbx6zSq
        LXJ/VMRMevKectFkgu2i+blMDvQcgiy3Mu1CNvGdJtWqCiUThZgvZVxAfliU5YNi
        K7X7V9EK+FqF1SB2mpzxhY2+a+tPBFTUxMFqOHl8JMZ1ZVTtmbRn+N1l9DojjqM5
        R0DZcxC/p5Fu6+EmFRc5ORK7J2s1NBYsEm5Arr64Vy/h9XUiz38uX+umQb8tUWtf
        KjneRhiGWehqzpOpKzsk8KQ7TDFbT3++jltyzoTaeJ1S1wO3ORjGgQ1JPgmHXw17
        4LymfT36hs1KY30h6Pq9hQWeRPk47n5SVpDvK8kCAwEAAaM2MDQwEwYDVR0RBAww
        CocIAQIDBP///wAwHQYDVR0OBBYEFMxmf4cFN9j8mIzwq4OJFwTOev1BMA0GCSqG
        SIb3DQEBCwUAA4IBAQCTS5DRJjNMxGfc1w8wSoiAWM+ZNQk/Pdi7iysLVLSjlE9w
        RmOd6oB3bsukBfUYTidM5m0Jq2hFXSQ7pb5pghh+E8IKnqYighFiFBlroQABswQ2
        BCCftWj0P9cGSp27Uq9yjvjNUDXHIDxS6qgCcsnNOpFF8NWGJ3YQPvPXkxC3C1rX
        rCu5cpLiayY5CdZcuqxqE68l3zaMh3Vd8YCaIwJupkMVRJ0emNnv92/7t86rsGDO
        P4laLm0p2pMeNrcZular1zvxzcVIv5uKq5ND+EBHp/rsT+Qy9n/NlRsspLTkQKsA
        Lu6ozoYr25wr3VMYnoba4P/fiCJOXhO7f/pxbUao
        -----END CERTIFICATE-----
        """.trimIndent(),
      )

    // Both of these have two '::' and are not addresses.
    assertThat(verifier.verify("1::2::3", session)).isFalse()
    assertThat(verifier.verify("::1::", session)).isFalse()
  }

  @Test fun thatCatchesErrorsWithBadSession() {
    val localVerifier = OkHttpClient().hostnameVerifier

    // Since this is public API, okhttp3.internal.tls.OkHostnameVerifier.verify is also
    assertThat(verifier).isInstanceOf<OkHostnameVerifier>()
    val handshakeCertificates = platform.localhostHandshakeCertificates()
    val session = handshakeCertificates.sslContext().createSSLEngine().session
    assertThat(localVerifier.verify("\uD83D\uDCA9.com", session)).isFalse()
  }

  @Test fun verifyAsIpAddress() {
    // IPv4
    assertThat("127.0.0.1".canParseAsIpAddress()).isTrue()
    assertThat("1.2.3.4".canParseAsIpAddress()).isTrue()

    // IPv6
    assertThat("::1".canParseAsIpAddress()).isTrue()
    assertThat("2001:db8::1".canParseAsIpAddress()).isTrue()
    assertThat("::192.168.0.1".canParseAsIpAddress()).isTrue()
    assertThat("::ffff:192.168.0.1".canParseAsIpAddress()).isTrue()
    assertThat("FEDC:BA98:7654:3210:FEDC:BA98:7654:3210".canParseAsIpAddress()).isTrue()
    assertThat("1080:0:0:0:8:800:200C:417A".canParseAsIpAddress()).isTrue()
    assertThat("1080::8:800:200C:417A".canParseAsIpAddress()).isTrue()
    assertThat("FF01::101".canParseAsIpAddress()).isTrue()
    assertThat("0:0:0:0:0:0:13.1.68.3".canParseAsIpAddress()).isTrue()
    assertThat("0:0:0:0:0:FFFF:129.144.52.38".canParseAsIpAddress()).isTrue()
    assertThat("::13.1.68.3".canParseAsIpAddress()).isTrue()
    assertThat("::FFFF:129.144.52.38".canParseAsIpAddress()).isTrue()

    // Hostnames
    assertThat("go".canParseAsIpAddress()).isFalse()
    assertThat("localhost".canParseAsIpAddress()).isFalse()
    assertThat("squareup.com".canParseAsIpAddress()).isFalse()
    assertThat("www.nintendo.co.jp".canParseAsIpAddress()).isFalse()
  }

  private fun certificate(certificate: String): X509Certificate =
    CertificateFactory
      .getInstance("X.509")
      .generateCertificate(ByteArrayInputStream(certificate.toByteArray()))
      as X509Certificate

  private fun session(certificate: String): SSLSession = FakeSSLSession(certificate(certificate))
}
