/*
 * Copyright (C) 2011 Google Inc.
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
@file:Suppress(
  "CANNOT_OVERRIDE_INVISIBLE_MEMBER",
  "INVISIBLE_MEMBER",
  "INVISIBLE_REFERENCE",
  "ktlint:standard:property-naming",
)

package mockwebserver3

import java.io.Closeable
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ProtocolException
import java.net.Proxy
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.security.SecureRandom
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import java.util.Collections
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.logging.Level
import java.util.logging.Logger
import javax.net.ServerSocketFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager
import mockwebserver3.SocketEffect.CloseSocket
import mockwebserver3.SocketEffect.CloseStream
import mockwebserver3.SocketEffect.ShutdownConnection
import mockwebserver3.SocketEffect.Stall
import mockwebserver3.internal.DEFAULT_REQUEST_LINE_HTTP_1
import mockwebserver3.internal.DEFAULT_REQUEST_LINE_HTTP_2
import mockwebserver3.internal.MockWebServerSocket
import mockwebserver3.internal.RecordedRequest
import mockwebserver3.internal.RequestLine
import mockwebserver3.internal.ThrottledSink
import mockwebserver3.internal.TriggerSink
import mockwebserver3.internal.decodeRequestLine
import okhttp3.Headers
import okhttp3.Headers.Companion.headersOf
import okhttp3.HttpUrl
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.internal.addHeaderLenient
import okhttp3.internal.closeQuietly
import okhttp3.internal.concurrent.TaskRunner
import okhttp3.internal.http.HttpMethod
import okhttp3.internal.http2.ErrorCode
import okhttp3.internal.http2.Header
import okhttp3.internal.http2.Http2Connection
import okhttp3.internal.http2.Http2Stream
import okhttp3.internal.immutableListOf
import okhttp3.internal.platform.Platform
import okhttp3.internal.threadFactory
import okhttp3.internal.toImmutableList
import okhttp3.internal.ws.RealWebSocket
import okhttp3.internal.ws.WebSocketExtensions
import okhttp3.internal.ws.WebSocketProtocol
import okio.Buffer
import okio.BufferedSink
import okio.BufferedSource
import okio.ByteString
import okio.Sink
import okio.Timeout
import okio.buffer

/**
 * A scriptable web server. Callers supply canned responses and the server replays them upon request
 * in sequence.
 */
public class MockWebServer : Closeable {
  private val taskRunnerBackend =
    TaskRunner.RealBackend(
      threadFactory("MockWebServer TaskRunner", daemon = false),
    )
  private val taskRunner = TaskRunner(taskRunnerBackend)
  private val requestQueue = LinkedBlockingQueue<RecordedRequest>()
  private val openClientSockets =
    Collections.newSetFromMap(ConcurrentHashMap<Socket, Boolean>())
  private val openConnections =
    Collections.newSetFromMap(ConcurrentHashMap<Http2Connection, Boolean>())

  private val atomicRequestCount = AtomicInteger()

  private var serverSocketFactory_: ServerSocketFactory? = null
  private var serverSocket: ServerSocket? = null

  /** Non-null after [start]. */
  private var socketAddress_: InetSocketAddress? = null

  private var sslSocketFactory: SSLSocketFactory? = null
  private var clientAuth = CLIENT_AUTH_NONE

  private var closed: Boolean = false

  /**
   * The number of HTTP requests received thus far by this server. This may exceed the number of
   * HTTP connections when connection reuse is in practice.
   */
  public val requestCount: Int
    get() = atomicRequestCount.get()

  /** The number of bytes of the POST body to keep in memory to the given limit. */
  public var bodyLimit: Long = Long.MAX_VALUE

  public var serverSocketFactory: ServerSocketFactory?
    @Synchronized get() = serverSocketFactory_

    @Synchronized set(value) {
      check(socketAddress_ == null) { "serverSocketFactory must not be set after start()" }
      serverSocketFactory_ = value
    }

  /**
   * The dispatcher used to respond to HTTP requests. The default dispatcher is a [QueueDispatcher],
   * which serves a fixed sequence of responses from a [queue][enqueue].
   *
   * Other dispatchers can be configured. They can vary the response based on timing or the content
   * of the request.
   */
  public var dispatcher: Dispatcher = QueueDispatcher()

  public val socketAddress: InetSocketAddress
    get() = socketAddress_ ?: error("call start() first")

  public val port: Int
    get() = socketAddress.port

  public val hostName: String
    get() = socketAddress.address.hostName

  /** Returns the address of this server, to connect to it as an HTTP proxy. */
  public val proxyAddress: Proxy
    get() = Proxy(Proxy.Type.HTTP, socketAddress)

  /**
   * True if ALPN is used on incoming HTTPS connections to negotiate a protocol like HTTP/1.1 or
   * HTTP/2. This is true by default; set to false to disable negotiation and restrict connections
   * to HTTP/1.1.
   */
  public var protocolNegotiationEnabled: Boolean = true

