/*
 * Copyright (C) 2012 Square, Inc.
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

import java.net.InetAddress
import java.net.UnknownHostException
import okhttp3.Dns
import okhttp3.internal.OkHttpInternalApi

/**
 * A DNS that uses [InetAddress.getAllByName] to ask the underlying operating system to
 * lookup IP addresses.
 */
@OkHttpInternalApi
internal object InetAddressDns : Dns {
  override fun lookup(hostname: String): List<InetAddress> {
    try {
      return InetAddress.getAllByName(hostname).toList()
    } catch (e: NullPointerException) {
      throw UnknownHostException("Broken system behaviour for dns lookup of $hostname").apply {
        initCause(e)
      }
    }
  }
}
