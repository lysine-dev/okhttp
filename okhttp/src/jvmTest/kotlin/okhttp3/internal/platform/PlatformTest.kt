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

import assertk.assertThat
import assertk.assertions.isEqualTo
import okhttp3.internal.platform.Platform.Companion.isAndroid
import okhttp3.testing.PlatformRule
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.RegisterExtension

class PlatformTest {
  @RegisterExtension
  var platform = PlatformRule()

  @Test
  fun alwaysBuilds() {
    Platform()
  }

  /** Guard against the default value changing by accident.  */
  @Test
  fun defaultPrefix() {
    assertThat(Platform().prefix).isEqualTo("OkHttp")
  }

  @Test
  fun testToStringIsClassname() {
    assertThat(Platform().toString()).isEqualTo("Platform")
  }

  @Test
  fun testNotAndroid() {
    platform.assumeNotAndroid()

    // This is tautological so just confirms that it runs.
    assertThat(isAndroid).isEqualTo(false)
  }
}
