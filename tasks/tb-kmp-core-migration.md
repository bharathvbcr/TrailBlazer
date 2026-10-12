# Task brief v1

## Title
Migrate :core module to Kotlin Multiplatform (KMP)

Task: ft-e6f583c150084e178d886fc13658e6a0 (revision 1)
Updated (Unix seconds): 1791254495
Type: improvement
Status: done
Severity: low
Owner: Unassigned
Priority: 2 (Normal)
Due (Unix seconds): None
Labels: architecture, kmp, multiplatform, build
Enhancement field locks: 

## Repositories
- TrailBlazer [repo-39153-18dbd06d89ce5e80-1] (revision 1) — primary

Home workspace: None

## Description
The :core module is already 100% pure Kotlin/JVM with zero Android dependencies, encapsulating Meeus celestial mechanics, JPL planetary orbits, geodesy, ICAO atmospheric models, and XML streaming. Migrating :core to Kotlin Multiplatform (KMP targeting JVM, Android, iOS, and macOS) establishes a portable foundation for native multi-platform shells.

## Planned files
- core/build.gradle.kts
- gradle/libs.versions.toml

## Acceptance criteria
- [x] Configure :core with kotlin("multiplatform") plugin supporting JVM, Android, and Apple targets
  - `core/build.gradle.kts`: `jvm()`, Android KMP library with host tests, `macosArm64`, `iosArm64`, `iosSimulatorArm64`, `iosX64`, each with a static `TrailBlazerCore` framework.
- [x] Verify all existing astronomy, geodesy, and stress tests execute on KMP JVM and Apple targets
  - `./gradlew :core:allTests` on an Apple Silicon Mac ran the same 209 `commonTest` tests with 0 failures and 0 skips on `jvmTest`, `testAndroidHostTest`, `macosArm64Test` and `iosSimulatorArm64Test`.
  - Not run: `iosX64Test`. Kotlin does not execute the Intel simulator on Apple Silicon; it is compiled and linked, and `reportAppleTestsNotRun` says so on every run.
  - Getting there needed a fix: `TileUtilsTest` used the JVM-only `Charsets`, so `commonTest` did not compile for any Apple target until then.
- [x] Retain binary and source compatibility for the :app Android module
  - `:app` builds and tests against the KMP `:core` (`:app:testDebugUnitTest`, `:app:lintDebug`, `:app:assembleRelease`).
- [ ] Confirm CI / Gradle test gates pass without degradation
  - `.github/workflows/ci.yml` now runs the gate on Linux and macOS, but it has not been run on a GitHub runner. Open until the first green run.
