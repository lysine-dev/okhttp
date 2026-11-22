plugins {
  kotlin("jvm")
  id("okhttp.publish-conventions")
  id("okhttp.jvm-conventions")
  id("okhttp.quality-conventions")
  id("okhttp.testing-conventions")
}

project.applyOsgi(
  "Export-Package: okhttp3.java.net.cookiejar",
  "Bundle-SymbolicName: com.squareup.okhttp3.java.net.cookiejar",
)

project.applyJavaModules("okhttp3.java.net.cookiejar")

dependencies {
  "friendsApi"(projects.okhttp)
  compileOnly(libs.animalsniffer.annotations)
}
