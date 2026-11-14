HTTPS
=====

OkHttp attempts to balance two competing concerns:

 * **Connectivity** to as many hosts as possible. That includes advanced hosts that run the latest versions of [boringssl](https://boringssl.googlesource.com/boringssl/) and less out of date hosts running older versions of [OpenSSL](https://www.openssl.org/).
 * **Security** of the connection. This includes verification of the remote webserver with certificates and the privacy of data exchanged with strong ciphers.

When negotiating a connection to an HTTPS server, OkHttp needs to know which [TLS versions](https://lysine.dev/okhttp/5.x/okhttp/okhttp3/-tls-version/) and [cipher suites](https://lysine.dev/okhttp/5.x/okhttp/okhttp3/-cipher-suite/) to offer. A client that wants to maximize connectivity would include obsolete TLS versions and weak-by-design cipher suites. A strict client that wants to maximize security would be limited to only the latest TLS version and strongest cipher suites.

Specific security vs. connectivity decisions are implemented by [ConnectionSpec](https://lysine.dev/okhttp/5.x/okhttp/okhttp3/-connection-spec/). OkHttp includes four built-in connection specs:

 * `RESTRICTED_TLS` is a secure configuration, intended to meet stricter compliance requirements.
 * `MODERN_TLS` is a secure configuration that connects to modern HTTPS servers.
 * `COMPATIBLE_TLS` is a secure configuration that connects to secure–but not current–HTTPS servers.
 * `CLEARTEXT` is an insecure configuration that is used for `http://` URLs.

These loosely follow the model set in [Google Cloud Policies](https://cloud.google.com/load-balancing/docs/ssl-policies-concepts). We [track changes](../security/tls_configuration_history.md) to this policy.

By default, OkHttp will attempt a `MODERN_TLS` connection.  However by configuring the client connectionSpecs you can allow a fall back to `COMPATIBLE_TLS` connection if the modern configuration fails.

```java
OkHttpClient client = new OkHttpClient.Builder()
    .connectionSpecs(Arrays.asList(ConnectionSpec.MODERN_TLS, ConnectionSpec.COMPATIBLE_TLS))
    .build();
```

The TLS versions and cipher suites in each spec can change with each release. For example, in OkHttp 2.2 we dropped support for SSL 3.0 in response to the [POODLE](https://googleonlinesecurity.blogspot.ca/2014/10/this-poodle-bites-exploiting-ssl-30.html) attack. And in OkHttp 2.3 we dropped support for [RC4](https://en.wikipedia.org/wiki/RC4#Security). As with your desktop web browser, staying up-to-date with OkHttp is the best way to stay secure.

You can build your own connection spec with a custom set of TLS versions and cipher suites. For example, this configuration is limited to three highly-regarded cipher suites. Its drawback is that it requires Android 5.0+ and a similarly current webserver.

```java
ConnectionSpec spec = new ConnectionSpec.Builder(ConnectionSpec.MODERN_TLS)
    .tlsVersions(TlsVersion.TLS_1_2)
    .cipherSuites(
          CipherSuite.TLS_ECDHE_ECDSA_WITH_AES_128_GCM_SHA256,
          CipherSuite.TLS_ECDHE_RSA_WITH_AES_128_GCM_SHA256,
          CipherSuite.TLS_DHE_RSA_WITH_AES_128_GCM_SHA256)
    .build();

OkHttpClient client = new OkHttpClient.Builder()
    .connectionSpecs(Collections.singletonList(spec))
    .build();
```

### Debugging TLS Handshake Failures

The TLS handshake requires clients and servers to share a common TLS version and cipher suite. This
depends on the JVM or Android version, OkHttp version, and web server configuration. If there is no
common cipher suite and TLS version, your call will fail like this:

```
Caused by: javax.net.ssl.SSLProtocolException: SSL handshake aborted: ssl=0x7f2719a89e80:
    Failure in SSL library, usually a protocol error
        error:14077410:SSL routines:SSL23_GET_SERVER_HELLO:sslv3 alert handshake
        failure (external/openssl/ssl/s23_clnt.c:770 0x7f2728a53ea0:0x00000000)
    at com.android.org.conscrypt.NativeCrypto.SSL_do_handshake(Native Method)
```

You can check a web server's configuration using [Qualys SSL Labs][qualys]. OkHttp's TLS
configuration history is [tracked here](../security/tls_configuration_history.md).

Applications expected to be installed on older Android devices should consider adopting the
[Google Play Services’ ProviderInstaller][provider_installer]. This will increase security for users
and increase connectivity with web servers.

### Certificate Pinning ([.kt][CertificatePinningKotlin], [.java][CertificatePinningJava])

By default, OkHttp trusts the certificate authorities of the host platform. This strategy maximizes connectivity, but it is subject to certificate authority attacks such as the [2011 DigiNotar attack](https://www.computerworld.com/article/2510951/cybercrime-hacking/hackers-spied-on-300-000-iranians-using-fake-google-certificate.html). It also assumes your HTTPS servers’ certificates are signed by a certificate authority.

Use [CertificatePinner](https://lysine.dev/okhttp/5.x/okhttp/okhttp3/-certificate-pinner/) to restrict which certificates and certificate authorities are trusted. Certificate pinning increases security, but limits your server team’s abilities to update their TLS certificates. **Do not use certificate pinning without the blessing of your server’s TLS administrator!**

=== ":material-language-kotlin: Kotlin"
    ```kotlin
      private val client = OkHttpClient.Builder()
          .certificatePinner(
              CertificatePinner.Builder()
                  .add("publicobject.com", "sha256/afwiKY3RxoMmLkuRW1l7QsPZTJPwDS2pdDROQjXw8ig=")
                  .build())
          .build()

      fun run() {
        val request = Request.Builder()
            .url("https://publicobject.com/robots.txt")
            .build()

        client.newCall(request).execute().use { response ->
          if (!response.isSuccessful) throw IOException("Unexpected code $response")

          for (certificate in response.handshake!!.peerCertificates) {
            println(CertificatePinner.pin(certificate))
          }
        }
      }
    ```
=== ":material-language-java: Java"
    ```java
      private final OkHttpClient client = new OkHttpClient.Builder()
          .certificatePinner(
              new CertificatePinner.Builder()
                  .add("publicobject.com", "sha256/afwiKY3RxoMmLkuRW1l7QsPZTJPwDS2pdDROQjXw8ig=")
                  .build())
          .build();

      public void run() throws Exception {
        Request request = new Request.Builder()
            .url("https://publicobject.com/robots.txt")
            .build();

        try (Response response = client.newCall(request).execute()) {
          if (!response.isSuccessful()) throw new IOException("Unexpected code " + response);

          for (Certificate certificate : response.handshake().peerCertificates()) {
            System.out.println(CertificatePinner.pin(certificate));
          }
        }
      }
    ```

### Customizing Trusted Certificates ([.kt][CustomTrustKotlin], [.java][CustomTrustJava])

The full code sample shows how to replace the host platform’s certificate authorities with your own set. As above, **do not use custom certificates without the blessing of your server’s TLS administrator!**

=== ":material-language-kotlin: Kotlin"
    ```kotlin
      private val client: OkHttpClient

      init {
        val trustManager = trustManagerForCertificates(trustedCertificatesInputStream())
        val sslContext = SSLContext.getInstance("TLS")
        sslContext.init(null, arrayOf<TrustManager>(trustManager), null)
        val sslSocketFactory = sslContext.socketFactory

        client = OkHttpClient.Builder()
            .sslSocketFactory(sslSocketFactory, trustManager)
            .build()
      }

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

      /**
       * Returns an input stream containing one or more certificate PEM files. This implementation just
       * embeds the PEM files in Java strings; most applications will instead read this from a resource
       * file that gets bundled with the application.
       */
      private fun trustedCertificatesInputStream(): InputStream {
        ... // Full source omitted. See sample.
      }

      private fun trustManagerForCertificates(inputStream: InputStream): X509TrustManager {
        ... // Full source omitted. See sample.
      }
    ```
=== ":material-language-java: Java"
    ```java
      private final OkHttpClient client;

      public CustomTrust() {
        X509TrustManager trustManager;
        SSLSocketFactory sslSocketFactory;
        try {
          trustManager = trustManagerForCertificates(trustedCertificatesInputStream());
          SSLContext sslContext = SSLContext.getInstance("TLS");
          sslContext.init(null, new TrustManager[] { trustManager }, null);
          sslSocketFactory = sslContext.getSocketFactory();
        } catch (GeneralSecurityException e) {
          throw new RuntimeException(e);
        }

        client = new OkHttpClient.Builder()
            .sslSocketFactory(sslSocketFactory, trustManager)
            .build();
      }

      public void run() throws Exception {
        Request request = new Request.Builder()
            .url("https://publicobject.com/helloworld.txt")
            .build();

        Response response = client.newCall(request).execute();
        System.out.println(response.body().string());
      }

      private InputStream trustedCertificatesInputStream() {
        ... // Full source omitted. See sample.
      }

      public SSLContext sslContextForTrustedCertificates(InputStream in) {
        ... // Full source omitted. See sample.
      }
    ```

 [CustomTrustJava]: https://github.com/lysine-dev/okhttp/blob/master/samples/guide/src/main/java/okhttp3/recipes/CustomTrust.java
 [CustomTrustKotlin]: https://github.com/lysine-dev/okhttp/blob/master/samples/guide/src/main/java/okhttp3/recipes/kt/CustomTrust.kt
 [CertificatePinningJava]: https://github.com/lysine-dev/okhttp/blob/master/samples/guide/src/main/java/okhttp3/recipes/CertificatePinning.java
 [CertificatePinningKotlin]: https://github.com/lysine-dev/okhttp/blob/master/samples/guide/src/main/java/okhttp3/recipes/kt/CertificatePinning.kt
 [provider_installer]: https://developer.android.com/training/articles/security-gms-provider
 [qualys]: https://www.ssllabs.com/ssltest/