  /**
   * The protocols supported by ALPN on incoming HTTPS connections in order of preference. The list
   * must contain [Protocol.HTTP_1_1]. It must not contain null.
   *
   * This list is ignored when [negotiation is disabled][protocolNegotiationEnabled].
   */
  public var protocols: List<Protocol> = immutableListOf(Protocol.HTTP_2, Protocol.HTTP_1_1)
    set(value) {
      val protocolList = value.toImmutableList()
      require(Protocol.H2_PRIOR_KNOWLEDGE !in protocolList || protocolList.size == 1) {
        "protocols containing h2_prior_knowledge cannot use other protocols: $protocolList"
      }
      require(Protocol.HTTP_1_1 in protocolList || Protocol.H2_PRIOR_KNOWLEDGE in protocolList) {
        "protocols doesn't contain http/1.1: $protocolList"
      }
      require(null !in protocolList as List<Protocol?>) { "protocols must not contain null" }
      field = protocolList
    }

  public val started: Boolean
    get() = socketAddress_ != null

  /**
   * Returns a URL for connecting to this server.
   *
   * @param path the request path, such as "/".
   */
  public fun url(path: String): HttpUrl =
    HttpUrl
      .Builder()
      .scheme(if (sslSocketFactory != null) "https" else "http")
      .host(hostName)
      .port(port)
      .build()
      .resolve(path)!!

  /**
   * Serve requests with HTTPS rather than otherwise.
   */
  public fun useHttps(sslSocketFactory: SSLSocketFactory) {
    this.sslSocketFactory = sslSocketFactory
  }

  /**
   * Configure the server to not perform SSL authentication of the client. This leaves
   * authentication to another layer such as in an HTTP cookie or header. This is the default and
   * most common configuration.
   */
  public fun noClientAuth() {
    this.clientAuth = CLIENT_AUTH_NONE
  }

  /**
   * Configure the server to [want client auth][SSLSocket.setWantClientAuth]. If the
   * client presents a certificate that is [trusted][TrustManager] the handshake will
   * proceed normally. The connection will also proceed normally if the client presents no
   * certificate at all! But if the client presents an untrusted certificate the handshake
   * will fail and no connection will be established.
   */
  public fun requestClientAuth() {
    this.clientAuth = CLIENT_AUTH_REQUESTED
  }

  /**
   * Configure the server to [need client auth][SSLSocket.setNeedClientAuth]. If the
   * client presents a certificate that is [trusted][TrustManager] the handshake will
   * proceed normally. If the client presents an untrusted certificate or no certificate at all the
   * handshake will fail and no connection will be established.
   */
  public fun requireClientAuth() {
    this.clientAuth = CLIENT_AUTH_REQUIRED
  }

  /**
   * Awaits the next HTTP request, removes it, and returns it. Callers should use this to verify the
   * request was sent as intended. This method will block until the request is available, possibly
   * forever.
   *
   * @return the head of the request queue
   */
  @Throws(InterruptedException::class)
  public fun takeRequest(): RecordedRequest = requestQueue.take()

  /**
   * Awaits the next HTTP request (waiting up to the specified wait time if necessary), removes it,
   * and returns it. Callers should use this to verify the request was sent as intended within the
   * given time.
   *
   * @param timeout how long to wait before giving up, in units of [unit]
   * @param unit a [TimeUnit] determining how to interpret the [timeout] parameter
   * @return the head of the request queue
   */
  @Throws(InterruptedException::class)
  public fun takeRequest(
    timeout: Long,
    unit: TimeUnit,
  ): RecordedRequest? = requestQueue.poll(timeout, unit)

  /**
   * Scripts [response] to be returned to a request made in sequence. The first request is
   * served by the first enqueued response; the second request by the second enqueued response; and
   * so on.
   *
   * @throws ClassCastException if the default dispatcher has been
   * replaced with [setDispatcher][dispatcher].
   */
  public fun enqueue(response: MockResponse) {
    (dispatcher as QueueDispatcher).enqueue(response)
  }

  /**
   * Starts the server on the loopback interface for the given port.
   *
   * @param port the port to listen to, or 0 for any available port. Automated tests should always
   * use port 0 to avoid flakiness when a specific port is unavailable.
   */
  @Throws(IOException::class)
  @JvmOverloads
  public fun start(port: Int = 0) {
    start(InetAddress.getByName("localhost"), port)
  }

  /**
   * Starts the server on the given address and port.
   *
   * @param inetAddress the address to create the server socket on
   * @param port the port to listen to, or 0 for any available port. Automated tests should always
   * use port 0 to avoid flakiness when a specific port is unavailable.
   */
  @Throws(IOException::class)
  public fun start(
    inetAddress: InetAddress,
    port: Int,
  ) {
    start(InetSocketAddress(inetAddress, port))
  }

