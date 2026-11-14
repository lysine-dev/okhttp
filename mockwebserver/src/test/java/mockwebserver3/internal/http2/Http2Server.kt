/*
 * Copyright (C) 2011 The Android Open Source Project
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
package mockwebserver3.internal.http2

import java.io.File
import java.io.IOException
import java.net.InetSocketAddress
import java.net.ProtocolException
import java.net.ServerSocket
import java.net.Socket
import java.util.logging.Level
import java.util.logging.Logger
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory
import okhttp3.Protocol
import okhttp3.Protocol.Companion.get
import okhttp3.internal.closeQuietly
import okhttp3.internal.concurrent.TaskRunner
import okhttp3.internal.connection.asBufferedSocket
import okhttp3.internal.http2.Header
import okhttp3.internal.http2.Http2Connection
import okhttp3.internal.http2.Http2Stream
import okhttp3.internal.platform.Platform
import okhttp3.tls.internal.TlsUtil.localhost
import okio.buffer
import okio.source

/** A basic HTTP/2 server that serves the contents of a local directory.  */
class Http2Server(
  private val baseDirectory: File,
  private val sslSocketFactory: SSLSocketFactory,
) : Http2Connection.Listener() {
  private fun run() {
    val serverSocket = ServerSocket(8888)
    serverSocket.reuseAddress = true
    while (true) {
      var socket: Socket? = null
      try {
        socket = serverSocket.accept()
        val sslSocket = doSsl(socket)
        val protocolString = Platform.get().getSelectedProtocol(sslSocket)
        val protocol = if (protocolString != null) get(protocolString) else null
        if (protocol != Protocol.HTTP_2) {
          throw ProtocolException("Protocol $protocol unsupported")
        }
        val connection =
          Http2Connection
            .Builder(false, TaskRunner.INSTANCE)
            .socket(sslSocket.asBufferedSocket(), sslSocket.peerName())
            .listener(this)
            .build()
        connection.start()
      } catch (e: IOException) {
        logger.log(Level.INFO, "Http2Server connection failure: $e")
        socket?.closeQuietly()
      } catch (e: Exception) {
        logger.log(Level.WARNING, "Http2Server unexpected failure", e)
        socket?.closeQuietly()
      }
    }
  }

  private fun doSsl(socket: Socket): SSLSocket {
    val sslSocket =
      sslSocketFactory.createSocket(
        socket,
        socket.inetAddress.hostAddress,
        socket.port,
        true,
      ) as SSLSocket
    sslSocket.useClientMode = false
    Platform.get().configureTlsExtensions(
      sslSocket = sslSocket,
      hostname = null,
      protocols = listOf(Protocol.HTTP_2),
      echConfigList = null,
    )
    sslSocket.startHandshake()
    return sslSocket
  }

  override fun onStream(stream: Http2Stream) {
    try {
      val requestHeaders = stream.takeHeaders()
      var path: String? = null
      var i = 0
      val size = requestHeaders.size
      while (i < size) {
        if (requestHeaders.name(i) == Header.TARGET_PATH_UTF8) {
          path = requestHeaders.value(i)
          break
        }
        i++
      }
      if (path == null) {
        // TODO: send bad request error
        throw AssertionError()
      }
      val file = File(baseDirectory.toString() + path)
      if (file.isDirectory) {
        serveDirectory(stream, file.listFiles()!!)
      } else if (file.exists()) {
        serveFile(stream, file)
      } else {
        send404(stream, path)
      }
    } catch (e: IOException) {
      Platform.get().log("Failure serving Http2Stream: " + e.message, Platform.INFO, null)
    }
  }

  private fun send404(
    stream: Http2Stream,
    path: String,
  ) {
    val responseHeaders =
      listOf(
        Header(":status", "404"),
        Header(":version", "HTTP/1.1"),
        Header("content-type", "text/plain"),
      )
    stream.writeHeaders(
      responseHeaders = responseHeaders,
      outFinished = false,
      flushHeaders = false,
    )
    val out = stream.sink.buffer()
    out.writeUtf8("Not found: $path")
    out.close()
  }

  private fun serveDirectory(
    stream: Http2Stream,
    files: Array<File>,
  ) {
    val responseHeaders =
      listOf(
        Header(":status", "200"),
        Header(":version", "HTTP/1.1"),
        Header("content-type", "text/html; charset=UTF-8"),
      )
    stream.writeHeaders(
      responseHeaders = responseHeaders,
      outFinished = false,
      flushHeaders = false,
    )
    val out = stream.sink.buffer()
    for (file in files) {
      val target = if (file.isDirectory) file.name + "/" else file.name
      out.writeUtf8("<a href='$target'>$target</a><br>")
    }
    out.close()
  }

  private fun serveFile(
    stream: Http2Stream,
    file: File,
  ) {
    val responseHeaders =
      listOf(
        Header(":status", "200"),
        Header(":version", "HTTP/1.1"),
        Header("content-type", contentType(file)),
      )
    stream.writeHeaders(
      responseHeaders = responseHeaders,
      outFinished = false,
      flushHeaders = false,
    )
    file.source().use { source ->
      stream.sink.buffer().use { sink ->
        sink.writeAll(source)
      }
    }
  }

  private fun contentType(file: File): String =
    when {
      file.name.endsWith(".css") -> "text/css"
      file.name.endsWith(".gif") -> "image/gif"
      file.name.endsWith(".html") -> "text/html"
      file.name.endsWith(".jpeg") -> "image/jpeg"
      file.name.endsWith(".jpg") -> "image/jpeg"
      file.name.endsWith(".js") -> "application/javascript"
      file.name.endsWith(".png") -> "image/png"
      else -> "text/plain"
    }

  private fun Socket.peerName(): String {
    val address = remoteSocketAddress
    return if (address is InetSocketAddress) address.hostName else address.toString()
  }

  companion object {
    val logger: Logger = Logger.getLogger(Http2Server::class.java.name)

    @JvmStatic
    fun main(args: Array<String>) {
      if (args.size != 1 || args[0].startsWith("-")) {
        println("Usage: Http2Server <base directory>")
        return
      }
      val server =
        Http2Server(
          File(args[0]),
          localhost().sslContext().socketFactory,
        )
      server.run()
    }
  }
}
