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

import java.io.IOException
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.LinkedBlockingDeque
import java.util.concurrent.TimeUnit
import okhttp3.internal.OkHttpInternalApi
import okhttp3.internal.concurrent.Task
import okhttp3.internal.concurrent.TaskRunner
import okhttp3.internal.connection.RoutePlanner.ConnectResult
import okhttp3.internal.connection.RoutePlanner.Plan
import okhttp3.internal.okHttpName

/**
 * Speculatively connects to each IP address of a target address, returning as soon as one of them
 * connects successfully. This kicks off new attempts every 250 ms until a connect succeeds.
 */
@OkHttpInternalApi
internal class FastFallbackExchangeFinder(
  override val routePlanner: RoutePlanner,
  private val taskRunner: TaskRunner,
) : ExchangeFinder {
  private val connectDelayNanos = TimeUnit.MILLISECONDS.toNanos(250L)
  private var nextTcpConnectAtNanos = Long.MIN_VALUE

  /**
   * Plans currently being connected, and that will later be added to [connectResults]. This is
   * mutated by the call thread only. If is accessed by background connect threads.
   */
  private val tcpConnectsInFlight = CopyOnWriteArrayList<Plan>()

  /**
   * Results are posted here as they occur. The find job is done when either one plan completes
   * successfully or all plans fail.
   */
  private val connectResults = taskRunner.backend.decorate(LinkedBlockingDeque<ConnectResult>())

  override fun find(): RealConnection {
    var firstException: IOException? = null
    try {
      while (tcpConnectsInFlight.isNotEmpty() || routePlanner.hasNext()) {
        if (routePlanner.isCanceled()) throw IOException("Canceled")

        // Launch a new connection if we're ready to.
        val now = taskRunner.backend.nanoTime()
        var awaitTimeoutNanos = nextTcpConnectAtNanos - now
        var connectResult: ConnectResult? = null
        if (tcpConnectsInFlight.isEmpty() || awaitTimeoutNanos <= 0) {
          connectResult = launchTcpConnect()
          nextTcpConnectAtNanos = now + connectDelayNanos
          awaitTimeoutNanos = connectDelayNanos
        }

        // Wait for an in-flight connect to complete or fail.
        if (connectResult == null) {
          connectResult = awaitTcpConnect(awaitTimeoutNanos, TimeUnit.NANOSECONDS) ?: continue
        }

        if (connectResult.isSuccess) {
          // We have a connected TCP connection. Cancel and defer the racing connects that all lost.
          cancelInFlightConnects()

          // Finish connecting. We won't have to if the winner is from the connection pool.
          if (!connectResult.plan.isReady) {
            connectResult = connectResult.plan.connectTlsEtc()
          }

          if (connectResult.isSuccess) {
            return connectResult.plan.handleSuccess()
          }
        }

        val throwable = connectResult.throwable
        if (throwable != null) {
          if (throwable !is IOException) throw throwable
          if (firstException == null) {
            firstException = throwable
          } else {
            firstException.addSuppressed(throwable)
          }
          if (!attemptAnotherConnection(throwable)) break
        }

        val nextPlan = connectResult.nextPlan
        if (nextPlan != null) {
          // Try this plan's successor before deferred plans because it won the race!
          routePlanner.deferredPlans.addFirst(nextPlan)
        }
      }
    } finally {
      cancelInFlightConnects()
    }

    throw firstException!!
  }

  /**
   * Returns non-null if we don't need to wait for the launched result. In such cases, this result
   * must be processed before whatever is waiting in the queue because we may have already acquired
   * its connection.
   */
  private fun launchTcpConnect(): ConnectResult? {
    val plan =
      when {
        routePlanner.hasNext() -> {
          try {
            routePlanner.plan()
          } catch (e: Throwable) {
            FailedPlan(e)
          }
        }

        else -> {
          return null
        } // Nothing further to try.
      }

    // Already connected. Return it immediately.
    if (plan.isReady) return ConnectResult(plan)

    // Already failed? Return it immediately.
    if (plan is FailedPlan) return plan.result

    // Connect TCP asynchronously.
    tcpConnectsInFlight += plan
    val taskName = "$okHttpName connect ${routePlanner.address.url.redact()}"
    taskRunner.newQueue().schedule(
      object : Task(taskName) {
        override fun runOnce(): Long {
          val connectResult =
            try {
              plan.connectTcp()
            } catch (e: Throwable) {
              ConnectResult(plan, throwable = e)
            }
          // Only post a result if this hasn't since been canceled.
          if (plan in tcpConnectsInFlight) {
            connectResults.put(connectResult)
          }
          return -1L
        }
      },
    )
    return null
  }

  private fun awaitTcpConnect(
    timeout: Long,
    unit: TimeUnit,
  ): ConnectResult? {
    if (tcpConnectsInFlight.isEmpty()) return null

    val result = connectResults.poll(timeout, unit) ?: return null

    tcpConnectsInFlight.remove(result.plan)

    return result
  }

  private fun cancelInFlightConnects() {
    for (plan in tcpConnectsInFlight) {
      plan.cancel()
      val retry = plan.retry() ?: continue
      routePlanner.deferredPlans.addLast(retry)
    }
    tcpConnectsInFlight.clear()
  }
}
