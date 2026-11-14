/*
 * Copyright (C) 2020 Square, Inc.
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
package okhttp3.tls.internal.der

import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isFalse
import assertk.assertions.isNull
import assertk.assertions.isTrue
import java.math.BigInteger
import java.security.KeyFactory
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.X509EncodedKeySpec
import java.text.SimpleDateFormat
import java.util.Date
import java.util.TimeZone
import okhttp3.tls.HeldCertificate
import okhttp3.tls.decodeCertificatePem
import okhttp3.tls.internal.der.ObjectIdentifiers.BASIC_CONSTRAINTS
import okhttp3.tls.internal.der.ObjectIdentifiers.COMMON_NAME
import okhttp3.tls.internal.der.ObjectIdentifiers.ORGANIZATIONAL_UNIT_NAME
import okhttp3.tls.internal.der.ObjectIdentifiers.RSA_ENCRYPTION
import okhttp3.tls.internal.der.ObjectIdentifiers.SHA256_WITH_RSA_ENCRYPTION
import okhttp3.tls.internal.der.ObjectIdentifiers.SUBJECT_ALTERNATIVE_NAME
import okio.Buffer
import okio.ByteString
import okio.ByteString.Companion.decodeBase64
import okio.ByteString.Companion.decodeHex
import okio.ByteString.Companion.encodeUtf8
import okio.ByteString.Companion.toByteString
import org.junit.jupiter.api.Test

internal class DerCertificatesTest {
  private val stateOrProvince = "1.3.6.1.4.1.311.60.2.1.2"
  private val country = "1.3.6.1.4.1.311.60.2.1.3"
  private val certificateTransparencySignedCertificateTimestamps = "1.3.6.1.4.1.11129.2.4.2"
  private val authorityInfoAccess = "1.3.6.1.5.5.7.1.1"
  private val serialNumber = "2.5.4.5"
  private val countryName = "2.5.4.6"
  private val localityName = "2.5.4.7"
  private val stateOrProvinceName = "2.5.4.8"
  private val organizationName = "2.5.4.10"
  private val businessCategory = "2.5.4.15"
  private val subjectKeyIdentifier = "2.5.29.14"
  private val keyUsage = "2.5.29.15"
  private val crlDistributionPoints = "2.5.29.31"
  private val certificatePolicies = "2.5.29.32"
  private val authorityKeyIdentifier = "2.5.29.35"
  private val extendedKeyUsage = "2.5.29.37"

  @Test
  fun `decode simple certificate`() {
    val certificateBase64 =
      """
      |MIIBmjCCAQOgAwIBAgIBATANBgkqhkiG9w0BAQsFADATMREwDwYDVQQDEwhjYXNo
      |LmFwcDAeFw03MDAxMDEwMDAwMDBaFw03MDAxMDEwMDAwMDFaMBMxETAPBgNVBAMT
      |CGNhc2guYXBwMIGfMA0GCSqGSIb3DQEBAQUAA4GNADCBiQKBgQCApFHhtrLan28q
      |+oMolZuaTfWBA0V5aMIvq32BsloQu6LlvX1wJ4YEoUCjDlPOtpht7XLbUmBnbIzN
      |89XK4UJVM6Sqp3K88Km8z7gMrdrfTom/274wL25fICR+yDEQ5fUVYBmJAKXZF1ao
      |I0mIoEx0xFsQhIJ637v2MxJDupd61wIDAQABMA0GCSqGSIb3DQEBCwUAA4GBADam
      |UVwKh5Ry7es3OxtY3IgQunPUoLc0Gw71gl9Z+7t2FJ5VkcI5gWfutmdxZ2bDXCI8
      |8V0vxo1pHXnbBrnxhS/Z3TBerw8RyQqcaWOdp+pBXyIWmR+jHk9cHZCqQveTIBsY
      |jaA9VEhgdaVhxBsT2qzUNDsXlOzGsliznDfoqETb
      |
      """.trimMargin()
    val certificateByteString = certificateBase64.decodeBase64()!!
    val certificatePem =
      """
      |-----BEGIN CERTIFICATE-----
      |$certificateBase64
      |-----END CERTIFICATE-----
      |
      """.trimMargin()

    val javaCertificate = certificatePem.decodeCertificatePem()
    val okHttpCertificate =
      CertificateAdapters.certificate
        .fromDer(certificateByteString)

    assertThat(okHttpCertificate.signatureValue.byteString)
      .isEqualTo(javaCertificate.signature.toByteString())

    val publicKeyBytes =
      """
      3081890281810080a451e1b6b2da9f6f2afa8328959b9a4df58103457968c22fab7d81b25a10bba2e5bd7d70278604
      a140a30e53ceb6986ded72db5260676c8ccdf3d5cae1425533a4aaa772bcf0a9bccfb80caddadf4e89bfdbbe302f6e
      5f20247ec83110e5f51560198900a5d91756a8234988a04c74c45b1084827adfbbf6331243ba977ad70203010001
      """.decodeHex(ignoreWhitespace = true)
    val signatureBytes =
      """
      36a6515c0a879472edeb373b1b58dc8810ba73d4a0b7341b0ef5825f59fbbb76149e5591c2398167eeb667716766c3
      5c223cf15d2fc68d691d79db06b9f1852fd9dd305eaf0f11c90a9c69639da7ea415f2216991fa31e4f5c1d90aa42f7
      93201b188da03d54486075a561c41b13daacd4343b1794ecc6b258b39c37e8a844db
      """.decodeHex(ignoreWhitespace = true)

    assertThat(okHttpCertificate).isEqualTo(
      Certificate(
        tbsCertificate =
          TbsCertificate(
            // v3.
            version = 2L,
            serialNumber = BigInteger.ONE,
            signature =
              AlgorithmIdentifier(
                algorithm = SHA256_WITH_RSA_ENCRYPTION,
                parameters = null,
              ),
            issuer =
              listOf(
                listOf(
                  AttributeTypeAndValue(
                    type = COMMON_NAME,
                    value = "cash.app",
                  ),
                ),
              ),
            validity =
              Validity(
                notBefore = 0L,
                notAfter = 1000L,
              ),
            subject =
              listOf(
                listOf(
                  AttributeTypeAndValue(
                    type = COMMON_NAME,
                    value = "cash.app",
                  ),
                ),
              ),
            subjectPublicKeyInfo =
              SubjectPublicKeyInfo(
                algorithm =
                  AlgorithmIdentifier(
                    algorithm = RSA_ENCRYPTION,
                    parameters = null,
                  ),
                subjectPublicKey =
                  BitString(
                    byteString = publicKeyBytes,
                    unusedBitsCount = 0,
                  ),
              ),
            issuerUniqueID = null,
            subjectUniqueID = null,
            extensions = listOf(),
          ),
        signatureAlgorithm =
          AlgorithmIdentifier(
            algorithm = SHA256_WITH_RSA_ENCRYPTION,
            parameters = null,
          ),
        signatureValue =
          BitString(
            byteString = signatureBytes,
            unusedBitsCount = 0,
          ),
      ),
    )
  }

  @Test
  fun `decode CA certificate`() {
    val certificateBase64 =
      """
      |MIIE/zCCA+egAwIBAgIEUdNARDANBgkqhkiG9w0BAQsFADCBsDELMAkGA1UEBhMC
      |VVMxFjAUBgNVBAoTDUVudHJ1c3QsIEluYy4xOTA3BgNVBAsTMHd3dy5lbnRydXN0
      |Lm5ldC9DUFMgaXMgaW5jb3Jwb3JhdGVkIGJ5IHJlZmVyZW5jZTEfMB0GA1UECxMW
      |KGMpIDIwMDYgRW50cnVzdCwgSW5jLjEtMCsGA1UEAxMkRW50cnVzdCBSb290IENl
      |cnRpZmljYXRpb24gQXV0aG9yaXR5MB4XDTE0MDkyMjE3MTQ1N1oXDTI0MDkyMzAx
      |MzE1M1owgb4xCzAJBgNVBAYTAlVTMRYwFAYDVQQKEw1FbnRydXN0LCBJbmMuMSgw
      |JgYDVQQLEx9TZWUgd3d3LmVudHJ1c3QubmV0L2xlZ2FsLXRlcm1zMTkwNwYDVQQL
      |EzAoYykgMjAwOSBFbnRydXN0LCBJbmMuIC0gZm9yIGF1dGhvcml6ZWQgdXNlIG9u
      |bHkxMjAwBgNVBAMTKUVudHJ1c3QgUm9vdCBDZXJ0aWZpY2F0aW9uIEF1dGhvcml0
      |eSAtIEcyMIIBIjANBgkqhkiG9w0BAQEFAAOCAQ8AMIIBCgKCAQEAuoS2ctueDGvi
      |mekwAad26jK4lUEaydphTlhyz/72gnm/c2EGCqUn2LNf00VOHHLWTjLycooP94MZ
      |0GqAgABFHrDH55q/ElcnHKNoLwqHvWprDl5l8xx31dSFjXAhtLMy54ui1YY5ArG4
      |0kfO5MlJxDun3vtUfVe+8OhuwnmyOgtV4lCYFjITXC94VsHClLPyWuQnmp8k18bs
      |0JslguPMwsRFxYyXegZrKhGfqQpuSDtv29QRGUL3jwe/9VNfnD70FyzmaaxOMkxi
      |d+q36OW7NLwZi66cUee3frVTsTMi5W3PcDwa+uKbZ7aD9I2lr2JMTeBYrGQ0EgP4
      |to2UYySkcQIDAQABo4IBDzCCAQswDgYDVR0PAQH/BAQDAgEGMBIGA1UdEwEB/wQI
      |MAYBAf8CAQEwMwYIKwYBBQUHAQEEJzAlMCMGCCsGAQUFBzABhhdodHRwOi8vb2Nz
      |cC5lbnRydXN0Lm5ldDAzBgNVHR8ELDAqMCigJqAkhiJodHRwOi8vY3JsLmVudHJ1
      |c3QubmV0L3Jvb3RjYTEuY3JsMDsGA1UdIAQ0MDIwMAYEVR0gADAoMCYGCCsGAQUF
      |BwIBFhpodHRwOi8vd3d3LmVudHJ1c3QubmV0L0NQUzAdBgNVHQ4EFgQUanImetAe
      |733nO2lR1GyNn5ASZqswHwYDVR0jBBgwFoAUaJDkZ6SmU4DHhmak8fdLQ/uEvW0w
      |DQYJKoZIhvcNAQELBQADggEBAGkzg/woem99751V68U+ep11s8zDODbZNKIoaBjq
      |HmnTvefQd9q4AINOSs9v0fHBIj905PeYSZ6btp7h25h3LVY0sag82f3Azce/BQPU
      |AsXx5cbaCKUTx2IjEdFhMB1ghEXveajGJpOkt800uGnFE/aRs8lFc3a2kvZ2Clvh
      |A0e36SlMkTIjN0qcNdh4/R0f5IOJJICtt/nP5F2l1HHEhVtwH9s/HAHrGkUmMRTM
      |Zb9n3srMM2XlQZHXN75BGpad5oqXnafOrE6aPb0BoGrZTyIAi0TVaWJ7LuvMuueS
      |fWlnPfy4fN5Bh9Bp6roKGHoalUOzeXEodm2h+1dK7E3IDhA=
      |
      """.trimMargin()
    val certificateByteString = certificateBase64.decodeBase64()!!
    val certificatePem =
      """
      |-----BEGIN CERTIFICATE-----
      |$certificateBase64
      |-----END CERTIFICATE-----
      |
      """.trimMargin()

    val javaCertificate = certificatePem.decodeCertificatePem()
    val okHttpCertificate =
      CertificateAdapters.certificate
        .fromDer(certificateByteString)

    val publicKeyBytes =
      """
      3082010a0282010100ba84b672db9e0c6be299e93001a776ea32b895411ac9da614e5872cffef68279bf7361060aa5
      27d8b35fd3454e1c72d64e32f2728a0ff78319d06a808000451eb0c7e79abf1257271ca3682f0a87bd6a6b0e5e65f3
      1c77d5d4858d7021b4b332e78ba2d5863902b1b8d247cee4c949c43ba7defb547d57bef0e86ec279b23a0b55e25098
      1632135c2f7856c1c294b3f25ae4279a9f24d7c6ecd09b2582e3ccc2c445c58c977a066b2a119fa90a6e483b6fdbd4
      111942f78f07bff5535f9c3ef4172ce669ac4e324c6277eab7e8e5bb34bc198bae9c51e7b77eb553b13322e56dcf70
      3c1afae29b67b683f48da5af624c4de058ac64341203f8b68d946324a4710203010001
      """.decodeHex(ignoreWhitespace = true)
    val signatureBytes =
      """
      693383fc287a6f7def9d55ebc53e7a9d75b3ccc33836d934a2286818ea1e69d3bde7d077dab800834e4acf6fd1f1c1
      223f74e4f798499e9bb69ee1db98772d5634b1a83cd9fdc0cdc7bf0503d402c5f1e5c6da08a513c7622311d161301d
      608445ef79a8c62693a4b7cd34b869c513f691b3c9457376b692f6760a5be10347b7e9294c913223374a9c35d878fd
      1d1fe483892480adb7f9cfe45da5d471c4855b701fdb3f1c01eb1a45263114cc65bf67decacc3365e54191d737be41
      1a969de68a979da7ceac4e9a3dbd01a06ad94f22008b44d569627b2eebccbae7927d69673dfcb87cde4187d069eaba
      0a187a1a9543b3797128766da1fb574aec4dc80e10
      """.decodeHex(ignoreWhitespace = true)

    assertThat(okHttpCertificate.signatureValue.byteString)
      .isEqualTo(javaCertificate.signature.toByteString())

    assertThat(okHttpCertificate).isEqualTo(
      Certificate(
        tbsCertificate =
          TbsCertificate(
            // v3.
            version = 2L,
            serialNumber = BigInteger("1372799044"),
            signature =
              AlgorithmIdentifier(
                algorithm = SHA256_WITH_RSA_ENCRYPTION,
                parameters = null,
              ),
            issuer =
              listOf(
                listOf(
                  AttributeTypeAndValue(
                    type = countryName,
                    value = "US",
                  ),
                ),
                listOf(
                  AttributeTypeAndValue(
                    type = organizationName,
                    value = "Entrust, Inc.",
                  ),
                ),
                listOf(
                  AttributeTypeAndValue(
                    type = ORGANIZATIONAL_UNIT_NAME,
                    value = "www.entrust.net/CPS is incorporated by reference",
                  ),
                ),
                listOf(
                  AttributeTypeAndValue(
                    type = ORGANIZATIONAL_UNIT_NAME,
                    value = "(c) 2006 Entrust, Inc.",
                  ),
                ),
                listOf(
                  AttributeTypeAndValue(
                    type = COMMON_NAME,
                    value = "Entrust Root Certification Authority",
                  ),
                ),
              ),
            validity =
              Validity(
                notBefore = 1411406097000L,
                notAfter = 1727055113000L,
              ),
            subject =
              listOf(
                listOf(
                  AttributeTypeAndValue(
                    type = countryName,
                    value = "US",
                  ),
                ),
                listOf(
                  AttributeTypeAndValue(
                    type = organizationName,
                    value = "Entrust, Inc.",
                  ),
                ),
                listOf(
                  AttributeTypeAndValue(
                    type = ORGANIZATIONAL_UNIT_NAME,
                    value = "See www.entrust.net/legal-terms",
                  ),
                ),
                listOf(
                  AttributeTypeAndValue(
                    type = ORGANIZATIONAL_UNIT_NAME,
                    value = "(c) 2009 Entrust, Inc. - for authorized use only",
                  ),
                ),
                listOf(
                  AttributeTypeAndValue(
                    type = COMMON_NAME,
                    value = "Entrust Root Certification Authority - G2",
                  ),
                ),
              ),
            subjectPublicKeyInfo =
              SubjectPublicKeyInfo(
                algorithm =
                  AlgorithmIdentifier(
                    algorithm = RSA_ENCRYPTION,
                    parameters = null,
                  ),
                subjectPublicKey =
                  BitString(
                    byteString = publicKeyBytes,
                    unusedBitsCount = 0,
                  ),
              ),
            issuerUniqueID = null,
            subjectUniqueID = null,
            extensions =
              listOf(
                Extension(
                  id = keyUsage,
                  critical = true,
                  value = "03020106".decodeHex(),
                ),
                Extension(
                  id = BASIC_CONSTRAINTS,
                  critical = true,
                  value =
                    BasicConstraints(
                      ca = true,
                      maxIntermediateCas = 1L,
                    ),
                ),
                Extension(
                  id = authorityInfoAccess,
                  critical = false,
                  value =
                    """
                    3025302306082b060105050730018617687474703a2f2f6f6373702e656e74727573742e6e6574
                    """.decodeHex(ignoreWhitespace = true),
                ),
                Extension(
                  id = crlDistributionPoints,
                  critical = false,
                  value =
                    """
                    302a3028a026a0248622687474703a2f2f63726c2e656e74727573742e6e65742f726f6f74636131
                    2e63726c
                    """.decodeHex(ignoreWhitespace = true),
                ),
                Extension(
                  id = certificatePolicies,
                  critical = false,
                  value =
                    """
                    303230300604551d20003028302606082b06010505070201161a687474703a2f2f7777772e656e74
                    727573742e6e65742f435053
                    """.decodeHex(ignoreWhitespace = true),
                ),
                Extension(
                  id = subjectKeyIdentifier,
                  critical = false,
                  value = "04146a72267ad01eef7de73b6951d46c8d9f901266ab".decodeHex(),
                ),
                Extension(
                  id = authorityKeyIdentifier,
                  critical = false,
                  value = "301680146890e467a4a65380c78666a4f1f74b43fb84bd6d".decodeHex(),
                ),
              ),
          ),
        signatureAlgorithm =
          AlgorithmIdentifier(
            algorithm = SHA256_WITH_RSA_ENCRYPTION,
            parameters = null,
          ),
        signatureValue =
          BitString(
            byteString = signatureBytes,
            unusedBitsCount = 0,
          ),
      ),
    )
  }

  @Test
  fun `decode typical certificate`() {
    val certificateBase64 =
      """
      |MIIHHTCCBgWgAwIBAgIRAL5oALmpH7l6AAAAAFTRMh0wDQYJKoZIhvcNAQELBQAw
      |gboxCzAJBgNVBAYTAlVTMRYwFAYDVQQKEw1FbnRydXN0LCBJbmMuMSgwJgYDVQQL
      |Ex9TZWUgd3d3LmVudHJ1c3QubmV0L2xlZ2FsLXRlcm1zMTkwNwYDVQQLEzAoYykg
      |MjAxNCBFbnRydXN0LCBJbmMuIC0gZm9yIGF1dGhvcml6ZWQgdXNlIG9ubHkxLjAs
      |BgNVBAMTJUVudHJ1c3QgQ2VydGlmaWNhdGlvbiBBdXRob3JpdHkgLSBMMU0wHhcN
      |MjAwNDEzMTMyNTQ5WhcNMjEwNDEyMTM1NTQ5WjCBxTELMAkGA1UEBhMCVVMxEzAR
      |BgNVBAgTCkNhbGlmb3JuaWExFjAUBgNVBAcTDVNhbiBGcmFuY2lzY28xEzARBgsr
      |BgEEAYI3PAIBAxMCVVMxGTAXBgsrBgEEAYI3PAIBAhMIRGVsYXdhcmUxFTATBgNV
      |BAoTDFNxdWFyZSwgSW5jLjEdMBsGA1UEDxMUUHJpdmF0ZSBPcmdhbml6YXRpb24x
      |EDAOBgNVBAUTBzQ2OTk4NTUxETAPBgNVBAMTCGNhc2guYXBwMIIBIjANBgkqhkiG
      |9w0BAQEFAAOCAQ8AMIIBCgKCAQEAqv2iSwWvb6ys/Ru4LtSz0R4wDaxklrFIGqdJ
      |rxxYdAdLQjyjHyJsfkNQdt2u4JYPRKaRTVYR9VIIeWUx/IjhZhsGPstPMjYT3cN1
      |VsphSDtrRVuxYlmkrvHar0HoadNr1MHd96Ach3g1QJlV8uyUJ7JXpPCNJ8EMiH52
      |n8bVzpjDjXwoYg3oOYvceteA0GJ5VWYACDgfmkeoaN1Cx31O9qcSiUk5AY8HfAnP
      |h20VcrnPo2dJmm7fkUKohIxrMjtpwi5esWhCBZJk50FveKrgdeSe4XxNL7uJPD89
      |SJtKmX7jxoNQSY3mrPssLdadwltUOhzc4Lcmoj4Ob24JxuVw8QIDAQABo4IDDzCC
      |AwswIQYDVR0RBBowGIIIY2FzaC5hcHCCDHd3dy5jYXNoLmFwcDCCAX8GCisGAQQB
      |1nkCBAIEggFvBIIBawFpAHcAVhQGmi/XwuzT9eG9RLI+x0Z2ubyZEVzA75SYVdaJ
      |0N0AAAFxc9MmmwAABAMASDBGAiEAqeWK3uWt9LX1p3l0gPgNxYBB142oqtRMnMBB
      |anTKy2ICIQDrRj7PRsVyXf1QRxgE5MZl6K6XkBKbaXBlAqPpb8z2hQB3AId1v+dZ
      |fPiMQ5lfvfNu/1aNR1Y2/0q1YMG06v9eoIMPAAABcXPTJq0AAAQDAEgwRgIhANRS
      |wAmVQLXhhxbbUTSKIA6P0Q6EmNABCNSJjSK5Q0ItAiEA88hnegYqVaykbbsQSSI0
      |gP/+Odnm/Thso6HEJFXvYGcAdQB9PvL4j/+IVWgkwsDKnlKJeSvFDngJfy5ql2iZ
      |fiLw1wAAAXFz0yazAAAEAwBGMEQCIH4RLAKbk+DbFdHeQO3bmqelXutLSM6MlN34
      |7XEzHpMeAiB4KB48OcjmQ7kBwrxsRwqg7TrQG/F/DyB9wPilq1QacDAOBgNVHQ8B
      |Af8EBAMCBaAwHQYDVR0lBBYwFAYIKwYBBQUHAwEGCCsGAQUFBwMCMGgGCCsGAQUF
      |BwEBBFwwWjAjBggrBgEFBQcwAYYXaHR0cDovL29jc3AuZW50cnVzdC5uZXQwMwYI
      |KwYBBQUHMAKGJ2h0dHA6Ly9haWEuZW50cnVzdC5uZXQvbDFtLWNoYWluMjU2LmNl
      |cjAzBgNVHR8ELDAqMCigJqAkhiJodHRwOi8vY3JsLmVudHJ1c3QubmV0L2xldmVs
      |MW0uY3JsMEoGA1UdIARDMEEwNgYKYIZIAYb6bAoBAjAoMCYGCCsGAQUFBwIBFhpo
      |dHRwOi8vd3d3LmVudHJ1c3QubmV0L3JwYTAHBgVngQwBATAfBgNVHSMEGDAWgBTD
      |99C1KjCtrw2RIXA5VN28iXDHOjAdBgNVHQ4EFgQUdf0kwt9ZJZnjLzNz4YwEUN0b
      |h7YwCQYDVR0TBAIwADANBgkqhkiG9w0BAQsFAAOCAQEAYLX6TSuQqSAEu37pJ+au
      |9IlRiAEhtdybxr3mhuII0zImejhLuo2knO2SD59avCDBPivITsSvh2aewOUmeKj1
      |GYI7v16xCOCTQz3k31sCAX2L7DozHtbrY4wG7hUSA9dSv/aYJEtebkwim3lgHwv3
      |NHA3iiW3raH1DPJThQmxFJrnT1zL0LQbM1nRQMXaBVfQEEhIYnrU672x6D/cya6r
      |5UwWye3TOZCH0Lh+YaZqtuKx9lEIEXaxjD3jpGlwRLuE/fI6fXg+0kMvaqNVLmpN
      |aJT7WeHs5bkf0dU7rtDefr0iKeqIxrlURPgbeWZF8GAkpdNaCwWMDAFO8DG04K+t
      |Aw==
      |
      """.trimMargin()
    val certificateByteString = certificateBase64.decodeBase64()!!
    val certificatePem =
      """
      |-----BEGIN CERTIFICATE-----
      |$certificateBase64
      |-----END CERTIFICATE-----
      |
      """.trimMargin()

    val javaCertificate = certificatePem.decodeCertificatePem()
    val okHttpCertificate =
      CertificateAdapters.certificate
        .fromDer(certificateByteString)

    val publicKeyBytes =
      """
      3082010a0282010100aafda24b05af6facacfd1bb82ed4b3d11e300dac6496b1481aa749af1c5874074b423ca31f22
      6c7e435076ddaee0960f44a6914d5611f55208796531fc88e1661b063ecb4f323613ddc37556ca61483b6b455bb162
      59a4aef1daaf41e869d36bd4c1ddf7a01c877835409955f2ec9427b257a4f08d27c10c887e769fc6d5ce98c38d7c28
      620de8398bdc7ad780d0627955660008381f9a47a868dd42c77d4ef6a712894939018f077c09cf876d1572b9cfa367
      499a6edf9142a8848c6b323b69c22e5eb16842059264e7416f78aae075e49ee17c4d2fbb893c3f3d489b4a997ee3c6
      8350498de6acfb2c2dd69dc25b543a1cdce0b726a23e0e6f6e09c6e570f10203010001
      """.decodeHex(ignoreWhitespace = true)
    val signatureBytes =
      """
      60b5fa4d2b90a92004bb7ee927e6aef48951880121b5dc9bc6bde686e208d332267a384bba8da49ced920f9f5abc20
      c13e2bc84ec4af87669ec0e52678a8f519823bbf5eb108e093433de4df5b02017d8bec3a331ed6eb638c06ee151203
      d752bff698244b5e6e4c229b79601f0bf73470378a25b7ada1f50cf2538509b1149ae74f5ccbd0b41b3359d140c5da
      0557d0104848627ad4ebbdb1e83fdcc9aeabe54c16c9edd3399087d0b87e61a66ab6e2b1f651081176b18c3de3a469
      7044bb84fdf23a7d783ed2432f6aa3552e6a4d6894fb59e1ece5b91fd1d53baed0de7ebd2229ea88c6b95444f81b79
      6645f06024a5d35a0b058c0c014ef031b4e0afad03
      """.decodeHex(ignoreWhitespace = true)

    assertThat(okHttpCertificate.signatureValue.byteString)
      .isEqualTo(javaCertificate.signature.toByteString())

    assertThat(okHttpCertificate).isEqualTo(
      Certificate(
        tbsCertificate =
          TbsCertificate(
            // v3.
            version = 2L,
            serialNumber = BigInteger("253093332781973022312510445874391888413"),
            signature =
              AlgorithmIdentifier(
                algorithm = SHA256_WITH_RSA_ENCRYPTION,
                parameters = null,
              ),
            issuer =
              listOf(
                listOf(
                  AttributeTypeAndValue(
                    type = countryName,
                    value = "US",
                  ),
                ),
                listOf(
                  AttributeTypeAndValue(
                    type = organizationName,
                    value = "Entrust, Inc.",
                  ),
                ),
                listOf(
                  AttributeTypeAndValue(
                    type = ORGANIZATIONAL_UNIT_NAME,
                    value = "See www.entrust.net/legal-terms",
                  ),
                ),
                listOf(
                  AttributeTypeAndValue(
                    type = ORGANIZATIONAL_UNIT_NAME,
                    value = "(c) 2014 Entrust, Inc. - for authorized use only",
                  ),
                ),
                listOf(
                  AttributeTypeAndValue(
                    type = COMMON_NAME,
                    value = "Entrust Certification Authority - L1M",
                  ),
                ),
              ),
            validity =
              Validity(
                notBefore = 1586784349000L,
                notAfter = 1618235749000L,
              ),
            subject =
              listOf(
                listOf(
                  AttributeTypeAndValue(
                    type = countryName,
                    value = "US",
                  ),
                ),
                listOf(
                  AttributeTypeAndValue(
                    type = stateOrProvinceName,
                    value = "California",
                  ),
                ),
                listOf(
                  AttributeTypeAndValue(
                    type = localityName,
                    value = "San Francisco",
                  ),
                ),
                listOf(
                  AttributeTypeAndValue(
                    type = country,
                    value = "US",
                  ),
                ),
                listOf(
                  AttributeTypeAndValue(
                    type = stateOrProvince,
                    value = "Delaware",
                  ),
                ),
                listOf(
                  AttributeTypeAndValue(
                    type = organizationName,
                    value = "Square, Inc.",
                  ),
                ),
                listOf(
                  AttributeTypeAndValue(
                    type = businessCategory,
                    value = "Private Organization",
                  ),
                ),
                listOf(
                  AttributeTypeAndValue(
                    type = serialNumber,
                    value = "4699855",
                  ),
                ),
                listOf(
                  AttributeTypeAndValue(
                    type = COMMON_NAME,
                    value = "cash.app",
                  ),
                ),
              ),
            subjectPublicKeyInfo =
              SubjectPublicKeyInfo(
                algorithm =
                  AlgorithmIdentifier(
                    algorithm = RSA_ENCRYPTION,
                    parameters = null,
                  ),
                subjectPublicKey =
                  BitString(
                    byteString = publicKeyBytes,
                    unusedBitsCount = 0,
                  ),
              ),
            issuerUniqueID = null,
            subjectUniqueID = null,
            extensions =
              listOf(
                Extension(
                  id = SUBJECT_ALTERNATIVE_NAME,
                  critical = false,
                  value =
                    listOf(
                      CertificateAdapters.generalNameDnsName to "cash.app",
                      CertificateAdapters.generalNameDnsName to "www.cash.app",
                    ),
                ),
                Extension(
                  id = certificateTransparencySignedCertificateTimestamps,
                  critical = false,
                  value =
                    (
                      "0482016b01690077005614069a2fd7c2ecd3f5e1bd44b23ec74676b9bc9" +
                        "9115cc0ef949855d689d0dd0000017173d3269b0000040300483046022100a9e58ad" +
                        "ee5adf4b5f5a7797480f80dc58041d78da8aad44c9cc0416a74cacb62022100eb463" +
                        "ecf46c5725dfd50471804e4c665e8ae9790129b69706502a3e96fccf685007700877" +
                        "5bfe7597cf88c43995fbdf36eff568d475636ff4ab560c1b4eaff5ea0830f0000017" +
                        "173d326ad0000040300483046022100d452c0099540b5e18716db51348a200e8fd10" +
                        "e8498d00108d4898d22b943422d022100f3c8677a062a55aca46dbb1049223480fff" +
                        "e39d9e6fd386ca3a1c42455ef60670075007d3ef2f88fff88556824c2c0ca9e52897" +
                        "92bc50e78097f2e6a9768997e22f0d70000017173d326b3000004030046304402207" +
                        "e112c029b93e0db15d1de40eddb9aa7a55eeb4b48ce8c94ddf8ed71331e931e02207" +
                        "8281e3c39c8e643b901c2bc6c470aa0ed3ad01bf17f0f207dc0f8a5ab541a70"
                    ).decodeHex(),
                ),
                Extension(
                  id = keyUsage,
                  critical = true,
                  value = "030205a0".decodeHex(),
                ),
                Extension(
                  id = extendedKeyUsage,
                  critical = false,
                  value = "301406082b0601050507030106082b06010505070302".decodeHex(),
                ),
                Extension(
                  id = authorityInfoAccess,
                  critical = false,
                  value =
                    """
                    305a302306082b060105050730018617687474703a2f2f6f6373702e656e74727573742e6e657430
                    3306082b060105050730028627687474703a2f2f6169612e656e74727573742e6e65742f6c316d2d
                    636861696e3235362e636572
                    """.decodeHex(ignoreWhitespace = true),
                ),
                Extension(
                  id = crlDistributionPoints,
                  critical = false,
                  value =
                    """
                    302a3028a026a0248622687474703a2f2f63726c2e656e74727573742e6e65742f6c6576656c316d
                    2e63726c
                    """.decodeHex(ignoreWhitespace = true),
                ),
                Extension(
                  id = certificatePolicies,
                  critical = false,
                  value =
                    """
                    30413036060a6086480186fa6c0a01023028302606082b06010505070201161a687474703a2f2f77
                    77772e656e74727573742e6e65742f7270613007060567810c0101
                    """.decodeHex(ignoreWhitespace = true),
                ),
                Extension(
                  id = authorityKeyIdentifier,
                  critical = false,
                  value = ("30168014c3f7d0b52a30adaf0d9121703954ddbc8970c73a").decodeHex(),
                ),
                Extension(
                  id = subjectKeyIdentifier,
                  critical = false,
                  value = "041475fd24c2df592599e32f3373e18c0450dd1b87b6".decodeHex(),
                ),
                Extension(
                  id = BASIC_CONSTRAINTS,
                  critical = false,
                  value =
                    BasicConstraints(
                      ca = false,
                      maxIntermediateCas = null,
                    ),
                ),
              ),
          ),
        signatureAlgorithm =
          AlgorithmIdentifier(
            algorithm = SHA256_WITH_RSA_ENCRYPTION,
            parameters = null,
          ),
        signatureValue =
          BitString(
            byteString = signatureBytes,
            unusedBitsCount = 0,
          ),
      ),
    )
  }

  @Test
  fun `certificate attributes`() {
    val certificate =
      HeldCertificate
        .Builder()
        .certificateAuthority(3)
        .commonName("Jurassic Park")
        .organizationalUnit("Gene Research")
        .addSubjectAlternativeName("*.example.com")
        .addSubjectAlternativeName("www.example.org")
        .validityInterval(-1000L, 2000L)
        .serialNumber(17L)
        .build()

    val certificateByteString = certificate.certificate.encoded.toByteString()

    val okHttpCertificate =
      CertificateAdapters.certificate
        .fromDer(certificateByteString)

    assertThat(okHttpCertificate.basicConstraints).isEqualTo(
      Extension(
        id = BASIC_CONSTRAINTS,
        critical = true,
        value = BasicConstraints(true, 3),
      ),
    )
    assertThat(okHttpCertificate.commonName).isEqualTo("Jurassic Park")
    assertThat(okHttpCertificate.organizationalUnitName).isEqualTo("Gene Research")
    assertThat(okHttpCertificate.subjectAlternativeNames).isEqualTo(
      Extension(
        id = SUBJECT_ALTERNATIVE_NAME,
        critical = true,
        value =
          listOf(
            CertificateAdapters.generalNameDnsName to "*.example.com",
            CertificateAdapters.generalNameDnsName to "www.example.org",
          ),
      ),
    )
    assertThat(okHttpCertificate.tbsCertificate.validity).isEqualTo(Validity(-1000L, 2000L))
    assertThat(okHttpCertificate.tbsCertificate.serialNumber).isEqualTo(BigInteger("17"))
  }

  @Test
  fun `missing subject alternative names`() {
    val certificate =
      HeldCertificate
        .Builder()
        .certificateAuthority(3)
        .commonName("Jurassic Park")
        .organizationalUnit("Gene Research")
        .validityInterval(-1000L, 2000L)
        .serialNumber(17L)
        .build()

    val certificateByteString = certificate.certificate.encoded.toByteString()

    val okHttpCertificate =
      CertificateAdapters.certificate
        .fromDer(certificateByteString)

    assertThat(okHttpCertificate.commonName).isEqualTo("Jurassic Park")
    assertThat(okHttpCertificate.subjectAlternativeNames).isNull()
  }

  @Test
  fun `public key`() {
    val publicKeyBytes =
      (
        "MIGJAoGBAICkUeG2stqfbyr6gyiVm5pN9YEDRXlowi+rfYGyWhC7ouW9fXAnhgShQKMOU8" +
          "62mG3tcttSYGdsjM3z1crhQlUzpKqncrzwqbzPuAyt2t9Oib/bvjAvbl8gJH7IMRDl9RVgGYkApdkXVqgjSYigTH" +
          "TEWxCEgnrfu/YzEkO6l3rXAgMBAAE="
      ).decodeBase64()!!
    val privateKeyBytes =
      (
        "MIICdQIBADANBgkqhkiG9w0BAQEFAASCAl8wggJbAgEAAoGBAICkUeG2stqfbyr6gyiVm" +
          "5pN9YEDRXlowi+rfYGyWhC7ouW9fXAnhgShQKMOU862mG3tcttSYGdsjM3z1crhQlUzpKqncrzwqbzPuAyt2t9Oi" +
          "b/bvjAvbl8gJH7IMRDl9RVgGYkApdkXVqgjSYigTHTEWxCEgnrfu/YzEkO6l3rXAgMBAAECgYB99mhnB6piADOud" +
          "dXv626NzUBTr4xbsYRTgSxHzwf50oFTTBSDuW+1IOBVyTWu94SSPyt0LllPbC8Di3sQSTnVGpSqAvEXknBMzIc0U" +
          "O74Rn9p3gZjEenPt1l77fIBa2nK06/rdsJCoE/1P1JSfM9w7LU1RsTmseYMLeJl5F79gQJBAO/BbAKqg1yzK7Vij" +
          "ygvBoUrr+rt2lbmKgcUQ/rxu8IIQk0M/xgJqSkXDXuOnboGM7sQSKfJAZUtT7xozvLzV7ECQQCJW59w7NIM0qZ/g" +
          "IX2gcNZr1B/V3zcGlolTDciRm+fnKGNt2EEDKnVL3swzbEfTCa48IT0QKgZJqpXZERa26UHAkBLXmiP5f5pk8F3w" +
          "cXzAeVw06z3k1IB41Tu6MX+CyPU+TeudRlz+wV8b0zDvK+EnRKCCbptVFj1Bkt8lQ4JfcnhAkAk2Y3Gz+HySrkcT" +
          "7Cg12M/NkdUQnZe3jr88pt/+IGNwomc6Wt/mJ4fcWONTkGMcfOZff1NQeNXDAZ6941XCsIVAkASOg02PlVHLidU7" +
          "mIE65swMM5/RNhS4aFjez/MwxFNOHaxc9VgCwYPXCLOtdf7AVovdyG0XWgbUXH+NyxKwboE"
      ).decodeBase64()!!

    val x509PublicKey =
      encodeKey(
        algorithm = RSA_ENCRYPTION,
        publicKeyBytes = publicKeyBytes,
      )
    val keyFactory = KeyFactory.getInstance("RSA")
    val publicKey = keyFactory.generatePublic(X509EncodedKeySpec(x509PublicKey.toByteArray()))
    val privateKey = keyFactory.generatePrivate(PKCS8EncodedKeySpec(privateKeyBytes.toByteArray()))

    val certificate =
      HeldCertificate
        .Builder()
        .keyPair(publicKey, privateKey)
        .build()

    val certificateByteString = certificate.certificate.encoded.toByteString()

    val okHttpCertificate =
      CertificateAdapters.certificate
        .fromDer(certificateByteString)

    assertThat(okHttpCertificate.tbsCertificate.subjectPublicKeyInfo.subjectPublicKey)
      .isEqualTo(BitString(publicKeyBytes, 0))
  }

  @Test fun `time before 2050 uses UTC_TIME`() {
    val utcTimeDer = "170d3439313233313233353935395a".decodeHex()

    val decoded = CertificateAdapters.time.fromDer(utcTimeDer)
    val encoded = CertificateAdapters.time.toDer(decoded)

    assertThat(decoded).isEqualTo(date("2049-12-31T23:59:59.000+0000").time)
    assertThat(encoded).isEqualTo(utcTimeDer)
  }

  @Test fun `time not before 2050 uses GENERALIZED_TIME`() {
    val generalizedTimeDer = "180f32303530303130313030303030305a".decodeHex()

    val decoded = CertificateAdapters.time.fromDer(generalizedTimeDer)
    val encoded = CertificateAdapters.time.toDer(decoded)

    assertThat(decoded).isEqualTo(date("2050-01-01T00:00:00.000+0000").time)
    assertThat(encoded).isEqualTo(generalizedTimeDer)
  }

  /**
   * Conforming applications MUST be able to process validity dates that are encoded in either
   * UTCTime or GeneralizedTime.
   */
  @Test fun `can read GENERALIZED_TIME before 2050`() {
    val generalizedTimeDer = "180f32303439313233313233353935395a".decodeHex()

    val decoded = CertificateAdapters.time.fromDer(generalizedTimeDer)
    assertThat(decoded).isEqualTo(date("2049-12-31T23:59:59.000+0000").time)
  }

  @Test fun `time before 1950 uses GENERALIZED_TIME`() {
    val generalizedTimeDer = "180f31393439313233313233353935395a".decodeHex()

    val decoded = CertificateAdapters.time.fromDer(generalizedTimeDer)
    val encoded = CertificateAdapters.time.toDer(decoded)

    assertThat(decoded).isEqualTo(date("1949-12-31T23:59:59.000+0000").time)
    assertThat(encoded).isEqualTo(generalizedTimeDer)
  }

  @Test
  fun `reencode golden EC certificate`() {
    val certificateByteString =
      (
        "MIIBkjCCATmgAwIBAgIBETAKBggqhkjOPQQDAjAwMRYwFAYDVQQLDA1HZW5lIFJ" +
          "lc2VhcmNoMRYwFAYDVQQDDA1KdXJhc3NpYyBQYXJrMB4XDTY5MTIzMTIzNTk1OVoXDTcwMDEwMTAwMDAwMlowMDE" +
          "WMBQGA1UECwwNR2VuZSBSZXNlYXJjaDEWMBQGA1UEAwwNSnVyYXNzaWMgUGFyazBZMBMGByqGSM49AgEGCCqGSM4" +
          "9AwEHA0IABKzhiMzpN+BkUSPLIKItu6O2iao2Pd7dxrvPdIs4xv9/2tPCVgUxevZ27qRcqZOnSd31ZP6B04vkXag" +
          "/awy2/iujRDBCMBIGA1UdEwEB/wQIMAYBAf8CAQMwLAYDVR0RAQH/BCIwIIINKi5leGFtcGxlLmNvbYIPd3d3LmV" +
          "4YW1wbGUub3JnMAoGCCqGSM49BAMCA0cAMEQCIHzutN/uzViLBXZ0slMqO5oz7ghgBgDbgo2ZyroVeQ/KAiB6Vqo" +
          "QXETXce4IZyv3mwGWYePlXU2yMXtezbNluXqUxQ=="
      ).decodeBase64()!!

    val decoded = CertificateAdapters.certificate.fromDer(certificateByteString)
    val encoded = CertificateAdapters.certificate.toDer(decoded)

    assertThat(encoded).isEqualTo(certificateByteString)
  }

  @Test
  fun `reencode golden RSA certificate`() {
    val certificateByteString =
      (
        "MIIDHzCCAgegAwIBAgIBETANBgkqhkiG9w0BAQsFADAwMRYwFAYDVQQLDA1HZW5" +
          "lIFJlc2VhcmNoMRYwFAYDVQQDDA1KdXJhc3NpYyBQYXJrMB4XDTY5MTIzMTIzNTk1OVoXDTcwMDEwMTAwMDAwMlo" +
          "wMDEWMBQGA1UECwwNR2VuZSBSZXNlYXJjaDEWMBQGA1UEAwwNSnVyYXNzaWMgUGFyazCCASIwDQYJKoZIhvcNAQE" +
          "BBQADggEPADCCAQoCggEBAMfROxfCzmxIX5bDSZt6hstXALVeiywFFzTLW5UI0eKSDCliojmiKBcGR5ln7gVe6/t" +
          "me35J9n+Xe5LLmRogMo1CxCoyJxuDX4RrTpPGSepJCrvsBaMA7bQXc/9SbckPF4DYGbE5j3L6IyFU++8RKep/xjc" +
          "FAK4yhEgriDh7Gb+sbG6Mv2qTO4p6TR9WhMKXhMgHdk1JYyaSsJ+tSruKiPVmMAcQLBWgNez6MUIC1WVDyvCvfXI" +
          "pgsxosVCMtEDSllYe2lVta5tq1RkyzrvkazMEROK+0CVTfg8CadyBn83WTdWRsAX3qiwng8fQU3R4D9HuF/monfH" +
          "XuHsr53J+6v8CAwEAAaNEMEIwEgYDVR0TAQH/BAgwBgEB/wIBAzAsBgNVHREBAf8EIjAggg0qLmV4YW1wbGUuY29" +
          "tgg93d3cuZXhhbXBsZS5vcmcwDQYJKoZIhvcNAQELBQADggEBAC/+HbZBfVzazPARyI90ot3wzyEmCnXEotNhyl3" +
          "0QHZ6UGtJwvVBqY187xg9whytqdMFmadCp8FQT/dLRUn27gtQLOju4FfA3yetJ5oWjbgkaAr7YGP7Auz3o+w51aa" +
          "YpseFTZ/zABwnADSiHCIl35TGZJa1XOl32+RWn9VhT92zm3R12FMBovpMFaDckSJAi0jhMHm/QsFK66V0DZxdvl9" +
          "LX/UI7q870lojkolCmDJfftAnd2eazoY/O3TqP/duRH522U+C42nXRg9y0CFgzVWmee4EzsCHhkeHUDbsijgSHd4" +
          "vjraGi943vN59SjQrflkISUnOqChOaWP0oSztRUA="
      ).decodeBase64()!!

    val decoded = CertificateAdapters.certificate.fromDer(certificateByteString)
    val encoded = CertificateAdapters.certificate.toDer(decoded)

    assertThat(encoded).isEqualTo(certificateByteString)
  }

  @Test
  fun `private key info`() {
    val privateKeyInfoByteString =
      (
        "MIICdQIBADANBgkqhkiG9w0BAQEFAASCAl8wggJbAgEAAoGBAICkUeG2stqf" +
          "byr6gyiVm5pN9YEDRXlowi+rfYGyWhC7ouW9fXAnhgShQKMOU862mG3tcttSYGdsjM3z1crhQlUzpKqncrzwqbzP" +
          "uAyt2t9Oib/bvjAvbl8gJH7IMRDl9RVgGYkApdkXVqgjSYigTHTEWxCEgnrfu/YzEkO6l3rXAgMBAAECgYB99mhn" +
          "B6piADOuddXv626NzUBTr4xbsYRTgSxHzwf50oFTTBSDuW+1IOBVyTWu94SSPyt0LllPbC8Di3sQSTnVGpSqAvEX" +
          "knBMzIc0UO74Rn9p3gZjEenPt1l77fIBa2nK06/rdsJCoE/1P1JSfM9w7LU1RsTmseYMLeJl5F79gQJBAO/BbAKq" +
          "g1yzK7VijygvBoUrr+rt2lbmKgcUQ/rxu8IIQk0M/xgJqSkXDXuOnboGM7sQSKfJAZUtT7xozvLzV7ECQQCJW59w" +
          "7NIM0qZ/gIX2gcNZr1B/V3zcGlolTDciRm+fnKGNt2EEDKnVL3swzbEfTCa48IT0QKgZJqpXZERa26UHAkBLXmiP" +
          "5f5pk8F3wcXzAeVw06z3k1IB41Tu6MX+CyPU+TeudRlz+wV8b0zDvK+EnRKCCbptVFj1Bkt8lQ4JfcnhAkAk2Y3G" +
          "z+HySrkcT7Cg12M/NkdUQnZe3jr88pt/+IGNwomc6Wt/mJ4fcWONTkGMcfOZff1NQeNXDAZ6941XCsIVAkASOg02" +
          "PlVHLidU7mIE65swMM5/RNhS4aFjez/MwxFNOHaxc9VgCwYPXCLOtdf7AVovdyG0XWgbUXH+NyxKwboE"
      ).decodeBase64()!!

    val decoded = CertificateAdapters.privateKeyInfo.fromDer(privateKeyInfoByteString)

    assertThat(decoded.version).isEqualTo(0L)
    assertThat(decoded.algorithmIdentifier).isEqualTo(AlgorithmIdentifier(RSA_ENCRYPTION, null))
    assertThat(decoded.privateKey.size).isEqualTo(607)

    val encoded = CertificateAdapters.privateKeyInfo.toDer(decoded)
    assertThat(encoded).isEqualTo(privateKeyInfoByteString)
  }

  @Test
  fun `RSA issuer and signature`() {
    val root =
      HeldCertificate
        .Builder()
        .certificateAuthority(0)
        .rsa2048()
        .build()
    val certificate =
      HeldCertificate
        .Builder()
        .signedBy(root)
        .rsa2048()
        .build()

    val certificateByteString = certificate.certificate.encoded.toByteString()

    // Valid signature.
    val okHttpCertificate =
      CertificateAdapters.certificate
        .fromDer(certificateByteString)
    println(okHttpCertificate)
    assertThat(okHttpCertificate.checkSignature(root.keyPair.public)).isTrue()

    // Invalid signature.
    val okHttpCertificateWithBadSignature =
      okHttpCertificate.copy(
        signatureValue =
          okHttpCertificate.signatureValue.copy(
            byteString = okHttpCertificate.signatureValue.byteString.offByOneBit(),
          ),
      )
    assertThat(okHttpCertificateWithBadSignature.checkSignature(root.keyPair.public)).isFalse()

    // Wrong public key.
    assertThat(okHttpCertificate.checkSignature(certificate.keyPair.public)).isFalse()
  }

  @Test
  fun `EC issuer and signature`() {
    val root =
      HeldCertificate
        .Builder()
        .certificateAuthority(0)
        .ecdsa256()
        .build()
    val certificate =
      HeldCertificate
        .Builder()
        .signedBy(root)
        .ecdsa256()
        .build()

    val certificateByteString = certificate.certificate.encoded.toByteString()

    // Valid signature.
    val okHttpCertificate =
      CertificateAdapters.certificate
        .fromDer(certificateByteString)
    assertThat(okHttpCertificate.checkSignature(root.keyPair.public)).isTrue()

    // Invalid signature.
    val okHttpCertificateWithBadSignature =
      okHttpCertificate.copy(
        signatureValue =
          okHttpCertificate.signatureValue.copy(
            byteString = okHttpCertificate.signatureValue.byteString.offByOneBit(),
          ),
      )
    assertThat(okHttpCertificateWithBadSignature.checkSignature(root.keyPair.public)).isFalse()

    // Wrong public key.
    assertThat(okHttpCertificate.checkSignature(certificate.keyPair.public)).isFalse()
  }

  /**
   * We don't have API support for rfc822Name values (email addresses) in the subject alternative
   * name, but we don't crash either.
   */
  @Test
  fun `unsupported general name tag`() {
    val certificateByteString =
      (
        "MIIFEDCCA/igAwIBAgIRAJK4dE9xztDibHKj2NXZJbIwDQYJKoZIhvcNAQELBQA" +
          "wSDELMAkGA1UEBhMCVVMxIDAeBgNVBAoTF1NlY3VyZVRydXN0IENvcnBvcmF0aW9uMRcwFQYDVQQDEw5TZWN1cmV" +
          "UcnVzdCBDQTAeFw0xNjA5MDExNDM1MzVaFw0yNDA5MjkxNDM1MzVaMIG1MQswCQYDVQQGEwJVUzERMA8GA1UECBM" +
          "ISWxsaW5vaXMxEDAOBgNVBAcTB0NoaWNhZ28xITAfBgNVBAoTGFRydXN0d2F2ZSBIb2xkaW5ncywgSW5jLjE9MDs" +
          "GA1UEAxM0VHJ1c3R3YXZlIE9yZ2FuaXphdGlvbiBWYWxpZGF0aW9uIFNIQTI1NiBDQSwgTGV2ZWwgMTEfMB0GCSq" +
          "GSIb3DQEJARYQY2FAdHJ1c3R3YXZlLmNvbTCCASIwDQYJKoZIhvcNAQEBBQADggEPADCCAQoCggEBAOPTqIZSRwS" +
          "f47okE5omzktvKR7wgqdWzznOnpUOtgwmBPwNeCV1LSPMmlHPZxY4enTc0eyoxTxKv6g6ZUJe39U74eYnwTTT9sE" +
          "OnvNtE1pTzuB4Uf+YOPt4hZidTe5Ba8Q6dfz/Ht/vZXCbF3JFwrXxZEPbJaICap2grIqYHax+IEIYnBQC+WKh8Ng" +
          "Cn3LWS0j6cYSN8SEjFf5SEMGT1iNtttb/QC3JKJIeaVunUyvMfMjVFMntc7eZrFs6rp3wY1WFVI+fy17uOoUvfTH" +
          "8bvNAESUch7FyLh2zM8FVxqilT2XygHRwZeXtxJQozcDcvh4ItPb0uz6AFIYwn/8Gzp0CAwEAAaOCAYUwggGBMBI" +
          "GA1UdEwEB/wQIMAYBAf8CAQAwHQYDVR0OBBYEFMrOHRgDdx4c83xYsppwqAiAFvSuMA4GA1UdDwEB/wQEAwIBhjA" +
          "yBgNVHR8EKzApMCegJaAjhiFodHRwOi8vY3JsLnRydXN0d2F2ZS5jb20vU1RDQS5jcmwwPQYDVR0gBDYwNDAyBgR" +
          "VHSAAMCowKAYIKwYBBQUHAgEWHGh0dHBzOi8vc3NsLnRydXN0d2F2ZS5jb20vQ0EwbAYIKwYBBQUHAQEEYDBeMCU" +
          "GCCsGAQUFBzABhhlodHRwOi8vb2NzcC50cnVzdHdhdmUuY29tMDUGCCsGAQUFBzAChilodHRwOi8vc3NsLnRydXN" +
          "0d2F2ZS5jb20vaXNzdWVycy9TVENBLmNydDAdBgNVHSUEFjAUBggrBgEFBQcDAgYIKwYBBQUHAwEwHwYDVR0jBBg" +
          "wFoAUQjK2FvoE/f5dS3rD/fdMQB1aQ68wGwYDVR0RBBQwEoEQY2FAdHJ1c3R3YXZlLmNvbTANBgkqhkiG9w0BAQs" +
          "FAAOCAQEAC0OvN7/UJBcRDXchA4b2qJo7mBD05+XR96N7vucMaanz26CnUxs1o8DcBckpqyEXCxdOanIr+/UJNbB" +
          "LXLJCzNLJEJcgV9TjbVu33eQR23yMuXD+cZsqLMF+L5IIM47W8dlwKJvMy0xs7Jb1S3NOIhcoVu+XPzRsgKv8Yi2" +
          "B6l278RfzegiCx4vYJv0pBjFzizEiFH9bWTYIOlIJJSM57hoICgjCTS8BoEgndwWIyc/nEmlYaUwmCo9QynY+UmW" +
          "1WPWmVITEJPMdMK6AZqvvaWmuHJ6/vURaz+Hoc5D3z0yJDDCkv52bXV04ZoF6cbcWry7JvNA+djvay/4BRR4SZQ=="
      ).decodeBase64()!!

    val decoded = CertificateAdapters.certificate.fromDer(certificateByteString)
    assertThat(decoded.subjectAlternativeNames).isEqualTo(
      Extension(
        id = SUBJECT_ALTERNATIVE_NAME,
        critical = false,
        value =
          listOf(
            Adapters.ANY_VALUE to
              AnyValue(
                tagClass = DerHeader.TAG_CLASS_CONTEXT_SPECIFIC,
                tag = 1L,
                constructed = false,
                length = 16,
                bytes = "ca@trustwave.com".encodeUtf8(),
              ),
          ),
      ),
    )
  }

  /** Converts public key bytes to SubjectPublicKeyInfo bytes. */
  private fun encodeKey(
    algorithm: String,
    publicKeyBytes: ByteString,
  ): ByteString {
    val subjectPublicKeyInfo =
      SubjectPublicKeyInfo(
        algorithm = AlgorithmIdentifier(algorithm = algorithm, parameters = null),
        subjectPublicKey = BitString(publicKeyBytes, 0),
      )
    return CertificateAdapters.subjectPublicKeyInfo.toDer(subjectPublicKeyInfo)
  }

  /** Returns a byte string that differs from this one by one bit. */
  private fun ByteString.offByOneBit(): ByteString =
    Buffer()
      .write(this, 0, size - 1)
      .writeByte(this[size - 1].toInt() xor 1)
      .readByteString()

  private fun date(s: String): Date =
    SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSZ").run {
      timeZone = TimeZone.getTimeZone("GMT")
      parse(s)
    }
}
