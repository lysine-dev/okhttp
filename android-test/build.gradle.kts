import de.mannodermaus.gradle.plugins.junit5.dsl.UnsupportedDeviceBehavior
import okhttp3.buildsupport.androidBuild

plugins {
  id("okhttp.base-conventions")
  id("com.android.library")
  id("de.mannodermaus.android-junit5")
}

android {
  compileSdk {
    version = release(37)
  }

  namespace = "okhttp.android.test"

  defaultConfig {
    minSdk = 21

    // Make sure to use the AndroidJUnitRunner (or a sub-class) in order to hook in the JUnit 5 Test Builder
    testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    testInstrumentationRunnerArguments += mapOf(
      "runnerBuilder" to "de.mannodermaus.junit5.AndroidJUnit5Builder",
      "notPackage" to "org.bouncycastle",
      "configurationParameters" to "junit.jupiter.extensions.autodetection.enabled=true"
    )
  }

  if (androidBuild) {
    sourceSets["androidTest"].java.srcDirs(
      "../okhttp-brotli/src/test/java",
      "../okhttp-dnsoverhttps/src/test/java",
      "../okhttp-logging-interceptor/src/test/java",
      "../okhttp-sse/src/test/java"
    )
  }

  compileOptions {
    targetCompatibility(JavaVersion.VERSION_11)
    sourceCompatibility(JavaVersion.VERSION_11)
  }

  testOptions {
    targetSdk = 37
    unitTests.isIncludeAndroidResources = true

    // Robolectric 4.17 reflects into JDK internals, which JDK 17+ blocks by default.
    // https://robolectric.org/getting-started/
    unitTests.all {
      it.jvmArgs(
        "--add-opens=java.base/java.lang=ALL-UNNAMED",
        "--add-opens=java.base/java.util=ALL-UNNAMED",
        "--add-opens=java.base/java.io=ALL-UNNAMED",
        "--add-opens=java.base/java.net=ALL-UNNAMED",
        "--add-opens=java.base/java.security=ALL-UNNAMED",
        "--add-opens=java.base/java.text=ALL-UNNAMED",
        "--add-opens=java.base/jdk.internal.access=ALL-UNNAMED",
        "--add-opens=java.desktop/java.awt.font=ALL-UNNAMED",
        "--add-opens=jdk.compiler/com.sun.tools.javac.api=ALL-UNNAMED",
      )
    }
  }


  // issue merging due to conflict with httpclient and something else
  packagingOptions.resources.excludes += setOf(
    "META-INF/DEPENDENCIES",
    "META-INF/LICENSE.md",
    "META-INF/LICENSE-notice.md",
    "README.txt",
    "org/bouncycastle/LICENSE",
    "META-INF/versions/9/OSGI-INF/MANIFEST.MF"
  )
}

dependencies {
  implementation(libs.kotlin.reflect)
  implementation(libs.playservices.safetynet)
  "friendsImplementation"(projects.okhttp)
  "friendsImplementation"(projects.okhttpDnsoverhttps)

  testImplementation(projects.okhttp)
  testImplementation(libs.junit)
  testImplementation(libs.junit.ktx)
  testImplementation(libs.assertk)
  testImplementation(projects.okhttpTls)
  "friendsTestImplementation"(projects.loggingInterceptor)
  testImplementation(libs.androidx.test.runner)
  testImplementation(libs.robolectric)
  testImplementation(libs.androidx.espresso.core)
  testImplementation(libs.square.okio.fakefilesystem)
  testImplementation(projects.okhttpTestingSupport)
  testImplementation(libs.conscrypt.openjdk)
  testImplementation(libs.junit.jupiter.engine)
  testImplementation(libs.junit.vintage.engine)

  androidTestImplementation(projects.okhttpTestingSupport) {
    exclude("org.openjsse", "openjsse")
    exclude("org.conscrypt", "conscrypt-openjdk-uber")
    exclude("software.amazon.cryptools", "AmazonCorrettoCryptoProvider")
  }
  androidTestImplementation(libs.assertk)
  androidTestImplementation(libs.bouncycastle.bcprov)
  androidTestImplementation(libs.bouncycastle.bctls)
  androidTestImplementation(libs.conscrypt.android)
  androidTestImplementation(projects.mockwebserver3Junit4)
  androidTestImplementation(projects.mockwebserver3Junit5)
  androidTestImplementation(projects.okhttpBrotli)
  androidTestImplementation(projects.okhttpZstd)
  androidTestImplementation(projects.okhttpDnsoverhttps)
  androidTestImplementation(projects.loggingInterceptor)
  androidTestImplementation(projects.okhttpSse)
  androidTestImplementation(projects.okhttpTls)
  androidTestImplementation(libs.androidx.junit)
  androidTestImplementation(libs.androidx.espresso.core)
  androidTestImplementation(libs.http.client5)
  androidTestImplementation(libs.kotlin.test.common)
  androidTestImplementation(libs.kotlin.test.junit)
  androidTestImplementation(libs.square.moshi)
  androidTestImplementation(libs.square.moshi.kotlin)
  androidTestImplementation(libs.square.okio.fakefilesystem)

  androidTestImplementation(libs.androidx.test.runner)
  androidTestImplementation(libs.junit.jupiter.api)
  androidTestImplementation(libs.junit5android.core)
  androidTestRuntimeOnly(libs.junit5android.runner)
}

junitPlatform {
  instrumentationTests.behaviorForUnsupportedDevices = UnsupportedDeviceBehavior.Skip
  filters {
    excludeTags("Remote")
  }
}
