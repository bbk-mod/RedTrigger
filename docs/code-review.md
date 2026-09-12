# RedTrigger Code Review — Findings

Scope: full `app/src` (Kotlin, Compose, AIDL, manifest), `native/redtrigger_uinput.c`, build config, and CI workflows.
Revision reviewed: `6c31ace`.
Context: **personal fork** — one device (Red Magic 11 Pro), one user, no distribution, no Play Store. Priority below is filtered to what actually shows up in day-to-day use; hardening that only matters for a distributed build is collected in its own section at the end.
Method: static review by two independent reviewers (general Android correctness; security audit of the privileged surface), followed by a verification pass in which every cited file was re-read and every line number re-checked. Where the passes disagree, the correction is recorded under "Corrections" and the original claim is preserved.

## Verification legend

| Tag | Meaning |
| --- | --- |
| `VERIFIED` | Confirmed by reading the cited code directly. Line numbers checked. |
| `CORRECTED` | The original finding was re-checked and the claim changed. See the correction note. |
| `REASONED` | Confirmed from code semantics, but the runtime consequence depends on platform/Shizuku behaviour not testable in this environment. |
| `OPEN` | Neither pass could confirm the platform behaviour. Treat as a question, not a finding. |

No build or device test was possible: Gradle dependency resolution needs network, the NDK toolchain path is hardcoded to `linux-x86_64`, and `assembleDebug` unconditionally depends on `buildNativeBinary`.

## Priority for day-to-day use

1. **N1 — nothing reconnects the reader after Shizuku starts or restarts.** The most likely cause of "triggers stopped working again". See N1.
2. **C2 — every connect blocks the main thread.** `pkill` + 300 ms sleep + unzip + READY poll + two `detectDevices()` runs, all inside `onServiceConnected`. See C2.
3. **S8 — injection dies permanently after one failed write.** Remap silently stops working until the user toggles the switch off and on. See S8.
4. **C3 — `ContentObserver` re-registration leak.** Real, but needs a second `enableTriggers()` while the service is already running. See C3.
5. **S1 — `debuggable(true)` on the shell-uid user service.** One-line fix, worth taking even on a personal build. See S1.

---

## A. Corrections

Findings from the original review that did not survive verification, or that were scoped wrongly.

### C1 — `STARTING` is never reset when Shizuku is not running — **CORRECTED: not reachable**

Original claim: `VERIFIED` — `InputReader.kt:134-142`. The failed-ping branch sets `state = State.STARTING` and returns without resetting, so a later sticky-listener callback hits the "Already starting" guard and wedges the reader forever. Described as the primary post-reboot flow.

Correction: the branch is **dead code**. `InputReader.start()` has exactly one caller — `TriggerService.startInputReader()` at `TriggerService.kt:86` — and it is only reached inside `if (Shizuku.pingBinder())` (`TriggerService.kt:75`). Nothing ever calls `start()` while the binder is down, so the `!ping` path never executes and the described wedge cannot occur.

The underlying one-line defect is still real (`state` is not reset before the `return` at `:142`, while the permission branch at `:150-152` does reset it), but it is latent, not P0. Fix it while in the file.

### C2 — Unconditional ~3 s stall on every connect — **CORRECTED: the stall does not happen**

Original claim: `repeat(30) { if (uinputReady) return@repeat; Thread.sleep(100) }` "always burns the full 30 × 100 ms ≈ 3.0 s even when READY arrives early", with the `if (uinputReady)` check called dead code.

Correction: `return@repeat` exits the current lambda invocation for `repeat`, which is equivalent to `continue` — it skips the remaining body of that iteration, i.e. skips the `Thread.sleep(100)`. Once `uinputReady` flips true, every subsequent iteration returns without sleeping. Measured against the native binary, which prints `READY` after a fixed 200 ms (`native/redtrigger_uinput.c`, `usleep(200000)`), the poll costs ~200–300 ms, **not** 2.8 s. The check is not dead code.

The real defect is elsewhere and is confirmed: the entire connect chain runs **synchronously on the main thread** inside `onServiceConnected` (`InputReader.kt:63-86`) — `startReading` → `startUinputInjector` (`pkill` + `Thread.sleep(300)` + unzip + READY poll) → `detectDevices()` → reader spawns. Enabling triggers therefore freezes the UI. The `for`/`break` rewrite is a readability improvement, not a performance fix.

### C4 — `InputReader.stop()` leaves Shizuku listeners registered — **CORRECTED: reachability narrower**

