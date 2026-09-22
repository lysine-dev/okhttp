/*
 *  Licensed to the Apache Software Foundation (ASF) under one or more
 *  contributor license agreements.  See the NOTICE file distributed with
 *  this work for additional information regarding copyright ownership.
 *  The ASF licenses this file to You under the Apache License, Version 2.0
 *  (the "License"); you may not use this file except in compliance with
 *  the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */
@file:OptIn(OkHttpInternalApi::class)

package okhttp3

import java.util.concurrent.TimeUnit
import okhttp3.internal.OkHttpInternalApi
import okhttp3.internal.concurrent.TaskRunner
import okhttp3.internal.connection.ConnectionListener
import okhttp3.internal.connection.RealConnectionPool

/**
 * Manages reuse of HTTP and HTTP/2 connections for reduced network latency. HTTP requests that
 * share the same [Address] may share a [Connection]. This class implements the policy
 * of which connections to keep open for future use.
 *
 * @constructor Create a new connection pool with tuning parameters appropriate for a single-user
 * application. The tuning parameters in this pool are subject to change in future OkHttp releases.
 * Currently this pool holds up to 5 idle connections which will be evicted after 5 minutes of
 * inactivity.
 */
class ConnectionPool internal constructor(
  internal val delegate: RealConnectionPool,
) {
  internal constructor(
    maxIdleConnections: Int = 5,
    keepAliveDuration: Long = 5,
    timeUnit: TimeUnit = TimeUnit.MINUTES,
    taskRunner: TaskRunner = TaskRunner.INSTANCE,
    connectionListener: ConnectionListener = ConnectionListener.NONE,
  ) : this(
    RealConnectionPool(
      taskRunner = taskRunner,
      maxIdleConnections = maxIdleConnections,
      keepAliveDuration = keepAliveDuration,
      timeUnit = timeUnit,
      connectionListener = connectionListener,
    ),
  )

  // Internal until we promote ConnectionListener to be a public API.
  internal constructor(
    maxIdleConnections: Int = 5,
    keepAliveDuration: Long = 5,
    timeUnit: TimeUnit = TimeUnit.MINUTES,
    connectionListener: ConnectionListener = ConnectionListener.NONE,
  ) : this(
    taskRunner = TaskRunner.INSTANCE,
    maxIdleConnections = maxIdleConnections,
    keepAliveDuration = keepAliveDuration,
    timeUnit = timeUnit,
    connectionListener = connectionListener,
  )

  // Public API
  constructor(
    maxIdleConnections: Int,
    keepAliveDuration: Long,
    timeUnit: TimeUnit,
  ) : this(
    maxIdleConnections = maxIdleConnections,
    keepAliveDuration = keepAliveDuration,
    timeUnit = timeUnit,
    taskRunner = TaskRunner.INSTANCE,
    connectionListener = ConnectionListener.NONE,
  )

  constructor() : this(5, 5, TimeUnit.MINUTES)

  /** Returns the number of idle connections in the pool. */
  fun idleConnectionCount(): Int = delegate.idleConnectionCount()

  /** Returns total number of connections in the pool. */
  fun connectionCount(): Int = delegate.connectionCount()

  internal val connectionListener: ConnectionListener
    get() = delegate.connectionListener

  /** Close and remove all idle connections in the pool. */
  fun evictAll() {
    delegate.evictAll()
  }
}
