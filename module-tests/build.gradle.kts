import okhttp3.buildsupport.testJavaVersion

plugins {
  id("okhttp.base-conventions")
  id("java")
  id("application")
  alias(libs.plugins.jlink)
  alias(libs.plugins.extra.java.module.info)
}

dependencies {
  implementation(projects.okhttp)
  implementation(projects.loggingInterceptor)

  // Force version 26.0.2-1 which is a proper JPMS module, unlike transitive 13.0
  implementation(libs.jetbrains.annotations)

  testImplementation(projects.okhttp)
  testImplementation(projects.loggingInterceptor)
  testImplementation(projects.mockwebserver3)
  testImplementation(projects.mockwebserver3Junit5)

  testImplementation(libs.junit.jupiter.api)
  testRuntimeOnly(libs.junit.jupiter.engine)
  testRuntimeOnly(libs.junit.platform.launcher)
}

application {
  mainClass = "okhttp3.modules.Main"
  mainModule = "okhttp3.modules"
}

jlinkApplication {
  stripDebug = true
  stripJavaDebugAttributes = true
  compress.set("zip-9")
  addModules.addAll("jdk.crypto.ec", "java.logging")
  vm.set("server")
}

extraJavaModuleInfo {
  module("com.squareup.okio:okio-jvm", "okio") {
    exportAllPackages()
    requires("kotlin.stdlib")
    requires("java.logging")
  }
  module("com.squareup.okio:okio", "okio") {
    exportAllPackages()
  }
}

// Exclude dokka from all configurations
// to attempt to avoid https://github.com/gradlex-org/extra-java-module-info/issues/221
configurations.configureEach {
  exclude(group = "org.jetbrains.dokka")
}


val testJavaVersion = project.testJavaVersion

tasks.withType<Test>().configureEach {
  useJUnitPlatform()

  enabled = testJavaVersion > 8

  javaLauncher.set(javaToolchains.launcherFor {
    languageVersion.set(JavaLanguageVersion.of(testJavaVersion))
  })
}

java {
  sourceCompatibility = JavaVersion.VERSION_11
  targetCompatibility = JavaVersion.VERSION_11
  toolchain {
    languageVersion.set(JavaLanguageVersion.of(21))
  }
}
