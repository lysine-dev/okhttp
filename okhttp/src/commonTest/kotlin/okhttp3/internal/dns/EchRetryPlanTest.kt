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
@file:OptIn(OkHttpInternalApi::class)

package okhttp3.internal.dns

import assertk.assertThat
import assertk.assertions.isNotNull
import assertk.assertions.isNull
import kotlin.test.Test
import okhttp3.internal.OkHttpInternalApi
import okhttp3.internal.ech.EchRetryPlan

/**
 * Confirm we correctly validate the requirements of RFC 9849 public names. We're stricter with
 * these than on regular DNS names, because we expect the DNS servers will reject those for us.
 *
 * https://www.rfc-editor.org/rfc/rfc9849.html#section-6.1.7
 */
class EchRetryPlanTest {
  @Test
  fun `valid public name`() {
    assertValid("ech.example.com")
    assertValid("ECH.EXAMPLE.COM")
    assertValid("abcdefghijklmnopqrstuvwxyzabcdefghijklmnopqrstuvwxyzabcdefghijk.example.com")
    assertValid("0xG")
    assertValid("0xG.example.com")
    assertValid("0XG")
    assertValid("0XG.example.com")
  }

  @Test
  fun `invalid public name`() {
    assertInvalid("1:2::3:4")
    assertInvalid("10.20.30.40")
    assertInvalid("ech.example.com.")
    assertInvalid(".ech.example.com")
    assertInvalid("abcdefghijklmnopqrstuvwxyzabcdefghijklmnopqrstuvwxyzabcdefghijkl.example.com")
    assertInvalid("ech.example.123")
    assertInvalid("ech.example.0x123")
    assertInvalid("123")
    assertInvalid("0xab")
    assertInvalid("0XAB")
    assertInvalid("0X")
  }

  private fun assertValid(publicName: String) {
    assertThat(
      EchRetryPlan.getOrNull(
        publicName = publicName,
        configList = null,
      ),
    ).isNotNull()
  }

  private fun assertInvalid(publicName: String) {
    assertThat(
      EchRetryPlan.getOrNull(
        publicName = publicName,
        configList = null,
      ),
    ).isNull()
  }
}
