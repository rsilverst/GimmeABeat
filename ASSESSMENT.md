# GimmeABeat — Cross-Functional Assessment Report

## Executive Summary

Four independent expert reviews (UX Designer, UX Researcher, Product Manager, Tech Lead) converge on a consistent story: **GimmeABeat is a well-architected MVP with a genuinely novel core mechanic, undermined by a thin layer of resilience, observability, and user-facing context.** All four hats praise the clean separation of concerns (relays → `AutoModeService` → UI) and the robust Spotify App Remote integration. The sharpest cross-cutting theme is **silent failure**: the Tech Lead sees it as swallowed Wearable Data Layer errors and untested retry logic; the PM sees it as missing telemetry on core-loop failures and undocumented Spotify Premium / getsongbpm dependencies; the UX Designer sees it as absent loading/error states; the UX Researcher sees it as unvalidated assumptions the team can't currently measure. The hats agree most strongly on three points: **(1) the app needs instrumentation** (telemetry/metrics) before any optimization is meaningful, **(2) watch↔phone connectivity loss and sensor dropout are handled silently and must surface to the user**, and **(3) the watch ambient-mode experience defeats the core "glance at BPM" affordance.** The main tension is on prioritization: the PM treats the **Spotify Premium requirement** as the dominant business risk (blocks ~70% of users), and the UXR argues nothing should be optimized until **latency/library/event telemetry** exists to validate the five core assumptions. These are complementary, not contradictory — they form a natural P0→P1 sequence.

## What the App Does

GimmeABeat is a Wear OS + phone companion app that matches Spotify track tempo (BPM) to a runner's live workout signal. A Wear OS watch streams heart rate or step cadence via Health Services to the phone, where a 5-second auto-mode polling loop (`AutoModeService`) smooths the signal over a 10-second window, looks up tempo-matched songs (getsongbpm.com via `GetSongBpmClient` + `SongFinder`), searches Spotify, and plays the match through the Spotify App Remote (with a Web API fallback). Users can choose their signal source (HR vs. cadence), tune a 0.5–2.0× BPM multiplier, and filter by genre.

## Strengths

- **Clean, decoupled architecture** (all four hats). Sensor → relay → auto-loop → UI separation via singletons; `HeartRateRelay`, `CadenceRelay`, `AutoModeService`, and `SongFinder` are independently swappable. ViewModel-driven UI with no business-logic leakage.
- **Robust Spotify integration** (UXR, PM, TL). Prefers App Remote local IPC (`LocalSpotifyController`) to avoid device-routing complications, with smart device targeting by name/model and graceful Web API fallback (`AutoModeService.kt:264–270`).
- **Thoughtful signal handling** (UXD, UXR). 10-second rolling-average smoothing in `HeartRateRelay`/`CadenceRelay` damps spike noise; dual signal-source support (`SignalSource.kt`) reflects real product insight that interval runners may prefer cadence over HR; 0.5–2.0× multiplier gives users calibration control (`SettingsScreen.kt:163–169`).
- **Solid system-constraint handling** (UXD, UXR, TL). Foreground services + notifications correctly configured for Android 14+ (`ServiceInfo` type); watch uses Health Services `ExerciseClient` with a wake-lock and `OngoingActivity` to dodge Samsung ambient-mode throttling; `SupervisorJob` scopes prevent cancellation propagation.
- **Resilient API plumbing** (TL). Exponential backoff (800ms → 6s) in `SpotifyClient` for transient/429 errors; `GetSongBpmClient` caches per-BPM lookups.
- **Clear core-mechanic UX** (UXD). Strong information hierarchy — 128sp signal hero, now-playing card, anchored 72dp auto button; three distinct auto-mode states (unauthorized / authorized / running) with contextual status messaging; first-run permission and battery-optimization flows implemented up front.
- **Intelligent error classification** (PM, UXR). Platform-vs-transient error distinction in the auto-loop; user-visible status strings keep failures from being fully silent.

## Opportunities

Organized by theme, de-duplicated, leading with the highest impact-to-effort bets.

### 1. Instrument the core loop before optimizing it — *high impact / low–medium effort*
**Hats: UXR, PM, TL.** This is the foundation everything else depends on. The core loop's failure modes (`FindResult.NoBpmCandidates`, `FindResult.NoSpotifyMatch`, watch disconnections, signal dropout) have **no counters** (PM). There is no Wearable Data Layer latency measurement (UXR), no `SongFinder` library-coverage logging (UXR), and no phone-side auto-loop timing/API-latency instrumentation — only the watch logs throughput every 30s via `ExerciseService.maybeLogTick` (TL). Without this, the team is debating song-change frequency, library dead zones, and battery drain blind.

