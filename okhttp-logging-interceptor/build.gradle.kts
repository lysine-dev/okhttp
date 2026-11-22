plugins {
  kotlin("jvm")
  id("okhttp.publish-conventions")
  id("okhttp.jvm-conventions")
  id("okhttp.quality-conventions")
  id("okhttp.testing-conventions")
}

project.applyOsgi(
  "Export-Package: okhttp3.logging",
  "Bundle-SymbolicName: com.squareup.okhttp3.logging",
)

project.applyJavaModules("okhttp3.logging")

dependencies {
  "friendsApi"(projects.okhttp)

  testImplementation(libs.junit)
  testImplementation(projects.mockwebserver3)
  testImplementation(projects.mockwebserver3Junit5)
  testImplementation(projects.okhttpTestingSupport)
  testImplementation(projects.okhttpTls)
  testImplementation(libs.assertk)
}
