/*
 * Copyright (C) 2022 Square, Inc.
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
package okhttp3.internal.connection

import assertk.assertThat
import assertk.assertions.containsExactly
import assertk.assertions.hasMessage
import assertk.assertions.isEqualTo
import java.io.IOException
import java.net.UnknownServiceException
import kotlin.test.assertFailsWith
import okhttp3.FakeRoutePlanner
import okhttp3.FakeRoutePlanner.ConnectState.TLS_CONNECTED
import okhttp3.internal.concurrent.TaskFaker
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test

/**
 * Unit test for [FastFallbackExchangeFinder] implementation details.
 *
 * This test uses [TaskFaker] to deterministically test racy code. Each function in this test has
 * the same structure:
 *
 *  * prepare a set of plans, each with a predictable connect delay
 *  * attempt to find a connection
 *  * step through time, asserting that the expected side effects are performed.
 */
internal class FastFallbackExchangeFinderTest {
  private val taskFaker = TaskFaker()
  private val taskRunner = taskFaker.taskRunner

  /**
   * Note that we don't use the same [TaskFaker] for this factory. That way off-topic tasks like
   * connection pool maintenance tasks don't add noise to route planning tests.
   */
  private val routePlanner = FakeRoutePlanner(taskFaker = taskFaker)
  private val finder = FastFallbackExchangeFinder(routePlanner, taskRunner)

  @AfterEach
  fun tearDown() {
    taskFaker.close()
    routePlanner.close()
  }

  @Test
  fun takeConnectedConnection() {
    val plan0 = routePlanner.addPlan()
    plan0.connectState = TLS_CONNECTED

    taskRunner.newQueue().execute("connect") {
      val result0 = finder.find()
      assertThat(result0).isEqualTo(plan0.connection)
    }

    taskFaker.runTasks()
    assertEvents(
      "take plan 0",
    )

    taskFaker.assertNoMoreTasks()
  }

  @Test
  fun takeConnectingConnection() {
    val plan0 = routePlanner.addPlan()
    plan0.tcpConnectDelayNanos = 240.ms

    taskRunner.newQueue().execute("connect") {
      val result0 = finder.find()
      assertThat(result0).isEqualTo(plan0.connection)
    }

    taskFaker.runTasks()
    assertEvents(
      "take plan 0",
      "plan 0 TCP connecting...",
    )

    taskFaker.advanceUntil(240.ms)
    assertEvents(
      "plan 0 TCP connected",
      "plan 0 TLS connecting...",
      "plan 0 TLS connected",
    )
  }

  @Test
  fun firstPlanConnectedBeforeSecondPlan() {
    val plan0 = routePlanner.addPlan()
    plan0.tcpConnectDelayNanos = 260.ms
    val plan1 = routePlanner.addPlan()
    plan1.tcpConnectDelayNanos = 20.ms // Connect at time = 270 ms.

    taskRunner.newQueue().execute("connect") {
      val result0 = finder.find()
      assertThat(result0).isEqualTo(plan0.connection)
    }

    taskFaker.runTasks()
    assertEvents(
      "take plan 0",
      "plan 0 TCP connecting...",
    )

    taskFaker.advanceUntil(250.ms)
    assertEvents(
      "take plan 1",
      "plan 1 TCP connecting...",
    )

    taskFaker.advanceUntil(260.ms)
    assertEvents(
      "plan 0 TCP connected",
      "plan 1 cancel",
      "plan 0 TLS connecting...",
      "plan 0 TLS connected",
    )

    taskFaker.advanceUntil(270.ms)
    assertEvents(
      "plan 1 TCP connect canceled",
    )
  }

  @Test
  fun secondPlanAlreadyConnected() {
    val plan0 = routePlanner.addPlan()
    plan0.tcpConnectDelayNanos = 260.ms
    val plan1 = routePlanner.addPlan()
    plan1.connectState = TLS_CONNECTED

    taskRunner.newQueue().execute("connect") {
      val result0 = finder.find()
      assertThat(result0).isEqualTo(plan1.connection)
    }

    taskFaker.runTasks()
    assertEvents(
      "take plan 0",
      "plan 0 TCP connecting...",
    )

    taskFaker.advanceUntil(250.ms)
    assertEvents(
      "take plan 1",
      "plan 0 cancel",
    )

    taskFaker.advanceUntil(260.ms)
    assertEvents(
      "plan 0 TCP connect canceled",
    )
  }

