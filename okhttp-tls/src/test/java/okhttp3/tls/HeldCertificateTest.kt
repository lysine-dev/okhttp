/*
 * Copyright (C) 2018 Square, Inc.
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
package okhttp3.tls

import assertk.assertThat
import assertk.assertions.containsExactly
import assertk.assertions.isCloseTo
import assertk.assertions.isEqualTo
import assertk.assertions.isIn
import assertk.assertions.isNull
import assertk.assertions.matches
import java.math.BigInteger
import java.security.KeyFactory
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.X509EncodedKeySpec
import java.util.concurrent.TimeUnit
import okhttp3.testing.PlatformRule
import okhttp3.tls.HeldCertificate.Companion.decode
import okio.ByteString.Companion.decodeBase64
import org.bouncycastle.asn1.x509.GeneralName
import org.junit.jupiter.api.Assertions.fail
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.RegisterExtension

class HeldCertificateTest {
  @RegisterExtension
  var platform = PlatformRule()

  @Test
  fun defaultCertificate() {
    val now = System.currentTimeMillis()
    val heldCertificate = HeldCertificate.Builder().build()
    val certificate = heldCertificate.certificate
    assertThat(certificate.getSubjectX500Principal().name, "self-signed")
      .isEqualTo(certificate.getIssuerX500Principal().name)
    assertThat(certificate.getIssuerX500Principal().name).matches(Regex("CN=[0-9a-f-]{36}"))
    assertThat(certificate.serialNumber).isEqualTo(BigInteger.ONE)
    assertThat(certificate.subjectAlternativeNames).isNull()
    val deltaMillis = 1000.0
    val durationMillis = TimeUnit.MINUTES.toMillis((60 * 24).toLong())
    assertThat(certificate.notBefore.time.toDouble())
      .isCloseTo(now.toDouble(), deltaMillis)
    assertThat(certificate.notAfter.time.toDouble())
      .isCloseTo(now.toDouble() + durationMillis, deltaMillis)
  }

  @Test
  fun customInterval() {
    // 5 seconds starting on 1970-01-01.
    val heldCertificate =
      HeldCertificate
        .Builder()
        .validityInterval(5000L, 10000L)
        .build()
    val certificate = heldCertificate.certificate
    assertThat(certificate.notBefore.time).isEqualTo(5000L)
    assertThat(certificate.notAfter.time).isEqualTo(10000L)
  }

  @Test
  fun customDuration() {
    val now = System.currentTimeMillis()
    val heldCertificate =
      HeldCertificate
        .Builder()
        .duration(5, TimeUnit.SECONDS)
        .build()
    val certificate = heldCertificate.certificate
    val deltaMillis = 1000.0
    val durationMillis = 5000L
    assertThat(certificate.notBefore.time.toDouble())
      .isCloseTo(now.toDouble(), deltaMillis)
    assertThat(certificate.notAfter.time.toDouble())
      .isCloseTo(now.toDouble() + durationMillis, deltaMillis)
  }

  @Test
  fun subjectAlternativeNames() {
    val heldCertificate =
      HeldCertificate
        .Builder()
        .addSubjectAlternativeName("1.1.1.1")
        .addSubjectAlternativeName("cash.app")
        .build()
    val certificate = heldCertificate.certificate
    assertThat(certificate.subjectAlternativeNames.toList()).containsExactly(
      listOf(GeneralName.iPAddress, "1.1.1.1"),
      listOf(GeneralName.dNSName, "cash.app"),
    )
  }

  @Test
  fun commonName() {
    val heldCertificate =
      HeldCertificate
        .Builder()
        .commonName("cash.app")
        .build()
    val certificate = heldCertificate.certificate
    assertThat(certificate.getSubjectX500Principal().name).isEqualTo("CN=cash.app")
  }

  @Test
  fun organizationalUnit() {
    val heldCertificate =
      HeldCertificate
        .Builder()
        .commonName("cash.app")
        .organizationalUnit("cash")
        .build()
    val certificate = heldCertificate.certificate
    assertThat(certificate.getSubjectX500Principal().name).isEqualTo(
      "CN=cash.app,OU=cash",
    )
  }

  /** Confirm golden values of encoded PEMs.  */
  @Test
  fun pems() {
    val keyFactory = KeyFactory.getInstance("RSA")
    val publicKeyBytes =
      (
        "MIGfMA0GCSqGSIb3DQEBAQUAA4GNADCBiQKBgQCApFHhtrLan28q+oMolZuaTfWBA0V5aM" +
          "Ivq32BsloQu6LlvX1wJ4YEoUCjDlPOtpht7XLbUmBnbIzN89XK4UJVM6Sqp3K88Km8z7gMrdrfTom/274wL25fICR+" +
          "yDEQ5fUVYBmJAKXZF1aoI0mIoEx0xFsQhIJ637v2MxJDupd61wIDAQAB"
      ).decodeBase64()!!
    val publicKey =
      keyFactory.generatePublic(
        X509EncodedKeySpec(publicKeyBytes.toByteArray()),
      )
    val privateKeyBytes =
      (
        "MIICdQIBADANBgkqhkiG9w0BAQEFAASCAl8wggJbAgEAAoGBAICkUeG2stqfbyr6gyiVm" +
          "5pN9YEDRXlowi+rfYGyWhC7ouW9fXAnhgShQKMOU862mG3tcttSYGdsjM3z1crhQlUzpKqncrzwqbzPuAyt2t9Oib/" +
          "bvjAvbl8gJH7IMRDl9RVgGYkApdkXVqgjSYigTHTEWxCEgnrfu/YzEkO6l3rXAgMBAAECgYB99mhnB6piADOuddXv6" +
          "26NzUBTr4xbsYRTgSxHzwf50oFTTBSDuW+1IOBVyTWu94SSPyt0LllPbC8Di3sQSTnVGpSqAvEXknBMzIc0UO74Rn9" +
          "p3gZjEenPt1l77fIBa2nK06/rdsJCoE/1P1JSfM9w7LU1RsTmseYMLeJl5F79gQJBAO/BbAKqg1yzK7VijygvBoUrr" +
          "+rt2lbmKgcUQ/rxu8IIQk0M/xgJqSkXDXuOnboGM7sQSKfJAZUtT7xozvLzV7ECQQCJW59w7NIM0qZ/gIX2gcNZr1B" +
          "/V3zcGlolTDciRm+fnKGNt2EEDKnVL3swzbEfTCa48IT0QKgZJqpXZERa26UHAkBLXmiP5f5pk8F3wcXzAeVw06z3k" +
          "1IB41Tu6MX+CyPU+TeudRlz+wV8b0zDvK+EnRKCCbptVFj1Bkt8lQ4JfcnhAkAk2Y3Gz+HySrkcT7Cg12M/NkdUQnZ" +
          "e3jr88pt/+IGNwomc6Wt/mJ4fcWONTkGMcfOZff1NQeNXDAZ6941XCsIVAkASOg02PlVHLidU7mIE65swMM5/RNhS4" +
          "aFjez/MwxFNOHaxc9VgCwYPXCLOtdf7AVovdyG0XWgbUXH+NyxKwboE"
      ).decodeBase64()!!
    val privateKey =
      keyFactory.generatePrivate(
        PKCS8EncodedKeySpec(privateKeyBytes.toByteArray()),
      )
    val heldCertificate =
      HeldCertificate
        .Builder()
        .keyPair(publicKey, privateKey)
        .commonName("cash.app")
        .validityInterval(0L, 1000L)
        .rsa2048()
        .build()
    assertThat(
      """
      |-----BEGIN CERTIFICATE-----
      |MIIBmjCCAQOgAwIBAgIBATANBgkqhkiG9w0BAQsFADATMREwDwYDVQQDDAhjYXNo
      |LmFwcDAeFw03MDAxMDEwMDAwMDBaFw03MDAxMDEwMDAwMDFaMBMxETAPBgNVBAMM
      |CGNhc2guYXBwMIGfMA0GCSqGSIb3DQEBAQUAA4GNADCBiQKBgQCApFHhtrLan28q
      |+oMolZuaTfWBA0V5aMIvq32BsloQu6LlvX1wJ4YEoUCjDlPOtpht7XLbUmBnbIzN
      |89XK4UJVM6Sqp3K88Km8z7gMrdrfTom/274wL25fICR+yDEQ5fUVYBmJAKXZF1ao
      |I0mIoEx0xFsQhIJ637v2MxJDupd61wIDAQABMA0GCSqGSIb3DQEBCwUAA4GBADHT
      |vcjwl9Z4I5Cb2R1y7aaa860HkY2k3ThaDK5OJt6GYqJTA9P3LtX7VwQtL1TWqXGc
      |+OEfl3zhm0PUqcbckMzhJtqIa7NkDSjNm71BKd843pIhGcEri69DcL/cR8T+eMex
      |hadh7aGM9OjeL8gznLeq27Ly6Dj7Vkp5OmOrSKfn
      |-----END CERTIFICATE-----
      |
      """.trimMargin(),
    ).isEqualTo(heldCertificate.certificatePem())
    assertThat(
      """
      |-----BEGIN RSA PRIVATE KEY-----
      |MIICWwIBAAKBgQCApFHhtrLan28q+oMolZuaTfWBA0V5aMIvq32BsloQu6LlvX1w
      |J4YEoUCjDlPOtpht7XLbUmBnbIzN89XK4UJVM6Sqp3K88Km8z7gMrdrfTom/274w
      |L25fICR+yDEQ5fUVYBmJAKXZF1aoI0mIoEx0xFsQhIJ637v2MxJDupd61wIDAQAB
      |AoGAffZoZweqYgAzrnXV7+tujc1AU6+MW7GEU4EsR88H+dKBU0wUg7lvtSDgVck1
      |rveEkj8rdC5ZT2wvA4t7EEk51RqUqgLxF5JwTMyHNFDu+EZ/ad4GYxHpz7dZe+3y
      |AWtpytOv63bCQqBP9T9SUnzPcOy1NUbE5rHmDC3iZeRe/YECQQDvwWwCqoNcsyu1
      |Yo8oLwaFK6/q7dpW5ioHFEP68bvCCEJNDP8YCakpFw17jp26BjO7EEinyQGVLU+8
      |aM7y81exAkEAiVufcOzSDNKmf4CF9oHDWa9Qf1d83BpaJUw3IkZvn5yhjbdhBAyp
      |1S97MM2xH0wmuPCE9ECoGSaqV2REWtulBwJAS15oj+X+aZPBd8HF8wHlcNOs95NS
      |AeNU7ujF/gsj1Pk3rnUZc/sFfG9Mw7yvhJ0Sggm6bVRY9QZLfJUOCX3J4QJAJNmN
      |xs/h8kq5HE+woNdjPzZHVEJ2Xt46/PKbf/iBjcKJnOlrf5ieH3FjjU5BjHHzmX39
      |TUHjVwwGeveNVwrCFQJAEjoNNj5VRy4nVO5iBOubMDDOf0TYUuGhY3s/zMMRTTh2
      |sXPVYAsGD1wizrXX+wFaL3chtF1oG1Fx/jcsSsG6BA==
      |-----END RSA PRIVATE KEY-----
      |
      """.trimMargin(),
    ).isEqualTo(heldCertificate.privateKeyPkcs1Pem())
    assertThat(
      """
      |-----BEGIN PRIVATE KEY-----
      |MIICdQIBADANBgkqhkiG9w0BAQEFAASCAl8wggJbAgEAAoGBAICkUeG2stqfbyr6
      |gyiVm5pN9YEDRXlowi+rfYGyWhC7ouW9fXAnhgShQKMOU862mG3tcttSYGdsjM3z
      |1crhQlUzpKqncrzwqbzPuAyt2t9Oib/bvjAvbl8gJH7IMRDl9RVgGYkApdkXVqgj
      |SYigTHTEWxCEgnrfu/YzEkO6l3rXAgMBAAECgYB99mhnB6piADOuddXv626NzUBT
      |r4xbsYRTgSxHzwf50oFTTBSDuW+1IOBVyTWu94SSPyt0LllPbC8Di3sQSTnVGpSq
      |AvEXknBMzIc0UO74Rn9p3gZjEenPt1l77fIBa2nK06/rdsJCoE/1P1JSfM9w7LU1
      |RsTmseYMLeJl5F79gQJBAO/BbAKqg1yzK7VijygvBoUrr+rt2lbmKgcUQ/rxu8II
      |Qk0M/xgJqSkXDXuOnboGM7sQSKfJAZUtT7xozvLzV7ECQQCJW59w7NIM0qZ/gIX2
      |gcNZr1B/V3zcGlolTDciRm+fnKGNt2EEDKnVL3swzbEfTCa48IT0QKgZJqpXZERa
      |26UHAkBLXmiP5f5pk8F3wcXzAeVw06z3k1IB41Tu6MX+CyPU+TeudRlz+wV8b0zD
      |vK+EnRKCCbptVFj1Bkt8lQ4JfcnhAkAk2Y3Gz+HySrkcT7Cg12M/NkdUQnZe3jr8
      |8pt/+IGNwomc6Wt/mJ4fcWONTkGMcfOZff1NQeNXDAZ6941XCsIVAkASOg02PlVH
      |LidU7mIE65swMM5/RNhS4aFjez/MwxFNOHaxc9VgCwYPXCLOtdf7AVovdyG0XWgb
      |UXH+NyxKwboE
      |-----END PRIVATE KEY-----
      |
      """.trimMargin(),
    ).isEqualTo(heldCertificate.privateKeyPkcs8Pem())
  }

  @Test
  fun ecdsaSignedByRsa() {
    val root =
      HeldCertificate
        .Builder()
        .certificateAuthority(0)
        .rsa2048()
        .build()
    val leaf =
      HeldCertificate
        .Builder()
        .certificateAuthority(0)
        .ecdsa256()
        .signedBy(root)
        .build()
    assertThat(root.certificate.sigAlgName).isEqualTo("SHA256WITHRSA", ignoreCase = true)
    assertThat(leaf.certificate.sigAlgName).isEqualTo("SHA256WITHRSA", ignoreCase = true)
  }

  @Test
  fun rsaSignedByEcdsa() {
    val root =
      HeldCertificate
        .Builder()
        .certificateAuthority(0)
        .ecdsa256()
        .build()
    val leaf =
      HeldCertificate
        .Builder()
        .certificateAuthority(0)
        .rsa2048()
        .signedBy(root)
        .build()
    assertThat(root.certificate.sigAlgName).isEqualTo("SHA256WITHECDSA", ignoreCase = true)
    assertThat(leaf.certificate.sigAlgName).isEqualTo("SHA256WITHECDSA", ignoreCase = true)
  }

  @Test
  fun ed25519() {
    platform.assumeNotAndroid()
    val heldCertificate =
      HeldCertificate
        .Builder()
        .commonName("cash.app")
        .ed25519()
        .build()
    assertThat(heldCertificate.certificate.sigAlgName.lowercase()).isIn("ed25519", "eddsa")
    assertThat(heldCertificate.keyPair.private.algorithm.lowercase()).isIn("ed25519", "eddsa")
    assertThat(heldCertificate.keyPair.public.algorithm.lowercase()).isIn("ed25519", "eddsa")
  }

  @Test
  fun ed25519RoundTrip() {
    platform.assumeNotAndroid()
    val original =
      HeldCertificate
        .Builder()
        .commonName("cash.app")
        .ed25519()
        .build()
    val pem = original.certificatePem() + original.privateKeyPkcs8Pem()
    val decoded = decode(pem)
    assertThat(decoded.certificate.encoded).isEqualTo(original.certificate.encoded)
    assertThat(decoded.keyPair.private.encoded).isEqualTo(original.keyPair.private.encoded)
    assertThat(decoded.keyPair.public.encoded).isEqualTo(original.keyPair.public.encoded)
  }

  @Test
  fun ed25519SignedByEcdsa() {
    platform.assumeNotAndroid()
    val root =
      HeldCertificate
        .Builder()
        .certificateAuthority(0)
        .ecdsa256()
        .build()
    val leaf =
      HeldCertificate
        .Builder()
        .ed25519()
        .signedBy(root)
        .build()
    assertThat(root.certificate.sigAlgName).isEqualTo("SHA256WITHECDSA", ignoreCase = true)
    assertThat(leaf.certificate.sigAlgName).isEqualTo("SHA256WITHECDSA", ignoreCase = true)
    assertThat(leaf.keyPair.private.algorithm.lowercase()).isIn("ed25519", "eddsa")
  }

  @Test
  fun decodeEcdsa256() {
    // The certificate + private key below was generated programmatically:
    //
    // HeldCertificate heldCertificate = new HeldCertificate.Builder()
    //     .validityInterval(5_000L, 10_000L)
    //     .addSubjectAlternativeName("1.1.1.1")
    //     .addSubjectAlternativeName("cash.app")
    //     .serialNumber(42L)
    //     .commonName("cash.app")
    //     .organizationalUnit("engineering")
    //     .ecdsa256()
    //     .build();
    val certificatePem =
      """
      |-----BEGIN CERTIFICATE-----
      |MIIBYTCCAQegAwIBAgIBKjAKBggqhkjOPQQDAjApMRQwEgYDVQQLEwtlbmdpbmVl
      |cmluZzERMA8GA1UEAxMIY2FzaC5hcHAwHhcNNzAwMTAxMDAwMDA1WhcNNzAwMTAx
      |MDAwMDEwWjApMRQwEgYDVQQLEwtlbmdpbmVlcmluZzERMA8GA1UEAxMIY2FzaC5h
      |cHAwWTATBgcqhkjOPQIBBggqhkjOPQMBBwNCAASda8ChkQXxGELnrV/oBnIAx3dD
      |ocUOJfdz4pOJTP6dVQB9U3UBiW5uSX/MoOD0LL5zG3bVyL3Y6pDwKuYvfLNhoyAw
      |HjAcBgNVHREBAf8EEjAQhwQBAQEBgghjYXNoLmFwcDAKBggqhkjOPQQDAgNIADBF
      |AiAyHHg1N6YDDQiY920+cnI5XSZwEGhAtb9PYWO8bLmkcQIhAI2CfEZf3V/obmdT
      |yyaoEufLKVXhrTQhRfodTeigi4RX
      |-----END CERTIFICATE-----
      |
      """.trimMargin()
    val pkcs8Pem =
      """
      |-----BEGIN PRIVATE KEY-----
      |MEECAQAwEwYHKoZIzj0CAQYIKoZIzj0DAQcEJzAlAgEBBCA7ODT0xhGSNn4ESj6J
      |lu/GJQZoU9lDrCPeUcQ28tzOWw==
      |-----END PRIVATE KEY-----
      |
      """.trimMargin()
    val bcPkcs8Pem =
      """
      |-----BEGIN PRIVATE KEY-----
      |ME0CAQAwEwYHKoZIzj0CAQYIKoZIzj0DAQcEMzAxAgEBBCA7ODT0xhGSNn4ESj6J
      |lu/GJQZoU9lDrCPeUcQ28tzOW6AKBggqhkjOPQMBBw==
      |-----END PRIVATE KEY-----
      |
      """.trimMargin()
    val heldCertificate = decode(certificatePem + pkcs8Pem)
    assertThat(heldCertificate.certificatePem()).isEqualTo(certificatePem)

    // Slightly different encoding
    if (platform.isBouncyCastle()) {
      assertThat(heldCertificate.privateKeyPkcs8Pem()).isEqualTo(bcPkcs8Pem)
    } else {
      assertThat(heldCertificate.privateKeyPkcs8Pem()).isEqualTo(pkcs8Pem)
    }
    val certificate = heldCertificate.certificate
    assertThat(certificate.notBefore.time).isEqualTo(5000L)
    assertThat(certificate.notAfter.time).isEqualTo(10000L)
    assertThat(certificate.subjectAlternativeNames.toList()).containsExactly(
      listOf(GeneralName.iPAddress, "1.1.1.1"),
      listOf(GeneralName.dNSName, "cash.app"),
    )
    assertThat(certificate.getSubjectX500Principal().name)
      .isEqualTo("CN=cash.app,OU=engineering")
  }

  @Test
  fun decodeRsa512() {
    // The certificate + private key below was generated with OpenSSL. Never generate certificates
    // with MD5 or 512-bit RSA; that's insecure!
    //
    // openssl req \
    //   -x509 \
    //   -md5 \
    //   -nodes \
    //   -days 1 \
    //   -newkey rsa:512 \
    //   -keyout privateKey.key \
    //   -out certificate.crt
    val certificatePem =
      """
      |-----BEGIN CERTIFICATE-----
      |MIIBFzCBwgIJAIVAqagcVN7/MA0GCSqGSIb3DQEBBAUAMBMxETAPBgNVBAMMCGNh
      |c2guYXBwMB4XDTE5MDkwNzAyMjg0NFoXDTE5MDkwODAyMjg0NFowEzERMA8GA1UE
      |AwwIY2FzaC5hcHAwXDANBgkqhkiG9w0BAQEFAANLADBIAkEA8qAeoubm4mBTD9/J
      |ujLQkfk/fuJt/T5pVQ1vUEqxfcMw0zYgszQ5C2MiIl7M6JkTRKU01q9hVFCR83wX
      |zIdrLQIDAQABMA0GCSqGSIb3DQEBBAUAA0EAO1UpwhrkW3Ho1nZK/taoUQOoqz/n
      |HFVMtyEkm5gBDgz8nJXwb3zbegclQyH+kVou02S8zC5WWzEtd0R8S0LsTA==
      |-----END CERTIFICATE-----
      |
      """.trimMargin()
    val pkcs8Pem =
      """
      |-----BEGIN PRIVATE KEY-----
      |MIIBVQIBADANBgkqhkiG9w0BAQEFAASCAT8wggE7AgEAAkEA8qAeoubm4mBTD9/J
      |ujLQkfk/fuJt/T5pVQ1vUEqxfcMw0zYgszQ5C2MiIl7M6JkTRKU01q9hVFCR83wX
      |zIdrLQIDAQABAkEA7dEA9o/5k77y68ZhRv9z7QEwucBcKzQ3rsSCbWMpYqg924F9
      |L8Z76kzSedSO2PN8mg6y/OLL+qBuTeUK/yiowQIhAP0cknFMbqeNX6uvj/S+V7in
      |bIhQkhcSdJjRw8fxMnJpAiEA9WTp9wzJpn+9etZo0jJ8wkM0+LTMNELo47Ctz7l1
      |kiUCIQCi34vslD5wWyzBEcwUtZdFH5dbcF1Rs3KMFA9jzfWkYQIgHtiWiFV1K5a3
      |DK/S8UkjYY/tIq4nVRJsD+LvlkLrwnkCIECcz4yF4HQgv+Tbzj/gGSBl1VIliTcB
      |Rc5RUQ0mZJQF
      |-----END PRIVATE KEY-----
      |
      """.trimMargin()
    val heldCertificate = decode(pkcs8Pem + certificatePem)
    assertThat(heldCertificate.certificatePem()).isEqualTo(certificatePem)
    assertThat(heldCertificate.privateKeyPkcs8Pem()).isEqualTo(pkcs8Pem)
    val certificate = heldCertificate.certificate
    assertThat(certificate.getSubjectX500Principal().name)
      .isEqualTo("CN=cash.app")
  }

  @Test
  fun decodeRsa2048() {
    // The certificate + private key below was generated programmatically:
    //
    // HeldCertificate heldCertificate = new HeldCertificate.Builder()
    //     .validityInterval(5_000L, 10_000L)
    //     .addSubjectAlternativeName("1.1.1.1")
    //     .addSubjectAlternativeName("cash.app")
    //     .serialNumber(42L)
    //     .commonName("cash.app")
    //     .organizationalUnit("engineering")
    //     .rsa2048()
    //     .build();
    val certificatePem =
      """
      |-----BEGIN CERTIFICATE-----
      |MIIC7TCCAdWgAwIBAgIBKjANBgkqhkiG9w0BAQsFADApMRQwEgYDVQQLEwtlbmdp
      |bmVlcmluZzERMA8GA1UEAxMIY2FzaC5hcHAwHhcNNzAwMTAxMDAwMDA1WhcNNzAw
      |MTAxMDAwMDEwWjApMRQwEgYDVQQLEwtlbmdpbmVlcmluZzERMA8GA1UEAxMIY2Fz
      |aC5hcHAwggEiMA0GCSqGSIb3DQEBAQUAA4IBDwAwggEKAoIBAQCaU+vrUPL0APGI
      |SXIuRX4xRrigXmGKx+GRPnWDWvGJwOm23Vpq/eZxQx6PbSUB1+QZzAwge20RpNAp
      |2lt5/qFtgUpEon2j06rd/0+ODqqVJX+6d3SpmF1fPfKUB6AOZbxEkaJpBSTavoTg
      |G2M/NMdjZjrcB3quNQcLg54mmI3HJm1zOd/8i2fZjvoiyVY30Inn2SmQsAotXw1u
      |aE/319bnR2sQlnkp6MJU0eLEtKyRif/IODvY+mtRYYdkFtoeT6qQPMIh+gF/H3to
      |5tjs3g59QC8k2TJDop4EFYUOwdrtnb8wUiBnLyURD1szASE2IO2Ftk1zaNOPKtrv
      |VeJuB/mpAgMBAAGjIDAeMBwGA1UdEQEB/wQSMBCHBAEBAQGCCGNhc2guYXBwMA0G
      |CSqGSIb3DQEBCwUAA4IBAQAPm7vfk+rxSucxxbFiimmFKBw+ymieLY/kznNh0lHJ
      |q15fsMYK7TTTt2FFqyfVXhhRZegLrkrGb3+4Dz1uNtcRrjT4qo+T/JOuZGxtBLbm
      |4/hkFSYavtd2FW+/CK7EnQKUyabgLOblb21IHOlcPwpSe6KkJjpwq0TV/ozzfk/q
      |kGRA7/Ubn5TMRYyHWnod2SS14+BkItcWN03Z7kvyMYrpNZpu6vQRYsqJJFMcmpGZ
      |sZQW31gO2arPmfNotkQdFdNL12c9YZKkJGhyK6NcpffD2l6O9NS5SRD5RnkvBxQw
      |fX5DamL8je/YKSLQ4wgUA/5iVKlCiJGQi6fYIJ0kxayO
      |-----END CERTIFICATE-----
      |
      """.trimMargin()
    val pkcs8Pem =
      """
      |-----BEGIN PRIVATE KEY-----
      |MIIEvwIBADANBgkqhkiG9w0BAQEFAASCBKkwggSlAgEAAoIBAQCaU+vrUPL0APGI
      |SXIuRX4xRrigXmGKx+GRPnWDWvGJwOm23Vpq/eZxQx6PbSUB1+QZzAwge20RpNAp
      |2lt5/qFtgUpEon2j06rd/0+ODqqVJX+6d3SpmF1fPfKUB6AOZbxEkaJpBSTavoTg
      |G2M/NMdjZjrcB3quNQcLg54mmI3HJm1zOd/8i2fZjvoiyVY30Inn2SmQsAotXw1u
      |aE/319bnR2sQlnkp6MJU0eLEtKyRif/IODvY+mtRYYdkFtoeT6qQPMIh+gF/H3to
      |5tjs3g59QC8k2TJDop4EFYUOwdrtnb8wUiBnLyURD1szASE2IO2Ftk1zaNOPKtrv
      |VeJuB/mpAgMBAAECggEAOlOXaYNZn1Cv+INRrR1EmVkSNEIXeX0bymohvbhka1zG
      |t/8myiMVsh7c8PYeM3kl034j4y7ixPVWW0sUoaHT3vArYo9LDtzTyj1REu6GGAJp
      |KM82/1X/jBx8jufm3SokIoIsMKbqC+ZPj+ep9dx7sxyTCE+nVSnjdL2Uyx+DDg3o
      |na237HTScWIi+tMv5QGEwqLHS2q+NZYfjgnSxNY8BRw4XZCcIZRko9niuB5gUjj/
      |y01HwvOCWuOMaSKZak1OdOaz3427/TkhYIqf6ft0ELF+ASRk3BLQA06pRt88H3u2
      |3vsHJsWr2rkCN0h9uDp2o50ZQ5fvlxqG0QIZmvkIkQKBgQDOHeZKvXO5IxQ+S8ed
      |09bC5SKiclCdW+Ry7N2x1MBfrxc4TTTTNaUN9Qdc6RXANG9bX2CJv0Dkh/0yH3z9
      |Bdq6YcoP6DFCX46jwhCKvxMX9h9PFLvY7l2VSe7NfboGzvYLCy8ErsGuio8u9MHZ
      |osX2ch6Gdhn1xUwLCw+T7rNwjQKBgQC/rWb0sWfgbKhEqV+u5oov+fFjooWmTQlQ
      |jcj+lMWUOkitnPmX9TsH5JDa8I89Y0gJGu7Lfg8XSH+4FCCfX3mSLYwVH5vAIvmr
      |TjMqRwSahQuTr/g+lx7alpcUHYv3z6b3WYIXFPPr3t7grWNJ14wMv9DnItWOg84H
      |LlxAvXXsjQKBgQCRPPhdignVVyaYjwVl7TPTuWoiVbMAbxQW91lwSZ4UzmfqQF0M
      |xyw7HYHGsmelPE2LcTWxWpb7cee0PgPwtwNdejLL6q1rO7JjKghF/EYUCFYff1iu
      |j6hZ3fLr0cAXtBYjygmjnxDTUMd8KvO9y7j644cm8GlyiUgAMBcWAolmsQKBgQCT
      |AJQTWfPGxM6QSi3d32VfwhsFROGnVzGrm/HofYTCV6jhraAmkKcDOKJ3p0LT286l
      |XQiC/FzqiGmbbaRPVlPQbiofESzMQIamgMTwyaKYNy1XyP9kUVYSYqfff4GXPqRY
      |00bYGPOxlC3utkuNmEgKhxnaCncqY5+hFkceR6+nCQKBgQC1Gonjhw0lYe43aHpp
      |nDJKv3FnyN3wxjsR2c9sWpDzHA6CMVhSeLoXCB9ishmrSE/CygNlTU1TEy63xN22
      |+dMHl5I/urMesjKKWiKZHdbWVIjJDv25r3jrN9VLr4q6AD9r1Su5G0o2j0N5ujVg
      |SzpFHp+ZzhL/SANa8EqlcF6ItQ==
      |-----END PRIVATE KEY-----
      |
      """.trimMargin()
    val heldCertificate = decode(pkcs8Pem + certificatePem)
    assertThat(heldCertificate.certificatePem()).isEqualTo(certificatePem)
    assertThat(heldCertificate.privateKeyPkcs8Pem()).isEqualTo(pkcs8Pem)
    val certificate = heldCertificate.certificate
    assertThat(certificate.notBefore.time).isEqualTo(5000L)
    assertThat(certificate.notAfter.time).isEqualTo(10000L)
    assertThat(certificate.subjectAlternativeNames.toList()).containsExactly(
      listOf(GeneralName.iPAddress, "1.1.1.1"),
      listOf(GeneralName.dNSName, "cash.app"),
    )
    assertThat(certificate.getSubjectX500Principal().name)
      .isEqualTo("CN=cash.app,OU=engineering")
  }

  @Test
  fun decodeWrongNumber() {
    val certificatePem =
      """
      |-----BEGIN CERTIFICATE-----
      |MIIBYTCCAQegAwIBAgIBKjAKBggqhkjOPQQDAjApMRQwEgYDVQQLEwtlbmdpbmVl
      |cmluZzERMA8GA1UEAxMIY2FzaC5hcHAwHhcNNzAwMTAxMDAwMDA1WhcNNzAwMTAx
      |MDAwMDEwWjApMRQwEgYDVQQLEwtlbmdpbmVlcmluZzERMA8GA1UEAxMIY2FzaC5h
      |cHAwWTATBgcqhkjOPQIBBggqhkjOPQMBBwNCAASda8ChkQXxGELnrV/oBnIAx3dD
      |ocUOJfdz4pOJTP6dVQB9U3UBiW5uSX/MoOD0LL5zG3bVyL3Y6pDwKuYvfLNhoyAw
      |HjAcBgNVHREBAf8EEjAQhwQBAQEBgghjYXNoLmFwcDAKBggqhkjOPQQDAgNIADBF
      |AiAyHHg1N6YDDQiY920+cnI5XSZwEGhAtb9PYWO8bLmkcQIhAI2CfEZf3V/obmdT
      |yyaoEufLKVXhrTQhRfodTeigi4RX
      |-----END CERTIFICATE-----
      |
      """.trimMargin()
    val pkcs8Pem =
      """
      |-----BEGIN PRIVATE KEY-----
      |MEECAQAwEwYHKoZIzj0CAQYIKoZIzj0DAQcEJzAlAgEBBCA7ODT0xhGSNn4ESj6J
      |lu/GJQZoU9lDrCPeUcQ28tzOWw==
      |-----END PRIVATE KEY-----
      |
      """.trimMargin()
    try {
      decode(certificatePem)
      fail<Any>()
    } catch (expected: IllegalArgumentException) {
      assertThat(expected.message).isEqualTo("string does not include a private key")
    }
    try {
      decode(pkcs8Pem)
      fail<Any>()
    } catch (expected: IllegalArgumentException) {
      assertThat(expected.message).isEqualTo("string does not include a certificate")
    }
    try {
      decode(certificatePem + pkcs8Pem + certificatePem)
      fail<Any>()
    } catch (expected: IllegalArgumentException) {
      assertThat(expected.message).isEqualTo("string includes multiple certificates")
    }
    try {
      decode(pkcs8Pem + certificatePem + pkcs8Pem)
      fail<Any>()
    } catch (expected: IllegalArgumentException) {
      assertThat(expected.message).isEqualTo("string includes multiple private keys")
    }
  }

  @Test
  fun decodeWrongType() {
    try {
      decode(
        """
        |-----BEGIN CERTIFICATE-----
        |MIIBmjCCAQOgAwIBAgIBATANBgkqhkiG9w0BAQsFADATMREwDwYDVQQDEwhjYXNo
        |-----END CERTIFICATE-----
        |-----BEGIN RSA PRIVATE KEY-----
        |sXPVYAsGD1wizrXX+wFaL3chtF1oG1Fx/jcsSsG6BA==
        |-----END RSA PRIVATE KEY-----
        |
        """.trimMargin(),
      )
      fail<Any>()
    } catch (expected: IllegalArgumentException) {
      assertThat(expected.message).isEqualTo("unexpected type: RSA PRIVATE KEY")
    }
  }

  @Test
  fun decodeMalformed() {
    try {
      decode(
        """
        |-----BEGIN CERTIFICATE-----
        |MIIBYTCCAQegAwIBAgIBKjAKBggqhkjOPQQDAjApMRQwEgYDVQQLEwtlbmdpbmVl
        |-----END CERTIFICATE-----
        |-----BEGIN PRIVATE KEY-----
        |MEECAQAwEwYHKoZIzj0CAQYIKoZIzj0DAQcEJzAlAgEBBCA7ODT0xhGSNn4ESj6J
        |lu/GJQZoU9lDrCPeUcQ28tzOWw==
        |-----END PRIVATE KEY-----
        |
        """.trimMargin(),
      )
      fail<Any>()
    } catch (expected: IllegalArgumentException) {
      if (!platform.isConscrypt()) {
        assertThat(expected.message).isEqualTo("failed to decode certificate")
      }
    }
    try {
      decode(
        """
        |-----BEGIN CERTIFICATE-----
        |MIIBYTCCAQegAwIBAgIBKjAKBggqhkjOPQQDAjApMRQwEgYDVQQLEwtlbmdpbmVl
        |cmluZzERMA8GA1UEAxMIY2FzaC5hcHAwHhcNNzAwMTAxMDAwMDA1WhcNNzAwMTAx
        |MDAwMDEwWjApMRQwEgYDVQQLEwtlbmdpbmVlcmluZzERMA8GA1UEAxMIY2FzaC5h
        |cHAwWTATBgcqhkjOPQIBBggqhkjOPQMBBwNCAASda8ChkQXxGELnrV/oBnIAx3dD
        |ocUOJfdz4pOJTP6dVQB9U3UBiW5uSX/MoOD0LL5zG3bVyL3Y6pDwKuYvfLNhoyAw
        |HjAcBgNVHREBAf8EEjAQhwQBAQEBgghjYXNoLmFwcDAKBggqhkjOPQQDAgNIADBF
        |AiAyHHg1N6YDDQiY920+cnI5XSZwEGhAtb9PYWO8bLmkcQIhAI2CfEZf3V/obmdT
        |yyaoEufLKVXhrTQhRfodTeigi4RX
        |-----END CERTIFICATE-----
        |-----BEGIN PRIVATE KEY-----
        |MEECAQAwEwYHKoZIzj0CAQYIKoZIzj0DAQcEJzAlAgEBBCA7ODT0xhGSNn4ESj6J
        |-----END PRIVATE KEY-----
        |
        """.trimMargin(),
      )
      fail<Any>()
    } catch (expected: IllegalArgumentException) {
      if (!platform.isConscrypt()) {
        assertThat(expected.message).isEqualTo("failed to decode private key")
      }
    }
  }
}
