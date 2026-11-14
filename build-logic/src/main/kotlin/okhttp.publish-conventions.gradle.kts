import com.vanniktech.maven.publish.JavadocJar
import com.vanniktech.maven.publish.KotlinJvm
import com.vanniktech.maven.publish.KotlinMultiplatform
import com.vanniktech.maven.publish.MavenPublishBaseExtension
import kotlinx.validation.ApiValidationExtension
import kotlinx.validation.KotlinApiBuildTask
import kotlinx.validation.KotlinApiCompareTask
import org.jetbrains.dokka.gradle.DokkaExtension
import org.jetbrains.dokka.gradle.DokkaPlugin

plugins {
  id("com.vanniktech.maven.publish.base")
  id("binary-compatibility-validator")
}

val okhttpDokka: String? by project
if (okhttpDokka?.toBoolean() == true) {
  apply(plugin = "org.jetbrains.dokka")
  plugins.withType<DokkaPlugin> {
    configure<DokkaExtension> {
      dokkaPublications.all {
        dokkaSourceSets.configureEach {
          perPackageOption {
            matchingRegex.set(""".*[.]internal([.].*)?""")
            suppress.set(true)
          }
        }
      }
    }
  }
}

configure<MavenPublishBaseExtension> {
  publishToMavenCentral(automaticRelease = true)
  signAllPublications()
  pom {
    name.set(project.name)
    description.set("Square’s meticulous HTTP client for Java and Kotlin.")
    url.set("https://lysine.dev/okhttp/")
    licenses {
      license {
        name.set("The Apache Software License, Version 2.0")
        url.set("https://www.apache.org/licenses/LICENSE-2.0.txt")
        distribution.set("repo")
      }
    }
    scm {
      connection.set("scm:git:https://github.com/lysine-dev/okhttp.git")
      developerConnection.set("scm:git:ssh://git@github.com/lysine-dev/okhttp.git")
      url.set("https://github.com/lysine-dev/okhttp")
    }
    developers {
      developer {
        name.set("Square, Inc.")
      }
    }
  }

  if (project.name == "okhttp") {
    configure(KotlinMultiplatform(javadocJar = JavadocJar.Empty()))
  } else if (plugins.hasPlugin(JavaBasePlugin::class.java)) {
    configure(KotlinJvm(javadocJar = JavadocJar.Empty()))
  }
}

configure<ApiValidationExtension> {
  ignoredPackages += "mockwebserver3.internal"
  ignoredPackages += "mockwebserver3.junit5.internal"
  ignoredPackages += "okhttp3.brotli.internal"
  ignoredPackages += "okhttp3.dnsoverhttps.internal"
  ignoredPackages += "okhttp3.internal"
  ignoredPackages += "okhttp3.logging.internal"
  ignoredPackages += "okhttp3.sse.internal"
  ignoredPackages += "okhttp3.tls.internal"
}

if (project.name == "okhttp") {
  // Workaround for https://github.com/Kotlin/binary-compatibility-validator/issues/312
  val apiBuild = tasks.register<KotlinApiBuildTask>("androidApiBuild") {
    outputApiFile = project.layout.buildDirectory.file("${this.name}/okhttp.api")
    inputClassesDirs.from(tasks.getByName("compileAndroidMain").outputs)
  }
  val apiCheck = tasks.register<KotlinApiCompareTask>("androidApiCheck") {
    group = "verification"
    projectApiFile = project.file("api/android/okhttp.api")
    generatedApiFile = apiBuild.flatMap(KotlinApiBuildTask::outputApiFile)
  }
  val apiDump = tasks.register<Copy>("androidApiDump") {
    from(apiBuild.flatMap(KotlinApiBuildTask::outputApiFile))
    destinationDir = project.file("api/android")
  }

  tasks.named("apiDump").configure { dependsOn(apiDump) }
  tasks.named("apiCheck").configure { dependsOn(apiCheck) }
}
