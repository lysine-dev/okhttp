@file:Suppress("UnstableApiUsage")

import okhttp3.buildsupport.testJavaVersion


plugins {
  id("okhttp.base-conventions")
  id("com.android.application")
}

android {
  compileSdk {
    version = release(37)
  }

  namespace = "okhttp.android.testapp"

  // Release APKs can't be tested currently with AGP
  testBuildType = "debug"

  defaultConfig {
    minSdk = 21
    targetSdk = 37
    testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
  }

  compileOptions {
    targetCompatibility(JavaVersion.VERSION_11)
    sourceCompatibility(JavaVersion.VERSION_11)
  }


  buildTypes {
    release {
      isShrinkResources = true
      isMinifyEnabled = true
      signingConfig = signingConfigs.getByName("debug")
      setProguardFiles(listOf(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro"))
      testProguardFiles("test-proguard-rules.pro")
    }
  }

  lint {
    abortOnError = true
  }
}

dependencies {
  implementation(libs.playservices.safetynet)
  implementation(projects.okhttp)
  implementation(libs.androidx.activity)

  androidTestImplementation(libs.androidx.junit)
  androidTestImplementation(libs.androidx.espresso.core)
  androidTestImplementation(libs.androidx.test.runner)
  androidTestImplementation(libs.assertk)
}
