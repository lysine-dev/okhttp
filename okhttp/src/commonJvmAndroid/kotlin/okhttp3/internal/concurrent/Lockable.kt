/*
 * Copyright (C) 2025 Block, Inc.
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
@file:Suppress("PLATFORM_CLASS_MAPPED_TO_KOTLIN", "NOTHING_TO_INLINE")

package okhttp3.internal.concurrent

import kotlin.contracts.ExperimentalContracts
import kotlin.contracts.InvocationKind
import kotlin.contracts.contract
import okhttp3.internal.OkHttpInternalApi
import okhttp3.internal.assertionsEnabled

/**
 * Marker interface for objects that use the JVM's `synchronized` mechanism and the related
 * `wait()` and `notify()` functions.
 *
 * The Lockable interface is particularly handy because it ensures we lock the right `this` when
 * there are multiple `this` objects in scope.
 */
interface Lockable

@OkHttpInternalApi
inline fun Lockable.wait() = (this as Object).wait()

@OkHttpInternalApi
inline fun Lockable.notify() = (this as Object).notify()

@OkHttpInternalApi
inline fun Lockable.notifyAll() = (this as Object).notifyAll()

@OkHttpInternalApi
inline fun Lockable.awaitNanos(nanos: Long) {
  val ms = nanos / 1_000_000L
  val ns = nanos - (ms * 1_000_000L)
  if (ms > 0L || nanos > 0) {
    (this as Object).wait(ms, ns.toInt())
  }
}

internal inline fun Lockable.assertLockNotHeld() {
  if (assertionsEnabled && Thread.holdsLock(this)) {
    throw AssertionError("Thread ${Thread.currentThread().name} MUST NOT hold lock on $this")
  }
}

internal inline fun Lockable.assertLockHeld() {
  if (assertionsEnabled && !Thread.holdsLock(this)) {
    throw AssertionError("Thread ${Thread.currentThread().name} MUST hold lock on $this")
  }
}

@OptIn(ExperimentalContracts::class)
inline fun <T> Lockable.withLock(action: () -> T): T {
  contract { callsInPlace(action, InvocationKind.EXACTLY_ONCE) }
  return synchronized(this, action)
}
