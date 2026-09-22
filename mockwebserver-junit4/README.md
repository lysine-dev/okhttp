MockWebServer for JUnit 4
=========================

This module integrates mockwebserver3.MockWebServer with JUnit 4.

To use, first add this library as a test dependency:

```
testImplementation("com.squareup.okhttp3:mockwebserver3-junit4:5.5.0")
```

Then in tests annotated `@org.junit.Test`, you may declare a field with the `@Rule` annotation:

```
@Rule public final MockWebServerRule serverRule = new MockWebServerRule();
```

The `serverRule` field has a `server` field. It is an instance of `MockWebServer`. That instance
will be shut down automatically after the test runs.

For Kotlin, the `@JvmField` annotation is also necessary:

```
@JvmField @Rule val serverRule = MockWebServerRule()
```
