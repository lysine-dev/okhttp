/*
 * Copyright (C) 2016 Square, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package okhttp3.tls

import java.math.BigInteger
import java.net.InetAddress
import java.security.GeneralSecurityException
import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.PrivateKey
import java.security.PublicKey
import java.security.SecureRandom
import java.security.Signature
import java.security.cert.X509Certificate
import java.security.interfaces.ECPublicKey
import java.security.interfaces.RSAPrivateKey
import java.security.interfaces.RSAPublicKey
import java.security.spec.PKCS8EncodedKeySpec
import java.util.UUID
import java.util.concurrent.TimeUnit
import okhttp3.internal.canParseAsIpAddress
import okhttp3.tls.internal.der.AlgorithmIdentifier
import okhttp3.tls.internal.der.AttributeTypeAndValue
import okhttp3.tls.internal.der.BasicConstraints
import okhttp3.tls.internal.der.BitString
import okhttp3.tls.internal.der.Certificate
import okhttp3.tls.internal.der.CertificateAdapters
import okhttp3.tls.internal.der.CertificateAdapters.generalNameDnsName
import okhttp3.tls.internal.der.CertificateAdapters.generalNameIpAddress
import okhttp3.tls.internal.der.Extension
import okhttp3.tls.internal.der.ObjectIdentifiers
import okhttp3.tls.internal.der.ObjectIdentifiers.BASIC_CONSTRAINTS
import okhttp3.tls.internal.der.ObjectIdentifiers.ED25519
import okhttp3.tls.internal.der.ObjectIdentifiers.ORGANIZATIONAL_UNIT_NAME
import okhttp3.tls.internal.der.ObjectIdentifiers.SHA256_WITH_ECDSA
import okhttp3.tls.internal.der.ObjectIdentifiers.SHA256_WITH_RSA_ENCRYPTION
import okhttp3.tls.internal.der.ObjectIdentifiers.SUBJECT_ALTERNATIVE_NAME
import okhttp3.tls.internal.der.TbsCertificate
import okhttp3.tls.internal.der.Validity
import okio.ByteString
import okio.ByteString.Companion.decodeBase64
import okio.ByteString.Companion.toByteString

/**
 * A certificate and its private key. These are some properties of certificates that are used with
 * TLS:
 *
 *  * **A common name.** This is a string identifier for the certificate. It usually describes the
 *    purpose of the certificate like "Entrust Root Certification Authority - G2" or
 *    "www.squareup.com".
 *
 *  * **A set of hostnames.** These are in the certificate's subject alternative name (SAN)
 *    extension. A subject alternative name is either a literal hostname (`squareup.com`), a literal
 *    IP address (`74.122.190.80`), or a hostname pattern (`*.api.squareup.com`).
 *
 *  * **A validity interval.** A certificate should not be used before its validity interval starts
 *    or after it ends.
 *
 *  * **A public key.** This cryptographic key is used for asymmetric encryption digital signatures.
 *    Note that the private key is not a part of the certificate!
 *
 *  * **A signature issued by another certificate's private key.** This mechanism allows a trusted
 *    third-party to endorse a certificate. Third parties should only endorse certificates once
 *    they've confirmed that the owner of the private key is also the owner of the certificate's
 *    other properties.
 *
 * Certificates are signed by other certificates and a sequence of them is called a certificate
 * chain. The chain terminates in a self-signed "root" certificate. Signing certificates in the
 * middle of the chain are called "intermediates". Organizations that offer certificate signing are
 * called certificate authorities (CAs).
 *
 * Browsers and other HTTP clients need a set of trusted root certificates to authenticate their
 * peers. Sets of root certificates are managed by either the HTTP client (like Firefox), or the
 * host platform (like Android). In July 2018 Android had 134 trusted root certificates for its HTTP
 * clients to trust.
 *
 * For example, in order to establish a secure connection to `https://www.squareup.com/`,
 * these three certificates are used.
 *
 * ```
 * www.squareup.com certificate:
 *
 * Common Name: www.squareup.com
 * Subject Alternative Names: www.squareup.com, squareup.com, account.squareup.com...
 * Validity: 2018-07-03T20:18:17Z – 2019-08-01T20:48:15Z
 * Public Key: d107beecc17325f55da976bcbab207ba4df68bd3f8fce7c3b5850311128264fd53e1baa342f58d93...
 * Signature: 1fb0e66fac05322721fe3a3917f7c98dee1729af39c99eab415f22d8347b508acdf0bab91781c3720...
 *
 * signed by intermediate certificate:
 *
 * Common Name: Entrust Certification Authority - L1M
 * Subject Alternative Names: none
 * Validity: 2014-12-15T15:25:03Z – 2030-10-15T15:55:03Z
 * Public Key: d081c13923c2b1d1ecf757dd55243691202248f7fcca520ab0ab3f33b5b08407f6df4e7ab0fb9822...
 * Signature: b487c784221a29c0a478ecf54f1bb484976f77eed4cf59afa843962f1d58dea6f3155b2ed9439c4c4...
 *
 * signed by root certificate:
 *
 * Common Name: Entrust Root Certification Authority - G2
 * Subject Alternative Names: none
 * Validity: 2009-07-07T17:25:54Z – 2030-12-07T17:55:54Z
 * Public Key: ba84b672db9e0c6be299e93001a776ea32b895411ac9da614e5872cffef68279bf7361060aa527d8...
 * Self-signed Signature: 799f1d96c6b6793f228d87d3870304606a6b9a2e59897311ac43d1f513ff8d392bc0f...
 * ```
 *
 * In this example the HTTP client already knows and trusts the last certificate, "Entrust Root
 * Certification Authority - G2". That certificate is used to verify the signature of the
 * intermediate certificate, "Entrust Certification Authority - L1M". The intermediate certificate
 * is used to verify the signature of the "www.squareup.com" certificate.
 *
 * This roles are reversed for client authentication. In that case the client has a private key and
 * a chain of certificates. The server uses a set of trusted root certificates to authenticate the
 * client. Subject alternative names are not used for client authentication.
 */
