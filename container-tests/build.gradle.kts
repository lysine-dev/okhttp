import org.gradle.language.base.plugins.LifecycleBasePlugin

plugins {
  kotlin("jvm")
  id("okhttp.base-conventions")
}

import okhttp3.buildsupport.platform
import okhttp3.buildsupport.testJavaVersion

val platform = project.platform
val testJavaVersion = project.testJavaVersion

tasks.withType<Test> {
  useJUnitPlatform()
  val isCi = providers.environmentVariable("CI")
  val containerTests = providers.gradleProperty("containerTests")
  onlyIf("By default not in CI") {
    !isCi.isPresent
      || (containerTests.isPresent && containerTests.get().toBoolean())
  }

  jvmArgs(
    "-Dokhttp.platform=$platform",
  )

  if (platform == "loom") {
    jvmArgs(
      "-Djdk.tracePinnedThreads=short",
    )
  }

  val javaToolchains = project.extensions.getByType<JavaToolchainService>()
  javaLauncher.set(javaToolchains.launcherFor {
    languageVersion.set(JavaLanguageVersion.of(testJavaVersion))
  })
}

dependencies {
  api(projects.okhttp)

  testImplementation(projects.okhttpTestingSupport)
  testImplementation(libs.junit.jupiter.api)
  testImplementation(libs.junit.jupiter.params)
  testImplementation(libs.junit.jupiter.engine)
  testImplementation(libs.assertk)
  testImplementation(libs.testcontainers)
  testImplementation(libs.mockserver)
  testImplementation(libs.mockserver.client)
  testImplementation(libs.testcontainers.junit5)
}

tasks.register<JavaExec>("runAndroidTestService") {
  group = LifecycleBasePlugin.VERIFICATION_GROUP
  description = "Starts the Testcontainers service used by Android instrumentation tests."
  classpath = sourceSets["test"].runtimeClasspath
  mainClass = "okhttp3.containers.AndroidTestService"
}

tasks.register<JavaExec>("runAndroidEchTestService") {
  group = LifecycleBasePlugin.VERIFICATION_GROUP
  description = "Starts the DoH and HTTPS containers used by the Android ECH test."
  classpath = sourceSets["test"].runtimeClasspath
  mainClass = "okhttp3.containers.AndroidEchTestService"
}
