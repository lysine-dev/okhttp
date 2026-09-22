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
package okhttp3.internal.platform.android

import android.annotation.SuppressLint
import android.net.ssl.EchConfigList
import android.net.ssl.InvalidEchDataException
import android.net.ssl.SSLSockets
import android.os.Build
import androidx.annotation.ChecksSdkIntAtLeast
import androidx.annotation.RequiresApi
import java.io.IOException
import javax.net.ssl.SSLSocket
import okhttp3.Protocol
import okhttp3.internal.SuppressSignatureCheck
import okhttp3.internal.platform.Platform
import okhttp3.internal.platform.Platform.Companion.isAndroid
import okio.ByteString

/**
 * Socket adapter for Android 17+ platform TLS APIs.
 *
 * Specifically supports setEchConfigList for ECH goodness.
 */
@SuppressLint("NewApi")
@SuppressSignatureCheck
class Android17SocketAdapter
  @RequiresApi(37)
  internal constructor() : SocketAdapter {
    override fun matchesSocket(sslSocket: SSLSocket): Boolean = SSLSockets.isSupportedSocket(sslSocket)

    override fun isSupported(): Boolean = Companion.isSupported()

    override fun getSelectedProtocol(sslSocket: SSLSocket): String? =
      // SSLSocket.getApplicationProtocol returns "" if application protocols values will not
      // be used. Observed if you didn't specify SSLParameters.setApplicationProtocols
      when (val protocol = sslSocket.applicationProtocol) {
        null, "" -> null
        else -> protocol
      }

    override fun configureTlsExtensions(
      sslSocket: SSLSocket,
      hostname: String?,
      protocols: List<Protocol>,
      echConfigList: ByteString?,
    ) {
      SSLSockets.setUseSessionTickets(sslSocket, true)

      val sslParameters =
        try {
          sslSocket.sslParameters
        } catch (iae: IllegalArgumentException) {
          // Conscrypt's getSSLParameters() eagerly builds an SNIHostName from the peer host and
          // throws "Invalid input to toASCII" (via IDN.toASCII) for names that violate STD3 ASCII,
          // such as hostnames containing underscores. The JDK skips such names instead of throwing;
          // wrap it as an IOException so the call fails cleanly. Mirrors Android10SocketAdapter.
          throw IOException("Android internal error", iae)
        }

      // Enable ALPN.
      sslParameters.applicationProtocols = Platform.alpnProtocolNames(protocols).toTypedArray()

      sslSocket.sslParameters = sslParameters

      if (hostname != null && echConfigList != null) {
        // TODO put behind Network Policy

        // The ECH config was resolved during DNS (RealCall.resolveAddresses); just apply it here.
        // A config we can't parse is skipped, and the connection proceeds without ECH.
        val echConfig =
          try {
            EchConfigList.fromBytes(echConfigList.toByteArray())
          } catch (_: InvalidEchDataException) {
            // The platform can throw on a malformed or absent ECH parameter.
            // https://issuetracker.google.com/issues/319957694
            null
          }

        if (echConfig != null) {
          SSLSockets.setEchConfigList(sslSocket, echConfig)
        }
      }
    }

    @SuppressSignatureCheck
    companion object {
      fun buildIfSupported(): SocketAdapter? = if (isSupported()) Android17SocketAdapter() else null

      @ChecksSdkIntAtLeast(api = 37)
      fun isSupported() = isAndroid && Build.VERSION.SDK_INT >= 37
    }
  }
