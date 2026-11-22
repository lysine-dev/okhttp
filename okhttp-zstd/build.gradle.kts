plugins {
  kotlin("jvm")
  id("okhttp.publish-conventions")
  id("okhttp.jvm-conventions")
  id("okhttp.quality-conventions")
  id("okhttp.testing-conventions")
}

project.applyOsgi(
  "Export-Package: okhttp3.zstd",
  "Automatic-Module-Name: okhttp3.zstd",
  "Bundle-SymbolicName: com.squareup.okhttp3.zstd",
)

dependencies {
  "friendsApi"(projects.okhttp)
  implementation(libs.square.zstd.kmp.okio)

  testImplementation(projects.okhttpBrotli)
  testImplementation(projects.okhttpTestingSupport)
  testImplementation(libs.kotlin.test.common)
  testImplementation(libs.kotlin.test.junit)
  testImplementation(libs.assertk)
}
