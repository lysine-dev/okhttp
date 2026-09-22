OkHttp 4.x Change Log
=====================

## Version 4.12.0

_2023-10-16_

 *  Fix: Don't hang taking headers for HTTP 103 responses.

 *  Fix: Recover gracefully when a cache entry's certificate is corrupted.

 *  Fix: Fail permanently when there's a failure loading the bundled public suffix database.
    This is the dataset that powers `HttpUrl.topPrivateDomain()`.

 *  Fix: Immediately update the connection's flow control window instead of waiting for the
    receiving stream to process it.

    This change may increase OkHttp's memory use for applications that make many concurrent HTTP
    calls and that can receive data faster than they can process it. Previously, OkHttp limited
    HTTP/2 to 16 MiB of unacknowledged data per connection. With this fix there is a limit of 16 MiB
    of unacknowledged data per stream and no per-connection limit.

 *  Fix: Don't operate on a connection after it's been returned to the pool. This race occurred
    on failed web socket connection attempts.

 *  Upgrade: [Okio 3.6.0][okio_3_6_0].

 *  Upgrade: [Kotlin 1.8.21][kotlin_1_8_21].


## Version 4.11.0

_2023-04-22_

 *  Fix: Don't fail the call when the response code is ‘HTTP 102 Processing’ or
    ‘HTTP 103 Early Hints’.
 *  Fix: Read the response even if writing the request fails. This means you'll get a proper HTTP
    response even if the server rejects your request body.
 *  Fix: Use literal IP addresses directly rather than passing them to `DnsOverHttps`.
 *  Fix: Embed Proguard rules to prevent warnings from tools like DexGuard and R8. These warnings
    were triggered by OkHttp’s feature detection for TLS packages like `org.conscrypt`,
    `org.bouncycastle`, and `org.openjsse`.
 *  Upgrade: Explicitly depend on `kotlin-stdlib-jdk8`. This fixes a problem with dependency
    locking. That's a potential security vulnerability, tracked as [CVE-2022-24329].
 *  Upgrade: [publicsuffix.org data][public_suffix]. This powers `HttpUrl.topPrivateDomain()`.
    It's also how OkHttp knows which domains can share cookies with one another.
 *  Upgrade: [Okio 3.2.0][okio_3_2_0].


## Version 4.10.0

_2022-06-12_

 *  Upgrade: [Kotlin 1.6.20][kotlin_1_6_20].
 *  Upgrade: [Okio 3.0.0][okio_3_0_0].
 *  Fix: Recover gracefully when Android's `NativeCrypto` crashes with `"ssl == null"`. This occurs
    when OkHttp retrieves ALPN state on a closed connection.


## Version 4.9.3

_2021-11-21_

 *  Fix: Don't fail HTTP/2 responses if they complete before a `RST_STREAM` is sent.


## Version 4.9.2

_2021-09-30_

 *  Fix: Don't include potentially-sensitive header values in `Headers.toString()` or exceptions.
    This applies to `Authorization`, `Cookie`, `Proxy-Authorization`, and `Set-Cookie` headers.
 *  Fix: Don't crash with an `InaccessibleObjectException` when running on JDK17+ with strong
    encapsulation enabled.
 *  Fix: Strictly verify hostnames used with OkHttp's `HostnameVerifier`. Programs that make direct
    manual calls to `HostnameVerifier` could be defeated if the hostnames they pass in are not
    strictly ASCII. This issue is tracked as [CVE-2021-0341].


## Version 4.9.1

_2021-01-30_

 *  Fix: Work around a crash in Android 10 and 11 that may be triggered when two threads
    concurrently close an SSL socket. This would have appeared in crash logs as
    `NullPointerException: bio == null`.


## Version 4.9.0

_2020-09-11_

**With this release, `okhttp-tls` no longer depends on Bouncy Castle and doesn't install the
Bouncy Castle security provider.** If you still need it, you can do it yourself:

