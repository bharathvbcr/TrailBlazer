# TrailBlazer backlog build report

Session date: 2026-10-13. Main is still `c1530c189fe612c00fe7ad259e5a8cf4df4f1be4`. Nothing was merged. No task was requested done. Manvi `work.verify.task` has no passing record for these branches, so review is where a finished card stays.

This report is a snapshot. Wave 1, wave 2, wave 4, and the shared-place card are in review, verification empty. Nothing has been merged. No card was requested done.

## In review

| Task | Revision | Branch / head | What was judged |
| --- | --- | --- | --- |
| ft-2cca5c489a91ce47eb630551e473756e daylight status | 3 | `tb/daylight-status-wrap` `af2e248` | Opus read `SolarDay.status`. Past the window the status is `AfterSunset`. At the window end it stays `DaylightLeft(0)`. |
| ft-9640730955580646960f806e5fa5e7c7 Gregorian civil time | 3 | `tb/gregorian-civil-time` `075ff76` | Opus read the Apple actual. Integer proleptic `CivilDate.daysFromCivil`, later overlap wins, a gap throws, a Double that cannot hold the millis throws. JVM and Android use `GregorianCalendar` with `gregorianChange` at `Long.MIN_VALUE`. |
| ft-4e93e3084c80b3a23538fb37a7e483b5 room datum and segments | 5 | `tb/room-datum-segment-ui` `00219d7` | Opus read the production commit `6de3e04`. Adversarial commit object says parent `6de3e04`, message "Add adversarial datum and segment cases". TRK-1 is still open. |
| ft-6f596348a8867ca4e6afd6fea1b1be47 pressure trend | 3 | `tb/pressure-trend-recursion` `d0a6e43` | Opus read `Weather.kt`. Finite elevations only, and no recurse unless the list is strictly shorter. |
| ft-dc043a4e6f2a705bd1795b590522310c NMEA midnight | 3 | `tb/nmea-midnight-wrap` `bce9deb` | Opus read `accept` at `44e97f9`. The closed 0..30 s window runs even while a later epoch is open, before flush. Same timestamp still joins the open epoch. Parent decoded `bce9deb` against `44e97f9`: the only changed path is `NmeaParserTest.kt`. Adversarial pass reported CLEAN. Verification is null. Not requested done. |
| ft-95712c10c129ab729624b8244ec5bb31 GPX/KML | 7 | `tb/gpx-kml-wave1` `17b87bc` | Opus read `16b1b22`. Placemark description depth, U+FEFF kept, `<coord>` only under Track, signed-zero speed omitted. Parent decoded `17b87bc`: only `GeoFormatsTest.kt`. GEO-3 still open. Verification is null. Not requested done. |
| ft-221632e7a4c5362a46ad045539edecdf GATT close | 3 | `tb/gatt-reconnect-close` `027258a` | Opus read `403cd6a`. `close()` is posted from `STATE_DISCONNECTED` or the 5 s timeout. A second `connect()` waits without blocking. Parent decoded `027258a`: only `AndroidBleTransportTest.kt`. `BleGattParsers.kt` was not edited. Verification is null. Not requested done. |
| ft-d5fb6a6ac11ceb111d8e905ac2282482 recording stop and flush | 3 | `tb/recording-stop-flush` `953f378` | Opus read `32b07e5`. A start that arrives during the write is undone: Recording, `endedMs` cleared, no segment break. Parent decoded `953f378`: tests only. Destroy joins the flush for 5 s, then returns and does not claim the tail. Verification is null. Not requested done. |
| ft-a3652736501b92e97b4cca25557adb76 Morse ASCII fold | 3 | `tb/morse-ascii-fold` `6303534` | Opus read `8e1f517`. ASCII a-z fold, no `uppercase` call. Parent tree-walked `6303534`: only `MorseAsciiFoldHostileTest.kt`. Verification is null. Not requested done. |
| ft-56698cb4cd88dd868d3014aa2037b239 plain-byte cap | 3 | `tb/plain-byte-cap` `b0894bd` | Opus read both actuals. Same blob `361903d0`. Non-gzip `size > max` throws. Parent tree-walked `b0894bd`: only `PlainByteCapAttackTest.kt`. Verification is null. Not requested done. |
| ft-e4b8834b38e465ff9ddd8fbf0cda7168 shared place | 3 | `tb/share-intent-recreate` `f00c8ea` | `onNewIntent` calls `setIntent`. After the share is on the back stack, `onShareHandled` puts the same launch `Intent` back. `ActivityScenario` 1.7 ignores lifecycle events once `getIntent()` no longer matches the launch intent, and Robolectric does not advance `SystemClock` during that wait. Production `98434f7` changes only `MainActivity.kt` and `ShareIntentRecreateTest.kt`. Attack `f00c8ea` adds only `ShareIntentHostileTest.kt`. A recreate with `ACTION_SEND` still opens once; `SHARE_CONSUMED` blocks the next one. Parent read hostile XML 12/0 at `2026-10-14T04:30:29.201Z` and `ProcessDeathTest` XML 2/0 at `2026-10-14T04:19:14.311Z`. Daemon logs: `BUILD SUCCESSFUL` in 49s and 24s; those two XML files were overwritten. Sequence 4338. Verification is null. Not requested done. |