### 2. Surface watch-connectivity loss and sensor dropout to the user — *high impact / medium effort*
**Hats: UXD, UXR, PM, TL — unanimous.** `WatchSync.sendSignalSource`, `HeartRatePublisher.publish`, and `AutoModeService.sendWatchCommand` all swallow node/message errors with `Log.w` and never retry or notify (TL). When HR is null the loop silently defaults to `DEFAULT_HR=80` indefinitely instead of escalating (PM, TL). The user gets no visual signal of staleness or disconnection (UXD). Fix: add bounded retry/backoff to Data Layer sends, count consecutive null signals and escalate to "Waiting for signal…" after ~15s, expose a watch-connected/disconnected badge + signal-freshness indicator on `HomeScreen`, and distinguish transient network failure from a truly unpaired watch.

### 3. Pre-flight device & dependency health checks — *high impact / medium effort*
**Hats: UXR, PM.** `AutoModeService` assumes Spotify is installed, Premium is active, and the watch is connected, detecting failures only post-hoc — a user can wait up to `IDLE_BACKOFF_MS` (~20s) before learning Spotify isn't available (UXR). Add checks in `MainViewModel.startAuto()` / `onStartCommand()` for: Spotify installed, auth token valid, active Spotify device present, watch connected via `Wearable.getNodeClient()`, and show blockers on `HomeScreen` before the user taps "Start auto."

### 4. Reduce cognitive load on signal-source & matching choices — *high impact / low effort*
**Hats: UXR (+ UXD on strings).** `SettingsScreen` help text explains the multiplier but never says **when** to pick HR vs. cadence; `SignalSource.kt` carries zero pedagogical context, so new users guess, get bad matches, and churn (UXR). Pairs naturally with surfacing genre/BPM library coverage gaps transparently so a user picking "classical 200+ BPM" understands why they get zero candidates.

### 5. Accessibility & localization gaps — *high impact / low–medium effort*
**Hats: UXD.** `strings.xml` holds only `app_name`; UI strings are hardcoded across `HomeScreen.kt`, `SettingsScreen.kt`, `HeartRateScreen.kt`, and `AutoModeService` status messages — blocking all non-English use. Only 3 `contentDescription`s exist in the whole codebase; the 128sp signal hero, album art, and play/pause icon are unlabeled for screen readers. `Type.kt` defines only `bodyLarge`, with hero/title/label sizes hardcoded and no dynamic-type scaling.

### 6. De-amplify frequent song changes with phase awareness — *high impact / medium–high effort*
**Hats: UXR, PM.** The 5-second poll triggers song swaps on effort micro-spikes, but real workouts have phases (warmup → steady → sprint → cool-down), risking song fatigue and skip churn (UXR). PM frames the same insight as predictive workout-phase staging. Validate with an A/B test (5s vs. 60s polling) before committing.

### 7. Spotify Premium dependency & business risk — *high impact / high effort*
**Hats: PM (+ TL on auditing the fallback path).** Playback ultimately requires Premium, which PM estimates blocks ~70% of users. First step is to **quantify and document** the dependency (does any free-plan path exist?) before scoping free-plan support.

### 8. Learning / personalization feedback loop — *high impact / high effort*
**Hats: UXR.** `SongFinder.findCandidate()` shuffles candidates but never ranks by user history, Spotify saves, or skip patterns; the 50-track dedup (`RECENT_LIMIT`) is blunt, so users hear the same niche tracks repeatedly with no learning.

### 9. Watch ambient-mode glanceability — *medium impact / medium–high effort*
**Hats: UXD, UXR.** The watch keeps the screen on (`FLAG_KEEP_SCREEN_ON`) to avoid throttling but has no ambient/low-power display variant; dimming defeats the "glance at BPM" affordance. Add an ambient `AmbientHeartRateScreen` (large number only) and/or a raise-to-wake trigger on significant HR change.

### 10. State-lifecycle & resilience hardening — *medium impact / medium effort*
**Hats: TL.** Global singletons (`AutoModeState`, `HeartRateRelay`, `WatchTrackingState`) have no lifecycle binding; if `AutoModeService` is killed mid-play, state goes stale because `reset()` isn't guaranteed to fire. Tie reset to app lifecycle / add a >60s stale-status timeout. Also: unbounded `GetSongBpmClient` per-BPM cache growth, and untested `runLoop`/`pickAndPlay` retry logic.

