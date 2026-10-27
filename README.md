# LinkQueue

LinkQueue is an Android link inbox backed by Rust, Apalis, and Rivium. Users can share a URL into the app or enter one manually. The app saves accepted links with Room and submits each link to a durable Apalis SQLite queue. A Rivium-managed Rust worker normalizes HTTP(S) URLs, calculates a SHA-256 hash, and writes a result associated with the job ID. The app reads that result and shows the job status.

## Requirements covered

- Android Share Sheet intake and manual URL entry.
- URL validation, normalization, and SHA-256 processing in Rust.
- Durable Apalis SQLite jobs processed by an Apalis worker managed by Rivium.
- JNI integration that starts the embedded worker, enqueues a URL, and reads its result.
- Room persistence for the Android saved-link list, kept separate from queue and worker-result storage.
- A standalone laptop worker and enqueue command for demonstrating the Rust side without the Android UI.

## Architecture and data

On Android, the native library starts the Rivium embedded host and uses the app-private files directory for the Apalis SQLite database and job result files. Room stores links and their displayed state. The job ID ties a Room entry to its Rust queue result. Queue entries and results survive app process restarts because they are stored in app-private persistent storage.

On a laptop, the standalone Rivium process host and enqueue command use the same local data directory. Android and laptop use separate local databases; they do not share jobs over a network.

## Run the Rust worker on Windows

Install Rust (the project uses edition 2024) and open PowerShell at the repository root. Start the worker:

```powershell
cargo run -- --root target/linkqueue --set data_dir=target/linkqueue
```

In a second PowerShell window, submit a URL to that same directory:

```powershell
cargo run -- enqueue https://example.com target/linkqueue
```

The enqueue command prints the job ID. The worker processes the durable queue and writes each result under `target/linkqueue/results/`. Both commands must use the same data directory. If the worker is stopped while jobs are pending, start it again with the same directory to resume processing.

The data directory can also be selected with `LINKQUEUE_DATA_DIR` when using the default path. Do not use a different directory for the worker and enqueue command if they are meant to share a queue.

## Build and run Android on Windows

Open the `android/` directory in Android Studio, or use PowerShell from that directory. Install the Android SDK platform and NDK configured in Android Studio. Install `cargo-ndk` and both Rust Android targets:

```powershell
cargo install cargo-ndk --locked
rustup target add aarch64-linux-android x86_64-linux-android
```

The Gradle `buildRustAndroid` task uses the configured SDK/NDK and packages Rust libraries for `arm64-v8a` phones and `x86_64` emulators as part of the normal app build.

```powershell
cd android
.\gradlew.bat assembleDebug
```

Install `app\build\outputs\apk\debug\app-debug.apk` on a supported device or emulator. Share a URL from another app or enter one in LinkQueue. The app displays its saved link and processing result. Android Studio can also open `android/` directly and run the `app` configuration.

## Versions

Rust dependency versions are locked in `Cargo.lock`. The core integration uses Apalis 1.0.0, Apalis SQLite 1.0.0, Rivium 0.2.0, and Rivium JNI 0.2.0. The Android version catalog is in `android/gradle/libs.versions.toml`; it currently configures Android Gradle Plugin 9.4.1, Gradle 9.6.0, Kotlin 2.2.10, Room 2.8.4, and Android API 37.

## Verification

The following have been verified for this project:

- `cargo test` and `cargo build` pass on Windows.
- The laptop enqueue/worker flow processes a URL and persists its result.
- The Android `assembleDebug` task completes with the Rust Android build enabled. The APK packages the native Rust library for the configured Android ABIs.

For a functional check, submit a valid HTTP(S) URL and confirm the normalized URL and hash appear. Also check malformed or unsupported URLs, duplicate submission, Share Sheet intake, and saved links after app restart. Device/emulator runtime testing is separate from a successful build; use a device ABI matching one of the packaged libraries. The challenge submission also asks for a short video or GIF showing the app working; capture it from the final device demonstration.
