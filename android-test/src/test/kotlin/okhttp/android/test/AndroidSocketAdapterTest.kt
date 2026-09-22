/*
 * Copyright (C) 2019 Square, Inc.
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
package okhttp.android.test

import java.security.Provider
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket
import okhttp3.Protocol.HTTP_1_1
import okhttp3.Protocol.HTTP_2
import okhttp3.internal.platform.android.AndroidSocketAdapter
import okhttp3.internal.platform.android.ConscryptSocketAdapter
import okhttp3.internal.platform.android.DeferredSocketAdapter
import okhttp3.internal.platform.android.SocketAdapter
import okhttp3.internal.platform.android.StandardAndroidSocketAdapter
import okhttp3.sockets.DelegatingSSLSocket
import okhttp3.sockets.DelegatingSSLSocketFactory
import org.conscrypt.Conscrypt
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeFalse
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.ParameterizedRobolectricTestRunner
import org.robolectric.ParameterizedRobolectricTestRunner.Parameters

@RunWith(ParameterizedRobolectricTestRunner::class)
class AndroidSocketAdapterTest(
  val adapter: SocketAdapter,
) {
  val context: SSLContext by lazy {
    val provider: Provider = Conscrypt.newProviderBuilder().provideTrustManager(true).build()

    SSLContext.getInstance("TLS", provider).apply {
      init(null, null, null)
    }
  }

  @Test
  fun testMatchesSupportedSocket() {
    val socketFactory = context.socketFactory

    val sslSocket = socketFactory.createSocket() as SSLSocket
    assertTrue(adapter.matchesSocket(sslSocket))

    adapter.configureTlsExtensions(sslSocket, null, listOf(HTTP_2, HTTP_1_1), null)
    // not connected
    assertNull(adapter.getSelectedProtocol(sslSocket))
  }

  @Test
  fun testMatchesSupportedAndroidSocketFactory() {
    assumeTrue(adapter is StandardAndroidSocketAdapter)

    assertTrue(adapter.matchesSocketFactory(context.socketFactory))
    assertNotNull(adapter.trustManager(context.socketFactory))
  }

  @Test
  fun testDoesntMatchSupportedCustomSocketFactory() {
    assumeFalse(adapter is StandardAndroidSocketAdapter)

    assertFalse(adapter.matchesSocketFactory(context.socketFactory))
    assertNull(adapter.trustManager(context.socketFactory))
  }

  @Test
  fun testCustomSocket() {
    val socketFactory = DelegatingSSLSocketFactory(context.socketFactory)

    assertFalse(adapter.matchesSocketFactory(socketFactory))

    val sslSocket =
      object : DelegatingSSLSocket(context.socketFactory.createSocket() as SSLSocket) {}
    assertFalse(adapter.matchesSocket(sslSocket))

    adapter.configureTlsExtensions(sslSocket, null, listOf(HTTP_2, HTTP_1_1), null)
    // not connected
    assertNull(adapter.getSelectedProtocol(sslSocket))
  }

  companion object {
    @JvmStatic
    @Parameters(name = "{0}")
    fun data(): Collection<SocketAdapter> =
      listOfNotNull(
        DeferredSocketAdapter(ConscryptSocketAdapter.factory),
        DeferredSocketAdapter(AndroidSocketAdapter.factory("org.conscrypt")),
        StandardAndroidSocketAdapter.buildIfSupported("org.conscrypt"),
      )
  }
}
