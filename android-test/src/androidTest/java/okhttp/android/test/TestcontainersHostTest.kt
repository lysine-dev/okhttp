/*
 * Copyright (C) 2026 Block, Inc.
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
package okhttp.android.test

import androidx.test.platform.app.InstrumentationRegistry
import kotlin.test.assertEquals
import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test

class TestcontainersHostTest {
  @Test
  fun reachesTestcontainersServiceOnHost() {
    assumeTrue(
      InstrumentationRegistry.getArguments().getString("testcontainers") == "true",
      "requires the host-side Testcontainers launcher",
    )

    val request = Request.Builder().url("http://127.0.0.1:8080/android-test").build()

    OkHttpClient().newCall(request).execute().use { response ->
      assertEquals(200, response.code)
      assertEquals("hello from Testcontainers", response.body.string())
    }
  }
}
