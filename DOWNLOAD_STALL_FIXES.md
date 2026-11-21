# Download stall fixes (applied on top of "c claude revamp")

Files changed: DCPPService, PeerConnectionHandler, UploadManager, DownloadListFragment, Constants (app layer);
DCHandlers, DCDownloader, MessageHandler (libjdcpp).

## A. Wait state no longer latches (DCPPService.DownloadObject)
- Pre-flight no longer runs in the constructor (hubs are usually not connected yet on restore). It runs at the start of every attempt.
- No connected hub = stays queued (not "user offline"). Peer problems -> WAITING_FOR_PEER with a reason and backoff 30s/60s/120s/300s (nextRetryAt).
- get_status() reports WAITING_FOR_PEER while waiting, so the list shows "Waiting for peer (reason)". resume_download() and a network change clear the wait.
- Notifications for waiting items set a title and small icon before posting.

## B. Handshake order (PeerConnectionHandler)
- Upload server path now sends $MyNick, $Lock, then $Direction Upload, then $Key. The intercept path ($MyNick, $Lock) is unchanged.

## C. Advertised address (DCPPService.resolveAdvertiseIp)
- Hub-reported address ($UserIP), else our local socket address if public (or if the hub itself is on the same private network). Never the hub's own address.
- Private test covers 10/8, 172.16/12, 192.168/16, 127/8, 169.254/16, 100.64/10 and IPv6 loopback/link-local/ULA.
- Used for $ConnectToMe, $RevConnectToMe replies and $SR. If nothing usable is known, the attempt falls back to passive.

## D. One $ConnectToMe consumer (DCHandlers.MainDownloadHandler)
- MyConnectToMeHandler removed. The peer is identified from its $MyNick (never connect_nick).
- Pending download found -> download on that connection. Otherwise the SAME handshaken socket goes to UploadManager.handleHandshakenUploadConnection (PeerConnectionHandler resumes at the Direction/Key phase).
- DCHandlers.setUploadCallback is now installed by the service. Blocking connect/handshake waits are bounded and run off the router thread.

## E. Worker ownership (DCPPService.generate_worker)
- Claim per object and per peer (one active download per peer), released in a finally block on every exit path.
- Completed downloads are never restarted; retryPendingDownloads skips owned items and items still backing off.
- RuntimeExceptions are caught per item so a worker cannot die from one bad item.

## F. Deadlines and re-signalling
- QUEUED for > 90 s (peer never connected) or INITIATED/STARTED for > 60 s: attempt abandoned, item parked with backoff, worker moves on.
- Re-signal every 30 s via lastSignalTime, and only while the request is still pending (DCDownloader.isEntityPending).
- $MaxedOut parks the item immediately instead of holding a worker.
- DCDownloader marks an entity INITIATED the moment a connection claims it.

## G. Data integrity and locking
- MessageHandler: a first file byte of '|' is no longer dropped (dump flag is volatile and checked right after read()).
- DCDownloader.downloadQ is private; hasPendingFor / removeDownloadEntity / case-insensitive getters are synchronized.

## H. Active-mode decision
- If we can accept incoming connections we send $ConnectToMe whatever the peer's mode; otherwise $RevConnectToMe (needs an active peer). Both passive -> waiting.

## Diagnostics (queue investigation build)
- Every retry pass (about every 30s) logs `[DLTRACE] snapshot:` (queue size, workers alive, claimed items, busy peers) plus one line per unfinished download (status, paused, wait reason, retry countdown, inQueue, owned, hub state).
- Workers log `took`, `claimed`, `released`, and `full pass found nothing runnable`.

## Build f additions
- I. Stale-claim watchdog: every 30 s scheduler pass, a claimed download whose byte count has not moved for 240 s is force-released
  (connection closed, worker interrupted and replaced, peer freed, item parked with backoff). Logged as `[DLTRACE] STALE CLAIM`.
  Claims are owner-checked so a zombie worker cannot free a newer claim.
