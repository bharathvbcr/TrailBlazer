# Task brief v1

## Title
Trip elevation profiles and cumulative terrain ascent/descent calculations

Task: ft-55a2fc3489b2064f8b0a23ac7cebfc40 (revision 1)
Updated (Unix seconds): 1791254495
Type: feature
Status: ready
Severity: low
Owner: Unassigned
Priority: 2 (Normal)
Due (Unix seconds): None
Labels: trips, elevation, algorithms, planning
Enhancement field locks: 

## Repositories
- TrailBlazer [repo-39153-18dbd06d89ce5e80-1] (revision 1) — primary

Home workspace: None

## Description
While recorded tracks calculate real elevation gain and loss with a 3m hysteresis filter, planned trips currently only calculate straight-line distances and daylight per stop. Integrating an offline digital elevation model (DEM / SRTM 1-arc-second HGT) sampler will enable pre-trip elevation profiles, gradient warnings, and realistic cumulative ascent/descent planning.

## Planned files
- core/src/main/kotlin/com/trailblazer/core/geo/DemElevation.kt
- app/src/main/java/com/example/trailblazer/ui/trips/ElevationProfilePlot.kt

## Acceptance criteria
- [ ] Sample elevation along multi-stop routes using local SRTM / HGT elevation tiles
- [ ] Display visual elevation profiles with peak and valley markers in Trip Details
- [ ] Compute cumulative elevation gain and loss with noise rejection
- [ ] Unit test DEM sampling against known summit elevations