  /**
   * Starts the server and binds to the given socket address.
   *
   * @param socketAddress the socket address to bind the server on
   */
  @Synchronized
  @Throws(IOException::class)
  private fun start(socketAddress: InetSocketAddress) {
    check(!closed) { "close() already called" }

    val alreadyStartedAddress = socketAddress_
    if (alreadyStartedAddress != null) {
      check(socketAddress.address == alreadyStartedAddress.address) {
        "unexpected address"
      }
      check(socketAddress.port == 0 || socketAddress.port == alreadyStartedAddress.port) {
        "unexpected port"
      }
      return // Already started.
    }

    var boundSocketAddress = socketAddress
    try {
      val serverSocketFactory =
        serverSocketFactory_
          ?: (ServerSocketFactory.getDefault()!!.also { this.serverSocketFactory_ = it })

      val serverSocket =
        serverSocketFactory
          .createServerSocket()!!
          .also { this.serverSocket = it }

      // Reuse if the user specified a port
      serverSocket.reuseAddress = socketAddress.port != 0
      serverSocket.bind(socketAddress, 50)

      // If the local port was 0, it'll be non-zero after bind().
      boundSocketAddress = InetSocketAddress(boundSocketAddress.address, serverSocket.localPort)
    } finally {
      this.socketAddress_ = boundSocketAddress
    }

    taskRunner.newQueue().execute(toString(), cancelable = false) {
      try {
        logger.fine("$this starting to accept connections")
        acceptConnections()
      } catch (e: Throwable) {
        logger.log(Level.WARNING, "$this failed unexpectedly", e)
      }

      // Release all sockets and all threads, even if any close fails.
      serverSocket?.closeQuietly()

      val openClientSocket = openClientSockets.iterator()
      while (openClientSocket.hasNext()) {
        openClientSocket.next().closeQuietly()
        openClientSocket.remove()
      }

      val httpConnection = openConnections.iterator()
      while (httpConnection.hasNext()) {
        httpConnection.next().closeQuietly()
        httpConnection.remove()
      }
      dispatcher.close()
    }
  }

  @Throws(Exception::class)
  private fun acceptConnections() {
    var nextConnectionIndex = 0
    while (true) {
      val socket: Socket
      try {
        socket = serverSocket!!.accept()
      } catch (e: SocketException) {
        logger.fine("${this@MockWebServer} done accepting connections: ${e.message}")
        return
      }

      val peek = dispatcher.peek()
      if (peek.onRequestStart is CloseSocket) {
        dispatchBookkeepingRequest(
          connectionIndex = nextConnectionIndex++,
          exchangeIndex = 0,
          socket = MockWebServerSocket(socket),
        )
        socket.close()
      } else {
        openClientSockets.add(socket)
        serveConnection(nextConnectionIndex++, socket, peek)
      }
    }
  }

  public override fun close() {
    if (closed) return
    closed = true

    if (!started) return // Nothing to shut down.
    val serverSocket = this.serverSocket ?: return // If this is null, start() must have failed.

    // Cause acceptConnections() to break out.
    serverSocket.closeQuietly()

    // Await shutdown.
    for (queue in taskRunner.activeQueues()) {
      if (!queue.idleLatch().await(5, TimeUnit.SECONDS)) {
        throw AssertionError("Gave up waiting for queue to shut down")
      }
    }
    taskRunnerBackend.shutdown()
  }

  private fun serveConnection(
    connectionIndex: Int,
    raw: Socket,
    firstExchangePeek: MockResponse,
  ) {
    taskRunner.newQueue().execute("MockWebServer ${raw.remoteSocketAddress}", cancelable = false) {
      try {
        SocketHandler(connectionIndex, raw, firstExchangePeek).handle()
      } catch (e: IOException) {
        logger.fine("$this connection from ${raw.inetAddress} failed: $e")
      } catch (e: Exception) {
        logger.log(Level.SEVERE, "$this connection from ${raw.inetAddress} crashed", e)
      }
    }
  }

