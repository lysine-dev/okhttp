import org.gradle.internal.os.OperatingSystem
plugins {
  kotlin("jvm")
  id("okhttp.jvm-conventions")
  id("okhttp.quality-conventions")
  id("okhttp.testing-conventions")
}

dependencies {
  api(libs.square.okio)
  api(projects.mockwebserver3)
  "friendsApi"(projects.okhttp)
  "friendsApi"(projects.okhttpDnsoverhttps)
  api(projects.okhttpTls)
  api(libs.assertk)
  api(platform(libs.bouncycastle.bom))
  api(libs.bouncycastle.bcprov)
  implementation(libs.bouncycastle.bcpkix)
  implementation(libs.bouncycastle.bcutil)
  implementation(libs.bouncycastle.bctls)
  api(libs.conscrypt.openjdk)
  api(libs.openjsse)

  api(libs.junit.jupiter.engine)

  // This runs Corretto on macOS (aarch64) and Linux (x86_64). We don't test Corretto on other
  // operating systems or architectures.
  api(
    variantOf(libs.amazon.corretto) {
      classifier(
        when {
          OperatingSystem.current().isMacOsX -> "osx-aarch_64"
          OperatingSystem.current().isLinux -> "linux-x86_64"
          else -> "linux-x86_64" // Code that references Corretto will build but not run.
        },
      )
    },
  )

  api(libs.hamcrest.library)
  api(libs.junit.jupiter.api)
  api(libs.junit.jupiter.params)

  api(libs.junit.pioneer)

  compileOnly(libs.robolectric.android)

  testImplementation(libs.kotlin.test.common)
  testImplementation(libs.kotlin.test.junit)
}

animalsniffer {
  isIgnoreFailures = true
}
