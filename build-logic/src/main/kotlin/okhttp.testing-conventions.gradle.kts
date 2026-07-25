import org.gradle.api.tasks.testing.logging.TestExceptionFormat
import org.gradle.api.artifacts.VersionCatalogsExtension
import okhttp3.buildsupport.platform
import okhttp3.buildsupport.testJavaVersion

plugins {
  id("okhttp.base-conventions")
}

val libs = extensions.getByType<VersionCatalogsExtension>().named("libs")

fun library(alias: String) = libs.findLibrary(alias).get().get().let {
  "${it.module.group}:${it.module.name}:${it.versionConstraint.requiredVersion}"
}


val testRuntimeOnly = configurations.maybeCreate("testRuntimeOnly")
dependencies {
  testRuntimeOnly(library("junit-jupiter-engine"))
  testRuntimeOnly(library("junit-vintage-engine"))
  testRuntimeOnly(library("junit-platform-launcher"))
}

tasks.withType<Test> {
  useJUnitPlatform()
  jvmArgs("-Dokhttp.platform=$platform")

  if (platform == "loom") {
    jvmArgs("-Djdk.tracePinnedThreads=short")
  }
  if (platform == "openjsse") {
    if (testJavaVersion > 8) {
      throw GradleException("OpenJSSE is only supported on Java 8")
    }
  }

  val javaToolchains = project.extensions.getByType<JavaToolchainService>()
  javaLauncher.set(javaToolchains.launcherFor {
    languageVersion.set(JavaLanguageVersion.of(testJavaVersion))
  })

  maxParallelForks = Runtime.getRuntime().availableProcessors() * 2
  testLogging {
    exceptionFormat = TestExceptionFormat.FULL
  }

  systemProperty("okhttp.platform", platform)
  systemProperty("junit.jupiter.extensions.autodetection.enabled", "true")

  if (testJavaVersion < 21) {
    // Robolectric needs Java 21 to sandbox Android SDK 37. Tests configured only for SDKs
    // outside this set are skipped rather than failing to create a sandbox.
    systemProperty("robolectric.enabledSdks", (21..36).joinToString(","))
  }
}

tasks.withType<Test>().configureEach {
  environment("OKHTTP_ROOT", rootDir)
}

plugins.withId("org.jetbrains.kotlin.jvm") {
  val test = tasks.named("test")
  tasks.register("jvmTest") {
    description = "Get 'gradlew jvmTest' to run the tests of JVM-only modules"
    dependsOn(test)
  }
}
