# Task brief v1

## Title
Migrate :core module to Kotlin Multiplatform (KMP)

Task: ft-e6f583c150084e178d886fc13658e6a0 (revision 1)
Updated (Unix seconds): 1791254495
Type: improvement
Status: ready
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
- [ ] Configure :core with kotlin("multiplatform") plugin supporting JVM, Android, and Apple targets
- [ ] Verify all existing astronomy, geodesy, and stress tests execute on KMP JVM and Apple targets
- [ ] Retain binary and source compatibility for the :app Android module
- [ ] Confirm CI / Gradle test gates pass without degradation
