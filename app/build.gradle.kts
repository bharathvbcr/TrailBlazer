import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.DirectoryProperty

plugins {
  alias(libs.plugins.android.application)
  alias(libs.plugins.compose.compiler)
  alias(libs.plugins.kotlin.serialization)
  alias(libs.plugins.ksp)
  alias(libs.plugins.room)
}

/**
 * Release identity. Gradle properties (`-PversionCode=…`, `-PversionName=…`, or gradle.properties) win; otherwise the
 * commit count of the checkout gives a monotonic versionCode.
 *
 * The count is only a version when the clone has its full history. A shallow clone (`actions/checkout` defaults to
 * depth 1) counts 1 commit, which would ship a versionCode that Play rejects as older than the last upload. So the
 * count is used only from a complete clone; otherwise (a shallow clone, or a source tarball with no git) the fixed
 * development values apply, and the `verifyReleaseIdentity` task refuses to build a SIGNED release from them.
 */
fun git(vararg args: String): String? = runCatching {
    providers.exec {
        commandLine("git", *args)
        isIgnoreExitValue = true
    }.standardOutput.asText.get().trim()
}.getOrNull()?.takeIf { it.isNotEmpty() }

val explicitVersionCode: Int? = providers.gradleProperty("versionCode").orNull?.let { raw ->
    raw.toIntOrNull()?.takeIf { it > 0 } ?: throw GradleException("-PversionCode must be a positive integer, got '$raw'.")
}
val historyCommitCount: Int? =
    if (git("rev-parse", "--is-shallow-repository") == "false") git("rev-list", "--count", "HEAD")?.toIntOrNull()?.takeIf { it > 0 } else null
val releaseVersionCode: Int = explicitVersionCode ?: historyCommitCount ?: 2
val releaseVersionName: String = providers.gradleProperty("versionName").orNull
    ?: historyCommitCount?.let { "2.0.$it" } ?: "2.0"
val versionCodeIsTrusted = explicitVersionCode != null || historyCommitCount != null

/**
 * Release signing comes only from the environment or Gradle properties, never from the repository. All four values
 * present gives a signed release. None gives an UNSIGNED release APK (app-release-unsigned.apk): it builds and shrinks,
 * and cannot be installed, so it can never be mistaken for a shippable build. A partial set fails the build.
 */
fun signingValue(property: String, env: String): String? =
    (providers.gradleProperty(property).orNull ?: providers.environmentVariable(env).orNull)?.takeIf { it.isNotBlank() }

val signingStoreFile = signingValue("trailblazer.signing.storeFile", "TRAILBLAZER_KEYSTORE_PATH")
val signingStorePassword = signingValue("trailblazer.signing.storePassword", "TRAILBLAZER_KEYSTORE_PASSWORD")
val signingKeyAlias = signingValue("trailblazer.signing.keyAlias", "TRAILBLAZER_KEY_ALIAS")
val signingKeyPassword = signingValue("trailblazer.signing.keyPassword", "TRAILBLAZER_KEY_PASSWORD")
val signingParts = listOf(signingStoreFile, signingStorePassword, signingKeyAlias, signingKeyPassword)
val releaseSigningConfigured = signingParts.all { it != null }
if (!releaseSigningConfigured && signingParts.any { it != null }) {
    throw GradleException(
        "Incomplete release signing configuration: set all of trailblazer.signing.storeFile / storePassword / keyAlias / " +
            "keyPassword (or TRAILBLAZER_KEYSTORE_PATH / TRAILBLAZER_KEYSTORE_PASSWORD / TRAILBLAZER_KEY_ALIAS / " +
            "TRAILBLAZER_KEY_PASSWORD), or none of them for an unsigned release.",
    )
}

/**
 * Runs before every release-variant build (`preReleaseBuild`). A signed release is a shippable one, so it must carry a
 * trustworthy versionCode and a keystore that exists; an unsigned release (CI, local R8 check) is not subject to this.
 */
val verifyReleaseIdentity = tasks.register("verifyReleaseIdentity") {
    val signed = releaseSigningConfigured
    val trusted = versionCodeIsTrusted
    val keystore = signingStoreFile?.let { file(it) }
    doLast {
        if (!signed) return@doLast
        if (!trusted) {
            throw GradleException(
                "Refusing to build a SIGNED release with a derived versionCode: this clone is shallow or has no git history, " +
                    "so the commit count is not a version. Fetch full history (git fetch --unshallow; actions/checkout " +
                    "fetch-depth: 0) or pass -PversionCode=<n>.",
            )
        }
        if (!keystore!!.isFile) throw GradleException("Release keystore not found: $keystore")
    }
}
tasks.configureEach { if (name == "preReleaseBuild") dependsOn(verifyReleaseIdentity) }

