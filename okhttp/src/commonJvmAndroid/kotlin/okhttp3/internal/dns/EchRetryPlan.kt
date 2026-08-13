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
package okhttp3.internal.dns

import okhttp3.internal.OkHttpInternalApi
import okhttp3.internal.canParseAsIpAddress
import okhttp3.internal.toCanonicalHost
import okio.ByteString

/**
 * A plan to retry after a failed Encrypted Client Hello TLS handshake.
 *
 * The [publicName] comes from the failed attempt's ECH config list. This is available locally by
 * unpacking [okhttp3.Dns.Record.ServiceMetadata.echConfigList].
 *
 * The [configList] comes from the server that couldn't successfully handshake with ECH. It will be
 * null if the server has directed us to securely disable ECH.
 *
 * See RFC 9849, section 6.1.6.
 */
@OkHttpInternalApi
class EchRetryPlan private constructor(
  /** The client-facing server's name from `ECHConfig.contents.public_name`. */
  val publicName: String,
  /** The ECH config list to retry with, or null to retry without ECH. */
  val configList: ByteString?,
) {
  companion object {
    private val INVALID_PUBLIC_NAME =
      "(\\..*)|(.*\\.)|((.*\\.)?[0-9]+)|((.*\\.)?0[xX][0-9a-fA-F]*)".toRegex()

    /** Returns a new config if the inputs are valid, and null otherwise. */
    fun getOrNull(
      publicName: String,
      configList: ByteString?,
    ): EchRetryPlan? {
      val canonicalHost = publicName.toCanonicalHost() ?: return null
      if (canonicalHost.canParseAsIpAddress()) return null
      if (INVALID_PUBLIC_NAME.matches(canonicalHost)) return null
      return EchRetryPlan(
        publicName,
        configList,
      )
    }
  }
}
