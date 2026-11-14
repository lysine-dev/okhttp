/*
 * Copyright (C) 2014 Square, Inc.
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
package okhttp3.recipes.kt

import java.io.IOException
import okhttp3.OkHttpClient
import okhttp3.Request

class AccessHeaders {
  private val client = OkHttpClient()

  fun run() {
    val request =
      Request
        .Builder()
        .url("https://api.github.com/repos/lysine-dev/okhttp/issues")
        .header("User-Agent", "OkHttp Headers.java")
        .addHeader("Accept", "application/json; q=0.5")
        .addHeader("Accept", "application/vnd.github.v3+json")
        .build()

    client.newCall(request).execute().use { response ->
      if (!response.isSuccessful) throw IOException("Unexpected code $response")

      println("Server: ${response.header("Server")}")
      println("Date: ${response.header("Date")}")
      println("Vary: ${response.headers("Vary")}")
    }
  }
}

fun main() {
  AccessHeaders().run()
}