- J. `saveDownloadsState()` is now debounced (one write ~2 s later on a background thread); `onDestroy` flushes immediately.
- K. A peer `$Error ... Not Available` marks the item permanently failed: it is no longer re-queued every 30 s (Resume clears it).
- Snapshot now also prints a `CLAIM` line per active download (held time, no-progress time, thread state).

## Build g additions (power management)
- L. Hub liveness: MessageHandler records the time of the last byte read. The 30 s tick probes a quiet hub with `$GetINFO <ourNick> <ourNick>`; no bytes for 120 s means the link is dead and the hub is reconnected (`[DLTRACE] tick(...): hub ... silent`).
- M. After any hub (re)connect, parked downloads clear their backoff and restart at once.
- N. The keep-alive tick is also driven by an AlarmManager alarm (`setExactAndAllowWhileIdle`, 60 s, action `ACTION_TICK`) so it still runs when the phone is idle; the Timer remains. Both call `runKeepAliveTick`, which ignores a second call within 10 s.

## Build h
- O. Idle workers no longer spin: when every queued item belongs to a busy peer, a worker waits (up to 5 s, woken when a claim is released) instead of cycling the whole queue every 0.3 s. The per-take `[DLTRACE] ... took` log line was removed (the `claimed` line remains).

## Build i
- P. `MyApplication` records why the previous run ended into `Download/Android-DC++/last_exit.txt` (newest first): saved Java crash stack trace, plus Android 11+ `ApplicationExitInfo` (crash, low memory, killed by system, ANR, user stop...). No download behaviour changed.

## Build j
- Q. `MyApplication` now shows the previous exit reason on every app start: a long toast plus a "Previous exit report" notification (expand it for full text). The notification also says, for each of three places, whether the log was saved or why it failed: Download/Android-DC++/last_exit.txt, Android/data/com.phinmadvader.andcpp/files/last_exit.txt, and internal storage. The Download copy is media-scanned so it appears over USB. The saved crash trace is only deleted once at least one copy was written. No download behaviour changed.

## Build k
- R. Crash fix: `MainActivity.onConnectUIChanges` re-adds the Chat/Users/Downloads tabs with notify=false after a hub reconnect but never called `notifyDataSetChanged()`. After a disconnect had hidden Chat and Users, the pager still expected the old count (8 vs 10) and the app crashed with IllegalStateException on the next screen draw (seen when reopening the app after sleep). One `notifyDataSetChanged()` call added after those tabs. No download behaviour changed.

## Build l
- S. `MyApplication` starts a background "TraceLogger" thread that runs `logcat -v time` for this app's own tags (andcpp, libjdcpp, AndroidRuntime errors) and appends every line to `Download/Android-DC++/dltrace.txt` (falls back to Android/data/com.phinmadvader.andcpp/files/ if Download is not writable). At 3 MB the file is renamed to `dltrace_old.txt` and a new one started. This captures the existing `[DLTRACE]` lines and 30 s snapshots without USB. No download behaviour changed.

## Build m
- T. Hub reconnect no longer forgets pending download requests. Every reconnect built a new `DCClient` with a new, empty `DCDownloader`, so a $ConnectToMe/$RevConnectToMe already sent to the peer was lost: when the peer connected back, no pending download was found and the transfer never started (item then sat out the 90 s deadline and up to 5 min backoff; repeated reconnects during sleep could stop downloads indefinitely). `HubSession.keptDownloads` now holds the register across `connectToHub`/`handleConnectionFailure`, and `DCClient.adoptDownloadHandler()` hands it to the new client before `bootstrap()`. Includes build l trace file.

