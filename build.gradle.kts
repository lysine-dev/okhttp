plugins {
  alias(libs.plugins.spotless) apply false
  alias(libs.plugins.android.library) apply false
  alias(libs.plugins.android.application) apply false
  alias(libs.plugins.kotlin.jvm) apply false
  alias(libs.plugins.kotlin.multiplatform) apply false
  alias(libs.plugins.kotlin.serialization) apply false
  alias(libs.plugins.dokka) apply false
  alias(libs.plugins.maven.publish) apply false
  alias(libs.plugins.binary.compatibility.validator) apply false
  alias(libs.plugins.animalsniffer) apply false
  alias(libs.plugins.android.junit5) apply false
  alias(libs.plugins.shadow) apply false
  alias(libs.plugins.graalvm) apply false
  alias(libs.plugins.ksp) apply false
  alias(libs.plugins.burst) apply false
  id("okhttp.dokka-multimodule-conventions")
}

tasks.wrapper {
  distributionType = Wrapper.DistributionType.ALL
}