Parent did not re-run Gradle for these. Agent-reported counts, unverified here: daylight `DaylightStatusWrapTest` 14/0 and `AstroGoldenTest` 14/0; civil time JVM `time.*` 24/0 and macosArm64 `time.*` 22/0; room core track 34/0 and app 70/0; pressure 19/0; NMEA `NmeaParserTest` 40/0; GPX/KML `GeoFormatsTest` 70, `GeoSegmentsTest` 9, `GeoFormatsHostileXmlTest` 3, `GeoFormatsStreamingJvmTest` 3; GATT `AndroidBleTransportTest` 23; recording `RecordingSessionTest` 19, `TrackRecorderTest` 15, `TrackRecorderSegmentsTest` 6, `TrackRecordingServiceTest` 22; Morse `MorseTest` 4, `MorseTurkishLocaleTest` 1, `MorseAsciiFoldHostileTest` 8, `StressTest.morseHandlesAnyText` 1; plain-byte `PlainByteCapTest` 9 and `PlainByteCapAttackTest` 15, plus the implementer's tile tests (`TileUtilsTest` 10, `TileSpecRegressionTest` 11, `PmtilesArchiveTest` 13, `MvtParserTest` 13). `:core:compileAndroidMain` was reported successful for the cap. The Android actual was not executed. ios targets were not run. The adversarial commit file lists for room and pressure were not parent-listed: objects are packed, and `git --git-dir` against a worktree is blocked. NMEA's test commit was parent-listed: only `NmeaParserTest.kt`.

## In progress

This session has no card in progress. Left untouched because another lane already holds them: accessibility `ft-e1a43cbcd1879d34d565d1b7afb449bf`, release readiness `ft-09d169d802fed1a2b33680c7c3a14f5f`.

## Deferred

- Wave 3 offline map camera `ft-c9c215ae8d200e26b75d03c2ef082f03`. The room worktree still holds `OfflineMapCanvas.kt`. UI state is already in review and was not restarted.
- Wave 3 shared place was deferred until the accessibility planned files were read. They are `Gestures.kt`, `Components.kt`, `Screen.kt`, `TrailNav.kt`, `StargazeScreen.kt`, `ToolsScreen.kt`, and `app/src/test/.../ui/`. `MainActivity.kt` is not one of them. The shared-place card is in review. `TrailNav.kt` was not edited.
- Wave 4 DEM probe spacing `ft-55a2fc3489b2064f8b0a23ac7cebfc40` is already in review. Spacing, not a speed regression. Not restarted.
- Elevation fail-closed `ft-7c5a0a8eb1b9e81fcb811a38ac56000a` stays backlog. The room worktree holds `LocationRepository.kt` and `TrackDetail.kt`.
- Refactor `ft-306d4c7f23966650ba61d937a3a620d7` while accessibility is in progress.
- GEO-3 KML `gx:Track` export. `KmlWriter` still writes a `LineString`.

## Benchmarks

No benchmark was run. Written justification for the paths already reviewed: daylight status is one comparison per minute; civil time and the pressure slope are not profiled hot paths; the room change is UI and data, not a timed kernel. Morse runs once per SOS press. The plain-byte cap adds one integer compare; the gzip inflate loop was not changed. There is no harness for either function. A regression number over 5% does not exist because there is no before/after sample. That is a gap in the Definition of Done, not a measured regression.

## Verification gaps

- `dev verify` does not block. `gates.mode` is off.
- Full suite was not run. iosSimulatorArm64 and iosX64 were not run this session.
- GitPulse Insights sees one worktree (main) and one idle Claude Desktop session. Collision count 0 is unknown for the Grok worktrees, which are not registered git worktrees.
- DevMap analyzer payload was stale at generation 427. Impact walks for NMEA, pressure, recording, and BLE were `walk_incomplete`. BLE was also truncated (458 total, 160 shown). The saved envelopes for those two had no `repository.root`. Gap `tb-wave2-nmea-pressure` and gap `tb-wave2-recording-ble` are recorded.
- Wave 3 share: `devmap_affected_tests` for `takeShare` and `onNewIntent` fail-closed because the analyzer payload is stale (generation 432). Both targets were unmatched. Impact listed only `onCreate` and `onNewIntent`, with no `repository.root`, and the walk was partial. Gap `tb-wave3-share` is recorded. Empty tests are unknown.
- Wave 4 `devmap_affected_tests` for `Morse.code` and both `decompressGzip` actuals: `repository.root` matched this checkout. The walk stopped at depth 3, the blast layer list was truncated (one layer hidden, 109 impacted), and analysis is partial (8 ERROR/MISSING files). Shown tests are a lower bound: `MorseTest`, `StressTest`, `MiscTest`, `GzipUnsupportedTest`, plus tile and SOS tests reached through `TileUtils.inflate` and the SOS screen. Empty extra callers are unknown. Gaps `tb-wave4-morse` and `tb-wave4-gzip` are recorded. The JVM `decompressGzip` impact earlier returned zero callers under that partial analysis; that is unknown, not "no callers". The Android walk reached `OfflineMapCanvas.kt` through `TileUtils.inflate`. The planned gzip files do not include that canvas.

