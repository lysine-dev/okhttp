OkHttp Brotli Implementation
============================

This module is an implementation of [Brotli][1] compression.
It enables Brotli support in addition to tranparent Gzip support,
provided Accept-Encoding is not set previously.  Modern web servers
must choose to return Brotli responses.  n.b. It is not used for
sending requests.

```java
OkHttpClient client = new OkHttpClient.Builder()
  .addInterceptor(BrotliInterceptor.INSTANCE)
  .build();
```

```kotlin
implementation("com.squareup.okhttp3:okhttp-brotli:5.5.0")
```

 [1]: https://github.com/google/brotli
