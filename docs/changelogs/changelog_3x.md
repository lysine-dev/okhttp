OkHttp 3.x Change Log
=====================

## Version 3.14.9

_2020-05-17_

 *  Fix: Don't crash when running as a plugin in Android Studio Canary 4.1. To enable
    platform-specific TLS features OkHttp must detect whether it's running in a JVM or in Android.
    The upcoming Android Studio runs in a JVM but has classes from Android and that confused OkHttp!


## Version 3.14.8

_2020-04-28_

 *  Fix: Don't crash on Java 8u252 which introduces an API previously found only on Java 9 and
    above. See [Jetty's overview][jetty_8_252] of the API change and its consequences.

## Version 3.14.7

_2020-02-24_

 *  Fix: Don't crash on Android 11 due to use of restricted methods. This prevents a crash with the
    exception, "Expected Android API level 21+ but was 29".


## Version 3.14.6

_2020-01-11_

 *  Fix: Don't crash if the connection is closed when sending a degraded ping. This fixes a
    regression that was introduced in OkHttp 3.14.5.


## Version 3.14.5

_2020-01-03_

 *  Fix: Degrade HTTP/2 connections after a timeout. When an HTTP/2 stream times out it may impact
    the stream only or the entire connection. With this fix OkHttp will now send HTTP/2 pings after
    a stream timeout to determine whether the connection should remain eligible for pooling.


## Version 3.14.4

_2019-09-29_

 *  Fix: Cancel calls that fail due to unexpected exceptions. We had a bug where an enqueued call
    would never call back if it crashed with an unchecked throwable, such as a
    `NullPointerException` or `OutOfMemoryError`. We now call `Callback.onFailure()` with an
    `IOException` that reports the call as canceled. The triggering exception is still delivered to
    the thread's `UncaughtExceptionHandler`.
 *  Fix: Don't evict incomplete entries when iterating the cache. We had a bug where iterating
    `Cache.urls()` would prevent in-flight entries from being written.


## Version 3.14.3

_2019-09-10_

 *  Fix: Don't lose HTTP/2 flow control bytes when incoming data races with a stream close. If this
    happened enough then eventually the connection would stall.

 *  Fix: Acknowledge and apply inbound HTTP/2 settings atomically. Previously we had a race where we
    could use new flow control capacity before acknowledging it, causing strict HTTP/2 servers to
    fail the call.

 *  Fix: Recover gracefully when a coalesced connection immediately goes unhealthy.

## Version 3.14.2

_2019-05-19_

 *  Fix: Lock in a route when recovering from an HTTP/2 connection error. We had a bug where two
    calls that failed at the same time could cause OkHttp to crash with a `NoSuchElementException`
    instead of the expected `IOException`.

 *  Fix: Don't crash with a `NullPointerException` when formatting an error message describing a
    truncated response from an HTTPS proxy.


## Version 3.14.1

_2019-04-10_

 *  Fix: Don't crash when an interceptor retries when there are no more routes. This was an
    edge-case regression introduced with the events cleanup in 3.14.0.

 *  Fix: Provide actionable advice when the exchange is non-null. Prior to 3.14, OkHttp would
    silently leak connections when an interceptor retries without closing the response body. With
    3.14 we detect this problem but the exception was not helpful.

## Version 3.14.0

_2019-03-14_

 *  **This release deletes the long-deprecated `OkUrlFactory` and `OkApacheClient` APIs.** These
    facades hide OkHttp's implementation behind another client's API. If you still need this please
    copy and paste [ObsoleteUrlFactory.java][obsolete_url_factory] or
    [ObsoleteApacheClient.java][obsolete_apache_client] into your project.

 *  **OkHttp now supports duplex calls over HTTP/2.** With normal HTTP calls the request must finish
    before the response starts. With duplex, request and response bodies are transmitted
    simultaneously. This can be used to implement interactive conversations within a single HTTP
    call.

    Create duplex calls by overriding the new `RequestBody.isDuplex()` method to return true.
    This simple option dramatically changes the behavior of the request body and of the entire
    call.

    The `RequestBody.writeTo()` method may now retain a reference to the provided sink and
    hand it off to another thread to write to it after `writeTo` returns.

    The `EventListener` may now see requests and responses interleaved in ways not previously
    permitted. For example, a listener may receive `responseHeadersStart()` followed by
    `requestBodyEnd()`, both on the same call. Such events may be triggered by different threads
    even for a single call.

    Interceptors that rewrite or replace the request body may now inadvertently interfere with
    duplex request bodies. Such interceptors should check `RequestBody.isDuplex()` and avoid
    accessing the request body when it is.

    Duplex calls require HTTP/2. If HTTP/1 is established instead the duplex call will fail. The
    most common use of duplex calls is [gRPC][grpc_http2].

 *  New: Prevent OkHttp from retransmitting a request body by overriding `RequestBody.isOneShot()`.
    This is most useful when writing the request body is destructive.

 *  New: We've added `requestFailed()` and `responseFailed()` methods to `EventListener`. These
    are called instead of `requestBodyEnd()` and `responseBodyEnd()` in some failure situations.
    They may also be fired in cases where no event was published previously. In this release we did
    an internal rewrite of our event code to fix problems where events were lost or unbalanced.

 *  Fix: Don't leak a connection when a call is canceled immediately preceding the `onFailure()`
    callback.

 *  Fix: Apply call timeouts when connecting duplex calls, web sockets, and server-sent events.
    Once the streams are established no further timeout is enforced.

 *  Fix: Retain the `Route` when a connection is reused on a redirect or other follow-up. This was
    causing some `Authenticator` calls to see a null route when non-null was expected.

 *  Fix: Use the correct key size in the name of `TLS_AES_128_CCM_8_SHA256` which is a TLS 1.3
    cipher suite. We accidentally specified a key size of 256, preventing that cipher suite from
    being selected for any TLS handshakes. We didn't notice because this cipher suite isn't
    supported on Android, Java, or Conscrypt.

    We removed this cipher suite and `TLS_AES_128_CCM_SHA256` from the restricted, modern, and
    compatible sets of cipher suites. These two cipher suites aren't enabled by default in either
    Firefox or Chrome.

    See our [TLS Configuration History][tls_configuration_history] tracker for a log of all changes
    to OkHttp's default TLS options.

 *  New: Upgrade to Conscrypt 2.0.0. OkHttp works with other versions of Conscrypt but this is the
    version we're testing against.

    ```kotlin
    implementation("org.conscrypt:conscrypt-openjdk-uber:2.0.0")
    ```

 *  New: Update the embedded public suffixes list.


## Version 3.13.1

_2019-02-05_

 *  Fix: Don't crash when using a custom `X509TrustManager` or `SSLSocket` on Android. When we
    removed obsolete code for Android 4.4 we inadvertently also removed support for custom
    subclasses. We've restored that support!


## Version 3.13.0

_2019-02-04_

 *  **This release bumps our minimum requirements to Java 8+ or Android 5+.** Cutting off old
    devices is a serious change and we don't do it lightly! [This post][require_android_5] explains
    why we're doing this and how to upgrade.

    The OkHttp 3.12.x branch will be our long-term branch for Android 2.3+ (API level 9+) and Java
    7+. These platforms lack support for TLS 1.2 and should not be used. But because upgrading is
    difficult we will backport critical fixes to the 3.12.x branch through December 31, 2021. (This
    commitment was originally through December 31, 2020; we have since extended it.)

 *  **TLSv1 and TLSv1.1 are no longer enabled by default.** Major web browsers are working towards
    removing these versions altogether in early 2020. If your servers aren't ready yet you can
    configure OkHttp 3.13 to allow TLSv1 and TLSv1.1 connections:

    ```
    OkHttpClient client = new OkHttpClient.Builder()
        .connectionSpecs(Arrays.asList(ConnectionSpec.COMPATIBLE_TLS))
        .build();
    ```

 *  New: You can now access HTTP trailers with `Response.trailers()`. This method may only be called
    after the entire HTTP response body has been read.

 *  New: Upgrade to Okio 1.17.3. If you're on Kotlin-friendly Okio 2.x this release requires 2.2.2
    or newer.

    ```kotlin
    implementation("com.squareup.okio:okio:1.17.3")
    ```

 *  Fix: Don't miss cancels when sending HTTP/2 request headers.
 *  Fix: Don't miss whole operation timeouts when calls redirect.
 *  Fix: Don't leak connections if web sockets have malformed responses or if `onOpen()` throws.
 *  Fix: Don't retry when request bodies fail due to `FileNotFoundException`.
 *  Fix: Don't crash when URLs have IPv4-mapped IPv6 addresses.
 *  Fix: Don't crash when building `HandshakeCertificates` on Android API 28.
 *  Fix: Permit multipart file names to contain non-ASCII characters.
 *  New: API to get MockWebServer's dispatcher.
 *  New: API to access headers as `java.time.Instant`.
 *  New: Fail fast if a `SSLSocketFactory` is used as a `SocketFactory`.
 *  New: Log the TLS handshake in `LoggingEventListener`.

## Version 3.12.13

_2021-01-30_

 *  Fix: Work around a crash in Android 10 and 11 that may be triggered when two threads
    concurrently close an SSL socket. This would have appeared in crash logs as
    `NullPointerException: bio == null`.


## Version 3.12.12

_2020-05-17_

 *  Fix: Don't crash when running as a plugin in Android Studio Canary 4.1. To enable
    platform-specific TLS features OkHttp must detect whether it's running in a JVM or in Android.
    The upcoming Android Studio runs in a JVM but has classes from Android and that confused OkHttp!


## Version 3.12.11

_2020-04-28_

 *  Fix: Don't crash on Java 8u252 which introduces an API previously found only on Java 9 and
    above. See [Jetty's overview][jetty_8_252] of the API change and its consequences.


## Version 3.12.10

_2020-02-29_

 *  Fix: Don't crash on Android 4.1 when detecting methods that became restricted in Android 11.
    Supporting a full decade of Android releases on our 3.12.x branch is tricky!


## Version 3.12.9

_2020-02-24_

 *  Fix: Don't crash on Android 11 due to use of restricted methods. This prevents a crash with the
    exception, "Expected Android API level 21+ but was 29".


## Version 3.12.8

_2020-01-11_

 *  Fix: Don't crash if the connection is closed when sending a degraded ping. This fixes a
    regression that was introduced in OkHttp 3.12.7.


## Version 3.12.7

_2020-01-03_

 *  Fix: Degrade HTTP/2 connections after a timeout. When an HTTP/2 stream times out it may impact
    the stream only or the entire connection. With this fix OkHttp will now send HTTP/2 pings after
    a stream timeout to determine whether the connection should remain eligible for pooling.


## Version 3.12.6

_2019-09-29_

 *  Fix: Cancel calls that fail due to unexpected exceptions. We had a bug where an enqueued call
    would never call back if it crashed with an unchecked throwable, such as a
    `NullPointerException` or `OutOfMemoryError`. We now call `Callback.onFailure()` with an
    `IOException` that reports the call as canceled. The triggering exception is still delivered to
    the thread's `UncaughtExceptionHandler`.
 *  Fix: Don't evict incomplete entries when iterating the cache. We had a bug where iterating
    `Cache.urls()` would prevent in-flight entries from being written.


## Version 3.12.5

_2019-09-10_

 *  Fix: Don't lose HTTP/2 flow control bytes when incoming data races with a stream close. If this
    happened enough then eventually the connection would stall.

 *  Fix: Acknowledge and apply inbound HTTP/2 settings atomically. Previously we had a race where we
    could use new flow control capacity before acknowledging it, causing strict HTTP/2 servers to
    fail the call.


## Version 3.12.4

_2019-09-04_

 *  Fix: Don't crash looking up an absent class on certain buggy Android 4.x devices.


## Version 3.12.3

_2019-05-07_

 *  Fix: Permit multipart file names to contain non-ASCII characters.
 *  Fix: Retain the `Route` when a connection is reused on a redirect or other follow-up. This was
    causing some `Authenticator` calls to see a null route when non-null was expected.


## Version 3.12.2

_2019-03-14_

 *  Fix: Don't crash if the HTTPS server returns no certificates in the TLS handshake.
 *  Fix: Don't leak a connection when a call is canceled immediately preceding the `onFailure()`
    callback.


## Version 3.12.1

_2018-12-23_

 *  Fix: Remove overlapping `package-info.java`. This caused issues with some build tools.


## Version 3.12.0

_2018-11-16_

 *  **OkHttp now supports TLS 1.3.** This requires either Conscrypt or Java 11+.

 *  **Proxy authenticators are now asked for preemptive authentication.** OkHttp will now request
    authentication credentials before creating TLS tunnels through HTTP proxies (HTTP `CONNECT`).
    Authenticators should identify preemptive authentications by the presence of a challenge whose
    scheme is "OkHttp-Preemptive".

 *  **OkHttp now offers full-operation timeouts.** This sets a limit on how long the entire call may
    take and covers resolving DNS, connecting, writing the request body, server processing, and
    reading the full response body. If a call requires redirects or retries all must complete within
    one timeout period.

    Use `OkHttpClient.Builder.callTimeout()` to specify the default duration and `Call.timeout()` to
    specify the timeout of an individual call.

 *  New: Return values and fields are now non-null unless otherwise annotated.
 *  New: `LoggingEventListener` makes it easy to get basic visibility into a call's performance.
    This class is in the `logging-interceptor` artifact.
 *  New: `Headers.Builder.addUnsafeNonAscii()` allows non-ASCII values to be added without an
    immediate exception.
 *  New: Headers can be redacted in `HttpLoggingInterceptor`.
 *  New: `Headers.Builder` now accepts dates.
 *  New: OkHttp now accepts `java.time.Duration` for timeouts on Java 8+ and Android 26+.
 *  New: `Challenge` includes all authentication parameters.
 *  New: Upgrade to BouncyCastle 1.60, Conscrypt 1.4.0, and Okio 1.15.0. We don't yet require
    Kotlin-friendly Okio 2.x but OkHttp works fine with that series.

    ```kotlin
    implementation("org.bouncycastle:bcprov-jdk15on:1.60")
    implementation("org.conscrypt:conscrypt-openjdk-uber:1.4.0")
    implementation("com.squareup.okio:okio:1.15.0")
    ```

 *  Fix: Handle dispatcher executor shutdowns gracefully. When there aren't any threads to carry a
    call its callback now gets a `RejectedExecutionException`.
 *  Fix: Don't permanently cache responses with `Cache-Control: immutable`. We misunderstood the
    original `immutable` proposal!
 *  Fix: Change `Authenticator`'s `Route` parameter to be nullable. This was marked as non-null but
    could be called with null in some cases.
 *  Fix: Don't create malformed URLs when `MockWebServer` is reached via an IPv6 address.
 *  Fix: Don't crash if the system default authenticator is null.
 *  Fix: Don't crash generating elliptic curve certificates on Android.
 *  Fix: Don't crash doing platform detection on RoboVM.
 *  Fix: Don't leak socket connections when web socket upgrades fail.


## Version 3.11.0

_2018-07-12_

 *  **OkHttp's new okhttp-tls submodule tames HTTPS and TLS.**

    `HeldCertificate` is a TLS certificate and its private key. Generate a certificate with its
    builder then use it to sign another certificate or perform a TLS handshake. The
    `certificatePem()` method encodes the certificate in the familiar PEM format
    (`--- BEGIN CERTIFICATE ---`); the `privateKeyPkcs8Pem()` does likewise for the private key.

    `HandshakeCertificates` holds the TLS certificates required for a TLS handshake. On the server
    it keeps your `HeldCertificate` and its chain. On the client it keeps the root certificates
    that are trusted to sign a server's certificate chain. `HandshakeCertificates` also works with
    mutual TLS where these roles are reversed.

    These classes make it possible to enable HTTPS in MockWebServer in [just a few lines of
    code][https_server_sample].

 *  **OkHttp now supports prior knowledge cleartext HTTP/2.** Enable this by setting
    `Protocol.H2_PRIOR_KNOWLEDGE` as the lone protocol on an `OkHttpClient.Builder`. This mode
    only supports `http:` URLs and is best suited in closed environments where HTTPS is
    inappropriate.

 *  New: `HttpUrl.get(String)` is an alternative to `HttpUrl.parse(String)` that throws an exception
    when the URL is malformed instead of returning null. Use this to avoid checking for null in
    situations where the input is known to be well-formed. We've also added `MediaType.get(String)`
    which is an exception-throwing alternative to `MediaType.parse(String)`.
 *  New: The `EventListener` API previewed in OkHttp 3.9 has graduated to a stable API. Use this
    interface to track metrics and monitor HTTP requests' size and duration.
 *  New: `okhttp-dnsoverhttps` is an experimental API for doing DNS queries over HTTPS. Using HTTPS
    for DNS offers better security and potentially better performance. This feature is a preview:
    the API is subject to change.
 *  New: `okhttp-sse` is an early preview of Server-Sent Events (SSE). This feature is incomplete
    and is only suitable for experimental use.
 *  New: MockWebServer now supports client authentication (mutual TLS). Call `requestClientAuth()`
    to permit an optional client certificate or `requireClientAuth()` to require one.
 *  New: `RecordedRequest.getHandshake()` returns the HTTPS handshake of a request sent to
    `MockWebServer`.
 *  Fix: Honor the `MockResponse` header delay in MockWebServer.
 *  Fix: Don't release HTTP/2 connections that have multiple canceled calls. We had a bug where
    canceling calls would cause the shared HTTP/2 connection to be unnecessarily released. This
    harmed connection reuse.
 *  Fix: Ensure canceled and discarded HTTP/2 data is not permanently counted against the limited
    flow control window. We had a few bugs where window size accounting was broken when streams
    were canceled or reset.
 *  Fix: Recover gracefully if the TLS session returns an unexpected version (`NONE`) or cipher
    suite (`SSL_NULL_WITH_NULL_NULL`).
 *  Fix: Don't change Conscrypt configuration globally. We migrated from a process-wide setting to
    configuring only OkHttp's TLS sockets.
 *  Fix: Prefer TLSv1.2 where it is available. On certain older platforms it is necessary to opt-in
    to TLSv1.2.
 *  New: `Request.tag()` permits multiple tags. Use a `Class<?>` as a key to identify tags. Note
    that `tag()` now returns null if the request has no tag. Previously this would return the
    request itself.
 *  New: `Headers.Builder.addAll(Headers)`.
 *  New: `ResponseBody.create(MediaType, ByteString)`.
 *  New: Embed R8/ProGuard rules in the jar. These will be applied automatically by R8.
 *  Fix: Release the connection if `Authenticator` throws an exception.
 *  Fix: Change the declaration of `OkHttpClient.cache()` to return a `@Nullable Cache`. The return
    value has always been nullable but it wasn't declared properly.
 *  Fix: Reverse suppression of connect exceptions. When both a call and its retry fail, we now
    throw the initial exception which is most likely to be actionable.
 *  Fix: Retain interrupted state when throwing `InterruptedIOException`. A single interrupt should
    now be sufficient to break out an in-flight OkHttp call.
 *  Fix: Don't drop a call to `EventListener.callEnd()` when the response body is consumed inside an
    interceptor.


## Version 3.10.0

_2018-02-24_

 *  **The pingInterval() feature now aggressively checks connectivity for web
    sockets and HTTP/2 connections.**

    Previously if you configured a ping interval that would cause OkHttp to send
    pings, but it did not track whether the reply pongs were received. With this
    update OkHttp requires that every ping receive a response: if it does not
    the connection will be closed and the listener's `onFailure()` method will
    be called.

    Web sockets have always been had pings, but pings on HTTP/2 connections is
    new in this release. Pings are used for connections that are busy carrying
    calls and for idle connections in the connection pool. (Pings do not impact
    when pooled connections are evicted).

    If you have a configured ping interval, you should confirm that it is long
    enough for a roundtrip from client to server. If your ping interval is too
    short, slow connections may be misinterpreted as failed connections. A ping
    interval of 30 seconds is reasonable for most use cases.

 *  **OkHttp now supports [Conscrypt][conscrypt].** Conscrypt is a Java Security
    Provider that integrates BoringSSL into the Java platform. Conscrypt
    supports more cipher suites than the JVM’s default provider and may also
    execute more efficiently.

    To use it, first register a [Conscrypt dependency][conscrypt_dependency] in
    your build system.

    OkHttp will use Conscrypt if you set the `okhttp.platform` system property
    to `conscrypt`.

    Alternatively, OkHttp will also use Conscrypt if you install it as your
    preferred security provider. To do so, add the following code to execute
    before you create your `OkHttpClient`.

    ```
    Security.insertProviderAt(
        new org.conscrypt.OpenSSLProvider(), 1);
    ```

    Conscrypt is the bundled security provider on Android so it is not necessary
    to configure it on that platform.

 *  New: `HttpUrl.addQueryParameter()` percent-escapes more characters.
    Previously several ASCII punctuation characters were not percent-escaped
    when used with this method. This does not impact already-encoded query
    parameters in APIs like `HttpUrl.parse()` and
    `HttpUrl.Builder.addEncodedQueryParameter()`.
 *  New: CBC-mode ECDSA cipher suites have been removed from OkHttp's default
    configuration: `TLS_ECDHE_ECDSA_WITH_AES_128_CBC_SHA` and
    `TLS_ECDHE_ECDSA_WITH_AES_256_CBC_SHA`. This tracks a [Chromium
    change][remove_cbc_ecdsa] to remove these cipher suites because they are
    fragile and rarely-used.
 *  New: Don't fall back to common name (CN) verification for hostnames. This
    behavior was deprecated with RFC 2818 in May 2000 and was recently dropped
    from major web browsers.
 *  New: Honor the `Retry-After` response header. HTTP 503 (Unavailable)
    responses are retried automatically if this header is present and its delay
    is 0 seconds. HTTP 408 (Client Timeout) responses are retried automatically
    if the header is absent or its delay is 0 seconds.
 *  New: Allow request bodies for all HTTP methods except GET and HEAD.
 *  New: Automatic module name of `okhttp3` for use with the Java Platform
    Module System.
 *  New: Log gzipped bodies when `HttpLoggingInterceptor` is used as a network
    interceptor.
 *  New: `Protocol.QUIC` constant. This protocol is not supported but this
    constant is included for completeness.
 *  New: Upgrade to Okio 1.14.0.

     ```xml
     <dependency>
       <groupId>com.squareup.okio</groupId>
       <artifactId>okio</artifactId>
       <version>1.14.0</version>
     </dependency>

     com.squareup.okio:okio:1.14.0
     ```

 *  Fix: Handle `HTTP/1.1 100 Continue` status lines, even on requests that did
    not send the `Expect: continue` request header.
 *  Fix: Do not count web sockets toward the dispatcher's per-host connection
    limit.
 *  Fix: Avoid using invalid HTTPS sessions. This prevents OkHttp from crashing
    with the error, `Unexpected TLS version: NONE`.
 *  Fix: Don't corrupt the response cache when a 304 (Not Modified) response
    overrides the stored "Content-Encoding" header.
 *  Fix: Gracefully shut down the HTTP/2 connection before it exhausts the
    namespace of stream IDs (~536 million streams).
 *  Fix: Never pass a null `Route` to `Authenticator`. There was a bug where
    routes were omitted for eagerly-closed connections.

## Version 3.9.1

_2017-11-18_

 *  New: Recover gracefully when Android's DNS crashes with an unexpected
    `NullPointerException`.
 *  New: Recover gracefully when Android's socket connections crash with an
    unexpected `ClassCastException`.
 *  Fix: Don't include the URL's fragment in `encodedQuery()` when the query
    itself is empty.

## Version 3.9.0

_2017-09-03_

 *  **Interceptors are more capable.** The `Chain` interface now offers access
    to the call and can adjust all call timeouts. Note that this change is
    source-incompatible for code that implements the `Chain` interface.
    We don't expect this to be a problem in practice!

 *  **OkHttp has an experimental new API for tracking metrics.** The new
    `EventListener` API is designed to help developers monitor HTTP requests'
    size and duration. This feature is an unstable preview: the API is subject
    to change, and the implementation is incomplete. This is a big new API we
    are eager for feedback.

 *  New: Support ALPN via Google Play Services' Dynamic Security Provider. This
    expands HTTP/2 support to older Android devices that have Google Play
    Services.
 *  New: Consider all routes when looking for candidate coalesced connections.
    This increases the likelihood that HTTP/2 connections will be shared.
 *  New: Authentication challenges and credentials now use a charset. Use this in
    your authenticator to support user names and passwords with non-ASCII
    characters.
 *  New: Accept a charset in `FormBody.Builder`. Previously form bodies were
    always UTF-8.
 *  New: Support the `immutable` cache-control directive.
 *  Fix: Don't crash when an HTTP/2 call is redirected while the connection is
    being shut down.
 *  Fix: Don't drop headers of healthy streams that raced with `GOAWAY` frames.
    This bug would cause HTTP/2 streams to occasional hang when the connection
    was shutting down.
 *  Fix: Honor `OkHttpClient.retryOnConnectionFailure()` when the response is a
    HTTP 408 Request Timeout. If retries are enabled, OkHttp will retry exactly
    once in response to a 408.
 *  Fix: Don't crash when reading the empty `HEAD` response body if it specifies
    a `Content-Length`.
 *  Fix: Don't crash if the thread is interrupted while reading the public
    suffix database.
 *  Fix: Use relative resource path when loading the public suffix database.
    Loading the resource using a path relative to the class prevents conflicts
    when the OkHttp classes are relocated (shaded) by allowing multiple private
    copies of the database.
 *  Fix: Accept cookies for URLs that have an IPv6 address for a host.
 *  Fix: Don't log the protocol (HTTP/1.1, h2) in HttpLoggingInterceptor if the
    protocol isn't negotiated yet! Previously we'd log HTTP/1.1 by default, and
    this was confusing.
 *  Fix: Omit the message from MockWebServer's HTTP/2 `:status` header.
 *  Fix: Handle 'Expect: 100 Continue' properly in MockWebServer.


## Version 3.8.1

_2017-06-18_

 *  Fix: Recover gracefully from stale coalesced connections. We had a bug where
    connection coalescing (introduced in OkHttp 3.7.0) and stale connection
    recovery could interact to cause a `NoSuchElementException` crash in the
    `RouteSelector`.


## Version 3.8.0

_2017-05-13_


 *  **OkHttp now uses `@Nullable` to annotate all possibly-null values.** We've
    added a compile-time dependency on the JSR 305 annotations. This is a
    [provided][maven_provided] dependency and does not need to be included in
    your build configuration, `.jar` file, or `.apk`. We use
    `@ParametersAreNonnullByDefault` and all parameters and return types are
    never null unless explicitly annotated `@Nullable`.

 *  **Warning: this release is source-incompatible for Kotlin users.**
    Nullability was previously ambiguous and lenient but now the compiler will
    enforce strict null checks.

 *  New: The response message is now non-null. This is the "Not Found" in the
    status line "HTTP 404 Not Found". If you are building responses
    programmatically (with `new Response.Builder()`) you must now always supply
    a message. An empty string `""` is permitted. This value was never null on
    responses returned by OkHttp itself, and it was an old mistake to permit
    application code to omit a message.

 *  The challenge's scheme and realm are now non-null. If you are calling
    `new Challenge(scheme, realm)` you must provide non-null values. These were
    never null in challenges created by OkHttp, but could have been null in
    application code that creates challenges.

 *  New: The `TlsVersion` of a `Handshake` is now non-null. If you are calling
    `Handshake.get()` with a null TLS version, you must instead now provide a
    non-null `TlsVersion`. Cache responses persisted prior to OkHttp 3.0 did not
    store a TLS version; for these unknown values the handshake is defaulted to
    `TlsVersion.SSL_3_0`.

 *  New: Upgrade to Okio 1.13.0.

     ```xml
     <dependency>
       <groupId>com.squareup.okio</groupId>
       <artifactId>okio</artifactId>
       <version>1.13.0</version>
     </dependency>

     com.squareup.okio:okio:1.13.0
     ```

 *  Fix: gracefully recover when Android 7.0's sockets throw an unexpected
    `NullPointerException`.

## Version 3.7.0

_2017-04-15_

 *  **OkHttp no longer recovers from TLS handshake failures by attempting a TLSv1 connection.**
    The fallback was necessary for servers that implemented version negotiation incorrectly. Now
    that 99.99% of servers do it right this fallback is obsolete.
 *  Fix: Do not honor cookies set on a public domain. Previously a malicious site could inject
    cookies on top-level domains like `co.uk` because our cookie parser didn't honor the [public
    suffix][public_suffix] list. Alongside this fix is a new API, `HttpUrl.topPrivateDomain()`,
    which returns the privately domain name if the URL has one.
 *  Fix: Change `MediaType.charset()` to return null for unexpected charsets.
 *  Fix: Don't skip cache invalidation if the invalidating response has no body.
 *  Fix: Don't use a cryptographic random number generator for web sockets. Some Android devices
    implement `SecureRandom` incorrectly!
 *  Fix: Correctly canonicalize IPv6 addresses in `HttpUrl`. This prevented OkHttp from trusting
    HTTPS certificates issued to certain IPv6 addresses.
 *  Fix: Don't reuse connections after an unsuccessful `Expect: 100-continue`.
 *  Fix: Handle either `TLS_` or `SSL_` prefixes for cipher suite names. This is necessary for
    IBM JVMs that use the `SSL_` prefix exclusively.
 *  Fix: Reject HTTP/2 data frames if the stream ID is 0.
 *  New: Upgrade to Okio 1.12.0.

     ```xml
     <dependency>
       <groupId>com.squareup.okio</groupId>
       <artifactId>okio</artifactId>
       <version>1.12.0</version>
     </dependency>

     com.squareup.okio:okio:1.12.0
     ```

 *  New: Connection coalescing. OkHttp may reuse HTTP/2 connections across calls that share an IP
    address and HTTPS certificate, even if their domain names are different.
 *  New: MockWebServer's `RecordedRequest` exposes the requested `HttpUrl` with `getRequestUrl()`.


## Version 3.6.0

_2017-01-29_

 *  Fix: Don't crash with a "cache is closed" error when there is an error initializing the cache.
 *  Fix: Calling `disconnect()` on a connecting `HttpUrlConnection` could cause it to retry in an
    infinite loop! This regression was introduced in OkHttp 2.7.0.
 *  Fix: Drop cookies that contain ASCII NULL and other bad characters. Previously such cookies
    would cause OkHttp to crash when they were included in a request.
 *  Fix: Release duplicated multiplexed connections. If we concurrently establish connections to an
    HTTP/2 server, close all but the first connection.
 *  Fix: Fail the HTTP/2 connection if first frame isn't `SETTINGS`.
 *  Fix: Forbid spaces in header names.
 *  Fix: Don't offer to do gzip if the request is partial.
 *  Fix: MockWebServer is now usable with JUnit 5. That update [broke the rules][junit_5_rules].
 *  New: Support `Expect: 100-continue` as a request header. Callers can use this header to
    pessimistically hold off on transmitting a request body until a server gives the go-ahead.
 *  New: Permit network interceptors to rewrite the host header for HTTP/2. This makes it possible
    to do domain fronting.
 *  New: charset support for `Credentials.basic()`.


## Version 3.5.0

_2016-11-30_

 *  **Web Sockets are now a stable feature of OkHttp.** Since being introduced as a beta feature in
    OkHttp 2.3 our web socket client has matured. Connect to a server's web socket with
    `OkHttpClient.newWebSocket()`, send messages with `send()`, and receive messages with the
    `WebSocketListener`.

    The `okhttp-ws` submodule is no longer available and `okhttp-ws` artifacts from previous
    releases of OkHttp are not compatible with OkHttp 3.5. When upgrading to the new package
    please note that the `WebSocket` and `WebSocketCall` classes have been merged. Sending messages
    is now asynchronous and they may be enqueued before the web socket is connected.

 *  **OkHttp no longer attempts a direct connection if the system's HTTP proxy fails.** This
    behavior was surprising because OkHttp was disregarding the user's specified configuration. If
    you need to customize proxy fallback behavior, implement your own `java.net.ProxySelector`.

 *  Fix: Support TLSv1.3 on devices that support it.

 *  Fix: Share pooled connections across equivalent `OkHttpClient` instances. Previous releases had
    a bug where a shared connection pool did not guarantee shared connections in some cases.
 *  Fix: Prefer the server's response body on all conditional cache misses. Previously we would
    return the cached response's body if it had a newer `Last-Modified` date.
 *  Fix: Update the stored timestamp on conditional cache hits.
 *  New: Optimized HTTP/2 request header encoding. More headers are HPACK-encoded and string
    literals are now Huffman-encoded.
 *  New: Expose `Part` headers and body in `Multipart`.
 *  New: Make `ResponseBody.string()` and `ResponseBody.charStream()` BOM-aware. If your HTTP
    response body begins with a [byte order mark][bom] it will be consumed and used to select a
    charset for the remaining bytes. Most applications should not need a byte order mark.

 *  New: Upgrade to Okio 1.11.0.

     ```xml
     <dependency>
       <groupId>com.squareup.okio</groupId>
       <artifactId>okio</artifactId>
       <version>1.11.0</version>
     </dependency>

     com.squareup.okio:okio:1.11.0
     ```

 *  Fix: Avoid sending empty HTTP/2 data frames when there is no request body.
 *  Fix: Add a leading `.` for better domain matching in `JavaNetCookieJar`.
 *  Fix: Gracefully recover from HTTP/2 connection shutdowns at start of request.
 *  Fix: Be lenient if a `MediaType`'s character set is `'single-quoted'`.
 *  Fix: Allow horizontal tab characters in header values.
 *  Fix: When parsing HTTP authentication headers permit challenge parameters in any order.


## Version 3.4.2

_2016-11-03_

 *  Fix: Recover gracefully when an HTTP/2 connection is shutdown. We had a
    bug where shutdown HTTP/2 connections were considered usable. This caused
    infinite loops when calls attempted to recover.


## Version 3.4.1

_2016-07-10_

 *  **Fix a major bug in encoding HTTP headers.** In 3.4.0 and 3.4.0-RC1 OkHttp
    had an off-by-one bug in our HPACK encoder. This bug could have caused the
    wrong headers to be emitted after a sequence of HTTP/2 requests! Everyone
    who is using OkHttp 3.4.0 or 3.4.0-RC1 should upgrade for this bug fix.


## Version 3.4.0

_2016-07-08_

 *  New: Support dynamic table size changes to HPACK Encoder.
 *  Fix: Use `TreeMap` in `Headers.toMultimap()`. This makes string lookups on
    the returned map case-insensitive.
 *  Fix: Don't share the OkHttpClient's `Dispatcher` in `HttpURLConnection`.


## Version 3.4.0-RC1

_2016-07-02_

 *  **We’ve rewritten HttpURLConnection and HttpsURLConnection.** Previously we
    shared a single HTTP engine between two frontend APIs: `HttpURLConnection`
    and `Call`. With this release we’ve rearranged things so that the
    `HttpURLConnection` frontend now delegates to the `Call` APIs internally.
    This has enabled substantial simplifications and optimizations in the OkHttp
    core for both frontends.

    For most HTTP requests the consequences of this change will be negligible.
    If your application uses `HttpURLConnection.connect()`,
    `setFixedLengthStreamingMode()`, or `setChunkedStreamingMode()`, OkHttp will
    now use a async dispatcher thread to establish the HTTP connection.

    We don’t expect this change to have any behavior or performance
    consequences. Regardless, please exercise your `OkUrlFactory` and
    `HttpURLConnection` code when applying this update.

 *  **Cipher suites may now have arbitrary names.** Previously `CipherSuite` was
    a Java enum and it was impossible to define new cipher suites without first
    upgrading OkHttp. With this change it is now a regular Java class with
    enum-like constants. Application code that uses enum methods on cipher
    suites (`ordinal()`, `name()`, etc.) will break with this change.

 *  Fix: `CertificatePinner` now matches canonicalized hostnames. Previously
    this was case sensitive. This change should also make it easier to configure
    certificate pinning for internationalized domain names.
 *  Fix: Don’t crash on non-ASCII `ETag` headers. Previously OkHttp would reject
    these headers when validating a cached response.
 *  Fix: Don’t allow remote peer to arbitrarily size the HPACK decoder dynamic
    table.
 *  Fix: Honor per-host configuration in Android’s network security config.
    Previously disabling cleartext for any host would disable cleartext for all
    hosts. Note that this setting is only available on Android 24+.
 *  New: HPACK compression is now dynamic. This should improve performance when
    transmitting request headers over HTTP/2.
 *  New: `Dispatcher.setIdleCallback()` can be used to signal when there are no
    calls in flight. This is useful for [testing with
    Espresso][okhttp_idling_resource].
 *  New: Upgrade to Okio 1.9.0.

     ```xml
     <dependency>
       <groupId>com.squareup.okio</groupId>
       <artifactId>okio</artifactId>
       <version>1.9.0</version>
     </dependency>
     ```


## Version 3.3.1

_2016-05-28_

 *  Fix: The plaintext check in HttpLoggingInterceptor incorrectly classified
    newline characters as control characters. This is fixed.
 *  Fix: Don't crash reading non-ASCII characters in HTTP/2 headers or in cached
    HTTP headers.
 *  Fix: Retain the response body when an attempt to open a web socket returns a
    non-101 response code.


## Version 3.3.0

_2016-05-24_

 *  New: `Response.sentRequestAtMillis()` and `receivedResponseAtMillis()`
    methods track the system's local time when network calls are made. These
    replace the `OkHttp-Sent-Millis` and `OkHttp-Received-Millis` headers that were
    present in earlier versions of OkHttp.
 *  New: Accept user-provided trust managers in `OkHttpClient.Builder`. This
    allows OkHttp to satisfy its TLS requirements directly. Otherwise OkHttp
    will use reflection to extract the `TrustManager` from the
    `SSLSocketFactory`.
 *  New: Support prerelease Java 9. This gets ALPN from the platform rather than
    relying on the alpn-boot bootclasspath override.
 *  New: `HttpLoggingInterceptor` now logs connection failures.
 *  New: Upgrade to Okio 1.8.0.

     ```xml
     <dependency>
       <groupId>com.squareup.okio</groupId>
       <artifactId>okio</artifactId>
       <version>1.8.0</version>
     </dependency>
     ```

 *  Fix: Gracefully recover from a failure to rebuild the cache journal.
 *  Fix: Don't corrupt cache entries when a cache entry is evicted while it is
    being updated.
 *  Fix: Make logging more consistent throughout OkHttp.
 *  Fix: Log plaintext bodies only. This uses simple heuristics to differentiate
    text from other data.
 *  Fix: Recover from `REFUSED_STREAM` errors in HTTP/2. This should improve
    interoperability with Nginx 1.10.0, which [refuses][nginx_959] streams
    created before HTTP/2 settings have been acknowledged.
 *  Fix: Improve recovery from failed routes.
 *  Fix: Accommodate tunneling proxies that close the connection after an auth
    challenge.
 *  Fix: Use the proxy authenticator when authenticating HTTP proxies. This
    regression was introduced in OkHttp 3.0.
 *  Fix: Fail fast if network interceptors transform the response body such that
    closing it doesn't also close the underlying stream. We had a bug where
    OkHttp would attempt to reuse a connection but couldn't because it was still
    held by a prior request.
 *  Fix: Ensure network interceptors always have access to the underlying
    connection.
 *  Fix: Use `X509TrustManagerExtensions` on Android 17+.
 *  Fix: Unblock waiting dispatchers on MockWebServer shutdown.


## Version 3.2.0

_2016-02-25_

 *  Fix: Change the certificate pinner to always build full chains. This
    prevents a potential crash when using certificate pinning with the Google
    Play Services security provider.
 *  Fix: Make IPv6 request lines consistent with Firefox and Chrome.
 *  Fix: Recover gracefully when trimming the response cache fails.
 *  New: Add multiple path segments using a single string in `HttpUrl.Builder`.
 *  New: Support SHA-256 pins in certificate pinner.


## Version 3.1.2

_2016-02-10_

 *  Fix: Don’t crash when finding the trust manager on Robolectric. We attempted
    to detect the host platform and got confused because Robolectric looks like
    Android but isn’t!
 *  Fix: Change `CertificatePinner` to skip sanitizing the certificate chain
    when no certificates were pinned. This avoids an SSL failure in insecure
    “trust everyone” configurations, such as when talking to a development
    HTTPS server that has a self-signed certificate.


## Version 3.1.1

_2016-02-07_

 *  Fix: Don't crash when finding the trust manager if the Play Services (GMS)
    security provider is installed.
 *  Fix: The previous release introduced a performance regression on Android,
    caused by looking up CA certificates. This is now fixed.


## Version 3.1.0

_2016-02-06_

 *  New: WebSockets now defer some writes. This should improve performance for
    some applications.
 *  New: Override `equals()` and `hashCode()` in our new cookie class. This
    class now defines equality by value rather than by reference.
 *  New: Handle 408 responses by retrying the request. This allows servers to
    direct clients to retry rather than failing permanently.
 *  New: Expose the framed protocol in `Connection`. Previously this would
    return the application-layer protocol (HTTP/1.1 or HTTP/1.0); now it always
    returns the wire-layer protocol (HTTP/2, SPDY/3.1, or HTTP/1.1).
 *  Fix: Permit the trusted CA root to be pinned by `CertificatePinner`.
 *  Fix: Silently ignore unknown HTTP/2 settings. Previously this would cause
    the entire connection to fail.
 *  Fix: Don’t crash on unexpected charsets in the logging interceptor.
 *  Fix: `OkHttpClient` is now non-final for the benefit of mocking frameworks.
    Mocking sophisticated classes like `OkHttpClient` is fragile and you
    shouldn’t do it. But if that’s how you want to live your life we won’t stand
    in your way!


## Version 3.0.1

_2016-01-14_

 *  Rollback OSGi support. This was causing library jars to include more classes
    than expected, which interfered with Gradle builds.


## Version 3.0.0

_2016-01-13_

This release commits to a stable 3.0 API. Read the 3.0.0-RC1 changes for advice
on upgrading from 2.x to 3.x.

 *  **The `Callback` interface now takes a `Call`**. This makes it easier to
    check if the call was canceled from within the callback. When migrating
    async calls to this new API, `Call` is now the first parameter for both
    `onResponse()` and `onFailure()`.
 *  Fix: handle multiple cookies in `JavaNetCookieJar` on Android.
 *  Fix: improve the default HTTP message in MockWebServer responses.
 *  Fix: don't leak file handles when a conditional GET throws.
 *  Fix: Use charset specified by the request body content type in OkHttp's
    logging interceptor.
 *  Fix: Don't eagerly release pools on cache hits.
 *  New: Make OkHttp OSGi ready.
 *  New: Add already-implemented interfaces Closeable and Flushable to the cache.


## Version 3.0.0-RC1

_2016-01-02_

OkHttp 3 is a major release focused on API simplicity and consistency. The API
changes are numerous but most are cosmetic. Applications should be able to
upgrade from the 2.x API to the 3.x API mechanically and without risk.

Because the release includes breaking API changes, we're changing the project's
package name from `com.squareup.okhttp` to `okhttp3`. This should make it
possible for large applications to migrate incrementally. The Maven group ID
is now `com.squareup.okhttp3`. For an explanation of this strategy, see Jake
Wharton's post, [Java Interoperability Policy for Major Version
Updates][major_versions].