`stop()` does not call `removeBinderReceivedListener` / `removeRequestPermissionResultListener` — `VERIFIED` (the only remove calls in the file are at `:145` and `:156`, both inside `start()`). But the consequence requires `start()` to take one of the listener-registering paths, and as with C1 the failed-ping path is unreachable from the only caller. The permission path is also hard to reach: `TriggerService` calls `start()` whenever `pingBinder()` is true without checking `checkSelfPermission()` (it only logs it, `:78-79`), but the UI switch and QS tile both gate on permission, so the normal flow cannot enter it.

Latent, not P0. Still the right fix: register the sticky listener once in `init()` and remove both in `stop()`.

### C6 / C7 / S2 / S3 — confirmed absent, unreachable in practice

- **C6** is `REPORTED` in the original and the main-thread consequence is real (`AndroidUiDispatcher.Main`), but it is behind the "Toggle & Diff ALL Settings" button on the debug screen. Not day-to-day.
- **C7** (`com.redtrigger` hardcoded in `InputReader.kt:43`, `InputService.kt:156`, `:332`) only bites if `applicationId` stops being `com.redtrigger`. There are no flavors and no suffix; it is `com.redtrigger` everywhere. Not worth touching for a personal build.
- **S2 / S3** (no `Binder.getCallingUid()` check; `grantPermission(String)` as a generic grant primitive) are `VERIFIED` as absent, and correctly assessed as not exploitable today: `InputService` is not a manifest `<service>`, the binder is never written to an Intent/Parcel/Provider, and the grant target package is hardcoded. Only relevant if Shizuku's own validation is relaxed.

### C3 — `ContentObserver` re-registration leak — **VERIFIED: frequency overstated**

The leak is real: `onStartCommand` calls `startObserving()` unconditionally (`TriggerService.kt:54`), which assigns **new** observer instances to `sceneObserver` / `modeObserver` (`:100`, `:119`) and registers them, so the previous instances become unreachable and `stopObserving()` (`:136`) can never unregister them.

But the original's trigger list is too broad. `MainActivity` only starts the service when `!TriggerService.isRunning` (and `MainScreen`'s recovery effect has the same guard), so ordinary app opens do not re-register. The leak needs a second `enableTriggers()` — from the UI switch or QS tile — while the service is already running.

### R6 — `release.yml` secret interpolation — **CORRECTED: mis-attributed**

The `Create keystore.properties` step passes the password through `env: KS_PASS:` and expands `"$KS_PASS"` inside `run:` — that is the correct pattern. The step that genuinely interpolates a secret into shell text is `Decode keystore`, which uses `echo "${{ secrets.KEYSTORE_BASE64 }}" | base64 -d > redtrigger-release.jks`. Minor, and not a concern for a personal fork.

### R7 — "FGS start rejected at boot" — **CORRECTED: premise wrong for API 35**

For apps targeting API 35, the `BOOT_COMPLETED` restriction list is `camera`, `dataSync`, `mediaPlayback`, `mediaProjection`, `microphone`, `phoneCall`. `specialUse` is **not** on it, so `TriggerService`'s start from `BootReceiver` is not subject to that restriction. Reports of `specialUse` throwing `ForegroundServiceStartNotAllowedException` from a boot receiver are Android 16, not 15. The second half of R7 stands: any failure is written only to the in-app log while the UI still reads "on".

### S4 — broad key capture and clipboard export — **VERIFIED, but theoretical on this hardware**

The mechanism is confirmed: `InputService.kt:207-209` starts `getevent -ql` with no device filter when detection finds nothing; `parseGeteventLine` calls `onRawKeyEvent` for every `EV_KEY` line (`:272`) before filtering for F7/F8; `InputReader.kt:107-109` logs each one; `MainScreen.kt:589` copies the whole buffer to the clipboard.

But the fallback is only reached when the name filter (`nubia_tgk_aw_sar` / `sar0` / `sar1`) matches no device. On a Red Magic the filter matches, so only event4/5 are read and only F7/F8-shaped lines reach the log. Broad capture requires detection to fail — and detection failing is itself the more likely thing to notice.

---

## B. New findings from verification

### N1 — Nothing reconnects the reader after Shizuku starts or restarts

`VERIFIED` (by absence) — `TriggerService.kt:62-92`, `InputReader.kt:145`, `MainActivity.kt`, `MainScreen.kt:112-124`

Shizuku is not running at boot and needs a manual restart. `TriggerService` starts anyway (START_STICKY, post-boot), and `startInputReader()` does:

```kotlin
val shizukuAvailable = rikka.shizuku.Shizuku.pingBinder()   // 75
if (shizukuAvailable) {
    ...
    InputReader.start()                                      // 86
} else {
    DebugLog.log("Shizuku", "Not available, input reading disabled")
}                                                            // no retry, no listener
```

When Shizuku is down, `InputReader.start()` is never called, so no binder listener is ever registered — nothing observes Shizuku coming up. When the user later starts Shizuku, no component reacts:

- `InputReader` has no listener registered at that point.
- Opening the app does not help: `MainActivity` starts the service only if `shizukuOk && triggersEnabled && !TriggerService.isRunning`, and `MainScreen`'s `LaunchedEffect(shizukuOk, triggersEnabled)` has the same `!TriggerService.isRunning` guard. The service *is* running, so both are no-ops.

The bundle is also running the watchdog, so `nubia_game_scene=1` keeps being forced and the sensors stay on — the failure is silent on the capture/remap side only. Recovery today is to toggle triggers off and on.

Combined with C5 (below), this is the same bug at both ends of the Shizuku lifecycle: the reader connects exactly once, at service start, and never again.

### N2 — `detectDevices()` runs twice per connect

`VERIFIED` — `InputReader.kt:77`, `InputService.kt:204`

`onServiceConnected` calls `inputService?.detectDevices()` and then calls `startReading(...)`, whose first act is `val detected = detectDevices()` again. Each call spawns `getevent -pl`, drains both streams, and `waitFor()`s. Halving it is free.

---

## C. Verified, worth fixing

### C5 — No recovery from a failed bind or a Shizuku restart

`VERIFIED` — `InputReader.kt:63-95`, `:145`

The `ServiceConnection` overrides only `onServiceConnected` / `onServiceDisconnected`. `onNullBinding` and `onBindingDied` are absent, so a failed instantiation leaves `state = STARTING` with nothing to rescue it.

After a successful start, the sticky binder listener is removed at `:145` and re-added nowhere. When Shizuku restarts — a routine event on this setup — `onServiceDisconnected` sets `state = STOPPED` and nothing rebinds. Capture is dead while the watchdog keeps re-applying settings, and the only signal is the "Reader Active ✗" row.

**Fix (this and N1 together):** keep `Shizuku.addBinderReceivedListenerSticky` registered for the lifetime of `TriggerService`, add `Shizuku.addBinderDeadListener`, and override `onNullBinding` / `onBindingDied` to reset state and retry with backoff.

### S8 — Injector teardown is fragile against a transient write failure

`VERIFIED` — `InputService.kt:298-308`

`injectKey` checks `uinputWriter == null` then dereferences `uinputWriter!!` while binder threads can close and null it. The `catch (Exception)` sets `uinputReady = false`, which disables remapping until the user re-toggles. Confirmed that recovery is manual-only: `setInjectionEnabled(true)` restarts the injector only when `!uinputReady && reading`, but the UI switch is already `checked = true`, so it never fires — the user has to toggle OFF then ON. `activeCallback` is also written on a binder thread and read on reader threads without a memory barrier.

**Fix:** route injector operations through one lock or single-threaded executor; mark `activeCallback` `@Volatile`; don't set `uinputReady = false` on a transient failure.

### S1 — `debuggable(true)` on the shell-uid user service in release builds

`VERIFIED` — `InputReader.kt:47`

`debuggable(true)` is applied unconditionally, including in release builds, on the process holding uid 2000, `/dev/input`, `/dev/uinput`, and the ability to run `pm grant`.

Scope note: the Shizuku Javadoc documents `debuggable` only as "the process can be found when 'Show all processes' is enabled". The stronger claims about arbitrary debugger-driven code injection with no Shizuku-side gating are not verifiable from this repo (the AAR is resolved from Maven, not vendored) and should be treated as `REASONED`. Regardless, a debug flag should not ship.

**Fix:** `.apply { if (BuildConfig.DEBUG) debuggable(true) }`.

### C3 — `ContentObserver` leak

See the correction above for frequency. The fix is one line at the top of `startObserving()`: call `stopObserving()`, or guard with `if (sceneObserver != null) return`.

### C8 — Dead `capture_shizuku` preference read

`VERIFIED` — `TriggerService.kt:65`

The pref is read but nothing in the repo writes it. The "disabled" branch is unreachable; if it were ever `false` (restored backup, older build), the reader would be off with no UI to re-enable it short of clearing app data.

---

## D. Verified, not day-to-day

Real, but they do not change how the app behaves for a single user on a single device.