  @Test
  fun secondPlanConnectedBeforeFirstPlan() {
    val plan0 = routePlanner.addPlan()
    plan0.tcpConnectDelayNanos = 270.ms
    val plan1 = routePlanner.addPlan()
    plan1.tcpConnectDelayNanos = 10.ms // Connect at time = 260 ms.

    taskRunner.newQueue().execute("connect") {
      val result0 = finder.find()
      assertThat(result0).isEqualTo(plan1.connection)
    }

    taskFaker.runTasks()
    assertEvents(
      "take plan 0",
      "plan 0 TCP connecting...",
    )

    taskFaker.advanceUntil(250.ms)
    assertEvents(
      "take plan 1",
      "plan 1 TCP connecting...",
    )

    taskFaker.advanceUntil(260.ms)
    assertEvents(
      "plan 1 TCP connected",
      "plan 0 cancel",
      "plan 1 TLS connecting...",
      "plan 1 TLS connected",
    )

    taskFaker.advanceUntil(270.ms)
    assertEvents(
      "plan 0 TCP connect canceled",
    )
  }

  @Test
  fun thirdPlanAlreadyConnected() {
    val plan0 = routePlanner.addPlan()
    plan0.tcpConnectDelayNanos = 520.ms
    val plan1 = routePlanner.addPlan()
    plan1.tcpConnectDelayNanos = 260.ms // Connect completes at 510 ms.
    val plan2 = routePlanner.addPlan()
    plan2.connectState = TLS_CONNECTED

    taskRunner.newQueue().execute("connect") {
      val result0 = finder.find()
      assertThat(result0).isEqualTo(plan2.connection)
    }

    taskFaker.runTasks()
    assertEvents(
      "take plan 0",
      "plan 0 TCP connecting...",
    )

    taskFaker.advanceUntil(250.ms)
    assertEvents(
      "take plan 1",
      "plan 1 TCP connecting...",
    )

    taskFaker.advanceUntil(500.ms)
    assertEvents(
      "take plan 2",
      "plan 0 cancel",
      "plan 1 cancel",
    )

    taskFaker.advanceUntil(510.ms)
    assertEvents(
      "plan 1 TCP connect canceled",
    )

    taskFaker.advanceUntil(520.ms)
    assertEvents(
      "plan 0 TCP connect canceled",
    )
  }

  @Test
  fun takeMultipleConnections() {
    val plan0 = routePlanner.addPlan()
    plan0.connectState = TLS_CONNECTED
    val plan1 = routePlanner.addPlan()
    plan1.connectState = TLS_CONNECTED

    taskRunner.newQueue().execute("connect") {
      val result0 = finder.find()
      assertThat(result0).isEqualTo(plan0.connection)
      val result1 = finder.find()
      assertThat(result1).isEqualTo(plan1.connection)
    }

    taskFaker.runTasks()
    assertEvents(
      "take plan 0",
      "take plan 1",
    )

    taskFaker.assertNoMoreTasks()
  }

