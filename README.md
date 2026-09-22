OkHttp
======

See the [project website][okhttp] for documentation and APIs.

HTTP is the way modern applications network. It’s how we exchange data & media. Doing HTTP
efficiently makes your stuff load faster and saves bandwidth.

OkHttp is an HTTP client that’s efficient by default:

 * HTTP/2 support allows all requests to the same host to share a socket.
 * Connection pooling reduces request latency (if HTTP/2 isn’t available).
 * Transparent GZIP shrinks download sizes.
 * Response caching avoids the network completely for repeat requests.

OkHttp perseveres when the network is troublesome: it will silently recover from common connection
problems. If your service has multiple IP addresses, OkHttp will attempt alternate addresses if the
first connect fails. This is necessary for IPv4+IPv6 and services hosted in redundant data
centers. OkHttp supports modern TLS features (TLS 1.3, ALPN, certificate pinning). It can be
configured to fall back for broad connectivity.

Using OkHttp is easy. Its request/response API is designed with fluent builders and immutability. It
supports both synchronous blocking calls and async calls with callbacks.

A well behaved user agent
-------------------------

OkHttp follows modern HTTP specifications such as

* HTTP Semantics - [RFC 9110](https://datatracker.ietf.org/doc/html/rfc9110)
* HTTP Caching- [RFC 9111](https://datatracker.ietf.org/doc/html/rfc9111)
* HTTP/1.1 - [RFC 9112](https://datatracker.ietf.org/doc/html/rfc9112)
* HTTP/2 - [RFC 9113](https://datatracker.ietf.org/doc/html/rfc9113)
* Websockets - [RFC 6455](https://datatracker.ietf.org/doc/html/rfc6455)
* SSE - [Server-sent events](https://html.spec.whatwg.org/multipage/server-sent-events.html#server-sent-events)

Where the spec is ambiguous, OkHttp follows modern user agents such as popular Browsers or common HTTP Libraries.

OkHttp is principled and avoids being overly configurable, especially when such configuration is
to workaround a buggy server, test invalid scenarios or that contradict the relevant RFC.
Other HTTP libraries exist that fill that gap allowing extensive customisation including potentially
invalid requests.

Example Limitations

* Does not allow GET with a body.
* Cache is not an interface with alternative implementations.

Get a URL
---------

This program downloads a URL and prints its contents as a string. [Full source][get_example].

```java
OkHttpClient client = new OkHttpClient();

String run(String url) throws IOException {
  Request request = new Request.Builder()
      .url(url)
      .build();

  try (Response response = client.newCall(request).execute()) {
    return response.body().string();
  }
}
```


Post to a Server
----------------

This program posts data to a service. [Full source][post_example].

```java
public static final MediaType JSON = MediaType.get("application/json");

OkHttpClient client = new OkHttpClient();

String post(String url, String json) throws IOException {
  RequestBody body = RequestBody.create(json, JSON);
  Request request = new Request.Builder()
      .url(url)
      .post(body)
      .build();
  try (Response response = client.newCall(request).execute()) {
    return response.body().string();
  }
}
```

Further examples are on the [OkHttp Recipes page][recipes].


Requirements
------------

OkHttp works on Android 5.0+ (API level 21+) and Java 8+.

On Android, OkHttp uses [AndroidX Startup][androidx_startup]. If you disable the initializer in the manifest,
then apps are responsible for calling `OkHttp.initialize(applicationContext)` in `Application.onCreate`.

OkHttp depends on [Okio][okio] for high-performance I/O and the [Kotlin standard library][kotlin]. Both are small libraries with strong backward-compatibility.

We highly recommend you keep OkHttp up-to-date. As with auto-updating web browsers, staying current
with HTTPS clients is an important defense against potential security problems. [We
track][tls_history] the dynamic TLS ecosystem and adjust OkHttp to improve connectivity and
security.

OkHttp uses your platform's built-in TLS implementation. On Java platforms OkHttp also supports
[Conscrypt][conscrypt], which integrates [BoringSSL](https://github.com/google/boringssl) with Java. OkHttp will use Conscrypt if it is
the first security provider:

```java
Security.insertProviderAt(Conscrypt.newProvider(), 1);
```

The OkHttp `3.12.x` branch supports Android 2.3+ (API level 9+) and Java 7+. These platforms lack
support for TLS 1.2 and should not be used.


Releases
--------

Our [change log][changelog] has release history.

The latest release is available on [Maven Central](https://search.maven.org/artifact/com.squareup.okhttp3/okhttp/5.5.0/jar).

```kotlin
implementation("com.squareup.okhttp3:okhttp:5.5.0")
```

Snapshot builds are [available][snap]. [R8 and ProGuard][r8_proguard] rules are available.

Also, we have a [bill of materials (BOM)][bom] available to help you keep OkHttp artifacts up to date and be sure about version compatibility.

```kotlin
    dependencies {
       // define a BOM and its version
       implementation(platform("com.squareup.okhttp3:okhttp-bom:5.5.0"))

       // define any required OkHttp artifacts without version
       implementation("com.squareup.okhttp3:okhttp")
       implementation("com.squareup.okhttp3:logging-interceptor")
    }
```

Maven and JVM Projects
----------------------

OkHttp is published as a Kotlin Multiplatform project. While Gradle handles this automatically,
Maven projects must select between `okhttp-jvm` and `okhttp-android`. The `okhttp` artifact will be empty in
Maven projects.

```xml
<dependencyManagement>
  <dependencies>
    <dependency>
      <groupId>com.squareup.okhttp3</groupId>
      <artifactId>okhttp-bom</artifactId>
      <version>5.2.0</version>
      <type>pom</type>
      <scope>import</scope>
    </dependency>
  </dependencies>
</dependencyManagement>
```



```xml
<dependency>
  <groupId>com.squareup.okhttp3</groupId>
  <artifactId>okhttp-jvm</artifactId>
  <!-- Remove after OkHttp 5.2.0 with updated BOM. -->
  <version>5.1.0</version>
</dependency>

<dependency>
  <groupId>com.squareup.okhttp3</groupId>
  <artifactId>mockwebserver3</artifactId>
</dependency>

<dependency>
  <groupId>com.squareup.okhttp3</groupId>
  <artifactId>logging-interceptor</artifactId>
</dependency>
```

MockWebServer
-------------

OkHttp includes a library for testing HTTP, HTTPS, and HTTP/2 clients.

The latest release is available on [Maven Central](https://search.maven.org/artifact/com.squareup.okhttp3/mockwebserver/5.5.0/jar).

```kotlin
testImplementation("com.squareup.okhttp3:mockwebserver3:5.5.0")
```

MockWebServer is used for firstly for internal testing, and for basic testing of apps using OkHttp client.
It is not a full featured HTTP testing library that is developed standalone. It is not being actively developed
for new features. As such you might find your needs outgrow MockWebServer and you may which to use a
more full featured testing library such as [MockServer](https://www.mock-server.com/).

GraalVM Native Image
--------------------

Building your native images with [GraalVM] should work automatically.

See the okcurl module for an example build.

```shell
$ ./gradlew okcurl:nativeImage
$ ./okcurl/build/graal/okcurl https://httpbin.org/get
```

Java Modules
------------

OkHttp (5.2+) implements Java 9 Modules.

With this in place Java builds should fail if apps attempt to use internal packages.

```
error: package okhttp3.internal.platform is not visible
    okhttp3.internal.platform.Platform.get();
                    ^
  (package okhttp3.internal.platform is declared in module okhttp3,
    which does not export it to module com.bigco.sdk)
```

The stable public API is based on the list of defined modules:

- okhttp3
- okhttp3.brotli
- okhttp3.coroutines
- okhttp3.dnsoverhttps
- okhttp3.java.net.cookiejar
- okhttp3.logging
- okhttp3.sse
- okhttp3.tls
- okhttp3.urlconnection
- mockwebserver3
- mockwebserver3.junit4
- mockwebserver3.junit5

License
-------

```
Copyright 2019 Square, Inc.

Licensed under the Apache License, Version 2.0 (the "License");
you may not use this file except in compliance with the License.
You may obtain a copy of the License at

   http://www.apache.org/licenses/LICENSE-2.0

Unless required by applicable law or agreed to in writing, software
distributed under the License is distributed on an "AS IS" BASIS,
WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
See the License for the specific language governing permissions and
limitations under the License.
```

 [GraalVM]: https://www.graalvm.org/
 [androidx_startup]: https://developer.android.com/jetpack/androidx/releases/startup
 [bom]: https://docs.gradle.org/6.2/userguide/platforms.html#sub:bom_import
 [changelog]: https://lysine.dev/okhttp/changelog/
 [conscrypt]: https://github.com/google/conscrypt/
 [get_example]: https://raw.github.com/lysine-dev/okhttp/master/samples/guide/src/main/java/okhttp3/guide/GetExample.java
 [kotlin]: https://kotlinlang.org/
 [okhttp3_pro]: https://raw.githubusercontent.com/lysine-dev/okhttp/master/okhttp/okhttp3.pro
 [okhttp]: https://lysine.dev/okhttp/
 [okhttp_312x]: https://github.com/lysine-dev/okhttp/tree/okhttp_3.12.x
 [okio]: https://github.com/lysine-dev/okio
 [post_example]: https://raw.github.com/lysine-dev/okhttp/master/samples/guide/src/main/java/okhttp3/guide/PostExample.java
 [r8_proguard]: https://lysine.dev/okhttp/features/r8_proguard/
 [recipes]: https://lysine.dev/okhttp/recipes/
 [snap]: https://s01.oss.sonatype.org/content/repositories/snapshots/
 [tls_history]: https://lysine.dev/okhttp/tls_configuration_history/
