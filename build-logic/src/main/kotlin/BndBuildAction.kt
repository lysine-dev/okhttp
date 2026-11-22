/*
 * Copyright (c) aQute SARL (2000, 2021). All Rights Reserved.
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

import aQute.bnd.gradle.BundleTaskExtension
import aQute.bnd.osgi.Builder
import aQute.bnd.osgi.Constants
import aQute.bnd.osgi.Jar
import aQute.bnd.osgi.Processor
import aQute.bnd.version.MavenVersion
import aQute.lib.io.IO
import aQute.lib.utf8properties.UTF8Properties
import java.io.File
import java.util.Properties
import java.util.jar.Manifest
import java.util.zip.ZipFile
import org.gradle.api.Action
import org.gradle.api.GradleException
import org.gradle.api.Task
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.FileCollection
import org.gradle.api.file.ProjectLayout
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.MapProperty
import org.gradle.api.provider.Provider
import org.gradle.api.tasks.bundling.Jar as GradleJar
import org.gradle.api.tasks.bundling.ZipEntryCompression

/**
 * A static BuildAction that does not capture the Task instance, enabling Configuration Cache
 * compatibility.
 *
 * This class is based on bundled code from the BND Gradle Plugin.
 * https://github.com/bndtools/bnd/blob/master/gradle-plugins/biz.aQute.bnd.gradle/src/main/java/aQute/bnd/gradle/BundleTaskExtension.java
 */
