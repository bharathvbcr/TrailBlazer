plugins {
  alias(libs.plugins.kotlin.multiplatform)
  alias(libs.plugins.android.multiplatform.library)
  alias(libs.plugins.kotlin.serialization)
}

kotlin {
  jvmToolchain(17)

  compilerOptions {
    freeCompilerArgs.add("-Xexpect-actual-classes")
  }

  jvm()
  android {
    namespace = "com.trailblazer.core"
    compileSdk = 37
    minSdk = 24
    withHostTest {}
  }

  listOf(macosArm64(), iosArm64(), iosSimulatorArm64(), iosX64()).forEach { target ->
    target.binaries.framework {
      baseName = "TrailBlazerCore"
      isStatic = true
    }
  }

  sourceSets {
    commonMain.dependencies {

      implementation(libs.kotlinx.coroutines.core)
      implementation(libs.kotlinx.serialization.json)
    }
    commonTest.dependencies {
      implementation(kotlin("test"))
      implementation(libs.kotlinx.coroutines.test)
    }
  }
}

tasks.withType<Test>().configureEach {
  maxHeapSize = "1g"
}

// The iOS simulator tests need an installed simulator runtime. Without one they cannot run, and a quiet skip looks like
// a pass: on CI (CI=true) the missing simulator fails the build; locally the skip is announced as a warning.
// Only macOS hosts probe xcrun (Linux has none, and Kotlin/Native disables the Apple tasks there anyway).
val isMacHost = System.getProperty("os.name").startsWith("Mac")
val isCi = providers.environmentVariable("CI").map { it == "true" }.orElse(false).get()

if (isMacHost) {
  val hasIosSimulator = runCatching {
    providers.exec {
      commandLine("xcrun", "simctl", "list", "devices", "available")
      isIgnoreExitValue = true
    }.standardOutput.asText.get().let { it.contains("iPhone") || it.contains("iPad") }
  }.getOrDefault(false)

  tasks.matching { it.name == "iosSimulatorArm64Test" || it.name == "iosX64Test" }.configureEach {
    if (!hasIosSimulator) {
      if (isCi) {
        throw GradleException("$path was NOT RUN: no iOS simulator runtime is installed (xcrun simctl list devices available). A skipped iOS test must not pass CI.")
      }
      logger.warn("WARNING: $path was NOT RUN: no iOS simulator runtime is installed (xcrun simctl list devices available). The iOS tests are UNVERIFIED on this machine.")
      enabled = false
    }
  }
}
