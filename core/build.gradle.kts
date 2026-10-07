plugins {
  alias(libs.plugins.kotlin.multiplatform)
  alias(libs.plugins.android.multiplatform.library)
}

kotlin {
  jvmToolchain(21)

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

  macosArm64()
  iosArm64()
  iosSimulatorArm64()
  iosX64()

  sourceSets {
    commonMain.dependencies {
    }
    commonTest.dependencies {
      implementation(kotlin("test"))
    }
  }
}

tasks.withType<Test>().configureEach {
  maxHeapSize = "1g"
}

tasks.matching { it.name == "iosSimulatorArm64Test" || it.name == "iosX64Test" }.configureEach {
  val hasSimulator = providers.exec {
    commandLine("xcrun", "simctl", "list", "devices", "available")
    isIgnoreExitValue = true
  }.standardOutput.asText.map { it.contains("iPhone") || it.contains("iPad") }.getOrElse(false)
  enabled = hasSimulator
}
