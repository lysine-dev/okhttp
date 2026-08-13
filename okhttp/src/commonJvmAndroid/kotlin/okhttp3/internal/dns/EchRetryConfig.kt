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
package okhttp3.internal.dns

import okhttp3.internal.OkHttpInternalApi
import okio.ByteString

/**
 * ECH retry config. Sent by a server when the ECH configuration we offered has fallen out of sync
 * with the one it accepts: its TTL expired, or the server rotated to a new configuration. (For
 * example, Cloudflare publishes one configuration at a time and rotates it hourly, honoring the
 * previous one for a further 4 hours. A configuration cached past that grace period earns a retry
 * config.)
 *
 * If a new [configList] is present, the server securely replaced our ECH configuration, and it
 * must only be used when [publicHostname] can be validated against the certificate from the
 * SSLSession (the outer client hello). Authenticating the public name is what makes this safe:
 * https://www.rfc-editor.org/rfc/rfc9849.html#section-6.1.7
 *
 * A null [configList] means the server offered no usable retry configuration, which securely
 * disables ECH. Retry without ECH.
 *
 * https://www.rfc-editor.org/rfc/rfc9849.html#section-6.1.6
 */
@OkHttpInternalApi
data class EchRetryConfig(
  /** The client-facing server's name from `ECHConfig.contents.public_name`. */
  val publicHostname: String,
  /** updated ECH configList or null to retry without ECH */
  val configList: ByteString?,
)