  @Test
  fun takeMultipleConnectionsReturnsRaceLoser() {
    val plan0 = routePlanner.addPlan()
    plan0.tcpConnectDelayNanos = 270.ms
    val plan1 = routePlanner.addPlan()
    plan1.tcpConnectDelayNanos = 10.ms // Connect at time = 260 ms.
    val plan2 = plan0.createRetry()
    plan2.tcpConnectDelayNanos = 20.ms // Connect at time = 280 ms.

    taskRunner.newQueue().execute("connect") {
      val result0 = finder.find()
      assertThat(result0).isEqualTo(plan1.connection)
      val result1 = finder.find()
      assertThat(result1).isEqualTo(plan2.connection)
    }

    taskFaker.runTasks()
    assertEvents(
      "take plan 0",
      "plan 0 TCP connecting...",
    )

    taskFaker.advanceUntil(250.ms)
    assertEvents(
      "take plan 1",
      "plan 1 TCP connecting...",
    )

    taskFaker.advanceUntil(260.ms)
    assertEvents(
      "plan 1 TCP connected",
      "plan 0 cancel",
      "plan 1 TLS connecting...",
      "plan 1 TLS connected",
      "plan 2 TCP connecting...",
    )

    taskFaker.advanceUntil(270.ms)
    assertEvents(
      "plan 0 TCP connect canceled",
    )

    taskFaker.advanceUntil(280.ms)
    assertEvents(
      "plan 2 TCP connected",
      "plan 2 TLS connecting...",
      "plan 2 TLS connected",
    )

    taskFaker.assertNoMoreTasks()
  }

  @Test
  fun firstConnectionFailsAndNoOthersExist() {
    val plan0 = routePlanner.addPlan()
    plan0.tcpConnectThrowable = IOException("boom!")

    taskRunner.newQueue().execute("connect") {
      assertFailsWith<IOException> {
        finder.find()
      }.also { expected ->
        assertThat(expected).hasMessage("boom!")
      }
    }

    taskFaker.runTasks()
    assertEvents(
      "take plan 0",
      "plan 0 TCP connecting...",
      "plan 0 TCP connect failed",
    )

    taskFaker.assertNoMoreTasks()
  }

  @Test
  fun firstConnectionFailsToConnectAndSecondSucceeds() {
    val plan0 = routePlanner.addPlan()
    plan0.tcpConnectThrowable = IOException("boom!")
    val plan1 = routePlanner.addPlan()
    plan1.connectState = TLS_CONNECTED

    taskRunner.newQueue().execute("connect") {
      val result0 = finder.find()
      assertThat(result0).isEqualTo(plan1.connection)
    }

    taskFaker.runTasks()
    assertEvents(
      "take plan 0",
      "plan 0 TCP connecting...",
      "plan 0 TCP connect failed",
      "take plan 1",
    )

    taskFaker.assertNoMoreTasks()
  }

  @Test
  fun firstConnectionFailsToConnectAndSecondFailureIsSuppressedException() {
    val plan0 = routePlanner.addPlan()
    plan0.tcpConnectThrowable = IOException("boom 0!")
    val plan1 = routePlanner.addPlan()
    plan1.tcpConnectThrowable = IOException("boom 1!")

    taskRunner.newQueue().execute("connect") {
      assertFailsWith<IOException> {
        finder.find()
      }.also { expected ->
        assertThat(expected).hasMessage("boom 0!")
        assertThat(expected.suppressed.single()).hasMessage("boom 1!")
      }
    }

    taskFaker.runTasks()
    assertEvents(
      "take plan 0",
      "plan 0 TCP connecting...",
      "plan 0 TCP connect failed",
      "take plan 1",
      "plan 1 TCP connecting...",
      "plan 1 TCP connect failed",
    )

    taskFaker.assertNoMoreTasks()
  }

  @Test
  fun firstConnectionCrashesWithUncheckedException() {
    val plan0 = routePlanner.addPlan()
    plan0.tcpConnectThrowable = IllegalStateException("boom!")
    routePlanner.addPlan() // This plan should not be used.

    taskRunner.newQueue().execute("connect") {
      assertFailsWith<IllegalStateException> {
        finder.find()
      }.also { expected ->
        assertThat(expected).hasMessage("boom!")
      }
    }

    taskFaker.runTasks()
    assertEvents(
      "take plan 0",
      "plan 0 TCP connecting...",
      "plan 0 TCP connect failed",
    )

    taskFaker.assertNoMoreTasks()
  }

  @Test
  fun routePlannerPlanThrowsOnOnlyPlan() {
    val plan0 = routePlanner.addPlan()
    plan0.planningThrowable = UnknownServiceException("boom!")

    taskRunner.newQueue().execute("connect") {
      assertFailsWith<UnknownServiceException> {
        finder.find()
      }.also { expected ->
        assertThat(expected).hasMessage("boom!")
      }
    }

    taskFaker.runTasks()
    assertEvents(
      "take plan 0",
    )

    taskFaker.assertNoMoreTasks()
  }

