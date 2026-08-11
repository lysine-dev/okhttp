/*
 * Copyright (C) 2026 Block, Inc.
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

import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.util.concurrent.atomic.AtomicBoolean
import okhttp3.containers.BasicMockServerTest.Companion.MOCKSERVER_IMAGE
import org.mockserver.client.MockServerClient
import org.mockserver.configuration.ConfigurationProperties
import org.mockserver.model.HttpRequest.request
import org.mockserver.model.HttpResponse.response
import org.testcontainers.containers.MockServerContainer

/** A host-side Testcontainers service whose lifetime is controlled by the calling shell. */
object AndroidTestService {
  @JvmStatic
  fun main(args: Array<String>) {
    require(args.isEmpty()) { "This service does not accept command-line arguments" }

    val endpointFile =
      Path.of(
        requireNotNull(System.getenv("ANDROID_TEST_SERVICE_ENDPOINT_FILE")) {
          "ANDROID_TEST_SERVICE_ENDPOINT_FILE is not set"
        },
      )
    val mockServer =
      MockServerContainer(MOCKSERVER_IMAGE)
        // The amd64 MockServer image starts under emulation on Apple Silicon when using Colima.
        .withStartupTimeout(Duration.ofMinutes(5))
    val stopped = AtomicBoolean()
    val stop = {
      if (stopped.compareAndSet(false, true)) {
        mockServer.stop()
      }
    }

    mockServer.start()
    ConfigurationProperties.maxSocketTimeout(Duration.ofMinutes(2).toMillis())
    val mockServerClient = MockServerClient(mockServer.host, mockServer.serverPort)
    Runtime.getRuntime().addShutdownHook(Thread({ stop() }, "android-test-service-shutdown"))

    try {
      mockServerClient
        .`when`(request().withMethod("GET").withPath("/android-test"))
        .respond(response().withStatusCode(200).withBody("hello from Testcontainers"))

      val host = mockServer.host.let { if (it == "localhost") "127.0.0.1" else it }
      val endpoint = "http://$host:${mockServer.serverPort}"
      endpointFile.parent?.let { Files.createDirectories(it) }
      Files.writeString(endpointFile, endpoint)
      println("Android test service ready at $endpoint")

      while (Files.exists(endpointFile)) {
        Thread.sleep(250L)
      }
    } finally {
      Files.deleteIfExists(endpointFile)
      mockServerClient.close()
      stop()
    }
  }
}
