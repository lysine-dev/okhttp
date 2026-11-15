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
@file:Suppress("ktlint:standard:filename")

package okhttp3.internal.dns

import java.io.IOException
import java.net.UnknownHostException
import java.util.concurrent.atomic.AtomicReference
import okhttp3.Dns
import okhttp3.internal.OkHttpInternalApi
import okhttp3.internal.concurrent.Task
import okhttp3.internal.concurrent.TaskRunner
import okhttp3.internal.testAndSet

/**
 * Adapts the blocking [Dns.lookup] function (introduced in 2015) to the non-blocking [Dns.newCall]
 * API (introduced in 2026), using [TaskRunner] to provide a thread for background execution.
 *
 * When canceled, the callback is immediately notified but the in-flight call is run to completion
 * and discarded.
 */
@OkHttpInternalApi
class LookupDnsCall(
  private val taskRunner: TaskRunner,
  private val delegate: Dns,
  override val request: Dns.Request,
) : Task("${request.hostname} dns", cancelable = false),
  Dns.Call {
  private val state = AtomicReference<State>(State.Idle)

  override fun enqueue(callback: Dns.Callback) {
    val previous = state.testAndSet(State.Running(callback)) { it is State.Idle }
    when (previous) {
      is State.Idle -> taskRunner.newQueue().schedule(this)
      is State.Running, State.Complete -> throw IllegalStateException("already enqueued")
      State.Canceled -> callback.onFailure(this, IOException("canceled"))
    }
  }

  override fun cancel() {
    val previous = state.testAndSet(State.Canceled) { it is State.Running || it == State.Idle }
    (previous as? State.Running)?.callback?.onFailure(this, IOException("canceled"))
  }

  override fun isCanceled() = state.get() is State.Canceled

  override fun runOnce(): Long {
    try {
      val inetAddresses = delegate.lookup(request.hostname)
      val previous = state.testAndSet(State.Complete) { it is State.Running }
      (previous as? State.Running)?.callback?.onRecords(
        call = this,
        last = true,
        records = inetAddresses.map { Dns.Record.IpAddress(request.hostname, it) },
      )
    } catch (e: UnknownHostException) {
      val previous = state.testAndSet(State.Complete) { it is State.Running }
      (previous as? State.Running)?.callback?.onFailure(
        call = this,
        e = e,
      )
    }
    return -1L
  }

  private sealed interface State {
    object Idle : State

    class Running(
      val callback: Dns.Callback,
    ) : State

    object Complete : State

    object Canceled : State
  }
}
