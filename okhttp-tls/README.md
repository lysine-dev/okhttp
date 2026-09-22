OkHttp TLS
==========

Approachable APIs for using TLS.

A [`HeldCertificate`][held_certificate] is a certificate and its private key. Use the
[builder][held_certificate_builder] to create a self-signed certificate that a test server can use
for HTTPS:

```java
HeldCertificate localhostCertificate = new HeldCertificate.Builder()
    .addSubjectAlternativeName("localhost")
    .build();
```

[`HandshakeCertificates`][handshake_certificates] keeps the certificates for a TLS handshake.
Use its [builder][handshake_certificates_builder] to define which certificates the HTTPS server
returns to its clients. The returned instance can create an `SSLSocketFactory` that implements this
policy:

```java
HandshakeCertificates serverCertificates = new HandshakeCertificates.Builder()
    .heldCertificate(localhostCertificate)
    .build();
MockWebServer server = new MockWebServer();
server.useHttps(serverCertificates.sslSocketFactory(), false);
```

`HandshakeCertificates` also works for clients where its job is to define which root certificates
to trust. In this simplified example we trust the server's self-signed certificate:

```java
HandshakeCertificates clientCertificates = new HandshakeCertificates.Builder()
    .addTrustedCertificate(localhostCertificate.certificate())
    .build();
OkHttpClient client = new OkHttpClient.Builder()
    .sslSocketFactory(clientCertificates.sslSocketFactory(), clientCertificates.trustManager())
    .build();
```

With a server that holds a certificate and a client that trusts it we have enough for an HTTPS
handshake. The best part of this example is that we don't need to make our test code insecure with a
a fake `HostnameVerifier` or `X509TrustManager`.

Certificate Authorities
-----------------------

The above example uses a self-signed certificate. This is convenient for testing but not
representative of real-world HTTPS deployment. To get closer to that we can use `HeldCertificate`
to generate a trusted root certificate, an intermediate certificate, and a server certificate.
We use `certificateAuthority(int)` to create certificates that can sign other certificates. The
int specifies how many intermediate certificates are allowed beneath it in the chain.

```java
HeldCertificate rootCertificate = new HeldCertificate.Builder()
    .certificateAuthority(1)
    .build();

HeldCertificate intermediateCertificate = new HeldCertificate.Builder()
    .certificateAuthority(0)
    .signedBy(rootCertificate)
    .build();

HeldCertificate serverCertificate = new HeldCertificate.Builder()
    .addSubjectAlternativeName("localhost")
    .signedBy(intermediateCertificate)
    .build();
```

To serve this configuration the server needs to provide its clients with a chain of certificates
starting with its own and including everything up-to but not including the root. We don't need to
include root certificates because the client already has them.

```java
HandshakeCertificates serverHandshakeCertificates = new HandshakeCertificates.Builder()
    .heldCertificate(serverCertificate, intermediateCertificate.certificate())
    .build();
```

The client only needs to know the trusted root certificate. It checks the server's certificate by
validating the signatures within the chain.

```java
HandshakeCertificates clientCertificates = new HandshakeCertificates.Builder()
    .addTrustedCertificate(rootCertificate.certificate())
    .build();
OkHttpClient client = new OkHttpClient.Builder()
    .sslSocketFactory(clientCertificates.sslSocketFactory(), clientCertificates.trustManager())
    .build();
```

Client Authentication
---------------------

The above scenario is representative of most TLS set ups: the client uses certificates to validate
the identity of a server. The converse is also possible. Here we create a server that authenticates
a client and a client that authenticates a server.

```java
// Create the root for client and server to trust. We could also use different roots for each!
HeldCertificate rootCertificate = new HeldCertificate.Builder()
    .certificateAuthority(0)
    .build();

// Create a server certificate and a server that uses it.
HeldCertificate serverCertificate = new HeldCertificate.Builder()
    .commonName("ingen")
    .addSubjectAlternativeName(server.getHostName())
    .signedBy(rootCertificate)
    .build();
HandshakeCertificates serverCertificates = new HandshakeCertificates.Builder()
    .addTrustedCertificate(rootCertificate.certificate())
    .heldCertificate(serverCertificate)
    .build();
MockWebServer server = new MockWebServer();
server.useHttps(serverCertificates.sslSocketFactory(), false);
server.requestClientAuth();
server.enqueue(new MockResponse());

// Create a client certificate and a client that uses it.
HeldCertificate clientCertificate = new HeldCertificate.Builder()
    .commonName("ianmalcolm")
    .signedBy(rootCertificate)
    .build();
HandshakeCertificates clientCertificates = new HandshakeCertificates.Builder()
    .addTrustedCertificate(rootCertificate.certificate())
    .heldCertificate(clientCertificate)
    .build();
OkHttpClient client = new OkHttpClient.Builder()
    .sslSocketFactory(clientCertificates.sslSocketFactory(), clientCertificates.trustManager())
    .build();

// Connect 'em all together. Certificates are exchanged in the handshake.
Call call = client.newCall(new Request.Builder()
    .url(server.url("/"))
    .build());
Response response = call.execute();
System.out.println(response.handshake().peerPrincipal());
RecordedRequest recordedRequest = server.takeRequest();
System.out.println(recordedRequest.getHandshake().peerPrincipal());
```

