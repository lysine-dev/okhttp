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
import assertk.assertions.isNotNull
import assertk.assertions.isNull
import javax.net.ssl.SSLSocket
import okhttp3.internal.platform.Jdk9Platform.Companion.buildIfSupported
import okhttp3.sockets.DelegatingSSLSocket
import okhttp3.testing.PlatformRule
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.RegisterExtension

class Jdk9PlatformTest {
  @RegisterExtension
  val platform = PlatformRule()

  @Test
  fun buildsWhenJdk9() {
    platform.assumeJdk9()
    assertThat(buildIfSupported()).isNotNull()
  }

  @Test
  fun buildsWhenJdk8() {
    platform.assumeJdk8()
    try {
      SSLSocket::class.java.getMethod("getApplicationProtocol")
      // also present on JDK8 after build 252.
      assertThat(buildIfSupported()).isNotNull()
    } catch (nsme: NoSuchMethodException) {
      assertThat(buildIfSupported()).isNull()
    }
  }

  @Test
  fun testToStringIsClassname() {
    assertThat(Jdk9Platform().toString()).isEqualTo("Jdk9Platform")
  }

  @Test
  fun selectedProtocolIsNullWhenSslSocketThrowsExceptionForApplicationProtocol() {
    platform.assumeJdk9()
    val applicationProtocolUnsupported =
      object : DelegatingSSLSocket(null) {
        override fun getApplicationProtocol(): String = throw UnsupportedOperationException("Mock exception")
      }
    assertThat(Jdk9Platform().getSelectedProtocol(applicationProtocolUnsupported)).isNull()
  }
}
