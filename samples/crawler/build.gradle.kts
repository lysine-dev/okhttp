plugins {
  kotlin("jvm")
  id("okhttp.jvm-conventions")
  id("okhttp.quality-conventions")
  id("okhttp.testing-conventions")
  application
}

application {
  mainClass.set("okhttp3.sample.Crawler")
}

dependencies {
  implementation(projects.okhttp)
  implementation(libs.jsoup)
}

tasks.compileJava {
  options.isWarnings = false
}
