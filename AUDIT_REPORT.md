# TrailBlazer audit — 2026-10-13

No source, config, or dependency file was modified. The writes are this report and the GitPulse board updates below. Nothing is ready to build until you say so.

The board was re-listed immediately before this report (`gitpulse_list_tasks`, `has_more: false`, `total: 25`). GitPulse Insights at the same time: one worktree (`main`, clean), collisions 0, unscanned worktrees 0. One idle Claude Desktop session (pid 42155, this checkout). No contended files. Findings are not flagged in-flight against another lane's dirty tree. The accessibility card is still `in_progress` and was not edited.

## How the audit was run

DevMap at the start of the audit was degraded (`devmap status`: `is_fresh` false, source differed from the indexed generation). `devmap dead` and impact walks reported `walk_incomplete`. Empty graph answers were treated as unknown. A later advisor call saw generation 415 with `is_fresh` true. Insights at report time still shows generation 415, `source_freshness` true, `analyzer_freshness` false, reason "stored extraction payload is obsolete". Verdicts below are from source that was read, not from the graph. The index was not rebuilt, and `devmap gap-record` was not run, because that writes `.devcouncil`.

Partial-parse files the graph cannot see, read from source when a finding cites them: `LocationRepository.kt`, `NmeaParser.kt`, `SensorRepositories.kt`, `SettingsScreen.kt`, `MoonPhases.kt`, `ShareScreen.kt`, `TripEditScreen.kt`, `BleDialogPermissionTest.kt`, `SpeedAlert.kt`, `NightSky.kt`, `Dem.kt`, `UrlCodec.kt`, `Track.kt` (large lost region), `PmtilesArchiveTest.kt`.

The dead-symbol walk returned one low-confidence row, `StringBuilder.appendCodePoint` in `XmlPull.kt:299`. That is a false positive. An empty dead list is unknown, not a clean bill.

The harness cannot pin a subagent model. Four slice auditors played the Sonnet role and wrote `/tmp/tb-audit-slice-{track,ui,geo,astro}.md`. The parent re-read cited lines and callers and played the Opus role. A code-reader subagent (`01a13738-07eb-70f3-9820-ee7415095c08`) played the Fable role and its grouping was followed. They were not Sonnet, Opus, or Fable. No tests or builds were run. Prior card logs that quote a green gate are claims, not this session's verification. Archived tasks were not paged.

## Severity × dimension

Each confirmed finding is counted once, under one primary dimension, at the severity set after the re-read and the advisor pass. `GEO-4` is the same defect as `TRK-1`. `GEO-3` is the same defect as `TRK-6`. Evidence-only rows (`UI-7`, `AST-4`, `GEO-10`) are confirmed drift and were appended to the existing refactor card. They are not new acceptance criteria.

| Dimension | Critical | High | Medium | Low |
| --- | ---: | ---: | ---: | ---: |
| Correctness (Android, JVM, Apple) | 0 | 5 | 7 | 7 |
| Async and lifecycle error boundaries | 0 | 1 | 5 | 0 |
| Defensive validation at a trust boundary | 0 | 0 | 0 | 1 |
| Security | 0 | 0 | 0 | 1 |
| Modular architecture | 0 | 0 | 0 | 3 |
| Strict typing | 0 | 0 | 0 | 0 |
| Deterministic testability | 0 | 0 | 0 | 0 |
| Performance and benchmarks | 0 | 0 | 0 | 0 |

Correctness, high: `TRK-1`, `TRK-2`, `GEO-2`, `AST-1`, `AST-2`.
Correctness, medium: `TRK-3`, `TRK-6`, `TRK-8`, `GEO-1`, `GEO-5`, `UI-3`, `UI-4`.
Correctness, low: `TRK-7`, `GEO-6`, `GEO-7`, `GEO-8`, `GEO-9`, `UI-6`, `AST-6`.
Async, high: `TRK-4`. Medium: `TRK-5`, `TRK-9`, `UI-1`, `UI-2`, `UI-5`.
Defensive validation, low: `AST-3` (downgraded; the shipped recorder does not feed this branch).
Security, low: `AST-5` (a cap-contract mismatch, not a gzip bomb).
Architecture, low: `UI-7`, `AST-4`, `GEO-10`.

