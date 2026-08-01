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
package okhttp3

import java.net.Socket

/**
 * The sockets and streams of an HTTP, HTTPS, or HTTPS+HTTP/2 connection. May be used for multiple
 * HTTP request/response exchanges. Connections may be direct to the origin server or via a proxy.
 *
 * Typically instances of this class are created, connected and exercised automatically by the HTTP
 * client. Applications may use this class to monitor HTTP connections as members of a
 * [connection pool][ConnectionPool].
 *
 * Do not confuse this class with the misnamed `HttpURLConnection`, which isn't so much a connection
 * as a single request/response exchange.
 *
 * ## Modern TLS
 *
 * There are trade-offs when selecting which options to include when negotiating a secure connection
 * to a remote host. Newer TLS options are quite useful:
 *
 *  * Server Name Indication (SNI) enables one IP address to negotiate secure connections for
 *    multiple domain names.
 *
 *  * Application Layer Protocol Negotiation (ALPN) enables the HTTPS port (443) to be used to
 *    negotiate HTTP/2.
 *
 * Unfortunately, older HTTPS servers refuse to connect when such options are presented. Rather than
 * avoiding these options entirely, this class allows a connection to be attempted with modern
 * options and then retried without them should the attempt fail.
 *
 * ## Connection Reuse
 *
 * Each connection can carry a varying number of streams, depending on the underlying protocol being
 * used. HTTP/1.x connections can carry either zero or one streams. HTTP/2 connections can carry any
 * number of streams, dynamically configured with `SETTINGS_MAX_CONCURRENT_STREAMS`. A connection
 * currently carrying zero streams is an idle stream. We keep it alive because reusing an existing
 * connection is typically faster than establishing a new one.
 *
 * When a single logical call requires multiple streams due to redirects or authorization
 * challenges, we prefer to use the same physical connection for all streams in the sequence. There
 * are potential performance and behavior consequences to this preference. To support this feature,
 * this class separates _allocations_ from _streams_. An allocation is created by a call, used for
 * one or more streams, and then released. An allocated connection won't be stolen by other calls
 * while a redirect or authorization challenge is being handled.
 *
 * When the maximum concurrent streams limit is reduced, some allocations will be rescinded.
 * Attempting to create new streams on these allocations will fail.
 *
 * Note that an allocation may be released before its stream is completed. This is intended to make
 * bookkeeping easier for the caller: releasing the allocation as soon as the terminal stream has
 * been found. But only complete the stream once its data stream has been exhausted.
 */
interface Connection {
  /** Unique id of this connection, assigned at the time of the attempt. */
  val id: Long
    get() = 0L

  /** Returns the route used by this connection. */
  fun route(): Route

  /**
   * Returns the socket that this connection is using. Returns an
   * [SSL socket][javax.net.ssl.SSLSocket] if this connection is HTTPS. If this is an HTTP/2
   * connection the socket may be shared by multiple concurrent calls.
   */
  fun socket(): Socket

  /**
   * Returns the TLS handshake used to establish this connection, or null if the connection is not
   * HTTPS.
   */
  fun handshake(): Handshake?

  /**
   * Returns the protocol negotiated by this connection, or [Protocol.HTTP_1_1] if no protocol
   * has been negotiated. This method returns [Protocol.HTTP_1_1] even if the remote peer is using
   * [Protocol.HTTP_1_0].
   */
  fun protocol(): Protocol
}
