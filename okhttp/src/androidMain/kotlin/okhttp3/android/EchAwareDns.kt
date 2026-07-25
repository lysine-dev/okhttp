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

import android.annotation.SuppressLint
import android.net.Network
import android.os.Build
import android.security.NetworkSecurityPolicy
import android.security.NetworkSecurityPolicy.DOMAIN_ENCRYPTION_MODE_ENABLED
import android.security.NetworkSecurityPolicy.DOMAIN_ENCRYPTION_MODE_OPPORTUNISTIC
import androidx.annotation.ChecksSdkIntAtLeast
import androidx.annotation.RequiresApi
import java.net.InetAddress
import okhttp3.Dns
import okhttp3.internal.SuppressSignatureCheck
import okhttp3.internal.platform.Platform.Companion.isAndroid

/**
 * ECH Aware DNS Wrapper for Android 37.
 *
 * Uses the [NetworkSecurityPolicy] to read the domain encryption settings for
 * a given host.
 *
 * Usage:
 * ```
 * val dns = EchAwareDns(AndroidDns())
 * val client = OkHttpClient.Builder().dns(dns).build()
 * ```
 *
 * @param echDns resolves hosts that the policy permits ECH for. It should carry service metadata,
 *   such as [AndroidDns] with `includeServiceMetadata` enabled.
 * @param addressOnlyDns resolves hosts that the policy denies ECH for.
 *   The default, [Dns.SYSTEM], resolves on the default network.
 * @param policy the source of each host's domain encryption mode.
 */
@SuppressLint("NewApi")
@SuppressSignatureCheck
class EchAwareDns
  @RequiresApi(37)
  constructor(
    internal val echDns: Dns = AndroidDns(),
    internal val addressOnlyDns: Dns = Dns.SYSTEM,
    internal val policy: NetworkSecurityPolicy = NetworkSecurityPolicy.getInstance(),
  ) : Dns {
    // Safe to call lookup as it doesn't carry HTTPS records
    override fun lookup(hostname: String): List<InetAddress> = addressOnlyDns.lookup(hostname)

    override fun newCall(request: Dns.Request): Dns.Call {
      val dns = if (echAllowed(request.hostname)) echDns else addressOnlyDns

      return dns.newCall(request)
    }

    /**
     * Allows avoiding waiting for HTTPS records, when we know that Android Conscrypt won't use them.
     */
    private fun echAllowed(hostname: String): Boolean =
      when (policy.getDomainEncryptionMode(hostname)) {
        DOMAIN_ENCRYPTION_MODE_ENABLED, DOMAIN_ENCRYPTION_MODE_OPPORTUNISTIC -> true
        else -> false
      }

    @SuppressSignatureCheck
    companion object {
      /** Returns a [Dns] that resolves on [network]. */
      @RequiresApi(37)
      fun forNetwork(network: Network): EchAwareDns =
        EchAwareDns(
          echDns = AndroidDns(network = network),
          addressOnlyDns = AndroidDns(network = network, includeServiceMetadata = false),
        )

      @ChecksSdkIntAtLeast(api = 37)
      internal val isSupported: Boolean = isAndroid && Build.VERSION.SDK_INT >= 37

      internal fun buildIfSupported(): Dns? = if (isSupported) EchAwareDns() else null
    }
  }