```
Security.addProvider(BouncyCastleProvider())
```

You will also need to configure this dependency:

```
dependencies {
  implementation "org.bouncycastle:bcprov-jdk15on:1.65"
}
```

 *  Upgrade: [Kotlin 1.4.10][kotlin_1_4_10]. We now use Kotlin 1.4.x [functional
    interfaces][fun_interface] for `Authenticator`, `Interceptor`, and others.
 *  Upgrade: Build with Conscrypt 2.5.1.


## Version 4.8.1

_2020-08-06_

 *  Fix: Don't crash in `HeldCertificate.Builder` when creating certificates on older versions of
    Android, including Android 6. We were using a feature of `SimpleDateFormat` that wasn't
    available in those versions!


## Version 4.8.0

_2020-07-11_

 *  New: Change `HeldCertificate.Builder` to use its own ASN.1 certificate encoder. This is part
    of our effort to remove the okhttp-tls module's dependency on Bouncy Castle. We think Bouncy
    Castle is great! But it's a large dependency (6.5 MiB) and its security provider feature
    impacts VM-wide behavior.

 *  New: Reduce contention for applications that make a very high number of concurrent requests.
    Previously OkHttp used its connection pool as a lock when making changes to connections and
    calls. With this change each connection is locked independently.

 *  Upgrade: [Okio 2.7.0][okio_2_7_0].

    ```kotlin
    implementation("com.squareup.okio:okio:2.7.0")
    ```

 *  Fix: Avoid log messages like "Didn't find class org.conscrypt.ConscryptHostnameVerifier" when
    detecting the TLS capabilities of the host platform.

 *  Fix: Don't crash in `HttpUrl.topPrivateDomain()` when the hostname is malformed.

 *  Fix: Don't attempt Brotli decompression if the response body is empty.


## Version 4.7.2

_2020-05-20_

 *  Fix: Don't crash inspecting whether the host platform is JVM or Android. With 4.7.0 and 4.7.1 we
    had a crash `IllegalArgumentException: Not a Conscrypt trust manager` because we depended on
    initialization order of companion objects.


## Version 4.7.1

_2020-05-18_

 *  Fix: Pass the right arguments in the trust manager created for `addInsecureHost()`. Without the
    fix insecure hosts crash with an `IllegalArgumentException` on Android.


## Version 4.7.0

_2020-05-17_

 *  New: `HandshakeCertificates.Builder.addInsecureHost()` makes it easy to turn off security in
    private development environments that only carry test data. Prefer this over creating an
    all-trusting `TrustManager` because only hosts on the allowlist are insecure. From
    [our DevServer sample][dev_server]:

    ```kotlin
    val clientCertificates = HandshakeCertificates.Builder()
        .addPlatformTrustedCertificates()
        .addInsecureHost("localhost")
        .build()

    val client = OkHttpClient.Builder()
        .sslSocketFactory(clientCertificates.sslSocketFactory(), clientCertificates.trustManager)
        .build()
    ```

 *  New: Add `cacheHit`, `cacheMiss`, and `cacheConditionalHit()` events to `EventListener`. Use
    these in logs, metrics, and even test cases to confirm your cache headers are configured as
    expected.

 *  New: Constant string `okhttp3.VERSION`. This is a string like "4.5.0-RC1", "4.5.0", or
    "4.6.0-SNAPSHOT" indicating the version of OkHttp in the current runtime. Use this to include
    the OkHttp version in custom `User-Agent` headers.

 *  Fix: Don't crash when running as a plugin in Android Studio Canary 4.1. To enable
    platform-specific TLS features OkHttp must detect whether it's running in a JVM or in Android.
    The upcoming Android Studio runs in a JVM but has classes from Android and that confused OkHttp!

 *  Fix: Include the header `Accept: text/event-stream` for SSE calls. This header is not added if
    the request already contains an `Accept` header.

 *  Fix: Don't crash with a `NullPointerException` if a server sends a close while we're sending a
    ping. OkHttp had a race condition bug.


