# Pocketcrab 🦀: a pocket backend on Android

Pinch a link, Ferris does the rest. Share a link from any Android app to **Pocketcrab**. An [Apalis](https://github.com/apalis-dev/apalis) job
fetches the page and records its **title and SHA-256**, running inside a
[Rivium](https://github.com/rivium-rs/rivium) service that lives *inside the app process*.
Results show up as a **notification** and in the app's list.

```
 Share Sheet / clipboard button / Quick Settings tile
        │  Kotlin drops the link in  <root>/inbox/<uuid>
        ▼
┌─ Rust, one Rivium app ("link-digest"), started by nativeStart ───────────────┐
│ service "inbox"          polls inbox/ and pushes a DigestLink job into Apalis │
│ service "digest-worker"  Apalis worker: fetch URL, save title + sha256        │
│                          storage: SQLite  <root>/jobs.db  (jobs + results)    │
│ service "snapshot"       writes <root>/state.json once a second (atomic)      │
└───────────────────────────────────────────────────────────────────────────────┘
        │  Kotlin only reads state.json (it never opens jobs.db)
        ▼
 notifications + list screen
```

* **One job type:** `DigestLink { url }` (5 attempts, retried by Apalis).
* **Android features** (three ways in, one way out):
  * **Share Sheet**: share a link from any app and pick *Pocketcrab*.
  * **Quick Settings tile** "Pinch link": copy a link, swipe down, tap. A transparent activity reads the
    clipboard (Android only lets a focused window do that) and queues the link.
  * **In-app button** "Pinch the link on my clipboard".
  * **Notifications**: one per finished job (tap opens the page) and one when a job gives up, with the reason.
    A foreground service keeps the worker alive.
  * Every job is visible in the app: working, retrying, pinched, or failed (tap to retry). Some sites
    (e.g. accuweather.com) return 403 to any non-browser client; those show as failed with the reason.
* **What was verified on the phone:** the in-app clipboard button, notifications, the list, and persistence
  (see the video). The Share Sheet was exercised with the same `ACTION_SEND` intent delivered over adb, and
  the Quick Settings tile compiles and installs but **has not been tapped on the device yet**.
* **Persistence:** jobs and results live in SQLite (`apalis-sqlite`). Force-stop the app, reopen it, and
  unfinished jobs are re-queued (picked up again within ~5-10 s, because of the 5 s heartbeat) and finished
  results are still listed. The same holds on the laptop: stop the service, restart it, jobs are still there.
* No `unsafe` in this crate; the JNI bridge comes from `rivium_jni::export!`.

**Demo video** ([docs/pocketcrab-demo.mp4](https://github.com/horacenjoroge/rivium-challenge/blob/pocketcrab-link-digest/docs/pocketcrab-demo.mp4), 1:36): laptop `cargo run` session (rendered from the real captured terminal output, idle waits shortened), then the phone
(clipboard to notification) and the force-stop persistence test.

## Run on your laptop (no phone)

```bash
cargo run -- --root target/dev                          # Rivium service + worker; Ctrl-C stops cleanly
# in another terminal, either:
cargo run --bin enqueue -- target/dev https://example.com
echo https://www.rust-lang.org > target/dev/inbox/a      # same inbox the Android app uses
sqlite3 target/dev/jobs.db "select url,title,substr(sha256,1,12) from digests"
```

## Run on Android

Prerequisites: Rust ≥ 1.94 with the Android target, `cargo-ndk`, Android SDK + NDK, JDK 17+.

```bash
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

## Known limitations

* Duplicate links create duplicate jobs (no dedupe).
* Title extraction is a simple `<title>` scan, not an HTML parser.
* Only `arm64-v8a` is built; no emulator/x86 testing done.
* The worker only runs while the foreground service is alive; Android may still kill it under memory pressure.

## License

GPL-3.0, same as the challenge repository (see `LICENSE`).
