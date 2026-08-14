/*
 * Copyright (C) 2022 Block, Inc.
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
@file:OptIn(OkHttpInternalApi::class)

package okhttp3.internal.connection

import java.io.InterruptedIOException
import java.net.ProtocolException
import java.security.cert.CertificateException
import javax.net.ssl.SSLException
import javax.net.ssl.SSLHandshakeException
import javax.net.ssl.SSLPeerUnverifiedException
import okhttp3.internal.OkHttpInternalApi
import okhttp3.internal.ech.EchUntrustedException
import okio.IOException

/** Returns true if another connection should be attempted to any IP address. */
internal fun attemptAnotherConnection(e: IOException): Boolean = e !is EchUntrustedException

/** Returns true if the next [okhttp3.ConnectionSpec] should be attempted to the same IP address. */
fun attemptAnotherConnectionSpec(e: IOException): Boolean =
  when {
    // If there was a protocol problem, don't recover.
    e is ProtocolException -> false

    // If there was an interruption or timeout (SocketTimeoutException), don't recover.
    // For the socket connect timeout case we do not try the same host with a different
    // ConnectionSpec: we assume it is unreachable.
    e is InterruptedIOException -> false

    // If the problem was a CertificateException from the X509TrustManager, do not retry.
    e is SSLHandshakeException && e.cause is CertificateException -> false

    // e.g. a certificate pinning error.
    e is SSLPeerUnverifiedException -> false

    // Retry for all other SSL failures.
    e is SSLException -> true

    else -> false
  }