This release obsoletes OkHttp 2.x, and all code that uses OkHttp's
`com.squareup.okhttp` package should upgrade to the `okhttp3` package. Libraries
that depend on OkHttp should upgrade quickly to prevent applications from being
stuck on the old version.

 *  **There is no longer a global singleton connection pool.** In OkHttp 2.x,
    all `OkHttpClient` instances shared a common connection pool by default.
    In OkHttp 3.x, each new `OkHttpClient` gets its own private connection pool.
    Applications should avoid creating many connection pools as doing so
    prevents connection reuse. Each connection pool holds its own set of
    connections alive so applications that have many pools also risk exhausting
    memory!

    The best practice in OkHttp 3 is to create a single OkHttpClient instance
    and share it throughout the application. Requests that needs a customized
    client should call `OkHttpClient.newBuilder()` on that shared instance.
    This allows customization without the drawbacks of separate connection
    pools.

 *  **OkHttpClient is now stateless.** In the 2.x API `OkHttpClient` had getters
    and setters. Internally each request was forced to make its own complete
    snapshot of the `OkHttpClient` instance to defend against racy configuration
    changes. In 3.x, `OkHttpClient` is now stateless and has a builder. Note
    that this class is not strictly immutable as it has stateful members like
    the connection pool and cache.

 *  **Get and Set prefixes are now avoided.** With ubiquitous builders
    throughout OkHttp these accessor prefixes aren't necessary. Previously
    OkHttp used _get_ and _set_ prefixes sporadically which make the API
    inconsistent and awkward to explore.

 *  **OkHttpClient now implements the new `Call.Factory` interface.** This
    interface will make your code easier to test. When you test code that makes
    HTTP requests, you can use this interface to replace the real `OkHttpClient`
    with your own mocks or fakes.

    The interface will also let you use OkHttp's API with another HTTP client's
    implementation. This is useful in sandboxed environments like Google App
    Engine.

 *  **OkHttp now does cookies.** We've replaced `java.net.CookieHandler` with
    a new interface, `CookieJar` and added our own `Cookie` model class. This
    new cookie follows the latest RFC and supports the same cookie attributes
    as modern web browsers.

 *  **Form and Multipart bodies are now modeled.** We've replaced the opaque
    `FormEncodingBuilder` with the more powerful `FormBody` and
    `FormBody.Builder` combo. Similarly we've upgraded `MultipartBuilder` into
    `MultipartBody`, `MultipartBody.Part`, and `MultipartBody.Builder`.

 *  **The Apache HTTP client and HttpURLConnection APIs are deprecated.** They
    continue to work as they always have, but we're moving everything to the new
    OkHttp 3 API. The `okhttp-apache` and `okhttp-urlconnection` modules should
    be only be used to accelerate a transition to OkHttp's request/response API.
    These deprecated modules will be dropped in an upcoming OkHttp 3.x release.

 *  **Canceling batches of calls is now the application's responsibility.**
    The API to cancel calls by tag has been removed and replaced with a more
    general mechanism. The dispatcher now exposes all in-flight calls via its
    `runningCalls()` and `queuedCalls()` methods. You can write code that
    selects calls by tag, host, or whatever, and invokes `Call.cancel()` on the
    ones that are no longer necessary.

 *  **OkHttp no longer uses the global `java.net.Authenticator` by default.**
    We've changed our `Authenticator` interface to authenticate web and proxy
    authentication failures through a single method. An adapter for the old
    authenticator is available in the `okhttp-urlconnection` module.

 *  Fix: Don't throw `IOException` on `ResponseBody.contentLength()` or `close()`.
 *  Fix: Never throw converting an `HttpUrl` to a `java.net.URI`. This changes
    the `uri()` method to handle malformed percent-escapes and characters
    forbidden by `URI`.
 *  Fix: When a connect times out, attempt an alternate route. Previously route
    selection was less efficient when differentiating failures.
 *  New: `Response.peekBody()` lets you access the response body without
    consuming it. This may be handy for interceptors!
 *  New: `HttpUrl.newBuilder()` resolves a link to a builder.
 *  New: Add the TLS version to the `Handshake`.
 *  New: Drop `Request.uri()` and `Request#urlString()`. Just use
    `Request.url().uri()` and `Request.url().toString()`.
 *  New: Add URL to HTTP response logging.
 *  New: Make `HttpUrl` the blessed URL method of `Request`.


