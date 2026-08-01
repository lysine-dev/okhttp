/*
 * Copyright (c) 2026 OkHttp Authors
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

package okhttp3.internal.tracing

import androidx.tracing.Trace
import okhttp3.Call
import okhttp3.Connection
import okhttp3.Route
import okhttp3.internal.connection.ConnectionListener
import okio.IOException

/**
 * Tracing implementation of ConnectionListener that marks the lifetime of each connection
 * in Perfetto traces.
 */
internal class AndroidxTracingConnectionListener(
    private val delegate: ConnectionListener = NONE,
    val traceLabel: (Route) -> String = { it.defaultTracingLabel },
) : ConnectionListener() {
  override fun connectStart(
      connectionId: Long,
      route: Route,
      call: Call,
  ) {
    Trace.beginAsyncSection(labelForTrace(route), connectionId.toInt())
    delegate.connectStart(connectionId, route, call)
  }

  override fun connectFailed(
      connectionId: Long,
      route: Route,
      call: Call,
      failure: IOException,
  ) {
    Trace.endAsyncSection(labelForTrace(route), connectionId.toInt())
    delegate.connectFailed(connectionId, route, call, failure)
  }

  override fun connectEnd(
      connection: Connection,
      route: Route,
      call: Call,
  ) {
    delegate.connectEnd(connection, route, call)
  }

  override fun connectionClosed(connection: Connection) {
    Trace.endAsyncSection(labelForTrace(connection.route()), connection.id.toInt())
    delegate.connectionClosed(connection)
  }

  private fun labelForTrace(route: Route): String = traceLabel(route).take(AndroidxTracingInterceptor.Companion.MAX_TRACE_LABEL_LENGTH)

  override fun connectionAcquired(
      connection: Connection,
      call: Call,
  ) {
    delegate.connectionAcquired(connection, call)
  }

  override fun connectionReleased(
      connection: Connection,
      call: Call,
  ) {
    delegate.connectionReleased(connection, call)
  }

  override fun noNewExchanges(connection: Connection) {
    delegate.noNewExchanges(connection)
  }

  companion object {
    val Route.defaultTracingLabel: String
      get() = this.address.url.host
  }
}
