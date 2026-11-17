# Upstream issues — status in this fork

All issues of [F0x1d/LogFox](https://github.com/F0x1d/LogFox/issues) (26 at the time of writing,
18 open + 8 closed) checked against this fork. "Implemented" means the code ships in the
`v2.1.10-hUh63-1.3` release.

## Fixed / implemented here

| # | Issue | What was done |
|---|-------|---------------|
| [#239](https://github.com/F0x1d/LogFox/issues/239) | Apps with `sharedUserId` show up under a random sibling package | Logs of a shared uid are labelled with `android:sharedUserId` (read reflectively, the field is gone from the SDK in API 36) |
| [#240](https://github.com/F0x1d/LogFox/issues/240) | Regex toggle for tag (and other string fields) | `UserFilter` gained `tagRegex` / `contentRegex`; matching uses `contains` by default and a `Regex` when the switch is on (invalid patterns match nothing). Toggles are in the filter editor overflow menu. DB migrated 18 → 19 |
| [#249](https://github.com/F0x1d/LogFox/issues/249) | Can't setup LogFox (Shizuku click does nothing) | Shizuku permission request and user-service bind now have timeouts (30 s / 15 s) with cancellable continuations, so a missing callback ends in a visible error instead of a dead button |
| [#250](https://github.com/F0x1d/LogFox/issues/250) | Grabbable scroll bars | Scrollbars enabled (non-fading) on the logs list, crashes list, app crashes, filters and crash details |
| [#251](https://github.com/F0x1d/LogFox/issues/251) | Save LogCat output to a file | Already present as *Save all logs* in the logs overflow menu |
| [#253](https://github.com/F0x1d/LogFox/issues/253) | Tasker / MacroDroid / Automate plugin | Implemented as an event-intent API instead of a native plugin: see *Broadcast log events* below |
| [#254](https://github.com/F0x1d/LogFox/issues/254) | LogFox detected a crash in LogFox itself | Exporting a recording whose file is gone threw `FileNotFoundException` out of the TEA store; the use case now returns `Result`, the user gets a toast, and crash-log reads are guarded + moved off the main thread |
| [#255](https://github.com/F0x1d/LogFox/issues/255) | Expose logcat events to third-party automation via broadcasts | Same broadcast API as #253 |
| [#256](https://github.com/F0x1d/LogFox/issues/256) | Shizuku not working (secondary profile) | Same timeout fix as #249: when the user service cannot be started the app reports an error instead of hanging |
| [#258](https://github.com/F0x1d/LogFox/issues/258) | Sometimes the log appears, sometimes it doesn't | On start, LogFox checks `persist.log.tag` / `log.tag` and warns when the device mutes logcat (`S` or `W`), with the exact `setprop` fix |
| [#259](https://github.com/F0x1d/LogFox/issues/259) | Real-time logs with filter buttons | Logs are already streamed live; a V D I W E F S button row above the list now filters levels instantly |
| [#262](https://github.com/F0x1d/LogFox/issues/262) | Change font size for crash dumps | Smaller / larger text actions in the crash details toolbar (8–30 sp, shares the log text size preference) |
| [#263](https://github.com/F0x1d/LogFox/issues/263) | Wrap long lines checkbox state is buggy | Wrapping is applied on every render (not only when a crash is already loaded), text/scroll position is preserved, stale search highlights are dropped |
| [#264](https://github.com/F0x1d/LogFox/issues/264) | Search and replace for crash dumps | *Replace* / *Clear replacements* in the crash details overflow menu: a find & replace pair is applied to the original dump (empty replacement removes, e.g. R8 tokens). Map-file deobfuscation is **not** implemented |
| [#235](https://github.com/F0x1d/LogFox/issues/235) | Cannot open or view logs exported or saved in LogFox | `LogFileViewerActivity` + `VIEW` intent filters (`.log`, `text/*`) so files open from any file manager, and a *View* button in the recording details sheet to read a recording in-app |
| [#229](https://github.com/F0x1d/LogFox/issues/229) | Export pop-up not fully visible (Quest 3) | Recording details sheet is scrollable and always expands |
| [#226](https://github.com/F0x1d/LogFox/issues/226) | Help with fixing F-Droid releases | `versionCode` / `versionName` now have static fallbacks (81 / `2.1.10-hUh63-1.1`) instead of requiring environment variables |
| [#241](https://github.com/F0x1d/LogFox/issues/241) | Optional name/label for filters | Already implemented (`UserFilter.name`, rename from the toolbar title) |
| [#236](https://github.com/F0x1d/LogFox/issues/236) | Add button to save current log | Already implemented (*Save all logs*) |
| [#230](https://github.com/F0x1d/LogFox/issues/230) | "Clear" not working when paused | Already fixed upstream (reducer clears the list, not only the service buffer) |
| [#248](https://github.com/F0x1d/LogFox/issues/248) | "Discard changes?" without any edit | Already fixed upstream (render uses watcher-safe setters) |
| [#238](https://github.com/F0x1d/LogFox/issues/238) | LogFox stuck on startup screen | Same root cause as #249/#256 (Shizuku path could wait forever); the setup screen now always finishes with success or an error |

## Added in 1.3

| # | Issue | What was done |
|---|-------|---------------|
| [#261](https://github.com/F0x1d/LogFox/issues/261) | Redesign the filters screen | Screen rebuilt: uid/pid/tid side by side, every text field has a clear (✕) icon, the name field sits on its own row, the log levels are a flat row of V D I W E F S buttons with a *clear* button next to them. The save button is gone: existing filters auto-save on every change (writes are serialized, a stale snapshot can never overwrite newer text) and leaving the screen always persists |
| [#264](https://github.com/F0x1d/LogFox/issues/264) | Deobfuscate stacktraces with a ProGuard/R8 map file | Full retracer: **Crash details → ⋮ → Retrace: import mapping.txt** parses the mapping (class renames, method renames including overloads by line range, line numbers, `Caused by:` headers) and applies it to the log. Copy, share and export all use the retraced text. **Retrace: forget mapping** turns it off again |

## Fixed in 1.3

| Bug | What was done |
|-----|---------------|
| Crash right after authorizing Shizuku for the first time | The user-service binder dies while it is being (re)created after the grant, and `RemoteException`/`DeadObjectException` escaped `execute`/`executeNow`, the grant use case and `hardRestartApp`. All of them are guarded now, a dead binder resets the cached service instead of killing the app, and `pingBinder()` inside the service callback no longer throws into the process |
| V D I W E F S buttons in the logs toolbar did nothing | `MaterialButton` toggles its own checked state before the click listener runs, so flipping it manually cancelled the tap — the level set never changed. The buttons are now the source of truth (`SetLevelFilter`), the active levels are shown in the toolbar subtitle, a snackbar confirms every tap and the same widget is reused in the filter editor |
| Logging could stop silently | Storage, crash detection, recording and the broadcast receiver shared one collection coroutine: the first `IOException`/`SQLiteException` cancelled it and no new line was collected until the service restarted. Each step now fails on its own |
| File logger could die and leak memory | The writer coroutine had no supervisor and the channel was unbounded: one failed write killed the loop and every later line stayed in memory. Now bounded (drop-oldest) with a supervisor scope |
| Shizuku user service races | The process map was a plain `HashMap` written from several binder threads, ids were not atomic, finished processes were never removed, and a hung command pinned a binder thread forever |

## Not implemented (with reasons)

| # | Issue | Reason |
|---|-------|--------|
| [#244](https://github.com/F0x1d/LogFox/issues/244) | Connect to remote devices over ADB | Needs a full ADB client (pairing, TLS, key exchange) inside the app — comparable in size to the whole logcat pipeline. Recommended workaround stays `adb connect` + a local forwarder |
| [#252](https://github.com/F0x1d/LogFox/issues/252) | Request `READ_LOGS` at runtime | `READ_LOGS` is a signature/privileged permission; Android does not show a runtime dialog for it. It is either preinstalled-as-system, granted through `pm grant` (root/Shizuku/ADB) or not available at all. The setup screen keeps explaining the three possible paths |

## Broadcast log events (Tasker / MacroDroid / Automate)

Enable *Broadcast log events* in **Settings → Service**. Every collected line is then sent as

```
action: com.f0x1d.logfox.LOG_EVENT
extras: level (VERBOSE/DEBUG/INFO/WARNING/ERROR/FATAL/SILENT), tag, content,
        package, uid, pid, tid, dateAndTime
```

at most 50 events per second (logcat easily produces thousands, and flooding would break both
sides). In Tasker add a *System → Intent Received* profile for `com.f0x1d.logfox.LOG_EVENT`
and match on `tag` / `content`, e.g. `%content ~ *MyTag*`.
