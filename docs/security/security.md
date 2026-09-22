Security
========

## Supported Versions

| Version | Supported          | Notes                                        |
| ------- |--------------------| -------------------------------------------- |
| 5.x     | ✅                  | Android 5.0+ (API level 21+) and on Java 8+. |
| 4.x     | ✅ Until 2028-08-31 | Android 5.0+ (API level 21+) and on Java 8+. |
| 3.x     | ❌ Ended 2021-12-31 | Android 2.3+ (API level 9+) and Java 7+.     |


## Reporting a Vulnerability

Square recognizes the important contributions the security research community
can make. We therefore encourage reporting security issues with the code
contained in this repository.

If you believe you have discovered a security vulnerability, please follow the
guidelines at https://bugcrowd.com/squareopensource


## Verifying Artifacts

We sign our artifacts using [this key][signing_key_2026]:

```
pub (4)eddsa263/5dd80b0ddccbe005f0a47fd66751e1a6e2001b8d 2026-07-21T22:42:04Z

uid lysine.dev <project-lysine@commonhaus.org>
sig cert  6751e1a6e2001b8d 2026-07-21T22:42:04Z 2076-07-08T22:42:04Z ____________________ [selfsig]
```

Prior to 2026-07-21, we used [this key][signing_key_2021].

```
pub rsa4096/dbd744ace7ade6aa50dd591f66b50994442d2d40 2021-07-09T14:50:19Z
	 Hash=a79b48fd6a1f31699c788b50c97d0b98

uid Square Clippy <opensource@squareup.com>
sig  sig  66b50994442d2d40 2021-07-09T14:50:19Z 2041-07-04T14:50:19Z ____________________ [selfsig]
```

The best way to verify artifacts is [automatically with Gradle][gradle_verification].


[gradle_verification]: https://docs.gradle.org/current/userguide/dependency_verification.html#sec:signature-verification
[signing_key_2021]: https://keyserver.ubuntu.com/pks/lookup?search=dbd744ace7ade6aa50dd591f66b50994442d2d40&fingerprint=on&op=index
[signing_key_2026]: https://keyserver.ubuntu.com/pks/lookup?search=5dd80b0ddccbe005f0a47fd66751e1a6e2001b8d&fingerprint=on&op=index
