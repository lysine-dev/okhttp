import okhttp3.buildsupport.testJavaVersion

/**
 * Shared configuration for the modules that run Robolectric tests.
 *
 * Robolectric reflects into JDK internals, which JDK 17+ blocks by default, and its newer Android
 * images need a newer JVM. https://robolectric.org/getting-started/
 */

/** Opened so Robolectric can reflect into the JDK internals its shadows and interceptors use. */
val robolectricJvmArgs =
  listOf(
    "--add-opens=java.base/java.lang=ALL-UNNAMED",
    "--add-opens=java.base/java.util=ALL-UNNAMED",
    "--add-opens=java.base/java.io=ALL-UNNAMED",
    "--add-opens=java.base/java.net=ALL-UNNAMED",
    "--add-opens=java.base/java.security=ALL-UNNAMED",
    "--add-opens=java.base/java.text=ALL-UNNAMED",
    "--add-opens=java.base/jdk.internal.access=ALL-UNNAMED",
    "--add-opens=java.desktop/java.awt.font=ALL-UNNAMED",
    "--add-opens=jdk.compiler/com.sun.tools.javac.api=ALL-UNNAMED",
  )

tasks.withType<Test>().configureEach {
  if (testJavaVersion >= 9) {
    jvmArgs(robolectricJvmArgs)
  }

  if (testJavaVersion < 21) {
    // Robolectric needs Java 21 to sandbox Android SDK 37. Tests configured only for SDKs outside
    // this set are skipped rather than failing to create a sandbox.
    systemProperty("robolectric.enabledSdks", (21..36).joinToString(","))
  }
}

if (testJavaVersion < 9) {
  afterEvaluate {
    tasks.withType<Test> {
      // Work around robolectric requirements and limitations. The Android Gradle Plugin adds
      // --add-opens itself, which Java 8 doesn't understand.
      // https://cs.android.com/android-studio/platform/tools/base/+/mirror-goog-studio-main:build-system/gradle-core/src/main/java/com/android/build/gradle/tasks/factory/AndroidUnitTest.java;l=339
      allJvmArgs = allJvmArgs.filter { !it.startsWith("--add-opens") }
    }
  }
}