| # | Finding | Location |
| --- | --- | --- |
| C6 | `dumpAllSettings()` on the main thread — two unbounded `ContentResolver.query` sweeps. Behind the debug "Toggle & Diff ALL Settings" button only. | `MainScreen.kt:608-629` → `TriggerManager.kt:120-148` |
| C7 | Hardcoded `com.redtrigger` across the process boundary. No flavors exist; nothing changes `applicationId`. | `InputReader.kt:43`, `InputService.kt:156`, `:332` |
| S2 | `IInputService` binder has no caller authentication. Not reachable by an ordinary app today (not a manifest `<service>`; binder never leaked). | `InputService.kt:19`, `IInputService.aidl:5-23` |
| S3 | `grantPermission(String)` is an unchecked grant primitive. Contained by a hardcoded target package and argv-form `exec`. | `InputService.kt:~331`, `IInputService.aidl:19` |
| S5 | `BootReceiver` exported without a permission. `BOOT_COMPLETED` is a platform protected broadcast, so it cannot be forged. `exported="false"` would still be the better default. | `AndroidManifest.xml:60-67` |
| S6 | `allowBackup="true"` with no extraction rules. Nothing secret is stored. | `AndroidManifest.xml:34` |
| S7 | `sh -c` string built from `pm path` output. Single-quoted, PackageManager-controlled, not exploitable. | `InputService.kt:163-166` |
| R1 | 1 Hz polling loop does PackageManager lookups and binder pings on the main thread; `statusTick++` invalidates the screen. Real, imperceptible in practice. | `MainScreen.kt:86-129` |
| R3 | `proguard-rules.pro` referenced but **absent** (confirmed: file does not exist). Harmless while `isMinifyEnabled = false`; breaks the release build the moment R8 is enabled. | `app/build.gradle.kts:48-51` |
| R4 | NDK compiler path hardcoded to `linux-x86_64`. Valid on this host (`uname -m` = `x86_64`); breaks on macOS/ARM. | `app/build.gradle.kts:95-112` |
| R5 | ~15 unused `cn.nubia.*` / `com.zte.*` `<queries>` entries widen package visibility without being used. | `AndroidManifest.xml:13-30` |
| R6 | JitPack declared but unused; no `distributionSha256Sum`; `build.yml` has no `permissions:` block. | `settings.gradle.kts:11`, `gradle/wrapper/gradle-wrapper.properties`, `.github/workflows/*` |
| R8 | Compose: `currentScreen` uses `remember` (lost on rotation); status shown only as "✓"/"✗" with no `stateDescription`; a 28 dp `IconButton` touch target. | `MainScreen.kt:47`, `804`, `873`, `400-406` |
| R9 | Native binary built without `-fstack-protector-strong` / `-D_FORTIFY_SOURCE=2`; signal handler uses non-async-signal-safe `fprintf` and `signal()` rather than `sigaction()`. | `app/build.gradle.kts:99-113`, `native/redtrigger_uinput.c:14-23` |
| R10 | Helper extracted to `/data/local/tmp` with mode `0755` and not deleted on shutdown. | `InputService.kt:23`, `:163` |

The reviewers found **no** memory-safety defect in the native binary: no `argv` parsing, no `system`/`popen`/`strcpy`/`sprintf`, bounded `fgets` on both buffers, fixed-size `input_event` writes. That matches the source I read.

### R2 — OPEN

`POST_NOTIFICATIONS` is declared (`AndroidManifest.xml:9`) but never requested at runtime, so the FGS notification may be suppressed on API 33+. The effect on a *foreground service* notification specifically (as opposed to an ordinary notification) was not confirmed in either pass — do not assume it is suppressed, and do not assume it is exempt. Verify on the device before changing anything.

---

## E. Documentation drift

### D1 — `CLAUDE.md` documents two capture methods that do not exist

`VERIFIED` — `CLAUDE.md:15-16`, `:40-41`; `AndroidManifest.xml`; `app/src/main/res/values/strings.xml:3`

`CLAUDE.md` describes `TriggerInputMethod` (an IME) and `TriggerAccessibilityService`, along with `capture_ime` / `capture_a11y` preferences and AIDL `setKeyMapping` / `setGrab` methods. None of these exist:

- The manifest declares only `MainActivity`, `BootReceiver`, `TriggerService`, and `TriggerTileService` — no IME or accessibility service.
- No `BIND_INPUT_METHOD` or `BIND_ACCESSIBILITY_SERVICE` permission is declared.
- The full source listing under `app/src` contains no `InputMethodService` or `AccessibilityService` subclass.
- `capture_ime` / `capture_a11y` are read nowhere; `capture_shizuku` is read but never written (C8).
- `strings.xml` still carries an orphaned `accessibility_service_description`.

