# TaskPing

A minimal Android background-job demo built with **Rust + Rivium + Apalis**, made for the
Rivium + Apalis Pocket Backend Challenge.

When you tap **Run Task**, Android queues a real `DemoJob` in an Apalis SQLite queue. An Apalis worker
running inside a Rivium-managed service processes it (`a + b`). Android then shows the result
and posts a notification.

## Versions (pinned, verified working together)

| Component | Version |
|---|---|
| Rust | 1.99.1 (crate requires ≥ 1.92, edition 2024) |
| rivium / rivium-host / rivium-jni | =0.2.0 |
| apalis / apalis-sqlite | =1.0.0 (sqlx 0.9.0, tokio 1.54) |
| jni | =0.22.4 (same version rivium-jni uses) |
| Android NDK | 30.0.16248370, built with cargo-ndk 4.1.2, minSdk 26 |
| AGP / Kotlin / Gradle | 9.0.1 / 2.3.20 / 9.1.0 (from the `android create empty-activity` template) |

## Architecture

```
Android (Kotlin)                         libtaskping.so (Rust)
────────────────                         ─────────────────────
onCreate ── nativeStart(--root files/) ─▶ rivium-jni ─▶ rivium-host embedded Host
                                                         └─ service "apalis-worker"
                                                              └─ Apalis Worker ◀─┐ polls
Run Task ── nativeRunDemoJob(root,a,b) ─▶ push DemoJob ─▶ files/taskping.db ─────┘ (SQLite)
   ◀──────────── result "42" ◀────────── wait_for_single(task id) ◀── result stored by worker
notification
```

- `rust/src/lib.rs` defines `DemoJob`, the handler, the SQLite storage, and `TaskPingApp`. `TaskPingApp`
  is the Rivium `App` with one service, and that service owns the Apalis worker. The worker stops when
  Rivium stops the service (`run_until(ctx.stopped())`).
- `rust/src/main.rs` is the laptop host: `rivium_host::process::run` (SIGINT/SIGTERM, `--root`).
- `rust/src/bin/submit.rs` is the laptop client. It queues one job and waits for its result.
- `rust/src/jni_bridge.rs` contains `rivium_jni::export!`, which provides Rivium's official five-method lifecycle ABI.
  It also adds one extra native method, `nativeRunDemoJob`, which pushes into the queue and waits for the result.
- `android/.../TaskPingBridge.kt` declares the native methods. `MainActivity.kt` has the button, the status
  text, and the notification.

The job queue is persistent. Jobs live in `<root>/taskping.db` (apalis-sqlite) and are written
to disk before they are processed.

## Run on a laptop

```sh
cd rust
cargo run -- run --root target/demo            # terminal 1: Rivium-managed worker (Ctrl-C stops it)
cargo run --bin submit -- target/demo 2 3      # terminal 2: prints "[client] result: 5"
```

Expected output in terminal 1:
```
[service] apalis worker running; db at .../target/demo/taskping.db
[worker] DemoJob 2 + 3 = 5
```

**Persistence check:** stop the worker and run `submit`. It queues the job and waits. Start the
worker again, and the queued job is processed and `submit` prints the result.

## Run on Android

```sh
./build-android.sh                                        # Rust .so libraries + debug APK
adb install -r -g android/app/build/outputs/apk/debug/app-debug.apk
adb shell am start -n com.taskping.app/.MainActivity
```

Tap **Run Task**. The status line shows `DemoJob 1 + 41 = 42` and a "TaskPing job complete"
notification appears. You can confirm it with `adb logcat -s TaskPing`.

## What was actually tested

- ✅ Laptop: job queued → processed by the Rivium-managed Apalis worker → result returned (about 1–2 s).
- ✅ Laptop: a job queued while the worker was stopped was processed after the worker restarted (persistence).
- ✅ Laptop: graceful SIGINT shutdown (exit 0) and repeated back-to-back restarts.
- ✅ Desktop JVM (JDK 17): `TaskPingBridge` loaded the dylib. start → job result 42 → stop.
- ✅ Android emulator (API 36.1, arm64): Run Task → `42` from the Rust worker → notification posted
  (checked with `dumpsys notification`). The job rows are `Done` in the app's `files/taskping.db`, and they
  survived a force-stop and relaunch.

## Limitations (honest list)

- **The worker runs only while the app process is alive.** There is no foreground service or WorkManager,
  so "background" means off the UI thread inside the Rust service. It does not keep running after Android
  kills the app. Queued jobs stay in SQLite and are picked up on the next start. This was tested on the
  laptop but not on Android.
- `nativeRunDemoJob` blocks (Kotlin calls it on `Dispatchers.IO`) and polls SQLite every 500 ms until the result is stored.
- The heartbeat is set to 2 s, because apalis-sqlite registers a worker only after the first heartbeat
  tick (the default of 30 s delayed the first job by about 30 s). The worker name is unique per run, because
  a fixed name was rejected with `WORKER_ALREADY_EXISTS` right after a restart.
- Rust `println!` output does not reach logcat on Android. Android-side logs come from Kotlin (`TaskPing` tag).
- The x86_64 `.so` is built but was not run. It was not tested on a physical device.
- Not handled: notification permission denial (the result still shows in the UI), and job failure
  or retries beyond Apalis defaults.
