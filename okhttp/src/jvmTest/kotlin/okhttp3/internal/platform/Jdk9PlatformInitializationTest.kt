/*
 * Copyright (C) 2026 Square, Inc.
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

import org.junit.jupiter.api.Test

class Jdk9PlatformInitializationTest {
  @Test
  fun classForNameDoesNotTriggerPlatformInitializationCycle() {
    val loader = PlatformClassLoader(Jdk9PlatformInitializationTest::class.java.classLoader)

    Class.forName("okhttp3.internal.platform.Jdk9Platform", true, loader)
  }

  private class PlatformClassLoader(
    private val parentClassLoader: ClassLoader,
  ) : ClassLoader(parentClassLoader) {
    override fun loadClass(
      name: String,
      resolve: Boolean,
    ): Class<*> {
      synchronized(getClassLoadingLock(name)) {
        val loadedClass = findLoadedClass(name)
        if (loadedClass != null) {
          return loadedClass.also {
            if (resolve) {
              resolveClass(it)
            }
          }
        }

        val loadedPlatformClass =
          if (name.startsWith("okhttp3.internal.platform.")) {
            findClass(name)
          } else {
            super.loadClass(name, false)
          }

        if (resolve) {
          resolveClass(loadedPlatformClass)
        }
        return loadedPlatformClass
      }
    }

    override fun findClass(name: String): Class<*> {
      val resourceName = name.replace('.', '/') + ".class"
      val bytes =
        parentClassLoader.getResourceAsStream(resourceName)?.use { it.readBytes() }
          ?: throw ClassNotFoundException(name)

      return defineClass(name, bytes, 0, bytes.size)
    }
  }
}
