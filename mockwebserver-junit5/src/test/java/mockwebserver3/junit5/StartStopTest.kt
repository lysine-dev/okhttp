/*
 * Copyright (C) 2025 Square, Inc.
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
package mockwebserver3.junit5

import assertk.assertThat
import assertk.assertions.isFalse
import assertk.assertions.isTrue
import java.util.concurrent.CopyOnWriteArrayList
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.AfterAllCallback
import org.junit.jupiter.api.extension.RegisterExtension

class StartStopTest {
  private val dispatcherA = ClosableDispatcher()

  @StartStop val serverA =
    MockWebServer().apply {
      dispatcher = dispatcherA
    }

  private val dispatcherB = ClosableDispatcher()

  @StartStop val serverB =
    MockWebServer().apply {
      dispatcher = dispatcherB
    }

  /** This one won't start because it isn't annotated. */
  private val dispatcherC = ClosableDispatcher()
  val serverC =
    MockWebServer().apply {
      dispatcher = dispatcherC
    }

  @Test
  fun happyPath() {
    testInstances += this

    assertThat(serverA.started).isTrue()
    assertThat(serverB.started).isTrue()
    assertThat(serverC.started).isFalse()

    assertThat(serverD.started).isTrue()
    assertThat(serverE.started).isTrue()
    assertThat(serverF.started).isFalse()
  }

  @Nested
  inner class NestedTest {
    @Test
    fun happyPath() {
      testInstances += this@StartStopTest

      assertThat(serverA.started).isTrue()
      assertThat(serverB.started).isTrue()
      assertThat(serverC.started).isFalse()
    }
  }

  private companion object {
    val testInstances = CopyOnWriteArrayList<StartStopTest>()

    private val dispatcherD = ClosableDispatcher()

    @StartStop @JvmStatic
    val serverD =
      MockWebServer().apply {
        dispatcher = dispatcherD
      }

    private val dispatcherE = ClosableDispatcher()

    @StartStop @JvmStatic
    val serverE =
      MockWebServer().apply {
        dispatcher = dispatcherE
      }

    private val dispatcherF = ClosableDispatcher()

    @JvmStatic val serverF =
      MockWebServer().apply {
        dispatcher = dispatcherF
      }

    @JvmStatic
    @RegisterExtension
    val checkClosed =
      AfterAllCallback {
        for (test in testInstances) {
          assertThat(test.dispatcherA.closed).isTrue()
          assertThat(test.dispatcherB.closed).isTrue()
          assertThat(test.dispatcherC.closed).isFalse() // Never started.
        }
        testInstances.clear()

        // No assertion that serverC and serverD are closed, because the MockWebServerExtension
        // runs after this callback.
        if (false) {
          assertThat(dispatcherD.closed).isTrue()
          assertThat(dispatcherE.closed).isTrue()
          assertThat(dispatcherF.closed).isFalse() // Never started.
        }
      }
  }

  class ClosableDispatcher : Dispatcher() {
    var closed = false

    override fun dispatch(request: RecordedRequest) = MockResponse()

    override fun close() {
      closed = true
    }
  }
}
