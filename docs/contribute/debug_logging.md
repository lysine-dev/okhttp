Debug Logging
=============

OkHttp has internal APIs to enable debug logging. It uses the `java.util.logging` API which can be
tricky to configure. As a shortcut, you can paste [OkHttpDebugLogging.kt]. Then enable debug logging
for whichever features you need:

```
OkHttpDebugLogging.enableHttp2()
OkHttpDebugLogging.enableTaskRunner()
```

### Activating on Android

```
$ adb shell setprop log.tag.okhttp.Http2 DEBUG
$ adb shell setprop log.tag.okhttp.TaskRunner DEBUG
$ adb logcat '*:E' 'okhttp.Http2:D' 'okhttp.TaskRunner:D'
```

### HTTP/2 Frame Logging

This logs inbound (`<<`) and outbound (`>>`) frames for HTTP/2 connections.

```
[2020-01-01 00:00:00] >> CONNECTION 505249202a20485454502f322e300d0a0d0a534d0d0a0d0a
[2020-01-01 00:00:00] >> 0x00000000     6 SETTINGS
[2020-01-01 00:00:00] >> 0x00000000     4 WINDOW_UPDATE
[2020-01-01 00:00:00] >> 0x00000003    47 HEADERS       END_STREAM|END_HEADERS
[2020-01-01 00:00:00] << 0x00000000     6 SETTINGS
[2020-01-01 00:00:00] << 0x00000000     0 SETTINGS      ACK
[2020-01-01 00:00:00] << 0x00000000     4 WINDOW_UPDATE
[2020-01-01 00:00:00] >> 0x00000000     0 SETTINGS      ACK
[2020-01-01 00:00:00] << 0x00000003   322 HEADERS       END_HEADERS
[2020-01-01 00:00:00] << 0x00000003   288 DATA
[2020-01-01 00:00:00] << 0x00000003     0 DATA          END_STREAM
[2020-01-01 00:00:00] << 0x00000000     8 GOAWAY
[2020-01-01 00:00:05] << 0x00000000     8 GOAWAY
```

### Task Runner Logging

This logs task enqueues, starts, and finishes.

```
[2020-01-01 00:00:00] Q10000 scheduled after   0 µs: OkHttp ConnectionPool
[2020-01-01 00:00:00] Q10000 starting              : OkHttp ConnectionPool
[2020-01-01 00:00:00] Q10000 run again after 300 s : OkHttp ConnectionPool
[2020-01-01 00:00:00] Q10000 finished run in   1 ms: OkHttp ConnectionPool
[2020-01-01 00:00:00] Q10001 scheduled after   0 µs: OkHttp squareup.com applyAndAckSettings
[2020-01-01 00:00:00] Q10001 starting              : OkHttp squareup.com applyAndAckSettings
[2020-01-01 00:00:00] Q10003 scheduled after   0 µs: OkHttp squareup.com onSettings
[2020-01-01 00:00:00] Q10003 starting              : OkHttp squareup.com onSettings
[2020-01-01 00:00:00] Q10001 finished run in   3 ms: OkHttp squareup.com applyAndAckSettings
[2020-01-01 00:00:00] Q10003 finished run in 528 µs: OkHttp squareup.com onSettings
[2020-01-01 00:00:00] Q10000 scheduled after   0 µs: OkHttp ConnectionPool
[2020-01-01 00:00:00] Q10000 starting              : OkHttp ConnectionPool
[2020-01-01 00:00:00] Q10000 run again after 300 s : OkHttp ConnectionPool
[2020-01-01 00:00:00] Q10000 finished run in 739 µs: OkHttp ConnectionPool
```

[OkHttpDebugLogging.kt]: https://github.com/lysine-dev/okhttp/blob/master/okhttp-testing-support/src/main/kotlin/okhttp3/OkHttpDebugLogging.kt
