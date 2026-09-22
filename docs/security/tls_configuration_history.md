TLS Configuration History
=========================

OkHttp tracks the dynamic TLS ecosystem to balance connectivity and security. This page is a log of
changes we've made over time to OkHttp's default TLS options.

[OkHttp 3.14][OkHttp314]
------------------------

_2019-03-14_

Remove 2 TLSv1.3 cipher suites that are neither available on OkHttp’s host platforms nor enabled in releases of Chrome and Firefox.

##### RESTRICTED_TLS cipher suites

 * TLS_AES_128_GCM_SHA256[¹][tlsv13_only]
 * TLS_AES_256_GCM_SHA384[¹][tlsv13_only]
 * TLS_CHACHA20_POLY1305_SHA256[¹][tlsv13_only]
 * TLS_ECDHE_ECDSA_WITH_AES_128_GCM_SHA256
 * TLS_ECDHE_RSA_WITH_AES_128_GCM_SHA256
 * TLS_ECDHE_ECDSA_WITH_AES_256_GCM_SHA384
 * TLS_ECDHE_RSA_WITH_AES_256_GCM_SHA384
 * TLS_ECDHE_ECDSA_WITH_CHACHA20_POLY1305_SHA256
 * TLS_ECDHE_RSA_WITH_CHACHA20_POLY1305_SHA256
 * **REMOVED:** ~~TLS_AES_128_CCM_SHA256[¹][tlsv13_only]~~
 * **REMOVED:** ~~TLS_AES_128_CCM_8_SHA256[¹][tlsv13_only]~~

##### MODERN_TLS / COMPATIBLE_TLS cipher suites

 * TLS_AES_128_GCM_SHA256[¹][tlsv13_only]
 * TLS_AES_256_GCM_SHA384[¹][tlsv13_only]
 * TLS_CHACHA20_POLY1305_SHA256[¹][tlsv13_only]
 * TLS_ECDHE_ECDSA_WITH_AES_128_GCM_SHA256
 * TLS_ECDHE_RSA_WITH_AES_128_GCM_SHA256
 * TLS_ECDHE_ECDSA_WITH_AES_256_GCM_SHA384
 * TLS_ECDHE_RSA_WITH_AES_256_GCM_SHA384
 * TLS_ECDHE_ECDSA_WITH_CHACHA20_POLY1305_SHA256
 * TLS_ECDHE_RSA_WITH_CHACHA20_POLY1305_SHA256
 * TLS_ECDHE_RSA_WITH_AES_128_CBC_SHA[²][http2_naughty]
 * TLS_ECDHE_RSA_WITH_AES_256_CBC_SHA[²][http2_naughty]
 * TLS_RSA_WITH_AES_128_GCM_SHA256[²][http2_naughty]
 * TLS_RSA_WITH_AES_256_GCM_SHA384[²][http2_naughty]
 * TLS_RSA_WITH_AES_128_CBC_SHA[²][http2_naughty]
 * TLS_RSA_WITH_AES_256_CBC_SHA[²][http2_naughty]
 * TLS_RSA_WITH_3DES_EDE_CBC_SHA[²][http2_naughty]
 * **REMOVED:** ~~TLS_AES_128_CCM_SHA256[¹][tlsv13_only]~~
 * **REMOVED:** ~~TLS_AES_128_CCM_8_SHA256[¹][tlsv13_only]~~

[OkHttp 3.13][OkHttp313]
------------------------

_2019-02-04_

Remove TLSv1.1 and TLSv1 from MODERN_TLS. Change COMPATIBLE_TLS to support all TLS versions.

##### RESTRICTED_TLS versions

* TLSv1.3
* TLSv1.2

##### MODERN_TLS versions

* TLSv1.3
* TLSv1.2
* **REMOVED:** ~~TLSv1.1~~
* **REMOVED:** ~~TLSv1~~

##### COMPATIBLE_TLS versions

* **NEW:** TLSv1.3
* **NEW:** TLSv1.2
* **NEW:** TLSv1.1
* TLSv1

[OkHttp 3.12][OkHttp312]
------------------------

_2018-11-16_

Added support for TLSv1.3.

##### RESTRICTED_TLS cipher suites

 * **NEW:** TLS_AES_128_GCM_SHA256[¹][tlsv13_only]
 * **NEW:** TLS_AES_256_GCM_SHA384[¹][tlsv13_only]
 * **NEW:** TLS_CHACHA20_POLY1305_SHA256[¹][tlsv13_only]
 * **NEW:** TLS_AES_128_CCM_SHA256[¹][tlsv13_only]
 * **NEW:** TLS_AES_128_CCM_8_SHA256[¹][tlsv13_only]
 * TLS_ECDHE_ECDSA_WITH_AES_128_GCM_SHA256
 * TLS_ECDHE_RSA_WITH_AES_128_GCM_SHA256
 * TLS_ECDHE_ECDSA_WITH_AES_256_GCM_SHA384
 * TLS_ECDHE_RSA_WITH_AES_256_GCM_SHA384
 * TLS_ECDHE_ECDSA_WITH_CHACHA20_POLY1305_SHA256
 * TLS_ECDHE_RSA_WITH_CHACHA20_POLY1305_SHA256