## Version 2.x

[Change log](changelog_2x.md)


 [bom]: https://en.wikipedia.org/wiki/Byte_order_mark
 [conscrypt]: https://github.com/google/conscrypt/
 [conscrypt_dependency]: https://github.com/google/conscrypt/#download
 [grpc_http2]: https://github.com/grpc/grpc/blob/master/doc/PROTOCOL-HTTP2.md
 [https_server_sample]: https://github.com/lysine-dev/okhttp/blob/master/samples/guide/src/main/java/okhttp3/recipes/HttpsServer.java
 [jetty_8_252]: https://webtide.com/jetty-alpn-java-8u252/
 [junit_5_rules]: https://junit.org/junit5/docs/current/user-guide/#migrating-from-junit4-rulesupport
 [major_versions]: https://jakewharton.com/java-interoperability-policy-for-major-version-updates/
 [maven_provided]: https://maven.apache.org/guides/introduction/introduction-to-dependency-mechanism.html
 [nginx_959]: https://trac.nginx.org/nginx/ticket/959
 [obsolete_apache_client]: https://gist.github.com/swankjesse/09721f72039e3a46cf50f94323deb82d
 [obsolete_url_factory]: https://gist.github.com/swankjesse/dd91c0a8854e1559b00f5fc9c7bfae70
 [okhttp_idling_resource]: https://github.com/JakeWharton/okhttp-idling-resource
 [public_suffix]: https://publicsuffix.org/
 [remove_cbc_ecdsa]: https://developers.google.com/web/updates/2016/12/chrome-56-deprecations#remove_cbc-mode_ecdsa_ciphers_in_tls
 [require_android_5]: https://code.cash.app/okhttp-3-13-requires-android-5
 [tls_configuration_history]: https://lysine.dev/okhttp/tls_configuration_history/
 [upgrading_to_okhttp_4]: https://lysine.dev/okhttp/upgrading_to_okhttp_4/