This handshake is successful because each party has prearranged to trust the root certificate that
signs the other party's chain.

Well-Known Certificate Authorities
----------------------------------

In these examples we've prearranged which root certificates to trust. But for regular HTTPS on the
Internet this set of trusted root certificates is usually provided by default by the host platform.
Such a set typically includes many root certificates from well-known certificate authorities like
Entrust and Verisign.

This is the behavior you'll get with your OkHttpClient if you don't specifically configure
`HandshakeCertificates`. Or you can do it explicitly with `addPlatformTrustedCertificates()`:

```java
HandshakeCertificates clientCertificates = new HandshakeCertificates.Builder()
    .addPlatformTrustedCertificates()
    .build();
OkHttpClient client = new OkHttpClient.Builder()
    .sslSocketFactory(clientCertificates.sslSocketFactory(), clientCertificates.trustManager())
    .build();
```

PEM files
---------

You can encode a `HeldCertificate` in PEM format:

```java
HeldCertificate heldCertificate = ...
System.out.println(heldCertificate.certificatePem())
```

```
-----BEGIN CERTIFICATE-----
MIIBSjCB8aADAgECAgEBMAoGCCqGSM49BAMCMC8xLTArBgNVBAMTJDJiYWY3NzVl
LWE4MzUtNDM5ZS1hYWE2LTgzNmNiNDlmMGM3MTAeFw0xODA3MTMxMjA0MzJaFw0x
ODA3MTQxMjA0MzJaMC8xLTArBgNVBAMTJDJiYWY3NzVlLWE4MzUtNDM5ZS1hYWE2
LTgzNmNiNDlmMGM3MTBZMBMGByqGSM49AgEGCCqGSM49AwEHA0IABDmlOiZ3dxA2
zw1KwqGNsKVUZbkUVj5cxV1jDbSTvTlOjSj6LR0Ovys9RFdrjcbbMLWvSvMQgHch
k8Q50c6Kb34wCgYIKoZIzj0EAwIDSAAwRQIhAJkXiCbIR3zxuH5SQR5PEAPJ+ntg
msOSMaAKwAePESf+AiBlxbEu6YpHt1ZJoAhMAv6raYnwSp/A94eJGlJynQ0igQ==
-----END CERTIFICATE-----
```

You can also do so with the private key. Be careful with these!

```java
HeldCertificate heldCertificate = ...
System.out.println(heldCertificate.privateKeyPkcs8Pem())
```

```
-----BEGIN PRIVATE KEY-----
MIGTAgEAMBMGByqGSM49AgEGCCqGSM49AwEHBHkwdwIBAQQgQbYDQiewSnregm9e
IjXEHQgc6w3ELHdnH1houEUom9CgCgYIKoZIzj0DAQehRANCAAQ5pTomd3cQNs8N
SsKhjbClVGW5FFY+XMVdYw20k705To0o+i0dDr8rPURXa43G2zC1r0rzEIB3IZPE
OdHOim9+
-----END PRIVATE KEY-----
```

Recommendations
---------------

Typically servers need a held certificate plus a chain of intermediates. Servers only need the
private key for their own certificate. The chain served by a server doesn't need the root
certificate.

The trusted roots don't need to be the same for client and server when using client authentication.
Clients might rely on the platform certificates and servers might use a private
organization-specific certificate authority.

By default `HeldCertificate` instances expire after 24 hours. Use `duration()` to adjust.

By default server certificates need to identify which hostnames they're trusted for. You may add as
many as necessary with `addSubjectAlternativeName()`. This mechanism also supports a very limited
form of wildcards `*.example.com` where the `*` must be first and doesn't match nested subdomains.

By default certificates use fast and secure 256-bit ECDSA keys. For interoperability with very old
clients use `HeldCertificate.Builder.rsa2048()`.

Download
--------

```kotlin
implementation("com.squareup.okhttp3:okhttp-tls:5.5.0")
```

 [held_certificate]: https://lysine.dev/okhttp/5.x/okhttp-tls/okhttp3.tls/-held-certificate/
 [held_certificate_builder]: https://lysine.dev/okhttp/5.x/okhttp-tls/okhttp3.tls/-held-certificate/-builder/
 [handshake_certificates]: https://lysine.dev/okhttp/5.x/okhttp-tls/okhttp3.tls/-handshake-certificates/
 [handshake_certificates_builder]: https://lysine.dev/okhttp/5.x/okhttp-tls/okhttp3.tls/-handshake-certificates/-builder/
