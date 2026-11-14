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
package okhttp3.buildsupport

import org.gradle.api.Project

val Project.platform: String
  get() = findProperty("okhttp.platform")?.toString() ?: "jdk9"

val Project.testJavaVersion: Int
  get() = findProperty("test.java.version")?.toString()?.toInt() ?: 21

val Project.androidBuild: Boolean
  get() = findProperty("androidBuild")?.toString()?.toBoolean() ?: false

val Project.alpnBootVersion: String?
  get() = findProperty("alpn.boot.version")?.toString()
