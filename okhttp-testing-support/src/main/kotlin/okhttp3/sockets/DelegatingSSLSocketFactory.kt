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
import java.net.Socket
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory

/**
 * A [SSLSocketFactory] that delegates calls. Sockets can be configured after creation by
 * overriding [.configureSocket].
 */
open class DelegatingSSLSocketFactory(
  private val delegate: SSLSocketFactory,
) : SSLSocketFactory() {
  @Throws(IOException::class)
  override fun createSocket(): SSLSocket {
    val sslSocket = delegate.createSocket() as SSLSocket
    return configureSocket(sslSocket)
  }

  @Throws(IOException::class)
  override fun createSocket(
    host: String,
    port: Int,
  ): SSLSocket {
    val sslSocket = delegate.createSocket(host, port) as SSLSocket
    return configureSocket(sslSocket)
  }

  @Throws(IOException::class)
  override fun createSocket(
    host: String,
    port: Int,
    localAddress: InetAddress,
    localPort: Int,
  ): SSLSocket {
    val sslSocket = delegate.createSocket(host, port, localAddress, localPort) as SSLSocket
    return configureSocket(sslSocket)
  }

  @Throws(IOException::class)
  override fun createSocket(
    host: InetAddress,
    port: Int,
  ): SSLSocket {
    val sslSocket = delegate.createSocket(host, port) as SSLSocket
    return configureSocket(sslSocket)
  }

  @Throws(IOException::class)
  override fun createSocket(
    host: InetAddress,
    port: Int,
    localAddress: InetAddress,
    localPort: Int,
  ): SSLSocket {
    val sslSocket = delegate.createSocket(host, port, localAddress, localPort) as SSLSocket
    return configureSocket(sslSocket)
  }

  override fun getDefaultCipherSuites(): Array<String> = delegate.defaultCipherSuites

  override fun getSupportedCipherSuites(): Array<String> = delegate.supportedCipherSuites

  @Throws(IOException::class)
  override fun createSocket(
    socket: Socket,
    host: String,
    port: Int,
    autoClose: Boolean,
  ): SSLSocket {
    val sslSocket = delegate.createSocket(socket, host, port, autoClose) as SSLSocket
    return configureSocket(sslSocket)
  }

  @Throws(IOException::class)
  protected open fun configureSocket(sslSocket: SSLSocket): SSLSocket {
    // No-op by default.
    return sslSocket
  }
}
