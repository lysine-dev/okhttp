/*
 * Copyright (C) 2017 Square, Inc.
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

import okhttp3.Call
import okhttp3.Connection
import okhttp3.Route
import okio.IOException

/**
 * Listener for connection events. Extend this class to monitor the new connections and closes.
 *
 * All event methods must execute fast, without external locking, cannot throw exceptions,
 * attempt to mutate the event parameters, or be reentrant back into the client.
 * Any IO - writing to files or network should be done asynchronously.
 */
internal abstract class ConnectionListener {
  /**
   * Invoked as soon as a call causes a connection to be started.
   */
  open fun connectStart(
    connectionId: Long,
    route: Route,
    call: Call,
  ) {}

  /**
   * Invoked when a connection fails to be established.
   */
  open fun connectFailed(
    connectionId: Long,
    route: Route,
    call: Call,
    failure: IOException,
  ) {}

  /**
   * Invoked as soon as a connection is successfully established.
   */
  open fun connectEnd(
    connection: Connection,
    route: Route,
    call: Call,
  ) {}

  /**
   * Invoked when a connection is released as no longer required.
   */
  open fun connectionClosed(connection: Connection) {}

  /**
   * Invoked when a call is assigned a particular connection.
   */
  open fun connectionAcquired(
    connection: Connection,
    call: Call,
  ) {}

  /**
   * Invoked when a call no longer uses a connection.
   */
  open fun connectionReleased(
    connection: Connection,
    call: Call,
  ) {}

  /**
   * Invoked when a connection is marked for no new exchanges.
   */
  open fun noNewExchanges(connection: Connection) {}

  companion object {
    val NONE: ConnectionListener = object : ConnectionListener() {}
  }
}
