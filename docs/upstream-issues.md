# Upstream issues — status in this fork

All issues of [F0x1d/LogFox](https://github.com/F0x1d/LogFox/issues) (26 at the time of writing,
18 open + 8 closed) checked against this fork. "Implemented" means the code ships in the
`v2.1.10-hUh63-1.4` release (1.2–1.4 are cumulative).

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

## 1.4 — stability pass

Bugs found by auditing the 1.3 sources, all fixed in `v2.1.10-hUh63-1.4`:

| Area | Problem | Fix |
|------|---------|-----|
| Level filter buttons (#259) | The highlight came from `MaterialButton` toggling itself, but the list was only re-submitted when a shared `logsChanged` flag was true — and the scroll listener's `Pause` kept resetting it. Result: buttons lit up without filtering anything. | Buttons no longer toggle themselves (`app:toggleCheckedStateOnClick="false"`), the store is the single source of truth (`ToggleLevelFilter`), and the list is submitted on a monotonic `listRevision` instead of the dirty flag |
| Level filter buttons | No visible feedback about what was filtered | Snackbar + toolbar subtitle ("Showing levels: …") |
| ProGuard retrace (#264) | The parsing regexes had no named groups but were read via `groups["…"]`, so every frame threw and the exception was swallowed — retracing silently did nothing | Positional groups, R8 inline-frame support (`start:end:…:obfStart:obfEnd`) with line mapping, no more guessing the first overload |
| First Shizuku authorization "crash" | `hardRestartApp()` killed the process even when the replacement activity had not started | Restart only kills the process after `startActivity` actually succeeded; launch intent gets `NEW_TASK`/`CLEAR_TASK` |
| Root setup | `pm grant` exit code was ignored, so a denied su prompt was reported as success | Checks `TerminalResult.isSuccessful`, exceptions become a normal failure |
| TEA store | One reducer/effect exception crashed the app or killed the screen permanently | Exceptions are isolated (cancellation rethrown), jobs always cleaned up, `ConcurrentHashMap` |
| Log collection | `Flow.catch` only protects upstream; a failure inside `collect` stopped logging for good, an error caused two restarts, a normal stop was treated as an error | Explicit failure/cancel flags and a `finally` that only restarts on clean completion |
| Log updates to UI | A failed preference read killed the only loop pushing logs to the list | Interval read is protected, clamped to a sane minimum |
| Crash detection | `collecting` was only reset on success — one failure disabled crash detection and leaked memory | Reset in `finally` |
| Recordings | `end()` left the state at `SAVING` on any failure, freezing recording forever | `finally` always returns to `IDLE` and clears the file |
| Filters editor | Backing out of a new filter created an empty "including" filter that matched every line and neutralized other filters; export used the snapshot loaded on open | Empty new filters are discarded, export serializes the current values |
| Crash export | Whole-file read + reformat on the main thread (ANR) and `Result` ignored (silent empty file) | Runs on IO, failures surface as a snackbar |
| Date/time format | An invalid custom pattern crashed list/detail screens from a `lazy` initializer; new format needed a restart | Falls back to the default pattern, formatter cache keyed by pattern |
| Shizuku terminal | Cached proxy trusted blindly, concurrent binds, stale proxy after a failed unbind | Serialized binding, binder liveness check, proxy cleared in `finally` |
| Shizuku user service | `destroy()` joined pipe coroutines that block in `read()` before killing the children → deadlock, `:service` process leaked | Children are destroyed first, bounded join |
| Logging service | `startForeground` only in `onCreate` (cold starts killed after 5 s), negative notification id, side-effect collector died on one bad effect | Re-asserted in `onStartCommand`, positive id, isolated collector |
| MCP server | Unbounded request line/header read, busy-loop on persistent `accept` errors, SSE session leaked when the client disconnected immediately, unbounded log channel, wildcard CORS exposed logcat to any web page | Length limits, accept backoff, sessions registered inside `try`, bounded channel, CORS removed |
| Apps picker | A query typed while the package list was loading was overwritten by the full list | Query is re-applied after loading |
| Service settings | One-time migration wrote `enabled=true` even when the user had turned the server off | Only fresh installs get the default |
| Filters list | A dirty row killed the flow collector, list never refreshed again | Retry with backoff |
| CI release | Tag builds ignored the tag and shipped the hard-coded default version | Tag name becomes `versionName` (`v` stripped), default bumped to 84 |

## 1.5 — level filter follow-up

| Problem | Fix |
|---------|-----|
| Level buttons could not be selected at all after switching the service backend | The button list was a `by lazy` property bound to the fragment *binding*. Re-creating the fragment view (what switching the backend does) leaves the fragment instance alive, so the lazy list kept pointing at destroyed views: the listener was attached to invisible buttons and the visible ones did nothing. Resolved on every access now. |
| Log list came back empty after the same re-creation | `lastListRevision` is reset in `onViewCreated`, so the new RecyclerView gets the list submitted |
| "Showing levels: …" was clipped to "Showing level…" | The summary moved out of the toolbar subtitle (it only gets the space left of the title and actions) into its own full-width line, up to two lines |

## 1.6 — follow-up

| Problem | Fix |
|---------|-----|
| Live logcat stopped for good after 10 s without output | The per-line `withTimeout` threw a `CancellationException`, which the collection code (now strict about cancellation) treated as a real cancel. `withTimeoutOrNull` + "keep waiting while the process is alive" instead: an idle live logcat is normal, a finished dump still ends the flow |
| "Save all logs" inserted an empty recording when writing failed | The write error is rethrown after the toast, and the effect handler only opens a recording on success |
| Two recordings saved within the same formatted time shared one file | Unique `-N` suffix, opened without append |
| Whole-row crash updates blanked the legacy `log` column | `log` is part of `AppCrashEntity` and mapped both ways |
| MCP: idle/half-open clients could starve the shared IO pool | 15 s socket read timeout + bounded concurrent clients (32) |
| `su` process and three FDs leaked when writing the command failed | Destroyed and closed on failure; `TerminalProcess.destroy` also closes the pipes |
| Settings kept claiming the MCP server was running after a force-stop | The "running" flag is reset when the process starts |



## 1.7 — Shizuku backend regression

| Problem | Fix |
|---------|-----|
| Shizuku backend shown as unavailable even though permission was granted | The `isBinderAlive` probe added to `ensureUserServiceBound()` in 1.4 is unreliable for the binder Shizuku hands out: it can report false while the user service is alive. Every availability check then discarded the cached proxy, rebound and hit the 15 s timeout. Removed the probe; a genuinely dead proxy is already reset by `execute()`/`executeNow()` on `RemoteException` |

## 1.8 — resource leaks, stuck states and MCP hardening

| Problem | Fix |
|---------|-----|
| Default terminal leaked stderr + stdin on every collection restart (EMFILE after a long session) | `TerminalProcess.destroy` closes both pipes (stdout is closed by the caller) |
| Shizuku terminal leaked pipes and left an orphan logcat in the service process when one of the three `ParcelFileDescriptor` calls returned null | Close what was obtained, `destroyProcess()` the child, and close all three streams on destroy |
| "Exit" left a zombie foreground service when `finishAndRemoveTask()`/`stopForeground()` threw | Every step isolated, store cancelled last, `exitProcess` in `finally` |
| Search panel stopped reacting to query/case-sensitivity changes after one failed emission | Same `runCatching` guard as the logs list |
| Selected log lines survived buffer eviction: toolbar stuck in selection mode, count larger than the list, copy/export silently empty | `selectedIds` follows the visible ids; `SyncSelectedLines` fired when it shrinks |
| Apps picker could spin forever when `getInstalledPackages()` threw | `AppsLoaded` is always dispatched (empty list on failure) |
| Apps picker: a slow result for an old query could replace the newer list | `SearchedAppsUpdated` carries the query and is dropped when it no longer matches |
| Two recordings started within the same formatted timestamp (e.g. `HH:mm`) shared one file | Unique `-N` suffix, same as the repository path |
| A failed write discarded the whole recording batch | Lines are dropped only after they are on disk |
| Notification "stop recording" could be lost when the process was frozen right after `onReceive` | `goAsync()` + `finish()` in `finally` |
| Crash log file written even when collection was disabled, leaving orphans nobody deletes | File is written only when the crash is actually collected |
| Soft-deleted crashes were never physically removed (`clearIfNeeded()` had no caller) | Called before inserting a new crash |
| MCP server stopped from the notification came back on the next process start | The action also disables `pref_mcp_server_enabled` |
| MCP `dataSync` foreground service hit the Android 15 six-hour cap and crashed the process | `specialUse` type with a subtype property |
| MCP SSE session buffered responses without limit (slow/non-reading client could OOM the service) | Bounded channel (256, drop oldest) |
| Filter import could not tell "unreadable file" from "empty file", and import/export failures were silent | Null content is a failure; both paths show a toast |
