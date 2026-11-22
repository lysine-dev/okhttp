plugins {
  kotlin("jvm")
  id("okhttp.publish-conventions")
  id("okhttp.jvm-conventions")
  id("okhttp.quality-conventions")
  id("okhttp.testing-conventions")
}

project.applyOsgi(
  "Export-Package: okhttp3.brotli",
  "Bundle-SymbolicName: com.squareup.okhttp3.brotli",
)

project.applyJavaModules("okhttp3.brotli")

dependencies {
  "friendsApi"(projects.okhttp)
  api(libs.brotli.dec)

  testImplementation(projects.okhttpTestingSupport)
  testImplementation(libs.conscrypt.openjdk)
  testImplementation(libs.junit)
  testImplementation(libs.kotlin.test.common)
  testImplementation(libs.kotlin.test.junit)
  testImplementation(libs.assertk)
}