  internal inner class SocketHandler(
    private val connectionIndex: Int,
    private val raw: Socket,
    private val firstExchangePeek: MockResponse,
  ) {
    private var nextExchangeIndex = 0

    @Throws(Exception::class)
    fun handle() {
      if (!processTunnelRequests()) return

      val protocol: Protocol
      val socket: MockWebServerSocket
      when {
        sslSocketFactory != null -> {
          if (firstExchangePeek.failHandshake) {
            dispatchBookkeepingRequest(
              connectionIndex = connectionIndex,
              exchangeIndex = nextExchangeIndex++,
              socket = MockWebServerSocket(raw),
            )
            processHandshakeFailure(raw)
            return
          }
          val sslSocket =
            sslSocketFactory!!.createSocket(
              raw,
              raw.inetAddress.hostAddress,
              raw.port,
              true,
            ) as SSLSocket
          sslSocket.useClientMode = false
          if (clientAuth == CLIENT_AUTH_REQUIRED) {
            sslSocket.needClientAuth = true
          } else if (clientAuth == CLIENT_AUTH_REQUESTED) {
            sslSocket.wantClientAuth = true
          }
          openClientSockets.add(sslSocket)

          if (protocolNegotiationEnabled) {
            Platform.get().configureTlsExtensions(
              sslSocket = sslSocket,
              hostname = null,
              protocols = protocols,
              echConfigList = null,
            )
          }

          sslSocket.startHandshake()

          // Wait until after handshake to grab the buffered socket
          socket = MockWebServerSocket(sslSocket)

          if (protocolNegotiationEnabled) {
            val protocolString = Platform.get().getSelectedProtocol(sslSocket)
            protocol =
              when {
                protocolString != null -> Protocol.get(protocolString)
                else -> Protocol.HTTP_1_1
              }
            Platform.get().afterHandshake(sslSocket)
          } else {
            protocol = Protocol.HTTP_1_1
          }
          openClientSockets.remove(raw)
        }

        else -> {
          protocol =
            when {
              Protocol.H2_PRIOR_KNOWLEDGE in protocols -> Protocol.H2_PRIOR_KNOWLEDGE
              else -> Protocol.HTTP_1_1
            }
          socket = MockWebServerSocket(raw)
        }
      }

      if (firstExchangePeek.onRequestStart == Stall) {
        dispatchBookkeepingRequest(
          connectionIndex = connectionIndex,
          exchangeIndex = nextExchangeIndex++,
          socket = socket,
        )
        return // Ignore the socket until the server is shut down!
      }

      if (protocol === Protocol.HTTP_2 || protocol === Protocol.H2_PRIOR_KNOWLEDGE) {
        val http2SocketHandler = Http2SocketHandler(connectionIndex, socket, protocol)
        val connection =
          Http2Connection
            .Builder(false, taskRunner)
            .socket(socket, socket.javaNetSocket.remoteSocketAddress.toString())
            .listener(http2SocketHandler)
            .build()
        connection.start()
        openConnections.add(connection)
        openClientSockets.remove(socket.javaNetSocket)
        return
      } else if (protocol !== Protocol.HTTP_1_1) {
        throw AssertionError()
      }

      while (processOneRequest(socket)) {
      }

      if (nextExchangeIndex == 0) {
        logger.warning(
          "${this@MockWebServer} connection from ${raw.inetAddress} didn't make a request",
        )
      }

      socket.close()
      openClientSockets.remove(socket.javaNetSocket)
    }

    /**
     * Respond to `CONNECT` requests until a non-tunnel response is peeked. Returns true if further
     * calls should be attempted on the socket.
     */
    @Throws(IOException::class, InterruptedException::class)
    private fun processTunnelRequests(): Boolean {
      if (!dispatcher.peek().inTunnel) return true // No tunnel requests.

      val socket = MockWebServerSocket(raw)
      while (true) {
        val socketStillGood = processOneRequest(socket)

        // Clean up after the last exchange on a socket.
        if (!socketStillGood) {
          raw.close()
          openClientSockets.remove(raw)
          return false
        }

        if (!dispatcher.peek().inTunnel) return true // No more tunnel requests.
      }
    }

    /**
     * Reads a request and writes its response. Returns true if further calls should be attempted
     * on the socket.
     */
    @Throws(IOException::class, InterruptedException::class)
    private fun processOneRequest(socket: MockWebServerSocket): Boolean {
      if (socket.source.exhausted()) {
        return false // No more requests on this socket.
      }

      val request =
        readRequest(
          socket = socket,
          connectionIndex = connectionIndex,
          exchangeIndex = nextExchangeIndex++,
        )
      atomicRequestCount.incrementAndGet()
      requestQueue.add(request)

      if (request.failure != null) {
        return false // Nothing to respond to.
      }

      val response = dispatcher.dispatch(request)

      try {
        if (handleSocketEffect(response.onResponseStart, socket)) {
          return false
        }

        var reuseSocket = true
        val requestWantsSocket = "Upgrade".equals(request.headers["Connection"], ignoreCase = true)
        val requestWantsWebSocket =
          requestWantsSocket &&
            "websocket".equals(request.headers["Upgrade"], ignoreCase = true)
        val responseWantsSocket = response.socketHandler != null
        val responseWantsWebSocket = response.webSocketListener != null
        if (requestWantsWebSocket && responseWantsWebSocket) {
          handleWebSocketUpgrade(socket, request, response)
          reuseSocket = false
        } else if (requestWantsSocket && responseWantsSocket) {
          writeHttpResponse(socket, response)
          reuseSocket = false
        } else {
          writeHttpResponse(socket, response)
        }

        if (logger.isLoggable(Level.FINE)) {
          logger.fine(
            "${this@MockWebServer} received request: $request and responded: $response",
          )
        }

        // See warnings associated with these socket policies in SocketPolicy.
        if (handleSocketEffect(response.onResponseEnd, socket)) {
          return false
        }

        return reuseSocket
      } finally {
        if (response.shutdownServer) {
          close()
        }
      }
    }
  }

