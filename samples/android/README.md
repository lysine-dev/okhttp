# Android ECH sample

This instrumentation test makes HTTPS requests with Encrypted Client Hello (ECH). The sample keeps
OkHttp's API 21 minimum and only enables ECH on Android 17 (API 37) or newer.

The important pieces are:

* [`EchClient.kt`](src/androidTest/kotlin/okhttp3/sample/ech/EchClient.kt), which configures OkHttp
  with either Android's resolver or DNS over HTTPS, makes a sample request, and tests the result.
* [`network_security_config.xml`](src/main/res/xml/network_security_config.xml), which opts the
  sample domain into enabled ECH. Older Android versions ignore the unsupported element.

Run the test from the repository root:

```shell
./gradlew :samples:android:connectedDebugAndroidTest
```

The test checks that Cloudflare reports `sni=encrypted`.