`AST-1`'s 543-year Buddhist shift is inferred from the JDK `Calendar.getInstance` contract. It was not executed. The locale-calendar call itself was read (`PlatformTimeZone.kt:9-13` on Android and JVM, identical; Apple `NSCalendar.currentCalendar` and `?: 0.0` at `appleMain` lines 18-30).

No confirmed finding is a speed regression. `GEO-7` is wrong sample spacing at high latitude, so no benchmark-baseline card was filed.

## Top 10 risks

1. **Pause gap is lost after process death** (`TRK-1`, high). `TrackRecorder` writes `Recording`, then sets `segmentBreakPending` only in memory. `restore` clears that flag. A kill after resume and before the next kept fix stitches the pause. Distance and moving time include the drive between trailheads. Card `ft-4e93e3084c80b3a23538fb37a7e483b5`.
2. **An older Stop can finish a newer recording** (`TRK-4`, high). `onStartCommand` stores `latestStartId` before the `when` (`TrackRecordingService.kt:82`). `leave()` calls `stopSelf(latestStartId)` (`:195`). Android stops the service when that id is the newest start. The session mutex serializes the work and does not bind a Stop to its own start. Card `ft-d5fb6a6ac11ceb111d8e905ac2282482`.
3. **One bad GPX time demotes the whole track** (`GEO-2`, high). `GpxReader` keeps a track only when every point in every segment has a time. One null `Iso8601.parse` becomes one route, then `ImportExport.store` saves a trip simplified to `TripRules.MAX_STOPS` (50). A fully untimed track imported as a route is intentional and tested. Card `ft-95712c10c129ab729624b8244ec5bb31`.
4. **Ellipsoid heights disappear from GPX and KML** (`TRK-2`, high). `exportElevationM` returns a height only for `ElevationDatum.Msl`. Below API 34 the platform altitude is labeled ellipsoid unless `hasMslAltitude()`, so `<ele>` is omitted. Relabeling that height as sea level would be worse. The omit behavior stays locked until you pick a policy. Same room card.
5. **Civil time follows the locale calendar** (`AST-1`, high). Android and JVM use `Calendar.getInstance(zone)`, not a proleptic Gregorian calendar. Apple uses the current calendar and returns `0` when `dateFromComponents` fails. Day windows, "today", and sun events move with the locale. Card `ft-9640730955580646960f806e5fa5e7c7`.
6. **Daylight status ignores a sun that is up across midnight** (`AST-2`, high). `SolarEvents.status` does not use the wrap `inDaylight` already has. Sunset before sunrise in the device-day window reports `UntilSunrise` while the sun is up. Polar day and polar night are handled. This is a different bug from the twilight-slice fix. Card `ft-2cca5c489a91ce47eb630551e473756e`.
7. **Destroy-time flush can drop or collide the track tail** (`TRK-5`, medium, on the high recording card). `onDestroy` launches `session.flush()` and returns (`TrackRecordingService.kt:198-206`). `restore` sets `nextSeq = maxSeq + 1` (`TrackRecorder.kt:73`) and does not clear `buffer`. Process death drops up to 10 points or 5 seconds. A same-process restart can rewind `nextSeq` onto a buffer that still holds points. `insertPoints` stays `OnConflictStrategy.IGNORE`.
8. **Pretty-printed KML coordinates import as an empty file** (`GEO-1`, medium). `CoordinateStream` ends a token on any whitespace, then `parseKmlTuple` splits on commas (`GeoFormats.kt:63-70`, `90-114`). `lon, lat, ele` drops every point. Same parser card.
9. **Settings writes die with the screen, and forecast-off does not cancel the load** (`UI-1`, `UI-2`, medium). Settings uses `rememberCoroutineScope` for every pref write, including the slider (`SettingsScreen.kt:67-70`). `PrefWrites.persist` already exists and is unused there. The forecast toggle writes consent on that scope and calls `weather.clearCache()` immediately. It does not call `SkyViewModel.setConsent`, which cancels and joins before clearing. Card `ft-7cca354d76060793c5d4eee88d7ba060`.
10. **LNS elevation is labeled ellipsoid and shifted again on API 34+** (`TRK-3`, medium). `BleGattParsers.kt` always sets `AltitudeDatum.Ellipsoid`. Flags 10–11 are unread. The location path geoid-shifts anything that is not already sea level (`LocationRepository.kt:224-225`). Wrong height, not a lost track. Same room card. Unknown source stays unlabeled.

