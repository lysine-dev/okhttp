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
package okhttp3.internal.platform

import java.lang.reflect.InvocationHandler
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Method
import java.lang.reflect.Proxy
import javax.net.ssl.SSLSocket
import okhttp3.Protocol
import okio.ByteString

/** OpenJDK 8 with `org.mortbay.jetty.alpn:alpn-boot` in the boot class path. */
class Jdk8WithJettyBootPlatform(
  private val putMethod: Method,
  private val getMethod: Method,
  private val removeMethod: Method,
  private val clientProviderClass: Class<*>,
  private val serverProviderClass: Class<*>,
) : Platform() {
  override fun configureTlsExtensions(
    sslSocket: SSLSocket,
    hostname: String?,
    protocols: List<Protocol>,
    echConfigList: ByteString?,
  ) {
    val names = alpnProtocolNames(protocols)

    try {
      val alpnProvider =
        Proxy.newProxyInstance(
          Platform::class.java.classLoader,
          arrayOf(clientProviderClass, serverProviderClass),
          AlpnProvider(names),
        )
      putMethod.invoke(null, sslSocket, alpnProvider)
    } catch (e: InvocationTargetException) {
      throw AssertionError("failed to set ALPN", e)
    } catch (e: IllegalAccessException) {
      throw AssertionError("failed to set ALPN", e)
    }
  }

  override fun afterHandshake(sslSocket: SSLSocket) {
    try {
      removeMethod.invoke(null, sslSocket)
    } catch (e: IllegalAccessException) {
      throw AssertionError("failed to remove ALPN", e)
    } catch (e: InvocationTargetException) {
      throw AssertionError("failed to remove ALPN", e)
    }
  }

  override fun getSelectedProtocol(sslSocket: SSLSocket): String? {
    try {
      val provider = Proxy.getInvocationHandler(getMethod.invoke(null, sslSocket)) as AlpnProvider
      if (!provider.unsupported && provider.selected == null) {
        log("ALPN callback dropped: HTTP/2 is disabled. " + "Is alpn-boot on the boot class path?")
        return null
      }
      return if (provider.unsupported) null else provider.selected
    } catch (e: InvocationTargetException) {
      throw AssertionError("failed to get ALPN selected protocol", e)
    } catch (e: IllegalAccessException) {
      throw AssertionError("failed to get ALPN selected protocol", e)
    }
  }

  /**
   * Handle the methods of ALPN's ClientProvider and ServerProvider without a compile-time
   * dependency on those interfaces.
   */
  private class AlpnProvider(
    /** This peer's supported protocols. */
    private val protocols: List<String>,
  ) : InvocationHandler {
    /** Set when remote peer notifies ALPN is unsupported. */
    var unsupported: Boolean = false

    /** The protocol the server selected. */
    var selected: String? = null

    @Throws(Throwable::class)
    override fun invoke(
      proxy: Any,
      method: Method,
      args: Array<Any>?,
    ): Any? {
      val callArgs = args ?: arrayOf<Any?>()
      val methodName = method.name
      val returnType = method.returnType
      if (methodName == "supports" && Boolean::class.javaPrimitiveType == returnType) {
        return true // ALPN is supported.
      } else if (methodName == "unsupported" && Void.TYPE == returnType) {
        this.unsupported = true // Peer doesn't support ALPN.
        return null
      } else if (methodName == "protocols" && callArgs.isEmpty()) {
        return protocols // Client advertises these protocols.
      } else if ((methodName == "selectProtocol" || methodName == "select") &&
        String::class.java == returnType &&
        callArgs.size == 1 &&
        callArgs[0] is List<*>
      ) {
        val peerProtocols = callArgs[0] as List<*>
        // Pick the first known protocol the peer advertises.
        for (i in 0..peerProtocols.size) {
          val protocol = peerProtocols[i] as String
          if (protocol in protocols) {
            selected = protocol
            return selected
          }
        }
        selected = protocols[0] // On no intersection, try peer's first protocol.
        return selected
      } else if ((methodName == "protocolSelected" || methodName == "selected") && callArgs.size == 1) {
        this.selected = callArgs[0] as String // Server selected this protocol.
        return null
      } else {
        return method.invoke(this, *callArgs)
      }
    }
  }

  companion object {
    fun buildIfSupported(): Platform? {
      val jvmVersion = System.getProperty("java.specification.version", "unknown")
      try {
        // 1.8, 9, 10, 11, 12 etc
        val version = jvmVersion.toInt()
        if (version >= 9) return null
      } catch (_: NumberFormatException) {
        // expected on >= JDK 9
      }

      // Find Jetty's ALPN extension for OpenJDK.
      try {
        val alpnClassName = "org.eclipse.jetty.alpn.ALPN"
        val alpnClass = Class.forName(alpnClassName, true, null)
        val providerClass = Class.forName("$alpnClassName\$Provider", true, null)
        val clientProviderClass = Class.forName("$alpnClassName\$ClientProvider", true, null)
        val serverProviderClass = Class.forName("$alpnClassName\$ServerProvider", true, null)
        val putMethod = alpnClass.getMethod("put", SSLSocket::class.java, providerClass)
        val getMethod = alpnClass.getMethod("get", SSLSocket::class.java)
        val removeMethod = alpnClass.getMethod("remove", SSLSocket::class.java)
        return Jdk8WithJettyBootPlatform(
          putMethod,
          getMethod,
          removeMethod,
          clientProviderClass,
          serverProviderClass,
        )
      } catch (_: ClassNotFoundException) {
      } catch (_: NoSuchMethodException) {
      }

      return null
    }
  }
}
