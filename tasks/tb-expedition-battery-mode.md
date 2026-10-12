# Task brief v1

## Title
Expedition power saver mode with adaptive GNSS duty cycling

Task: ft-ec49a938042acc0373fac6be7f8203f6 (revision 1)
Updated (Unix seconds): 1791254495
Type: improvement
Status: done
Severity: medium
Owner: Unassigned
Priority: 1 (High)
Due (Unix seconds): None
Labels: tracking, battery, performance, location
Enhancement field locks: 

## Repositories
- TrailBlazer [repo-39153-18dbd06d89ce5e80-1] (revision 1) — primary

Home workspace: None

## Description
Multi-day wilderness expeditions require extreme battery endurance. Currently, the foreground tracking service collects continuous GPS fixes at 1s intervals. Implementing an adaptive expedition mode (e.g. 30s, 60s, or 300s fix intervals while walking, combined with accelerometer motion gating when stationary) will dramatically prolong device battery life.

## Planned files
- app/src/main/java/com/example/trailblazer/tracking/TrackRecordingService.kt
- app/src/main/java/com/example/trailblazer/data/Prefs.kt

## Acceptance criteria
- [x] Provide tracking interval options in Settings: Continuous (1s), Balanced (15s), and Expedition (60s/300s)
  - `TrackingMode` in `data/Prefs.kt:31-34`; the picker is `ui/settings/SettingsScreen.kt:115-120`.
- [x] Automatically sleep GPS receiver when accelerometer detects stationary state for over 5 minutes
  - `tracking/GnssDutyCycle.kt` (stillness gate plus significant-motion wake-up); `GnssDutyCycleTest.gpsSleepsAfterFiveMinutesStillAndWakesOnSignificantMotion`.
- [x] Accurately stitch segment intervals without corrupting distance or moving-time metrics
  - `TrackTest.aGapIsMovingOnlyIfTheAverageSpeedAcrossItIs` / `aGapCoveredAtWalkingPaceIsMoving` (`core/src/commonTest`); `PowerSaveResumeTest` asserts the gap is not moving time.
- [x] Test track resumption across system power save mode transitions
  - `app/src/test/.../tracking/PowerSaveResumeTest.kt`.
- Not covered by automated tests (still manual, see docs/TESTING.md "Not covered by automated tests"): a real walk with the 5-minute mode and the sensor hub's accelerometer FIFO on a sleeping phone.
