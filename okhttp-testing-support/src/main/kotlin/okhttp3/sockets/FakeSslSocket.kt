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
@file:Suppress("Since15")

package okhttp3.sockets

import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.SocketAddress
import java.net.SocketException
import java.net.SocketOption
import java.nio.channels.SocketChannel
import java.util.concurrent.TimeUnit
import java.util.function.BiFunction
import javax.net.ssl.HandshakeCompletedListener
import javax.net.ssl.SSLParameters
import javax.net.ssl.SSLPeerUnverifiedException
import javax.net.ssl.SSLSession
import javax.net.ssl.SSLSocket
import okhttp3.CipherSuite
import okhttp3.Handshake
import okhttp3.Protocol
import okhttp3.TlsVersion
import okio.ByteString
import okio.Timeout

/**
 * The TLS layer on top of socket.
 *
 * The [java.net.Socket] API uses inheritance rather than composition for TLS, but this uses
 * composition, and delegates socket methods to its underlying [FakeSocket]. It also relies on that
 * class to hold the state of the TLS handshake.
 */
internal class FakeSslSocket(
  val tls: FakeTls,
  val socket: FakeSocket,
  val hostname: String?,
  tlsVersions: List<TlsVersion>,
  cipherSuites: List<CipherSuite>,
) : SSLSocket() {
  private var sslParameters =
    SSLParameters().apply {
      this.protocols = tlsVersions.map { it.javaName }.toTypedArray()
      this.cipherSuites = cipherSuites.map { it.javaName }.toTypedArray()
    }
  private var enableSessionCreation = true
  private var useClientMode = true
  var echConfigList: ByteString? = null

  override fun getEnabledProtocols(): Array<String> = sslParameters.protocols

  override fun setEnabledProtocols(protocols: Array<String>) {
    sslParameters.protocols = protocols
  }

  override fun getSupportedProtocols() = tls.supportedTlsVersions.map { it.javaName }.toTypedArray()

  override fun getSupportedCipherSuites() = tls.supportedCipherSuites.map { it.javaName }.toTypedArray()

  override fun getEnabledCipherSuites(): Array<out String?> = sslParameters.cipherSuites

  override fun setEnabledCipherSuites(cipherSuites: Array<String>) {
    sslParameters.cipherSuites = cipherSuites
  }

  override fun setNeedClientAuth(needClientAuth: Boolean) {
    sslParameters.needClientAuth = needClientAuth
  }

  override fun setWantClientAuth(wantClientAuth: Boolean) {
    this.sslParameters.wantClientAuth = wantClientAuth
  }

  override fun getNeedClientAuth() = sslParameters.needClientAuth

  override fun getWantClientAuth() = sslParameters.wantClientAuth

  override fun setEnableSessionCreation(flag: Boolean) {
    this.enableSessionCreation = flag
  }

  override fun getEnableSessionCreation() = enableSessionCreation

  override fun getSSLParameters() = sslParameters

  override fun setSSLParameters(sslParameters: SSLParameters) {
    this.sslParameters = sslParameters
  }

  override fun setUseClientMode(useClientMode: Boolean) {
    this.useClientMode = useClientMode
  }

  override fun getUseClientMode() = useClientMode

  override fun getSession(): SSLSession = requireSession()

  override fun startHandshake() {
    requireSession()
  }

  private fun requireSession(): SSLSession {
    val tlsVersions = sslParameters.protocols.map { TlsVersion.forJavaName(it) }
    val cipherSuites = sslParameters.cipherSuites.map { CipherSuite.forJavaName(it) }
    val protocols = sslParameters.applicationProtocols.map { Protocol.get(it) }

    val inputs =
      when {
        useClientMode -> {
          Handshaker.ClientInputs(
            tlsVersions = tlsVersions,
            cipherSuites = cipherSuites,
            protocols = protocols,
            keyManager = tls.keyManager,
            hostname = hostname,
            echConfigList = echConfigList,
          )
        }

        else -> {
          Handshaker.ServerInputs(
            tlsVersions = tlsVersions,
            cipherSuites = cipherSuites,
            protocols = protocols,
            keyManager = tls.keyManager,
            clientAuth =
              when {
                sslParameters.needClientAuth -> Handshaker.ClientAuth.Required
                sslParameters.wantClientAuth -> Handshaker.ClientAuth.Requested
                else -> Handshaker.ClientAuth.None
              },
          )
        }
      }

    val previous = socket.state
    if (previous.handshakeState == HandshakeState.New) {
      val connection =
        (previous as? FakeSocket.State.Connected)?.connection
          ?: throw SocketException("not connected")

      val handshaking =
        previous.withHandshakeState(
          handshakeState = HandshakeState.Handshaking,
        )

      if (!socket.atomicState.compareAndSet(previous, handshaking)) {
        throw SocketException("not ready")
      }

      val handshakeTimeout = Timeout()
      val soTimeout = soTimeout
      if (soTimeout != 0) {
        handshakeTimeout.deadline(soTimeout.toLong(), TimeUnit.MILLISECONDS)
      }

      val next =
        try {
          when (inputs) {
            is Handshaker.ClientInputs -> {
              val result = connection.handshake(tls.handshaker, inputs, handshakeTimeout)
              previous.withHandshakeSuccess(
                socket = result.clientSocket,
                handshakeState =
                  HandshakeState.Success(
                    session =
                      FakeSslSession(
                        peerAddress = remoteSocketAddress,
                        handshake = result.clientHandshake,
                        selectedProtocol = result.selectedProtocol,
                      ),
                  ),
              )
            }

            is Handshaker.ServerInputs -> {
              val result = connection.handshake(inputs, handshakeTimeout)
              previous.withHandshakeSuccess(
                socket = result.serverSocket,
                handshakeState =
                  HandshakeState.Success(
                    session =
                      FakeSslSession(
                        peerAddress = remoteSocketAddress,
                        handshake = result.serverHandshake,
                        selectedProtocol = result.selectedProtocol,
                      ),
                  ),
              )
            }
          }
        } catch (e: IOException) {
          previous.withHandshakeState(
            handshakeState =
              HandshakeState.Failed(
                exception = e,
                session = FakeSslSession(),
              ),
          )
        }

      // If the state changed while we were connecting, the other state wins.
      socket.atomicState.compareAndSet(handshaking, next)
    }

    val state = socket.state
    if (state is FakeSocket.State.Closed) throw SocketException("closed")

    return when (val handshakeState = state.handshakeState) {
      is HandshakeState.Failed -> throw handshakeState.exception
      else -> handshakeState.session ?: error("unexpected state")
    }
  }

  override fun getApplicationProtocol(): String? {
    val session = socket.state.handshakeState.session ?: return null
    return session.selectedProtocol?.toString() ?: ""
  }

  override fun getInputStream(): InputStream {
    val state = socket.state
    if (state !is FakeSocket.State.Connected) throw IOException("not connected")
    if (state.handshakeState.session == null) throw IOException("no handshake")
    return state.inputStream
  }

  override fun getOutputStream(): OutputStream {
    val state = socket.state
    if (state !is FakeSocket.State.Connected) throw IOException("not connected")
    if (state.handshakeState.session == null) throw IOException("no handshake")
    return state.outputStream
  }

  override fun isInputShutdown() = socket.isInputShutdown()

  override fun shutdownInput() {
    socket.shutdownInput()
  }

  override fun isOutputShutdown() = socket.isOutputShutdown

  override fun shutdownOutput() {
    socket.shutdownOutput()
  }

  override fun close() {
    socket.close()
  }

  override fun isClosed() = socket.isClosed

  override fun getLocalSocketAddress() = socket.localSocketAddress

  override fun getLocalAddress() = socket.localAddress

  override fun getLocalPort() = socket.localPort

  override fun getRemoteSocketAddress() = socket.remoteSocketAddress

  override fun getInetAddress() = socket.inetAddress

  override fun getPort() = socket.port

  override fun getKeepAlive() = socket.keepAlive

  override fun getSoLinger() = socket.soLinger

  override fun getReceiveBufferSize() = socket.receiveBufferSize

  override fun getSendBufferSize() = socket.sendBufferSize

  override fun getSoTimeout() = socket.soTimeout

  override fun getTcpNoDelay() = socket.tcpNoDelay

  override fun setKeepAlive(keepAlive: Boolean) {
    socket.setKeepAlive(keepAlive)
  }

  override fun setSendBufferSize(sendBufferSize: Int) {
    socket.setSendBufferSize(sendBufferSize)
  }

  override fun setReceiveBufferSize(receiveBufferSize: Int) {
    socket.setReceiveBufferSize(receiveBufferSize)
  }

  override fun setSoLinger(
    on: Boolean,
    timeout: Int,
  ) {
    socket.setSoLinger(on, timeout)
  }

  override fun setSoTimeout(soTimeout: Int) {
    socket.soTimeout = soTimeout
  }

  override fun setTcpNoDelay(tcpNoDelay: Boolean) {
    socket.setTcpNoDelay(tcpNoDelay)
  }

  override fun isBound() = socket.isBound

  override fun isConnected() = socket.isConnected

  override fun bind(localAddr: SocketAddress) {
    socket.bind(localAddr)
  }

  override fun connect(remoteAddr: SocketAddress) {
    socket.connect(remoteAddr)
  }

  override fun connect(
    remoteAddr: SocketAddress,
    timeout: Int,
  ) {
    socket.connect(remoteAddr, timeout)
  }

  override fun setReuseAddress(reuseAddress: Boolean) {
    socket.setReuseAddress(reuseAddress)
  }

  override fun getReuseAddress() = socket.reuseAddress

  override fun setOOBInline(oobInline: Boolean) {
    socket.setOOBInline(oobInline)
  }

  override fun getOOBInline() = socket.oobInline

  override fun setTrafficClass(trafficClass: Int) {
    socket.setTrafficClass(trafficClass)
  }

  override fun getTrafficClass() = socket.trafficClass

  override fun getChannel(): SocketChannel = socket.channel

  override fun sendUrgentData(data: Int) = socket.sendUrgentData(data)

  override fun setPerformancePreferences(
    connectionTime: Int,
    latency: Int,
    bandwidth: Int,
  ) = socket.setPerformancePreferences(connectionTime, latency, bandwidth)

  override fun <T> setOption(
    name: SocketOption<T?>,
    value: T?,
  ) = socket.setOption(name, value)

  override fun <T> getOption(name: SocketOption<T?>) = socket.getOption(name)

  override fun supportedOptions(): Set<SocketOption<*>> = socket.supportedOptions()

  override fun addHandshakeCompletedListener(listener: HandshakeCompletedListener) = error("unsupported")

  override fun removeHandshakeCompletedListener(listener: HandshakeCompletedListener) = error("unsupported")

  override fun getHandshakeSession() = error("unsupported")

  override fun getHandshakeApplicationProtocol() = error("unsupported")

  override fun setHandshakeApplicationProtocolSelector(selector: BiFunction<SSLSocket, MutableList<String>, String>) = error("unsupported")

  override fun getHandshakeApplicationProtocolSelector() = error("unsupported")

  override fun toString() = "FakeSslSocket"

  internal sealed interface HandshakeState {
    val session: FakeSslSession?
      get() = null

    object New : HandshakeState

    object Handshaking : HandshakeState

    data class Success(
      override val session: FakeSslSession,
    ) : HandshakeState

    class Failed(
      val exception: IOException,
      override val session: FakeSslSession,
    ) : HandshakeState
  }

  /**
   * For OkHttp this is useful as a holder for the handshake.
   *
   * It's particularly awkward to use because it returns dummy values when used without an actual
   * TLS handshake.
   */
  internal class FakeSslSession(
    val peerAddress: InetSocketAddress? = null,
    val handshake: Handshake? = null,
    val selectedProtocol: Protocol? = null,
  ) : SSLSession {
    override fun getPeerCertificates() =
      handshake?.peerCertificates?.toTypedArray()
        ?: throw SSLPeerUnverifiedException("no handshake")

    override fun getLocalCertificates() =
      handshake?.localCertificates?.toTypedArray()
        ?: throw SSLPeerUnverifiedException("no handshake")

    override fun getPeerPrincipal() =
      handshake?.peerPrincipal
        ?: throw SSLPeerUnverifiedException("no handshake")

    override fun getLocalPrincipal() =
      handshake?.localPrincipal
        ?: throw SSLPeerUnverifiedException("no handshake")

    override fun getCipherSuite() = handshake?.cipherSuite?.javaName ?: "TLS_NULL_WITH_NULL_NULL"

    override fun getProtocol() = handshake?.tlsVersion?.javaName ?: "NONE"

    override fun getPeerHost() = peerAddress?.hostName

    override fun getPeerPort() = peerAddress?.port ?: -1

    override fun getId() = error("unsupported")

    override fun getSessionContext() = error("unsupported")

    override fun getCreationTime() = error("unsupported")

    override fun getLastAccessedTime() = error("unsupported")

    override fun invalidate() = error("unsupported")

    override fun isValid() = error("unsupported")

    override fun putValue(
      name: String,
      value: Any,
    ) = error("unsupported")

    override fun getValue(name: String) = error("unsupported")

    override fun removeValue(name: String) = error("unsupported")

    override fun getValueNames() = error("unsupported")

    override fun getPacketBufferSize() = error("unsupported")

    override fun getApplicationBufferSize() = error("unsupported")
  }
}
