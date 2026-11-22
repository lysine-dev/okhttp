/*
 * Copyright (C) 2015 Square, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package okhttp3

import java.net.MalformedURLException
import java.net.URI
import java.net.URISyntaxException
import java.net.URL
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.internal.canParseAsIpAddress
import okhttp3.internal.delimiterOffset
import okhttp3.internal.indexOfFirstNonAsciiWhitespace
import okhttp3.internal.indexOfLastNonAsciiWhitespace
import okhttp3.internal.publicsuffix.PublicSuffixDatabase
import okhttp3.internal.toCanonicalHost
import okhttp3.internal.unmodifiable
import okhttp3.internal.url.FRAGMENT_ENCODE_SET
import okhttp3.internal.url.FRAGMENT_ENCODE_SET_URI
import okhttp3.internal.url.PASSWORD_ENCODE_SET
import okhttp3.internal.url.PATH_SEGMENT_ENCODE_SET
import okhttp3.internal.url.PATH_SEGMENT_ENCODE_SET_URI
import okhttp3.internal.url.QUERY_COMPONENT_ENCODE_SET
import okhttp3.internal.url.QUERY_COMPONENT_ENCODE_SET_URI
import okhttp3.internal.url.QUERY_COMPONENT_REENCODE_SET
import okhttp3.internal.url.QUERY_ENCODE_SET
import okhttp3.internal.url.USERNAME_ENCODE_SET
import okhttp3.internal.url.canonicalize
import okhttp3.internal.url.percentDecode

/**
 * A uniform resource locator (URL) with a scheme of either `http` or `https`. Use this class to
 * compose and decompose Internet addresses. For example, this code will compose and print a URL for
 * Google search:
 *
 * ```java
 * HttpUrl url = new HttpUrl.Builder()
 *     .scheme("https")
 *     .host("www.google.com")
 *     .addPathSegment("search")
 *     .addQueryParameter("q", "polar bears")
 *     .build();
 * System.out.println(url);
 * ```
 *
 * which prints:
 *
 * ```
 * https://www.google.com/search?q=polar%20bears
 * ```
 *
 * As another example, this code prints the human-readable query parameters of a Twitter search:
 *
 * ```java
 * HttpUrl url = HttpUrl.parse("https://twitter.com/search?q=cute%20%23puppies&f=images");
 * for (int i = 0, size = url.querySize(); i < size; i++) {
 *   System.out.println(url.queryParameterName(i) + ": " + url.queryParameterValue(i));
 * }
 * ```
 *
 * which prints:
 *
 * ```
 * q: cute #puppies
 * f: images
 * ```
 *
 * In addition to composing URLs from their component parts and decomposing URLs into their
 * component parts, this class implements relative URL resolution: what address you'd reach by
 * clicking a relative link on a specified page. For example:
 *
 * ```java
 * HttpUrl base = HttpUrl.parse("https://www.youtube.com/user/WatchTheDaily/videos");
 * HttpUrl link = base.resolve("../../watch?v=cbP2N1BQdYc");
 * System.out.println(link);
 * ```
 *
 * which prints:
 *
 * ```
 * https://www.youtube.com/watch?v=cbP2N1BQdYc
 * ```
 *
 * ## What's in a URL?
 *
 * A URL has several components.
 *
 * ### Scheme
 *
 * Sometimes referred to as *protocol*, A URL's scheme describes what mechanism should be used to
 * retrieve the resource. Although URLs have many schemes (`mailto`, `file`, `ftp`), this class only
 * supports `http` and `https`. Use [java.net.URI][URI] for URLs with arbitrary schemes.
 *
 * ### Username and Password
 *
 * Username and password are either present, or the empty string `""` if absent. This class offers
 * no mechanism to differentiate empty from absent. Neither of these components are popular in
 * practice. Typically HTTP applications use other mechanisms for user identification and
 * authentication.
 *
 * ### Host
 *
 * The host identifies the webserver that serves the URL's resource. It is either a hostname like
 * `square.com` or `localhost`, an IPv4 address like `192.168.0.1`, or an IPv6 address like `::1`.
 *
 * Usually a webserver is reachable with multiple identifiers: its IP addresses, registered
 * domain names, and even `localhost` when connecting from the server itself. Each of a web server's
 * names is a distinct URL and they are not interchangeable. For example, even if
 * `http://square.github.io/dagger` and `http://google.github.io/dagger` are served by the same IP
 * address, the two URLs identify different resources.
 *
 * ### Port
 *
 * The port used to connect to the web server. By default this is 80 for HTTP and 443 for HTTPS.
 * This class never returns -1 for the port: if no port is explicitly specified in the URL then the
 * scheme's default is used.
 *
 * ### Path
 *
 * The path identifies a specific resource on the host. Paths have a hierarchical structure like
 * "/lysine-dev/okhttp/issues/1486" and decompose into a list of segments like `["square", "okhttp",
 * "issues", "1486"]`.
 *
 * This class offers methods to compose and decompose paths by segment. It composes each path
 * from a list of segments by alternating between "/" and the encoded segment. For example the
 * segments `["a", "b"]` build "/a/b" and the segments `["a", "b", ""]` build "/a/b/".
 *
 * If a path's last segment is the empty string then the path ends with "/". This class always
 * builds non-empty paths: if the path is omitted it defaults to "/". The default path's segment
 * list is a single empty string: `[""]`.
 *
 * ### Query
 *
 * The query is optional: it can be null, empty, or non-empty. For many HTTP URLs the query string
 * is subdivided into a collection of name-value parameters. This class offers methods to set the
 * query as the single string, or as individual name-value parameters. With name-value parameters
 * the values are optional and names may be repeated.
 *
 * ### Fragment
 *
 * The fragment is optional: it can be null, empty, or non-empty. Unlike host, port, path, and
 * query the fragment is not sent to the webserver: it's private to the client.
 *
 * ## Encoding
 *
 * Each component must be encoded before it is embedded in the complete URL. As we saw above, the
 * string `cute #puppies` is encoded as `cute%20%23puppies` when used as a query parameter value.
 *
 * ### Percent encoding
 *
 * Percent encoding replaces a character (like `\ud83c\udf69`) with its UTF-8 hex bytes (like
 * `%F0%9F%8D%A9`). This approach works for whitespace characters, control characters, non-ASCII
 * characters, and characters that already have another meaning in a particular context.
 *
 * Percent encoding is used in every URL component except for the hostname. But the set of
 * characters that need to be encoded is different for each component. For example, the path
 * component must escape all of its `?` characters, otherwise it could be interpreted as the
 * start of the URL's query. But within the query and fragment components, the `?` character
 * doesn't delimit anything and doesn't need to be escaped.
 *
 * ```java
 * HttpUrl url = HttpUrl.parse("http://who-let-the-dogs.out").newBuilder()
 *     .addPathSegment("_Who?_")
 *     .query("_Who?_")
 *     .fragment("_Who?_")
 *     .build();
 * System.out.println(url);
 * ```
 *
 * This prints:
 *
 * ```
 * http://who-let-the-dogs.out/_Who%3F_?_Who?_#_Who?_
 * ```
 *
 * When parsing URLs that lack percent encoding where it is required, this class will percent encode
 * the offending characters.
 *
 * ### IDNA Mapping and Punycode encoding
 *
 * Hostnames have different requirements and use a different encoding scheme. It consists of IDNA
 * mapping and Punycode encoding.
 *
 * In order to avoid confusion and discourage phishing attacks, [IDNA Mapping][idna] transforms
 * names to avoid confusing characters. This includes basic case folding: transforming shouting
 * `SQUARE.COM` into cool and casual `square.com`. It also handles more exotic characters. For
 * example, the Unicode trademark sign (™) could be confused for the letters "TM" in
 * `http://ho™ail.com`. To mitigate this, the single character (™) maps to the string (tm). There
 * is similar policy for all of the 1.1 million Unicode code points. Note that some code points such
 * as "\ud83c\udf69" are not mapped and cannot be used in a hostname.
 *
 * [Punycode](http://ietf.org/rfc/rfc3492.txt) converts a Unicode string to an ASCII string to make
 * international domain names work everywhere. For example, "σ" encodes as "xn--4xa". The encoded
 * string is not human readable, but can be used with classes like [InetAddress] to establish
 * connections.
 *
 * ## Why another URL model?
 *
 * Java includes both [java.net.URL][URL] and [java.net.URI][URI]. We offer a new URL
 * model to address problems that the others don't.
 *
 * ### Different URLs should be different
 *
 * Although they have different content, `java.net.URL` considers the following two URLs
 * equal, and the [equals()][Object.equals] method between them returns true:
 *
 *  * https://example.net/
 *
 *  * https://example.com/
 *
 * This is because those two hosts share the same IP address. This is an old, bad design decision
 * that makes `java.net.URL` unusable for many things. It shouldn't be used as a [Map] key or in a
 * [Set]. Doing so is both inefficient because equality may require a DNS lookup, and incorrect
 * because unequal URLs may be equal because of how they are hosted.
 *
 * ### Equal URLs should be equal
 *
 * These two URLs are semantically identical, but `java.net.URI` disagrees:
 *
 *  * http://host:80/
 *
 *  * http://host
 *
 * Both the unnecessary port specification (`:80`) and the absent trailing slash (`/`) cause URI to
 * bucket the two URLs separately. This harms URI's usefulness in collections. Any application that
 * stores information-per-URL will need to either canonicalize manually, or suffer unnecessary
 * redundancy for such URLs.
 *
 * Because they don't attempt canonical form, these classes are surprisingly difficult to use
 * securely. Suppose you're building a webservice that checks that incoming paths are prefixed
 * "/static/images/" before serving the corresponding assets from the filesystem.
 *
 * ```java
 * String attack = "http://example.com/static/images/../../../../../etc/passwd";
 * System.out.println(new URL(attack).getPath());
 * System.out.println(new URI(attack).getPath());
 * System.out.println(HttpUrl.parse(attack).encodedPath());
 * ```
 *
 * By canonicalizing the input paths, they are complicit in directory traversal attacks. Code that
 * checks only the path prefix may suffer!
 *
 * ```
 * /static/images/../../../../../etc/passwd
 * /static/images/../../../../../etc/passwd
 * /etc/passwd
 * ```
 *
 * ### If it works on the web, it should work in your application
 *
 * The `java.net.URI` class is strict around what URLs it accepts. It rejects URLs like
 * `http://example.com/abc|def` because the `|` character is unsupported. This class is more
 * forgiving: it will automatically percent-encode the `|'` yielding `http://example.com/abc%7Cdef`.
 * This kind behavior is consistent with web browsers. `HttpUrl` prefers consistency with major web
 * browsers over consistency with obsolete specifications.
 *
 * ### Paths and Queries should decompose
 *
 * Neither of the built-in URL models offer direct access to path segments or query parameters.
 * Manually using `StringBuilder` to assemble these components is cumbersome: do '+' characters get
 * silently replaced with spaces? If a query parameter contains a '&amp;', does that get escaped?
 * By offering methods to read and write individual query parameters directly, application
 * developers are saved from the hassles of encoding and decoding.
 *
 * ### Plus a modern API
 *
 * The URL (JDK1.0) and URI (Java 1.4) classes predate builders and instead use telescoping
 * constructors. For example, there's no API to compose a URI with a custom port without also
 * providing a query and fragment.
 *
 * Instances of [HttpUrl] are well-formed and always have a scheme, host, and path. With
 * `java.net.URL` it's possible to create an awkward URL like `http:/` with scheme and path but no
 * hostname. Building APIs that consume such malformed values is difficult!
 *
 * This class has a modern API. It avoids punitive checked exceptions: [toHttpUrl] throws
 * [IllegalArgumentException] on invalid input or [toHttpUrlOrNull] returns null if the input is an
 * invalid URL. You can even be explicit about whether each component has been encoded already.
 *
 * [idna]: http://www.unicode.org/reports/tr46/#ToASCII
 */