## Build n
- U. Overnight test (21 Nov): uploads ran all night, but downloads only started within a few minutes of a fresh hub login (03:26, 03:37, 06:50 after the dead-link reconnect, 10:37 when the app was reopened). In between, the hub kept sending traffic (so the liveness check never fired) but no $ConnectToMe ever came back for our $RevConnectToMe: Chapter 4 failed "no response from peer" every 5 min from 07:12 to 10:36. Cause not visible from our side (hub not relaying, or peer ignoring). Fix: after 3 unanswered connect requests in a row (peer is in the nick list), the service logs in to the hub again (`[DLTRACE] ... connect requests unanswered; logging in to hub ... again`), at most once per 15 min. The counter resets whenever a peer connects. Pending requests survive the reconnect (build m) and parked downloads restart straight after it (build g, M).
- V. Hub list (tap or long-press a hub): "Disconnect" is replaced by "Reconnect". It drops the hub link and logs in again through the normal reconnect path (first retry after 5 s), so pending download requests are kept and parked downloads restart once the hub is back. "Connect" is unchanged for hubs that are not connected.

## In-app updater (build 295)
- W. `versionCode` = build number (app/build.gradle), so builds can be compared.
- `UpdateChecker` (started from MyApplication): 15 s after start and then every 30 min, reads the GitHub releases of stevemorris528/Android-DC.2026 (drafts skipped, pre-releases included) and takes the most recently uploaded `.apk`. Each new upload is downloaded once to Android/data/com.phinmadvader.andcpp/files/updates/ and its versionCode read from the file; only a higher build than the installed one is offered. No naming rules for releases.
- Offer: dialog when the app is open (Install / Later), notification otherwise (tap opens the dialog). "Later" waits until the next 30 min check. First install asks for "Install unknown apps" permission.
- Before the installer starts, download state is saved (`DCPPService.prepareForUpdate`).
- `UpdateReceiver` (MY_PACKAGE_REPLACED) reopens MainActivity after the update; because Android 10+ may block that from the background it also posts "updated to build N, tap to reopen", which MainActivity clears. This also runs after `gradlew installDebug`.
- Log lines tagged `[UPDATE]`.
- X. Share missing from the hub user list after first connect: the hub session's own user object had share size 0, and the $MyINFO re-sent right after login (connection mode detected) used it, overwriting the correct size from login. Also the size was never re-sent after the share was scanned. Now: $MyINFO always uses the session's current share size; every file list rebuild calls `DCPPService.onShareSizeChanged`, which re-sends $MyINFO when the size changed (log: `Share size now ... MyINFO re-sent`).
- Y. Updater fixes: the automatic check only ran when the process started, but the service keeps the process alive, so reopening the app never checked. Now MainActivity also checks every time it is created. Settings has a new "Check for Updates" item (shows "Checking...", then the result or the Install dialog). A stale pending update is cleared on each check and a build not newer than the installed one is never offered. State is saved through the running service before installing from any screen.

## Z. Wi-Fi only covers uploads; upload long-press menu (build 304)
- The "Wi-Fi Only (DL + UL)" menu toggle (was "Wi-Fi DL Only") now also blocks uploads on mobile data. Running uploads are
  stopped when the link switches to mobile data (DCPPService.onWifiOnlyConditionChanged -> UploadManager.stopAllUploads).
  New requests get `$MaxedOut` so peers retry and resume once Wi-Fi is back. Running streams also check about once per MiB.
- Long-press an upload row: Stop Upload / Stop All Uploads to <nick> / Block-Unblock <nick> (until app restart) / Stop All Uploads.
  Blocked nicks get `$MaxedOut` (PeerConnectionHandler.serveRequest via UploadManager.uploadRefusalReason).

## AA. Download queue lost across an update (build 306)
- Seen 21.11 17:20: build 303 started at 17:20:16, restored 35 downloads at 17:20:17.9 and then CLEARED the saved state
  (restoreDownloadsState did `prefs.edit().clear()` and relied on a later save). The install of 305 killed it at 17:20:18
  before any save ran, so 305 found 0 saved downloads and then saved its empty queue.
- Fix: restore no longer clears the saved state. Saving is refused until the restore has run (or failed), so a save
  that runs early can't overwrite the list with an empty one. Each save clears the prefs first, so a shorter list
  leaves no stale entries.