##### MODERN_TLS / COMPATIBLE_TLS cipher suites

 * **NEW:** TLS_AES_128_GCM_SHA256[¹][tlsv13_only]
 * **NEW:** TLS_AES_256_GCM_SHA384[¹][tlsv13_only]
 * **NEW:** TLS_CHACHA20_POLY1305_SHA256[¹][tlsv13_only]
 * **NEW:** TLS_AES_128_CCM_SHA256[¹][tlsv13_only]
 * **NEW:** TLS_AES_128_CCM_8_SHA256[¹][tlsv13_only]
 * TLS_ECDHE_ECDSA_WITH_AES_128_GCM_SHA256
 * TLS_ECDHE_RSA_WITH_AES_128_GCM_SHA256
 * TLS_ECDHE_ECDSA_WITH_AES_256_GCM_SHA384
 * TLS_ECDHE_RSA_WITH_AES_256_GCM_SHA384
 * TLS_ECDHE_ECDSA_WITH_CHACHA20_POLY1305_SHA256
 * TLS_ECDHE_RSA_WITH_CHACHA20_POLY1305_SHA256
 * TLS_ECDHE_RSA_WITH_AES_128_CBC_SHA[²][http2_naughty]
 * TLS_ECDHE_RSA_WITH_AES_256_CBC_SHA[²][http2_naughty]
 * TLS_RSA_WITH_AES_128_GCM_SHA256[²][http2_naughty]
 * TLS_RSA_WITH_AES_256_GCM_SHA384[²][http2_naughty]
 * TLS_RSA_WITH_AES_128_CBC_SHA[²][http2_naughty]
 * TLS_RSA_WITH_AES_256_CBC_SHA[²][http2_naughty]
 * TLS_RSA_WITH_3DES_EDE_CBC_SHA[²][http2_naughty]

##### RESTRICTED_TLS versions

* **NEW:** TLSv1.3
* TLSv1.2

##### MODERN_TLS versions

* **NEW:** TLSv1.3
* TLSv1.2
* TLSv1.1
* TLSv1

##### COMPATIBLE_TLS versions

* TLSv1

[OkHttp 3.11][OkHttp311]
------------------------

_2018-07-12_

Added a new extra strict RESTRICTED_TLS configuration inspired by [Google Cloud’s similar policy][googlecloud_ssl_policy]. It is appropriate when both the host platform
(JVM/Conscrypt/Android) and target webserver are current.

##### RESTRICTED_TLS cipher suites

 * TLS_ECDHE_ECDSA_WITH_AES_128_GCM_SHA256
 * TLS_ECDHE_RSA_WITH_AES_128_GCM_SHA256
 * TLS_ECDHE_ECDSA_WITH_AES_256_GCM_SHA384
 * TLS_ECDHE_RSA_WITH_AES_256_GCM_SHA384
 * TLS_ECDHE_ECDSA_WITH_CHACHA20_POLY1305_SHA256
 * TLS_ECDHE_RSA_WITH_CHACHA20_POLY1305_SHA256

##### RESTRICTED_TLS versions

 * TLSv1.2

[OkHttp 3.10][OkHttp310]
------------------------

_2018-02-24_

Remove two rarely-used cipher suites from the default set. This tracks a [Chromium change][chromium_change] to remove these cipher suites because they are fragile and rarely-used.

##### MODERN_TLS / COMPATIBLE_TLS cipher suites

 * TLS_ECDHE_ECDSA_WITH_AES_128_GCM_SHA256
 * TLS_ECDHE_RSA_WITH_AES_128_GCM_SHA256
 * TLS_ECDHE_ECDSA_WITH_AES_256_GCM_SHA384
 * TLS_ECDHE_RSA_WITH_AES_256_GCM_SHA384
 * TLS_ECDHE_ECDSA_WITH_CHACHA20_POLY1305_SHA256
 * TLS_ECDHE_RSA_WITH_CHACHA20_POLY1305_SHA256
 * TLS_ECDHE_RSA_WITH_AES_128_CBC_SHA[²][http2_naughty]
 * TLS_ECDHE_RSA_WITH_AES_256_CBC_SHA[²][http2_naughty]
 * TLS_RSA_WITH_AES_128_GCM_SHA256[²][http2_naughty]
 * TLS_RSA_WITH_AES_256_GCM_SHA384[²][http2_naughty]
 * TLS_RSA_WITH_AES_128_CBC_SHA[²][http2_naughty]
 * TLS_RSA_WITH_AES_256_CBC_SHA[²][http2_naughty]
 * TLS_RSA_WITH_3DES_EDE_CBC_SHA[²][http2_naughty]
 * **REMOVED:** ~~TLS_ECDHE_ECDSA_WITH_AES_256_CBC_SHA~~
 * **REMOVED:** ~~TLS_ECDHE_ECDSA_WITH_AES_128_CBC_SHA~~


[OkHttp 3.5][OkHttp35]
----------------------

_2016-11-30_

Remove three old cipher suites and add five new ones. This tracks changes in what's available on
Android and Java, and also what cipher suites recent releases of Chrome and Firefox support by
default.

