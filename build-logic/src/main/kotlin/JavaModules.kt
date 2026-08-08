/*
 * Copyright (C) 2025 Square, Inc.
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

import me.champeau.mrjar.MultiReleaseExtension
import org.gradle.api.JavaVersion
import org.gradle.api.Project
import org.gradle.api.plugins.JavaPluginExtension
import org.gradle.api.tasks.bundling.Jar
import org.gradle.api.tasks.compile.JavaCompile
import org.gradle.jvm.toolchain.JavaLanguageVersion
import org.gradle.jvm.toolchain.JavaToolchainService
import org.gradle.kotlin.dsl.configure
import org.gradle.kotlin.dsl.getByType
import org.gradle.kotlin.dsl.named
import org.gradle.kotlin.dsl.register
import org.jetbrains.kotlin.gradle.tasks.KotlinJvmCompile

fun Project.applyJavaModules(
  moduleName: String,
  defaultVersion: Int = 8,
  javaModuleVersion: Int = 9,
  enableValidation: Boolean = true,
) {
  plugins.withId("org.jetbrains.kotlin.jvm") {
    applyJavaModulesJvm(
      moduleName = moduleName,
      defaultVersion = defaultVersion,
      javaModuleVersion = javaModuleVersion,
      enableValidation = enableValidation,
    )
  }

  plugins.withId("org.jetbrains.kotlin.multiplatform") {
    applyJavaModulesMultiplatform(
      moduleName = moduleName,
      javaModuleVersion = javaModuleVersion,
      enableValidation = enableValidation,
    )
  }
}

private fun Project.applyJavaModulesJvm(
  moduleName: String,
  defaultVersion: Int,
  javaModuleVersion: Int,
  enableValidation: Boolean,
) {
  plugins.apply("me.champeau.mrjar")

  configure<MultiReleaseExtension> {
    targetVersions(defaultVersion, javaModuleVersion)
  }

  tasks.named<JavaCompile>("compileJava9Java").configure {
    val compileKotlinTask = tasks.getByName("compileKotlin") as KotlinJvmCompile

    if (enableValidation) {
      compileKotlinTask.source(file("src/main/java9"))
    }

    configureModuleInfoCompilation(moduleName, compileKotlinTask)

    val javaToolchains = project.extensions.getByType<JavaToolchainService>()
    val javaPluginExtension = project.extensions.getByType<JavaPluginExtension>()
    javaCompiler.set(javaToolchains.compilerFor(javaPluginExtension.toolchain))
  }
}

private fun Project.applyJavaModulesMultiplatform(
  moduleName: String,
  javaModuleVersion: Int,
  enableValidation: Boolean,
) {
  val compileJavaModuleInfo =
    tasks.register<JavaCompile>("compileJavaModuleInfo") {
      val compileKotlinTask = tasks.getByName("compileKotlinJvm") as KotlinJvmCompile
      val targetDir = compileKotlinTask.destinationDirectory.dir("../java9")
      val sourceDir = file("src/jvmMain/java9")

      val javaToolchains = project.extensions.getByType<JavaToolchainService>()
      javaCompiler.set(
        javaToolchains.compilerFor {
          languageVersion.set(JavaLanguageVersion.of(11))
        },
      )

      source(sourceDir)
      if (enableValidation) {
        compileKotlinTask.source(sourceDir)
      }

      outputs.dir(targetDir)
      destinationDirectory.set(targetDir)
      sourceCompatibility = JavaVersion.toVersion(javaModuleVersion).toString()
      targetCompatibility = JavaVersion.toVersion(javaModuleVersion).toString()
      options.release.set(javaModuleVersion)

      configureModuleInfoCompilation(moduleName, compileKotlinTask)
    }

  tasks.named<Jar>("jvmJar").configure {
    manifest {
      attributes(mapOf("Multi-Release" to true))
    }

    from(compileJavaModuleInfo.map { it.destinationDirectory }) {
      into("META-INF/versions/9/")
    }
  }
}

private fun JavaCompile.configureModuleInfoCompilation(
  moduleName: String,
  compileKotlinTask: KotlinJvmCompile,
) {
  dependsOn(compileKotlinTask)

  // Ignore warnings about using 'requires transitive' on automatic modules.
  options.compilerArgs.add("-Xlint:-requires-transitive-automatic")

  // Patch the Kotlin output into the compilation so exporting packages works correctly.
  options.compilerArgs.addAll(
    listOf(
      "--patch-module",
      "$moduleName=${compileKotlinTask.destinationDirectory.get().asFile}",
    ),
  )

  classpath = compileKotlinTask.libraries
  modularity.inferModulePath.set(true)
}
