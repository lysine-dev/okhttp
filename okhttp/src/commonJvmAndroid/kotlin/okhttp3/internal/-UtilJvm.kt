/*
 * Copyright (C) 2012 The Android Open Source Project
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

package okhttp3.internal

import java.io.IOException
import java.io.InterruptedIOException
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.nio.charset.Charset
import java.util.Collections
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.ThreadFactory
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.text.Charsets.UTF_16BE
import kotlin.text.Charsets.UTF_16LE
import kotlin.text.Charsets.UTF_32BE
import kotlin.text.Charsets.UTF_32LE
import kotlin.text.Charsets.UTF_8
import kotlin.time.Duration
import okhttp3.Dispatcher
import okhttp3.EventListener
import okhttp3.Headers
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.defaultPort
import okhttp3.OkHttpClient
import okhttp3.Response
import okhttp3.internal.http2.Header
import okio.Buffer
import okio.BufferedSource
import okio.Source

/** GMT and UTC are equivalent for our purposes. */
@JvmField
internal val UTC: TimeZone = TimeZone.getTimeZone("GMT")!!

internal fun threadFactory(
  name: String,
  daemon: Boolean,
): ThreadFactory =
  ThreadFactory { runnable ->
    Thread(runnable, name).apply {
      isDaemon = daemon
    }
  }

internal fun HttpUrl.toHostHeader(includeDefaultPort: Boolean = false): String {
  val host =
    if (":" in host) {
      "[$host]"
    } else {
      host
    }
  return if (includeDefaultPort || port != defaultPort(scheme)) {
    "$host:$port"
  } else {
    host
  }
}

/** Returns a [Locale.US] formatted [String]. */
internal fun format(
  format: String,
  vararg args: Any,
): String = String.format(Locale.US, format, *args)

/**
 * will also strip BOM from the source
 */
@Throws(IOException::class)
internal fun BufferedSource.readBomAsCharset(default: Charset): Charset =
  when (select(UNICODE_BOMS)) {
    // a mapping from the index of encoding methods in UNICODE_BOMS to its corresponding encoding method
    0 -> UTF_8

    1 -> UTF_16BE

    2 -> UTF_32LE

    3 -> UTF_16LE

    4 -> UTF_32BE

    -1 -> default

    else -> throw AssertionError()
  }

internal fun checkDuration(
  name: String,
  duration: Long,
  unit: TimeUnit,
): Int {
  check(duration >= 0L) { "$name < 0" }
  val millis = unit.toMillis(duration)
  require(millis <= Integer.MAX_VALUE) { "$name too large" }
  require(millis != 0L || duration <= 0L) { "$name too small" }
  return millis.toInt()
}

internal fun checkDuration(
  name: String,
  duration: Duration,
): Int {
  check(!duration.isNegative()) { "$name < 0" }
  val millis = duration.inWholeMilliseconds
  require(millis <= Integer.MAX_VALUE) { "$name too large" }
  require(millis != 0L || !duration.isPositive()) { "$name too small" }
  return millis.toInt()
}

internal fun List<Header>.toHeaders(): Headers {
  val builder = Headers.Builder()
  for ((name, value) in this) {
    builder.addLenient(name.utf8(), value.utf8())
  }
  return builder.build()
}

internal fun Headers.toHeaderList(): List<Header> =
  (0 until size).map {
    Header(name(it), value(it))
  }

/** Returns true if an HTTP request for this URL and [other] can reuse a connection. */
internal fun HttpUrl.canReuseConnectionFor(other: HttpUrl): Boolean =
  host == other.host &&
    port == other.port &&
    scheme == other.scheme

internal fun EventListener.asFactory() = EventListener.Factory { this }

/**
 * Reads until this is exhausted or the deadline has been reached. This is careful to not extend the
 * deadline if one exists already.
 */
@Throws(IOException::class)
internal fun Source.skipAll(
  duration: Int,
  timeUnit: TimeUnit,
): Boolean {
  val nowNs = System.nanoTime()
  val originalDurationNs =
    if (timeout().hasDeadline()) {
      timeout().deadlineNanoTime() - nowNs
    } else {
      Long.MAX_VALUE
    }
  timeout().deadlineNanoTime(nowNs + minOf(originalDurationNs, timeUnit.toNanos(duration.toLong())))
  return try {
    val skipBuffer = Buffer()
    while (read(skipBuffer, 8192) != -1L) {
      skipBuffer.clear()
    }
    true // Success! The source has been exhausted.
  } catch (_: InterruptedIOException) {
    false // We ran out of time before exhausting the source.
  } finally {
    if (originalDurationNs == Long.MAX_VALUE) {
      timeout().clearDeadline()
    } else {
      timeout().deadlineNanoTime(nowNs + originalDurationNs)
    }
  }
}

@Throws(IOException::class)
internal fun BufferedSource.skipAll() {
  while (!exhausted()) {
    skip(buffer.size)
  }
}

/**
 * Attempts to exhaust this, returning true if successful. This is useful when reading a complete
 * source is helpful, such as when doing so completes a cache body or frees a socket connection for
 * reuse.
 */
internal fun Source.discard(
  timeout: Int,
  timeUnit: TimeUnit,
): Boolean =
  try {
    this.skipAll(timeout, timeUnit)
  } catch (_: IOException) {
    false
  }

/**
 * Returns true if new reads and writes should be attempted on this.
 *
 * Unfortunately Java's networking APIs don't offer a good health check, so we go on our own by
 * attempting to read with a short timeout. If the fails immediately we know the socket is
 * unhealthy.
 *
 * @param source the source used to read bytes from the socket.
 */
