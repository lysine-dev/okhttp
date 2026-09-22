MockWebServer
=============

A scriptable web server for testing HTTP clients


### Motivation

This library makes it easy to test that your app Does The Right Thing when it
makes HTTP and HTTPS calls. It lets you specify which responses to return and
then verify that requests were made as expected.

Because it exercises your full HTTP stack, you can be confident that you're
testing everything. You can even copy & paste HTTP responses from your real web
server to create representative test cases. Or test that your code survives in
awkward-to-reproduce situations like 500 errors or slow-loading responses.


### Example

Use MockWebServer the same way that you use mocking frameworks like
[Mockito](https://github.com/mockito/mockito):

1. Script the mocks.
2. Run application code.
3. Verify that the expected requests were made.

Here's a complete example:

### Java
```java
public void test() throws Exception {
  // Create a MockWebServer. These are lean enough that you can create a new
  // instance for every unit test.
  MockWebServer server = new MockWebServer();

  // Schedule some responses.
  server.enqueue(new MockResponse.Builder()
      .body("hello, world!")
      .build());
  server.enqueue(new MockResponse.Builder()
      .body("sup, bra?")
      .build());
  server.enqueue(new MockResponse.Builder()
      .body("yo dog")
      .build());

  // Start the server.
  server.start();

  // Ask the server for its URL. You'll need this to make HTTP requests.
  HttpUrl baseUrl = server.url("/v1/chat/");

  // Exercise your application code, which should make those HTTP requests.
  // Responses are returned in the same order that they are enqueued.
  Chat chat = new Chat(baseUrl);

  chat.loadMore();
  assertEquals("hello, world!", chat.messages());

  chat.loadMore();
  chat.loadMore();
  assertEquals(""
      + "hello, world!\n"
      + "sup, bra?\n"
      + "yo dog", chat.messages());

  // Optional: confirm that your app made the HTTP requests you were expecting.
  RecordedRequest request1 = server.takeRequest();
  assertEquals("/v1/chat/messages/", request1.getUrl().encodedPath());
  assertNotNull(request1.getHeaders().get("Authorization"));

  RecordedRequest request2 = server.takeRequest();
  assertEquals("/v1/chat/messages/2", request2.getUrl().encodedPath());

  RecordedRequest request3 = server.takeRequest();
  assertEquals("/v1/chat/messages/3", request3.getUrl().encodedPath());

  // Shut down the server. Instances cannot be reused.
  server.close();
}
```

### Kotlin
```kotlin
fun test() {
  // Create a MockWebServer. These are lean enough that you can create a new
  // instance for every unit test.
  val server = MockWebServer()

  // Schedule some responses.
  server.enqueue(MockResponse(body = "hello, world!"))
  server.enqueue(MockResponse(body = "sup, bra?"))
  server.enqueue(MockResponse(body = "yo dog"))

  // Start the server.
  server.start()

  // Ask the server for its URL. You'll need this to make HTTP requests.
  val baseUrl = server.url("/v1/chat/")

  // Exercise your application code, which should make those HTTP requests.
  // Responses are returned in the same order that they are enqueued.
  val chat = Chat(baseUrl)

  chat.loadMore()
  assertEquals("hello, world!", chat.messages())

  chat.loadMore()
  chat.loadMore()
  assertEquals(""
    + "hello, world!\n"
    + "sup, bra?\n"
    + "yo dog", chat.messages())

  // Optional: confirm that your app made the HTTP requests you were expecting.
  val request1 = server.takeRequest()
  assertEquals("/v1/chat/messages/", request1.url.encodedPath)
  assertNotNull(request1.headers["Authorization"])

  val request2 = server.takeRequest()
  assertEquals("/v1/chat/messages/2", request2.url.encodedPath)

  val request3 = server.takeRequest()
  assertEquals("/v1/chat/messages/3", request3.url.encodedPath)

  // Shut down the server. Instances cannot be reused.
  server.close()
}
```

Your unit tests might move the `server` into a field so you can shut it down
from your test's `tearDown()`.

### API

#### MockResponse

Mock responses default to an empty response body and a `200` status code.
You can set a custom body with a string, input stream or byte array. Also
add headers with a fluent builder API.

### Java
```java
MockResponse response = new MockResponse.Builder()
    .addHeader("Content-Type", "application/json; charset=utf-8")
    .addHeader("Cache-Control", "no-cache")
    .body("{}")
    .build();
```

### Kotlin
```kotlin
val response = MockResponse.Builder()
  .addHeader("Content-Type", "application/json; charset=utf-8")
  .addHeader("Cache-Control", "no-cache")
  .body("{}")
  .build()
```

MockResponse can be used to simulate a slow network. This is useful for
testing timeouts and interactive testing.

### Java
```java
MockResponse response = new MockResponse.Builder()
    .throttleBody(1024, 1, TimeUnit.SECONDS)
    .build();
```

### Kotlin
```kotlin
val response = MockResponse.Builder()
  .throttleBody(1024, 1, TimeUnit.SECONDS)
  .build()
```

#### RecordedRequest

Verify requests by their method, path, HTTP version, body, and headers.

### Java
```java
RecordedRequest request = server.takeRequest();
assertEquals("POST /v1/chat/send HTTP/1.1", request.getRequestLine());
assertEquals("application/json; charset=utf-8", request.getHeaders().get("Content-Type"));
assertEquals("{}", request.getBody().readUtf8());
```

### Kotlin
```kotlin
val request = server.takeRequest()
assertEquals("POST /v1/chat/send HTTP/1.1", request.requestLine)
assertEquals("application/json; charset=utf-8", request.headers["Content-Type"])
assertEquals("{}", request.body!!.utf8())
```

#### Dispatcher

By default MockWebServer uses a queue to specify a series of responses. Use a
Dispatcher (`import okhttp3.mockwebserver.Dispatcher`) to handle requests using another policy. One natural policy is to
dispatch on the request path.
You can, for example, filter the request instead of using `server.enqueue()`.

### Java
```java
final Dispatcher dispatcher = new Dispatcher() {

    @Override
    public MockResponse dispatch (RecordedRequest request) throws InterruptedException {

        switch (request.getUrl().encodedPath()) {
          case "/v1/login/auth/":
              return new MockResponse.Builder()
                  .code(200)
                  .build();
          case "/v1/check/version/":
              return new MockResponse.Builder()
                  .code(200)
                  .body("version=9")
                  .build();
          case "/v1/profile/info":
            return new MockResponse.Builder()
                .code(200)
                .body("{\\\"info\\\":{\\\"name\":\"Lucas Albuquerque\",\"age\":\"21\",\"gender\":\"male\"}}")
                .build();
        }
        return new MockResponse.Builder()
            .code(404)
            .build();
    }
};
server.setDispatcher(dispatcher);
```

### Kotlin
```kotlin
val dispatcher = object : Dispatcher() {
  override fun dispatch(request: RecordedRequest): MockResponse {
    return when (request.url.encodedPath) {
      "/v1/login/auth/" -> {
        MockResponse.Builder()
          .code(200)
          .build()
      }
      "/v1/check/version/" -> {
        MockResponse.Builder()
          .code(200)
          .body("version=9")
          .build()
      }
      "/v1/profile/info" -> {
        MockResponse.Builder()
          .code(200)
          .body("{\\\"info\\\":{\\\"name\":\"Lucas Albuquerque\",\"age\":\"21\",\"gender\":\"male\"}}")
          .build()
      }
      else -> {
        MockResponse.Builder()
          .code(404)
          .build()
      }
    }
  }
}

server.dispatcher = dispatcher
```

### Download

```kotlin
testImplementation("com.squareup.okhttp3:mockwebserver3:5.5.0")
```

### License

    Licensed under the Apache License, Version 2.0 (the "License");
    you may not use this file except in compliance with the License.
    You may obtain a copy of the License at

       http://www.apache.org/licenses/LICENSE-2.0

    Unless required by applicable law or agreed to in writing, software
    distributed under the License is distributed on an "AS IS" BASIS,
    WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
    See the License for the specific language governing permissions and
    limitations under the License.
