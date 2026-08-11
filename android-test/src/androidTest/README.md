Android Test
============

A gradle module for running Android instrumentation tests on a device or emulator.

1. Add an Emulator named `pixel5`, if you don't already have one

```
$ sdkmanager --install "system-images;android-29;google_apis;x86"
$ echo "no" | avdmanager --verbose create avd --force --name "pixel5" --device "pixel" --package "system-images;android-29;google_apis;x86" --tag "google_apis" --abi "x86"
```

2. Run an Emulator using Android Studio or from command line.

```
$ emulator -no-window -no-snapshot-load @pixel5
```

2. Turn on logs with logcat

```
$ adb logcat '*:E' OkHttp:D Http2:D TestRunner:D TaskRunner:D OkHttpTest:D GnssHAL_GnssInterface:F DeviceStateChecker:F memtrack:F
...
01-01 12:53:32.811 10999 11089 D OkHttp  : [49 ms] responseHeadersEnd: Response{protocol=h2, code=200, message=, url=https://1.1.1.1/dns-query?dns=AAABAAABAAAAAAAAA3d3dwhmYWNlYm9vawNjb20AABwAAQ}
01-01 12:53:32.811 10999 11089 D OkHttp  : [49 ms] responseBodyStart
01-01 12:53:32.811 10999 11089 D OkHttp  : [49 ms] responseBodyEnd: byteCount=128
01-01 12:53:32.811 10999 11089 D OkHttp  : [49 ms] connectionReleased
01-01 12:53:32.811 10999 11089 D OkHttp  : [49 ms] callEnd
01-01 12:53:32.816 10999 11090 D OkHttp  : [54 ms] responseHeadersStart
01-01 12:53:32.816 10999 11090 D OkHttp  : [54 ms] responseHeadersEnd: Response{protocol=h2, code=200, message=, url=https://1.1.1.1/dns-query?dns=AAABAAABAAAAAAAAA3d3dwhmYWNlYm9vawNjb20AAAEAAQ}
01-01 12:53:32.817 10999 11090 D OkHttp  : [55 ms] responseBodyStart
01-01 12:53:32.818 10999 11090 D OkHttp  : [56 ms] responseBodyEnd: byteCount=128
01-01 12:53:32.818 10999 11090 D OkHttp  : [56 ms] connectionReleased
01-01 12:53:32.818 10999 11090 D OkHttp  : [56 ms] callEnd
```

3. Run tests using gradle

```
$ ANDROID_SDK_ROOT=/Users/myusername/Library/Android/sdk ./gradlew :android-test:connectedCheck -PandroidBuild=true
...
> Task :android-test:connectedDebugAndroidTest
...
11:55:40 V/InstrumentationResultParser: Time: 13.271
11:55:40 V/InstrumentationResultParser:
11:55:40 V/InstrumentationResultParser: OK (12 tests)
...
11:55:40 I/XmlResultReporter: XML test result file generated at /Users/myusername/workspace/okhttp/android-test/build/outputs/androidTest-results/connected/TEST-pixel3a-Q(AVD) - 10-android-test-.xml. Total tests 13, passed 11, assumption_failure 1, ignored 1,
...
BUILD SUCCESSFUL in 1m 30s
63 actionable tasks: 61 executed, 2 up-to-date

```

n.b. use ANDROID_SERIAL=emulator-5554 or similar if you need to select between devices.

Testcontainers service on the host
----------------------------------

`TestcontainersHostTest` runs on Android while its MockServer service runs in a
Testcontainers-managed Docker container on the host. The CI job uses the API 37.0
`google_apis_playstore_ps16k` system image. With an API 37 emulator running and
Docker available, run:

```
$ android-test/run-testcontainers-test.sh
```

The script starts the host service, discovers its random mapped port, and uses
`adb reverse` to make it available to the test at `127.0.0.1:8080`. It then runs
only `TestcontainersHostTest` and stops the container.

With a Docker engine running, the GitHub workflow can be smoke-tested with `act`
without trying to run a nested emulator:

```
$ act workflow_dispatch -W .github/workflows/android-testcontainers.yml
```

When using Colima, the Docker socket path visible to the Linux daemon differs
from its macOS forwarding path. Start Colima and run `act` with:

```
$ colima start
$ act workflow_dispatch \
    -W .github/workflows/android-testcontainers.yml \
    --container-architecture linux/arm64 \
    --container-daemon-socket unix:///var/run/docker.sock
```

The explicit ARM64 runner avoids emulating an amd64 `act` image on Apple Silicon.
Omit that option on Intel hosts.

For a direct local run with Colima, export the daemon-side socket used by Ryuk:

```
$ export TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE=/var/run/docker.sock
$ android-test/run-testcontainers-test.sh
```

Encrypted Client Hello fixture
------------------------------

`EncryptedClientHelloTest` is the API 37 ECH interoperability test used by the
workflow. Testcontainers starts two instances of a hermetic Go fixture:

* a TLS DoH server that returns an HTTPS (type 65) record containing an
  `ech` SvcParam; and
* an HTTPS target configured with the corresponding ECH private key.

The emulator reaches both random host ports through `adb reverse`. The tests
query the DoH server with OkHttp and exercise three API 37 `SSLSocket` paths:

* a current ECH config is accepted and the server observes the private SNI;
* a stale config is rejected, then the server-provided config is used on a
  successful ECH retry; and
* a stale config with no server-provided replacement is retried successfully
  without ECH.

The test configures `DnsOverHttps` as the client's `Dns` implementation and
makes ordinary OkHttp requests. OkHttp requests the HTTPS DNS record, consumes
its service metadata, configures the API 37 socket, and handles ECH rejection
state. There is no manual DNS message parsing or direct `SSLSocket` use in the
instrumentation test. The retry tests assert the intended OkHttp behavior and
are expected to fail until ECH retry support lands.

Run it with an API 37 emulator and Docker already running:

```
$ export TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE=/var/run/docker.sock # Colima only
$ android-test/run-ech-test.sh
```

To validate the Gradle/Testcontainers side without an emulator, including when
running the workflow with `act`:

```
$ android-test/run-ech-test.sh --smoke-only
$ act workflow_dispatch \
    -W .github/workflows/android-testcontainers.yml \
    --container-architecture linux/arm64 \
    --container-daemon-socket unix:///var/run/docker.sock
```

The test exercises OkHttp's Android ECH connection planning end to end against
the hermetic DoH and HTTPS services.