  @Test
  fun recoversAfterFirstPlanCallThrows() {
    val plan0 = routePlanner.addPlan()
    plan0.planningThrowable = UnknownServiceException("boom!")
    val plan1 = routePlanner.addPlan()

    taskRunner.newQueue().execute("connect") {
      val result0 = finder.find()
      assertThat(result0).isEqualTo(plan1.connection)
    }

    taskFaker.runTasks()
    assertEvents(
      "take plan 0",
      "take plan 1",
      "plan 1 TCP connecting...",
      "plan 1 TCP connected",
      "plan 1 TLS connecting...",
      "plan 1 TLS connected",
    )

    taskFaker.assertNoMoreTasks()
  }

  @Test
  fun retryConnectionThatLostTcpRaceAfterWinnersTlsFails() {
    val plan0 = routePlanner.addPlan()
    plan0.tcpConnectDelayNanos = 270.ms

    val plan1 = routePlanner.addPlan()
    plan1.tcpConnectDelayNanos = 10.ms // TCP connect at time = 260 ms.
    plan1.tlsConnectThrowable = IOException("boom!")

    val plan2 = plan0.createRetry()

    taskRunner.newQueue().execute("connect") {
      val result0 = finder.find()
      assertThat(result0).isEqualTo(plan2.connection)
    }

    taskFaker.runTasks()
    assertEvents(
      "take plan 0",
      "plan 0 TCP connecting...",
    )

    taskFaker.advanceUntil(250.ms)
    assertEvents(
      "take plan 1",
      "plan 1 TCP connecting...",
    )

    taskFaker.advanceUntil(260.ms)
    assertEvents(
      "plan 1 TCP connected",
      "plan 0 cancel",
      "plan 1 TLS connecting...",
      "plan 1 TLS connect failed",
      "plan 2 TCP connecting...",
      "plan 2 TCP connected",
      "plan 2 TLS connecting...",
      "plan 2 TLS connected",
    )

    taskFaker.advanceUntil(270.ms)
    assertEvents(
      "plan 0 TCP connect canceled",
    )

    taskFaker.assertNoMoreTasks()
  }

  @Test
  fun losingPlanDoesNotConnectTls() {
    val plan0 = routePlanner.addPlan()
    plan0.tcpConnectDelayNanos = 270.ms
    val plan1 = routePlanner.addPlan()
    plan1.tcpConnectDelayNanos = 10.ms // Connect at time = 260 ms.
    plan1.tlsConnectDelayNanos = 20.ms // Connect at time = 280 ms.

    taskRunner.newQueue().execute("connect") {
      val result0 = finder.find()
      assertThat(result0).isEqualTo(plan0.connection)
    }

    taskFaker.runTasks()
    assertEvents(
      "take plan 0",
      "plan 0 TCP connecting...",
    )

    taskFaker.advanceUntil(250.ms)
    assertEvents(
      "take plan 1",
      "plan 1 TCP connecting...",
    )

    taskFaker.advanceUntil(260.ms)
    assertEvents(
      "plan 1 TCP connected",
      "plan 0 cancel",
      "plan 1 TLS connecting...",
    )

    taskFaker.advanceUntil(270.ms)
    assertEvents(
      "plan 0 TCP connect canceled",
    )

    taskFaker.advanceUntil(280.ms)
    assertEvents(
      "plan 1 TLS connected",
    )

    taskFaker.assertNoMoreTasks()
  }

  @Test
  fun tcpConnectFollowUpPlanUsed() {
    val plan0 = routePlanner.addPlan()
    val plan1 = plan0.createConnectTcpNextPlan()

    taskRunner.newQueue().execute("connect") {
      val result0 = finder.find()
      assertThat(result0).isEqualTo(plan1.connection)
    }

    taskFaker.runTasks()
    assertEvents(
      "take plan 0",
      "plan 0 TCP connecting...",
      "plan 0 needs follow-up",
      "plan 1 TCP connecting...",
      "plan 1 TCP connected",
      "plan 1 TLS connecting...",
      "plan 1 TLS connected",
    )

    taskFaker.assertNoMoreTasks()
  }

