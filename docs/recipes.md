---
title: Recipes
description: A collection of common/useful code examples for Kotlin and Java
---


# Recipes

We've written some recipes that demonstrate how to solve common problems with OkHttp. Read through them to learn about how everything works together. Cut-and-paste these examples freely; that's what they're for.

### Synchronous Get ([.kt][SynchronousGetKotlin], [.java][SynchronousGetJava])

Download a file, print its headers, and print its response body as a string.

The `string()` method on response body is convenient and efficient for small documents. But if the response body is large (greater than 1 MiB), avoid `string()` because it will load the entire document into memory. In that case, prefer to process the body as a stream.

=== ":material-language-kotlin: Kotlin"
    ```kotlin
      private val client = OkHttpClient()

      fun run() {
        val request = Request.Builder()
            .url("https://publicobject.com/helloworld.txt")
            .build()

        client.newCall(request).execute().use { response ->
          if (!response.isSuccessful) throw IOException("Unexpected code $response")

          for ((name, value) in response.headers) {
            println("$name: $value")
          }

          println(response.body!!.string())
        }
      }
    ```
=== ":material-language-java: Java"
    ```java
      private final OkHttpClient client = new OkHttpClient();

      public void run() throws Exception {
        Request request = new Request.Builder()
            .url("https://publicobject.com/helloworld.txt")
            .build();

        try (Response response = client.newCall(request).execute()) {
          if (!response.isSuccessful()) throw new IOException("Unexpected code " + response);

          Headers responseHeaders = response.headers();
          for (int i = 0; i < responseHeaders.size(); i++) {
            System.out.println(responseHeaders.name(i) + ": " + responseHeaders.value(i));
          }

          System.out.println(response.body().string());
        }
      }
    ```

### Asynchronous Get ([.kt][AsynchronousGetKotlin], [.java][AsynchronousGetJava])

Download a file on a worker thread, and get called back when the response is readable. The callback is made after the response headers are ready. Reading the response body may still block. OkHttp doesn't currently offer asynchronous APIs to receive a response body in parts.

=== ":material-language-kotlin: Kotlin"
    ```kotlin
      private val client = OkHttpClient()

      fun run() {
        val request = Request.Builder()
            .url("http://publicobject.com/helloworld.txt")
            .build()

        client.newCall(request).enqueue(object : Callback {
          override fun onFailure(call: Call, e: IOException) {
            e.printStackTrace()
          }

          override fun onResponse(call: Call, response: Response) {
            response.use {
              if (!response.isSuccessful) throw IOException("Unexpected code $response")

              for ((name, value) in response.headers) {
                println("$name: $value")
              }

              println(response.body!!.string())
            }
          }
        })
      }
    ```
=== ":material-language-java: Java"
    ```java
      private final OkHttpClient client = new OkHttpClient();

      public void run() throws Exception {
        Request request = new Request.Builder()
            .url("http://publicobject.com/helloworld.txt")
            .build();

        client.newCall(request).enqueue(new Callback() {
          @Override public void onFailure(Call call, IOException e) {
            e.printStackTrace();
          }

          @Override public void onResponse(Call call, Response response) throws IOException {
            try (ResponseBody responseBody = response.body()) {
              if (!response.isSuccessful()) throw new IOException("Unexpected code " + response);

              Headers responseHeaders = response.headers();
              for (int i = 0, size = responseHeaders.size(); i < size; i++) {
                System.out.println(responseHeaders.name(i) + ": " + responseHeaders.value(i));
              }

              System.out.println(responseBody.string());
            }
          }
        });
      }
    ```

### Accessing Headers ([.kt][AccessHeadersKotlin], [.java][AccessHeadersJava])

Typically HTTP headers work like a `Map<String, String>`: each field has one value or none. But some headers permit multiple values, like Guava's [Multimap](https://guava.dev/releases/23.0/api/docs/com/google/common/collect/Multimap.html). For example, it's legal and common for an HTTP response to supply multiple `Vary` headers. OkHttp's APIs attempt to make both cases comfortable.

When writing request headers, use `header(name, value)` to set the only occurrence of `name` to `value`. If there are existing values, they will be removed before the new value is added. Use `addHeader(name, value)` to add a header without removing the headers already present.

When reading response a header, use `header(name)` to return the _last_ occurrence of the named value. Usually this is also the only occurrence! If no value is present, `header(name)` will return null. To read all of a field's values as a list, use `headers(name)`.

To visit all headers, use the `Headers` class which supports access by index.

=== ":material-language-kotlin: Kotlin"
    ```kotlin
      private val client = OkHttpClient()

      fun run() {
        val request = Request.Builder()
            .url("https://api.github.com/repos/lysine-dev/okhttp/issues")
            .header("User-Agent", "OkHttp Headers.java")
            .addHeader("Accept", "application/json; q=0.5")
            .addHeader("Accept", "application/vnd.github.v3+json")
            .build()

        client.newCall(request).execute().use { response ->
          if (!response.isSuccessful) throw IOException("Unexpected code $response")

          println("Server: ${response.header("Server")}")
          println("Date: ${response.header("Date")}")
          println("Vary: ${response.headers("Vary")}")
        }
      }
    ```

