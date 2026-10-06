# Task brief v1

## Title
Offline topographical & contour map tile support (MBTiles / Vector tiles)

Task: ft-54b6c5af6f8e9db8fb8eadaaeafae947 (revision 1)
Updated (Unix seconds): 1791254495
Type: feature
Status: backlog
Severity: medium
Owner: Unassigned
Priority: 1 (High)
Due (Unix seconds): None
Labels: maps, offline, trips, navigation, ui
Enhancement field locks: 

## Repositories
- TrailBlazer [repo-39153-18dbd06d89ce5e80-1] (revision 1) — primary

Home workspace: None

## Description
TrailBlazer currently renders straight-line route sketches and relies on external map app intents for turn-by-turn navigation. In remote wilderness without mobile data, external map apps often lack cached topographic maps. Add offline map tile rendering (supporting MBTiles / PMTiles or MapLibre offline vector/raster contour tiles) directly within the Trips tab.

## Planned files
- app/src/main/java/com/example/trailblazer/ui/trips/OfflineMapCanvas.kt
- core/src/main/kotlin/com/trailblazer/core/geo/TileUtils.kt

## Acceptance criteria
- [ ] Support importing local .mbtiles or .pmtiles via Storage Access Framework (SAF)
- [ ] Render offline raster/vector contour tiles smoothly on a Compose canvas
- [ ] Overlay planned trip waypoints and recorded GPS tracks on the offline map
- [ ] Maintain 100% offline functionality with zero network telemetry
