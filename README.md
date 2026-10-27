# TaskPing

TaskPing is an Android demo that submits jobs from Kotlin to a Rust worker. The Rust worker uses Rivium to manage its lifecycle and Apalis with SQLite to store and process jobs.

This repository contains both the Android application and the Rust library/binaries used by the application.

## What the application does

The app has two job flows:

- **Quick task:** submits `DemoJob::Add { a, b }` and waits for the Rust worker to return `a + b`.
- **Long task:** submits `DemoJob::Table { base, upto }`. The worker processes one step per second, records progress in SQLite, and returns the sum of `base x 1` through `base x upto`.

The Android UI starts the Rust host with the app's private files directory as its root. The SQLite database is stored at:

```text
<app files directory>/taskping.db
```

Long-job IDs are also saved in Android `SharedPreferences` so the UI can recover and poll jobs after an app restart. While unfinished long jobs exist, `TaskPingService` runs as a foreground service and keeps the process available for the Rust worker. Android may still stop or restrict services; this is not a guarantee of execution under every operating-system condition.

## Repository layout

```text
android/                         Android application
android/app/src/main/...         Kotlin UI, JNI declarations, service, notifications
rust/src/lib.rs                  Job types, SQLite storage, worker, and Rivium app
rust/src/jni_bridge.rs           JNI entry points used by Kotlin
rust/src/main.rs                 Laptop Rivium host
rust/src/bin/submit.rs           Laptop command-line client
build-android.sh                 Builds native libraries and the debug APK
```

## Rust implementation

`rust/src/lib.rs` defines two job variants:

```text
Add   { a, b }       -> a + b
Table { base, upto } -> base * upto * (upto + 1) / 2
```

`Table` progress is stored in the `taskping_progress` SQLite table. The Apalis result is stored by `apalis-sqlite`. The worker is owned by the Rivium service named `apalis-worker` and stops when Rivium stops that service.

The JNI bridge exposes these Android methods:

- `nativeStart`, `nativeStop`, `nativeStatus`, `nativeLastError`, and `nativeVersion` for the Rivium lifecycle.
- `nativeRunDemoJob` to submit and wait for an `Add` job.
- `nativeSubmitTable` to submit a `Table` job and return its ID.
- `nativeCheck` to read `pending`, `running:step/total`, `done:value`, or `failed:error`.

## Rust prerequisites

- Rust toolchain meeting `rust-version = "1.92"` in `rust/Cargo.toml`.
- A working C/C++ linker for the host platform. On Windows, the MSVC toolchain requires Visual Studio or Visual Studio Build Tools with the **Desktop development with C++** workload, including `link.exe`.
- Network access the first time Cargo downloads crates, unless all dependencies are already cached.

Run the Rust commands from `rust/`:

```powershell
cargo fmt --all -- --check
cargo test
```

There are currently no Rust test functions in the source tree, so `cargo test` primarily verifies that the crate and its test targets compile successfully.

Run the laptop worker and client in separate terminals:

```powershell
# Terminal 1
cargo run -- run --root target/demo

# Terminal 2: submit an Add job and wait for its result
cargo run --bin submit -- target/demo add 2 3

# Submit a long job; the command prints its job ID
cargo run --bin submit -- target/demo table 10 30

# Check a long job by ID
cargo run --bin submit -- target/demo check JOB_ID
```

The database is created under the root passed with `--root`. Stop the worker with Ctrl-C. A job that remains in the SQLite queue can be picked up when the worker starts again.

## Android prerequisites

- Android SDK with the compile SDK used by the project (`compileSdk = 37`).
- Android NDK 30.0.16248370, or an installed NDK version configured consistently with the build.
- Java/Gradle support for the Android project. The project uses Gradle 9.1.0, Android Gradle Plugin 9.0.1, Kotlin 2.3.20, and Java 17 source/target compatibility.
- `cargo-ndk` and the Android Rust targets when rebuilding the native libraries.
- An Android device or emulator for installation and runtime testing.

Install the Rust Android targets and `cargo-ndk` according to your platform, then configure `ANDROID_NDK_HOME`. The default path in `build-android.sh` is written for a macOS Android SDK location; on Windows or Linux, set `ANDROID_NDK_HOME` explicitly before running the script.

Build the native libraries and debug APK:

```sh
./build-android.sh
```

On Windows, the Gradle portion can be run directly from PowerShell:

```powershell
cd android
.\\gradlew.bat assembleDebug
```

The APK is produced at:

```text
android/app/build/outputs/apk/debug/app-debug.apk
```

Install and launch it with an attached device or running emulator:

```sh
adb install -r -g android/app/build/outputs/apk/debug/app-debug.apk
adb shell am start -n com.taskping.app/.MainActivity
```

On Android 13 and later, the app requests notification permission. The quick-task completion notification and long-task completion notification are not shown if that permission is denied, but the UI can still display job results.

## Verification status

The following checks were run while preparing this README:

- Android `assembleDebug`: **passed** using the existing native libraries in `android/app/src/main/jniLibs`.
- Rust `cargo test`: **not completed** in the Windows environment because the MSVC linker `link.exe` was unavailable.
- Rust `cargo fmt --all -- --check`: **failed** because the current Rust sources are not formatted according to rustfmt.
- Android device/emulator behavior: **not verified in this environment**. A successful APK build does not prove that JNI loading, job processing, notifications, or service recovery work on a device.

The Android build also reports a compatibility warning because compile SDK 37 is newer than the SDK range tested by Android Gradle Plugin 9.0.1. It is currently a warning, not a build failure.

## Current limitations

- No automated Rust or Android application tests are checked into the repository.
- A long job runs one step per second and is limited to 5-300 steps by the Android slider; the command-line client does not apply that UI range.
- `nativeRunDemoJob` waits synchronously for its result, so Kotlin calls it on `Dispatchers.IO`, not the main thread.
- The foreground service is used only while long jobs are unfinished. Android lifecycle and background-execution policies can still affect it.
- Notification permission denial is handled by suppressing completion notifications.
- Job failures are displayed as failure state, but the app does not provide a separate retry UI.
- The generated native libraries under `android/app/src/main/jniLibs` are ignored by Git and must be rebuilt when they are absent or stale.
