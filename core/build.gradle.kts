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

// Apple tests that cannot run on this host must never look like passes.
//  - iosSimulatorArm64Test / iosX64Test need an installed iOS simulator runtime. Without one, CI (CI=true) fails the
//    build; locally the tasks are disabled with a warning.
//  - iosX64Test is an Intel simulator: Kotlin skips it on Apple Silicon even with a runtime installed (compiled and
//    linked, not executed). That is inherent, so it is reported, not failed.
//  - On a non-macOS host Kotlin/Native builds no Apple task at all.
// Every case is repeated by `reportAppleTestsNotRun` after :core:allTests. It runs at execution time, so the report is
// printed on every run, unlike a configuration-time warning that a configuration-cache hit would swallow.
// Only macOS hosts probe xcrun; a missing or failing xcrun counts as "no simulator".
val isMacHost = System.getProperty("os.name").startsWith("Mac")
val isAppleSiliconHost = isMacHost && System.getProperty("os.arch") == "aarch64"
val isCi = providers.environmentVariable("CI").map { it == "true" }.orElse(false).get()
val hasIosSimulator = isMacHost && runCatching {
  providers.exec {
    commandLine("xcrun", "simctl", "list", "devices", "available")
    isIgnoreExitValue = true
  }.standardOutput.asText.get().let { it.contains("iPhone") || it.contains("iPad") }
}.getOrDefault(false)

if (isMacHost) {
  tasks.matching { it.name == "iosSimulatorArm64Test" || it.name == "iosX64Test" }.configureEach {
    if (!hasIosSimulator) {
      if (isCi) {
        throw GradleException("$path was NOT RUN: no iOS simulator runtime is installed (xcrun simctl list devices available). A skipped iOS test must not pass CI.")
      }
      enabled = false
    }
  }
}

val appleTestsNotRun: List<String> = buildList {
  when {
    !isMacHost -> add("macosArm64Test, iosSimulatorArm64Test, iosX64Test: Kotlin/Native builds Apple targets only on macOS")
    !hasIosSimulator -> add("iosSimulatorArm64Test, iosX64Test: no iOS simulator runtime is installed (xcrun simctl list devices available)")
    isAppleSiliconHost -> add("iosX64Test: the Intel iOS simulator cannot run on Apple Silicon (iosSimulatorArm64Test covers the same code)")
  }
}

val reportAppleTestsNotRun = tasks.register("reportAppleTestsNotRun") {
  val notRun = appleTestsNotRun
  doLast {
    notRun.forEach { logger.warn("WARNING: NOT RUN: $it. These tests are UNVERIFIED on this machine.") }
  }
}
tasks.matching { it.name == "allTests" }.configureEach { finalizedBy(reportAppleTestsNotRun) }