  @Throws(Exception::class)
  private fun processHandshakeFailure(raw: Socket) {
    val context = SSLContext.getInstance("TLS")
    context.init(null, arrayOf<TrustManager>(UNTRUSTED_TRUST_MANAGER), SecureRandom())
    val sslSocketFactory = context.socketFactory
    val socket =
      sslSocketFactory.createSocket(
        raw,
        raw.inetAddress.hostAddress,
        raw.port,
        true,
      ) as SSLSocket
    try {
      socket.startHandshake() // we're testing a handshake failure
      throw AssertionError()
    } catch (_: IOException) {
    }
    socket.close()
  }

  @Throws(InterruptedException::class)
  private fun dispatchBookkeepingRequest(
    connectionIndex: Int,
    exchangeIndex: Int,
    socket: MockWebServerSocket,
    requestLine: RequestLine = DEFAULT_REQUEST_LINE_HTTP_1,
  ) {
    val request =
      RecordedRequest(
        requestLine = requestLine,
        headers = headersOf(),
        chunkSizes = null,
        bodySize = 0L,
        body = null,
        connectionIndex = connectionIndex,
        exchangeIndex = exchangeIndex,
        socket = socket,
      )
    atomicRequestCount.incrementAndGet()
    requestQueue.add(request)
    dispatcher.dispatch(request)
  }

  /** @param exchangeIndex the index of this request on this connection.*/
  @Throws(IOException::class)
  private fun readRequest(
    socket: MockWebServerSocket,
    connectionIndex: Int,
    exchangeIndex: Int,
  ): RecordedRequest {
    var request: RequestLine = DEFAULT_REQUEST_LINE_HTTP_1
    val headers = Headers.Builder()
    var contentLength = -1L
    var chunked = false
    var hasBody = false
    val requestBody = TruncatingBuffer(bodyLimit)
    var chunkSizes: List<Int>? = null
    var failure: IOException? = null

    try {
      val requestLineString = socket.source.readUtf8LineStrict()
      if (requestLineString.isEmpty()) {
        throw ProtocolException("no request because the stream is exhausted")
      }
      request = decodeRequestLine(requestLineString)

      while (true) {
        val header = socket.source.readUtf8LineStrict()
        if (header.isEmpty()) {
          break
        }
        addHeaderLenient(headers, header)
        val lowercaseHeader = header.lowercase(Locale.US)
        if (contentLength == -1L && lowercaseHeader.startsWith("content-length:")) {
          contentLength = header.substring(15).trim().toLong()
        }
        if (lowercaseHeader.startsWith("transfer-encoding:") &&
          lowercaseHeader.substring(18).trim() == "chunked"
        ) {
          chunked = true
        }
      }

      val peek = dispatcher.peek()
      for (response in peek.informationalResponses) {
        writeHttpResponse(socket, response)
      }

      val requestBodySink =
        requestBody
          .withThrottlingAndSocketEffect(
            policy = peek,
            socketEffect = peek.onRequestBody,
            expectedByteCount = contentLength,
            socket = socket,
          ).buffer()
      requestBodySink.use {
        when {
          peek.doNotReadRequestBody -> {
            hasBody = false // Ignore the body completely.
          }

          contentLength != -1L -> {
            hasBody = contentLength > 0L || HttpMethod.permitsRequestBody(request.method)
            requestBodySink.write(socket.source, contentLength)
          }

          chunked -> {
            chunkSizes = mutableListOf()
            hasBody = true
            while (true) {
              val chunkSize =
                socket.source
                  .readUtf8LineStrict()
                  .trim()
                  .toInt(16)
              if (chunkSize == 0) {
                readEmptyLine(socket.source)
                break
              }
              chunkSizes.add(chunkSize)
              requestBodySink.write(socket.source, chunkSize.toLong())
              readEmptyLine(socket.source)
            }
          }

          else -> {
            hasBody = false // No request body.
          }
        }
      }

      require(!hasBody || HttpMethod.permitsRequestBody(request.method)) {
        "Request must not have a body: $request"
      }
    } catch (e: IOException) {
      failure = e
    }

    return RecordedRequest(
      requestLine = request,
      headers = headers.build(),
      chunkSizes = chunkSizes,
      bodySize = requestBody.receivedByteCount,
      body =
        when {
          hasBody -> requestBody.buffer.readByteString()
          else -> null
        },
      connectionIndex = connectionIndex,
      exchangeIndex = exchangeIndex,
      socket = socket,
      failure = failure,
    )
  }