### 11. Power-user diagnostics & mid-workout adjustment — *medium impact / low–medium effort*
**Hats: UXR.** `SongFinder` is a black box; power users can't see why a song was picked/rejected and suspect the app is broken. Add a dismissible debug-diagnostics card to `SettingsScreen` and a multiplier/genre quick-adjust overlay usable without stopping auto-mode.

## Action Items

Grouped by priority; duplicates merged across hats. The bracketed tag is the hat(s) that raised each item.

### P0 — instrument before optimizing

> **✅ Done** — All three P0 items shipped via a single shared telemetry surface
> (`telemetry/Telemetry.kt`): structured `key=value` logcat lines under tag
> `GABeatTelemetry` plus a bounded 500-event in-memory ring buffer (the basis for
> a future debug card / opt-in export). Watch live with
> `adb logcat | grep GABeatTelemetry`. Sink is local-only for now — no analytics
> backend, no new dependencies.

- **Wearable Data Layer latency instrumentation** `[UXR]` ✅ — `SignalArrivalMeter` wired into `HeartRateRelay.update()` and `CadenceRelay.update()` emits `signal_latency` with P50/P95/P99 + max **inter-arrival gaps** every 30s. *Deviation by design:* measures phone-side inter-arrival (one monotonic clock) rather than watch→phone latency — the watch/phone clocks are unsynchronized, so subtracting a watch timestamp yields meaningless values; inter-arrival gaps are what actually diagnose signal-lag → stale picks. Watch-side publish cadence is a possible follow-up.
- **`SongFinder` library-coverage logging** `[UXR]` ✅ — `findCandidate()` emits `song_finder` with `targetBpm`, `genre`, `tolerance`, `candidates` count, `outcome` (`found` / `no_bpm_candidates` / `no_spotify_match`), and `trackId`. Logs `candidates` count + outcome rather than a total Spotify-match count (the search short-circuits on first hit; counting all matches would cost extra API calls) — still surfaces BPM/genre dead zones.
- **Auto-mode event telemetry** `[UXR]` ✅ — `pickAndPlay()` emits `auto_pick` per pick with `reason`, `source`, `rawSignal`, `signalPresent`, `signalAgeMs` (distinguishes a live match from one against a stale/absent signal — the `DEFAULT_HR=80` fallback), `multiplier`, `targetBpm`, `outcome`, `trackId`, and `playLatencyMs`.

### P1 — resilience, reach, and de-risking

