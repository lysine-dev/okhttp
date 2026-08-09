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

import android.net.Network
import java.net.InetAddress
import java.net.UnknownHostException
import okhttp3.FakeDns
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.shadow.api.Shadow

@Implements(Network::class)
class ShadowNetwork {
  lateinit var dns: FakeDns

  @Implementation
  @Throws(UnknownHostException::class)
  fun getAllByName(host: String): Array<InetAddress> = dns.lookup(host).toTypedArray()

  companion object {
    fun create(dns: FakeDns): Network {
      val result = Shadow.newInstanceOf(Network::class.java)
      Shadow.extract<ShadowNetwork>(result).dns = dns
      return result
    }
  }
}
