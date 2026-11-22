plugins {
  kotlin("jvm")
  id("okhttp.jvm-conventions")
  id("okhttp.quality-conventions")
  id("okhttp.testing-conventions")
  id("ru.vyarus.animalsniffer")
}

dependencies {
  api(libs.square.okio)
  api(libs.square.kotlin.poet)
  testImplementation(libs.assertk)
  testImplementation(libs.junit.jupiter.api)
  testImplementation(libs.junit.jupiter.params)

  testImplementation(libs.junit.jupiter.engine)
}

animalsniffer {
  isIgnoreFailures = true
}
