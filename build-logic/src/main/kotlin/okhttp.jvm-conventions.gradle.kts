import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile
import tapmoc.TapmocExtension
import tapmoc.configureKotlinCompatibility

plugins {
  id("okhttp.base-conventions")
  id("com.gradleup.tapmoc")
}

val libs = extensions.getByType<VersionCatalogsExtension>().named("libs")

fun library(alias: String) = libs.findLibrary(alias).get().get().let {
  "${it.module.group}:${it.module.name}:${it.versionConstraint.requiredVersion}"
}
fun version(alias: String) = libs.findVersion(alias).get().toString()

extensions.configure<JavaPluginExtension> {
  toolchain {
    languageVersion.set(JavaLanguageVersion.of(21))
  }
}

// Introduce in a separate change
//configureJavaCompatibility(javaVersion = 8)

configureKotlinCompatibility(version = version("kotlinCoreLibrariesVersion"))

tasks.withType<JavaCompile> {
  options.encoding = Charsets.UTF_8.toString()
  if (name.contains("Java9")) {
    sourceCompatibility = "9"
    targetCompatibility = "9"
  } else {
    sourceCompatibility = "1.8"
    targetCompatibility = "1.8"
  }
}

tasks.withType<KotlinCompile> {
  compilerOptions {
    jvmTarget.set(JvmTarget.JVM_1_8)
    freeCompilerArgs.addAll(
      "-Xjvm-default=all",
      "-Xexpect-actual-classes",
    )
  }
}

extensions.configure<TapmocExtension> {
  // Fail the build if any api dependency exposes incompatible Kotlin metadata, Kotlin stdlib, or Java bytecode version.
  checkDependencies()
}
