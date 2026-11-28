@file:Suppress("UnstableApiUsage")

import com.android.build.api.variant.KotlinMultiplatformAndroidComponentsExtension
import com.android.build.api.variant.LibraryAndroidComponentsExtension
import okhttp3.buildsupport.robolectricEnabled

val robolectricJars = objects.fileCollection()

mapOf(
  23 to "6.0.1_r3-robolectric-r1-i7",
  26 to "8.0.0_r4-robolectric-r1-i7",
  30 to "11-robolectric-6757853-i7",
  33 to "13-robolectric-9030017-i7",
  35 to "15-robolectric-13954326-i7",
  36 to "16-robolectric-13921718-i7",
  37 to "17-robolectric-15733970-i7",
).forEach { (sdk, jarVersion) ->
  val robolectric = configurations.dependencyScope("robolectric$sdk") {
    dependencies.add(project.dependencies.create("org.robolectric:android-all-instrumented:$jarVersion"))
  }

  robolectricJars.from(
    configurations.resolvable("robolectric${sdk}Jar") {
      extendsFrom(robolectric)
    }
  )
}

val syncRobolectricJars = tasks.register<Sync>("syncRobolectricJars") {
  description = "Syncs Robolectric android-all-instrumented jars into a local directory."
  from(robolectricJars)
  destinationDirectory.set(layout.buildDirectory.dir("preinstrumented"))
}

val robolectricDependencyDir = object : CommandLineArgumentProvider {
  @get:InputDirectory
  @get:PathSensitive(PathSensitivity.RELATIVE)
  val directory: Provider<Directory> = syncRobolectricJars.flatMap { it.destinationDirectory }

  override fun asArguments() = listOf("-Drobolectric.dependency.dir=${directory.get().asFile}")
}

if (robolectricEnabled) {
  pluginManager.withPlugin("com.android.library") {
    extensions.getByType<LibraryAndroidComponentsExtension>().onVariants { variant ->
      variant.hostTests.values.forEach { hostTest ->
        hostTest.configureTestTask { it.jvmArgumentProviders.add(robolectricDependencyDir) }
      }
    }
  }

  pluginManager.withPlugin("com.android.kotlin.multiplatform.library") {
    extensions.getByType<KotlinMultiplatformAndroidComponentsExtension>().onVariants { variant ->
      variant.hostTests.values.forEach { hostTest ->
        hostTest.configureTestTask { it.jvmArgumentProviders.add(robolectricDependencyDir) }
      }
    }
  }
}
