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
package mockwebserver3.junit5.internal

import java.lang.reflect.Modifier
import mockwebserver3.MockWebServer
import mockwebserver3.junit5.StartStop
import okhttp3.internal.SuppressSignatureCheck
import org.junit.jupiter.api.extension.BeforeAllCallback
import org.junit.jupiter.api.extension.BeforeEachCallback
import org.junit.jupiter.api.extension.ExtensionContext
import org.junit.jupiter.api.extension.ExtensionContext.Namespace
import org.junit.platform.commons.support.AnnotationSupport.findAnnotatedFields

/** Implements the policy specified by [StartStop]. */
@SuppressSignatureCheck
internal class StartStopExtension :
  BeforeEachCallback,
  BeforeAllCallback {
  override fun beforeAll(context: ExtensionContext) {
    val store = context.getStore(Namespace.create(StartStop::class.java))

    val staticFields =
      findAnnotatedFields(
        context.requiredTestClass,
        StartStop::class.java,
      ) { Modifier.isStatic(it.modifiers) }

    for (field in staticFields) {
      field.setAccessible(true)
      val server = field.get(null) as? MockWebServer ?: continue

      // Put the instance in the store, so JUnit closes it for us in afterAll.
      store.put(field, server)

      server.start()
    }
  }

  override fun beforeEach(context: ExtensionContext) {
    val store = context.getStore(Namespace.create(StartStop::class.java))

    // A @Nested test declares its @StartStop fields on an enclosing instance, so walk every
    // instance in the hierarchy instead of just the innermost one from context.testInstance.
    for (testInstance in context.requiredTestInstances.allInstances) {
      val instanceFields =
        findAnnotatedFields(
          testInstance.javaClass,
          StartStop::class.java,
        ) { !Modifier.isStatic(it.modifiers) }

      for (field in instanceFields) {
        field.setAccessible(true)
        val server = field.get(testInstance) as? MockWebServer ?: continue

        // Put the instance in the store, so JUnit closes it for us in afterEach.
        store.put(field, server)

        server.start()
      }
    }
  }
}