Close behind these: this app's own multi-segment KML re-imports as N trips (`TRK-6` / `GEO-3`), on the parser card.

## Build waves

A wave is at most four cards, and no two cards in a wave share a delta file. Cards in a later wave that name a file an earlier wave also names wait for that wave. Stale planned-file lists on already-merged cards were not reopened. The two `in_progress` cards are outside the waves.

### Wave 1

| Card | item_id | Priority | Severity | Status |
| --- | --- | ---: | --- | --- |
| Room schema v2 (folded: `TRK-1`, `TRK-2`, `TRK-3`, `UI-3`, `UI-4`) | `ft-4e93e3084c80b3a23538fb37a7e483b5` | 1 | high | review |
| GPX/KML (folded: `GEO-1`, `GEO-2`, `GEO-3`/`TRK-6`, `GEO-5`, `GEO-6`, `GEO-8`, `GEO-9`, `TRK-7`) | `ft-95712c10c129ab729624b8244ec5bb31` | 1 | high | review |
| Gregorian civil time (`AST-1`) | `ft-9640730955580646960f806e5fa5e7c7` | 1 | high | backlog |
| Daylight status across midnight (`AST-2`) | `ft-2cca5c489a91ce47eb630551e473756e` | 1 | high | backlog |

Room files: `TrackRecorder.kt`, `TrailDb.kt`, new `app/schemas/.../TrailDb/3.json` (do not rewrite `2.json`), `LocationRepository.kt`, `BleGattParsers.kt`, `ElevationDatum.kt`, `TrackDetail.kt`, `TripsViewModel.kt`, `OfflineMapCanvas.kt`. Parser files: `GeoFormats.kt`, `ImportExport.kt`. Calendar: the three `PlatformTimeZone.kt` actuals. Solar: `SolarDay.kt`.

### Wave 2

After wave 1, because the recording card shares `TrackRecorder.kt` with the room card.

| Card | item_id | Priority | Severity | Status |
| --- | --- | ---: | --- | --- |
| Bind stop and flush to their start (`TRK-4`, `TRK-5`) | `ft-d5fb6a6ac11ceb111d8e905ac2282482` | 1 | high | backlog |
| GATT reconnect waits for close (`TRK-9`) | `ft-221632e7a4c5362a46ad045539edecdf` | 2 | medium | backlog |
| NMEA straggler window wraps at UTC midnight (`TRK-8`) | `ft-dc043a4e6f2a705bd1795b590522310c` | 2 | medium | backlog |
| Pressure trend must not recurse (`AST-3`) | `ft-6f596348a8867ca4e6afd6fea1b1be47` | 3 | low | backlog |

Recording files: `TrackRecordingService.kt`, `RecordingSession.kt`, `TrackRecorder.kt`. Do not edit `TrailDb.kt`. BLE: `BleSensorManager.kt` only. NMEA: `NmeaParser.kt`. Do not change `TrackRecorder.offer`. Pressure: `Weather.kt`.

### Wave 3

After wave 1, because the camera card shares `OfflineMapCanvas.kt` with the room card. The room card owns the polyline break. The camera card owns center and zoom.

| Card | item_id | Priority | Severity | Status |
| --- | --- | ---: | --- | --- |
| UI state survival (folded: `UI-1`, `UI-2` only) | `ft-7cca354d76060793c5d4eee88d7ba060` | 2 | medium | review |
| Offline map camera survives rotation (`UI-5`) | `ft-c9c215ae8d200e26b75d03c2ef082f03` | 2 | medium | backlog |
| Shared place survives recreation (`UI-6`) | `ft-e4b8834b38e465ff9ddd8fbf0cda7168` | 3 | low | backlog |
| Morse case fold (`AST-6`) | `ft-a3652736501b92e97b4cca25557adb76` | 3 | low | backlog |

UI files: `SettingsScreen.kt`, `PrefWrites.kt`, `SkyViewModel.kt`. Camera: `OfflineMapCanvas.kt` (`remember` at lines 130-131). Share: `MainActivity.kt`. Morse: `Morse.kt:20`.

### Wave 4

| Card | item_id | Priority | Severity | Status |
| --- | --- | ---: | --- | --- |
| Non-gzip bytes honor the cap (`AST-5`) | `ft-56698cb4cd88dd868d3014aa2037b239` | 3 | low | backlog |
| DEM probe spacing (`GEO-7`, folded) | `ft-55a2fc3489b2064f8b0a23ac7cebfc40` | 2 | low | review |

