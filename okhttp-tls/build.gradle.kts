plugins {
  kotlin("jvm")
  id("okhttp.publish-conventions")
  id("okhttp.jvm-conventions")
  id("okhttp.quality-conventions")
  id("okhttp.testing-conventions")
}

project.applyOsgi(
  "Export-Package: okhttp3.tls",
  "Bundle-SymbolicName: com.squareup.okhttp3.tls",
)

project.applyJavaModules("okhttp3.tls")

dependencies {
  api(libs.square.okio)
  "friendsImplementation"(projects.okhttp)
  compileOnly(libs.animalsniffer.annotations)

  testImplementation(projects.okhttpTestingSupport)
  testImplementation(projects.mockwebserver3Junit5)
  testImplementation(libs.junit)
  testImplementation(libs.kotlin.test.common)
  testImplementation(libs.kotlin.test.junit)
  testImplementation(libs.assertk)
}

animalsniffer {
  // InsecureExtendedTrustManager (API 24+)
  ignore("javax.net.ssl.X509ExtendedTrustManager")
}
