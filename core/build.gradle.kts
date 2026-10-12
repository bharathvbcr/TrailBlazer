import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import org.gradle.api.logging.Logging
import org.gradle.api.provider.Property
import org.gradle.api.services.BuildService
import org.gradle.api.services.BuildServiceParameters
import org.gradle.build.event.BuildEventsListenerRegistry
import org.gradle.tooling.events.FinishEvent
import org.gradle.tooling.events.OperationCompletionListener
import org.gradle.tooling.events.task.TaskFinishEvent
import org.gradle.tooling.events.task.TaskSkippedResult

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
//    build; locally the tasks are disabled.
//  - Kotlin/Native itself skips targets the host cannot execute (iosX64Test on Apple Silicon; the arm64 targets on an
//    Intel Mac) and builds no Apple task at all off macOS.
// Whatever the reason, AppleTestWatch records what Gradle actually did to each Apple test task and, when the build
// ends, names every one that was skipped. It is a build-event listener, so it reports on any invocation (allTests or a
// single task) and on configuration-cache hits, which a configuration-time warning would not.
// Only macOS hosts probe xcrun; a missing or failing xcrun counts as "no simulator".
val isMacHost = System.getProperty("os.name").startsWith("Mac")
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

abstract class AppleTestWatch : BuildService<AppleTestWatch.Params>, OperationCompletionListener, AutoCloseable {
  interface Params : BuildServiceParameters {
    val macHost: Property<Boolean>
    val simulatorInstalled: Property<Boolean>
    val githubActions: Property<Boolean>
  }

  private val appleTests = setOf(":core:macosArm64Test", ":core:iosSimulatorArm64Test", ":core:iosX64Test")
  private val skipped = ConcurrentHashMap.newKeySet<String>()
  private val ran = ConcurrentHashMap.newKeySet<String>()
  @Volatile private var allTestsRequested = false

  override fun onFinish(event: FinishEvent) {
    if (event !is TaskFinishEvent) return
    val path = event.descriptor.taskPath
    if (path == ":core:allTests") allTestsRequested = true
    if (path !in appleTests) return
    // Skipped covers disabled tasks, a false onlyIf and NO-SOURCE; UP-TO-DATE and FROM-CACHE are successes of an earlier run.
    if (event.result is TaskSkippedResult) skipped += path else ran += path
  }

  override fun close() {
    val notRun = buildList {
      skipped.sorted().forEach { add("${it.removePrefix(":")}: ${reason(it)}") }
      if (!parameters.macHost.get() && allTestsRequested) {
        add("macosArm64Test, iosSimulatorArm64Test, iosX64Test: Kotlin/Native builds Apple targets only on macOS")
      }
    }
    val log = Logging.getLogger(AppleTestWatch::class.java)
    notRun.forEach {
      log.warn("WARNING: NOT RUN: $it. These tests are UNVERIFIED on this machine.")
      if (parameters.githubActions.get()) log.warn("::warning title=Apple tests NOT RUN::$it")
    }
  }

  private fun reason(path: String): String {
    val arch = System.getProperty("os.arch")
    return when {
      path != ":core:macosArm64Test" && !parameters.simulatorInstalled.get() ->
        "no iOS simulator runtime is installed (xcrun simctl list devices available)"
      else -> "skipped because this $arch host cannot execute the target (iosX64Test is the Intel simulator, the arm64 targets need Apple Silicon)"
    }
  }
}

interface AppleTestWatchInjected {
  @get:Inject val listeners: BuildEventsListenerRegistry
}

val appleTestWatch = gradle.sharedServices.registerIfAbsent("appleTestWatch", AppleTestWatch::class) {
  parameters.macHost.set(isMacHost)
  parameters.simulatorInstalled.set(hasIosSimulator)
  parameters.githubActions.set(providers.environmentVariable("GITHUB_ACTIONS").map { it == "true" }.orElse(false))
}
objects.newInstance<AppleTestWatchInjected>().listeners.onTaskCompletion(appleTestWatch)