@Suppress("DEPRECATION")
class HeldCertificate(
  @get:JvmName("keyPair") val keyPair: KeyPair,
  @get:JvmName("certificate") val certificate: X509Certificate,
) {
  @JvmName("-deprecated_certificate")
  @Deprecated(
    message = "moved to val",
    replaceWith = ReplaceWith(expression = "certificate"),
    level = DeprecationLevel.ERROR,
  )
  fun certificate(): X509Certificate = certificate

  @JvmName("-deprecated_keyPair")
  @Deprecated(
    message = "moved to val",
    replaceWith = ReplaceWith(expression = "keyPair"),
    level = DeprecationLevel.ERROR,
  )
  fun keyPair(): KeyPair = keyPair

  /**
   * Returns the certificate encoded in [PEM format][rfc_7468].
   *
   * [rfc_7468]: https://tools.ietf.org/html/rfc7468
   */
  fun certificatePem(): String = certificate.certificatePem()

  /**
   * Returns the private key encoded in [PKCS #8][rfc_5208] [PEM format][rfc_7468].
   *
   * [rfc_5208]: https://tools.ietf.org/html/rfc5208
   * [rfc_7468]: https://tools.ietf.org/html/rfc7468
   */
  fun privateKeyPkcs8Pem(): String =
    buildString {
      append("-----BEGIN PRIVATE KEY-----\n")
      encodeBase64Lines(keyPair.private.encoded.toByteString())
      append("-----END PRIVATE KEY-----\n")
    }

  /**
   * Returns the RSA private key encoded in [PKCS #1][rfc_8017] [PEM format][rfc_7468].
   *
   * [rfc_8017]: https://tools.ietf.org/html/rfc8017
   * [rfc_7468]: https://tools.ietf.org/html/rfc7468
   */
  fun privateKeyPkcs1Pem(): String {
    check(keyPair.private is RSAPrivateKey) { "PKCS1 only supports RSA keys" }
    return buildString {
      append("-----BEGIN RSA PRIVATE KEY-----\n")
      encodeBase64Lines(pkcs1Bytes())
      append("-----END RSA PRIVATE KEY-----\n")
    }
  }

  private fun pkcs1Bytes(): ByteString {
    val decoded = CertificateAdapters.privateKeyInfo.fromDer(keyPair.private.encoded.toByteString())
    return decoded.privateKey
  }

  /** Build a held certificate with reasonable defaults. */
  class Builder {
    private var notBefore = -1L
    private var notAfter = -1L
    private var commonName: String? = null
    private var organizationalUnit: String? = null
    private val altNames = mutableListOf<String>()
    private var serialNumber: BigInteger? = null
    private var keyPair: KeyPair? = null
    private var signedBy: HeldCertificate? = null
    private var maxIntermediateCas = -1
    private var keyAlgorithm: String? = null
    private var keySize: Int = 0

    init {
      ecdsa256()
    }

    /**
     * Sets the certificate to be valid in ```[notBefore..notAfter]```. Both endpoints are specified
     * in the format of [System.currentTimeMillis]. Specify -1L for both values to use the default
     * interval, 24 hours starting when the certificate is created.
     */
    fun validityInterval(
      notBefore: Long,
      notAfter: Long,
    ) = apply {
      require(notBefore <= notAfter && notBefore == -1L == (notAfter == -1L)) {
        "invalid interval: $notBefore..$notAfter"
      }
      this.notBefore = notBefore
      this.notAfter = notAfter
    }

    /**
     * Sets the certificate to be valid immediately and until the specified duration has elapsed.
     * The precision of this field is seconds; further precision will be truncated.
     */
    fun duration(
      duration: Long,
      unit: TimeUnit,
    ) = apply {
      val now = System.currentTimeMillis()
      validityInterval(now, now + unit.toMillis(duration))
    }

    /**
     * Adds a subject alternative name (SAN) to the certificate. This is usually a literal hostname,
     * a literal IP address, or a hostname pattern. If no subject alternative names are added that
     * extension will be omitted.
     */
    fun addSubjectAlternativeName(altName: String) =
      apply {
        altNames += altName
      }

    /**
     * Set this certificate's common name (CN). Historically this held the hostname of TLS
     * certificate, but that practice was deprecated by [RFC 2818][rfc_2818] and replaced with
     * [addSubjectAlternativeName]. If unset a random string will be used.
     *
     * [rfc_2818]: https://tools.ietf.org/html/rfc2818
     */
    fun commonName(cn: String) =
      apply {
        this.commonName = cn
      }

    /** Sets the certificate's organizational unit (OU). If unset this field will be omitted. */
    fun organizationalUnit(ou: String) =
      apply {
        this.organizationalUnit = ou
      }

    /** Sets this certificate's serial number. If unset the serial number will be 1. */
    fun serialNumber(serialNumber: BigInteger) =
      apply {
        this.serialNumber = serialNumber
      }

    /** Sets this certificate's serial number. If unset the serial number will be 1. */
    fun serialNumber(serialNumber: Long) =
      apply {
        serialNumber(BigInteger.valueOf(serialNumber))
      }

    /**
     * Sets the public/private key pair used for this certificate. If unset a key pair will be
     * generated.
     */
    fun keyPair(keyPair: KeyPair) =
      apply {
        this.keyPair = keyPair
      }

    /**
     * Sets the public/private key pair used for this certificate. If unset a key pair will be
     * generated.
     */
    fun keyPair(
      publicKey: PublicKey,
      privateKey: PrivateKey,
    ) = apply {
      keyPair(KeyPair(publicKey, privateKey))
    }

    /**
     * Set the certificate that will issue this certificate. If unset the certificate will be
     * self-signed.
     */
    fun signedBy(signedBy: HeldCertificate?) =
      apply {
        this.signedBy = signedBy
      }

    /**
     * Set this certificate to be a signing certificate, with up to `maxIntermediateCas`
     * intermediate signing certificates beneath it.
     *
     * By default this certificate cannot not sign other certificates. Set this to 0 so this
     * certificate can sign other certificates (but those certificates cannot themselves sign
     * certificates). Set this to 1 so this certificate can sign intermediate certificates that can
     * themselves sign certificates. Add one for each additional layer of intermediates to permit.
     */
    fun certificateAuthority(maxIntermediateCas: Int) =
      apply {
        require(maxIntermediateCas >= 0) {
          "maxIntermediateCas < 0: $maxIntermediateCas"
        }
        this.maxIntermediateCas = maxIntermediateCas
      }

    /**
     * Configure the certificate to generate a 256-bit ECDSA key, which provides about 128 bits of
     * security. ECDSA keys are noticeably faster than RSA keys.
     *
     * This is the default configuration and has been since this API was introduced in OkHttp
     * 3.11.0. Note that the default may change in future releases.
     */
    fun ecdsa256() =
      apply {
        keyAlgorithm = "EC"
        keySize = 256
      }

    /**
     * Configure the certificate to generate a 2048-bit RSA key, which provides about 112 bits of
     * security. RSA keys are interoperable with very old clients that don't support ECDSA.
     */
    fun rsa2048() =
      apply {
        keyAlgorithm = "RSA"
        keySize = 2048
      }

    /**
     * Configure the certificate to generate an Ed25519 key. Ed25519 provides about 128 bits of
     * security and fast signing/verification. Requires Java 15 or newer at runtime.
     */
    fun ed25519() =
      apply {
        keyAlgorithm = "Ed25519"
        keySize = 0
      }

    fun build(): HeldCertificate {
      // Subject keys & identity.
      val subjectKeyPair = keyPair ?: generateKeyPair()
      val subjectPublicKeyInfo =
        CertificateAdapters.subjectPublicKeyInfo.fromDer(
          subjectKeyPair.public.encoded.toByteString(),
        )
      val subject: List<List<AttributeTypeAndValue>> = subject()

      // Issuer/signer keys & identity. May be the subject if it is self-signed.
      val issuerKeyPair: KeyPair
      val issuer: List<List<AttributeTypeAndValue>>
      if (signedBy != null) {
        issuerKeyPair = signedBy!!.keyPair
        issuer =
          CertificateAdapters.rdnSequence.fromDer(
            signedBy!!
              .certificate.subjectX500Principal.encoded
              .toByteString(),
          )
      } else {
        issuerKeyPair = subjectKeyPair
        issuer = subject
      }
      val signatureAlgorithm = signatureAlgorithm(issuerKeyPair)

      // Subset of certificate data that's covered by the signature.
      val tbsCertificate =
        TbsCertificate(
          // v3:
          version = 2L,
          serialNumber = serialNumber ?: BigInteger.ONE,
          signature = signatureAlgorithm,
          issuer = issuer,
          validity = validity(),
          subject = subject,
          subjectPublicKeyInfo = subjectPublicKeyInfo,
          issuerUniqueID = null,
          subjectUniqueID = null,
          extensions = extensions(),
        )

      // Signature.
      val signature =
        Signature.getInstance(tbsCertificate.signatureAlgorithmName).run {
          initSign(issuerKeyPair.private)
          update(CertificateAdapters.tbsCertificate.toDer(tbsCertificate).toByteArray())
          sign().toByteString()
        }

      // Complete signed certificate.
      val certificate =
        Certificate(
          tbsCertificate = tbsCertificate,
          signatureAlgorithm = signatureAlgorithm,
          signatureValue =
            BitString(
              byteString = signature,
              unusedBitsCount = 0,
            ),
        )

      return HeldCertificate(subjectKeyPair, certificate.toX509Certificate())
    }

    private fun subject(): List<List<AttributeTypeAndValue>> {
      val result = mutableListOf<List<AttributeTypeAndValue>>()

      if (organizationalUnit != null) {
        result +=
          listOf(
            AttributeTypeAndValue(
              type = ORGANIZATIONAL_UNIT_NAME,
              value = organizationalUnit,
            ),
          )
      }

      result +=
        listOf(
          AttributeTypeAndValue(
            type = ObjectIdentifiers.COMMON_NAME,
            value = commonName ?: UUID.randomUUID().toString(),
          ),
        )

      return result
    }

    private fun validity(): Validity {
      val notBefore = if (notBefore != -1L) notBefore else System.currentTimeMillis()
      val notAfter = if (notAfter != -1L) notAfter else notBefore + DEFAULT_DURATION_MILLIS
      return Validity(
        notBefore = notBefore,
        notAfter = notAfter,
      )
    }

    private fun extensions(): MutableList<Extension> {
      val result = mutableListOf<Extension>()

      if (maxIntermediateCas != -1) {
        result +=
          Extension(
            id = BASIC_CONSTRAINTS,
            critical = true,
            value =
              BasicConstraints(
                ca = true,
                maxIntermediateCas = maxIntermediateCas.toLong(),
              ),
          )
      }

      if (altNames.isNotEmpty()) {
        val extensionValue =
          altNames.map {
            when {
              it.canParseAsIpAddress() -> {
                generalNameIpAddress to InetAddress.getByName(it).address.toByteString()
              }

              else -> {
                generalNameDnsName to it
              }
            }
          }
        result +=
          Extension(
            id = SUBJECT_ALTERNATIVE_NAME,
            critical = true,
            value = extensionValue,
          )
      }

      return result
    }

    private fun signatureAlgorithm(signedByKeyPair: KeyPair): AlgorithmIdentifier =
      when {
        signedByKeyPair.private is RSAPrivateKey -> {
          AlgorithmIdentifier(
            algorithm = SHA256_WITH_RSA_ENCRYPTION,
            parameters = null,
          )
        }

        signedByKeyPair.private.algorithm == "Ed25519" || signedByKeyPair.private.algorithm == "EdDSA" -> {
          AlgorithmIdentifier(
            algorithm = ED25519,
            parameters = null,
          )
        }

        else -> {
          AlgorithmIdentifier(
            algorithm = SHA256_WITH_ECDSA,
            parameters = ByteString.EMPTY,
          )
        }
      }

    private fun generateKeyPair(): KeyPair =
      KeyPairGenerator.getInstance(keyAlgorithm).run {
        if (keySize > 0) initialize(keySize, SecureRandom())
        generateKeyPair()
      }

    companion object {
      private const val DEFAULT_DURATION_MILLIS = 1000L * 60 * 60 * 24 // 24 hours.
    }
  }

  companion object {
    private val PEM_REGEX = Regex("""-----BEGIN ([!-,.-~ ]*)-----([^-]*)-----END \1-----""")

    /**
     * Decodes a multiline string that contains both a [certificate][certificatePem] and a
     * [private key][privateKeyPkcs8Pem], both [PEM-encoded][rfc_7468]. A typical input string looks
     * like this:
     *
     * ```
     * -----BEGIN CERTIFICATE-----
     * MIIBYTCCAQegAwIBAgIBKjAKBggqhkjOPQQDAjApMRQwEgYDVQQLEwtlbmdpbmVl
     * cmluZzERMA8GA1UEAxMIY2FzaC5hcHAwHhcNNzAwMTAxMDAwMDA1WhcNNzAwMTAx
     * MDAwMDEwWjApMRQwEgYDVQQLEwtlbmdpbmVlcmluZzERMA8GA1UEAxMIY2FzaC5h
     * cHAwWTATBgcqhkjOPQIBBggqhkjOPQMBBwNCAASda8ChkQXxGELnrV/oBnIAx3dD
     * ocUOJfdz4pOJTP6dVQB9U3UBiW5uSX/MoOD0LL5zG3bVyL3Y6pDwKuYvfLNhoyAw
     * HjAcBgNVHREBAf8EEjAQhwQBAQEBgghjYXNoLmFwcDAKBggqhkjOPQQDAgNIADBF
     * AiAyHHg1N6YDDQiY920+cnI5XSZwEGhAtb9PYWO8bLmkcQIhAI2CfEZf3V/obmdT
     * yyaoEufLKVXhrTQhRfodTeigi4RX
     * -----END CERTIFICATE-----
     * -----BEGIN PRIVATE KEY-----
     * MEECAQAwEwYHKoZIzj0CAQYIKoZIzj0DAQcEJzAlAgEBBCA7ODT0xhGSNn4ESj6J
     * lu/GJQZoU9lDrCPeUcQ28tzOWw==
     * -----END PRIVATE KEY-----
     * ```
     *
     * The string should contain exactly one certificate and one private key in [PKCS #8][rfc_5208]
     * format. It should not contain any other PEM-encoded blocks, but it may contain other text
     * which will be ignored.
     *
     * Encode a held certificate into this format by concatenating the results of
     * [certificatePem()][certificatePem] and [privateKeyPkcs8Pem()][privateKeyPkcs8Pem].
     *
     * [rfc_7468]: https://tools.ietf.org/html/rfc7468
     * [rfc_5208]: https://tools.ietf.org/html/rfc5208
     */
    @JvmStatic
    fun decode(certificateAndPrivateKeyPem: String): HeldCertificate {
      var certificatePem: String? = null
      var pkcs8Base64: String? = null
      for (match in PEM_REGEX.findAll(certificateAndPrivateKeyPem)) {
        when (val label = match.groups[1]!!.value) {
          "CERTIFICATE" -> {
            require(certificatePem == null) { "string includes multiple certificates" }
            certificatePem = match.groups[0]!!.value // Keep --BEGIN-- and --END-- for certificates.
          }

          "PRIVATE KEY" -> {
            require(pkcs8Base64 == null) { "string includes multiple private keys" }
            pkcs8Base64 = match.groups[2]!!.value // Include the contents only for PKCS8.
          }

          else -> {
            throw IllegalArgumentException("unexpected type: $label")
          }
        }
      }
      require(certificatePem != null) { "string does not include a certificate" }
      require(pkcs8Base64 != null) { "string does not include a private key" }

      return decode(certificatePem, pkcs8Base64)
    }

    private fun decode(
      certificatePem: String,
      pkcs8Base64Text: String,
    ): HeldCertificate {
      val certificate = certificatePem.decodeCertificatePem()

      val pkcs8Bytes =
        pkcs8Base64Text.decodeBase64()
          ?: throw IllegalArgumentException("failed to decode private key")

      // The private key doesn't tell us its type but it's okay because the certificate knows!
      val keyType =
        when {
          certificate.publicKey is ECPublicKey -> "EC"
          certificate.publicKey is RSAPublicKey -> "RSA"
          certificate.publicKey.algorithm == "Ed25519" || certificate.publicKey.algorithm == "EdDSA" -> certificate.publicKey.algorithm
          else -> throw IllegalArgumentException("unexpected key type: ${certificate.publicKey}")
        }

      val privateKey = decodePkcs8(pkcs8Bytes, keyType)

      val keyPair = KeyPair(certificate.publicKey, privateKey)
      return HeldCertificate(keyPair, certificate)
    }

    private fun decodePkcs8(
      data: ByteString,
      keyAlgorithm: String,
    ): PrivateKey {
      try {
        val keyFactory = KeyFactory.getInstance(keyAlgorithm)
        return keyFactory.generatePrivate(PKCS8EncodedKeySpec(data.toByteArray()))
      } catch (e: GeneralSecurityException) {
        throw IllegalArgumentException("failed to decode private key", e)
      }
    }
  }
}
