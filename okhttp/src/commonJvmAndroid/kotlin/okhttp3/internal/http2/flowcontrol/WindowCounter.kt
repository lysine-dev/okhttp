/*
 * Copyright (C) 2023 Square, Inc.
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
package okhttp3.internal.http2.flowcontrol

class WindowCounter(
  val streamId: Int,
) {
  /** The total number of bytes consumed. */
  var total: Long = 0L
    @Synchronized get
    private set

  /** The total number of bytes acknowledged by outgoing `WINDOW_UPDATE` frames. */
  var acknowledged: Long = 0L
    @Synchronized get
    private set

  val unacknowledged: Long
    @Synchronized get() = total - acknowledged

  @Synchronized
  fun update(
    total: Long = 0,
    acknowledged: Long = 0,
  ) {
    check(total >= 0)
    check(acknowledged >= 0)

    this.total += total
    this.acknowledged += acknowledged

    check(this.acknowledged <= this.total)
  }

  override fun toString(): String =
    "WindowCounter(streamId=$streamId, total=$total, acknowledged=$acknowledged, unacknowledged=$unacknowledged)"
}
