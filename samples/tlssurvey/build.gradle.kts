plugins {
  kotlin("jvm")
  id("okhttp.jvm-conventions")
  id("okhttp.quality-conventions")
  id("okhttp.testing-conventions")
  application
  id("com.google.devtools.ksp")
}

application {
  mainClass.set("okhttp3.survey.RunSurveyKt")
}

dependencies {
  implementation(projects.okhttp)
  implementation(projects.okhttpCoroutines)
  implementation(libs.conscrypt.openjdk)

  implementation(libs.square.retrofit)
  implementation(libs.square.retrofit.converter.moshi)
  implementation(libs.square.moshi)
  implementation(libs.square.moshi.kotlin)

  ksp(libs.square.moshi.compiler)
}

tasks.compileJava {
  options.isWarnings = false
}
