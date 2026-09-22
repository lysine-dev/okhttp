MockWebServer for JUnit 5
=========================

This module integrates mockwebserver3.MockWebServer with JUnit 5.

To use, first add this library as a test dependency:

```
testImplementation("com.squareup.okhttp3:mockwebserver3-junit5:5.5.0")
```

Annotate fields in test classes with `@StartStop`. The server will be started and shut down
automatically.

```
class MyTest {

  @StartStop
  public final MockWebServer server = new MockWebServer();

  @Test
  void test() {
    ...
  }
}
```

Requirements
------------

MockWebServer's JUnit 5 integration works on Android 7.0+ (API level 24+) and Java 8+. Note that
this is above OkHttp's core requirements.

