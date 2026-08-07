/*
 * Copyright (C) 2022 Square, Inc.
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

import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketAddress
import okhttp3.internal.platform.Platform
import okhttp3.sockets.DelegatingSocketFactory

/**
 * A [SocketFactory] that redirects connections to [defaultAddress] or specific overridden address via [set].
 */
class SpecificHostSocketFactory(
  val defaultAddress: InetSocketAddress?,
) : DelegatingSocketFactory(getDefault()) {
  private val hostMapping = mutableMapOf<InetAddress, InetSocketAddress>()

  /** Sets the [real] address for [requested].  */
  operator fun set(
    requested: InetAddress,
    real: InetSocketAddress,
  ) {
    hostMapping[requested] = real
  }

  override fun createSocket(): Socket =
    object : Socket() {
      override fun connect(
        endpoint: SocketAddress?,
        timeout: Int,
      ) {
        val requested = (endpoint as InetSocketAddress)
        val inetSocketAddress = hostMapping[requested.address] ?: defaultAddress ?: requested
        Platform.get().log("Socket connection to: $inetSocketAddress was: $requested")
        super.connect(inetSocketAddress, timeout)
      }
    }
}
