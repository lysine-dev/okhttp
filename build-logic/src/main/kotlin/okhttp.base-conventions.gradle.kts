import org.gradle.api.artifacts.VersionCatalogsExtension
import okhttp3.buildsupport.platform
import okhttp3.buildsupport.testJavaVersion
import org.gradle.kotlin.dsl.withType
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

val libs = extensions.getByType<VersionCatalogsExtension>().named("libs")

group = "com.squareup.okhttp3"
version = "5.5.0-SNAPSHOT"

val platform = project.platform
val testJavaVersion = project.testJavaVersion


// Friend configurations - moved here to be shared across all modules (including android-test)
val friendsApi = configurations.maybeCreate("friendsApi").apply {
  isCanBeResolved = true
  isCanBeConsumed = false
  isTransitive = true
}
val friendsImplementation = configurations.maybeCreate("friendsImplementation").apply {
  isCanBeResolved = true
  isCanBeConsumed = false
  isTransitive = false
}
val friendsTestImplementation = configurations.maybeCreate("friendsTestImplementation").apply {
  isCanBeResolved = true
  isCanBeConsumed = false
  isTransitive = false
}

configurations.configureEach {
  if (name == "implementation") {
    extendsFrom(friendsApi, friendsImplementation)
  }
  if (name == "api") {
    extendsFrom(friendsApi)
  }
  if (name == "testImplementation") {
    extendsFrom(friendsTestImplementation)
  }
}

tasks.withType<KotlinCompile>().configureEach {
  friendPaths.from(friendsApi.incoming.artifactView { }.files)
  friendPaths.from(friendsImplementation.incoming.artifactView { }.files)
  friendPaths.from(friendsTestImplementation.incoming.artifactView { }.files)
}

val resolvableConfigurations = configurations.filter { it.isCanBeResolved }
tasks.register("downloadDependencies") {
  description = "Download all dependencies to the Gradle cache"
  doLast {
    for (configuration in resolvableConfigurations) {
      configuration.files
    }
  }
}

tasks.withType<KotlinCompile> {
  compilerOptions {
    optIn.add("okhttp3.internal.OkHttpInternalApi")
  }
}

normalization {
  runtimeClasspath {
    metaInf {
      ignoreAttribute("Bnd-LastModified")
    }
  }
}