  @Test
  fun tlsConnectFollowUpPlanUsed() {
    val plan0 = routePlanner.addPlan()
    val plan1 = plan0.createConnectTlsNextPlan()

    taskRunner.newQueue().execute("connect") {
      val result0 = finder.find()
      assertThat(result0).isEqualTo(plan1.connection)
    }

    taskFaker.runTasks()
    assertEvents(
      "take plan 0",
      "plan 0 TCP connecting...",
      "plan 0 TCP connected",
      "plan 0 TLS connecting...",
      "plan 0 needs follow-up",
      "plan 1 TCP connecting...",
      "plan 1 TCP connected",
      "plan 1 TLS connecting...",
      "plan 1 TLS connected",
    )

    taskFaker.assertNoMoreTasks()
  }

  /**
   * This test performs two races:
   *
   *  * The first race is between plan0 and plan1, with a 250 ms head start for plan0.
   *  * The second race is between plan2 and plan3, with a 250 ms head start for plan2.
   *
   * We get plan0 and plan1 from the route planner.
   * We get plan2 as a follow-up to plan1, typically retry the same IP but different TLS.
   * We get plan3 as a retry of plan0, which was canceled when it lost the race.
   *
   * This test confirms that we prefer to do the TLS follow-up (plan2) before the TCP retry (plan3).
   * It also confirms we enforce the 250 ms delay in each race.
   */
  @Test
  fun tcpConnectionsRaceAfterTlsFails() {
    val plan0 = routePlanner.addPlan()
    plan0.tcpConnectDelayNanos = 280.ms

    val plan1 = routePlanner.addPlan()
    plan1.tcpConnectDelayNanos = 10.ms // Connect at time = 260 ms.
    plan1.tlsConnectDelayNanos = 10.ms // Connect at time = 270 ms.
    plan1.tlsConnectThrowable = IOException("boom!")

    val plan2 = plan1.createConnectTlsNextPlan()
    plan2.tcpConnectDelayNanos = 270.ms // Connect at time = 540 ms.

    val plan3 = plan0.createRetry()
    plan3.tcpConnectDelayNanos = 10.ms // Connect at time = 530 ms.

    taskRunner.newQueue().execute("connect") {
      val result0 = finder.find()
      assertThat(result0).isEqualTo(plan3.connection)
    }

    taskFaker.runTasks()
    assertEvents(
      "take plan 0",
      "plan 0 TCP connecting...",
    )

    taskFaker.advanceUntil(250.ms)
    assertEvents(
      "take plan 1",
      "plan 1 TCP connecting...",
    )

    taskFaker.advanceUntil(260.ms)
    assertEvents(
      "plan 1 TCP connected",
      "plan 0 cancel",
      "plan 1 TLS connecting...",
    )

    taskFaker.advanceUntil(270.ms)
    assertEvents(
      "plan 1 TLS connect failed",
      "plan 2 TCP connecting...",
    )

    taskFaker.advanceUntil(280.ms)
    assertEvents(
      "plan 0 TCP connect canceled",
    )

    taskFaker.advanceUntil(520.ms)
    assertEvents(
      "plan 3 TCP connecting...",
    )

    taskFaker.advanceUntil(530.ms)
    assertEvents(
      "plan 3 TCP connected",
      "plan 2 cancel",
      "plan 3 TLS connecting...",
      "plan 3 TLS connected",
    )

    taskFaker.advanceUntil(540.ms)
    assertEvents(
      "plan 2 TCP connect canceled",
    )

    taskFaker.assertNoMoreTasks()
  }