Gzip: `androidMain` and `jvmMain` `PlatformIo.kt` (both return plain bytes at line 9 with no cap; Apple checks at lines 54-59). DEM: `Dem.kt` only. The old planned path `core/src/main/.../DemElevation.kt` is stale. Priority 2 was kept because the card was already 2. Severity stayed low.

### Not in a wave

`ft-306d4c7f23966650ba61d937a3a620d7` — refactor, priority 3, severity low, status review. Description now carries `UI-7`, `AST-4`, and `GEO-10` as evidence. The original eight criteria were left as they were. `androidMain` and `jvmMain` `PlatformIo.kt` are identical, and the same for `PlatformTimeZone.kt`. `core/src/main/.../TileUtils.kt` matches `commonMain` and is not a KMP source set. Do not start this while accessibility (`ft-e1a43cbcd1879d34d565d1b7afb449bf`) is in progress.

## Folded cards

Each fold sent the existing title, description, and criteria, then the additions. Priority and severity were only raised. Status was left `review` so the Manvi hold stays. `[MET — verified in source]` was applied only to the room card's original five criteria, whose implementing lines were re-read. The migration-test class named in criterion 3 was not re-opened. The criterion says that.

| item_id | What changed |
| --- | --- |
| `ft-4e93e3084c80b3a23538fb37a7e483b5` | Revision 3. Priority 2 → 1, severity medium → high. Five original criteria marked MET. New criteria `TRK-1`, `TRK-2`, `TRK-3`, `UI-3`, `UI-4`. |
| `ft-95712c10c129ab729624b8244ec5bb31` | Revision 5. Severity medium → high, priority stays 1. Previous ten criteria kept unmarked. New criteria for `GEO-1`, `GEO-2`, `GEO-3`/`TRK-6`, `GEO-5`, `GEO-6`, `GEO-8`, `GEO-9`, `TRK-7`. |
| `ft-7cca354d76060793c5d4eee88d7ba060` | Revision 4. Priority 2, severity medium, neither raised. Original nine criteria unmarked. New criteria `UI-1` and `UI-2`. Description notes wiring that was re-read and left unmarked: `TripEditViewModel` saved state (lines 63-96), `SkyViewModel.setConsent` cancel-and-join (292-307), `NowViewModel` rebaseline (`NowViewModel.kt:79-102`), `NowScreen` `rememberUpdatedState` (713-719). Settings does not call `setConsent`. |
| `ft-55a2fc3489b2064f8b0a23ac7cebfc40` | Revision 3. Priority 2, severity low. Original four criteria unmarked. New criterion `GEO-7`. |
| `ft-306d4c7f23966650ba61d937a3a620d7` | Revision 5. Evidence only. Priority 3, severity low. Original eight criteria unmarked. |

`related_tasks_exist` refusals were read and filed as separate cards with `reviewed_related`, not folded:

- Calendar refused against the refactor card and the in-progress release card. Different work.
- Recording refused against service hardening `ft-b4ae6954ab9fef22388fda5cd80c0a45`. That card's log says `stopSelf` uses the start id. The bug is which id is captured.
- BLE reconnect refused against `ft-ea3d275bbfb0813f4de131c76948cc30`. That card is permissions, NMEA reassembly, and the GATT op queue. `close()` beside `disconnect()` is `BleSensorManager.kt:479-486`.
- NMEA midnight refused against the new solar card and the BLE card. Shared words, different bugs.
- Map camera refused against offline tiles `ft-54b6c5af6f8e9db8fb8eadaaeafae947` and the share card. Tiles versus `remember`, and share text versus the camera.
- Morse refused against robustness `ft-75fbb813c02539bfbd583097b52ada4e`. That card's F18 is an empty timeline. `Morse.isSilent` is present at lines 22-23. The new bug is `String.uppercase()` at line 20.
- Gzip refused against the refactor card, the solar card, and then the new calendar card. Shared platform labels. The bug is the plain-byte cap.

## Verified-stale cards

These stay where they are. Done still needs a Manvi `work.verify.task` record. Merge-log text is not proof. Line numbers on the older cards predate later edits.

Left in `review`, criteria not prefixed MET, logs claim a merge:

- `ft-95712c10c129ab729624b8244ec5bb31` — parser. Original ten criteria not re-closed. Spots re-read and left unmarked: coordinate streaming, import in one transaction, `wrapLon`.
- `ft-7cca354d76060793c5d4eee88d7ba060` — UI state. Samples listed in the fold table. The rest of the nine were not re-closed.
- `ft-55a2fc3489b2064f8b0a23ac7cebfc40` — DEM profile. Original four not re-verified end to end. `GEO-7` is additional.
- `ft-306d4c7f23966650ba61d937a3a620d7` — refactor. Original eight not re-closed. Duplicate source sets were re-checked with `diff`.
- `ft-b4ae6954ab9fef22388fda5cd80c0a45` — service hardening, priority 1, high, revision 3. Log says merged `24db37f`. Not overwritten. `TRK-4` and `TRK-5` are a new card.
- `ft-ea3d275bbfb0813f4de131c76948cc30` — BLE path, priority 1, high, revision 5. Log says merged `1608128`. API 30 and below already require fine location (`Permissions.kt:32-37`, prior read). Not overwritten.
- `ft-54b6c5af6f8e9db8fb8eadaaeafae947` — offline tiles, priority 1, medium, revision 5. Log says merged `669ab3d`. Apple gzip rejection is the documented limit. Not overwritten.
- `ft-75fbb813c02539bfbd583097b52ada4e` — robustness, priority 2, medium, revision 3. Log says merged `1704f43`. Twilight slices, speed-alert rearm, and the empty Morse timeline stay that card's claim. Not overwritten.
- `ft-3a3572d5d626940ac362c1f98fbfa1e5` — sensor CSV, review. Not edited. `AST-7` was rejected and not added.

Left `done`, not reopened:

- `ft-30f82be81b20b02696901b76a9214db5` — WMM tables.
- `ft-762d73834d6aa7e180ec2cf3863da418` — BLE external GPS feature.
- `ft-9d7c1e9136ac20921f51f77b9086b307` — ephemeris at sensor rate. Auditors say that bug is not back.

Left `in_progress`, not touched:

- `ft-e1a43cbcd1879d34d565d1b7afb449bf` — accessibility and adaptive layouts. Navigation rail and two-pane Trips are still absent from the planned files' remaining work. `UI-5` is a different file and was not added here.
- `ft-09d169d802fed1a2b33680c7c3a14f5f` — release readiness. `applicationId` is still `com.example.trailblazer`. The decided name `com.trailblazer.vbcr` lives on this card. `.env.example` `GEMINI_API_KEY` stays held. CI on a real runner is still open.

Room originals marked MET, with the limit on the migration test: segments and `TrackStats`, `exportElevationM` MSL-only plus the nullable column, `MIGRATION_1_2` SQL present (`TrailDb.kt:254-266`), `lastPoint` absent from Kotlin sources (`rg`, not DevMap), `createdMs` preserved (`TrailDb.kt:202-207`).

## Rejected findings

- **`AST-7` CSV formula injection.** Current producers are app-authored notes and do not start with `=`, `+`, `-`, `@`, or tab. Numeric cells such as `-9.81` must stay numeric. Not filed.
- **XXE and billion laughs.** `XmlPull` does not resolve entities. `skipDoctype` is not quote-aware, so a `>` inside a SYSTEM id can end the skip. It does not fetch a DTD. Not filed.
- **Ephemeris recomputed at sensor rate.** Not present again. The done card stays done.
- **Accessibility leftovers** (navigation rail, two-pane Trips, landscape tests, satellite object list). Still open on the in-progress card. Not re-filed.
- **`applicationId` rename and removing `GEMINI_API_KEY` from `.env.example`.** Already on the in-progress release card. Agents do not edit `.env*`. Not re-filed.
- **MotionGate "a GNSS gap clears stillness".** Once stationary, a gap does not clear stillness. That matches the GNSS duty-cycle contract.
- **A fully untimed GPX track imported as a route.** Intentional, and tested.
- **`TRK-7` as a stats bug.** `TrackStats` does not bridge segments. The low finding is a chart or export that walks `seq` across a reversed join.
- **MultiGeometry children dropped.** Points and linestrings are kept. What remains is grouping (`GEO-3`) and a Placemark that emits both a route and a track (`GEO-9`).
- **Night-sky 10-minute peak sampling.** The auditor withdrew it.
- **`shareReading` resubscribe, level-haptics 80 ms blip, ISA extrapolation, `RiseSetFinder` near `Long.MAX_VALUE`.** Auditors rejected these after reading the code.
- **`StringBuilder.appendCodePoint` dead-symbol row.** False positive.