##### MODERN_TLS / COMPATIBLE_TLS cipher suites

 * TLS_ECDHE_ECDSA_WITH_AES_128_GCM_SHA256
 * TLS_ECDHE_RSA_WITH_AES_128_GCM_SHA256
 * **NEW:** TLS_ECDHE_ECDSA_WITH_AES_256_GCM_SHA384
 * **NEW:** TLS_ECDHE_RSA_WITH_AES_256_GCM_SHA384
 * **NEW:** TLS_ECDHE_ECDSA_WITH_CHACHA20_POLY1305_SHA256
 * **NEW:** TLS_ECDHE_RSA_WITH_CHACHA20_POLY1305_SHA256
 * TLS_ECDHE_ECDSA_WITH_AES_128_CBC_SHA[²][http2_naughty]
 * TLS_ECDHE_RSA_WITH_AES_128_CBC_SHA[²][http2_naughty]
 * TLS_ECDHE_ECDSA_WITH_AES_256_CBC_SHA[²][http2_naughty]
 * TLS_ECDHE_RSA_WITH_AES_256_CBC_SHA[²][http2_naughty]
 * TLS_RSA_WITH_AES_128_GCM_SHA256[²][http2_naughty]
 * **NEW:** TLS_RSA_WITH_AES_256_GCM_SHA384[²][http2_naughty]
 * TLS_RSA_WITH_AES_128_CBC_SHA[²][http2_naughty]
 * TLS_RSA_WITH_AES_256_CBC_SHA[²][http2_naughty]
 * TLS_RSA_WITH_3DES_EDE_CBC_SHA[²][http2_naughty]
 * **REMOVED:** ~~TLS_DHE_RSA_WITH_AES_128_CBC_SHA~~
 * **REMOVED:** ~~TLS_DHE_RSA_WITH_AES_128_GCM_SHA256~~
 * **REMOVED:** ~~TLS_DHE_RSA_WITH_AES_256_CBC_SHA~~

[OkHttp 3.0][OkHttp30]
----------------------

_2016-01-13_

##### MODERN_TLS / COMPATIBLE_TLS cipher suites

 * TLS_ECDHE_ECDSA_WITH_AES_128_GCM_SHA256
 * TLS_ECDHE_RSA_WITH_AES_128_GCM_SHA256
 * TLS_DHE_RSA_WITH_AES_128_GCM_SHA256
 * TLS_ECDHE_ECDSA_WITH_AES_256_CBC_SHA[²][http2_naughty]
 * TLS_ECDHE_ECDSA_WITH_AES_128_CBC_SHA[²][http2_naughty]
 * TLS_ECDHE_RSA_WITH_AES_128_CBC_SHA[²][http2_naughty]
 * TLS_ECDHE_RSA_WITH_AES_256_CBC_SHA[²][http2_naughty]
 * TLS_DHE_RSA_WITH_AES_128_CBC_SHA[²][http2_naughty]
 * TLS_DHE_RSA_WITH_AES_256_CBC_SHA[²][http2_naughty]
 * TLS_RSA_WITH_AES_128_GCM_SHA256[²][http2_naughty]
 * TLS_RSA_WITH_AES_128_CBC_SHA[²][http2_naughty]
 * TLS_RSA_WITH_AES_256_CBC_SHA[²][http2_naughty]
 * TLS_RSA_WITH_3DES_EDE_CBC_SHA[²][http2_naughty]

##### MODERN_TLS versions

 * TLSv1.2
 * TLSv1.1
 * TLSv1

##### COMPATIBLE_TLS versions

 * TLSv1

---

<a name="tlsv13_only"></a>
#### ¹ TLSv1.3 Only

Cipher suites that are only available with TLSv1.3.

<a name="http2_naughty"></a>
#### ² HTTP/2 Cipher Suite Denylist

Cipher suites that are [discouraged for use][http2_denylist] with HTTP/2. OkHttp includes them because better suites are not commonly available. For example, none of the better cipher suites listed above shipped with Android 4.4 or Java 7.

[OkHttp30]: https://lysine.dev/okhttp/changelog_3x/#version-300
[OkHttp310]: https://lysine.dev/okhttp/changelog_3x/#version-310
[OkHttp311]: https://lysine.dev/okhttp/changelog_3x/#version-320
[OkHttp312]: https://lysine.dev/okhttp/changelog_3x/#version-330
[OkHttp313]: https://lysine.dev/okhttp/changelog_3x/#version-340
[OkHttp314]: https://lysine.dev/okhttp/changelog_3x/#version-310
[OkHttp35]: https://lysine.dev/okhttp/changelog_3x/#version-350
[chromium_change]: https://developers.google.com/web/updates/2016/12/chrome-56-deprecations#remove_cbc-mode_ecdsa_ciphers_in_tls
[googlecloud_ssl_policy]: https://cloud.google.com/load-balancing/docs/ssl-policies-concepts
[http2_denylist]: https://tools.ietf.org/html/rfc7540#appendix-A
[http2_naughty]: #http2_naughty
[tlsv13_only]: #tlsv13_only