  @Throws(IOException::class)
  private fun handleWebSocketUpgrade(
    socket: MockWebServerSocket,
    request: RecordedRequest,
    response: MockResponse,
  ) {
    val key = request.headers["Sec-WebSocket-Key"]
    val webSocketResponse =
      response
        .newBuilder()
        .setHeader("Sec-WebSocket-Accept", WebSocketProtocol.acceptHeader(key!!))
        .build()
    writeHttpResponse(socket, webSocketResponse)

    // Adapt the request and response into our Request and Response domain model.
    val scheme = if (request.handshake != null) "https" else "http"
    val authority = request.headers["Host"] // Has host and port.
    val fancyRequest =
      Request
        .Builder()
        .url("$scheme://$authority/")
        .headers(request.headers)
        .build()
    val fancyResponse =
      Response
        .Builder()
        .code(webSocketResponse.code)
        .message(webSocketResponse.message)
        .headers(webSocketResponse.headers)
        .request(fancyRequest)
        .protocol(Protocol.HTTP_1_1)
        .build()

    val webSocket =
      RealWebSocket(
        taskRunner = taskRunner,
        originalRequest = fancyRequest,
        listener = webSocketResponse.webSocketListener!!,
        random = SecureRandom(),
        pingIntervalMillis = 0,
        extensions = WebSocketExtensions.parse(webSocketResponse.headers),
        // Compress all messages if compression is enabled.
        minimumDeflateSize = 0L,
        webSocketCloseTimeout = RealWebSocket.CANCEL_AFTER_CLOSE_MILLIS,
      )
    val name = "MockWebServer WebSocket ${request.url.encodedPath}"

    webSocket.initReaderAndWriter(
      name = name,
      socket = socket,
      client = false,
    )

    webSocket.loopReader(fancyResponse)

    // Even if messages are no longer being read we need to wait for the connection close signal.
    socket.awaitClosed()
  }

  @Throws(IOException::class)
  private fun writeHttpResponse(
    socket: MockWebServerSocket,
    response: MockResponse,
  ) {
    socket.sleepWhileOpen(response.headersDelayNanos)
    socket.sink.writeUtf8(response.status)
    socket.sink.writeUtf8("\r\n")

    writeHeaders(socket.sink, response.headers)

    if (response.socketHandler != null) {
      response.socketHandler.handle(socket)
      return
    }

    val body = response.body ?: return
    socket.sleepWhileOpen(response.bodyDelayNanos)
    val responseBodySink =
      socket.sink
        .withThrottlingAndSocketEffect(
          policy = response,
          socketEffect = response.onResponseBody,
          expectedByteCount = body.contentLength,
          socket = socket,
        ).buffer()
    body.writeTo(responseBodySink)
    responseBodySink.flush()

    socket.sleepWhileOpen(response.trailersDelayNanos)
    if ("chunked".equals(response.headers["Transfer-Encoding"], ignoreCase = true)) {
      writeHeaders(socket.sink, response.trailers)
    }
  }

  @Throws(IOException::class)
  private fun writeHeaders(
    sink: BufferedSink,
    headers: Headers,
  ) {
    for ((name, value) in headers) {
      sink.writeUtf8(name)
      sink.writeUtf8(": ")
      sink.writeUtf8(value)
      sink.writeUtf8("\r\n")
    }
    sink.writeUtf8("\r\n")
    sink.flush()
  }

  /** Returns a sink that applies throttling and disconnecting. */
  private fun Sink.withThrottlingAndSocketEffect(
    policy: MockResponse,
    socketEffect: SocketEffect?,
    expectedByteCount: Long,
    socket: MockWebServerSocket,
    stream: Http2Stream? = null,
  ): Sink {
    var result: Sink = this

    if (policy.throttlePeriodNanos > 0L) {
      result =
        ThrottledSink(
          socket = socket,
          delegate = result,
          bytesPerPeriod = policy.throttleBytesPerPeriod,
          periodDelayNanos = policy.throttlePeriodNanos,
        )
    }

    if (socketEffect != null) {
      val halfwayByteCount =
        when {
          expectedByteCount != -1L -> expectedByteCount / 2
          else -> 0L
        }
      result =
        TriggerSink(
          delegate = result,
          triggerByteCount = halfwayByteCount,
        ) {
          result.flush()
          handleSocketEffect(socketEffect, socket, stream)
        }
    }

    return result
  }

  /** Returns true if processing this exchange is complete. */
  private fun handleSocketEffect(
    effect: SocketEffect?,
    socket: MockWebServerSocket,
    stream: Http2Stream? = null,
  ): Boolean {
    if (effect == null) return false

    when (effect) {
      is CloseStream -> {
        if (stream != null) {
          stream.close(ErrorCode.fromHttp2(effect.http2ErrorCode)!!, null)
        } else {
          socket.close()
        }
      }

      ShutdownConnection -> {
        if (stream != null) {
          stream.connection.shutdown(ErrorCode.NO_ERROR)
        } else {
          socket.close()
        }
      }

      is CloseSocket -> {
        if (effect.shutdownInput) socket.shutdownInput()
        if (effect.shutdownOutput) socket.shutdownOutput()
        if (effect.closeSocket) socket.close()
      }

      Stall -> {
        // Sleep until the socket is closed.
        socket.sleepWhileOpen(TimeUnit.MINUTES.toNanos(60))
        error("expected timeout")
      }
    }

    return true
  }