Checked and left alone, from auditor reads plus parent samples: immutable `PendingIntent`s, recording service not exported, only `MainActivity` exported, no `FileProvider`, backup rules, BLE `neverForLocation`, no background location, HTTPS with redirects off, `openUrl` catches `ActivityNotFoundException`, whistle failure surfaced, torch off in `finally`, release minify and shrink, signing all-four-or-none from the environment, CI `contents: read`. Offline map and DEM path checks were cited clean by the track auditor (name sanitised, bounded copy, blob cap, HGT size, `DemTileId.fileName`). Those guards were sampled, not all re-read. Apple gzip on tiles stays a documented limit.

## Decisions made without asking

- The index was not rebuilt. Empty graph answers stayed unknown. `devmap gap-record` was not run.
- Subagents were not model-pinned, because the harness has no model field. Roles were played as described above.
- Archived tasks were not listed.
- The two `in_progress` cards were not edited, completed, or re-filed.
- `.env*` was not edited.
- No benchmark-baseline card. `GEO-7` is sample spacing.
- `AST-7` was not filed.
- `TRK-3` is medium on its own. The room card stays high because of `TRK-1` and `TRK-2`.
- `TRK-2` does not edit `GeoFormats.kt` in wave 1. An extension element would share that file with the parser card.
- `PressureSampler.kt` is not on the room card. `seaLevelAltitudeM` drops non-MSL on purpose.
- `OpenMeteo.kt` is not on the UI-state delta. The hole is Settings calling `clearCache` without `setConsent`'s cancel-and-join. The in-lock generation check already exists.
- `UI-5` stays priority 2 medium. Rotation is a normal configuration change. It was not added to the accessibility card.
- `TRK-9` stays priority 2 medium.
- `AST-1` and `AST-2` stay priority 1 high. `AST-3`, `AST-5`, `AST-6`, `GEO-6`, `GEO-8`, `GEO-9`, `TRK-7`, `UI-6`, and `GEO-7` stay low. `GEO-7`'s card stays priority 2.
- `GEO-2` is high. The parser card severity was raised. Its priority stays 1.
- `UI-1` stays on the priority-2 UI card. That card's priority was not raised.
- The room card's "KML reimports as N trips" and "seq order" notes are the parser card's `GEO-3` and `TRK-7`.
- The parser card's old "hoist the regex" criterion was not reopened as performance work.
- MET prefixes were applied only on the room card's original five criteria.
- Folded cards stayed in `review`. New cards were filed as `backlog`.
- No card was deleted, merged, or moved to `done`.
- `tb-platform-calendar`, `tb-recording-start-generation`, `tb-ble-reconnect-close`, `tb-nmea-midnight-wrap`, `tb-map-camera-saveable`, `tb-morse-ascii-case`, and `tb-gzip-plain-cap` were refused once for related tasks and filed again with `reviewed_related` after those cards were read. They were not folded.
- Advisor overrides were followed. There was no disagreement that changes scope.

## Owner decisions before building

These do not block the audit. They are written on the cards.

1. **Pause-gap persistence.** Prefer a v3 migration. Amend v2 only if no installed database has opened v2. Whether any device has opened v2 is unverified. Do not rewrite `2.json`.
2. **Heights below API 34.** Current code and tests omit non-MSL `<ele>`. Keep that until you choose a geoid or an explicit non-MSL extension. Do not label an ellipsoid height as sea level. An extension element is a parser-card change.
3. **`applicationId` `com.trailblazer.vbcr`.** Already decided, still on the in-progress release card.
4. **`GEMINI_API_KEY` in `.env.example`.** Held. Do not edit `.env*`.
5. **BLE Location and Navigation Service altitude.** Flags 10–11 are the elevation source. The GATT spec does not name the datum. Unknown source stays unlabeled, so API 34+ does not geoid-shift it a second time.
6. **KML export.** `gx:Track` (keeps times) versus teaching the importer to treat a MultiGeometry of only LineStrings as one track. Either fixes files this app already wrote. The choice is on the parser card.

Waiting for your go-ahead before any build.
