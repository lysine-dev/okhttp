plugins {
  kotlin("jvm")
  id("okhttp.publish-conventions")
  id("okhttp.jvm-conventions")
  id("okhttp.quality-conventions")
  id("okhttp.testing-conventions")
}

project.applyOsgi(
  "Export-Package: okhttp3.coroutines",
  "Bundle-SymbolicName: com.squareup.okhttp3.coroutines",
)

project.applyJavaModules("okhttp3.coroutines")

dependencies {
  api(projects.okhttp)
  implementation(libs.kotlinx.coroutines.core)
  api(libs.square.okio)
  api(libs.kotlin.stdlib)

  testImplementation(libs.kotlin.test.annotations)
  testImplementation(libs.kotlin.test.common)
  testImplementation(libs.kotlin.test.junit)
  testApi(libs.assertk)
  testImplementation(projects.okhttpTestingSupport)
  testImplementation(libs.kotlinx.coroutines.test)
  testImplementation(projects.mockwebserver3Junit5)
}
