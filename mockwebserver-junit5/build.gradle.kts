plugins {
  kotlin("jvm")
  id("okhttp.publish-conventions")
  id("okhttp.jvm-conventions")
  id("okhttp.quality-conventions")
  id("okhttp.testing-conventions")
}

project.applyJavaModules("mockwebserver3.junit5")

dependencies {
  "friendsApi"(projects.okhttp)
  api(projects.mockwebserver3)
  api(libs.junit.jupiter.api)
  compileOnly(libs.animalsniffer.annotations)

  testRuntimeOnly(libs.junit.jupiter.engine)
  testImplementation(libs.kotlin.junit5)
  testImplementation(projects.okhttpTestingSupport)
  testImplementation(libs.assertk)
}
