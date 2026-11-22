plugins {
  kotlin("jvm")
  id("okhttp.publish-conventions")
  id("okhttp.jvm-conventions")
  id("okhttp.quality-conventions")
  id("okhttp.testing-conventions")
}

project.applyJavaModules("mockwebserver3.junit4")

dependencies {
  api(projects.okhttp)
  api(projects.mockwebserver3)
  api(libs.junit)

  testImplementation(libs.assertk)
  testImplementation(libs.junit.vintage.engine)
}
