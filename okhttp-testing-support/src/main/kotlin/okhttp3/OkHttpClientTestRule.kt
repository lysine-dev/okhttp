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
@file:Suppress(
  "CANNOT_OVERRIDE_INVISIBLE_MEMBER",
  "INVISIBLE_MEMBER",
  "INVISIBLE_REFERENCE",
)

package okhttp3

import android.annotation.SuppressLint
import java.util.concurrent.ThreadFactory
import java.util.concurrent.TimeUnit
import java.util.logging.Handler
import java.util.logging.Level
import java.util.logging.LogManager
import java.util.logging.LogRecord
import java.util.logging.Logger
import okhttp3.OkHttpClientTestRule.Companion.plus
import okhttp3.internal.buildConnectionPool
import okhttp3.internal.concurrent.TaskRunner
import okhttp3.internal.concurrent.withLock
import okhttp3.internal.connection.RealConnectionPool
import okhttp3.internal.http2.Http2
import okhttp3.internal.taskRunnerInternal
import okhttp3.testing.Flaky
import okhttp3.testing.PlatformRule.Companion.LOOM_PROPERTY
import okhttp3.testing.PlatformRule.Companion.getPlatformSystemProperty
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.fail
import org.junit.jupiter.api.extension.AfterEachCallback
import org.junit.jupiter.api.extension.BeforeEachCallback
import org.junit.jupiter.api.extension.ExtensionContext

/**
 * Apply this rule to all tests. It adds additional checks for leaked resources and uncaught
 * exceptions.
 *
 * Use [newClient] as a factory for a OkHttpClient instances. These instances are specifically
 * configured for testing.
 */
