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

/** ASN.1 object identifiers used internally by this implementation. */
internal object ObjectIdentifiers {
  const val EC_PUBLIC_KEY = "1.2.840.10045.2.1"
  const val SHA256_WITH_ECDSA = "1.2.840.10045.4.3.2"
  const val RSA_ENCRYPTION = "1.2.840.113549.1.1.1"
  const val SHA256_WITH_RSA_ENCRYPTION = "1.2.840.113549.1.1.11"
  const val ED25519 = "1.3.101.112"
  const val SUBJECT_ALTERNATIVE_NAME = "2.5.29.17"
  const val BASIC_CONSTRAINTS = "2.5.29.19"
  const val COMMON_NAME = "2.5.4.3"
  const val ORGANIZATIONAL_UNIT_NAME = "2.5.4.11"
}
