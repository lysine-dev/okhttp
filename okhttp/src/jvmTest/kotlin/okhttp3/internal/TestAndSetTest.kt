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
package okhttp3.internal

import assertk.assertThat
import assertk.assertions.isEqualTo
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.Test

class TestAndSetTest {
  @Test
  fun `happy path`() {
    val value = AtomicReference("a")
    val previous = value.testAndSet("b") { true }
    assertThat(previous).isEqualTo("a")
    assertThat(value.get()).isEqualTo("b")
  }

  @Test
  fun `condition does not match`() {
    val value = AtomicReference("a")
    val previous = value.testAndSet("b") { false }
    assertThat(previous).isEqualTo("a")
    assertThat(value.get()).isEqualTo("a")
  }

  @Test
  fun `value is updated to a matching value between test and set`() {
    val value = AtomicReference("a")
    val previous =
      value.testAndSet("b") {
        value.set("c")
        true
      }
    assertThat(previous).isEqualTo("c")
    assertThat(value.get()).isEqualTo("b")
  }

  @Test
  fun `value is updated to a non-matching value between test and set`() {
    val value = AtomicReference("a")
    val previous =
      value.testAndSet("b") {
        value.set("c")
        it == "a"
      }
    assertThat(previous).isEqualTo("c")
    assertThat(value.get()).isEqualTo("c")
  }
}
