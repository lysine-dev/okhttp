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
package okhttp3.android

import okhttp3.ConnectionPool
import okhttp3.OkHttpClient
import okhttp3.internal.tracing.AndroidxTracingConnectionListener
import okhttp3.internal.tracing.AndroidxTracingInterceptor

/**
 * Configures the [OkHttpClient] with AndroidX Tracing support.
 *
 * This enables visibility into network requests and connection lifecycle events
 * within Android System Tracing (Perfetto/systrace). It installs a custom
 * [ConnectionPool] and a network interceptor to capture tracing spans.
 *
 * @return This builder for method chaining.
 */
fun OkHttpClient.Builder.addTracing(): OkHttpClient.Builder =
  this.connectionPool(ConnectionPool(connectionListener = AndroidxTracingConnectionListener()))
    .addNetworkInterceptor(AndroidxTracingInterceptor())
