/*
 * Copyright (C) 2025 Block, Inc.
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

import android.os.StrictMode
import android.os.StrictMode.ThreadPolicy
import android.os.strictmode.Violation
import androidx.test.filters.SdkSuppress
import assertk.assertThat
import assertk.assertions.hasSize
import assertk.assertions.isEmpty
import assertk.assertions.isEqualTo
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.internal.platform.Platform
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.parallel.Isolated

@Isolated
@SdkSuppress(minSdkVersion = 28)
class StrictModeTest {
  private val violations = mutableListOf<Violation>()

  @AfterEach
  fun cleanup() {
    StrictMode.setThreadPolicy(
      ThreadPolicy
        .Builder()
        .permitAll()
        .build(),
    )
  }

  @Test
  fun testInit() {
    Platform.resetForTests()

    applyStrictMode()

    // Not currently safe
    // See https://github.com/lysine-dev/okhttp/pull/8248
    OkHttpClient()

    assertThat(violations).hasSize(1)
    assertThat(violations[0].message).isEqualTo("newSSLContext")
  }

  @Test
  fun testNewCall() {
    Platform.resetForTests()

    val client = OkHttpClient()

    applyStrictMode()

    // Safe on main
    client.newCall(Request("https://google.com/robots.txt".toHttpUrl()))

    assertThat(violations).isEmpty()
  }

  private fun applyStrictMode() {
    StrictMode.setThreadPolicy(
      ThreadPolicy
        .Builder()
        .detectCustomSlowCalls()
        .penaltyListener({ it.run() }) {
          violations.add(it)
        }.build(),
    )
  }
}
