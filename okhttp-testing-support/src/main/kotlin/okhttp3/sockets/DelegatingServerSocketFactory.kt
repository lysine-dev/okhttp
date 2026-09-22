/*
 * Copyright (C) 2014 Square, Inc.
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
package okhttp3.sockets

import java.io.IOException
import java.net.InetAddress
import java.net.ServerSocket
import javax.net.ServerSocketFactory

/**
 * A [ServerSocketFactory] that delegates calls. Sockets can be configured after creation by
 * overriding [.configureServerSocket].
 */
open class DelegatingServerSocketFactory(
  private val delegate: ServerSocketFactory,
) : ServerSocketFactory() {
  @Throws(IOException::class)
  override fun createServerSocket(): ServerSocket {
    val serverSocket = delegate.createServerSocket()
    return configureServerSocket(serverSocket)
  }

  @Throws(IOException::class)
  override fun createServerSocket(port: Int): ServerSocket {
    val serverSocket = delegate.createServerSocket(port)
    return configureServerSocket(serverSocket)
  }

  @Throws(IOException::class)
  override fun createServerSocket(
    port: Int,
    backlog: Int,
  ): ServerSocket {
    val serverSocket = delegate.createServerSocket(port, backlog)
    return configureServerSocket(serverSocket)
  }

  @Throws(IOException::class)
  override fun createServerSocket(
    port: Int,
    backlog: Int,
    ifAddress: InetAddress,
  ): ServerSocket {
    val serverSocket = delegate.createServerSocket(port, backlog, ifAddress)
    return configureServerSocket(serverSocket)
  }

  @Throws(IOException::class)
  protected open fun configureServerSocket(serverSocket: ServerSocket): ServerSocket {
    // No-op by default.
    return serverSocket
  }
}