class OkHttpClientTestRule :
  BeforeEachCallback,
  AfterEachCallback {
  private val clientEventsList = mutableListOf<String>()
  private var testClient: OkHttpClient? = null
  private var uncaughtException: Throwable? = null
  private lateinit var testName: String
  private var defaultUncaughtExceptionHandler: Thread.UncaughtExceptionHandler? = null
  private var taskQueuesWereIdle: Boolean = false
  private val connectionListener = RecordingConnectionListener()

  var logger: Logger? = null

  var recordEvents = true
  var recordTaskRunner = false
  var recordFrames = false
  var recordSslDebug = false

  private val sslExcludeFilter =
    Regex(
      buildString {
        append("^(?:")
        append(
          listOf(
            "Inaccessible trust store",
            "trustStore is",
            "Reload the trust store",
            "Reload trust certs",
            "Reloaded",
            "adding as trusted certificates",
            "Ignore disabled cipher suite",
            "Ignore unsupported cipher suite",
          ).joinToString(separator = "|"),
        )
        append(").*")
      },
    )

  private val testLogHandler =
    object : Handler() {
      override fun publish(record: LogRecord) {
        val recorded =
          when (record.loggerName) {
            TaskRunner::class.java.name -> recordTaskRunner
            Http2::class.java.name -> recordFrames
            "javax.net.ssl" -> recordSslDebug && !sslExcludeFilter.matches(record.message)
            else -> false
          }

        if (recorded) {
          synchronized(clientEventsList) {
            clientEventsList.add(record.message)

            if (record.loggerName == "javax.net.ssl") {
              val parameters = record.parameters

              if (parameters != null) {
                clientEventsList.add(parameters.first().toString())
              }
            }
          }
        }
      }

      override fun flush() {
      }

      override fun close() {
      }
    }.apply {
      level = Level.FINEST
    }

  private fun applyLogger(fn: Logger.() -> Unit) {
    Logger.getLogger(OkHttpClient::class.java.`package`.name).fn()
    Logger.getLogger(OkHttpClient::class.java.name).fn()
    Logger.getLogger(Http2::class.java.name).fn()
    Logger.getLogger(TaskRunner::class.java.name).fn()
    Logger.getLogger("javax.net.ssl").fn()
  }

  fun wrap(eventListener: EventListener) =
    EventListener.Factory {
      ClientRuleEventListener(::addEvent) + eventListener
    }

  fun wrap(eventRecorder: EventRecorder) = wrap(eventRecorder.eventListener)

  fun wrap(eventListenerFactory: EventListener.Factory) =
    EventListener.Factory { call ->
      ClientRuleEventListener(::addEvent) + eventListenerFactory.create(call)
    }

  /**
   * Returns an OkHttpClient for tests to use as a starting point.
   *
   * The returned client installs a default event listener that gathers debug information. This will
   * be logged if the test fails.
   *
   * This client is also configured to be slightly more deterministic, returning a single IP
   * address for all hosts, regardless of the actual number of IP addresses reported by DNS.
   */
  fun newClient(): OkHttpClient {
    var client = testClient
    if (client == null) {
      client =
        initialClientBuilder()
          .dns(SINGLE_INET_ADDRESS_DNS) // Prevent unexpected fallback addresses.
          .eventListenerFactory { ClientRuleEventListener(logger = ::addEvent) }
          .build()
      connectionListener.forbidLock(RealConnectionPool.get(client.connectionPool))
      connectionListener.forbidLock(client.dispatcher)
      testClient = client
    }
    return client
  }

  private fun initialClientBuilder(): OkHttpClient.Builder =
    if (isLoom()) {
      val backend = TaskRunner.RealBackend(loomThreadFactory())
      val taskRunner = TaskRunner(backend)

      OkHttpClient
        .Builder()
        .connectionPool(
          buildConnectionPool(
            connectionListener = connectionListener,
            taskRunner = taskRunner,
          ),
        ).dispatcher(Dispatcher(backend.executor))
        .taskRunnerInternal(taskRunner)
    } else {
      OkHttpClient
        .Builder()
        .connectionPool(ConnectionPool(connectionListener = connectionListener))
    }

  private fun loomThreadFactory(): ThreadFactory {
    val ofVirtual = Thread::class.java.getMethod("ofVirtual").invoke(null)

    return Class
      .forName("java.lang.Thread\$Builder")
      .getMethod("factory")
      .invoke(ofVirtual) as ThreadFactory
  }

  private fun isLoom(): Boolean = getPlatformSystemProperty() == LOOM_PROPERTY

  fun newClientBuilder(): OkHttpClient.Builder = newClient().newBuilder()

  @Synchronized private fun addEvent(event: String) {
    if (recordEvents) {
      logger?.info(event)

      synchronized(clientEventsList) {
        clientEventsList.add(event)
      }
    }
  }

  @Synchronized private fun initUncaughtException(throwable: Throwable) {
    if (uncaughtException == null) {
      uncaughtException = throwable
    }
  }

  @Synchronized fun takeUncaughtException(): Throwable? =
    uncaughtException
      .also { uncaughtException = null }

  fun ensureAllConnectionsReleased() {
    testClient?.let {
      val connectionPool = it.connectionPool

      connectionPool.evictAll()
      if (connectionPool.connectionCount() > 0) {
        // Minimise test flakiness due to possible race conditions with connections closing.
        // Some number of tests will report here, but not fail due to this delay.
        println("Delaying to avoid flakes")
        Thread.sleep(500L)
        println("After delay: " + connectionPool.connectionCount())
      }

      connectionPool.evictAll()
      assertEquals(0, connectionPool.connectionCount()) {
        "Still ${connectionPool.connectionCount()} connections open"
      }
    }
  }

  private fun ensureAllTaskQueuesIdle() {
    val entryTime = System.nanoTime()

    for (queue in TaskRunner.INSTANCE.activeQueues()) {
      // We wait at most 1 second, so we don't ever turn multiple lost threads into
      // a test timeout failure.
      val waitTime = (entryTime + 1_000_000_000L - System.nanoTime())
      if (!queue.idleLatch().await(waitTime, TimeUnit.NANOSECONDS)) {
        TaskRunner.INSTANCE.withLock {
          TaskRunner.INSTANCE.cancelAll()
        }
        fail<Unit>("Queue still active after 1000 ms")
      }
    }
  }

  override fun beforeEach(context: ExtensionContext) {
    testName = context.displayName

    beforeEach()
  }

  private fun beforeEach() {
    defaultUncaughtExceptionHandler = Thread.getDefaultUncaughtExceptionHandler()
    Thread.setDefaultUncaughtExceptionHandler { _, throwable ->
      initUncaughtException(throwable)
    }

    taskQueuesWereIdle = TaskRunner.INSTANCE.activeQueues().isEmpty()

    applyLogger {
      addHandler(testLogHandler)
      level = Level.FINEST
      useParentHandlers = false
    }
  }

  @SuppressLint("NewApi")
  override fun afterEach(context: ExtensionContext) {
    val failure = context.executionException.orElseGet { null }

    if (uncaughtException != null) {
      throw failure + AssertionError("uncaught exception thrown during test", uncaughtException)
    }

    if (context.isFlaky()) {
      logEvents()
    }

    LogManager.getLogManager().reset()

    var result: Throwable? = failure
    Thread.setDefaultUncaughtExceptionHandler(defaultUncaughtExceptionHandler)
    try {
      ensureAllConnectionsReleased()
      releaseClient()
    } catch (ae: AssertionError) {
      result += ae
    }

    try {
      if (taskQueuesWereIdle) {
        ensureAllTaskQueuesIdle()
      }
    } catch (ae: AssertionError) {
      result += ae
    }

    if (result != null) throw result
  }

  private fun releaseClient() {
    testClient?.dispatcher?.executorService?.shutdown()
  }

  @SuppressLint("NewApi")
  private fun ExtensionContext.isFlaky(): Boolean =
    (testMethod.orElseGet { null }?.isAnnotationPresent(Flaky::class.java) == true) ||
      (testClass.orElseGet { null }?.isAnnotationPresent(Flaky::class.java) == true)

  @Synchronized private fun logEvents() {
    // Will be ineffective if test overrides the listener
    synchronized(clientEventsList) {
      println("$testName Events (${clientEventsList.size})")

      for (e in clientEventsList) {
        println(e)
      }
    }
  }

  fun recordedConnectionEventTypes(): List<String> = connectionListener.recordedEventTypes()

  companion object {
    /**
     * A network that resolves only one IP address per host. Use this when testing route selection
     * fallbacks to prevent the host machine's various IP addresses from interfering.
     */
    private val SINGLE_INET_ADDRESS_DNS =
      Dns { hostname ->
        val addresses = Dns.SYSTEM.lookup(hostname)
        listOf(addresses[0])
      }

    private operator fun Throwable?.plus(throwable: Throwable): Throwable {
      if (this != null) {
        addSuppressed(throwable)
        return this
      }
      return throwable
    }
  }
}