  /**
   * This test puts several connections in flight that all fail at approximately the same time. It
   * confirms the fast fallback implements these invariants:
   *
   *  * if there's no TCP connect in flight, start one.
   *  * don't start a new TCP connect within 250 ms of the previous TCP connect.
   */
  @Test
  fun minimumDelayEnforcedBetweenConnects() {
    val plan0 = routePlanner.addPlan()
    plan0.tcpConnectDelayNanos = 510.ms
    plan0.tcpConnectThrowable = IOException("boom!")
    val plan1 = routePlanner.addPlan()
    plan1.tcpConnectDelayNanos = 270.ms // Connect fail at time = 520 ms.
    plan1.tcpConnectThrowable = IOException("boom!")
    val plan2 = routePlanner.addPlan()
    plan2.tcpConnectDelayNanos = 30.ms // Connect fail at time = 530 ms.
    plan2.tcpConnectThrowable = IOException("boom!")
    val plan3 = routePlanner.addPlan()
    plan3.tcpConnectDelayNanos = 270.ms // Connect at time 800 ms.
    val plan4 = routePlanner.addPlan()
    plan4.tcpConnectDelayNanos = 10.ms // Connect at time 790 ms.

    taskRunner.newQueue().execute("connect") {
      val result0 = finder.find()
      assertThat(result0).isEqualTo(plan4.connection)
    }

    taskFaker.runTasks()
    assertEvents(
      "take plan 0",
      "plan 0 TCP connecting...",
    )

    taskFaker.advanceUntil(250.ms)
    assertEvents(
      "take plan 1",
      "plan 1 TCP connecting...",
    )

    taskFaker.advanceUntil(500.ms)
    assertEvents(
      "take plan 2",
      "plan 2 TCP connecting...",
    )

    taskFaker.advanceUntil(510.ms)
    assertEvents(
      "plan 0 TCP connect failed",
    )

    taskFaker.advanceUntil(520.ms)
    assertEvents(
      "plan 1 TCP connect failed",
    )

    taskFaker.advanceUntil(530.ms)
    assertEvents(
      "plan 2 TCP connect failed",
      "take plan 3",
      "plan 3 TCP connecting...",
    )

    taskFaker.advanceUntil(780.ms)
    assertEvents(
      "take plan 4",
      "plan 4 TCP connecting...",
    )

    taskFaker.advanceUntil(790.ms)
    assertEvents(
      "plan 4 TCP connected",
      "plan 3 cancel",
      "plan 4 TLS connecting...",
      "plan 4 TLS connected",
    )

    taskFaker.advanceUntil(800.ms)
    assertEvents(
      "plan 3 TCP connect canceled",
    )

    taskFaker.assertNoMoreTasks()
  }

  /**
   * This test causes two connections to become available simultaneously, one from a TCP connect and
   * one from the pool. We must take the pooled connection because by taking it from the pool, we've
   * fully acquired it.
   *
   * This test yields threads to force the decision of plan1 to be deliberate and not lucky. In
   * particular, we set up this sequence of events:
   *
   *  1. take plan 0
   *  3. plan 0 connects
   *  4. finish taking plan 1
   *
   * https://github.com/lysine-dev/okhttp/issues/7152
   */
  @Test
  fun reusePlanAndNewConnectRace() {
    val plan0 = routePlanner.addPlan()
    plan0.tcpConnectDelayNanos = 250.ms
    plan0.yieldBeforeTcpConnectReturns = true // Yield so we get a chance to take plan1...
    val plan1 = routePlanner.addPlan()
    plan1.connectState = TLS_CONNECTED
    plan1.yieldBeforePlanReturns = true // ... but let plan 0 connect before we act upon it.

    taskRunner.newQueue().execute("connect") {
      val result0 = finder.find()
      assertThat(result0).isEqualTo(plan0.connection)
    }

    taskFaker.runTasks()
    assertEvents(
      "take plan 0",
      "plan 0 TCP connecting...",
    )

    taskFaker.advanceUntil(250.ms)
    assertEvents(
      "take plan 1",
      "plan 0 cancel",
      "plan 0 TCP connect canceled",
    )
  }

  private fun assertEvents(vararg expected: String) {
    val actual = generateSequence { routePlanner.events.poll() }.toList()
    assertThat(actual).containsExactly(*expected)
  }

  private val Int.ms: Long
    get() = this * 1_000_000L
}