class BndBuildAction(
  private val properties: MapProperty<String, Any>,
  private val classpath: ConfigurableFileCollection,
  private val sourcepath: FileCollection,
  private val bundleSymbolicName: Provider<String>,
  private val bundleVersion: Provider<String>,
  private val bndfile: RegularFileProperty,
  private val bnd: Provider<String>,
  private val layout: ProjectLayout,
  private val entryCompression: ZipEntryCompression,
  private val preserveFileTimestamps: Boolean,
) : Action<Task> {
  constructor(
    extension: BundleTaskExtension,
    task: GradleJar,
    sourceSet: FileCollection,
  ) : this(
    extension.properties,
    extension.classpath,
    // Sourcepath default: all source
    sourceSet,
    // Symbolic name default logic
    task.archiveBaseName.zip(task.archiveClassifier) { baseName, classifier ->
      if (classifier.isNullOrEmpty()) baseName else "$baseName-$classifier"
    },
    task.archiveVersion.orElse("0").map { version ->
      MavenVersion.parseMavenString(version).osGiVersion.toString()
    },
    extension.bndfile,
    extension.bnd,
    task.project.layout,
    task.entryCompression,
    task.isPreserveFileTimestamps,
  )

  override fun execute(task: Task) {
    task as GradleJar
    val temporaryDir = task.temporaryDir
    val projectDir = layout.projectDirectory.asFile

    val gradleProperties = Properties()
    properties.get().forEach { (k, v) ->
      if (v is Provider<*>) {
        val value = v.getOrNull()
        if (value != null) gradleProperties[k] = value
      } else {
        gradleProperties[k] = v
      }
    }

    // Set default values if not present
    if (!gradleProperties.containsKey(Constants.BUNDLE_SYMBOLICNAME)) {
      gradleProperties[Constants.BUNDLE_SYMBOLICNAME] = bundleSymbolicName.get()
    }
    if (!gradleProperties.containsKey(Constants.BUNDLE_VERSION)) {
      gradleProperties[Constants.BUNDLE_VERSION] = bundleVersion.get()
    }

    // Do not capture 'task' in gradleProperties to avoid serialization issues

    try {
      Builder(Processor(gradleProperties, false)).use { builder ->
        val temporaryBndFile = File.createTempFile("bnd", ".bnd", temporaryDir)
        IO.writer(temporaryBndFile).use { writer ->
          val bndFileVal = bndfile.asFile.getOrNull()
          if (bndFileVal != null && bndFileVal.isFile) {
            Processor(gradleProperties).let { p ->
              p.loadProperties(bndFileVal).store(writer, null)
            }
          } else {
            val bndVal = bnd.getOrElse("")
            if (bndVal.isNotEmpty()) {
              val props = UTF8Properties()
              props.load(bndVal, File(projectDir, "build.gradle.kts"), builder)
              props.replaceHere(projectDir).store(writer, null)
            }
          }
        }
        builder.setProperties(temporaryBndFile, projectDir)

        builder.setProperty("project.output", temporaryDir.canonicalPath)

        if (builder.`is`(Constants.NOBUNDLES)) return

        val archiveFile = task.archiveFile.get().asFile
        val archiveFileName = task.archiveFileName.get()

        val archiveCopyFile = File(temporaryDir, archiveFileName)
        IO.copy(archiveFile, archiveCopyFile)

        val bundleJar = Jar(archiveFileName, archiveCopyFile)

        if (builder.getProperty(Constants.REPRODUCIBLE) == null && !preserveFileTimestamps) {
          builder.setProperty(Constants.REPRODUCIBLE, "true")
        }
        if (builder.getProperty(Constants.COMPRESSION) == null) {
          builder.setProperty(
            Constants.COMPRESSION,
            when (entryCompression) {
              ZipEntryCompression.STORED -> Jar.Compression.STORE.name
              else -> Jar.Compression.DEFLATE.name
            },
          )
        }

        bundleJar.updateModified(archiveFile.lastModified(), "time of Jar task generated jar")
        bundleJar.manifest = Manifest()
        builder.setJar(bundleJar)

        val validClasspath = classpath.filter { it.exists() && (it.isDirectory || isZip(it)) }
        builder.setProperty("project.buildpath", validClasspath.asPath)
        builder.setClasspath(validClasspath.files.toTypedArray())

        val validSourcepath = sourcepath.filter { it.exists() }
        builder.setProperty("project.sourcepath", validSourcepath.asPath)
        builder.setSourcepath(validSourcepath.files.toTypedArray())

        val builtJar = builder.build()
        if (!builder.isOk) {
          builder.getErrors().forEach { task.logger.error("Error: $it") }
          builder.getWarnings().forEach { task.logger.warn("Warning: $it") }
          throw GradleException("Bundle $archiveFileName has errors")
        }

        builtJar.write(archiveFile)
        archiveFile.setLastModified(System.currentTimeMillis())
      }
    } catch (e: Exception) {
      throw GradleException("Bnd build failed", e)
    }
  }

  private fun isZip(file: File): Boolean =
    try {
      ZipFile(file).close()
      true
    } catch (e: Exception) {
      false
    }

  companion object {
    /**
     * BND is incompatible with Kotlin/Multiplatform because it assumes the JVM source set's name is
     * 'main'. Work around this by creating a 'main' source set that forwards to 'jvmMain'.
     */
    fun installWorkaround(project: org.gradle.api.Project): org.gradle.api.tasks.SourceSet {
      val sourceSets =
        project.extensions
          .getByType(org.gradle.api.plugins.JavaPluginExtension::class.java)
          .sourceSets
      val existingMain = sourceSets.findByName("main")
      if (existingMain != null) return existingMain

      val jvmMainSourceSet = sourceSets.getByName("jvmMain")
      val mainSourceSet =
        object : org.gradle.api.tasks.SourceSet by jvmMainSourceSet {
          override fun getName() = "main"

          override fun getProcessResourcesTaskName() = "${jvmMainSourceSet.processResourcesTaskName}ForFakeMain"

          override fun getCompileJavaTaskName() = "${jvmMainSourceSet.compileJavaTaskName}ForFakeMain"

          override fun getClassesTaskName() = "${jvmMainSourceSet.classesTaskName}ForFakeMain"

          override fun getCompileOnlyConfigurationName(): String = jvmMainSourceSet.compileOnlyConfigurationName + "ForFakeMain"

          override fun getCompileClasspathConfigurationName(): String = jvmMainSourceSet.compileClasspathConfigurationName + "ForFakeMain"

          override fun getImplementationConfigurationName(): String = jvmMainSourceSet.implementationConfigurationName + "ForFakeMain"

          override fun getAnnotationProcessorConfigurationName(): String =
            jvmMainSourceSet.annotationProcessorConfigurationName + "ForFakeMain"

          override fun getRuntimeClasspathConfigurationName(): String = jvmMainSourceSet.runtimeClasspathConfigurationName + "ForFakeMain"

          override fun getRuntimeOnlyConfigurationName(): String = jvmMainSourceSet.runtimeOnlyConfigurationName + "ForFakeMain"

          override fun getTaskName(
            verb: String?,
            target: String?,
          ) = "${jvmMainSourceSet.getTaskName(verb, target)}ForFakeMain"
        }
      sourceSets.add(mainSourceSet)
      project.tasks.named { it.endsWith("ForFakeMain") }.configureEach { onlyIf { false } }

      return mainSourceSet
    }
  }
}
