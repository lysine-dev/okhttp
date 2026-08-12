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
package okhttp3.android

import android.security.NetworkSecurityPolicy
import android.security.NetworkSecurityPolicy.DOMAIN_ENCRYPTION_MODE_UNKNOWN
import okhttp3.android.ShadowNetworkSecurityPolicy.Companion.create
import okhttp3.internal.SuppressSignatureCheck
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.shadow.api.Shadow

/**
 * Gives tests a [NetworkSecurityPolicy] with domain encryption modes of their choosing. The
 * platform constructor is private, so instances come from [create] rather than
 * from a subclass.
 */
@SuppressSignatureCheck
@Implements(NetworkSecurityPolicy::class)
class ShadowNetworkSecurityPolicy {
  lateinit var domainEncryptionModes: Map<String, Int>

  @Implementation
  fun getDomainEncryptionMode(hostname: String): Int = domainEncryptionModes[hostname] ?: DOMAIN_ENCRYPTION_MODE_UNKNOWN

  companion object {
    /**
     * Returns a policy that reports [domainEncryptionModes], and [DOMAIN_ENCRYPTION_MODE_UNKNOWN]
     * for other hosts.
     */
    fun create(domainEncryptionModes: Map<String, Int>): NetworkSecurityPolicy {
      val result = Shadow.newInstanceOf(NetworkSecurityPolicy::class.java)
      val shadow = Shadow.extract<ShadowNetworkSecurityPolicy>(result)
      shadow.domainEncryptionModes = domainEncryptionModes
      return result
    }
  }
}
