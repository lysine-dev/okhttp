OkHttp Zstandard (zstd) Integration
===================================

This module enables [Zstandard (zstd)][1] response compression in addition to Gzip, as long as
the `Accept-Encoding` header is not otherwise set. Web servers must be configured to return zstd
responses.

Note that zstd is not used for sending requests.

```java
OkHttpClient client = new OkHttpClient.Builder()
  .addInterceptor(CompressionInterceptor(Zstd, Gzip))
  .build();
```

```kotlin
implementation("com.squareup.okhttp3:okhttp-zstd:5.5.0")
```

 [1]: https://github.com/facebook/zstd
