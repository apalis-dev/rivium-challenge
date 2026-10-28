# Pocket

A tiny durable job queue that runs **inside an Android app**. Share text from any
app and a Rust worker — supervised by [Rivium](https://github.com/rivium-rs/rivium),
queued by [Apalis](https://github.com/apalis-dev/apalis) — describes it, then reports
back through a notification.

The same `App` runs on a laptop with `cargo run` and inside the phone. Nothing in the
queue code knows which host it is under.

The original challenge brief is preserved as [CHALLENGE.md](CHALLENGE.md).

## What it does

Share a snippet from Chrome, a note, anywhere:

```text
Share → Pocket  →  POST 127.0.0.1:8787/jobs  →  SQLite  →  worker  →  notification
```

The job is written to disk **before** any work starts, so closing the app, or the
system killing it, loses nothing. Reopen and the work finishes.

One job type: count the characters and words, find the distinct words, and take an
FNV-1a hash so the same text always gives the same fingerprint.

Four states, all captured on the phone. Empty, and working:

![empty state](docs/pocket-empty.png)

![a job working](docs/pocket-working.png)

Once jobs exist, the list distinguishes them at a glance — `done`, an amber
`recovered · attempt 2`, and a red `failed`:

![job list on the phone](docs/pocket-list.png)

A job that cannot succeed is not left pretending to run — it records why:

![failed state](docs/pocket-failed.png)

Tapping any row opens the full result, with the attempt count and the hash in monospace:

![detail view](docs/pocket-detail.png)

A finished job arrives as a notification carrying **Copy hash** and **Share result**:

![notification](docs/pocket-notification.png)

[`docs/share.gif`](docs/share.gif) is one continuous recording on the phone, about 42
seconds: the app open, the Android share sheet with Pocket in it, the share arriving as a
job, the result appearing as a heads-up notification, **Slow job (6s)** tapped, the row
reading `running`, the process force-stopped, and after reopening the same job back at the
top of the list as `done · recovered · attempt 2`.

## How it works

- **Rivium** owns the lifecycle. `src/app.rs` implements `App` and returns two
  supervised services: the HTTP listener on the `Frontline` tier and the worker on
  `Background`. Shutdown therefore stops accepting requests first, then drains the
  job in flight. Readiness is also the migration barrier: the worker service reports
  ready only after migrations and the `job_view` table exist, and the listener cannot
  serve until every service is ready.
- **Apalis** owns the queue. `apalis-sqlite` gives retries, scheduled jobs and orphan
  recovery. Two settings matter and both are in `src/queue.rs`: a **unique worker name
  per run**, and a **1.5 s heartbeat**. With a fixed name, apalis refuses to register
  the new worker (`WORKER_ALREADY_EXISTS`) and the dead run's job stays `running`
  forever.
- **The share sheet** is the Android feature. It posts to a loopback listener, because
  Rivium's JNI bridge deliberately exposes only start/stop/status/version — there is no
  "enqueue" call, and adding one would mean undocumented `unsafe` JNI. HTTP is also the
  reason the laptop path and the phone path are identical.

`job_view` is the app's own read model. The UI never reads Apalis's internal tables.

## Run it on a laptop

No phone needed.

```sh
cargo run -- --root target/pocket
```

```sh
curl -s localhost:8787/readyz                     # 200 once the queue is migrated
curl -s -X POST localhost:8787/jobs \
  -H 'content-type: application/json' \
  -d '{"text":"the quick brown fox jumps over the lazy dog"}'
curl -s localhost:8787/jobs/<id>
```

Real output, copied as-is:

```text
$ curl -s -o /dev/null -w '%{http_code}\n' localhost:8787/readyz
200

$ curl -s -X POST localhost:8787/jobs -H 'content-type: application/json' \
    -d '{"text":"the quick brown fox jumps over the lazy dog"}'
{"id":"01M60MVD49KFX9KCPJ6Q4KCKHH"}

$ curl -s localhost:8787/jobs/01M60MVD49KFX9KCPJ6Q4KCKHH
{"id":"01M60MVD49KFX9KCPJ6Q4KCKHH","text":"the quick brown fox jumps over the lazy dog","status":"done","attempts":1,"result":{"chars":43,"elapsed_ms":0,"hash":"7404cea13ff89bb0","unique_words":8,"words":9},"enqueued_at":1793170715785,"done_at":1793170715808}
```

`work_ms` is a debug knob that makes a job slow on purpose — useful for the tests
below. `--set lifecycle.stop_timeout=10s` raises the 3 s default shutdown grace;
without it, a job longer than 3 s is cut off instead of allowed to finish.

Ctrl-C with a 4 s job running:

```text
state before signal:  ('running', 1)
process exit code:    0
state after shutdown: ('done', 1)
residue:              none
```

### Why the worker name changes every run

`src/queue.rs` names each worker `pocket-<ulid>`. That is not decoration — it is what
makes a crashed run's job recoverable. The transcript below comes from a throwaway
harness that pushes a job and runs a worker over the same `src/queue.rs`; the app does
exactly this, with the name it generates. Same job, same database, only the name differs:

```text
$ ./negctl $DB work fixed            # first run, killed -9 after 3 s
worker pocket-fixed
   running|1

$ ./negctl $DB work fixed            # same name, restarting
worker pocket-fixed
Error: BackendError(PollNextError(Database(Io(Custom { kind: AlreadyExists, error: "WORKER_ALREADY_EXISTS" }))))
   running|1                         # after 8 s: still stuck

$ ./negctl $DB work                  # fresh name, as the app does
worker pocket-01M60MXS5W7NR55BBH1FFV1M9A
   recovered after 6s
   done|2
```

The dead worker's row is still in Apalis's `Workers` table, so the same name cannot
re-register. A fixed name therefore turns every crash into a permanently `running` job.

## Run it on a phone

Requires Rust 1.92+, JDK 21, Android SDK platform 36 and **NDK 28.1.13356709**, plus
`cargo-ndk 4.1.2`.

```sh
android/build-rust.sh          # cross-compiles libpocket.so for arm64-v8a and x86_64
cd android && ./gradlew installDebug
```

Open the app once so the host starts, then share text from any other app. The first
share works too: it retries until the host is ready, because a share can launch the
process before Rivium has finished starting.

Two buttons in the app post to the same endpoint a share uses, so you can exercise
everything without leaving the app or touching a terminal: **Try a sample**, and
**Slow job (6s)** for watching recovery.

```sh
adb forward tcp:8787 tcp:8787  # optional: poke the phone's queue from the laptop
curl -s localhost:8787/readyz
```

## What survives what

| Situation | Result |
| --- | --- |
| App closed normally | Job stays on disk, finishes on next launch |
| `am force-stop` mid-job | Row stays `running`; next launch reclaims it, `attempts` → 2 |
| Process killed mid-job | Same — the row is on disk, not in memory |
| Job fails | Retried up to 5 attempts with backoff; the row says `failed` and why |
| Job cannot succeed (empty text) | Recorded as `failed` immediately, not retried pointlessly |
| Shutdown with a job running | Listener stops, worker drains, exit code 0 |


## What happens if I kill it

The claim is that nothing is lost. Three commands show it on a phone:

```sh
adb forward tcp:8787 tcp:8787
ID=$(curl -s -X POST localhost:8787/jobs -H 'content-type: application/json' \
  -d '{"text":"kill me mid job","work_ms":8000}' | python3 -c 'import json,sys;print(json.load(sys.stdin)["id"])')
curl -s localhost:8787/jobs/$ID          # wait for "status":"running"
adb shell am force-stop dev.kouragita.pocket
adb shell am start -n dev.kouragita.pocket/.MainActivity
curl -s localhost:8787/jobs/$ID          # now "done", "attempts":2
```

What that produced on the phone:

```text
claimed after ~900ms:   running | attempts 1
force-stop → process gone; row pulled from the device database: running|1
relaunch → recovered after 9s → done | attempts 2
```

In the app the same job renders as `done · recovered · attempt 2`. That line is the
whole point: it exists because a worker died and the queue kept the promise, and no
ordinary to-do app can show it.

## When a job cannot succeed

The failure path is recorded rather than assumed. With the single worker busy on a slow
job, submit text that is only whitespace:

```text
before   pending | attempts 0 | result None
after    failed  | attempts 1 | result {"error":"nothing to describe"}
```

It is not retried, because retrying cannot turn whitespace into something to describe,
and the row says so instead of sitting at `running` forever.

## On a real phone

Tested on a TECNO KM7k running Android 15 (API 35), arm64-v8a. Rivium's own Android
lane only compiles the library and inspects its ELF properties, so these are device
observations rather than a repeat of CI.

- The APK ships `arm64-v8a` and `x86_64`; the phone installed **only** `arm64`
  (`primaryCpuAbi=arm64-v8a`, `secondaryCpuAbi=null`), so the arm64 build is the one that
  actually ran.
- The host was ready about **1 s** after launch, and a shared note finished in about
  **0.5 s**.
- `am force-stop` during a 6 s job left the row `running` on disk. Relaunching reclaimed
  it after about **4.5 s** and finished with `attempts = 2`.
- The first cold start showed an empty list even though the queue had jobs: the list was
  fetched before the listener existed and never retried. Fixed by refreshing when the host
  reports ready and again in `onResume`. The emulator hid this because every test there
  began with a share.
- This phone's software build has no `sqlite3` binary, unlike the emulator image, so
  reading the database meant copying it off the device with `run-as ... cat` and querying
  it locally.

## Versions

Pinned exactly; `Cargo.lock` is committed.

| Component | Version |
| --- | --- |
| `rivium`, `rivium-host`, `rivium-http`, `rivium-jni` | 0.2.0 |
| `apalis`, `apalis-sqlite` | 1.0.0 |
| `sqlx` (sqlite, bundled) | 0.9.0 |
| Rust / Cargo | 1.99.1 (MSRV 1.92) |
| Android NDK | 28.1.13356709 |
| cargo-ndk | 4.1.2 |
| JDK / Gradle / AGP | 21.0.13 / 9.8.2 / 9.4.1 |
| minSdk / compileSdk | 30 / 36 |
| ABIs | `arm64-v8a`, `x86_64` |

## Known limits

- Tested on one physical phone (TECNO KM7k, Android 15, arm64-v8a) and one x86_64
  emulator (API 36). Nothing else was tried.
- Delivery is **at-least-once**. The handler is idempotent, so a retry overwrites the
  same row rather than duplicating results.
- `apalis-sqlite` sets `PRAGMA synchronous = OFF`. Jobs survive process death, but not
  necessarily sudden power loss. That is a deliberate trade in the library, not a
  choice made here.
- The listener is **loopback only** and unauthenticated; it is not reachable from
  another device.
- No foreground service: the app is allowed to die, which is exactly the behaviour the
  persistence story depends on.
- The notification carries **Copy hash** and **Share result**. Copy hash was tapped on the
  phone and the receiver logged `copied hash f3f9b7f5e7e47110` — the full sixteen
  characters. Share result is attached and visible in the notification, but its tap was
  not captured.
- One job type, by design.

## Layout

```text
src/app.rs      App impl: two supervised services, the shared database handle
src/queue.rs    SQLite storage, migrations, job_view, unique worker names
src/job.rs      the job, its result, and the Apalis handler
src/http.rs     POST /jobs, GET /jobs, GET /jobs/{id}
src/main.rs     laptop entry point (process host)
src/lib.rs      JNI export for Android (embedded host)
android/        Kotlin app: share sheet, notification, job list
```

## Contact

Questions, corrections or review comments: [@kouragitamaster](https://x.com/kouragitamaster)
on X.
