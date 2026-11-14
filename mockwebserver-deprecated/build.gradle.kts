plugins {
  kotlin("jvm")
  id("okhttp.publish-conventions")
  id("okhttp.jvm-conventions")
  id("okhttp.quality-conventions")
  id("okhttp.testing-conventions")
}

project.applyJavaModules("okhttp3.mockwebserver")

dependencies {
  "friendsApi"(projects.okhttp)
  api(projects.mockwebserver3)
  api(libs.junit)
  api(libs.square.okio)

  testImplementation(projects.okhttpTestingSupport)
  testImplementation(projects.okhttpTls)
  testImplementation(libs.kotlin.test.common)
  testImplementation(libs.kotlin.test.junit)
}