## New task

`tb-elevation-fail-closed`, board item `ft-7c5a0a8eb1b9e81fcb811a38ac56000a`, backlog, revision 1. `toSeaLevel` catches every `Exception` at `LocationRepository.kt:239` and returns the original fix. Track-detail bins add a non-finite height at `TrackDetail.kt:110`. Read on main. Not folded into the gzip-cap or GATT cards. Not started.

`tb-gzip-negative-cap`, board item `ft-7b2ee847bb419bcbbc6cf44e66e255f6`, backlog, revision 1. A gzip member with a negative cap throws `IllegalArgumentException` from `ByteArrayOutputStream` before inflate. Separate from the plain-byte card, which is in review. Not started, because both cards write the same two `PlatformIo.kt` files.

## Fable decisions

- Models cannot be pinned here. The parent writes the brief and the review. Implementers are `code-editor` subagents, at most four at once.
- Wave 1's four cards ran together: planned files were disjoint, and the only scanned worktree had no overlapping dirty files.
- TRK-1 stays deferred. The room card may sit in review with that criterion still open.
- GEO-3 stays deferred. Do not invent times for a `LineString`.
- TRK-2 does not change the height policy. Finite MSL is exported, including a negative value. Ellipsoid, unknown, and non-finite values are omitted.
- TRK-3: no LNS elevation-source value maps to MSL or ellipsoid. When elevation is present the datum is `Unspecified`.
- Past the daylight window, status is `AfterSunset`, not a negative `DaylightLeft`.
- Parser defects are in scope and were fixed in `16b1b22` without amending `fd85b5`. The third attack, `17b87bc`, is tests only. The card is in review. GEO-3 stays deferred.
- Pressure: filter to finite elevations before any recursive call. One shorter recursive call is allowed. Do not catch `StackOverflowError`.
- NMEA: modular distance behind the reference, 30 seconds, not a wider window. Same timestamp still joins an open epoch. The closed window applies while a later epoch is open. A 30_001 ms jump is outside the window. Card is in review, not done.
- Apple civil time does not call a private calendar cutover. Integer day count plus `NSTimeZone`.
- Do not merge to main. Do not rebuild the DevMap index.
- Recording destroy joins the flush for 5 seconds, then returns and logs that the tail was not confirmed. It does not cancel the flush and does not claim the tail is durable. An unbounded join was rejected because `onDestroy` runs on the main thread. This is an accepted deviation from the literal "does not return while flush is running".
- Wave 4 Morse and the plain-byte cap started together. Planned files do not overlap. Camera, shared place, and the geoid card stay deferred. DevMap impact for those two symbols is unknown (walks stopped early; the Android gzip walk was truncated; the JVM caller list was empty under partial analysis).
- Morse: ship the ASCII fold. On Kotlin 2.3.20, `String.uppercase()` with no locale argument uses the invariant locale (kotlinlang, contract since 1.5), so a Turkish default locale does not drop `i` in `code("missing")`. The fold's real change is that U+0131 no longer uppercases to ASCII `I`. `code("SOS")` stays. Do not send the card back for the stale locale theory.
- A negative cap on a real gzip member throws `IllegalArgumentException` from `ByteArrayOutputStream` before inflate. That pre-existed the plain-byte check. It is not a send-back of AST-5. It is a separate backlog card. Do not start it while `tb/plain-byte-cap` is unmerged.
- Next card is the shared place, not the camera. The room worktree `tb/room-datum-segment-ui` still differs from main on `OfflineMapCanvas.kt`, `LocationRepository.kt`, and `TrackDetail.kt`. `MainActivity.kt` matches main in that worktree and in the other Grok worktrees that contain the file. GitPulse still scans one worktree. Accessibility stays in progress and is not edited.

## Owner decisions still pending

1. TRK-1 pause-gap persistence. Prefer a v3 migration. Do not rewrite `2.json`. Whether any installed database has opened v2 is unverified.
2. Heights below API 34. Keep omitting non-MSL `<ele>` until a geoid or an explicit non-MSL extension is chosen.
3. KML export. `gx:Track` versus `LineString` grouping (GEO-3).
4. `applicationId` `com.trailblazer.vbcr` stays on the in-progress release card. Not touched.
5. `GEMINI_API_KEY` in `.env.example` stays held. `.env*` was not edited.
