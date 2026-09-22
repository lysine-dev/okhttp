OkHttp DNS over HTTPS Implementation
====================================

This module is an implementation of [DNS over HTTPS][1] using OkHttp.

### Download

```kotlin
testImplementation("com.squareup.okhttp3:okhttp-dnsoverhttps:5.5.0")
```

### Usage

```
  val appCache = Cache(File("cacheDir", "okhttpcache"), 10 * 1024 * 1024)
  val bootstrapClient = OkHttpClient.Builder().cache(appCache).build()

  val dns = DnsOverHttps.Builder().client(bootstrapClient)
    .url("https://dns.google/dns-query".toHttpUrl())
    .bootstrapDnsHosts(InetAddress.getByName("8.8.4.4"), InetAddress.getByName("8.8.8.8"))
    .build()

  val client = bootstrapClient.newBuilder().dns(dns).build()
```


[1]: https://en.wikipedia.org/wiki/DNS_over_HTTPS
