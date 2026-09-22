/*
 * Copyright (C) 2015 Square, Inc.
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
import okhttp3.internal.OkHttpInternalApi

/** Attempt routes one at a time until one connects. */
@OkHttpInternalApi
internal class SequentialExchangeFinder(
  override val routePlanner: RoutePlanner,
) : ExchangeFinder {
  override fun find(): RealConnection {
    var firstException: IOException? = null
    while (true) {
      if (routePlanner.isCanceled()) throw IOException("Canceled")

      try {
        val plan = routePlanner.plan()

        if (!plan.isReady) {
          val tcpConnectResult = plan.connectTcp()
          val connectResult =
            when {
              tcpConnectResult.isSuccess -> plan.connectTlsEtc()
              else -> tcpConnectResult
            }

          val (_, nextPlan, failure) = connectResult

          if (failure != null) throw failure
          if (nextPlan != null) {
            routePlanner.deferredPlans.addFirst(nextPlan)
            continue
          }
        }
        return plan.handleSuccess()
      } catch (e: IOException) {
        if (firstException == null) {
          firstException = e
        } else {
          firstException.addSuppressed(e)
        }
        if (!attemptAnotherConnection(e)) {
          throw firstException
        }
        if (!routePlanner.hasNext()) {
          throw firstException
        }
      }
    }
  }
}
