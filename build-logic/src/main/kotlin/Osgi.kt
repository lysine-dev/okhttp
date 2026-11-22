/*
 * Copyright (C) 2021 Square, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

import aQute.bnd.gradle.BundleTaskExtension
import org.gradle.api.Project
import org.gradle.api.artifacts.MinimalExternalModuleDependency
import org.gradle.api.artifacts.VersionCatalogsExtension
import org.gradle.api.plugins.ExtensionAware
import org.gradle.api.tasks.SourceSetContainer
import org.gradle.api.tasks.bundling.Jar as GradleJar
import org.gradle.kotlin.dsl.dependencies
import org.gradle.kotlin.dsl.get
import org.gradle.kotlin.dsl.getByName
import org.gradle.kotlin.dsl.named
import org.gradle.kotlin.dsl.provideDelegate

fun Project.applyOsgi(vararg bndProperties: String) {
  plugins.withId("org.jetbrains.kotlin.jvm") { applyOsgi("jar", "osgiApi", bndProperties) }
}

private fun Project.applyOsgi(
  jarTaskName: String,
  osgiApiConfigurationName: String,
  bndProperties: Array<out String>,
) {
  val osgi = project.sourceSets.create("osgi")
  val osgiApi = project.configurations.getByName(osgiApiConfigurationName)

  project.dependencies { osgiApi(kotlinOsgi) }

  val jarTask = tasks.getByName<GradleJar>(jarTaskName)
  val bundleExtension =
    jarTask.extensions.create(
      BundleTaskExtension.NAME,
      BundleTaskExtension::class.java,
      jarTask,
    )
  bundleExtension.run {
    setClasspath(osgi.compileClasspath + sourceSets["main"].compileClasspath)
    properties.empty()
    bnd(*bndProperties)
  }
  jarTask.doLast(
    "buildBundle",
    BndBuildAction(bundleExtension, jarTask, sourceSets["main"].allSource),
  )
}

fun Project.applyOsgiMultiplatform(vararg bndProperties: String) {
  // BND is incompatible with Kotlin/Multiplatform because it assumes the JVM source set's
  // name is
  // 'main'. Work around this by creating a 'main' source set that forwards to 'jvmMain'.
  //
  // The forwarding SourceSet also needs to fake out some task names to prevent them from
  // being
  // registered twice.
  //
  // https://github.com/bndtools/bnd/issues/6590
  val mainSourceSet = BndBuildAction.installWorkaround(project)

  val osgiApi = configurations.create("osgiApi")
  dependencies { osgiApi(kotlinOsgi) }

  tasks.named<GradleJar>("jvmJar").configure {
    val bundleExtension =
      extensions.create(
        BundleTaskExtension.NAME,
        BundleTaskExtension::class.java,
        this,
      )
    val osgiApiArtifacts = osgiApi.artifacts
    val jvmMainClasses = tasks.named("jvmMainClasses").map { it.outputs.files }
    bundleExtension.apply {
      classpath(osgiApiArtifacts)
      classpath(jvmMainClasses)
      properties.empty()
      bnd(*bndProperties)
    }
    doLast("buildBundle", BndBuildAction(bundleExtension, this, mainSourceSet.allSource))
  }
}

val Project.sourceSets: SourceSetContainer
  get() = (this as ExtensionAware).extensions["sourceSets"] as SourceSetContainer

private val Project.kotlinOsgi: MinimalExternalModuleDependency
  get() =
    extensions
      .getByType(VersionCatalogsExtension::class.java)
      .named("libs")
      .findLibrary("kotlin.stdlib.osgi")
      .get()
      .get()
