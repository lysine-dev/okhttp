/*
 * Copyright (C) 2022 Square, Inc.
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

import java.net.Inet6Address
import okhttp3.Route
import okhttp3.internal.interleave

/**
 * Implementation of HappyEyeballs Sorting Addresses.
 *
 * The current implementation does not address any of:
 *  - Async DNS split by IP class
 *  - Stateful handling of connectivity results
 *  - The prioritisation of addresses
 *
 * https://datatracker.ietf.org/doc/html/rfc8305#section-4
 */
internal fun reorderForHappyEyeballs(addresses: List<Route>): List<Route> {
  if (addresses.size < 2) {
    return addresses
  }

  val (ipv6, ipv4) = addresses.partition { it.socketAddress.address is Inet6Address }

  return if (ipv6.isEmpty() || ipv4.isEmpty()) {
    addresses
  } else {
    interleave(ipv6, ipv4)
  }
}
