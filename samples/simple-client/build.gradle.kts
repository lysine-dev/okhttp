plugins {
  kotlin("jvm")
  id("okhttp.jvm-conventions")
  id("okhttp.quality-conventions")
  id("okhttp.testing-conventions")
}

dependencies {
  implementation(projects.okhttp)
  implementation(libs.square.moshi)
}
