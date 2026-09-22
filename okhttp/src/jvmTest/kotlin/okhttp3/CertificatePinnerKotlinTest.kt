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
package okhttp3

import assertk.assertThat
import assertk.assertions.containsExactly
import assertk.assertions.isEmpty
import assertk.assertions.isEqualTo
import okhttp3.CertificatePinner.Companion.sha1Hash
import okhttp3.CertificatePinner.Pin
import okhttp3.tls.HeldCertificate
import okio.ByteString.Companion.decodeBase64
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class CertificatePinnerKotlinTest {
  @Test
  fun successfulCheckSha1Pin() {
    val certificatePinner =
      CertificatePinner
        .Builder()
        .add("example.com", "sha1/" + certA1.certificate.sha1Hash().base64())
        .build()

    certificatePinner.check("example.com", listOf(certA1.certificate))
  }

  @Test fun successfulFindMatchingPins() {
    val certificatePinner =
      CertificatePinner
        .Builder()
        .add("first.com", certA1Sha256Pin, certB1Sha256Pin)
        .add("second.com", certC1Sha256Pin)
        .build()

    val expectedPins =
      listOf(
        Pin("first.com", certA1Sha256Pin),
        Pin("first.com", certB1Sha256Pin),
      )
    assertThat(certificatePinner.findMatchingPins("first.com")).isEqualTo(expectedPins)
  }

  @Test fun successfulFindMatchingPinsForWildcardAndDirectCertificates() {
    val certificatePinner =
      CertificatePinner
        .Builder()
        .add("*.example.com", certA1Sha256Pin)
        .add("a.example.com", certB1Sha256Pin)
        .add("b.example.com", certC1Sha256Pin)
        .build()

    val expectedPins =
      listOf(
        Pin("*.example.com", certA1Sha256Pin),
        Pin("a.example.com", certB1Sha256Pin),
      )
    assertThat(certificatePinner.findMatchingPins("a.example.com")).isEqualTo(expectedPins)
  }

  @Test
  fun wildcardHostnameShouldNotMatchThroughDot() {
    val certificatePinner =
      CertificatePinner
        .Builder()
        .add("*.example.com", certA1Sha256Pin)
        .build()

    assertThat(certificatePinner.findMatchingPins("example.com")).isEmpty()
    assertThat(certificatePinner.findMatchingPins("..example.com")).isEmpty()
    assertThat(certificatePinner.findMatchingPins("a..example.com")).isEmpty()
    assertThat(certificatePinner.findMatchingPins("a.b.example.com")).isEmpty()
  }

  @Test
  fun doubleWildcardHostnameShouldMatchThroughDot() {
    val certificatePinner =
      CertificatePinner
        .Builder()
        .add("**.example.com", certA1Sha256Pin)
        .build()

    val expectedPin1 = listOf(Pin("**.example.com", certA1Sha256Pin))
    assertThat(certificatePinner.findMatchingPins("example.com")).isEqualTo(expectedPin1)
    assertThat(certificatePinner.findMatchingPins(".example.com")).isEqualTo(expectedPin1)
    assertThat(certificatePinner.findMatchingPins("..example.com")).isEqualTo(expectedPin1)
    assertThat(certificatePinner.findMatchingPins("a..example.com")).isEqualTo(expectedPin1)
    assertThat(certificatePinner.findMatchingPins("a.b.example.com")).isEqualTo(expectedPin1)
  }

  @Test
  fun doubleWildcardHostnameShouldNotMatchSuffix() {
    val certificatePinner =
      CertificatePinner
        .Builder()
        .add("**.example.com", certA1Sha256Pin)
        .build()

    assertThat(certificatePinner.findMatchingPins("xample.com")).isEmpty()
    assertThat(certificatePinner.findMatchingPins("dexample.com")).isEmpty()
    assertThat(certificatePinner.findMatchingPins("barnexample.com")).isEmpty()
  }

  @Test fun successfulFindMatchingPinsIgnoresCase() {
    val certificatePinner =
      CertificatePinner
        .Builder()
        .add("EXAMPLE.com", certA1Sha256Pin)
        .add("*.MyExample.Com", certB1Sha256Pin)
        .build()

    val expectedPin1 = listOf(Pin("EXAMPLE.com", certA1Sha256Pin))
    assertThat(certificatePinner.findMatchingPins("example.com")).isEqualTo(expectedPin1)

    val expectedPin2 = listOf(Pin("*.MyExample.Com", certB1Sha256Pin))
    assertThat(certificatePinner.findMatchingPins("a.myexample.com")).isEqualTo(expectedPin2)
  }

  @Test fun successfulFindMatchingPinPunycode() {
    val certificatePinner =
      CertificatePinner
        .Builder()
        .add("σkhttp.com", certA1Sha256Pin)
        .build()

    val expectedPin = listOf(Pin("σkhttp.com", certA1Sha256Pin))
    assertThat(certificatePinner.findMatchingPins("xn--khttp-fde.com")).isEqualTo(expectedPin)
  }

  /** https://github.com/lysine-dev/okhttp/issues/3324  */
  @Test
  fun checkSubstringMatch() {
    val certificatePinner =
      CertificatePinner
        .Builder()
        .add("*.example.com", certA1Sha256Pin)
        .build()

    assertThat(certificatePinner.findMatchingPins("a.example.com.notexample.com")).isEmpty()
    assertThat(certificatePinner.findMatchingPins("example.com.notexample.com")).isEmpty()
    assertThat(certificatePinner.findMatchingPins("notexample.com")).isEmpty()
    assertThat(certificatePinner.findMatchingPins("example.com")).isEmpty()
    assertThat(certificatePinner.findMatchingPins("a.b.example.com")).isEmpty()
    assertThat(certificatePinner.findMatchingPins("ple.com")).isEmpty()
    assertThat(certificatePinner.findMatchingPins("com")).isEmpty()

    val expectedPin = Pin("*.example.com", certA1Sha256Pin)
    assertThat(certificatePinner.findMatchingPins("a.example.com")).containsExactly(expectedPin)
    assertThat(certificatePinner.findMatchingPins(".example.com")).containsExactly(expectedPin)
    assertThat(certificatePinner.findMatchingPins("example.example.com"))
      .containsExactly(expectedPin)
  }

  @Test fun testGoodPin() {
    val pin =
      Pin(
        "**.example.co.uk",
        "sha256/AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=",
      )
    assertEquals("AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=".decodeBase64(), pin.hash)
    assertEquals("sha256", pin.hashAlgorithm)
    assertEquals("**.example.co.uk", pin.pattern)
    assertTrue(pin.matchesHostname("www.example.co.uk"))
    assertTrue(pin.matchesHostname("gopher.example.co.uk"))
    assertFalse(pin.matchesHostname("www.example.com"))
  }

  @Test fun testMatchesSha256() {
    val pin = Pin("example.com", certA1Sha256Pin)
    assertTrue(pin.matchesCertificate(certA1.certificate))
    assertFalse(pin.matchesCertificate(certB1.certificate))
  }

  @Test fun testMatchesSha1() {
    val pin = Pin("example.com", certC1Sha1Pin)
    assertTrue(pin.matchesCertificate(certC1.certificate))
    assertFalse(pin.matchesCertificate(certB1.certificate))
  }

  @Test fun pinList() {
    val builder =
      CertificatePinner
        .Builder()
        .add("example.com", CertificatePinnerTest.certA1Sha256Pin)
        .add("www.example.com", CertificatePinnerTest.certA1Sha256Pin)
    val certificatePinner = builder.build()

    val expectedPins =
      listOf(
        Pin("example.com", CertificatePinnerTest.certA1Sha256Pin),
        Pin("www.example.com", CertificatePinnerTest.certA1Sha256Pin),
      )

    assertEquals(expectedPins, builder.pins)
    assertEquals(expectedPins.toSet(), certificatePinner.pins)
  }

  companion object {
    internal var certA1: HeldCertificate =
      HeldCertificate
        .Builder()
        .serialNumber(100L)
        .build()
    internal var certA1Sha256Pin = CertificatePinner.pin(certA1.certificate)

    private var certB1 =
      HeldCertificate
        .Builder()
        .serialNumber(200L)
        .build()
    internal var certB1Sha256Pin = CertificatePinner.pin(certB1.certificate)

    private var certC1 =
      HeldCertificate
        .Builder()
        .serialNumber(300L)
        .build()
    internal var certC1Sha256Pin = CertificatePinner.pin(certC1.certificate)
    var certC1Sha1Pin = "sha1/" + certC1.certificate.sha1Hash().base64()
  }
}
