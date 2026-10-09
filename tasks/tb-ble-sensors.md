# Task brief v1

## Title
Bluetooth Low Energy (BLE) external GPS and environmental sensor support

Task: ft-762d73834d6aa7e180ec2cf3863da418 (revision 1)
Updated (Unix seconds): 1791254495
Type: feature
Status: completed
Severity: low
Owner: Unassigned
Priority: 3 (Low)
Due (Unix seconds): None
Labels: hardware, bluetooth, sensors, location
Enhancement field locks: 

## Repositories
- TrailBlazer [repo-39153-18dbd06d89ce5e80-1] (revision 1) — primary

Home workspace: None

## Description
Under dense forest canopy, slot canyons, or cold alpine conditions, phone internal GPS and sensors can experience degraded signal or battery drain. Adding BLE peripheral support allows pairing external high-sensitivity GNSS receivers (e.g. Garmin GLO 2) or external barometric sensors.

## Planned files
- app/src/main/java/com/example/trailblazer/sensors/BleSensorManager.kt

## Acceptance criteria
- [x] Scan and connect to standard BLE Location and Environmental Sensing services
- [x] Parse external NMEA / location streams into LocationRepository
- [x] Indicate external sensor connectivity status clearly in Now tab chips
- [x] Gracefully fallback to internal phone sensors if BLE connection disconnects
