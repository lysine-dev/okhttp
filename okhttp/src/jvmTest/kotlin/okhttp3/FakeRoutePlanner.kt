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
package okhttp3

import java.io.Closeable
import java.io.IOException
import java.util.concurrent.LinkedBlockingDeque
import okhttp3.internal.concurrent.TaskFaker
import okhttp3.internal.connection.RealConnection
import okhttp3.internal.connection.RoutePlanner
import okhttp3.internal.connection.RoutePlanner.ConnectResult

class FakeRoutePlanner(
  val factory: TestValueFactory = TestValueFactory(),
  val taskFaker: TaskFaker = factory.taskFaker,
) : RoutePlanner,
  Closeable {
  val pool = factory.newConnectionPool()
  val events = LinkedBlockingDeque<String>()
  var canceled = false
  var autoGeneratePlans = false
  var defaultConnectionIdleAtNanos = Long.MAX_VALUE
  private var nextPlanId = 0
  private var nextPlanIndex = 0
  val plans = mutableListOf<FakePlan>()

  override val deferredPlans = ArrayDeque<RoutePlanner.Plan>()

  override val address = factory.newAddress("example.com")

  fun addPlan(): FakePlan =
    FakePlan(nextPlanId++).also {
      plans += it
    }

  override fun isCanceled() = canceled

  override fun plan(): FakePlan {
    // Return deferred plans preferentially. These don't require addPlan().
    if (deferredPlans.isNotEmpty()) return deferredPlans.removeFirst() as FakePlan

    if (nextPlanIndex >= plans.size && autoGeneratePlans) addPlan()

    require(nextPlanIndex < plans.size) {
      "not enough plans! call addPlan() or set autoGeneratePlans=true in the test to set this up"
    }
    val result = plans[nextPlanIndex++]
    events += "take plan ${result.id}"

    if (result.yieldBeforePlanReturns) {
      taskFaker.yield()
    }

    val planningThrowable = result.planningThrowable
    if (planningThrowable != null) throw planningThrowable

    return result
  }

  override fun hasNext(failedConnection: RealConnection?): Boolean =
    deferredPlans.isNotEmpty() || nextPlanIndex < plans.size || autoGeneratePlans

  override fun sameHostAndPort(url: HttpUrl): Boolean =
    url.host == address.url.host && url.port == address.url.port && url.scheme == address.url.scheme

  override fun close() {
    factory.close()
  }

  inner class FakePlan(
    val id: Int,
  ) : RoutePlanner.Plan {
    var planningThrowable: Throwable? = null
    var canceled = false
    var connectState = ConnectState.READY
    val connection =
      factory.newConnection(
        pool = pool,
        route = factory.newRoute(address),
        idleAtNanos = defaultConnectionIdleAtNanos,
      )
    var retry: FakePlan? = null
    var retryTaken = false
    var yieldBeforePlanReturns = false

    override val isReady: Boolean
      get() = connectState == ConnectState.TLS_CONNECTED

    var tcpConnectDelayNanos = 0L
    var tcpConnectThrowable: Throwable? = null
    var yieldBeforeTcpConnectReturns = false
    var connectTcpNextPlan: FakePlan? = null
    var tlsConnectDelayNanos = 0L
    var tlsConnectThrowable: Throwable? = null
    var connectTlsNextPlan: FakePlan? = null

    fun createRetry(): FakePlan {
      check(retry == null)
      return FakePlan(nextPlanId++)
        .also {
          retry = it
        }
    }

    fun createConnectTcpNextPlan(): FakePlan {
      check(connectTcpNextPlan == null)
      return FakePlan(nextPlanId++)
        .also {
          connectTcpNextPlan = it
        }
    }

    fun createConnectTlsNextPlan(): FakePlan {
      check(connectTlsNextPlan == null)
      return FakePlan(nextPlanId++)
        .also {
          connectTlsNextPlan = it
        }
    }

    override fun connectTcp(): ConnectResult {
      check(connectState == ConnectState.READY)
      events += "plan $id TCP connecting..."

      taskFaker.sleep(tcpConnectDelayNanos)

      if (yieldBeforeTcpConnectReturns) {
        taskFaker.yield()
      }

      return when {
        tcpConnectThrowable != null -> {
          events += "plan $id TCP connect failed"
          ConnectResult(this, nextPlan = connectTcpNextPlan, throwable = tcpConnectThrowable)
        }

        canceled -> {
          events += "plan $id TCP connect canceled"
          ConnectResult(this, nextPlan = connectTcpNextPlan, throwable = IOException("canceled"))
        }

        connectTcpNextPlan != null -> {
          events += "plan $id needs follow-up"
          ConnectResult(this, nextPlan = connectTcpNextPlan)
        }

        else -> {
          events += "plan $id TCP connected"
          connectState = ConnectState.TCP_CONNECTED
          ConnectResult(this)
        }
      }
    }

    override fun connectTlsEtc(): ConnectResult {
      check(connectState == ConnectState.TCP_CONNECTED)
      events += "plan $id TLS connecting..."

      taskFaker.sleep(tlsConnectDelayNanos)

      return when {
        tlsConnectThrowable != null -> {
          events += "plan $id TLS connect failed"
          ConnectResult(this, nextPlan = connectTlsNextPlan, throwable = tlsConnectThrowable)
        }

        canceled -> {
          events += "plan $id TLS connect canceled"
          ConnectResult(this, nextPlan = connectTlsNextPlan, throwable = IOException("canceled"))
        }

        connectTlsNextPlan != null -> {
          events += "plan $id needs follow-up"
          ConnectResult(this, nextPlan = connectTlsNextPlan)
        }

        else -> {
          events += "plan $id TLS connected"
          connectState = ConnectState.TLS_CONNECTED
          ConnectResult(this)
        }
      }
    }

    override fun handleSuccess() = connection

    override fun cancel() {
      events += "plan $id cancel"
      canceled = true
    }

    override fun retry(): FakePlan? {
      check(!retryTaken)
      retryTaken = true
      return retry
    }
  }

  enum class ConnectState {
    READY,
    TCP_CONNECTED,
    TLS_CONNECTED,
  }
}
