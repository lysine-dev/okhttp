Caching
=======

OkHttp implements an optional, off by default, Cache. OkHttp aims for RFC correct and
pragmatic caching behaviour, following common real-world browser like Firefox/Chrome and
server behaviour when ambiguous.

# Basic Usage

```kotlin
  private val client: OkHttpClient = OkHttpClient.Builder()
      .cache(Cache(
          directory = File(application.cacheDir, "http_cache"),
          // $0.05 worth of phone storage in 2020
          maxSize = 50L * 1024L * 1024L // 50 MiB
      ))
      .build()
```

## EventListener events

Cache Events are exposed via the EventListener API.  Typical scenarios are below.

### Cache Hit

In the ideal scenario the cache can fulfill the request without any conditional call to the network.
This will skip the normal events such as DNS, connecting to the network, and downloading the response body.

As recommended by the HTTP RFC the max age of a document is defaulted to 10% of the
document's age at the time it was served based on "Last-Modified". Default expiration dates aren't used for URIs
containing a query.

 - CallStart
 - **CacheHit**
 - CallEnd

### Cache Miss

Under a cache miss the normal request events are seen but an additional event shows the presence of the cache.
Cache Miss will be typical if the item has not been read from the network, is uncacheable, or is past it's
lifetime based on Response cache headers.

 - CallStart
 - **CacheMiss**
 - ProxySelectStart
 - ... Standard Events ...
 - CallEnd

### Conditional Cache Hit

When cache flags require checking the cache results are still valid an early cacheConditionalHit event is
received followed by a cache hit or miss.  Critically in the cache hit scenario the server won’t send the response body.

The response will have non-null `cacheResponse` and `networkResponse`. The cacheResponse will be used as the top level
response only if the response code is HTTP/1.1 304 Not Modified.

 - CallStart
 - **CacheConditionalHit**
 - ConnectionAcquired
 - ... Standard Events...
 - ResponseBodyEnd _(0 bytes)_
 - **CacheHit**
 - ConnectionReleased
 - CallEnd

## Cache directory

The cache directory must be exclusively owned by a single instance.

Deleting the cache when it is no longer needed can be done.  However this may delete the purpose of the cache
which is designed to persist between app restarts.

```kotlin
cache.delete()
```

## Pruning the Cache

Pruning the entire Cache to clear space temporarily can be done using evictAll.

```kotlin
cache.evictAll()
```

Removing individual items can be done using the urls iterator.
This would be typical after a user initiates a force refresh by a pull to refresh type action.

```java
    val urlIterator = cache.urls()
    while (urlIterator.hasNext()) {
      if (urlIterator.next().startsWith("https://www.google.com/")) {
        urlIterator.remove()
      }
    }
```

### Troubleshooting

1. Valid cacheable responses are not being cached

Make sure you are reading responses fully as unless they are read fully, cancelled or stalled Responses will not be cached.

### Overriding normal cache behaviour

See [`Cache`](https://lysine.dev/okhttp/5.x/okhttp/okhttp3/-cache/) documentation.