- **Add telemetry / counters for core failures** `[PM, TL]` ✅ — `telemetry/Counters.kt` tallies find outcomes (`find_found` / `find_no_bpm_candidates` / `find_no_spotify_match`), play outcomes (`play_*`), `signal_absent` (pick fell back to `DEFAULT_HR`), and watch dropouts (`watch_unreachable` / `watch_send_failed`). Reset per auto-mode session; emitted through the shared `Telemetry` surface as a `counters` snapshot every 60s (`rolling`) and once at stop (`session`). Remaining sub-thread: an opt-in export / debug card reading the ring buffer (tracked under the P2 "Debug/diagnostics card").
- **Add retry + user-facing feedback to Wearable Data Layer sends** `[TL, UXD, PM]` — `WatchSync.sendSignalSource`, `HeartRatePublisher.publish`, `AutoModeService.sendWatchCommand` fail silently. Add exponential backoff (match `SpotifyClient`), max 2–3 retries; surface a "Watch disconnected" state to UI.
- **Handle sensor loss gracefully in the auto-loop** `[TL, PM]` — In `pickAndPlay` (`:184–193`), count consecutive null HR; after 3+ (~15s) escalate to "Waiting for signal…" and back off to `IDLE_BACKOFF_MS`; if watch is persistently unpaired, notify and stop auto-mode. Stop silently defaulting to `DEFAULT_HR=80`.
- **Expose watch pairing/connection status on HomeScreen** `[PM, UXD, TL]` — Add a connected/disconnected badge + signal-freshness indicator and a "Retry Sync" button.
- **Quantify & document the Spotify Premium dependency** `[PM, TL]` — Audit whether any free-plan playback path exists through the Web API fallback; document as a hard blocker if not, before scoping free-plan support.
- **Validate getsongbpm.com compliance** `[PM]` — `GetSongBpmClient` lacks rate-limit backoff, quota validation, and in-app attribution verification. Audit ToS; add defensive handling.
- **Bound `GetSongBpmClient` cache + fix tolerance staleness** `[TL]` — `findCandidates` (`:41–66`) caches per-BPM unbounded; add LRU/cap (~500). Re-evaluate hardcoded 5-BPM tolerance when the multiplier changes `targetBpm`.
- **Add lifecycle cleanup safeguards to `AutoModeState`** `[TL, PM]` — Tie `reset()` to app `onStop()` / `ProcessLifecycleObserver`; add a >60s stale-status timeout so a crashed service doesn't strand the UI.
- **Extract all hardcoded strings to `strings.xml`** `[UXD]` — Localize text across `HomeScreen.kt`, `SettingsScreen.kt`, `HeartRateScreen.kt`, and `AutoModeService` status messages with keys; wrap each `Text()` in `stringResource`.
- **Add contentDescriptions / semantics to all interactive elements** `[UXD]` — Label the 128sp signal hero, album art, play/pause icon, and all wear buttons; add `Modifier.semantics` blocks per the Material 3 accessibility guide.
- **Define & apply a full Material 3 typography scale** `[UXD]` — Expand `Type.kt` (displayLarge…labelSmall); replace hardcoded `fontSize` in `HomeScreen.kt`/`HeartRateScreen.kt` with typography tokens; support dynamic type.
- **Add loading-state UI + error messaging** `[UXD, UXR]` — Introduce an `AutoModeUiState` sealed class (Idle / LoadingAuth / LoadingSpotify / Playing / Error*) and show spinners/badges during auth, song-finding, and connectivity loss.
- **Signal-source mental-model study** `[UXR]` — Moderated walkthrough with 5 non-tech exercisers picking HR vs. cadence unaided; use findings to rewrite `SettingsScreen` help text.
- **A/B test song-change frequency** `[UXR]` — Compare 5s vs. 60s polling across real users; track skip rate, session duration, satisfaction before changing cadence.
- **Real-device connectivity & cadence audits** `[UXR]` — Lab-test Data Layer reliability/latency and `STEPS_PER_MINUTE` accuracy across Galaxy Watch 7 / Pixel Watch 2; map genre×BPM library coverage; interview power users on mid-workout adjustment needs.

### P2 — polish, hardening, and nice-to-haves

- **Implement pre-flight device health check** `[UXR, PM]` — Before "Start auto," verify Spotify installed, token valid, active device present, watch connected; show blockers up front.
- **Standardize status-message vocabulary + add action hints** `[UXD, UXR]` — Consistent tense/tone across `AutoModeService` and `ExerciseService`; e.g. "Heart rate sensor unavailable" not "Sensor: UNAVAILABLE", with user-actionable hints.
- **Signal quality / staleness indicator** `[UXD, UXR]` — Track last-update timestamp in relays; expose an `isStale` flow; show green/yellow/red freshness dot on phone and a "signal lost" cue on watch.
- **Watch ambient-mode display variant** `[UXD, UXR]` — Add `AmbientHeartRateScreen` (large number, black bg) and/or raise-to-wake on significant HR change; test in outdoor light.
- **Mid-workout multiplier/genre quick-adjust overlay** `[UXR]` — Floating panel adjustable without stopping `AutoModeService`; test on phone + watch.
- **Debug/diagnostics card** `[UXR, PM]` — Surface target BPM, signal source, multiplier, smoothed value, and why the last song was picked; dismissible toggle.
- **Integration tests for the auto-mode loop** `[TL]` — `androidTest` with mock Spotify/GetSongBpm clients covering happy path, transient-retry, null-sensor, track-end → next pick, external-playback sync.
- **Clearer 429 / rate-limit feedback** `[TL]` — Add an explicit "Rate-limited; trying again" status case distinct from "API down" in `SpotifyClient.retrying` → `pickAndPlay`.
- **Validate & document Data Layer keystore assumption** `[TL]` — Phone/watch need matching debug keystore signatures; add a runtime check logging a clear error if `connectedNodes` is empty after 10s; document in README.
- **Cache Spotify device list + null-safety in `pickTargetDevice`** `[TL]` — `pickTargetDevice` (`:116–125`) can return null on empty/null-ID device lists; cache the device list with ~30s TTL instead of refetching every poll.
- **Clarify / gate manual BPM mode & test track** `[PM]` — `MainViewModel.findAndPlayMatchingSong` is a one-shot test not wired into the auto-loop; the hardcoded `TEST_TRACK_URI` fails silently if delisted. Document scope or hide behind a dev flag.
- **Harden client-side API keys (corrected — see note)** `[TL]` — **Original P0 "keys checked into version control" was a false positive.** `local.properties` is *not* tracked (`git log --all` empty) and is already in `.gitignore`; keys flow the recommended way via `buildConfigField` (`app/build.gradle.kts:30–31`). The Spotify client ID is public by design (PKCE). The only residual exposure: the `getSongBpmApiKey` is compiled into `BuildConfig` and thus extractable from a release APK — unavoidable for a client-only app. Real fix is a backend proxy; until then, no action needed.

