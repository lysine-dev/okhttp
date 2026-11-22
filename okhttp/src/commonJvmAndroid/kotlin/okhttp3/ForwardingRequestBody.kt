/*
 * Copyright (C) 2019 Square, Inc.
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

import java.io.IOException
import okio.BufferedSink

open class ForwardingRequestBody(
  private val delegate: RequestBody,
) : RequestBody() {
  fun delegate(): RequestBody = delegate

  override fun contentType(): MediaType? = delegate.contentType()

  @Throws(IOException::class)
  override fun contentLength(): Long = delegate.contentLength()

  @Throws(IOException::class)
  override fun writeTo(sink: BufferedSink) {
    delegate.writeTo(sink)
  }

  override fun isDuplex(): Boolean = delegate.isDuplex()

  override fun isOneShot(): Boolean = delegate.isOneShot()

  override fun toString(): String = javaClass.simpleName + "(" + delegate.toString() + ")"
}
