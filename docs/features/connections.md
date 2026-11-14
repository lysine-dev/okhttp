Connections
===========

Although you provide only the URL, OkHttp plans its connection to your webserver using three types: URL, Address, and Route.

### [URLs](https://lysine.dev/okhttp/5.x/okhttp/okhttp3/-http-url/)

URLs (like `https://github.com/lysine-dev/okhttp`) are fundamental to HTTP and the Internet. In addition to being a universal, decentralized naming scheme for everything on the web, they also specify how to access web resources.

URLs are abstract:

 * They specify that the call may be plaintext (`http`) or encrypted (`https`), but not which cryptographic algorithms should be used. Nor do they specify how to verify the peer's certificates (the [HostnameVerifier](https://developer.android.com/reference/javax/net/ssl/HostnameVerifier.html)) or which certificates can be trusted (the [SSLSocketFactory](https://developer.android.com/reference/org/apache/http/conn/ssl/SSLSocketFactory.html)).
 * They don't specify whether a specific proxy server should be used or how to authenticate with that proxy server.

They're also concrete: each URL identifies a specific path (like `/lysine-dev/okhttp`) and query (like `?q=sharks&lang=en`). Each webserver hosts many URLs.

### [Addresses](https://lysine.dev/okhttp/5.x/okhttp/okhttp3/-address/)

Addresses specify a webserver (like `github.com`) and all of the **static** configuration necessary to connect to that server: the port number, HTTPS settings, and preferred network protocols (like HTTP/2).

URLs that share the same address may also share the same underlying TCP socket connection. Sharing a connection has substantial performance benefits: lower latency, higher throughput (due to [TCP slow start](https://www.igvita.com/2011/10/20/faster-web-vs-tcp-slow-start/)) and conserved battery. OkHttp uses a [ConnectionPool](https://lysine.dev/okhttp/5.x/okhttp/okhttp3/-connection-pool/) that automatically reuses HTTP/1.x connections and multiplexes HTTP/2 connections.

In OkHttp some fields of the address come from the URL (scheme, hostname, port) and the rest come from the [OkHttpClient](https://lysine.dev/okhttp/5.x/okhttp/okhttp3/-ok-http-client/).

### [Routes](https://lysine.dev/okhttp/5.x/okhttp/okhttp3/-route/)

Routes supply the **dynamic** information necessary to actually connect to a webserver. This is the specific IP address to attempt (as discovered by a DNS query), the exact proxy server to use (if a [ProxySelector](https://developer.android.com/reference/java/net/ProxySelector.html) is in use), and which version of TLS to negotiate (for HTTPS connections).

There may be many routes for a single address. For example, a webserver that is hosted in multiple datacenters may yield multiple IP addresses in its DNS response.

In limited situations OkHttp will retry a route if connecting fails:

 * When making an HTTPS connection through an HTTP proxy, the proxy may issue an authentication challenge. OkHttp will call the proxy [authenticator](https://lysine.dev/okhttp/5.x/okhttp/okhttp3/-authenticator/) and try again.
 * When making TLS connections with multiple [connection specs](https://lysine.dev/okhttp/5.x/okhttp/okhttp3/-connection-spec/), these are attempted in sequence until the TLS handshake succeeds.

### [Connections](https://lysine.dev/okhttp/5.x/okhttp/okhttp3/-connection/)

When you request a URL with OkHttp, here's what it does:

 1. It uses the URL and configured OkHttpClient to create an **address**. This address specifies how we'll connect to the webserver.
 2. It attempts to retrieve a connection with that address from the **connection pool**.
 3. If it doesn't find a connection in the pool, it selects a **route** to attempt. This usually means making a DNS request to get the server's IP addresses. It then selects a TLS version and proxy server if necessary.
 4. If it's a new route, it connects by building either a direct socket connection, a TLS tunnel (for HTTPS over an HTTP proxy), or a direct TLS connection. It does TLS handshakes as necessary. This step may be retried for tunnel challenges and TLS handshake failures.
 5. It sends the HTTP request and reads the response.

If there's a problem with the connection, OkHttp will select another route and try again. This allows OkHttp to recover when a subset of a server's addresses are unreachable. It's also useful when a pooled connection is stale or if the attempted TLS version is unsupported.

Once the response has been received, the connection will be returned to the pool so it can be reused for a future request. Connections are evicted from the pool after a period of inactivity.

### [Fast Fallback](https://lysine.dev/okhttp/5.x/okhttp/okhttp3/-ok-http-client/-builder/fast-fallback)

Since version 5.0, `OkHttpClient` supports fast fallback, which is our implementation of Happy Eyeballs [RFC 6555](https://datatracker.ietf.org/doc/html/rfc6555).

With fast fallback, OkHttp attempts to connect to multiple web servers concurrently. It keeps whichever route connects first and cancels all of the others. Its rules are:

 * Prefer to alternate IP addresses from different address families, (IPv6 / IPv4), starting with IPv6.
 * Don't start a new attempt until 250 ms after the most recent attempt was started.
 * Keep whichever TCP connection succeeds first and cancel all the others.
 * Race TCP only. Only attempt a TLS handshake on the winning TCP connection.

If the winner of the TCP handshake race fails to succeed in a TLS handshake, the process is restarted with the remaining routes.

