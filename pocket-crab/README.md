# Pocketcrab 🦀: a pocket backend on Android

Pinch a link, Ferris does the rest. Share a link from any Android app to **Pocketcrab**. An [Apalis](https://github.com/apalis-dev/apalis) job
fetches the page and records its **title and SHA-256**, running inside a
[Rivium](https://github.com/rivium-rs/rivium) service that lives *inside the app process*.
Results show up as a **notification** and in the app's list.

It can also **watch** a page: Apalis re-checks it on a schedule, and when the page's visible text changes you get
a notification showing what changed (`Price: $500` → `Price: $450`). For pages behind a login, you can sign in once
inside the app and the Rust worker reuses that session.

https://github.com/user-attachments/assets/587a557b-67ce-4de3-965b-0a22ede15165

```
 Share Sheet / clipboard button / Quick Settings tile / sign-in screen
        │  Kotlin drops a command in  <root>/inbox/<uuid>
        ▼
┌─ Rust, one Rivium app ("link-digest"), started by nativeStart ───────────────┐
│ service "inbox"          reads inbox commands, pushes jobs into Apalis        │
│ service "digest-worker"  Apalis worker: fetch URL, save title + sha256, or    │
│                          (watch) compare text, record a change, and schedule  │
│                          the next check with run_after                        │
│                          storage: SQLite  <root>/jobs.db                      │
│                          (jobs, digests, watches, changes, cookies)           │
│ service "snapshot"       writes <root>/state.json once a second (atomic)      │
└───────────────────────────────────────────────────────────────────────────────┘
        │  Kotlin only reads state.json (it never opens jobs.db)
        ▼
 notifications + list screen + home screen widget
```

* **One job type:** `DigestLink { url, watch_every }` (5 attempts, retried by Apalis). With `watch_every` set it
  is a *watch*: after each check it pushes itself again with `run_after(every)`, so the schedule lives in the
  same durable queue and survives restarts.
* **Android features:**
  * **Share Sheet**: share a link from any app, pick *Pocketcrab*, then choose *Pinch once* or *Watch* (every
    15 s for demos, 15 min, hourly, daily).
  * **Notifications**: one per finished job (tap opens the page), one when a job gives up (with the reason),
    and one when a watched page changes, with a before → after excerpt and a **Stop watching** button. A
    foreground service keeps the worker alive.
  * **Home screen widget**: "N watching, M changed since you last looked".
  * **Quick Settings tile** "Pinch link" and an **in-app button**: queue whatever link is on the clipboard.
  * Every job is visible in the app: working, retrying, pinched, failed (tap to retry), or watching.
* **Page Watcher:** the worker hashes the page's *visible text* (scripts, styles, comments and tags removed), so
  markup churn does not count as a change. A change stores a short excerpt around the edit (two words of
  context each side). A failed check is recorded and the next one is still scheduled, so a flaky network
  cannot silently end a watch.
* **Login support:** *Sign in to this site first* opens an in-app browser (WebView). Tapping *Use this login*
  hands that session's cookies to the worker, which sends them only to that host (and `reqwest` drops them on a
  cross-host redirect). They are stored in the app's private database, never logged, and never written to
  `state.json` (the UI only learns *which hosts* have a login). A new login replaces the old one and applies to
  watches that are already running. Plain `http` is refused by the app except for `127.0.0.1`/`localhost`, which
  is only there for local testing.
* **What was verified on the phone** (Redmi 12): the share dialog, watching, change detection and notifications
  (including the *Stop watching* button), the home screen widget, the Quick Settings tile, persistence across
  force-stop and reinstall, and login against a local test site that needs a session cookie (see the videos).
  The one exception: the Share Sheet was exercised by delivering the same `ACTION_SEND` intent over adb rather
  than through the system share menu.
* **Persistence:** jobs, results, watches and logins live in SQLite (`apalis-sqlite`). Force-stop the app,
  reopen it, and unfinished jobs are re-queued (picked up again within ~5-10 s, because of the 5 s heartbeat)
  and watches carry on. The same holds on the laptop: stop the service, restart it, everything is still there.
* No `unsafe` in this crate; the JNI bridge comes from `rivium_jni::export!`.

**Demo videos**
* [docs/pocketcrab-demo.mp4](https://github.com/horacenjoroge/rivium-challenge/blob/pocketcrab-link-digest/pocket-crab/docs/pocketcrab-demo.mp4)
  (1:36, the player above): a laptop `cargo run` session (rendered from the real captured terminal output, idle
  waits shortened), then the phone (clipboard to notification) and the force-stop persistence test.
* [docs/pocketcrab-watch-login-demo.mp4](https://github.com/horacenjoroge/rivium-challenge/blob/pocketcrab-link-digest/pocket-crab/docs/pocketcrab-watch-login-demo.mp4):
  watching a page and catching a change, then signing in to a page behind a login and catching a change there.

## Run on your laptop (no phone)

All commands run from this directory (`pocket-crab/`).

```bash
cargo run -- --root target/dev                          # Rivium service + worker; Ctrl-C stops cleanly
# in another terminal, either:
cargo run --bin enqueue -- target/dev https://example.com
echo https://www.rust-lang.org > target/dev/inbox/a      # same inbox the Android app uses
sqlite3 target/dev/jobs.db "select url,title,substr(sha256,1,12) from digests"

# watch a page for changes (re-check every 5 s), then stop watching
echo "watch 5 http://127.0.0.1:8765/page.html" > target/dev/inbox/b
echo "unwatch http://127.0.0.1:8765/page.html" > target/dev/inbox/c
# use a saved login for a host (this is what the Android sign-in screen sends)
echo "cookie example.com session=abc123" > target/dev/inbox/d
cargo test                                               # 8 unit tests
```

Inbox commands (one per file): `<url>`, `watch <seconds> <url>`, `unwatch <url>`, `cookie <host> <Cookie header>`,
`uncookie <host>`.

## Run on Android

Prerequisites: Rust ≥ 1.94 with the Android target, `cargo-ndk`, Android SDK + NDK, JDK 17+.

```bash
# from pocket-crab/
rustup target add aarch64-linux-android
cargo install cargo-ndk
export ANDROID_NDK_HOME=$ANDROID_HOME/ndk/27.1.12297006
cargo ndk -t arm64-v8a -o android/app/src/main/jniLibs build --release
cd android && ./gradlew assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

Open the app once (grant notifications). To add the tile: swipe down twice, tap the pencil/edit icon, and
drag **Pinch link** into your active tiles. Then share a link from a browser and pick **Pocketcrab**.
Without a browser, from adb:

```bash
adb shell "am start -a android.intent.action.SEND -t text/plain \
  --es android.intent.extra.TEXT 'https://www.rust-lang.org' -n com.horace.linkdigest/.MainActivity"
```

## Versions used

| Thing | Version |
|---|---|
| Rust | 1.99.1 (minimum 1.94: Rivium needs 1.92, but `sqlx` 0.9 via `apalis-sqlite` needs 1.94; checked with `cargo +1.94.0 check`) |
| rivium / rivium-host / rivium-jni | =0.2.0 |
| apalis / apalis-sqlite | =1.0.0 |
| sqlx | 0.9 |
| reqwest | 0.12 (rustls, no OpenSSL) |
| cargo-ndk / NDK | 4.1.2 / r27 (27.1.12297006) |
| Android Gradle Plugin / Gradle / Kotlin | 8.7.3 / 8.13 / 2.0.21 |
| compileSdk / minSdk | 35 / 26 (arm64-v8a only) |

## Real-device notes (Redmi 12, Android 15, HyperOS / MIUI 2.0.210)

* Rivium **embedded host runs on a real ART/bionic device**: `nativeStart` returned 0, status `Running`,
  `nativeStop` on service destroy. Built with NDK r27 (Rivium's CI uses r28b).
* Share Sheet → notification took ~2 s end to end on Wi-Fi.
* Force-stop mid-job, then reopen: the orphaned job was recovered and finished (attempt 2).
* A watch kept running across a reinstall of the app, and caught the next change.
* Signing in inside a `WebView`, then calling `CookieManager.getCookie(url)`, returned the session cookie
  (including `HttpOnly`) and the Rust worker could fetch the logged-in page with it.

Things that bit me (useful for Rivium/Apalis maintainers):

1. **Worker names must be unique per run.** Reusing a fixed name fails the next start with
   `WORKER_ALREADY_EXISTS` until the old heartbeat expires. Each run uses `digest-<millis>`.
2. **Concurrent first-time setup locks SQLite.** Two services opening the DB at startup gave
   `database is locked` (error 5) despite a `busy_timeout`; setup is now serialised with a mutex.
3. **Apalis SQLite default polling is slow for an idle queue.** A job pushed after startup was not picked
   up within 25 s until `poll_with_interval(1s)` was set.
4. **Default heartbeat is 30 s × 2 missed**, so orphaned jobs take ~60 s to recover; set to 5 s here.
5. **Retries have no backoff by default**; a bad URL burned through 12 attempts in seconds before I capped
   attempts at 5 and slept 5 s on failure.
6. **Queue name must match between pusher and worker.** Adding `with_config(Config::default()...)` to the
   worker silently changed its queue from the job type name to `default`, so jobs pushed with plain
   `SqliteStorage::new` were never picked up (stuck `Pending`, no error). Both sides now share one
   `queue_config()` with an explicit queue name.
7. **Failures were invisible.** A job that exhausted its retries is just status `Failed` in SQLite; my UI
   only counted unfinished jobs, so it looked like "queued, then nothing". The app now lists `Jobs` too.
   Many sites also 403 a request with no User-Agent, so the worker sends a browser-style one.
8. **Do not open the same SQLite file from Android and from Rust in one process.** My first UI read
   `jobs.db` with `android.database.sqlite` while the Rust side (bundled SQLite) had it open in WAL mode.
   It worked for a while, then the worker thread died with `SIGBUS` (`BUS_ADRERR`) in `sqlx-sqlite-worker`.
   Two SQLite copies in one process share POSIX file locks and the `-shm` mapping, and closing one
   connection can break the other. Rust is now the only thing that touches `jobs.db`; a third Rivium
   service writes `state.json` (temp file + rename) and Kotlin only reads that.
9. Android 15 edge-to-edge drew the layout under the action bar (`fitsSystemWindows`).
10. `adb shell am start --es` text with spaces needs the whole command in one quoted string.
11. **Apalis scheduling works for recurring work.** A watch is one task that pushes its successor with
    `TaskBuilder::run_after(..)`; after a restart the next check is picked up ~5-7 s after start (the heartbeat),
    not instantly. Unwatching just deletes the watch row: the next task finds no row and ends the chain.
12. **A job that returns `Err` ends a recurring chain after its retries**, so watch checks never return `Err`:
    a failed fetch is written to the watch row and the next check is scheduled anyway.
13. **Xiaomi/HyperOS:** `adb shell input tap` is ignored unless "USB debugging (Security settings)" is on
    (keyevents and swipes still work), and the launcher caches app icons and widget previews, so a reinstall
    does not refresh them until the launcher is restarted.

## Known limitations

* Duplicate one-off links create duplicate jobs (a *watch* is deduplicated per URL).
* Watching compares visible text only: image or layout changes are not seen, and pages that render their content
  with JavaScript after loading are not supported (the worker fetches the first HTML, like `curl`).
* Pages with ads, clocks or random tokens in their text will look "changed" every time.
* Login support is cookie based: it will not work for sites with two-factor sign-in or bot protection, or whose
  sessions expire quickly. A watch whose login stops working shows "Login may have expired". It was tested only
  against a local test site; do not use it for banking or other sensitive accounts.
* Checks only run while the app's process is alive, so a phone that kills the app will pause them.
* Title extraction is a simple `<title>` scan, not an HTML parser.
* Only `arm64-v8a` is built; no emulator/x86 testing done.
* The worker only runs while the foreground service is alive; Android may still kill it under memory pressure.

## License

GPL-3.0, same as the challenge repository (see `LICENSE`).

The crab image used for the launcher icon and widget preview is from
[Noto Emoji](https://github.com/googlefonts/noto-emoji) (Google), Apache License 2.0.
