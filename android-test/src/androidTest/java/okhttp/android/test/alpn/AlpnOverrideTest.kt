/*
 * Copyright (C) 2020 Square, Inc.
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
package okhttp.android.test.alpn

import android.os.Build
import android.util.Log
import assertk.assertThat
import assertk.assertions.isEqualTo
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory
import okhttp3.Call
import okhttp3.Connection
import okhttp3.ConnectionSpec
import okhttp3.EventListener
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.sockets.DelegatingSSLSocketFactory
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test

/**
 * Tests for ALPN overriding on Android.
 */
@Tag("Remote")
class AlpnOverrideTest {
  class CustomSSLSocketFactory(
    delegate: SSLSocketFactory,
  ) : DelegatingSSLSocketFactory(delegate) {
    override fun configureSocket(sslSocket: SSLSocket): SSLSocket {
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
        val parameters = sslSocket.sslParameters
        Log.d("CustomSSLSocketFactory", "old applicationProtocols: $parameters.applicationProtocols")
        parameters.applicationProtocols = arrayOf("x-amzn-http-ca")
        sslSocket.sslParameters = parameters
      }

      return sslSocket
    }
  }

  var client = OkHttpClient()

  @Test
  fun getWithCustomSocketFactory() {
    client =
      client
        .newBuilder()
        .sslSocketFactory(CustomSSLSocketFactory(client.sslSocketFactory), client.x509TrustManager!!)
        .connectionSpecs(
          listOf(
            ConnectionSpec
              .Builder(ConnectionSpec.MODERN_TLS)
              .supportsTlsExtensions(false)
              .build(),
          ),
        ).eventListener(
          object : EventListener() {
            override fun connectionAcquired(
              call: Call,
              connection: Connection,
            ) {
              val sslSocket = connection.socket() as SSLSocket
              println("Requested " + sslSocket.sslParameters.applicationProtocols.joinToString())
              println("Negotiated " + sslSocket.applicationProtocol)
            }
          },
        ).build()

    val request =
      Request
        .Builder()
        .url("https://www.google.com")
        .build()
    client.newCall(request).execute().use { response ->
      assertThat(response.code).isEqualTo(200)
    }
  }
}
