plugins {
  kotlin("jvm")
  id("okhttp.jvm-conventions")
  id("okhttp.quality-conventions")
  id("okhttp.testing-conventions")
}

dependencies {
  testImplementation(projects.okhttp)
  testImplementation(projects.mockwebserver3)
  testImplementation(projects.mockwebserver3Junit5)
  testImplementation(projects.okhttpTls)
  testImplementation(projects.okhttpTestingSupport)
  testImplementation(libs.http.client5)
  testImplementation(libs.jetty.client)
  testImplementation(libs.junit)
  testImplementation(libs.assertk)
}

tasks.compileJava {
  options.isWarnings = false
}
