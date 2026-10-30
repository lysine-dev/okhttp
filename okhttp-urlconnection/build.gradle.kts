plugins {
  kotlin("jvm")
  id("okhttp.publish-conventions")
  id("okhttp.jvm-conventions")
  id("okhttp.quality-conventions")
  id("okhttp.testing-conventions")
}

project.applyOsgi(
  $$"Fragment-Host: com.squareup.okhttp3; bundle-version=\"${range;[==,+);${version_cleanup;$${project.version}}}\"",
  "Bundle-SymbolicName: com.squareup.okhttp3.urlconnection",
  "-removeheaders: Private-Package",
)

project.applyJavaModules("okhttp3.urlconnection")

dependencies {
  "friendsApi"(projects.okhttp)
  api(projects.okhttpJavaNetCookiejar)
  compileOnly(libs.animalsniffer.annotations)
}
