import kotlinx.validation.ApiValidationExtension
import okhttp3.buildsupport.testJavaVersion
import org.graalvm.buildtools.gradle.dsl.GraalVMExtension
import org.graalvm.buildtools.gradle.tasks.BuildNativeImageTask
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile
import ru.vyarus.gradle.plugin.animalsniffer.AnimalSnifferExtension
import java.nio.file.Files

plugins {
  kotlin("jvm")
  id("okhttp.publish-conventions")
  id("okhttp.jvm-conventions")
  id("okhttp.quality-conventions")
  id("okhttp.testing-conventions")
  id("com.gradleup.shadow")
}

tasks.withType<KotlinCompile> {
  compilerOptions {
    jvmTarget.set(JvmTarget.JVM_17)
  }
}

tasks.withType<JavaCompile> {
  sourceCompatibility = JvmTarget.JVM_17.target
  targetCompatibility = JvmTarget.JVM_17.target
}

val copyResourcesTemplates =
  tasks.register<Copy>("copyResourcesTemplates") {
    from("src/main/resources-templates")
    into(layout.buildDirectory.dir("generated/resources-templates"))
    expand("projectVersion" to "${project.version}")
    filteringCharset = Charsets.UTF_8.toString()
  }

configure<JavaPluginExtension> {
  sourceSets.getByName("main").resources.srcDir(copyResourcesTemplates.get().outputs)
}

dependencies {
  api(projects.okhttp)
  api(projects.loggingInterceptor)
  api(libs.square.okio)
  implementation(libs.clikt)

  testImplementation(projects.okhttpTestingSupport)
  testImplementation(projects.mockwebserver3)
  testImplementation(projects.mockwebserver3Junit5)
  testImplementation(libs.junit)
  testImplementation(libs.assertk)
  testImplementation(kotlin("test"))
}

val jvmSignature = configurations.getByName("jvmSignature")
configure<AnimalSnifferExtension> {
  // Only check JVM
  signatures = jvmSignature
}

configure<ApiValidationExtension> {
  validationDisabled = true
}

tasks.jar {
  manifest {
    attributes("Main-Class" to "okhttp3.curl.MainCommandLineKt")
  }
}

tasks.shadowJar {
  mergeServiceFiles()
}

tasks.withType<Test> {
  val javaVersion = project.testJavaVersion
  onlyIf("native build requires Java 17") {
    javaVersion > 17
  }
}

apply(plugin = "org.graalvm.buildtools.native")

configure<GraalVMExtension> {
  binaries {
    named("main") {
      imageName = "okcurl"
      mainClass = "okhttp3.curl.MainCommandLineKt"
      if (System.getProperty("os.name").lowercase().contains("windows")) {
        // windows requires a slightly different approach for some things
      } else {
        buildArgs("--no-fallback")
      }

      javaLauncher.set(
        javaToolchains.launcherFor {
          languageVersion.set(JavaLanguageVersion.of(25))
          // Match any native-image capable JDK rather than JvmVendorSpec.GRAAL_VM
          nativeImageCapable.set(true)
        },
      )
    }
  }
}

// https://github.com/gradle/gradle/issues/28583
tasks.named<BuildNativeImageTask>("nativeCompile") {
  // Gradle's "Copy" task cannot handle symbolic links, see https://github.com/gradle/gradle/issues/3982. That is why
  // links contained in the GraalVM distribution archive get broken during provisioning and are replaced by empty
  // files. Address this by recreating the links in the toolchain directory.
  val toolchainDir =
    options.get().javaLauncher.get().executablePath.asFile.parentFile.run {
      if (name == "bin") parentFile else this
    }

  val toolchainFiles = toolchainDir.walkTopDown().filter { it.isFile }
  val emptyFiles = toolchainFiles.filter { it.length() == 0L }

  // Find empty toolchain files that are named like other toolchain files and assume these should have been links.
  val links =
    toolchainFiles.mapNotNull { file ->
      emptyFiles.singleOrNull { it != file && it.name == file.name }?.let {
        file to it
      }
    }

  // Fix up symbolic links.
  links.forEach { (target, link) ->
    logger.quiet("Fixing up '$link' to link to '$target'.")

    if (link.delete()) {
      Files.createSymbolicLink(link.toPath(), target.toPath())
    } else {
      logger.warn("Unable to delete '$link'.")
    }
  }
}
