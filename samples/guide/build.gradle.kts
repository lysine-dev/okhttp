plugins {
  kotlin("jvm")
  id("okhttp.jvm-conventions")
  id("okhttp.quality-conventions")
  id("okhttp.testing-conventions")
  id("com.google.devtools.ksp")
}

dependencies {
  "friendsImplementation"(projects.okhttp)
  implementation(projects.mockwebserver)
  implementation(projects.okhttpTestingSupport)
  implementation(projects.okhttpTls)
  implementation(libs.animalsniffer.annotations)
  implementation(libs.square.moshi)
  implementation(libs.square.okio.fakefilesystem)
  ksp(libs.square.moshi.compiler)
}

tasks.compileJava {
  options.isWarnings = false
}
