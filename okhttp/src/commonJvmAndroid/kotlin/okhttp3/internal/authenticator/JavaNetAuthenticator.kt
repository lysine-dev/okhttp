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
package okhttp3.internal.authenticator

import java.io.IOException
import java.net.Authenticator
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Proxy
import okhttp3.Credentials
import okhttp3.Dns
import okhttp3.HttpUrl
import okhttp3.Request
import okhttp3.Response
import okhttp3.Route

/**
 * Adapts [Authenticator] to [okhttp3.Authenticator]. Configure OkHttp to use [Authenticator] with
 * [okhttp3.OkHttpClient.Builder.authenticator] or [okhttp3.OkHttpClient.Builder.proxyAuthenticator].
 */
class JavaNetAuthenticator(
  private val defaultDns: Dns = Dns.SYSTEM,
) : okhttp3.Authenticator {
  @Throws(IOException::class)
  override fun authenticate(
    route: Route?,
    response: Response,
  ): Request? {
    val challenges = response.challenges()
    val request = response.request
    val url = request.url
    val proxyAuthorization = response.code == 407
    val proxy = route?.proxy ?: Proxy.NO_PROXY

    for (challenge in challenges) {
      if (!"Basic".equals(challenge.scheme, ignoreCase = true)) {
        continue
      }

      val dns = route?.address?.dns ?: defaultDns
      val auth =
        if (proxyAuthorization) {
          if (proxy.type() == Proxy.Type.HTTP) {
            val proxyAddress = proxy.address() as InetSocketAddress
            Authenticator.requestPasswordAuthentication(
              proxyAddress.hostName,
              proxy.connectToInetAddress(url, dns),
              proxyAddress.port,
              url.scheme,
              challenge.realm,
              challenge.scheme,
              url.toUrl(),
              Authenticator.RequestorType.PROXY,
            )
          } else {
            null
          }
        } else {
          Authenticator.requestPasswordAuthentication(
            url.host,
            proxy.connectToInetAddress(url, dns),
            url.port,
            url.scheme,
            challenge.realm,
            challenge.scheme,
            url.toUrl(),
            Authenticator.RequestorType.SERVER,
          )
        }

      if (auth != null) {
        val credentialHeader = if (proxyAuthorization) "Proxy-Authorization" else "Authorization"
        val credential =
          Credentials.basic(
            auth.userName,
            String(auth.password),
            challenge.charset,
          )
        return request
          .newBuilder()
          .header(credentialHeader, credential)
          .build()
      }
    }

    return null // No challenges were satisfied!
  }

  @Throws(IOException::class)
  private fun Proxy.connectToInetAddress(
    url: HttpUrl,
    dns: Dns,
  ): InetAddress =
    when (type()) {
      Proxy.Type.DIRECT -> dns.lookup(url.host).first()
      else -> {
        val socketAddress = address() as InetSocketAddress
        if (socketAddress.isUnresolved) {
          dns.lookup(socketAddress.hostString).first()
        } else {
          socketAddress.address
        }
      }
    }
}
