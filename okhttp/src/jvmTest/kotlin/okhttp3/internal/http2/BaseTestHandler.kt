/*
 * Copyright (C) 2013 Square, Inc.
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
package okhttp3.internal.http2

import assertk.fail
import okio.BufferedSource
import okio.ByteString

internal open class BaseTestHandler : Http2Reader.Handler {
  override fun data(
    inFinished: Boolean,
    streamId: Int,
    source: BufferedSource,
    length: Int,
  ) {
    fail("")
  }

  override fun dataPadding(
    streamId: Int,
    length: Int,
  ) {
    fail("")
  }

  override fun headers(
    inFinished: Boolean,
    streamId: Int,
    associatedStreamId: Int,
    headerBlock: List<Header>,
  ) {
    fail("")
  }

  override fun rstStream(
    streamId: Int,
    errorCode: ErrorCode,
  ) {
    fail("")
  }

  override fun settings(
    clearPrevious: Boolean,
    settings: Settings,
  ) {
    fail("")
  }

  override fun ackSettings() {
    fail("")
  }

  override fun ping(
    ack: Boolean,
    payload1: Int,
    payload2: Int,
  ) {
    fail("")
  }

  override fun goAway(
    lastGoodStreamId: Int,
    errorCode: ErrorCode,
    debugData: ByteString,
  ) {
    fail("")
  }

  override fun windowUpdate(
    streamId: Int,
    windowSizeIncrement: Long,
  ) {
    fail("")
  }

  override fun priority(
    streamId: Int,
    streamDependency: Int,
    weight: Int,
    exclusive: Boolean,
  ) {
    fail("")
  }

  override fun pushPromise(
    streamId: Int,
    associatedStreamId: Int,
    headerBlock: List<Header>,
  ) {
    fail("")
  }

  override fun alternateService(
    streamId: Int,
    origin: String,
    protocol: ByteString,
    host: String,
    port: Int,
    maxAge: Long,
  ) {
    fail("")
  }
}
