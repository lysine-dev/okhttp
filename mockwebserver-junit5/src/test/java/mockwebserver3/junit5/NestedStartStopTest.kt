/*
 * Copyright (C) 2025 Square, Inc.
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
package mockwebserver3.junit5

import assertk.assertThat
import assertk.assertions.isTrue
import mockwebserver3.MockWebServer
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

class NestedStartStopTest {
  @StartStop val outerServer = MockWebServer()

  @Test
  fun outerServerStartsForOuterTest() {
    assertThat(outerServer.started).isTrue()
  }

  @Nested
  inner class Group {
    @StartStop val nestedServer = MockWebServer()

    @Test
    fun outerServerStartsForNestedTest() {
      // Before the fix the outer @StartStop field was never started for @Nested tests.
      assertThat(outerServer.started).isTrue()
      assertThat(nestedServer.started).isTrue()
    }
  }
}