android {
    namespace = "com.example.trailblazer"
    compileSdk = 37
    defaultConfig {
        applicationId = "com.example.trailblazer"
        minSdk = 24
        targetSdk = 36
        versionCode = releaseVersionCode
        versionName = releaseVersionName
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    signingConfigs {
        if (releaseSigningConfigured) {
            create("release") {
                storeFile = file(signingStoreFile!!)
                storePassword = signingStorePassword
                keyAlias = signingKeyAlias
                keyPassword = signingKeyPassword
            }
        }
    }

    buildTypes {
        release {
            if (releaseSigningConfigured) signingConfig = signingConfigs.getByName("release")
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures {
      compose = true
      aidl = false
      buildConfig = false
      shaders = false
    }

    packaging {
      resources {
        excludes += "/META-INF/{AL2.0,LGPL2.1}"
      }
    }

    testOptions {
        unitTests.isIncludeAndroidResources = true
        unitTests.all {
            it.maxHeapSize = "2g"
            // Robolectric's SDK 36 sandbox reflects into FileDescriptor internals on JDK 21.
            it.jvmArgs("--add-exports=java.base/jdk.internal.access=ALL-UNNAMED", "--add-opens=java.base/jdk.internal.access=ALL-UNNAMED")
        }
    }

    lint {
        abortOnError = true
        warningsAsErrors = false
    }
}

kotlin {
    jvmToolchain(21)
}

room {
    schemaDirectory("$projectDir/schemas")
}

/**
 * Bundles the Markdown files in docs/ into the APK for the in-app Developer docs screen. docs/ stays the single source;
 * the output directory is rebuilt from scratch each run, so a renamed or deleted doc never lingers.
 */
abstract class SyncDocs : DefaultTask() {
    @get:InputFiles
    abstract val docs: ConfigurableFileCollection

    @get:OutputDirectory
    abstract val outputDir: DirectoryProperty

    @TaskAction
    fun sync() {
        val out = outputDir.get().asFile.resolve("docs")
        out.deleteRecursively()
        out.mkdirs()
        docs.files.sortedBy { it.name }.forEach { it.copyTo(out.resolve(it.name)) }
    }
}

val syncDocs = tasks.register<SyncDocs>("syncDocs") {
    docs.from(rootProject.fileTree("docs") { include("*.md") })
}

androidComponents {
    onVariants { variant -> variant.sources.assets?.addGeneratedSourceDirectory(syncDocs, SyncDocs::outputDir) }
}

dependencies {
  implementation(project(":core"))

  val composeBom = platform(libs.androidx.compose.bom)
  implementation(composeBom)
  androidTestImplementation(composeBom)
  testImplementation(composeBom)

  implementation(libs.androidx.core.ktx)
  implementation(libs.androidx.lifecycle.runtime.ktx)
  implementation(libs.androidx.lifecycle.service)
  implementation(libs.androidx.activity.compose)
  implementation(libs.androidx.lifecycle.runtime.compose)
  implementation(libs.androidx.lifecycle.viewmodel.compose)

  implementation(libs.androidx.compose.ui)
  implementation(libs.androidx.compose.ui.tooling.preview)
  implementation(libs.androidx.compose.material3)
  debugImplementation(libs.androidx.compose.ui.tooling)
  debugImplementation(libs.androidx.compose.ui.test.manifest)

  implementation(libs.androidx.navigation3.ui)
  implementation(libs.androidx.navigation3.runtime)
  implementation(libs.androidx.lifecycle.viewmodel.navigation3)

  implementation(libs.kotlinx.coroutines.core)
  implementation(libs.kotlinx.serialization.json)
  implementation(libs.androidx.room.runtime)
  implementation(libs.androidx.room.ktx)
  ksp(libs.androidx.room.compiler)
  implementation(libs.androidx.datastore.preferences)
  implementation(libs.haze)
  implementation(libs.haze.blur)
  implementation(libs.androidx.camera.camera2)
  implementation(libs.androidx.camera.lifecycle)
  implementation(libs.androidx.camera.view)

  testImplementation(libs.junit)
  testImplementation(libs.kotlinx.coroutines.test)
  testImplementation(libs.robolectric)
  testImplementation(libs.turbine)
  testImplementation(libs.androidx.test.core)
  testImplementation(libs.androidx.test.ext.junit)
  testImplementation(libs.androidx.room.testing)
  testImplementation(libs.androidx.compose.ui.test.junit4)

  androidTestImplementation(libs.androidx.compose.ui.test.junit4)
  androidTestImplementation(libs.androidx.test.core)
  androidTestImplementation(libs.androidx.test.ext.junit)
  androidTestImplementation(libs.androidx.test.runner)
  androidTestImplementation(libs.androidx.test.espresso.core)
}
