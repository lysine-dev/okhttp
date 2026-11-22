val okhttpDokka: String? by project
if (okhttpDokka?.toBoolean() == true) {
  apply(plugin = "org.jetbrains.dokka")

  dependencies {
    add("dokka", project(":okhttp"))
    add("dokka", project(":okhttp-brotli"))
    add("dokka", project(":okhttp-coroutines"))
    add("dokka", project(":okhttp-dnsoverhttps"))
    add("dokka", project(":okhttp-java-net-cookiejar"))
    add("dokka", project(":logging-interceptor"))
    add("dokka", project(":okhttp-sse"))
    add("dokka", project(":okhttp-tls"))
    add("dokka", project(":okhttp-urlconnection"))
    add("dokka", project(":okhttp-zstd"))
    add("dokka", project(":mockwebserver"))
    add("dokka", project(":mockwebserver3"))
    add("dokka", project(":mockwebserver3-junit4"))
    add("dokka", project(":mockwebserver3-junit5"))
  }
}
