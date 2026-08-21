/*
 * Copyright (c) 2025 Block, Inc.
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
package okhttp3.internal.cache

import okio.Buffer
import okio.Source

/**
 * Okio ForwardingSource, but without eagerly opening the file.
 */
abstract class DeferredForwardingSource(
  delegateFn: () -> Source,
) : Source {
  val delegate by lazy(delegateFn)

  override fun read(
    sink: Buffer,
    byteCount: Long,
  ): Long = delegate.read(sink, byteCount)

  override fun timeout() = delegate.timeout()

  override fun close() = delegate.close()

  override fun toString() = "${javaClass.simpleName}($delegate)"
}
