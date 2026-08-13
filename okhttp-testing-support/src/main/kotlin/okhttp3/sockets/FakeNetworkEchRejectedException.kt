/*
 * Copyright (c) 2026 OkHttp Authors
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
package okhttp3.sockets

import javax.net.ssl.SSLException
import okio.ByteString

/**
 * Thrown when handshaking with `ClientHelloInner` fails, but handshaking with `ClientHelloOuter`
 * succeeds.
 *
 * See RFC 9849, section 6.1.4.
 *
 * https://www.rfc-editor.org/rfc/rfc9849.html#section-6.1.4
 */
class FakeNetworkEchRejectedException(
  val publicName: String,
  val nextEchConfigList: ByteString?,
) : SSLException("ECH rejected")
