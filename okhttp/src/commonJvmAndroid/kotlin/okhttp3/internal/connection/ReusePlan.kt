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

import okhttp3.internal.OkHttpInternalApi

/** Reuse a connection from the pool. */
@OkHttpInternalApi
internal class ReusePlan(
  val connection: RealConnection,
) : RoutePlanner.Plan {
  override val isReady = true

  override fun connectTcp() = error("already connected")

  override fun connectTlsEtc() = error("already connected")

  override fun handleSuccess() = connection

  override fun cancel() = error("unexpected cancel")

  override fun retry() = error("unexpected retry")
}