## Version 4.6.0

_2020-04-28_

 *  Fix: Follow HTTP 307 and 308 redirects on methods other than GET and POST. We're reluctant to
    change OkHttp's behavior in handling common HTTP status codes, but this fix is overdue! The new
    behavior is now consistent with [RFC 7231][rfc_7231_647], which is newer than OkHttp itself.
    If you want this update with the old behavior use [this interceptor][legacy_interceptor].

 *  Fix: Don't crash decompressing web sockets messages. We had a bug where we assumed deflated
    bytes in would always yield deflated bytes out and this isn't always the case!

 *  Fix: Reliably update and invalidate the disk cache on windows. As originally designed our
    internal `DiskLruCache` assumes an inode-like file system, where it's fine to delete files that
    are currently being read or written. On Windows the file system forbids this so we must be more
    careful when deleting and renaming files.

 *  Fix: Don't crash on Java 8u252 which introduces an API previously found only on Java 9 and
    above. See [Jetty's overview][jetty_8_252] of the API change and its consequences.

 *  New: `MultipartReader` is a streaming decoder for [MIME multipart (RFC 2045)][rfc_2045]
    messages. It complements `MultipartBody` which is our streaming encoder.

    ```kotlin
    val response: Response = call.execute()
    val multipartReader = MultipartReader(response.body!!)

    multipartReader.use {
      while (true) {
        val part = multipartReader.nextPart() ?: break
        process(part.headers, part.body)
      }
    }
    ```

 *  New: `MediaType.parameter()` gets a parameter like `boundary` from a media type like
    `multipart/mixed; boundary="abc"`.

 *  New: `Authenticator.JAVA_NET_AUTHENTICATOR` forwards authentication requests to
    `java.net.Authenticator`. This obsoletes `JavaNetAuthenticator` in the `okhttp-urlconnection`
    module.

 *  New: `CertificatePinner` now offers an API for inspecting the configured pins.

 *  Upgrade: [Okio 2.6.0][okio_2_6_0].

    ```kotlin
    implementation("com.squareup.okio:okio:2.6.0")
    ```

 *  Upgrade: [publicsuffix.org data][public_suffix]. This powers `HttpUrl.topPrivateDomain()`.
    It's also how OkHttp knows which domains can share cookies with one another.

 *  Upgrade: [Bouncy Castle 1.65][bouncy_castle_releases]. This dependency is required by the
    `okhttp-tls` module.

 *  Upgrade: [Kotlin 1.3.71][kotlin_1_3_71].


## Version 4.5.0

_2020-04-06_

**This release fixes a severe bug where OkHttp incorrectly detected and recovered from unhealthy
connections.** Stale or canceled connections were incorrectly attempted when they shouldn't have
been, leading to rare cases of infinite retries. Please upgrade to this release!

 *  Fix: don't return stale DNS entries in `DnsOverHttps`. We were caching DNS results indefinitely
    rather than the duration specified in the response's cache-control header.
 *  Fix: Verify certificate IP addresses in canonical form. When a server presents a TLS certificate
    containing an IP address we must match that address against the URL's IP address, even when the
    two addresses are encoded differently, such as `192.168.1.1` and `0::0:0:FFFF:C0A8:101`. Note
    that OkHttp incorrectly rejected valid certificates resulting in a failure to connect; at no
    point were invalid certificates accepted.
 *  New: `OkHttpClient.Builder.minWebSocketMessageToCompress()` configures a threshold for
    compressing outbound web socket messages. Configure this with 0L to always compress outbound
    messages and `Long.MAX_VALUE` to never compress outbound messages. The default is 1024L which
    compresses messages of size 1 KiB and larger. (Inbound messages are compressed or not based on
    the web socket server's configuration.)
 *  New: Defer constructing `Inflater` and `Deflater` instances until they are needed. This saves
    memory if web socket compression is negotiated but not used.


## Version 4.5.0-RC1

_2020-03-17_

**This release candidate turns on web socket compression.**

The [spec][rfc_7692] includes a sophisticated mechanism for client and server to negotiate
compression features. We strive to offer great performance in our default configuration and so we're
making compression the default for everyone starting with this release candidate.

Please be considerate of your servers and their operators as you roll out this release. Compression
saves bandwidth but it costs CPU and memory! If you run into a problem you may need to adjust or
disable the `permessage-deflate` compression settings on your server.

Note that OkHttp won't use compression when sending messages smaller than 1 KiB.

 *  Fix: Don't crash when the URL hostname contains an underscore on Android.
 *  Fix: Change HTTP/2 to use a daemon thread for its socket reader. If you've ever seen a command
    line application hang after all of the work is done, it may be due to a non-daemon thread like
    this one.
 *  New: Include suppressed exceptions when all routes to a target service fail.


## Version 4.4.1

_2020-03-08_

 *  Fix: Don't reuse a connection on redirect if certs match but DNS does not. For better
    locality and performance OkHttp attempts to use the same pooled connection across redirects and
    follow-ups. It independently shares connections when the IP addresses and certificates match,
    even if the host names do not. In 4.4.0 we introduced a regression where we shared a connection
    when certificates matched but the DNS addresses did not. This would only occur when following a
    redirect from one hostname to another, and where both hosts had common certificates.

 *  Fix: Don't fail on a redirect when a client has configured a 'trust everything' trust manager.
    Typically this would cause certain redirects to fail in debug and development configurations.


## Version 4.4.0

_2020-02-17_

 *  New: Support `canceled()` as an event that can be observed by `EventListener`. This should be
    useful for splitting out canceled calls in metrics.

 *  New: Publish a [bill of materials (BOM)][bom] for OkHttp. Depend on this from Gradle or Maven to
    keep all of your OkHttp artifacts on the same version, even if they're declared via transitive
    dependencies. You can even omit versions when declaring other OkHttp dependencies.

    ```kotlin
    dependencies {
       api(platform("com.squareup.okhttp3:okhttp-bom:4.4.0"))
       api("com.squareup.okhttp3:okhttp")              // No version!
       api("com.squareup.okhttp3:logging-interceptor") // No version!
    }
    ```

 *  New: Upgrade to Okio 2.4.3.

    ```kotlin
    implementation("com.squareup.okio:okio:2.4.3")
    ```

 *  Fix: Limit retry attempts for HTTP/2 `REFUSED_STREAM` and `CANCEL` failures.
 *  Fix: Retry automatically when incorrectly sharing a connection among multiple hostnames. OkHttp
    shares connections when hosts share both IP addresses and certificates, such as `squareup.com`
    and `www.squareup.com`. If a server refuses such sharing it will return HTTP 421 and OkHttp will
    automatically retry on an unshared connection.
 *  Fix: Don't crash if a TLS tunnel's response body is truncated.
 *  Fix: Don't track unusable routes beyond their usefulness. We had a bug where we could track
    certain bad routes indefinitely; now we only track the ones that could be necessary.
 *  Fix: Defer proxy selection until a proxy is required. This saves calls to `ProxySelector` on
    calls that use a pooled connection.


## Version 4.3.1

_2020-01-07_

 *  Fix: Don't crash with a `NullPointerException` when a web socket is closed before it connects.
    This regression was introduced in OkHttp 4.3.0.
 *  Fix: Don't crash with an `IllegalArgumentException` when using custom trust managers on
    Android 10. Android uses reflection to look up a magic `checkServerTrusted()` method and we
    didn't have it.
 *  Fix: Explicitly specify the remote server name when making HTTPS connections on Android 5. In
    4.3.0 we introduced a regression where server name indication (SNI) was broken on Android 5.


## Version 4.3.0

_2019-12-31_

 *  Fix: Degrade HTTP/2 connections after a timeout. When an HTTP/2 stream times out it may impact
    the stream only or the entire connection. With this fix OkHttp will now send HTTP/2 pings after
    a stream timeout to determine whether the connection should remain eligible for pooling.

 *  Fix: Don't call `EventListener.responseHeadersStart()` or `responseBodyStart()` until bytes have
    been received. Previously these events were incorrectly sent too early, when OkHttp was ready to
    read the response headers or body, which mislead tracing tools. Note that the `responseFailed()`
    event always used to follow one of these events; now it may be sent without them.

 *  New: Upgrade to Kotlin 1.3.61.

 *  New: Match any number of subdomains with two asterisks in `CertificatePinner`. For example,
    `**.squareup.com` matches `us-west.www.squareup.com`, `www.squareup.com` and `squareup.com`.

 *  New: Share threads more aggressively between OkHttp's HTTP/2 connections, connection pool,
    web sockets, and cache. OkHttp has a new internal task runner abstraction for managed task
    scheduling. In your debugger you will see new thread names and more use of daemon threads.

 *  Fix: Don't drop callbacks on unexpected exceptions. When an interceptor throws an unchecked
    exception the callback is now notified that the call was canceled. The exception is still sent
    to the uncaught exception handler for reporting and recovery.

 *  Fix: Un-deprecate `MockResponse.setHeaders()` and other setters. These were deprecated in OkHttp
    4.0 but that broke method chaining for Java callers.

 *  Fix: Don't crash on HTTP/2 HEAD requests when the `Content-Length` header is present but is not
    consistent with the length of the response body.

 *  Fix: Don't crash when converting a `HttpUrl` instance with an unresolvable hostname to a URI.
    The new behavior strips invalid characters like `"` and `{` from the hostname before converting.

 *  Fix: Undo a performance regression introduced in OkHttp 4.0 caused by differences in behavior
    between Kotlin's `assert()` and Java's `assert()`. (Kotlin always evaluates the argument; Java
    only does when assertions are enabled.)

 *  Fix: Honor `RequestBody.isOneShot()` in `HttpLoggingInterceptor`.


## Version 4.2.2

_2019-10-06_

 *  Fix: When closing a canceled HTTP/2 stream, don't send the `END_STREAM` flag. This could cause
    the server to incorrectly interpret the stream as having completed normally. This is most useful
    when a request body needs to cancel its own call.


## Version 4.2.1

_2019-10-02_

 *  Fix: In 4.1.0 we introduced a performance regression that prevented connections from being
    pooled in certain situations. We have good test coverage for connection pooling but we missed
    this because it only occurs if you have proxy configured and you share a connection pool among
    multiple `OkHttpClient` instances.

    This particularly-subtle bug was caused by us assigning each `OkHttpClient` instance its own
    `NullProxySelector` when an explicit proxy is configured. But we don't share connections when
    the proxy selectors are different. Ugh!


## Version 4.2.0

_2019-09-10_

 *  New: API to decode a certificate and private key to create a `HeldCertificate`. This accepts a
    string containing both a certificate and PKCS #8-encoded private key.

    ```kotlin
    val heldCertificate = HeldCertificate.decode("""
        |-----BEGIN CERTIFICATE-----
        |MIIBYTCCAQegAwIBAgIBKjAKBggqhkjOPQQDAjApMRQwEgYDVQQLEwtlbmdpbmVl
        |cmluZzERMA8GA1UEAxMIY2FzaC5hcHAwHhcNNzAwMTAxMDAwMDA1WhcNNzAwMTAx
        |MDAwMDEwWjApMRQwEgYDVQQLEwtlbmdpbmVlcmluZzERMA8GA1UEAxMIY2FzaC5h
        |cHAwWTATBgcqhkjOPQIBBggqhkjOPQMBBwNCAASda8ChkQXxGELnrV/oBnIAx3dD
        |ocUOJfdz4pOJTP6dVQB9U3UBiW5uSX/MoOD0LL5zG3bVyL3Y6pDwKuYvfLNhoyAw
        |HjAcBgNVHREBAf8EEjAQhwQBAQEBgghjYXNoLmFwcDAKBggqhkjOPQQDAgNIADBF
        |AiAyHHg1N6YDDQiY920+cnI5XSZwEGhAtb9PYWO8bLmkcQIhAI2CfEZf3V/obmdT
        |yyaoEufLKVXhrTQhRfodTeigi4RX
        |-----END CERTIFICATE-----
        |-----BEGIN PRIVATE KEY-----
        |MEECAQAwEwYHKoZIzj0CAQYIKoZIzj0DAQcEJzAlAgEBBCA7ODT0xhGSNn4ESj6J
        |lu/GJQZoU9lDrCPeUcQ28tzOWw==
        |-----END PRIVATE KEY-----
        """.trimMargin())
    val handshakeCertificates = HandshakeCertificates.Builder()
        .heldCertificate(heldCertificate)
        .build()
    val server = MockWebServer()
    server.useHttps(handshakeCertificates.sslSocketFactory(), false)
    ```

    Get these strings with `HeldCertificate.certificatePem()` and `privateKeyPkcs8Pem()`.

 *  Fix: Handshake now returns peer certificates in canonical order: each certificate is signed by
    the certificate that follows and the last certificate is signed by a trusted root.

 *  Fix: Don't lose HTTP/2 flow control bytes when incoming data races with a stream close. If this
    happened enough then eventually the connection would stall.

 *  Fix: Acknowledge and apply inbound HTTP/2 settings atomically. Previously we had a race where we
    could use new flow control capacity before acknowledging it, causing strict HTTP/2 servers to
    fail the call.


## Version 4.1.1

_2019-09-05_

 *  Fix: Don't drop repeated headers when validating cached responses. In our Kotlin upgrade we
    introduced a regression where we iterated the number of unique header names rather than then
    number of unique headers. If you're using OkHttp's response cache this may impact you.


## Version 4.1.0

_2019-08-12_

 [brotli]: https://github.com/google/brotli

 *  **OkHttp's new okhttp-brotli module implements Brotli compression.** Install the interceptor to
    enable [Brotli compression][brotli], which compresses 5-20% smaller than gzip.

    ```
    val client = OkHttpClient.Builder()
        .addInterceptor(BrotliInterceptor)
        .build()
    ```

    This artifact has a dependency on Google's Brotli decoder (95 KiB).

 *  New: `EventListener.proxySelectStart()`, `proxySelectEnd()` events give visibility into the
    proxy selection process.
 *  New: `Response.byteString()` reads the entire response into memory as a byte string.
 *  New: `OkHttpClient.x509TrustManager` accessor.
 *  New: Permit [new WebSocket response codes][iana_websocket]: 1012 (Service Restart), 1013 (Try
    Again Later), and 1014 (invalid response from the upstream).
 *  New: Build with Kotlin 1.3.41, BouncyCastle 1.62, and Conscrypt 2.2.1.
 *  Fix: Recover gracefully when a coalesced connection immediately goes unhealthy.
 *  Fix: Defer the `SecurityException` when looking up the default proxy selector.
 *  Fix: Don't use brackets formatting IPv6 host names in MockWebServer.
 *  Fix: Don't permit cache iterators to remove entries that are being written.


## Version 4.0.1

_2019-07-10_

 *  Fix: Tolerate null-hostile lists in public API. Lists created with `List.of(...)` don't like it
    when you call `contains(null)` on them!
 *  Fix: Retain binary-compatibility in `okhttp3.internal.HttpHeaders.hasBody()`. Some unscrupulous
    coders call this and we don't want their users to suffer.


## Version 4.0.0

_2019-06-26_

**This release upgrades OkHttp to Kotlin.** We tried our best to make fast and safe to upgrade
from OkHttp 3.x. We wrote an [upgrade guide][upgrading_to_okhttp_4] to help with the migration and a
[blog post][okhttp4_blog_post] to explain it.

 *  Fix: Target Java 8 bytecode for Java and Kotlin.


## Version 4.0.0-RC3

_2019-06-24_

 *  Fix: Retain binary-compatibility in `okhttp3.internal.HttpMethod`. Naughty third party SDKs
    import this and we want to ease upgrades for their users.


## Version 4.0.0-RC2

_2019-06-21_

 *  New: Require Kotlin 1.3.40.
 *  New: Change the Kotlin API from `File.toRequestBody()` to `File.asRequestBody()` and
    `BufferedSource.toResponseBody()` to `BufferedSource.asResponseBody()`. If the returned value
    is a view of what created it, we use _as_.
 *  Fix: Permit response codes of zero for compatibility with OkHttp 3.x.
 *  Fix: Change the return type of `MockWebServer.takeRequest()` to be nullable.
 *  Fix: Make `Call.clone()` public to Kotlin callers.


## Version 4.0.0-RC1

_2019-06-03_

 *  First stable preview of OkHttp 4.


## Version 3.x

[Change log](https://lysine.dev/okhttp/changelog_3x/)


 [bom]: https://docs.gradle.org/6.2/userguide/platforms.html#sub:bom_import
 [bouncy_castle_releases]: https://www.bouncycastle.org/releasenotes.html
 [CVE-2021-0341]: https://nvd.nist.gov/vuln/detail/CVE-2021-0341
 [CVE-2022-24329]: https://nvd.nist.gov/vuln/detail/CVE-2022-24329
 [dev_server]: https://github.com/lysine-dev/okhttp/blob/482f88300f78c3419b04379fc26c3683c10d6a9d/samples/guide/src/main/java/okhttp3/recipes/kt/DevServer.kt
 [fun_interface]: https://kotlinlang.org/docs/reference/fun-interfaces.html
 [iana_websocket]: https://www.iana.org/assignments/websocket/websocket.txt
 [jetty_8_252]: https://webtide.com/jetty-alpn-java-8u252/
 [kotlin_1_3_71]: https://github.com/JetBrains/kotlin/releases/tag/v1.3.71
 [kotlin_1_4_10]: https://github.com/JetBrains/kotlin/releases/tag/v1.4.10
 [kotlin_1_6_20]: https://github.com/JetBrains/kotlin/releases/tag/v1.6.20
 [kotlin_1_8_21]: https://github.com/JetBrains/kotlin/releases/tag/v1.8.21
 [legacy_interceptor]: https://gist.github.com/swankjesse/80135f4e03629527e723ab3bcf64be0b
 [okhttp4_blog_post]: https://cashapp.github.io/2019-06-26/okhttp-4-goes-kotlin
 [okio.FileSystem]: https://lysine.dev/okio/file_system/
 [okio_2_6_0]: https://lysine.dev/okio/changelog/#version-260
 [okio_2_7_0]: https://lysine.dev/okio/changelog/#version-270
 [okio_3_0_0]: https://lysine.dev/okio/changelog/#version-300
 [okio_3_2_0]: https://lysine.dev/okio/changelog/#version-320
 [okio_3_6_0]: https://lysine.dev/okio/changelog/#version-360
 [public_suffix]: https://publicsuffix.org/
 [rfc_2045]: https://tools.ietf.org/html/rfc2045
 [rfc_7231_647]: https://tools.ietf.org/html/rfc7231#section-6.4.7
 [rfc_7692]: https://tools.ietf.org/html/rfc7692
 [semver]: https://semver.org/
 [upgrading_to_okhttp_4]: https://lysine.dev/okhttp/upgrading_to_okhttp_4/