internal fun Socket.isHealthy(source: BufferedSource): Boolean =
  try {
    val readTimeout = soTimeout
    try {
      soTimeout = 1
      !source.exhausted()
    } finally {
      soTimeout = readTimeout
    }
  } catch (_: SocketTimeoutException) {
    true // Read timed out; socket is good.
  } catch (_: IOException) {
    false // Couldn't read; socket is closed.
  }

internal inline fun threadName(
  name: String,
  block: () -> Unit,
) {
  val currentThread = Thread.currentThread()
  val oldName = currentThread.name
  currentThread.name = name
  try {
    block()
  } finally {
    currentThread.name = oldName
  }
}

/** Returns the Content-Length as reported by the response headers. */
internal fun Response.headersContentLength(): Long = headers["Content-Length"]?.toLongOrDefault(-1L) ?: -1L

/** Returns an immutable wrap of this. */
@Suppress("NOTHING_TO_INLINE")
internal inline fun <T> List<T>.unmodifiable(): List<T> = Collections.unmodifiableList(this)

/** Returns an immutable wrap of this. */
@Suppress("NOTHING_TO_INLINE")
internal inline fun <T> Set<T>.unmodifiable(): Set<T> = Collections.unmodifiableSet(this)

/** Returns an immutable wrap of this. */
@Suppress("NOTHING_TO_INLINE")
internal inline fun <K, V> Map<K, V>.unmodifiable(): Map<K, V> = Collections.unmodifiableMap(this)

/** Returns an immutable copy of this. */
@Suppress("UNCHECKED_CAST")
internal fun <T> List<T>.toImmutableList(): List<T> =
  when {
    this.isEmpty() -> emptyList()

    this.size == 1 -> Collections.singletonList(this[0])

    // Collection.toArray returns Object[] (covariant).
    // It is faster than creating real T[] via reflection (Arrays.copyOf).
    else -> (this as java.util.Collection<*>).toArray().asList().unmodifiable() as List<T>
  }

/** Returns an immutable list containing [elements]. */
@SafeVarargs
internal fun <T> immutableListOf(vararg elements: T): List<T> = elements.toImmutableList()

/** Returns an immutable list from copy of this. */
internal fun <T> Array<out T>?.toImmutableList(): List<T> =
  when {
    this.isNullOrEmpty() -> emptyList()
    this.size == 1 -> Collections.singletonList(this[0])
    else -> this.clone().asList().unmodifiable()
  }

/** Closes this, ignoring any checked exceptions. */
internal fun Socket.closeQuietly() {
  try {
    close()
  } catch (e: AssertionError) {
    throw e
  } catch (rethrown: RuntimeException) {
    if (rethrown.message == "bio == null") {
      // Conscrypt in Android 10 and 11 may throw closing an SSLSocket. This is safe to ignore.
      // https://issuetracker.google.com/issues/177450597
      return
    }
    throw rethrown
  } catch (_: Exception) {
  }
}

/** Closes this, ignoring any checked exceptions.  */
internal fun ServerSocket.closeQuietly() {
  try {
    close()
  } catch (rethrown: RuntimeException) {
    throw rethrown
  } catch (_: Exception) {
  }
}

internal fun Long.toHexString(): String = java.lang.Long.toHexString(this)

internal fun Int.toHexString(): String = Integer.toHexString(this)

internal fun <T> readFieldOrNull(
  instance: Any,
  fieldType: Class<T>,
  fieldName: String,
): T? {
  var c: Class<*> = instance.javaClass
  while (c != Any::class.java) {
    try {
      val field = c.getDeclaredField(fieldName)
      field.isAccessible = true
      val value = field.get(instance)
      return if (!fieldType.isInstance(value)) null else fieldType.cast(value)
    } catch (_: NoSuchFieldException) {
    }

    c = c.superclass
  }

  // Didn't find the field we wanted. As a last gasp attempt,
  // try to find the value on a delegate.
  if (fieldName != "delegate") {
    val delegate = readFieldOrNull(instance, Any::class.java, "delegate")
    if (delegate != null) return readFieldOrNull(delegate, fieldType, fieldName)
  }

  return null
}

@JvmField
internal val assertionsEnabled: Boolean = OkHttpClient::class.java.desiredAssertionStatus()

/** Dispatcher is not [Lockable] because we don't want that type in our public API. */
internal fun Dispatcher.assertLockNotHeld() {
  if (assertionsEnabled && Thread.holdsLock(this)) {
    throw AssertionError("Thread ${Thread.currentThread().name} MUST NOT hold lock on $this")
  }
}

/**
 * Returns the string "OkHttp" unless the library has been shaded for inclusion in another library,
 * or obfuscated with tools like R8 or ProGuard. In such cases it'll return a longer string like
 * "com.example.shaded.okhttp3.OkHttp". In large applications it's possible to have multiple OkHttp
 * instances; this makes it clear which is which.
 */
@JvmField
internal val okHttpName: String =
  OkHttpClient::class.java.name
    .removePrefix("okhttp3.")
    .removeSuffix("Client")

/**
 * Updates this reference to [newValue] if the previous value matches [condition].
 *
 * It is possible that a [condition] is invoked multiple times in a single call to this function.
 * This will occur if this state object is updated between fetching previous value and replacing it.
 *
 * Returns the previous value. This will always be the replaced value if the value was updated.
 */
@OkHttpInternalApi
tailrec fun <T> AtomicReference<T>.testAndSet(
  newValue: T,
  condition: (T) -> Boolean,
): T {
  val previous = get()
  if (!condition(previous)) return previous
  if (compareAndSet(previous, newValue)) return previous
  return testAndSet(newValue, condition) // Lost a race, retry.
}
