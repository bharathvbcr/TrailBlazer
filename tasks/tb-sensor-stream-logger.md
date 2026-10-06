# Task brief v1

## Title
High-rate raw sensor stream logger and CSV export for field research

Task: ft-3a3572d5d626940ac362c1f98fbfa1e5 (revision 1)
Updated (Unix seconds): 1791254495
Type: feature
Status: ready
Severity: low
Owner: Unassigned
Priority: 2 (Normal)
Due (Unix seconds): None
Labels: diagnostics, sensors, export, tools
Enhancement field locks: 

## Repositories
- TrailBlazer [repo-39153-18dbd06d89ce5e80-1] (revision 1) — primary

Home workspace: None

## Description
The Diagnostics screen visualizes real-time GNSS constellations, 3D accelerometer plots, thermals, and sound levels, but lacks a recording pipeline. Field expeditions, search-and-rescue squads, and environmental researchers need high-rate time-series logging of raw accelerometer, gyroscope, barometer, and GNSS pseudoranges to CSV.

## Planned files
- app/src/main/java/com/example/trailblazer/sensors/SensorLogger.kt
- app/src/main/java/com/example/trailblazer/ui/tools/DiagnosticsScreen.kt

## Acceptance criteria
- [ ] Add a session record toggle in the Diagnostics tool suite
- [ ] Stream raw sensor events to a buffered file without dropping UI frames
- [ ] Export structured CSV with UTC timestamps, sensor types, raw axes, and accuracy
- [ ] Safely prompt for user storage save location via SAF
