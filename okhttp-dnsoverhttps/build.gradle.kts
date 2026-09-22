plugins {
  kotlin("jvm")
  id("okhttp.publish-conventions")
  id("okhttp.jvm-conventions")
  id("okhttp.quality-conventions")
  id("okhttp.testing-conventions")
  id("app.cash.burst")
}

project.applyOsgi(
  "Export-Package: okhttp3.dnsoverhttps",
  "Bundle-SymbolicName: com.squareup.okhttp3.dnsoverhttps",
)

project.applyJavaModules("okhttp3.dnsoverhttps")

dependencies {
  "friendsApi"(projects.okhttp)

  testImplementation(projects.okhttpTestingSupport)
  testImplementation(projects.mockwebserver)
  testImplementation(projects.mockwebserver3Junit5)
  testImplementation(libs.square.okio.fakefilesystem)
  testImplementation(libs.conscrypt.openjdk)
  testImplementation(libs.junit)
  testImplementation(libs.kotlin.test.common)
  testImplementation(libs.kotlin.test.junit)
}
