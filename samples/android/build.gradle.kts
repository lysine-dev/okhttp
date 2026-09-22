@file:Suppress("UnstableApiUsage")

plugins {
  id("okhttp.base-conventions")
  id("com.android.library")
}

android {
  namespace = "okhttp3.sample.ech"

  compileSdk {
    version = release(37)
  }

  defaultConfig {
    minSdk = 21
    testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
  }

  testOptions {
    targetSdk = 37
  }

  compileOptions {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
  }

  lint {
    abortOnError = true
  }
}

dependencies {
  androidTestImplementation(projects.okhttp)
  androidTestImplementation(projects.okhttpDnsoverhttps)
  androidTestImplementation(libs.androidx.junit)
  androidTestImplementation(libs.androidx.test.runner)
  androidTestImplementation(libs.assertk)
}
