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

import java.net.URI
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.util.concurrent.atomic.AtomicBoolean
import org.testcontainers.containers.GenericContainer
import org.testcontainers.containers.wait.strategy.Wait
import org.testcontainers.images.builder.ImageFromDockerfile

/** Two host-side containers for an Android ECH interoperability test: DoH and HTTPS. */
object AndroidEchTestService {
  private const val CONTROL_PORT = 8080
  private const val DOH_PORT = 8053
  private const val TARGET_PORT = 8443

  @JvmStatic
  fun main(args: Array<String>) {
    require(args.isEmpty()) { "This service does not accept command-line arguments" }

    val endpointFile =
      Path.of(
        requireNotNull(System.getenv("ANDROID_ECH_TEST_ENDPOINT_FILE")) {
          "ANDROID_ECH_TEST_ENDPOINT_FILE is not set"
        },
      )
    val image =
      ImageFromDockerfile("okhttp/android-ech-fixture:local", false)
        .withFileFromClasspath("Dockerfile", "android-ech-fixture/Dockerfile")
        .withFileFromClasspath("main.go", "android-ech-fixture/main.go")

    val target = GenericContainer<Nothing>(image)
    target.withCommand("target")
    target.withExposedPorts(CONTROL_PORT, TARGET_PORT)
    target.waitingFor(Wait.forHttp("/health").forPort(CONTROL_PORT))
    target.withStartupTimeout(Duration.ofMinutes(10))
    var doh: GenericContainer<Nothing>? = null
    val stopped = AtomicBoolean()
    val stop = {
      if (stopped.compareAndSet(false, true)) {
        doh?.stop()
        target.stop()
      }
    }

    target.start()
    val targetHost = target.host.normalizedLoopback()
    val metadata =
      URI("http://$targetHost:${target.getMappedPort(CONTROL_PORT)}/metadata")
        .toURL()
        .readText()
        .lineSequence()
        .filter { it.isNotEmpty() }
        .associate { line ->
          val (name, value) = line.split('=', limit = 2)
          name to value
        }

    val dohContainer = GenericContainer<Nothing>(image)
    dohContainer.withCommand("doh")
    dohContainer.withEnv("ECH_GREEN_CONFIG_LIST", metadata.required("ECH_GREEN_CONFIG_LIST"))
    dohContainer.withEnv("ECH_RETRY_STALE_CONFIG_LIST", metadata.required("ECH_RETRY_STALE_CONFIG_LIST"))
    dohContainer.withEnv(
      "ECH_DISABLED_STALE_CONFIG_LIST",
      metadata.required("ECH_DISABLED_STALE_CONFIG_LIST"),
    )
    dohContainer.withEnv("DOH_CERT", metadata.required("DOH_CERT"))
    dohContainer.withEnv("DOH_KEY", metadata.required("DOH_KEY"))
    dohContainer.withEnv("TARGET_PORT", TARGET_PORT.toString())
    dohContainer.withExposedPorts(DOH_PORT)
    dohContainer.waitingFor(Wait.forHttps("/health").forPort(DOH_PORT).allowInsecure())
    dohContainer.withStartupTimeout(Duration.ofMinutes(5))
    doh = dohContainer
    dohContainer.start()

    Runtime.getRuntime().addShutdownHook(Thread({ stop() }, "android-ech-test-service-shutdown"))
    try {
      val endpoints =
        """
        DOH_HOST_PORT=${dohContainer.getMappedPort(DOH_PORT)}
        TARGET_HOST_PORT=${target.getMappedPort(TARGET_PORT)}
        CA_CERT=${metadata.required("CA_CERT")}
        """.trimIndent() + "\n"
      endpointFile.parent?.let { Files.createDirectories(it) }
      Files.writeString(endpointFile, endpoints)
      println("Android ECH test services ready on $targetHost")

      while (Files.exists(endpointFile)) {
        Thread.sleep(250L)
      }
    } finally {
      Files.deleteIfExists(endpointFile)
      stop()
    }
  }

  private fun String.normalizedLoopback() = if (this == "localhost") "127.0.0.1" else this

  private fun Map<String, String>.required(name: String) =
    requireNotNull(this[name]) { "ECH fixture metadata does not contain $name" }
}
