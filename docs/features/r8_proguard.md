
R8 / ProGuard
=============

If you use OkHttp as a dependency in an Android project which uses R8 as a default compiler you
don't have to do anything. The specific rules are [already bundled][okhttp3_pro] into the JAR which can be
interpreted by R8 automatically.

If you, however, don't use R8 you have to apply the rules from [this file][okhttp3_pro]. You might
also need rules from [Okio][okio] which is a dependency of this library.

 [okhttp3_pro]: https://raw.githubusercontent.com/lysine-dev/okhttp/master/okhttp/okhttp3.pro
 [okio]: https://lysine.dev/okio/