  @Throws(IOException::class)
  private fun readEmptyLine(source: BufferedSource) {
    val line = source.readUtf8LineStrict()
    check(line.isEmpty()) { "Expected empty but was: $line" }
  }

  public override fun toString(): String {
    val socketAddress = socketAddress_
    return when {
      closed -> "MockWebServer{closed}"
      socketAddress != null -> "MockWebServer{port=${socketAddress.port}}"
      else -> "MockWebServer{new}"
    }
  }

  /** A buffer wrapper that drops data after [bodyLimit] bytes. */
  private class TruncatingBuffer(
    private var remainingByteCount: Long,
  ) : Sink {
    val buffer = Buffer()
    var receivedByteCount = 0L

    @Throws(IOException::class)
    override fun write(
      source: Buffer,
      byteCount: Long,
    ) {
      val toRead = minOf(remainingByteCount, byteCount)
      if (toRead > 0L) {
        source.read(buffer, toRead)
      }
      val toSkip = byteCount - toRead
      if (toSkip > 0L) {
        source.skip(toSkip)
      }
      remainingByteCount -= toRead
      receivedByteCount += byteCount
    }

    @Throws(IOException::class)
    override fun flush() {
    }

    override fun timeout(): Timeout = Timeout.NONE

    @Throws(IOException::class)
    override fun close() {
    }
  }