class HttpUrl private constructor(
  /** Either "http" or "https". */
  @get:JvmName("scheme") val scheme: String,
  /**
   * The decoded username, or an empty string if none is present.
   *
   * | URL                              | `username()` |
   * | :------------------------------- | :----------- |
   * | `http://host/`                   | `""`         |
   * | `http://username@host/`          | `"username"` |
   * | `http://username:password@host/` | `"username"` |
   * | `http://a%20b:c%20d@host/`       | `"a b"`      |
   */
  @get:JvmName("username") val username: String,
  /**
   * Returns the decoded password, or an empty string if none is present.
   *
   * | URL                              | `password()` |
   * | :------------------------------- | :----------- |
   * | `http://host/`                   | `""`         |
   * | `http://username@host/`          | `""`         |
   * | `http://username:password@host/` | `"password"` |
   * | `http://a%20b:c%20d@host/`       | `"c d"`      |
   */
  @get:JvmName("password") val password: String,
  /**
   * The host address suitable for use with [InetAddress.getAllByName]. May be:
   *
   *  * A regular host name, like `android.com`.
   *
   *  * An IPv4 address, like `127.0.0.1`.
   *
   *  * An IPv6 address, like `::1`. Note that there are no square braces.
   *
   *  * An encoded IDN, like `xn--n3h.net`.
   *
   * | URL                   | `host()`        |
   * | :-------------------- | :-------------- |
   * | `http://android.com/` | `"android.com"` |
   * | `http://127.0.0.1/`   | `"127.0.0.1"`   |
   * | `http://[::1]/`       | `"::1"`         |
   * | `http://xn--n3h.net/` | `"xn--n3h.net"` |
   */
  @get:JvmName("host") val host: String,
  /**
   * The explicitly-specified port if one was provided, or the default port for this URL's scheme.
   * For example, this returns 8443 for `https://square.com:8443/` and 443 for
   * `https://square.com/`. The result is in `[1..65535]`.
   *
   * | URL                 | `port()` |
   * | :------------------ | :------- |
   * | `http://host/`      | `80`     |
   * | `http://host:8000/` | `8000`   |
   * | `https://host/`     | `443`    |
   */
  @get:JvmName("port") val port: Int,
  /**
   * A list of path segments like `["a", "b", "c"]` for the URL `http://host/a/b/c`. This list is
   * never empty though it may contain a single empty string.
   *
   * | URL                      | `pathSegments()`    |
   * | :----------------------- | :------------------ |
   * | `http://host/`           | `[""]`              |
   * | `http://host/a/b/c"`     | `["a", "b", "c"]`   |
   * | `http://host/a/b%20c/d"` | `["a", "b c", "d"]` |
   */
  @get:JvmName("pathSegments") val pathSegments: List<String>,
  /**
   * Alternating, decoded query names and values, or null for no query. Names may be empty or
   * non-empty, but never null. Values are null if the name has no corresponding '=' separator, or
   * empty, or non-empty.
   */
  private val queryNamesAndValues: List<String?>?,
  /**
   * This URL's fragment, like `"abc"` for `http://host/#abc`. This is null if the URL has no
   * fragment.
   *
   * | URL                    | `fragment()` |
   * | :--------------------- | :----------- |
   * | `http://host/`         | null         |
   * | `http://host/#`        | `""`         |
   * | `http://host/#abc`     | `"abc"`      |
   * | `http://host/#abc|def` | `"abc|def"`  |
   */
  @get:JvmName("fragment") val fragment: String?,
  /** Canonical URL. */
  private val url: String,
) {
  val isHttps: Boolean
    get() = scheme == "https"

  /** Returns this URL as a [java.net.URL][URL]. */
  @JvmName("url")
  fun toUrl(): URL {
    try {
      return URL(url)
    } catch (e: MalformedURLException) {
      throw RuntimeException(e) // Unexpected!
    }
  }

  /**
   * Returns this URL as a [java.net.URI][URI]. Because `URI` is more strict than this class, the
   * returned URI may be semantically different from this URL:
   *
   *  * Characters forbidden by URI like `[` and `|` will be escaped.
   *
   *  * Invalid percent-encoded sequences like `%xx` will be encoded like `%25xx`.
   *
   *  * Whitespace and control characters in the fragment will be stripped.
   *
   * These differences may have a significant consequence when the URI is interpreted by a
   * web server. For this reason the [URI class][URI] and this method should be avoided.
   */
  @JvmName("uri")
  fun toUri(): URI {
    val uri = newBuilder().reencodeForUri().toString()
    return try {
      URI(uri)
    } catch (e: URISyntaxException) {
      // Unlikely edge case: the URI has a forbidden character in the fragment. Strip it & retry.
      try {
        val stripped = uri.replace(Regex("[\\u0000-\\u001F\\u007F-\\u009F\\p{javaWhitespace}]"), "")
        URI.create(stripped)
      } catch (e1: Exception) {
        throw RuntimeException(e) // Unexpected!
      }
    }
  }

  /**
   * The username, or an empty string if none is set.
   *
   * | URL                              | `encodedUsername()` |
   * | :------------------------------- | :------------------ |
   * | `http://host/`                   | `""`                |
   * | `http://username@host/`          | `"username"`        |
   * | `http://username:password@host/` | `"username"`        |
   * | `http://a%20b:c%20d@host/`       | `"a%20b"`           |
   */
  @get:JvmName("encodedUsername")
  val encodedUsername: String
    get() {
      if (username.isEmpty()) return ""
      val usernameStart = scheme.length + 3 // "://".length() == 3.
      val usernameEnd = url.delimiterOffset(":@", usernameStart, url.length)
      return url.substring(usernameStart, usernameEnd)
    }

  /**
   * The password, or an empty string if none is set.
   *
   * | URL                              | `encodedPassword()` |
   * | :--------------------------------| :------------------ |
   * | `http://host/`                   | `""`                |
   * | `http://username@host/`          | `""`                |
   * | `http://username:password@host/` | `"password"`        |
   * | `http://a%20b:c%20d@host/`       | `"c%20d"`           |
   */
  @get:JvmName("encodedPassword")
  val encodedPassword: String
    get() {
      if (password.isEmpty()) return ""
      val passwordStart = url.indexOf(':', scheme.length + 3) + 1
      val passwordEnd = url.indexOf('@')
      return url.substring(passwordStart, passwordEnd)
    }

  /**
   * The number of segments in this URL's path. This is also the number of slashes in this URL's
   * path, like 3 in `http://host/a/b/c`. This is always at least 1.
   *
   * | URL                  | `pathSize()` |
   * | :------------------- | :----------- |
   * | `http://host/`       | `1`          |
   * | `http://host/a/b/c`  | `3`          |
   * | `http://host/a/b/c/` | `4`          |
   */
  @get:JvmName("pathSize")
  val pathSize: Int
    get() = pathSegments.size

  /**
   * The entire path of this URL encoded for use in HTTP resource resolution. The returned path will
   * start with `"/"`.
   *
   * | URL                     | `encodedPath()` |
   * | :---------------------- | :-------------- |
   * | `http://host/`          | `"/"`           |
   * | `http://host/a/b/c`     | `"/a/b/c"`      |
   * | `http://host/a/b%20c/d` | `"/a/b%20c/d"`  |
   */
  @get:JvmName("encodedPath")
  val encodedPath: String
    get() {
      val pathStart = url.indexOf('/', scheme.length + 3) // "://".length() == 3.
      val pathEnd = url.delimiterOffset("?#", pathStart, url.length)
      return url.substring(pathStart, pathEnd)
    }

  /**
   * A list of encoded path segments like `["a", "b", "c"]` for the URL `http://host/a/b/c`. This
   * list is never empty though it may contain a single empty string.
   *
   * | URL                     | `encodedPathSegments()` |
   * | :---------------------- | :---------------------- |
   * | `http://host/`          | `[""]`                  |
   * | `http://host/a/b/c`     | `["a", "b", "c"]`       |
   * | `http://host/a/b%20c/d` | `["a", "b%20c", "d"]`   |
   */
  @get:JvmName("encodedPathSegments")
  val encodedPathSegments: List<String>
    get() {
      val pathStart = url.indexOf('/', scheme.length + 3)
      val pathEnd = url.delimiterOffset("?#", pathStart, url.length)
      val result = mutableListOf<String>()
      var i = pathStart
      while (i < pathEnd) {
        i++ // Skip the '/'.
        val segmentEnd = url.delimiterOffset('/', i, pathEnd)
        result.add(url.substring(i, segmentEnd))
        i = segmentEnd
      }
      return result
    }

  /**
   * The query of this URL, encoded for use in HTTP resource resolution. This string may be null
   * (for URLs with no query), empty (for URLs with an empty query) or non-empty (all other URLs).
   *
   * | URL                               | `encodedQuery()`       |
   * | :-------------------------------- | :--------------------- |
   * | `http://host/`                    | null                   |
   * | `http://host/?`                   | `""`                   |
   * | `http://host/?a=apple&k=key+lime` | `"a=apple&k=key+lime"` |
   * | `http://host/?a=apple&a=apricot`  | `"a=apple&a=apricot"`  |
   * | `http://host/?a=apple&b`          | `"a=apple&b"`          |
   */
  @get:JvmName("encodedQuery")
  val encodedQuery: String?
    get() {
      if (queryNamesAndValues == null) return null // No query.
      val queryStart = url.indexOf('?') + 1
      val queryEnd = url.delimiterOffset('#', queryStart, url.length)
      return url.substring(queryStart, queryEnd)
    }

  /**
   * This URL's query, like `"abc"` for `http://host/?abc`. Most callers should prefer
   * [queryParameterName] and [queryParameterValue] because these methods offer direct access to
   * individual query parameters.
   *
   * | URL                               | `query()`              |
   * | :-------------------------------- | :--------------------- |
   * | `http://host/`                    | null                   |
   * | `http://host/?`                   | `""`                   |
   * | `http://host/?a=apple&k=key+lime` | `"a=apple&k=key lime"` |
   * | `http://host/?a=apple&a=apricot`  | `"a=apple&a=apricot"`  |
   * | `http://host/?a=apple&b`          | `"a=apple&b"`          |
   */
  @get:JvmName("query")
  val query: String?
    get() {
      if (queryNamesAndValues == null) return null // No query.
      val result = StringBuilder()
      queryNamesAndValues.toQueryString(result)
      return result.toString()
    }

  /**
   * The number of query parameters in this URL, like 2 for `http://host/?a=apple&b=banana`. If this
   * URL has no query this is 0. Otherwise it is one more than the number of `"&"` separators in the
   * query.
   *
   * | URL                               | `querySize()` |
   * | :-------------------------------- | :------------ |
   * | `http://host/`                    | `0`           |
   * | `http://host/?`                   | `1`           |
   * | `http://host/?a=apple&k=key+lime` | `2`           |
   * | `http://host/?a=apple&a=apricot`  | `2`           |
   * | `http://host/?a=apple&b`          | `2`           |
   */
  @get:JvmName("querySize")
  val querySize: Int
    get() {
      return if (queryNamesAndValues != null) queryNamesAndValues.size / 2 else 0
    }

  /**
   * The first query parameter named `name` decoded using UTF-8, or null if there is no such query
   * parameter.
   *
   * | URL                               | `queryParameter("a")` |
   * | :-------------------------------- | :-------------------- |
   * | `http://host/`                    | null                  |
   * | `http://host/?`                   | null                  |
   * | `http://host/?a=apple&k=key+lime` | `"apple"`             |
   * | `http://host/?a=apple&a=apricot`  | `"apple"`             |
   * | `http://host/?a=apple&b`          | `"apple"`             |
   */
  fun queryParameter(name: String): String? {
    if (queryNamesAndValues == null) return null
    for (i in 0 until queryNamesAndValues.size step 2) {
      if (name == queryNamesAndValues[i]) {
        return queryNamesAndValues[i + 1]
      }
    }
    return null
  }

  /**
   * The distinct query parameter names in this URL, like `["a", "b"]` for
   * `http://host/?a=apple&b=banana`. If this URL has no query this is the empty set.
   *
   * | URL                               | `queryParameterNames()` |
   * | :-------------------------------- | :---------------------- |
   * | `http://host/`                    | `[]`                    |
   * | `http://host/?`                   | `[""]`                  |
   * | `http://host/?a=apple&k=key+lime` | `["a", "k"]`            |
   * | `http://host/?a=apple&a=apricot`  | `["a"]`                 |
   * | `http://host/?a=apple&b`          | `["a", "b"]`            |
   */
  @get:JvmName("queryParameterNames")
  val queryParameterNames: Set<String>
    get() {
      if (queryNamesAndValues == null) return emptySet()
      val result = LinkedHashSet<String>(queryNamesAndValues.size / 2, 1.0F)
      for (i in 0 until queryNamesAndValues.size step 2) {
        result.add(queryNamesAndValues[i]!!)
      }
      return result.unmodifiable()
    }

  /**
   * Returns all values for the query parameter `name` ordered by their appearance in this
   * URL. For example this returns `["banana"]` for `queryParameterValue("b")` on
   * `http://host/?a=apple&b=banana`.
   *
   * | URL                               | `queryParameterValues("a")` | `queryParameterValues("b")` |
   * | :-------------------------------- | :-------------------------- | :-------------------------- |
   * | `http://host/`                    | `[]`                        | `[]`                        |
   * | `http://host/?`                   | `[]`                        | `[]`                        |
   * | `http://host/?a=apple&k=key+lime` | `["apple"]`                 | `[]`                        |
   * | `http://host/?a=apple&a=apricot`  | `["apple", "apricot"]`      | `[]`                        |
   * | `http://host/?a=apple&b`          | `["apple"]`                 | `[null]`                    |
   */
  fun queryParameterValues(name: String): List<String?> {
    if (queryNamesAndValues == null) return emptyList()
    val result = ArrayList<String?>(4)
    for (i in 0 until queryNamesAndValues.size step 2) {
      if (name == queryNamesAndValues[i]) {
        result.add(queryNamesAndValues[i + 1])
      }
    }
    return result.unmodifiable()
  }

  /**
   * Returns the name of the query parameter at `index`. For example this returns `"a"`
   * for `queryParameterName(0)` on `http://host/?a=apple&b=banana`. This throws if
   * `index` is not less than the [query size][querySize].
   *
   * | URL                               | `queryParameterName(0)` | `queryParameterName(1)` |
   * | :-------------------------------- | :---------------------- | :---------------------- |
   * | `http://host/`                    | exception               | exception               |
   * | `http://host/?`                   | `""`                    | exception               |
   * | `http://host/?a=apple&k=key+lime` | `"a"`                   | `"k"`                   |
   * | `http://host/?a=apple&a=apricot`  | `"a"`                   | `"a"`                   |
   * | `http://host/?a=apple&b`          | `"a"`                   | `"b"`                   |
   */
  fun queryParameterName(index: Int): String {
    if (queryNamesAndValues == null) throw IndexOutOfBoundsException()
    return queryNamesAndValues[index * 2]!!
  }

  /**
   * Returns the value of the query parameter at `index`. For example this returns `"apple"` for
   * `queryParameterName(0)` on `http://host/?a=apple&b=banana`. This throws if `index` is not less
   * than the [query size][querySize].
   *
   * | URL                               | `queryParameterValue(0)` | `queryParameterValue(1)` |
   * | :-------------------------------- | :----------------------- | :----------------------- |
   * | `http://host/`                    | exception                | exception                |
   * | `http://host/?`                   | null                     | exception                |
   * | `http://host/?a=apple&k=key+lime` | `"apple"`                | `"key lime"`             |
   * | `http://host/?a=apple&a=apricot`  | `"apple"`                | `"apricot"`              |
   * | `http://host/?a=apple&b`          | `"apple"`                | null                     |
   */
  fun queryParameterValue(index: Int): String? {
    if (queryNamesAndValues == null) throw IndexOutOfBoundsException()
    return queryNamesAndValues[index * 2 + 1]
  }

  /**
   * This URL's encoded fragment, like `"abc"` for `http://host/#abc`. This is null if the URL has
   * no fragment.
   *
   * | URL                    | `encodedFragment()` |
   * | :--------------------- | :------------------ |
   * | `http://host/`         | null                |
   * | `http://host/#`        | `""`                |
   * | `http://host/#abc`     | `"abc"`             |
   * | `http://host/#abc|def` | `"abc|def"`         |
   */
  @get:JvmName("encodedFragment")
  val encodedFragment: String?
    get() {
      if (fragment == null) return null
      val fragmentStart = url.indexOf('#') + 1
      return url.substring(fragmentStart)
    }

  /**
   * Returns a string with containing this URL with its username, password, query, and fragment
   * stripped, and its path replaced with `/...`. For example, redacting
   * `http://username:password@example.com/path` returns `http://example.com/...`.
   */
  fun redact(): String =
    newBuilder("/...")!!
      .username("")
      .password("")
      .build()
      .toString()

  /**
   * Returns the URL that would be retrieved by following `link` from this URL, or null if the
   * resulting URL is not well-formed.
   */
  fun resolve(link: String): HttpUrl? = newBuilder(link)?.build()

  /**
   * Returns a builder based on this URL.
   */
  fun newBuilder(): Builder {
    val result = Builder()
    result.scheme = scheme
    result.encodedUsername = encodedUsername
    result.encodedPassword = encodedPassword
    result.host = host
    // If we're set to a default port, unset it in case of a scheme change.
    result.port = if (port != defaultPort(scheme)) port else -1
    result.encodedPathSegments.clear()
    result.encodedPathSegments.addAll(encodedPathSegments)
    result.encodedQuery(encodedQuery)
    result.encodedFragment = encodedFragment
    return result
  }

  /**
   * Returns a builder for the URL that would be retrieved by following `link` from this URL,
   * or null if the resulting URL is not well-formed.
   */
  fun newBuilder(link: String): Builder? =
    try {
      Builder().parse(this, link)
    } catch (_: IllegalArgumentException) {
      null
    }

  override fun equals(other: Any?): Boolean = other is HttpUrl && other.url == url

  override fun hashCode(): Int = url.hashCode()

  override fun toString(): String = url

  /**
   * Returns the domain name of this URL's [host] that is one level beneath the public suffix by
   * consulting the [public suffix list](https://publicsuffix.org). Returns null if this URL's
   * [host] is an IP address or is considered a public suffix by the public suffix list.
   *
   * In general this method **should not** be used to test whether a domain is valid or routable.
   * Instead, DNS is the recommended source for that information.
   *
   * | URL                           | `topPrivateDomain()` |
   * | :---------------------------- | :------------------- |
   * | `http://google.com`           | `"google.com"`       |
   * | `http://adwords.google.co.uk` | `"google.co.uk"`     |
   * | `http://square`               | null                 |
   * | `http://co.uk`                | null                 |
   * | `http://localhost`            | null                 |
   * | `http://127.0.0.1`            | null                 |
   */
  fun topPrivateDomain(): String? =
    if (host.canParseAsIpAddress()) {
      null
    } else {
      PublicSuffixDatabase.get().getEffectiveTldPlusOne(host)
    }

  @JvmName("-deprecated_url")
  @Deprecated(
    message = "moved to toUrl()",
    replaceWith = ReplaceWith(expression = "toUrl()"),
    level = DeprecationLevel.ERROR,
  )
  fun url(): URL = toUrl()

  @JvmName("-deprecated_uri")
  @Deprecated(
    message = "moved to toUri()",
    replaceWith = ReplaceWith(expression = "toUri()"),
    level = DeprecationLevel.ERROR,
  )
  fun uri(): URI = toUri()

  @JvmName("-deprecated_scheme")
  @Deprecated(
    message = "moved to val",
    replaceWith = ReplaceWith(expression = "scheme"),
    level = DeprecationLevel.ERROR,
  )
  fun scheme(): String = scheme

  @JvmName("-deprecated_encodedUsername")
  @Deprecated(
    message = "moved to val",
    replaceWith = ReplaceWith(expression = "encodedUsername"),
    level = DeprecationLevel.ERROR,
  )
  fun encodedUsername(): String = encodedUsername

  @JvmName("-deprecated_username")
  @Deprecated(
    message = "moved to val",
    replaceWith = ReplaceWith(expression = "username"),
    level = DeprecationLevel.ERROR,
  )
  fun username(): String = username

  @JvmName("-deprecated_encodedPassword")
  @Deprecated(
    message = "moved to val",
    replaceWith = ReplaceWith(expression = "encodedPassword"),
    level = DeprecationLevel.ERROR,
  )
  fun encodedPassword(): String = encodedPassword

  @JvmName("-deprecated_password")
  @Deprecated(
    message = "moved to val",
    replaceWith = ReplaceWith(expression = "password"),
    level = DeprecationLevel.ERROR,
  )
  fun password(): String = password

  @JvmName("-deprecated_host")
  @Deprecated(
    message = "moved to val",
    replaceWith = ReplaceWith(expression = "host"),
    level = DeprecationLevel.ERROR,
  )
  fun host(): String = host

  @JvmName("-deprecated_port")
  @Deprecated(
    message = "moved to val",
    replaceWith = ReplaceWith(expression = "port"),
    level = DeprecationLevel.ERROR,
  )
  fun port(): Int = port

  @JvmName("-deprecated_pathSize")
  @Deprecated(
    message = "moved to val",
    replaceWith = ReplaceWith(expression = "pathSize"),
    level = DeprecationLevel.ERROR,
  )
  fun pathSize(): Int = pathSize

  @JvmName("-deprecated_encodedPath")
  @Deprecated(
    message = "moved to val",
    replaceWith = ReplaceWith(expression = "encodedPath"),
    level = DeprecationLevel.ERROR,
  )
  fun encodedPath(): String = encodedPath

  @JvmName("-deprecated_encodedPathSegments")
  @Deprecated(
    message = "moved to val",
    replaceWith = ReplaceWith(expression = "encodedPathSegments"),
    level = DeprecationLevel.ERROR,
  )
  fun encodedPathSegments(): List<String> = encodedPathSegments

  @JvmName("-deprecated_pathSegments")
  @Deprecated(
    message = "moved to val",
    replaceWith = ReplaceWith(expression = "pathSegments"),
    level = DeprecationLevel.ERROR,
  )
  fun pathSegments(): List<String> = pathSegments

  @JvmName("-deprecated_encodedQuery")
  @Deprecated(
    message = "moved to val",
    replaceWith = ReplaceWith(expression = "encodedQuery"),
    level = DeprecationLevel.ERROR,
  )
  fun encodedQuery(): String? = encodedQuery

  @JvmName("-deprecated_query")
  @Deprecated(
    message = "moved to val",
    replaceWith = ReplaceWith(expression = "query"),
    level = DeprecationLevel.ERROR,
  )
  fun query(): String? = query

  @JvmName("-deprecated_querySize")
  @Deprecated(
    message = "moved to val",
    replaceWith = ReplaceWith(expression = "querySize"),
    level = DeprecationLevel.ERROR,
  )
  fun querySize(): Int = querySize

  @JvmName("-deprecated_queryParameterNames")
  @Deprecated(
    message = "moved to val",
    replaceWith = ReplaceWith(expression = "queryParameterNames"),
    level = DeprecationLevel.ERROR,
  )
  fun queryParameterNames(): Set<String> = queryParameterNames

  @JvmName("-deprecated_encodedFragment")
  @Deprecated(
    message = "moved to val",
    replaceWith = ReplaceWith(expression = "encodedFragment"),
    level = DeprecationLevel.ERROR,
  )
  fun encodedFragment(): String? = encodedFragment

  @JvmName("-deprecated_fragment")
  @Deprecated(
    message = "moved to val",
    replaceWith = ReplaceWith(expression = "fragment"),
    level = DeprecationLevel.ERROR,
  )
  fun fragment(): String? = fragment

  class Builder {
    internal var scheme: String? = null
    internal var encodedUsername = ""
    internal var encodedPassword = ""
    internal var host: String? = null
    internal var port = -1
    internal val encodedPathSegments = mutableListOf<String>("")
    internal var encodedQueryNamesAndValues: MutableList<String?>? = null
    internal var encodedFragment: String? = null

    /**
     * @param scheme either "http" or "https".
     */
    fun scheme(scheme: String) =
      apply {
        when {
          scheme.equals("http", ignoreCase = true) -> this.scheme = "http"
          scheme.equals("https", ignoreCase = true) -> this.scheme = "https"
          else -> throw IllegalArgumentException("unexpected scheme: $scheme")
        }
      }

    fun username(username: String) =
      apply {
        this.encodedUsername = username.canonicalize(encodeSet = USERNAME_ENCODE_SET)
      }

    fun encodedUsername(encodedUsername: String) =
      apply {
        this.encodedUsername =
          encodedUsername.canonicalize(
            encodeSet = USERNAME_ENCODE_SET,
            alreadyEncoded = true,
          )
      }

    fun password(password: String) =
      apply {
        this.encodedPassword = password.canonicalize(encodeSet = PASSWORD_ENCODE_SET)
      }

    fun encodedPassword(encodedPassword: String) =
      apply {
        this.encodedPassword =
          encodedPassword.canonicalize(
            encodeSet = PASSWORD_ENCODE_SET,
            alreadyEncoded = true,
          )
      }

    /**
     * @param host either a regular hostname, International Domain Name, IPv4 address, or IPv6
     * address.
     */
    fun host(host: String) =
      apply {
        val encoded =
          host.percentDecode().toCanonicalHost()
            ?: throw IllegalArgumentException("unexpected host: $host")
        this.host = encoded
      }

    fun port(port: Int) =
      apply {
        require(port in 1..65535) { "unexpected port: $port" }
        this.port = port
      }

    fun addPathSegment(pathSegment: String) =
      apply {
        push(pathSegment, 0, pathSegment.length, addTrailingSlash = false, alreadyEncoded = false)
      }

    /**
     * Adds a set of path segments separated by a slash (either `\` or `/`). If `pathSegments`
     * starts with a slash, the resulting URL will have empty path segment.
     */
    fun addPathSegments(pathSegments: String): Builder = addPathSegments(pathSegments, false)

    fun addEncodedPathSegment(encodedPathSegment: String) =
      apply {
        push(
          encodedPathSegment,
          0,
          encodedPathSegment.length,
          addTrailingSlash = false,
          alreadyEncoded = true,
        )
      }

    /**
     * Adds a set of encoded path segments separated by a slash (either `\` or `/`). If
     * `encodedPathSegments` starts with a slash, the resulting URL will have empty path segment.
     */
    fun addEncodedPathSegments(encodedPathSegments: String): Builder = addPathSegments(encodedPathSegments, true)

    private fun addPathSegments(
      pathSegments: String,
      alreadyEncoded: Boolean,
    ) = apply {
      var offset = 0
      do {
        val segmentEnd = pathSegments.delimiterOffset("/\\", offset, pathSegments.length)
        val addTrailingSlash = segmentEnd < pathSegments.length
        push(pathSegments, offset, segmentEnd, addTrailingSlash, alreadyEncoded)
        offset = segmentEnd + 1
      } while (offset <= pathSegments.length)
    }

    fun setPathSegment(
      index: Int,
      pathSegment: String,
    ) = apply {
      val canonicalPathSegment = pathSegment.canonicalize(encodeSet = PATH_SEGMENT_ENCODE_SET)
      require(!isDot(canonicalPathSegment) && !isDotDot(canonicalPathSegment)) {
        "unexpected path segment: $pathSegment"
      }
      encodedPathSegments[index] = canonicalPathSegment
    }

    fun setEncodedPathSegment(
      index: Int,
      encodedPathSegment: String,
    ) = apply {
      val canonicalPathSegment =
        encodedPathSegment.canonicalize(
          encodeSet = PATH_SEGMENT_ENCODE_SET,
          alreadyEncoded = true,
        )
      encodedPathSegments[index] = canonicalPathSegment
      require(!isDot(canonicalPathSegment) && !isDotDot(canonicalPathSegment)) {
        "unexpected path segment: $encodedPathSegment"
      }
    }

    fun removePathSegment(index: Int) =
      apply {
        encodedPathSegments.removeAt(index)
        if (encodedPathSegments.isEmpty()) {
          encodedPathSegments.add("") // Always leave at least one '/'.
        }
      }

    fun encodedPath(encodedPath: String) =
      apply {
        require(encodedPath.startsWith("/")) { "unexpected encodedPath: $encodedPath" }
        resolvePath(encodedPath, 0, encodedPath.length)
      }

    fun query(query: String?) =
      apply {
        this.encodedQueryNamesAndValues =
          query
            ?.canonicalize(
              encodeSet = QUERY_ENCODE_SET,
              plusIsSpace = true,
            )?.toQueryNamesAndValues()
      }

    fun encodedQuery(encodedQuery: String?) =
      apply {
        this.encodedQueryNamesAndValues =
          encodedQuery
            ?.canonicalize(
              encodeSet = QUERY_ENCODE_SET,
              alreadyEncoded = true,
              plusIsSpace = true,
            )?.toQueryNamesAndValues()
      }

    /** Encodes the query parameter using UTF-8 and adds it to this URL's query string. */
    fun addQueryParameter(
      name: String,
      value: String?,
    ) = apply {
      if (encodedQueryNamesAndValues == null) encodedQueryNamesAndValues = mutableListOf()
      encodedQueryNamesAndValues!!.add(
        name.canonicalize(
          encodeSet = QUERY_COMPONENT_ENCODE_SET,
          plusIsSpace = true,
        ),
      )
      encodedQueryNamesAndValues!!.add(
        value?.canonicalize(
          encodeSet = QUERY_COMPONENT_ENCODE_SET,
          plusIsSpace = true,
        ),
      )
    }

    /** Adds the pre-encoded query parameter to this URL's query string. */
    fun addEncodedQueryParameter(
      encodedName: String,
      encodedValue: String?,
    ) = apply {
      if (encodedQueryNamesAndValues == null) encodedQueryNamesAndValues = mutableListOf()
      encodedQueryNamesAndValues!!.add(
        encodedName.canonicalize(
          encodeSet = QUERY_COMPONENT_REENCODE_SET,
          alreadyEncoded = true,
          plusIsSpace = true,
        ),
      )
      encodedQueryNamesAndValues!!.add(
        encodedValue?.canonicalize(
          encodeSet = QUERY_COMPONENT_REENCODE_SET,
          alreadyEncoded = true,
          plusIsSpace = true,
        ),
      )
    }

    fun setQueryParameter(
      name: String,
      value: String?,
    ) = apply {
      removeAllQueryParameters(name)
      addQueryParameter(name, value)
    }

    fun setEncodedQueryParameter(
      encodedName: String,
      encodedValue: String?,
    ) = apply {
      removeAllEncodedQueryParameters(encodedName)
      addEncodedQueryParameter(encodedName, encodedValue)
    }

    fun removeAllQueryParameters(name: String) =
      apply {
        if (encodedQueryNamesAndValues == null) return this
        val nameToRemove =
          name.canonicalize(
            encodeSet = QUERY_COMPONENT_ENCODE_SET,
            plusIsSpace = true,
          )
        removeAllCanonicalQueryParameters(nameToRemove)
      }

    fun removeAllEncodedQueryParameters(encodedName: String) =
      apply {
        if (encodedQueryNamesAndValues == null) return this
        removeAllCanonicalQueryParameters(
          encodedName.canonicalize(
            encodeSet = QUERY_COMPONENT_REENCODE_SET,
            alreadyEncoded = true,
            plusIsSpace = true,
          ),
        )
      }

    private fun removeAllCanonicalQueryParameters(canonicalName: String) {
      for (i in encodedQueryNamesAndValues!!.size - 2 downTo 0 step 2) {
        if (canonicalName == encodedQueryNamesAndValues!![i]) {
          encodedQueryNamesAndValues!!.removeAt(i + 1)
          encodedQueryNamesAndValues!!.removeAt(i)
          if (encodedQueryNamesAndValues!!.isEmpty()) {
            encodedQueryNamesAndValues = null
            return
          }
        }
      }
    }

    fun fragment(fragment: String?) =
      apply {
        this.encodedFragment =
          fragment?.canonicalize(
            encodeSet = FRAGMENT_ENCODE_SET,
            unicodeAllowed = true,
          )
      }

    fun encodedFragment(encodedFragment: String?) =
      apply {
        this.encodedFragment =
          encodedFragment?.canonicalize(
            encodeSet = FRAGMENT_ENCODE_SET,
            alreadyEncoded = true,
            unicodeAllowed = true,
          )
      }

    /**
     * Re-encodes the components of this URL so that it satisfies (obsolete) RFC 2396, which is
     * particularly strict for certain components.
     */
    internal fun reencodeForUri() =
      apply {
        host = host?.replace(Regex("[\"<>^`{|}]"), "")

        for (i in 0 until encodedPathSegments.size) {
          encodedPathSegments[i] =
            encodedPathSegments[i].canonicalize(
              encodeSet = PATH_SEGMENT_ENCODE_SET_URI,
              alreadyEncoded = true,
              strict = true,
            )
        }

        val encodedQueryNamesAndValues = this.encodedQueryNamesAndValues
        if (encodedQueryNamesAndValues != null) {
          for (i in 0 until encodedQueryNamesAndValues.size) {
            encodedQueryNamesAndValues[i] =
              encodedQueryNamesAndValues[i]?.canonicalize(
                encodeSet = QUERY_COMPONENT_ENCODE_SET_URI,
                alreadyEncoded = true,
                strict = true,
                plusIsSpace = true,
              )
          }
        }

        encodedFragment =
          encodedFragment?.canonicalize(
            encodeSet = FRAGMENT_ENCODE_SET_URI,
            alreadyEncoded = true,
            strict = true,
            unicodeAllowed = true,
          )
      }

    fun build(): HttpUrl {
      @Suppress("UNCHECKED_CAST") // percentDecode returns either List<String?> or List<String>.
      return HttpUrl(
        scheme = scheme ?: throw IllegalStateException("scheme == null"),
        username = encodedUsername.percentDecode(),
        password = encodedPassword.percentDecode(),
        host = host ?: throw IllegalStateException("host == null"),
        port = effectivePort(),
        pathSegments = encodedPathSegments.map { it.percentDecode() },
        queryNamesAndValues = encodedQueryNamesAndValues?.map { it?.percentDecode(plusIsSpace = true) },
        fragment = encodedFragment?.percentDecode(),
        url = toString(),
      )
    }

    private fun effectivePort(): Int = if (port != -1) port else defaultPort(scheme!!)

    override fun toString(): String =
      buildString {
        if (scheme != null) {
          append(scheme)
          append("://")
        } else {
          append("//")
        }

        if (encodedUsername.isNotEmpty() || encodedPassword.isNotEmpty()) {
          append(encodedUsername)
          if (encodedPassword.isNotEmpty()) {
            append(':')
            append(encodedPassword)
          }
          append('@')
        }

        if (host != null) {
          if (':' in host!!) {
            // Host is an IPv6 address.
            append('[')
            append(host)
            append(']')
          } else {
            append(host)
          }
        }

        if (port != -1 || scheme != null) {
          val effectivePort = effectivePort()
          if (scheme == null || effectivePort != defaultPort(scheme!!)) {
            append(':')
            append(effectivePort)
          }
        }

        encodedPathSegments.toPathString(this)

        if (encodedQueryNamesAndValues != null) {
          append('?')
          encodedQueryNamesAndValues!!.toQueryString(this)
        }

        if (encodedFragment != null) {
          append('#')
          append(encodedFragment)
        }
      }

    /** Returns a path string for this list of path segments. */
    private fun List<String>.toPathString(out: StringBuilder) {
      for (i in 0 until size) {
        out.append('/')
        out.append(this[i])
      }
    }

    internal fun parse(
      base: HttpUrl?,
      input: String,
    ): Builder {
      var pos = input.indexOfFirstNonAsciiWhitespace()
      val limit = input.indexOfLastNonAsciiWhitespace(pos)

      // Scheme.
      val schemeDelimiterOffset = schemeDelimiterOffset(input, pos, limit)
      if (schemeDelimiterOffset != -1) {
        when {
          input.startsWith("https:", ignoreCase = true, startIndex = pos) -> {
            this.scheme = "https"
            pos += "https:".length
          }

          input.startsWith("http:", ignoreCase = true, startIndex = pos) -> {
            this.scheme = "http"
            pos += "http:".length
          }

          else -> {
            throw IllegalArgumentException(
              "Expected URL scheme 'http' or 'https' but was '" +
                input.substring(0, schemeDelimiterOffset) + "'",
            )
          }
        }
      } else if (base != null) {
        this.scheme = base.scheme
      } else {
        val truncated = if (input.length > 6) input.take(6) + "..." else input
        throw IllegalArgumentException(
          "Expected URL scheme 'http' or 'https' but no scheme was found for $truncated",
        )
      }

      // Authority.
      var hasUsername = false
      var hasPassword = false
      val slashCount = input.slashCount(pos, limit)
      if (slashCount >= 2 || base == null || base.scheme != this.scheme) {
        // Read an authority if either:
        //  * The input starts with 2 or more slashes. These follow the scheme if it exists.
        //  * The input scheme exists and is different from the base URL's scheme.
        //
        // The structure of an authority is:
        //   username:password@host:port
        //
        // Username, password and port are optional.
        //   [username[:password]@]host[:port]
        pos += slashCount
        authority@ while (true) {
          val componentDelimiterOffset = input.delimiterOffset("@/\\?#", pos, limit)
          val c =
            if (componentDelimiterOffset != limit) {
              input[componentDelimiterOffset].code
            } else {
              -1
            }
          when (c) {
            '@'.code -> {
              // User info precedes.
              if (!hasPassword) {
                val passwordColonOffset = input.delimiterOffset(':', pos, componentDelimiterOffset)
                val canonicalUsername =
                  input.canonicalize(
                    pos = pos,
                    limit = passwordColonOffset,
                    encodeSet = USERNAME_ENCODE_SET,
                    alreadyEncoded = true,
                  )
                this.encodedUsername =
                  if (hasUsername) {
                    this.encodedUsername + "%40" + canonicalUsername
                  } else {
                    canonicalUsername
                  }
                if (passwordColonOffset != componentDelimiterOffset) {
                  hasPassword = true
                  this.encodedPassword =
                    input.canonicalize(
                      pos = passwordColonOffset + 1,
                      limit = componentDelimiterOffset,
                      encodeSet = PASSWORD_ENCODE_SET,
                      alreadyEncoded = true,
                    )
                }
                hasUsername = true
              } else {
                this.encodedPassword = this.encodedPassword + "%40" +
                  input.canonicalize(
                    pos = pos,
                    limit = componentDelimiterOffset,
                    encodeSet = PASSWORD_ENCODE_SET,
                    alreadyEncoded = true,
                  )
              }
              pos = componentDelimiterOffset + 1
            }

            -1, '/'.code, '\\'.code, '?'.code, '#'.code -> {
              // Host info precedes.
              val portColonOffset = portColonOffset(input, pos, componentDelimiterOffset)
              if (portColonOffset + 1 < componentDelimiterOffset) {
                host = input.percentDecode(pos = pos, limit = portColonOffset).toCanonicalHost()
                port = parsePort(input, portColonOffset + 1, componentDelimiterOffset)
                require(port != -1) {
                  "Invalid URL port: \"${input.substring(
                    portColonOffset + 1,
                    componentDelimiterOffset,
                  )}\""
                }
              } else {
                host = input.percentDecode(pos = pos, limit = portColonOffset).toCanonicalHost()
                port = defaultPort(scheme!!)
              }
              require(host != null) {
                "Invalid URL host: \"${input.substring(pos, portColonOffset)}\""
              }
              pos = componentDelimiterOffset
              break@authority
            }
          }
        }
      } else {
        // This is a relative link. Copy over all authority components. Also maybe the path & query.
        this.encodedUsername = base.encodedUsername
        this.encodedPassword = base.encodedPassword
        this.host = base.host
        this.port = base.port
        this.encodedPathSegments.clear()
        this.encodedPathSegments.addAll(base.encodedPathSegments)
        if (pos == limit || input[pos] == '#') {
          encodedQuery(base.encodedQuery)
        }
      }

      // Resolve the relative path.
      val pathDelimiterOffset = input.delimiterOffset("?#", pos, limit)
      resolvePath(input, pos, pathDelimiterOffset)
      pos = pathDelimiterOffset

      // Query.
      if (pos < limit && input[pos] == '?') {
        val queryDelimiterOffset = input.delimiterOffset('#', pos, limit)
        this.encodedQueryNamesAndValues =
          input
            .canonicalize(
              pos = pos + 1,
              limit = queryDelimiterOffset,
              encodeSet = QUERY_ENCODE_SET,
              alreadyEncoded = true,
              plusIsSpace = true,
            ).toQueryNamesAndValues()
        pos = queryDelimiterOffset
      }

      // Fragment.
      if (pos < limit && input[pos] == '#') {
        this.encodedFragment =
          input.canonicalize(
            pos = pos + 1,
            limit = limit,
            encodeSet = FRAGMENT_ENCODE_SET,
            alreadyEncoded = true,
            unicodeAllowed = true,
          )
      }

      return this
    }

    private fun resolvePath(
      input: String,
      startPos: Int,
      limit: Int,
    ) {
      var pos = startPos
      // Read a delimiter.
      if (pos == limit) {
        // Empty path: keep the base path as-is.
        return
      }
      val c = input[pos]
      if (c == '/' || c == '\\') {
        // Absolute path: reset to the default "/".
        encodedPathSegments.clear()
        encodedPathSegments.add("")
        pos++
      } else {
        // Relative path: clear everything after the last '/'.
        encodedPathSegments[encodedPathSegments.size - 1] = ""
      }

      // Read path segments.
      var i = pos
      while (i < limit) {
        val pathSegmentDelimiterOffset = input.delimiterOffset("/\\", i, limit)
        val segmentHasTrailingSlash = pathSegmentDelimiterOffset < limit
        push(input, i, pathSegmentDelimiterOffset, segmentHasTrailingSlash, true)
        i = pathSegmentDelimiterOffset
        if (segmentHasTrailingSlash) i++
      }
    }

    /** Adds a path segment. If the input is ".." or equivalent, this pops a path segment. */
    private fun push(
      input: String,
      pos: Int,
      limit: Int,
      addTrailingSlash: Boolean,
      alreadyEncoded: Boolean,
    ) {
      val segment =
        input.canonicalize(
          pos = pos,
          limit = limit,
          encodeSet = PATH_SEGMENT_ENCODE_SET,
          alreadyEncoded = alreadyEncoded,
        )
      if (isDot(segment)) {
        return // Skip '.' path segments.
      }
      if (isDotDot(segment)) {
        pop()
        return
      }
      if (encodedPathSegments[encodedPathSegments.size - 1].isEmpty()) {
        encodedPathSegments[encodedPathSegments.size - 1] = segment
      } else {
        encodedPathSegments.add(segment)
      }
      if (addTrailingSlash) {
        encodedPathSegments.add("")
      }
    }

    /**
     * Removes a path segment. When this method returns the last segment is always "", which means
     * the encoded path will have a trailing '/'.
     *
     * Popping "/a/b/c/" yields "/a/b/". In this case the list of path segments goes from ["a",
     * "b", "c", ""] to ["a", "b", ""].
     *
     * Popping "/a/b/c" also yields "/a/b/". The list of path segments goes from ["a", "b", "c"]
     * to ["a", "b", ""].
     */
    private fun pop() {
      val removed = encodedPathSegments.removeAt(encodedPathSegments.size - 1)

      // Make sure the path ends with a '/' by either adding an empty string or clearing a segment.
      if (removed.isEmpty() && encodedPathSegments.isNotEmpty()) {
        encodedPathSegments[encodedPathSegments.size - 1] = ""
      } else {
        encodedPathSegments.add("")
      }
    }

    private fun isDot(input: String): Boolean = input == "." || input.equals("%2e", ignoreCase = true)

    private fun isDotDot(input: String): Boolean =
      input == ".." ||
        input.equals("%2e.", ignoreCase = true) ||
        input.equals(".%2e", ignoreCase = true) ||
        input.equals("%2e%2e", ignoreCase = true)

    /**
     * Cuts this string up into alternating parameter names and values. This divides a query string
     * like `subject=math&easy&problem=5-2=3` into the list `["subject", "math", "easy", null,
     * "problem", "5-2=3"]`. Note that values may be null and may contain '=' characters.
     */
    private fun String.toQueryNamesAndValues(): MutableList<String?> {
      val result = mutableListOf<String?>()
      var pos = 0
      while (pos <= length) {
        var ampersandOffset = indexOf('&', pos)
        if (ampersandOffset == -1) ampersandOffset = length

        val equalsOffset = indexOf('=', pos)
        if (equalsOffset == -1 || equalsOffset > ampersandOffset) {
          result.add(substring(pos, ampersandOffset))
          result.add(null) // No value for this name.
        } else {
          result.add(substring(pos, equalsOffset))
          result.add(substring(equalsOffset + 1, ampersandOffset))
        }
        pos = ampersandOffset + 1
      }
      return result
    }

    /**
     * Returns the index of the ':' in `input` that is after scheme characters. Returns -1 if
     * `input` does not have a scheme that starts at `pos`.
     */
    private fun schemeDelimiterOffset(
      input: String,
      pos: Int,
      limit: Int,
    ): Int {
      if (limit - pos < 2) return -1

      val c0 = input[pos]
      if ((c0 < 'a' || c0 > 'z') && (c0 < 'A' || c0 > 'Z')) return -1 // Not a scheme start char.

      characters@ for (i in pos + 1 until limit) {
        return when (input[i]) {
          // Scheme character. Keep going.
          in 'a'..'z', in 'A'..'Z', in '0'..'9', '+', '-', '.' -> continue@characters

          // Scheme prefix!
          ':' -> i

          // Non-scheme character before the first ':'.
          else -> -1
        }
      }

      return -1 // No ':'; doesn't start with a scheme.
    }

    /** Returns the number of '/' and '\' slashes in this, starting at `pos`. */
    private fun String.slashCount(
      pos: Int,
      limit: Int,
    ): Int {
      var slashCount = 0
      for (i in pos until limit) {
        val c = this[i]
        if (c == '\\' || c == '/') {
          slashCount++
        } else {
          break
        }
      }
      return slashCount
    }

    /** Finds the first ':' in `input`, skipping characters between square braces "[...]". */
    private fun portColonOffset(
      input: String,
      pos: Int,
      limit: Int,
    ): Int {
      var i = pos
      while (i < limit) {
        when (input[i]) {
          '[' -> {
            while (++i < limit) {
              if (input[i] == ']') break
            }
          }

          ':' -> {
            return i
          }
        }
        i++
      }
      return limit // No colon.
    }

    private fun parsePort(
      input: String,
      pos: Int,
      limit: Int,
    ): Int =
      try {
        // Canonicalize the port string to skip '\n' etc.
        val portString = input.canonicalize(pos = pos, limit = limit, encodeSet = "")
        val i = portString.toInt()
        if (i in 1..65535) i else -1
      } catch (_: NumberFormatException) {
        -1 // Invalid port.
      }
  }

  companion object {
    /** Returns 80 if `scheme.equals("http")`, 443 if `scheme.equals("https")` and -1 otherwise. */
    @JvmStatic
    fun defaultPort(scheme: String): Int =
      when (scheme) {
        "http" -> 80
        "https" -> 443
        else -> -1
      }

    /** Returns a string for this list of query names and values. */
    private fun List<String?>.toQueryString(out: StringBuilder) {
      for (i in 0 until size step 2) {
        val name = this[i]
        val value = this[i + 1]
        if (i > 0) out.append('&')
        out.append(name)
        if (value != null) {
          out.append('=')
          out.append(value)
        }
      }
    }

    /**
     * Returns a new [HttpUrl] representing this.
     *
     * @throws IllegalArgumentException If this is not a well-formed HTTP or HTTPS URL.
     */
    @JvmStatic
    @JvmName("get")
    fun String.toHttpUrl(): HttpUrl = Builder().parse(null, this).build()

    /**
     * Returns a new `HttpUrl` representing `url` if it is a well-formed HTTP or HTTPS URL, or null
     * if it isn't.
     */
    @JvmStatic
    @JvmName("parse")
    fun String.toHttpUrlOrNull(): HttpUrl? =
      try {
        toHttpUrl()
      } catch (_: IllegalArgumentException) {
        null
      }

    /**
     * Returns an [HttpUrl] for this if its protocol is `http` or `https`, or null if it has any
     * other protocol.
     */
    @JvmStatic
    @JvmName("get")
    fun URL.toHttpUrlOrNull(): HttpUrl? = toString().toHttpUrlOrNull()

    @JvmStatic
    @JvmName("get")
    fun URI.toHttpUrlOrNull(): HttpUrl? = toString().toHttpUrlOrNull()

    @JvmName("-deprecated_get")
    @Deprecated(
      message = "moved to extension function",
      replaceWith =
        ReplaceWith(
          expression = "url.toHttpUrl()",
          imports = ["okhttp3.HttpUrl.Companion.toHttpUrl"],
        ),
      level = DeprecationLevel.ERROR,
    )
    fun get(url: String): HttpUrl = url.toHttpUrl()

    @JvmName("-deprecated_parse")
    @Deprecated(
      message = "moved to extension function",
      replaceWith =
        ReplaceWith(
          expression = "url.toHttpUrlOrNull()",
          imports = ["okhttp3.HttpUrl.Companion.toHttpUrlOrNull"],
        ),
      level = DeprecationLevel.ERROR,
    )
    fun parse(url: String): HttpUrl? = url.toHttpUrlOrNull()

    @JvmName("-deprecated_get")
    @Deprecated(
      message = "moved to extension function",
      replaceWith =
        ReplaceWith(
          expression = "url.toHttpUrlOrNull()",
          imports = ["okhttp3.HttpUrl.Companion.toHttpUrlOrNull"],
        ),
      level = DeprecationLevel.ERROR,
    )
    fun get(url: URL): HttpUrl? = url.toHttpUrlOrNull()

    @JvmName("-deprecated_get")
    @Deprecated(
      message = "moved to extension function",
      replaceWith =
        ReplaceWith(
          expression = "uri.toHttpUrlOrNull()",
          imports = ["okhttp3.HttpUrl.Companion.toHttpUrlOrNull"],
        ),
      level = DeprecationLevel.ERROR,
    )
    fun get(uri: URI): HttpUrl? = uri.toHttpUrlOrNull()
  }
}
