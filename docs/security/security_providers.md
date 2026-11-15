
Security Providers
==================

## Provider Status

| Provider         | HTTP/2  | TLSv1.3      | Powered By      | Notes                                                        |
| :--------------- | :------ | :----------- | :-------------- | :----------------------------------------------------------- |
| JVM default      | Java 9+ | Java 11+     | [OpenJDK]       |                                                              |
| Android default  | ✅      | Android 10+  | [BoringSSL]     |                                                              |
| [GraalVM]        | ✅      |              | [OpenJDK]       | Only actively tested with JDK 11, not with 8 target          |
| [Bouncy Castle]  | ✅      |              | [Bouncy Castle] | [Tracking bug.][bug5698]                                     |
| [Conscrypt]      | ✅      | ✅           | [BoringSSL]     | Activated if Conscrypt is first registered provider.         |
| [OpenJSSE]       |         | ✅           | [OpenJDK]       | OpenJDK backport.                                            |
| [Corretto]       | ✅      | ✅           | [OpenSSL]       | Amazon's high-performance provider. [Tracking bug.][bug5592] |

All providers support HTTP/1.1 and TLSv1.2.


[BoringSSL]: https://boringssl.googlesource.com/boringssl/
[Bouncy Castle]: https://www.bouncycastle.org/java.html
[Conscrypt]: https://www.conscrypt.org/
[Corretto]: https://github.com/corretto/amazon-corretto-crypto-provider
[GraalVM]: https://www.graalvm.org/
[OpenJDK]: https://openjdk.java.net/groups/security/
[OpenJSSE]: https://github.com/openjsse/openjsse
[OpenSSL]: https://www.openssl.org/
[bug5592]: https://github.com/lysine-dev/okhttp/issues/5592
[bug5698]: https://github.com/lysine-dev/okhttp/issues/5698