`README.md`'s architecture description is accurate; `CLAUDE.md` is not. This matters beyond tidiness — it is the file an agent or contributor reads first, so it hands them a false model of which capture paths exist and need maintaining.

**Fix:** correct the `CLAUDE.md` sections to match the code, delete the orphaned string, and decide whether the IME/accessibility paths are planned work or abandoned.

### D2 — Build and version notes

`VERIFIED` — `CLAUDE.md` documents `just ship` copying to `/mnt/storage/tmp`, while `justfile` defaults `storage := env("REDTRIGGER_OUTPUT", "/tmp")`.

---

## F. Notes toward the goal: triggers outside Game Space

Goal: have the triggers drive arbitrary apps/actions outside Game Space.

What already holds: nothing in the capture path is gated on Game Space. `nubia_game_scene=1` applied by the watchdog is what keeps the SAR sensors alive system-wide, and that mechanism does not depend on game mode. The trigger pipeline is:

`getevent` on event4/5 → `parseGeteventLine` → `ITriggerCallback.onTriggerEvent` → `InputReader.onTrigger` → currently just a log line in `TriggerService.kt:80-84` → `injectKey` → virtual L1/R1 gamepad via `uinput`

So `InputReader.onTrigger` is the hook for anything else you want to drive, and `InputService.injectKey` / `uinput` is the path for making the event visible to other apps as a standard gamepad button (which is what makes KeyMapper-style remapping work).

What blocks it: the reader connects exactly once, at service start. If Shizuku was not up at that moment, or dies later, remapping is silently dead while the watchdog keeps the sensors alive — so anything built on `onTrigger` inherits that unreliability. N1 + C5 are the prerequisite, not an optional cleanup.

---

## G. Confirmed sound

Worth preserving while fixing the above.

- **UserService teardown is correct.** `unbindUserService(..., remove = true)` destroys the service → `destroy()` → `stopReading()` kills every `getevent` child and closes injector stdin; the native binary destroys the uinput device on EOF and on SIGTERM/SIGINT/SIGHUP. Combined with `.daemon(false)`, no orphaned shell process or `/dev/uinput` device is left behind. This is the part most Shizuku integrations get wrong.
- **Watchdog shutdown is handled deliberately.** `@Volatile shuttingDown` is set in `onDestroy` before unregistering, callbacks are pinned to the main looper, and the re-apply is value-gated so it cannot write-loop.
- **FGS requirements are met.** `startForeground` is the first real statement of `onStartCommand` (no 5 s crash), the channel is created in `onCreate`, and the API 34 `specialUse` permission plus `PROPERTY_SPECIAL_USE_FGS_SUBTYPE` are declared.
- **IPC surface is mostly well-guarded.** The Shizuku provider is gated by `INTERACT_ACROSS_USERS_FULL`, the QS tile by `BIND_QUICK_SETTINGS_TILE`, `TriggerService` is `exported="false"` with a null `onBind()`, and every declared component states `exported` explicitly.
- **No injection sinks reachable from untrusted input.** Shell commands use argv-form `exec`; the only `sh -c` string interpolates PackageManager-controlled output (S7). Device paths passed to `getevent` are regex-restricted to `/dev/input/event\d+`.
- **No network attack surface.** No `INTERNET` permission, no HTTP client, no WebView, no sockets, no SQL, no exported files or Providers.
- **Every cross-process call is wrapped in `try/catch`**, so a dead peer cannot kill the reader loop.
- **No secrets in the repo.** Keystores and `keystore.properties` are gitignored; release signing degrades gracefully when absent. (No git-history mining was done — worth a one-time `git log --all --full-history -- '*.jks' 'keystore.properties'`.)

## Out of scope / not tested

- Dynamic testing: no APK was built or run, so the merged manifest was not inspected. Run `aapt2 dump permissions` on a built APK to confirm no unexpected permissions arrive from the Shizuku AAR.
- Shizuku internals: the `bindUserService` validation and `debuggable` semantics live in the Maven-resolved AAR, not in this repo, so S1/S2 reachability is reasoned rather than read.
- Platform-behaviour claims (FGS-from-boot restrictions on API 35, `POST_NOTIFICATIONS` effect on the FGS notification, protected-broadcast enforcement) are from platform knowledge, not verified on a device. R2 remains OPEN.
