# Task brief v1

## Title
Bundle World Magnetic Model (WMM) tables for true heading precision

Task: ft-30f82be81b20b02696901b76a9214db5 (revision 1)
Updated (Unix seconds): 1791254445
Type: improvement
Status: ready
Severity: low
Owner: Unassigned
Priority: 2 (Normal)
Due (Unix seconds): None
Labels: sensors, compass, navigation, algorithms
Enhancement field locks: 

## Repositories
- TrailBlazer [repo-39153-18dbd06d89ce5e80-1] (revision 1) — primary

Home workspace: None

## Description
Android GeomagneticField relies on firmware-embedded WMM tables, which drift after epoch on older OS builds. Bundle WMM coefficients or an offline calculation update in :core to guarantee sub-0.1° declination accuracy anywhere on Earth.

## Acceptance criteria
- [x] Embed WMM2025/2030 coefficients table in :core
- [x] Verify declination against NOAA WMM test values
- [x] Fallback gracefully to system GeomagneticField when needed