=== ":material-language-java: Java"
    ```java
      private final OkHttpClient client = new OkHttpClient();

      public void run() throws Exception {
        Request request = new Request.Builder()
            .url("https://api.github.com/repos/lysine-dev/okhttp/issues")
            .header("User-Agent", "OkHttp Headers.java")
            .addHeader("Accept", "application/json; q=0.5")
            .addHeader("Accept", "application/vnd.github.v3+json")
            .build();

        try (Response response = client.newCall(request).execute()) {
          if (!response.isSuccessful()) throw new IOException("Unexpected code " + response);

          System.out.println("Server: " + response.header("Server"));
          System.out.println("Date: " + response.header("Date"));
          System.out.println("Vary: " + response.headers("Vary"));
        }
      }
    ```

### Posting a String ([.kt][PostStringKotlin], [.java][PostStringJava])

Use an HTTP POST to send a request body to a service. This example posts a markdown document to a web service that renders markdown as HTML. Because the entire request body is in memory simultaneously, avoid posting large (greater than 1 MiB) documents using this API.

=== ":material-language-kotlin: Kotlin"
    ```kotlin
      private val client = OkHttpClient()

      fun run() {
        val postBody = """
            |Releases
            |--------
            |
            | * _1.0_ May 6, 2013
            | * _1.1_ June 15, 2013
            | * _1.2_ August 11, 2013
            |""".trimMargin()

        val request = Request.Builder()
            .url("https://api.github.com/markdown/raw")
            .post(postBody.toRequestBody(MEDIA_TYPE_MARKDOWN))
            .build()

        client.newCall(request).execute().use { response ->
          if (!response.isSuccessful) throw IOException("Unexpected code $response")

          println(response.body!!.string())
        }
      }

      companion object {
        val MEDIA_TYPE_MARKDOWN = "text/x-markdown; charset=utf-8".toMediaType()
      }
    ```
=== ":material-language-java: Java"
    ```java
      public static final MediaType MEDIA_TYPE_MARKDOWN
          = MediaType.parse("text/x-markdown; charset=utf-8");

      private final OkHttpClient client = new OkHttpClient();

      public void run() throws Exception {
        String postBody = ""
            + "Releases\n"
            + "--------\n"
            + "\n"
            + " * _1.0_ May 6, 2013\n"
            + " * _1.1_ June 15, 2013\n"
            + " * _1.2_ August 11, 2013\n";

        Request request = new Request.Builder()
            .url("https://api.github.com/markdown/raw")
            .post(RequestBody.create(MEDIA_TYPE_MARKDOWN, postBody))
            .build();

        try (Response response = client.newCall(request).execute()) {
          if (!response.isSuccessful()) throw new IOException("Unexpected code " + response);

          System.out.println(response.body().string());
        }
      }
    ```

### Post Streaming ([.kt][PostStreamingKotlin], [.java][PostStreamingJava])

Here we `POST` a request body as a stream. The content of this request body is being generated as it's being written. This example streams directly into the [Okio](https://github.com/lysine-dev/okio) buffered sink. Your programs may prefer an `OutputStream`, which you can get from `BufferedSink.outputStream()`.

=== ":material-language-kotlin: Kotlin"
    ```kotlin
      private val client = OkHttpClient()

      fun run() {
        val requestBody = object : RequestBody() {
          override fun contentType() = MEDIA_TYPE_MARKDOWN

          override fun writeTo(sink: BufferedSink) {
            sink.writeUtf8("Numbers\n")
            sink.writeUtf8("-------\n")
            for (i in 2..997) {
              sink.writeUtf8(String.format(" * $i = ${factor(i)}\n"))
            }
          }

          private fun factor(n: Int): String {
            for (i in 2 until n) {
              val x = n / i
              if (x * i == n) return "${factor(x)} × $i"
            }
            return n.toString()
          }
        }

        val request = Request.Builder()
            .url("https://api.github.com/markdown/raw")
            .post(requestBody)
            .build()

        client.newCall(request).execute().use { response ->
          if (!response.isSuccessful) throw IOException("Unexpected code $response")

          println(response.body!!.string())
        }
      }

      companion object {
        val MEDIA_TYPE_MARKDOWN = "text/x-markdown; charset=utf-8".toMediaType()
      }
    ```
=== ":material-language-java: Java"
    ```java
      public static final MediaType MEDIA_TYPE_MARKDOWN
          = MediaType.parse("text/x-markdown; charset=utf-8");

      private final OkHttpClient client = new OkHttpClient();

      public void run() throws Exception {
        RequestBody requestBody = new RequestBody() {
          @Override public MediaType contentType() {
            return MEDIA_TYPE_MARKDOWN;
          }

          @Override public void writeTo(BufferedSink sink) throws IOException {
            sink.writeUtf8("Numbers\n");
            sink.writeUtf8("-------\n");
            for (int i = 2; i <= 997; i++) {
              sink.writeUtf8(String.format(" * %s = %s\n", i, factor(i)));
            }
          }

          private String factor(int n) {
            for (int i = 2; i < n; i++) {
              int x = n / i;
              if (x * i == n) return factor(x) + " × " + i;
            }
            return Integer.toString(n);
          }
        };

        Request request = new Request.Builder()
            .url("https://api.github.com/markdown/raw")
            .post(requestBody)
            .build();

        try (Response response = client.newCall(request).execute()) {
          if (!response.isSuccessful()) throw new IOException("Unexpected code " + response);

          System.out.println(response.body().string());
        }
      }
    ```

### Posting a File ([.kt][PostFileKotlin], [.java][PostFileJava])

It's easy to use a file as a request body.

