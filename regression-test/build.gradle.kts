plugins {
  id("okhttp.base-conventions")
  id("com.android.library")
}

android {
  compileSdk {
    version = release(37)
  }

  namespace = "okhttp.android.regression"

  defaultConfig {
    minSdk = 21

    // Make sure to use the AndroidJUnitRunner (or a sub-class) in order to hook in the JUnit 5 Test Builder
    testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    testInstrumentationRunnerArguments += mapOf(
      "notClass" to "org.conscrypt.KitKatPlatformOpenSSLSocketImplAdapter,org.bouncycastle.pqc.crypto.qtesla.QTeslaKeyEncodingTests"
    )
  }

  compileOptions {
    targetCompatibility(JavaVersion.VERSION_11)
    sourceCompatibility(JavaVersion.VERSION_11)
  }


  // issue merging due to conflict with httpclient and something else
  packagingOptions.resources.excludes += setOf(
    "META-INF/DEPENDENCIES"
  )
}

dependencies {
  val okhttpLegacyVersion = "3.12.12"

  implementation(libs.kotlin.reflect)
  implementation(libs.playservices.safetynet)
  implementation("com.squareup.okhttp3:okhttp:${okhttpLegacyVersion}")
  implementation("com.squareup.okhttp3:okhttp-tls:${okhttpLegacyVersion}") {
    exclude("org.bouncycastle")
  }
  androidTestImplementation("com.squareup.okhttp3:mockwebserver:${okhttpLegacyVersion}")
  androidTestImplementation(libs.bouncycastle.bcprov)
  androidTestImplementation(libs.bouncycastle.bctls)
  androidTestImplementation(libs.androidx.junit)
  androidTestImplementation(libs.androidx.espresso.core)
  androidTestImplementation(libs.http.client5)
  androidTestImplementation(libs.square.moshi)
  androidTestImplementation(libs.square.moshi.kotlin)
}
