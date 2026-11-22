/*
 * Copyright (C) 2016 Square, Inc.
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
package okhttp3.internal.ws

import assertk.assertThat
import assertk.assertions.contains
import assertk.assertions.isEmpty
import assertk.assertions.isEqualTo
import assertk.assertions.isNull
import assertk.assertions.isSameInstanceAs
import java.io.IOException
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okhttp3.internal.platform.Platform
import okio.ByteString

class WebSocketRecorder(
  private val name: String,
) : WebSocketListener() {
  private val events = LinkedBlockingQueue<Any>()
  private var delegate: WebSocketListener? = null

  /** Sets a delegate for handling the next callback to this listener. Cleared after invoked.  */
  fun setNextEventDelegate(delegate: WebSocketListener?) {
    this.delegate = delegate
  }

  override fun onOpen(
    webSocket: WebSocket,
    response: Response,
  ) {
    Platform.get().log("[WS $name] onOpen", Platform.INFO, null)
    val delegate = delegate
    if (delegate != null) {
      this.delegate = null
      delegate.onOpen(webSocket, response)
    } else {
      events.add(Open(webSocket, response))
    }
  }

  override fun onMessage(
    webSocket: WebSocket,
    bytes: ByteString,
  ) {
    Platform.get().log("[WS $name] onMessage", Platform.INFO, null)
    val delegate = delegate
    if (delegate != null) {
      this.delegate = null
      delegate.onMessage(webSocket, bytes)
    } else {
      events.add(Message(bytes = bytes))
    }
  }

  override fun onMessage(
    webSocket: WebSocket,
    text: String,
  ) {
    Platform.get().log("[WS $name] onMessage", Platform.INFO, null)
    val delegate = delegate
    if (delegate != null) {
      this.delegate = null
      delegate.onMessage(webSocket, text)
    } else {
      events.add(Message(string = text))
    }
  }

  override fun onClosing(
    webSocket: WebSocket,
    code: Int,
    reason: String,
  ) {
    Platform.get().log("[WS $name] onClosing $code", Platform.INFO, null)
    val delegate = delegate
    if (delegate != null) {
      this.delegate = null
      delegate.onClosing(webSocket, code, reason)
    } else {
      events.add(Closing(code, reason))
    }
  }

  override fun onClosed(
    webSocket: WebSocket,
    code: Int,
    reason: String,
  ) {
    Platform.get().log("[WS $name] onClosed $code", Platform.INFO, null)
    val delegate = delegate
    if (delegate != null) {
      this.delegate = null
      delegate.onClosed(webSocket, code, reason)
    } else {
      events.add(Closed(code, reason))
    }
  }

  override fun onFailure(
    webSocket: WebSocket,
    t: Throwable,
    response: Response?,
  ) {
    Platform.get().log("[WS $name] onFailure", Platform.INFO, t)
    val delegate = delegate
    if (delegate != null) {
      this.delegate = null
      delegate.onFailure(webSocket, t, response)
    } else {
      events.add(Failure(t, response))
    }
  }

  private fun nextEvent(): Any =
    events.poll(10, TimeUnit.SECONDS)
      ?: throw AssertionError("Timed out waiting for event.")

  fun assertTextMessage(payload: String?) {
    assertThat(nextEvent()).isEqualTo(Message(string = payload))
  }

  fun assertBinaryMessage(payload: ByteString?) {
    assertThat(nextEvent()).isEqualTo(Message(payload))
  }

  fun assertPing(payload: ByteString) {
    assertThat(nextEvent()).isEqualTo(Ping(payload))
  }

  fun assertPong(payload: ByteString) {
    assertThat(nextEvent()).isEqualTo(Pong(payload))
  }

  fun assertClosing(
    code: Int,
    reason: String,
  ) {
    assertThat(nextEvent()).isEqualTo(Closing(code, reason))
  }

  fun assertClosed(
    code: Int,
    reason: String,
  ) {
    assertThat(nextEvent()).isEqualTo(Closed(code, reason))
  }

  fun assertExhausted() {
    assertThat(events).isEmpty()
  }

  fun assertOpen(): WebSocket {
    val event = nextEvent() as Open
    return event.webSocket
  }

  fun assertFailure(t: Throwable?) {
    val event = nextEvent() as Failure
    assertThat(event.response).isNull()
    assertThat(event.t).isSameInstanceAs(t)
  }

  fun assertFailure(
    cls: Class<out IOException?>?,
    vararg messages: String,
  ) {
    val event = nextEvent() as Failure
    assertThat(event.response).isNull()
    assertThat(event.t.javaClass).isEqualTo(cls)
    if (messages.isNotEmpty()) {
      assertThat(messages).contains(event.t.message)
    }
  }

  fun assertFailure() {
    nextEvent() as Failure
  }

  fun assertFailure(
    code: Int,
    body: String?,
    cls: Class<out IOException?>?,
    message: String?,
  ) {
    val event = nextEvent() as Failure
    assertThat(event.response!!.code).isEqualTo(code)
    if (body != null) {
      assertThat(event.responseBody).isEqualTo(body)
    }
    assertThat(event.t.javaClass).isEqualTo(cls)
    assertThat(event.t.message).isEqualTo(message)
  }

  /** Expose this recorder as a frame callback and shim in "ping" events.  */
  fun asFrameCallback() =
    object : WebSocketReader.FrameCallback {
      override fun onReadMessage(text: String) {
        events.add(Message(string = text))
      }

      override fun onReadMessage(bytes: ByteString) {
        events.add(Message(bytes = bytes))
      }

      override fun onReadPing(payload: ByteString) {
        events.add(Ping(payload))
      }

      override fun onReadPong(payload: ByteString) {
        events.add(Pong(payload))
      }

      override fun onReadClose(
        code: Int,
        reason: String,
      ) {
        events.add(Closing(code, reason))
      }
    }

  internal class Open(
    val webSocket: WebSocket,
    val response: Response,
  )

  internal class Failure(
    val t: Throwable,
    val response: Response?,
  ) {
    val responseBody: String? =
      when {
        response != null && response.code != 101 -> response.body.string()
        else -> null
      }

    override fun toString(): String =
      when (response) {
        null -> "Failure[$t]"
        else -> "Failure[$response]"
      }
  }

  internal data class Message(
    val bytes: ByteString? = null,
    val string: String? = null,
  )

  internal data class Ping(
    val payload: ByteString,
  )

  internal data class Pong(
    val payload: ByteString,
  )

  internal data class Closing(
    val code: Int,
    val reason: String,
  )

  internal data class Closed(
    val code: Int,
    val reason: String,
  )
}