=== ":material-language-kotlin: Kotlin"
    ```kotlin
      private val client = OkHttpClient()

      fun run() {
        val file = File("README.md")

        val request = Request.Builder()
            .url("https://api.github.com/markdown/raw")
            .post(file.asRequestBody(MEDIA_TYPE_MARKDOWN))
            .build()

        client.newCall(request).execute().use { response ->
          if (!response.isSuccessful) throw IOException("Unexpected code $response")

          println(response.body!!.string())
        }
      }

      companion object {
        val MEDIA_TYPE_MARKDOWN = "text/x-markdown; charset=utf-8".toMediaType()
      }
    ```
=== ":material-language-java: Java"
    ```java
      public static final MediaType MEDIA_TYPE_MARKDOWN
          = MediaType.parse("text/x-markdown; charset=utf-8");

      private final OkHttpClient client = new OkHttpClient();

      public void run() throws Exception {
        File file = new File("README.md");

        Request request = new Request.Builder()
            .url("https://api.github.com/markdown/raw")
            .post(RequestBody.create(MEDIA_TYPE_MARKDOWN, file))
            .build();

        try (Response response = client.newCall(request).execute()) {
          if (!response.isSuccessful()) throw new IOException("Unexpected code " + response);

          System.out.println(response.body().string());
        }
      }
    ```

### Posting form parameters ([.kt][PostFormKotlin], [.java][PostFormJava])

Use `FormBody.Builder` to build a request body that works like an HTML `<form>` tag. Names and values will be encoded using an HTML-compatible form URL encoding.

=== ":material-language-kotlin: Kotlin"
    ```kotlin
      private val client = OkHttpClient()

      fun run() {
        val formBody = FormBody.Builder()
            .add("search", "Jurassic Park")
            .build()
        val request = Request.Builder()
            .url("https://en.wikipedia.org/w/index.php")
            .post(formBody)
            .build()

        client.newCall(request).execute().use { response ->
          if (!response.isSuccessful) throw IOException("Unexpected code $response")

          println(response.body!!.string())
        }
      }
    ```
=== ":material-language-java: Java"
    ```java
      private final OkHttpClient client = new OkHttpClient();

      public void run() throws Exception {
        RequestBody formBody = new FormBody.Builder()
            .add("search", "Jurassic Park")
            .build();
        Request request = new Request.Builder()
            .url("https://en.wikipedia.org/w/index.php")
            .post(formBody)
            .build();

        try (Response response = client.newCall(request).execute()) {
          if (!response.isSuccessful()) throw new IOException("Unexpected code " + response);

          System.out.println(response.body().string());
        }
      }
    ```

### Posting a multipart request ([.kt][PostMultipartKotlin], [.java][PostMultipartJava])

`MultipartBody.Builder` can build sophisticated request bodies compatible with HTML file upload forms. Each part of a multipart request body is itself a request body, and can define its own headers. If present, these headers should describe the part body, such as its `Content-Disposition`. The `Content-Length` and `Content-Type` headers are added automatically if they're available.

=== ":material-language-kotlin: Kotlin"
    ```kotlin
      private val client = OkHttpClient()

      fun run() {
        // Use the imgur image upload API as documented at https://api.imgur.com/endpoints/image
        val requestBody = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart("title", "Square Logo")
            .addFormDataPart("image", "logo-square.png",
                File("docs/images/logo-square.png").asRequestBody(MEDIA_TYPE_PNG))
            .build()

        val request = Request.Builder()
            .header("Authorization", "Client-ID $IMGUR_CLIENT_ID")
            .url("https://api.imgur.com/3/image")
            .post(requestBody)
            .build()

        client.newCall(request).execute().use { response ->
          if (!response.isSuccessful) throw IOException("Unexpected code $response")

          println(response.body!!.string())
        }
      }

      companion object {
        /**
         * The imgur client ID for OkHttp recipes. If you're using imgur for anything other than running
         * these examples, please request your own client ID! https://api.imgur.com/oauth2
         */
        private val IMGUR_CLIENT_ID = "9199fdef135c122"
        private val MEDIA_TYPE_PNG = "image/png".toMediaType()
      }
    ```
=== ":material-language-java: Java"
    ```java
      /**
       * The imgur client ID for OkHttp recipes. If you're using imgur for anything other than running
       * these examples, please request your own client ID! https://api.imgur.com/oauth2
       */
      private static final String IMGUR_CLIENT_ID = "...";
      private static final MediaType MEDIA_TYPE_PNG = MediaType.parse("image/png");

      private final OkHttpClient client = new OkHttpClient();

      public void run() throws Exception {
        // Use the imgur image upload API as documented at https://api.imgur.com/endpoints/image
        RequestBody requestBody = new MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart("title", "Square Logo")
            .addFormDataPart("image", "logo-square.png",
                RequestBody.create(MEDIA_TYPE_PNG, new File("website/static/logo-square.png")))
            .build();

        Request request = new Request.Builder()
            .header("Authorization", "Client-ID " + IMGUR_CLIENT_ID)
            .url("https://api.imgur.com/3/image")
            .post(requestBody)
            .build();

        try (Response response = client.newCall(request).execute()) {
          if (!response.isSuccessful()) throw new IOException("Unexpected code " + response);

          System.out.println(response.body().string());
        }
      }
    ```

### Parse a JSON Response With Moshi ([.kt][ParseResponseWithMoshiKotlin], [.java][ParseResponseWithMoshiJava])

