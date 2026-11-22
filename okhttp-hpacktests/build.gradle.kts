plugins {
  kotlin("jvm")
  id("okhttp.jvm-conventions")
  id("okhttp.quality-conventions")
  id("okhttp.testing-conventions")
}

dependencies {
  testImplementation(libs.square.okio)
  testImplementation(libs.square.moshi)
  testImplementation(libs.square.moshi.kotlin)
  testImplementation(projects.okhttp)
  testImplementation(projects.okhttpTestingSupport)
  testImplementation(projects.mockwebserver)
  testImplementation(libs.junit)
}