## Top 5 — If You Only Do Five Things

1. **Add a smoke-test + CI gate and a real test suite** (P1, TL). Only stub tests exist; the resilience and edge-case work below has no safety net. (Note: the original #1 — a "P0 secrets leak" — was a false positive; `local.properties` is correctly gitignored and untracked, so it has been removed.)
2. **✅ Instrument the core loop** — Data Layer latency, `SongFinder` coverage, and auto-mode event telemetry (P0, UXR + PM/TL counters). *Done* — shipped via a shared `Telemetry` surface (see P0 section). Every optimization debate (song frequency, dead zones, battery) was blind; this unblocks them. P1 failure counters now done too; remaining sub-thread is an opt-in export / debug card built on the ring buffer.
3. **Stop failing silently on watch/sensor loss** — add retry+backoff to Data Layer sends, escalate after consecutive null signals instead of defaulting to HR 80, and surface a connection/freshness indicator on `HomeScreen` (P1, all four hats).
4. **Quantify and document the Spotify Premium dependency** (P1, PM/TL). It potentially gates the majority of the addressable market; the team needs a clear answer before any growth work.
5. **Fix accessibility & localization basics** — extract strings to `strings.xml` and add contentDescriptions/typography tokens (P1, UXD). Low effort, high reach, and a prerequisite for any audience beyond English-speaking sighted power users.

## Appendix — Raw Per-Hat Summaries

### UXD — UX Designer
GimmeABeat demonstrates solid interaction design for a specialized fitness app with good signal representation and auto-mode clarity, but has critical gaps in accessibility, localization, and watch-phone coherence. The giant signal hero and clear play/stop button work well for the core mechanic, but hardcoded UI strings throughout both phone and wear apps, minimal content descriptions, under-specified typography scaling, and missing loading/error state feedback for watch connectivity and API failures significantly limit usability for diverse users and locales.

### UXR — UX Researcher
GimmeABeat is a technically sound but assumption-heavy product that auto-selects and plays Spotify tracks based on live workout signals (heart rate or step cadence from a Wear OS watch). The core mechanic—real-time BPM matching via a 5-second polling loop—is novel, but the product makes five critical unvalidated assumptions: that users understand BPM matching without onboarding, that 10-second signal smoothing prevents disruptive song changes, that signal source choice (HR vs. cadence) is self-evident, that genre+BPM filtering finds music users actually like (no learning feedback loop), and that Spotify-Premium-only playback with frequent song switching is acceptable. The riskiest friction points are in real-world context: watch screen dimming defeats the "glance at BPM" affordance, Wearable Data Layer latency is unmeasured, song availability is likely sparse in some BPM/genre zones, and there's no mid-workout adjustment UI despite users likely needing multiplier/genre tweaks mid-session.

### PM — Product Manager
GimmeABeat v1.0 is a functional MVP executing core loop: read HR/cadence from Wear OS watch, look up matching BPM songs, search Spotify, play via App Remote. Architecture is solid but three critical dependencies lack mitigation: Spotify Premium (required, no fallback), getsongbpm.com API (rate limits and ToS unvalidated), watch pairing (silent failures). Core loop failures (missing BPM data, song not on Spotify, signal dropout) have no telemetry.

### TL — Tech Lead
GimmeABeat is a well-structured Android/Wear companion app with solid architecture in most areas, but has critical gaps in error handling resilience, testing, secrets management, and several reliability edge cases in the auto-mode loop and inter-device communication. The app correctly uses Kotlin coroutines, separation of concerns via singletons, and AppAuth/OAuth, but lacks defensive retry logic for sensor/network failures. *(Correction: this hat's original claim that secrets were "checked into version control" was a false positive — `local.properties` is untracked and gitignored, and keys are wired through `BuildConfig` per the standard pattern. The only residual item is the inherent APK-extraction exposure of client-side keys, now tracked as P2.)*