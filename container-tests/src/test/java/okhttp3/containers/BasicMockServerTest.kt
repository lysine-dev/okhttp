/*
 * Copyright (C) 2024 Square, Inc.
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
package okhttp3.containers

import assertk.assertThat
import assertk.assertions.contains
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.jupiter.api.Test
import org.mockserver.client.MockServerClient
import org.mockserver.configuration.Configuration
import org.mockserver.logging.MockServerLogger
import org.mockserver.model.HttpRequest.request
import org.mockserver.model.HttpResponse.response
import org.mockserver.socket.tls.KeyStoreFactory
import org.testcontainers.containers.MockServerContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import org.testcontainers.utility.DockerImageName

@Testcontainers
class BasicMockServerTest {
  @Container
  val mockServer: MockServerContainer = MockServerContainer(MOCKSERVER_IMAGE)

  val client =
    OkHttpClient
      .Builder()
      .trustMockServer()
      .build()

  @Test
  fun testRequest() {
    MockServerClient(mockServer.host, mockServer.serverPort).use { mockServerClient ->
      mockServerClient
        .`when`(
          request()
            .withPath("/person")
            .withQueryStringParameter("name", "peter"),
        ).respond(response().withBody("Peter the person!"))

      val response = client.newCall(Request((mockServer.endpoint + "/person?name=peter").toHttpUrl())).execute()

      assertThat(response.body.string()).contains("Peter the person")
    }
  }

  @Test
  fun testHttpsRequest() {
    MockServerClient(mockServer.host, mockServer.serverPort).use { mockServerClient ->
      mockServerClient
        .`when`(
          request()
            .withPath("/person")
            .withQueryStringParameter("name", "peter"),
        ).respond(response().withBody("Peter the person!"))

      val response = client.newCall(Request((mockServer.secureEndpoint + "/person?name=peter").toHttpUrl())).execute()

      assertThat(response.body.string()).contains("Peter the person")
    }
  }

  companion object {
    private val mockServerVersion =
      checkNotNull(System.getProperty("mockserver.version")) {
        "mockserver.version is not set"
      }

    val MOCKSERVER_IMAGE: DockerImageName =
      DockerImageName
        .parse("mockserver/mockserver")
        .withTag("mockserver-$mockServerVersion")

    fun OkHttpClient.Builder.trustMockServer(): OkHttpClient.Builder =
      apply {
        val keyStoreFactory = KeyStoreFactory(Configuration.configuration(), MockServerLogger())

        val socketFactory = keyStoreFactory.sslContext().socketFactory

        val trustManagerFactory = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
        trustManagerFactory.init(keyStoreFactory.loadOrCreateKeyStore())
        val trustManager = trustManagerFactory.trustManagers.first() as X509TrustManager

        sslSocketFactory(socketFactory, trustManager)
      }
  }
}