[Moshi](https://github.com/square/moshi) is a handy API for converting between JSON and Java objects. Here we're using it to decode a JSON response from a GitHub API.

Note that `ResponseBody.charStream()` uses the `Content-Type` response header to select which charset to use when decoding the response body. It defaults to `UTF-8` if no charset is specified.

=== ":material-language-kotlin: Kotlin"
    ```kotlin
      private val client = OkHttpClient()
      private val moshi = Moshi.Builder().build()
      private val gistJsonAdapter = moshi.adapter(Gist::class.java)

      fun run() {
        val request = Request.Builder()
            .url("https://api.github.com/gists/c2a7c39532239ff261be")
            .build()
        client.newCall(request).execute().use { response ->
          if (!response.isSuccessful) throw IOException("Unexpected code $response")

          val gist = gistJsonAdapter.fromJson(response.body!!.source())

          for ((key, value) in gist!!.files!!) {
            println(key)
            println(value.content)
          }
        }
      }

      @JsonClass(generateAdapter = true)
      data class Gist(var files: Map<String, GistFile>?)

      @JsonClass(generateAdapter = true)
      data class GistFile(var content: String?)
    ```
=== ":material-language-java: Java"
    ```java
      private final OkHttpClient client = new OkHttpClient();
      private final Moshi moshi = new Moshi.Builder().build();
      private final JsonAdapter<Gist> gistJsonAdapter = moshi.adapter(Gist.class);

      public void run() throws Exception {
        Request request = new Request.Builder()
            .url("https://api.github.com/gists/c2a7c39532239ff261be")
            .build();
        try (Response response = client.newCall(request).execute()) {
          if (!response.isSuccessful()) throw new IOException("Unexpected code " + response);

          Gist gist = gistJsonAdapter.fromJson(response.body().source());

          for (Map.Entry<String, GistFile> entry : gist.files.entrySet()) {
            System.out.println(entry.getKey());
            System.out.println(entry.getValue().content);
          }
        }
      }

      static class Gist {
        Map<String, GistFile> files;
      }

      static class GistFile {
        String content;
      }
    ```

### Response Caching ([.kt][CacheResponseKotlin], [.java][CacheResponseJava])

To cache responses, you'll need a cache directory that you can read and write to, and a limit on the cache's size. The cache directory should be private, and untrusted applications should not be able to read its contents!

It is an error to have multiple caches accessing the same cache directory simultaneously. Most applications should call `new OkHttpClient()` exactly once, configure it with their cache, and use that same instance everywhere. Otherwise the two cache instances will stomp on each other, corrupt the response cache, and possibly crash your program.

Response caching uses HTTP headers for all configuration. You can add request headers like `Cache-Control: max-stale=3600` and OkHttp's cache will honor them. Your webserver configures how long responses are cached with its own response headers, like `Cache-Control: max-age=9600`. There are cache headers to force a cached response, force a network response, or force the network response to be validated with a conditional GET.

=== ":material-language-kotlin: Kotlin"
    ```kotlin
      private val client: OkHttpClient = OkHttpClient.Builder()
          .cache(Cache(
              directory = cacheDirectory,
              maxSize = 10L * 1024L * 1024L // 10 MiB
          ))
          .build()

      fun run() {
        val request = Request.Builder()
            .url("http://publicobject.com/helloworld.txt")
            .build()

        val response1Body = client.newCall(request).execute().use {
          if (!it.isSuccessful) throw IOException("Unexpected code $it")

          println("Response 1 response:          $it")
          println("Response 1 cache response:    ${it.cacheResponse}")
          println("Response 1 network response:  ${it.networkResponse}")
          return@use it.body!!.string()
        }

        val response2Body = client.newCall(request).execute().use {
          if (!it.isSuccessful) throw IOException("Unexpected code $it")

          println("Response 2 response:          $it")
          println("Response 2 cache response:    ${it.cacheResponse}")
          println("Response 2 network response:  ${it.networkResponse}")
          return@use it.body!!.string()
        }

        println("Response 2 equals Response 1? " + (response1Body == response2Body))
      }
    ```
=== ":material-language-java: Java"
    ```java
      private final OkHttpClient client;

      public CacheResponse(File cacheDirectory) throws Exception {
        int cacheSize = 10 * 1024 * 1024; // 10 MiB
        Cache cache = new Cache(cacheDirectory, cacheSize);

        client = new OkHttpClient.Builder()
            .cache(cache)
            .build();
      }

      public void run() throws Exception {
        Request request = new Request.Builder()
            .url("http://publicobject.com/helloworld.txt")
            .build();

        String response1Body;
        try (Response response1 = client.newCall(request).execute()) {
          if (!response1.isSuccessful()) throw new IOException("Unexpected code " + response1);

          response1Body = response1.body().string();
          System.out.println("Response 1 response:          " + response1);
          System.out.println("Response 1 cache response:    " + response1.cacheResponse());
          System.out.println("Response 1 network response:  " + response1.networkResponse());
        }

        String response2Body;
        try (Response response2 = client.newCall(request).execute()) {
          if (!response2.isSuccessful()) throw new IOException("Unexpected code " + response2);

          response2Body = response2.body().string();
          System.out.println("Response 2 response:          " + response2);
          System.out.println("Response 2 cache response:    " + response2.cacheResponse());
          System.out.println("Response 2 network response:  " + response2.networkResponse());
        }

        System.out.println("Response 2 equals Response 1? " + response1Body.equals(response2Body));
      }
    ```

To prevent a response from using the cache, use [`CacheControl.FORCE_NETWORK`](https://lysine.dev/okhttp/5.x/okhttp/okhttp3/-cache-control/-companion/-f-o-r-c-e_-n-e-t-w-o-r-k). To prevent it from using the network, use [`CacheControl.FORCE_CACHE`](https://lysine.dev/okhttp/5.x/okhttp/okhttp3/-cache-control/-companion/-f-o-r-c-e_-c-a-c-h-e). Be warned: if you use `FORCE_CACHE` and the response requires the network, OkHttp will return a `504 Unsatisfiable Request` response.


### Canceling a Call ([.kt][CancelCallKotlin], [.java][CancelCallJava])

Use `Call.cancel()` to stop an ongoing call immediately. If a thread is currently writing a request or reading a response, it will receive an `IOException`. Use this to conserve the network when a call is no longer necessary; for example when your user navigates away from an application. Both synchronous and asynchronous calls can be canceled.

=== ":material-language-kotlin: Kotlin"
    ```kotlin
      private val executor = Executors.newScheduledThreadPool(1)
      private val client = OkHttpClient()

      fun run() {
        val request = Request.Builder()
            .url("http://httpbin.org/delay/2") // This URL is served with a 2 second delay.
            .build()

        val startNanos = System.nanoTime()
        val call = client.newCall(request)

        // Schedule a job to cancel the call in 1 second.
        executor.schedule({
          System.out.printf("%.2f Canceling call.%n", (System.nanoTime() - startNanos) / 1e9f)
          call.cancel()
          System.out.printf("%.2f Canceled call.%n", (System.nanoTime() - startNanos) / 1e9f)
        }, 1, TimeUnit.SECONDS)

        System.out.printf("%.2f Executing call.%n", (System.nanoTime() - startNanos) / 1e9f)
        try {
          call.execute().use { response ->
            System.out.printf("%.2f Call was expected to fail, but completed: %s%n",
                (System.nanoTime() - startNanos) / 1e9f, response)
          }
        } catch (e: IOException) {
          System.out.printf("%.2f Call failed as expected: %s%n",
              (System.nanoTime() - startNanos) / 1e9f, e)
        }
      }
    ```
=== ":material-language-java: Java"
    ```java
      private final ScheduledExecutorService executor = Executors.newScheduledThreadPool(1);
      private final OkHttpClient client = new OkHttpClient();

      public void run() throws Exception {
        Request request = new Request.Builder()
            .url("http://httpbin.org/delay/2") // This URL is served with a 2 second delay.
            .build();

        final long startNanos = System.nanoTime();
        final Call call = client.newCall(request);

        // Schedule a job to cancel the call in 1 second.
        executor.schedule(new Runnable() {
          @Override public void run() {
            System.out.printf("%.2f Canceling call.%n", (System.nanoTime() - startNanos) / 1e9f);
            call.cancel();
            System.out.printf("%.2f Canceled call.%n", (System.nanoTime() - startNanos) / 1e9f);
          }
        }, 1, TimeUnit.SECONDS);

        System.out.printf("%.2f Executing call.%n", (System.nanoTime() - startNanos) / 1e9f);
        try (Response response = call.execute()) {
          System.out.printf("%.2f Call was expected to fail, but completed: %s%n",
              (System.nanoTime() - startNanos) / 1e9f, response);
        } catch (IOException e) {
          System.out.printf("%.2f Call failed as expected: %s%n",
              (System.nanoTime() - startNanos) / 1e9f, e);
        }
      }
    ```

### Timeouts ([.kt][ConfigureTimeoutsKotlin], [.java][ConfigureTimeoutsJava])

Use timeouts to fail a call when its peer is unreachable. Network partitions can be due to client connectivity problems, server availability problems, or anything between. OkHttp supports connect, write, read, and full call timeouts.

=== ":material-language-kotlin: Kotlin"
    ```kotlin
      private val client: OkHttpClient = OkHttpClient.Builder()
          .connectTimeout(5, TimeUnit.SECONDS)
          .writeTimeout(5, TimeUnit.SECONDS)
          .readTimeout(5, TimeUnit.SECONDS)
          .callTimeout(10, TimeUnit.SECONDS)
          .build()

      fun run() {
        val request = Request.Builder()
            .url("http://httpbin.org/delay/2") // This URL is served with a 2 second delay.
            .build()

        client.newCall(request).execute().use { response ->
          println("Response completed: $response")
        }
      }
    ```
=== ":material-language-java: Java"
    ```java
      private final OkHttpClient client;

      public ConfigureTimeouts() throws Exception {
        client = new OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .writeTimeout(10, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .build();
      }

      public void run() throws Exception {
        Request request = new Request.Builder()
            .url("http://httpbin.org/delay/2") // This URL is served with a 2 second delay.
            .build();

        try (Response response = client.newCall(request).execute()) {
          System.out.println("Response completed: " + response);
        }
      }
    ```

### Per-call Configuration ([.kt][PerCallSettingsKotlin], [.java][PerCallSettingsJava])

All the HTTP client configuration lives in `OkHttpClient` including proxy settings, timeouts, and caches. When you need to change the configuration of a single call, call `OkHttpClient.newBuilder()`. This returns a builder that shares the same connection pool, dispatcher, and configuration with the original client. In the example below, we make one request with a 500 ms timeout and another with a 3000 ms timeout.

=== ":material-language-kotlin: Kotlin"
    ```kotlin
      private val client = OkHttpClient()

      fun run() {
        val request = Request.Builder()
            .url("http://httpbin.org/delay/1") // This URL is served with a 1 second delay.
            .build()

        // Copy to customize OkHttp for this request.
        val client1 = client.newBuilder()
            .readTimeout(500, TimeUnit.MILLISECONDS)
            .build()
        try {
          client1.newCall(request).execute().use { response ->
            println("Response 1 succeeded: $response")
          }
        } catch (e: IOException) {
          println("Response 1 failed: $e")
        }

        // Copy to customize OkHttp for this request.
        val client2 = client.newBuilder()
            .readTimeout(3000, TimeUnit.MILLISECONDS)
            .build()
        try {
          client2.newCall(request).execute().use { response ->
            println("Response 2 succeeded: $response")
          }
        } catch (e: IOException) {
          println("Response 2 failed: $e")
        }
      }
    ```
=== ":material-language-java: Java"
    ```java
      private final OkHttpClient client = new OkHttpClient();

      public void run() throws Exception {
        Request request = new Request.Builder()
            .url("http://httpbin.org/delay/1") // This URL is served with a 1 second delay.
            .build();

        // Copy to customize OkHttp for this request.
        OkHttpClient client1 = client.newBuilder()
            .readTimeout(500, TimeUnit.MILLISECONDS)
            .build();
        try (Response response = client1.newCall(request).execute()) {
          System.out.println("Response 1 succeeded: " + response);
        } catch (IOException e) {
          System.out.println("Response 1 failed: " + e);
        }

        // Copy to customize OkHttp for this request.
        OkHttpClient client2 = client.newBuilder()
            .readTimeout(3000, TimeUnit.MILLISECONDS)
            .build();
        try (Response response = client2.newCall(request).execute()) {
          System.out.println("Response 2 succeeded: " + response);
        } catch (IOException e) {
          System.out.println("Response 2 failed: " + e);
        }
      }
    ```

### Handling authentication ([.kt][AuthenticateKotlin], [.java][AuthenticateJava])

OkHttp can automatically retry unauthenticated requests. When a response is `401 Not Authorized`, an `Authenticator` is asked to supply credentials. Implementations should build a new request that includes the missing credentials. If no credentials are available, return null to skip the retry.

Use `Response.challenges()` to get the schemes and realms of any authentication challenges. When fulfilling a `Basic` challenge, use `Credentials.basic(username, password)` to encode the request header.

=== ":material-language-kotlin: Kotlin"
    ```kotlin
      private val client = OkHttpClient.Builder()
          .authenticator(object : Authenticator {
            @Throws(IOException::class)
            override fun authenticate(route: Route?, response: Response): Request? {
              if (response.request.header("Authorization") != null) {
                return null // Give up, we've already attempted to authenticate.
              }

              println("Authenticating for response: $response")
              println("Challenges: ${response.challenges()}")
              val credential = Credentials.basic("jesse", "password1")
              return response.request.newBuilder()
                  .header("Authorization", credential)
                  .build()
            }
          })
          .build()

      fun run() {
        val request = Request.Builder()
            .url("http://publicobject.com/secrets/hellosecret.txt")
            .build()
      }
    ```

    To avoid making many retries when authentication isn't working, you can return null to give up. For example, you may want to skip the retry when these exact credentials have already been attempted:

    ```kotlin
    if (credential == response.request.header("Authorization")) {
      return null // If we already failed with these credentials, don't retry.
     }
    ```

    You may also skip the retry when you’ve hit an application-defined attempt limit:

    ```kotlin
    if (response.responseCount >= 3) {
      return null // If we've failed 3 times, give up.
    }
    ```

    This above code relies on this `responseCount` extension val:

    ```kotlin
    val Response.responseCount: Int
      get() = generateSequence(this) { it.priorResponse }.count()
    ```
=== ":material-language-java: Java"
    ```java
      private final OkHttpClient client;

      public Authenticate() {
        client = new OkHttpClient.Builder()
            .authenticator(new Authenticator() {
              @Override public Request authenticate(Route route, Response response) throws IOException {
                if (response.request().header("Authorization") != null) {
                  return null; // Give up, we've already attempted to authenticate.
                }

                System.out.println("Authenticating for response: " + response);
                System.out.println("Challenges: " + response.challenges());
                String credential = Credentials.basic("jesse", "password1");
                return response.request().newBuilder()
                    .header("Authorization", credential)
                    .build();
              }
            })
            .build();
      }

      public void run() throws Exception {
        Request request = new Request.Builder()
            .url("http://publicobject.com/secrets/hellosecret.txt")
            .build();

        try (Response response = client.newCall(request).execute()) {
          if (!response.isSuccessful()) throw new IOException("Unexpected code " + response);

          System.out.println(response.body().string());
        }
      }
    ```

    To avoid making many retries when authentication isn't working, you can return null to give up. For example, you may want to skip the retry when these exact credentials have already been attempted:

    ```java
      if (credential.equals(response.request().header("Authorization"))) {
        return null; // If we already failed with these credentials, don't retry.
       }
    ```

    You may also skip the retry when you’ve hit an application-defined attempt limit:

    ```java
      if (responseCount(response) >= 3) {
        return null; // If we've failed 3 times, give up.
      }
    ```

    This above code relies on this `responseCount()` method:

    ```java
      private int responseCount(Response response) {
        int result = 1;
        while ((response = response.priorResponse()) != null) {
          result++;
        }
        return result;
      }
    ```

### Upload Progress ([.kt][UploadProgressKotlin], [.java][UploadProgressJava])

Upload a file to a server (for example, Imgur) and report progress as the request body is being written. You can implement a ProgressListener to receive updates and wrap the original request body with ProgressRequestBody. This allows you to monitor how many bytes have been uploaded and calculate the percentage of completion.

=== ":material-language-kotlin: Kotlin"
    ```kotlin
      class UploadProgress {

        companion object {
          private const val IMGUR_CLIENT_ID = "9199fdef135c122"
          private val MEDIA_TYPE_PNG = "image/png".toMediaType()

          @JvmStatic
          fun main(args: Array<String>) {
            UploadProgress().run()
          }
        }

        private val client = OkHttpClient()

        @Throws(Exception::class)
        fun run() {
          val progressListener = object : ProgressListener {
            private var firstUpdate = true

            override fun update(bytesWritten: Long, contentLength: Long, done: Boolean) {
              if (done) {
                println("completed")
              } else {
                if (firstUpdate) {
                  firstUpdate = false
                  if (contentLength == -1L) {
                    println("content-length: unknown")
                  } else {
                    println("content-length: $contentLength")
                  }
                }
                println(bytesWritten)
                if (contentLength != -1L) {
                  println("${100 * bytesWritten / contentLength}% done")
                }
              }
            }
          }

          val file = File("docs/images/logo-square.png")
          val requestBody: RequestBody = file.asRequestBody(MEDIA_TYPE_PNG)

          val request =
            Request.Builder().header("Authorization", "Client-ID $IMGUR_CLIENT_ID")
              .url("https://api.imgur.com/3/image")
              .post(ProgressRequestBody(requestBody, progressListener)).build()

          client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IOException("Unexpected code $response")
            println(response.body.string())
          }
        }

        private class ProgressRequestBody(
          private val delegate: RequestBody, private val progressListener: ProgressListener
        ) : RequestBody() {

          override fun contentType() = delegate.contentType()

          @Throws(IOException::class)
          override fun contentLength(): Long = delegate.contentLength()

          @Throws(IOException::class)
          override fun writeTo(sink: BufferedSink) {
            val forwardingSink = object : ForwardingSink(sink) {
              private var totalBytesWritten: Long = 0
              private var completed = false

              override fun write(source: Buffer, byteCount: Long) {
                super.write(source, byteCount)
                totalBytesWritten += byteCount
                progressListener.update(totalBytesWritten, contentLength(), completed)
              }

              override fun close() {
                super.close()
                if (!completed) {
                  completed = true
                  progressListener.update(totalBytesWritten, contentLength(), completed)
                }
              }
            }

            val bufferedSink = forwardingSink.buffer()
            delegate.writeTo(bufferedSink)
            bufferedSink.flush()
          }
        }

        fun interface ProgressListener {
          fun update(bytesWritten: Long, contentLength: Long, done: Boolean)
        }
      }
    ```

=== ":material-language-java: Java"
    ```java
      public final class UploadProgress {
        private static final String IMGUR_CLIENT_ID = "9199fdef135c122";
        private static final MediaType MEDIA_TYPE_PNG = MediaType.get("image/png");

        private final OkHttpClient client = new OkHttpClient();

        public void run() throws Exception {
          final ProgressListener progressListener = new ProgressListener() {
            boolean firstUpdate = true;

            @Override public void update(long bytesWritten, long contentLength, boolean done) {
              if (done) {
                System.out.println("completed");
              } else {
                if (firstUpdate) {
                  firstUpdate = false;
                  if (contentLength == -1) {
                    System.out.println("content-length: unknown");
                  } else {
                    System.out.format("content-length: %d\n", contentLength);
                  }
                }
                System.out.println(bytesWritten);
                if (contentLength != -1) {
                  System.out.format("%d%% done\n", (100 * bytesWritten) / contentLength);
                }
              }
            }
          };

          RequestBody requestBody = RequestBody.create(
              new File("docs/images/logo-square.png"),
              MEDIA_TYPE_PNG);

          Request request = new Request.Builder()
              .header("Authorization", "Client-ID " + IMGUR_CLIENT_ID)
              .url("https://api.imgur.com/3/image")
              .post(new ProgressRequestBody(requestBody, progressListener))
              .build();

          Response response = client.newCall(request).execute();
          if (!response.isSuccessful()) throw new IOException("Unexpected code " + response);

          System.out.println(response.body().string());
        }

        public static void main(String... args) throws Exception {
          new UploadProgress().run();
        }

        private static class ProgressRequestBody extends RequestBody {
          private final ProgressListener progressListener;
          private final RequestBody delegate;

          public ProgressRequestBody(RequestBody delegate, ProgressListener progressListener) {
            this.delegate = delegate;
            this.progressListener = progressListener;
          }

          @Override public MediaType contentType() {
            return delegate.contentType();
          }

          @Override public long contentLength() throws IOException {
            return delegate.contentLength();
          }

          @Override public void writeTo(BufferedSink sink) throws IOException {
            BufferedSink bufferedSink = Okio.buffer(sink(sink));
            delegate.writeTo(bufferedSink);
            bufferedSink.flush();
          }

          public Sink sink(Sink sink) {
            return new ForwardingSink(sink) {
              private long totalBytesWritten = 0L;
              private boolean completed = false;

              @Override public void write(Buffer source, long byteCount) throws IOException {
                super.write(source, byteCount);
                totalBytesWritten += byteCount;
                progressListener.update(totalBytesWritten, contentLength(), completed);
              }

              @Override public void close() throws IOException {
                super.close();
                if (!completed) {
                  completed = true;
                  progressListener.update(totalBytesWritten, contentLength(), completed);
                }
              }
            };
          }
        }

        interface ProgressListener {
          void update(long bytesWritten, long contentLength, boolean done);
        }
      }
    ```

 [SynchronousGetJava]: https://github.com/lysine-dev/okhttp/blob/master/samples/guide/src/main/java/okhttp3/recipes/SynchronousGet.java
 [SynchronousGetKotlin]: https://github.com/lysine-dev/okhttp/blob/master/samples/guide/src/main/java/okhttp3/recipes/kt/SynchronousGet.kt
 [AsynchronousGetJava]: https://github.com/lysine-dev/okhttp/blob/master/samples/guide/src/main/java/okhttp3/recipes/AsynchronousGet.java
 [AsynchronousGetKotlin]: https://github.com/lysine-dev/okhttp/blob/master/samples/guide/src/main/java/okhttp3/recipes/kt/AsynchronousGet.kt
 [AccessHeadersJava]: https://github.com/lysine-dev/okhttp/blob/master/samples/guide/src/main/java/okhttp3/recipes/AccessHeaders.java
 [AccessHeadersKotlin]: https://github.com/lysine-dev/okhttp/blob/master/samples/guide/src/main/java/okhttp3/recipes/kt/AccessHeaders.kt
 [PostStringJava]: https://github.com/lysine-dev/okhttp/blob/master/samples/guide/src/main/java/okhttp3/recipes/PostString.java
 [PostStringKotlin]: https://github.com/lysine-dev/okhttp/blob/master/samples/guide/src/main/java/okhttp3/recipes/kt/PostString.kt
 [PostStreamingJava]: https://github.com/lysine-dev/okhttp/blob/master/samples/guide/src/main/java/okhttp3/recipes/PostStreaming.java
 [PostStreamingKotlin]: https://github.com/lysine-dev/okhttp/blob/master/samples/guide/src/main/java/okhttp3/recipes/kt/PostStreaming.kt
 [PostFileJava]: https://github.com/lysine-dev/okhttp/blob/master/samples/guide/src/main/java/okhttp3/recipes/PostFile.java
 [PostFileKotlin]: https://github.com/lysine-dev/okhttp/blob/master/samples/guide/src/main/java/okhttp3/recipes/kt/PostFile.kt
 [PostFormJava]: https://github.com/lysine-dev/okhttp/blob/master/samples/guide/src/main/java/okhttp3/recipes/PostForm.java
 [PostFormKotlin]: https://github.com/lysine-dev/okhttp/blob/master/samples/guide/src/main/java/okhttp3/recipes/kt/PostForm.kt
 [PostMultipartJava]: https://github.com/lysine-dev/okhttp/blob/master/samples/guide/src/main/java/okhttp3/recipes/PostMultipart.java
 [PostMultipartKotlin]: https://github.com/lysine-dev/okhttp/blob/master/samples/guide/src/main/java/okhttp3/recipes/kt/PostMultipart.kt
 [ParseResponseWithMoshiJava]: https://github.com/lysine-dev/okhttp/blob/master/samples/guide/src/main/java/okhttp3/recipes/ParseResponseWithMoshi.java
 [ParseResponseWithMoshiKotlin]: https://github.com/lysine-dev/okhttp/blob/master/samples/guide/src/main/java/okhttp3/recipes/kt/ParseResponseWithMoshi.kt
 [CacheResponseJava]: https://github.com/lysine-dev/okhttp/blob/master/samples/guide/src/main/java/okhttp3/recipes/CacheResponse.java
 [CacheResponseKotlin]: https://github.com/lysine-dev/okhttp/blob/master/samples/guide/src/main/java/okhttp3/recipes/kt/CacheResponse.kt
 [CancelCallJava]: https://github.com/lysine-dev/okhttp/blob/master/samples/guide/src/main/java/okhttp3/recipes/CancelCall.java
 [CancelCallKotlin]: https://github.com/lysine-dev/okhttp/blob/master/samples/guide/src/main/java/okhttp3/recipes/kt/CancelCall.kt
 [ConfigureTimeoutsJava]: https://github.com/lysine-dev/okhttp/blob/master/samples/guide/src/main/java/okhttp3/recipes/ConfigureTimeouts.java
 [ConfigureTimeoutsKotlin]: https://github.com/lysine-dev/okhttp/blob/master/samples/guide/src/main/java/okhttp3/recipes/kt/ConfigureTimeouts.kt
 [PerCallSettingsJava]: https://github.com/lysine-dev/okhttp/blob/master/samples/guide/src/main/java/okhttp3/recipes/PerCallSettings.java
 [PerCallSettingsKotlin]: https://github.com/lysine-dev/okhttp/blob/master/samples/guide/src/main/java/okhttp3/recipes/kt/PerCallSettings.kt
 [AuthenticateJava]: https://github.com/lysine-dev/okhttp/blob/master/samples/guide/src/main/java/okhttp3/recipes/Authenticate.java
 [AuthenticateKotlin]: https://github.com/lysine-dev/okhttp/blob/master/samples/guide/src/main/java/okhttp3/recipes/kt/Authenticate.kt
 [UploadProgressJava]: https://github.com/lysine-dev/okhttp/blob/master/samples/guide/src/main/java/okhttp3/recipes/UploadProgress.java
 [UploadProgressKotlin]: https://github.com/lysine-dev/okhttp/blob/master/samples/guide/src/main/java/okhttp3/recipes/kt/UploadProgress.kt