  /** Processes HTTP requests layered over HTTP/2. */
  private inner class Http2SocketHandler(
    private val connectionIndex: Int,
    private val socket: MockWebServerSocket,
    private val protocol: Protocol,
  ) : Http2Connection.Listener() {
    private val nextExchangeIndex = AtomicInteger()

    @Throws(IOException::class)
    override fun onStream(stream: Http2Stream) {
      val peek = dispatcher.peek()
      if (handleSocketEffect(peek.onRequestStart, socket, stream)) {
        dispatchBookkeepingRequest(
          connectionIndex = connectionIndex,
          exchangeIndex = nextExchangeIndex.getAndIncrement(),
          socket = socket,
          requestLine = DEFAULT_REQUEST_LINE_HTTP_2,
        )
        return
      }

      val request = readRequest(stream)
      atomicRequestCount.incrementAndGet()
      requestQueue.add(request)
      if (request.failure != null) {
        return // Nothing to respond to.
      }

      val response = dispatcher.dispatch(request)

      try {
        if (handleSocketEffect(peek.onResponseStart, socket, stream)) {
          return
        }

        writeResponse(stream, request, response)
        if (logger.isLoggable(Level.FINE)) {
          logger.fine(
            "${this@MockWebServer} received request: $request " +
              "and responded: $response protocol is $protocol",
          )
        }

        handleSocketEffect(peek.onResponseEnd, socket, stream)
      } finally {
        if (response.shutdownServer) {
          close()
        }
      }
    }

    @Throws(IOException::class)
    private fun readRequest(stream: Http2Stream): RecordedRequest {
      val streamHeaders = stream.takeHeaders()
      val httpHeaders = Headers.Builder()
      var method = "<:method omitted>"
      var path = "<:path omitted>"
      var readBody = true
      for ((name, value) in streamHeaders) {
        if (name == Header.TARGET_METHOD_UTF8) {
          method = value
        } else if (name == Header.TARGET_PATH_UTF8) {
          path = value
        } else if (protocol === Protocol.HTTP_2 || protocol === Protocol.H2_PRIOR_KNOWLEDGE) {
          httpHeaders.add(name, value)
        } else {
          throw IllegalStateException()
        }
        if (name == "expect" && value.equals("100-continue", ignoreCase = true)) {
          // Don't read the body unless we've invited the client to send it.
          readBody = false
        }
      }
      val headers = httpHeaders.build()

      val peek = dispatcher.peek()
      for (response in peek.informationalResponses) {
        socket.sleepWhileOpen(response.headersDelayNanos)
        stream.writeHeaders(response.toHttp2Headers(), outFinished = false, flushHeaders = true)
        if (response.code == 100) {
          readBody = true
        }
      }

      val requestLine =
        RequestLine(
          method = method,
          target = path,
          version = "HTTP/2",
        )
      var exception: IOException? = null
      var bodyByteString: ByteString? = null
      if (readBody && peek.socketHandler == null && !peek.doNotReadRequestBody) {
        val body = Buffer()
        try {
          val contentLengthString = headers["content-length"]
          val requestBodySink =
            body
              .withThrottlingAndSocketEffect(
                policy = peek,
                socketEffect = peek.onRequestBody,
                expectedByteCount = contentLengthString?.toLong() ?: Long.MAX_VALUE,
                socket = socket,
                stream = stream,
              ).buffer()
          requestBodySink.use {
            it.writeAll(stream.source)
          }
        } catch (e: IOException) {
          exception = e
        } finally {
          bodyByteString = body.readByteString()
        }
      }

      return RecordedRequest(
        requestLine = requestLine,
        headers = headers,
        chunkSizes = null, // No chunked encoding for HTTP/2.
        bodySize = bodyByteString?.size?.toLong() ?: 0,
        body =
          when {
            HttpMethod.permitsRequestBody(method) -> bodyByteString
            else -> null
          },
        connectionIndex = connectionIndex,
        exchangeIndex = nextExchangeIndex.getAndIncrement(),
        socket = socket,
        failure = exception,
      )
    }

    private fun MockResponse.toHttp2Headers(): List<Header> {
      val result = mutableListOf<Header>()
      result += Header(Header.RESPONSE_STATUS, code.toString())
      for ((name, value) in headers) {
        result += Header(name, value)
      }
      return result
    }

    @Throws(IOException::class)
    private fun writeResponse(
      stream: Http2Stream,
      request: RecordedRequest,
      response: MockResponse,
    ) {
      val settings = response.settings
      stream.connection.setSettings(settings)

      val bodyDelayNanos = response.bodyDelayNanos
      val trailers = response.trailers
      val body = response.body
      val socketHandler = response.socketHandler
      val outFinished = (
        body == null &&
          response.pushPromises.isEmpty() &&
          socketHandler == null
      )
      val flushHeaders = body == null || bodyDelayNanos != 0L
      require(!outFinished || trailers.size == 0) {
        "unsupported: no body and non-empty trailers $trailers"
      }

      socket.sleepWhileOpen(response.headersDelayNanos)
      stream.writeHeaders(response.toHttp2Headers(), outFinished, flushHeaders)

      if (trailers.size > 0) {
        stream.enqueueTrailers(trailers)
      }
      pushPromises(stream, request, response.pushPromises)
      if (body != null) {
        socket.sleepWhileOpen(bodyDelayNanos)
        val responseBodySink =
          stream
            .sink
            .withThrottlingAndSocketEffect(
              policy = response,
              socketEffect = response.onResponseBody,
              expectedByteCount = body.contentLength,
              socket = socket,
              stream = stream,
            ).buffer()
        responseBodySink.use {
          body.writeTo(it)

          // Delay trailers by sleeping before we close the stream. It's the same on the wire.
          if (response.trailersDelayNanos != 0L) {
            it.flush()
            socket.sleepWhileOpen(response.trailersDelayNanos)
          }
        }
      } else if (socketHandler != null) {
        socketHandler.handle(stream)
      } else if (!outFinished) {
        socket.sleepWhileOpen(response.trailersDelayNanos)
        stream.close(ErrorCode.NO_ERROR, null)
      }
    }

    @Throws(IOException::class)
    private fun pushPromises(
      stream: Http2Stream,
      request: RecordedRequest,
      promises: List<PushPromise>,
    ) {
      for (pushPromise in promises) {
        val pushedHeaders = mutableListOf<Header>()
        pushedHeaders.add(Header(Header.TARGET_AUTHORITY, url(pushPromise.path).host))
        pushedHeaders.add(Header(Header.TARGET_METHOD, pushPromise.method))
        pushedHeaders.add(Header(Header.TARGET_PATH, pushPromise.path))
        val pushPromiseHeaders = pushPromise.headers
        for ((name, value) in pushPromiseHeaders) {
          pushedHeaders.add(Header(name, value))
        }
        val requestLine =
          RequestLine(
            method = pushPromise.method,
            target = pushPromise.path,
            version = "HTTP/2",
          )
        requestQueue.add(
          RecordedRequest(
            requestLine = requestLine,
            headers = pushPromise.headers,
            chunkSizes = null, // No chunked encoding for HTTP/2.
            bodySize = 0,
            body = null,
            connectionIndex = connectionIndex,
            exchangeIndex = nextExchangeIndex.getAndIncrement(),
            socket = socket,
          ),
        )
        val hasBody = pushPromise.response.body != null
        val pushedStream = stream.connection.pushStream(stream.id, pushedHeaders, hasBody)
        writeResponse(pushedStream, request, pushPromise.response)
      }
    }
  }

  private companion object {
    private const val CLIENT_AUTH_NONE = 0
    private const val CLIENT_AUTH_REQUESTED = 1
    private const val CLIENT_AUTH_REQUIRED = 2

    private val UNTRUSTED_TRUST_MANAGER =
      object : X509TrustManager {
        @Throws(CertificateException::class)
        override fun checkClientTrusted(
          chain: Array<X509Certificate>,
          authType: String,
        ) = throw CertificateException()

        override fun checkServerTrusted(
          chain: Array<X509Certificate>,
          authType: String,
        ) = throw AssertionError()

        override fun getAcceptedIssuers(): Array<X509Certificate> = throw AssertionError()
      }

    private val logger = Logger.getLogger(MockWebServer::class.java.name)
  }
}
