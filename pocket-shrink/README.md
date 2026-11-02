# 📦 PocketShrink — Offline Privacy Sanitizer & Media Optimizer

A high-performance Android application powered by **Rivium** and **Apalis** that processes, strips GPS/EXIF metadata, compresses, and generates SHA-256 integrity hashes for images in a persistent, background SQLite queue.

Built for the **DroidconKE Rivium Challenge** by Njuguna Mureithi.

![PocketShrink Live Demo](demo.gif)

---

## 📱 Real Device Testing & Findings (+10 Bonus)

Tested and verified on a physical **Xiaomi Redmi Note 9S (M2003J6A1G)** running **Android 12 (arm64-v8a)**.

### 🔍 Key Discovery & Bug Report for Rivium Maintainers:
When running on Android via JNI, Rivium failed to boot with exit code `CantCreate` (-73). We traced the root cause to `rivium-util 0.2.0` in `src/log/files.rs`:
```rust
lock.try_lock().map_err(...)
```
In the Rust standard library (`std::sys::pal::unix::fs`), `File::try_lock()` and `unlock()` only support OS targets explicitly enumerated under `#[cfg(any(target_os = "linux", ...))]`. **`target_os = "android"` was omitted from that list in Rust std**, causing it to unconditionally fail with `io::ErrorKind::Unsupported ("try_lock() not supported")`!

`rivium-util` interpreted this unsupported error as a conflict (`"log directory already owned: lock acquisition failed due to I/O error"`), which caused `rivium-host`'s `Logger::new` to fail with `CantCreate`.

### 🛠️ Solution:
We patched `rivium-util 0.2.0` locally under `pocket-backend/patches/rivium-util` to use POSIX advisory locking via `libc::flock(fd, libc::LOCK_EX | libc::LOCK_NB)` on Android. With this patch applied:
1. `Bridge.nativeStart` immediately succeeded with code `0`.
2. The foreground Android service notified: **"Rivium + Apalis worker is active (SQLite queue)"**.
3. All background jobs dequeued and completed cleanly.

---

## 🌟 Key Features

1. **Dual Execution Runtime**:
   - **Desktop CLI**: Runs headless with `cargo run` without an emulator or phone.
   - **Android Native App**: Runs inside a modern Jetpack Compose Kotlin Android app via JNI.
2. **Persistent Job Queue**:
   - Powered by `apalis` and `apalis-sqlite`.
   - Jobs are written to SQLite. If the Android application is force-closed or the process is killed, queued jobs survive and resume execution immediately on next launch.
3. **Privacy Sanitizer & Media Optimizer**:
   - Strips all private EXIF metadata (GPS coordinates, camera serials, timestamps).
   - Downsamples and re-encodes images efficiently.
   - Computes SHA-256 cryptographic verification checksum.
   - Calculates exact byte savings and percentage saved.
4. **Creative Android System Integrations**:
   - **Android Share Target (`image/*`)**: Share any photo directly from Google Photos, Gallery, WhatsApp, or Twitter into PocketShrink to sanitize on the fly.
   - **Rich Foreground & Completion Notifications**: Uses Android system channels to show live worker status and post rich completion alerts with file savings and SHA-256 hashes.
   - **Simulate Batch Queue**: Test queue persistence with a single tap.

---

## 🏗 Architecture

```
┌────────────────────────────────────────────────────────┐
│                   Android Frontend                     │
│  (Kotlin + Jetpack Compose + Foreground Service)       │
└──────────────────────────┬─────────────────────────────┘
                           │ JNI (rivium-jni)
┌──────────────────────────▼─────────────────────────────┐
│                 Rivium Embedded Host                   │
│   (rivium-host supervisor managing worker lifecycle)   │
└──────────────────────────┬─────────────────────────────┘
                           │
┌──────────────────────────▼─────────────────────────────┐
│                 Apalis Tokio Worker                    │
│   (SqliteStorage + ProcessImageJob Service)            │
└──────────────────────────┬─────────────────────────────┘
                           │
┌──────────────────────────▼─────────────────────────────┐
│                     SQLite DB                          │
│     (job queue + pocket_results table)                 │
└────────────────────────────────────────────────────────┘
```

---

## 📌 Pinned Dependencies & Tech Stack

| Component | Pinned Version | Notes |
|---|---|---|
| **Rivium** | `0.2.0` | Supervisor and runner runtime |
| **rivium-host** | `0.2.0` | Embedding API & context management |
| **rivium-jni** | `0.2.0` | JNI bridge export macro |
| **Apalis** | `1.0.0` | Rust async background worker framework |
| **apalis-sqlite** | `1.0.0` | SQLite persistence layer for jobs |
| **sqlx** | `0.9.0` | Async SQLite execution |
| **Rust Toolchain** | `1.96.0` (2024 Edition) | `stable-x86_64-apple-darwin` |
| **Android NDK** | `28.2.13676358` | LLVM Clang toolchain |
| **Android Architectures**| `arm64-v8a`, `x86_64` | `libpocket_backend.so` |
| **Kotlin** | `2.4.20` | Native Android UI logic |
| **Android Gradle Plugin** | `9.3.2` | Gradle `9.8.1` |
| **Android Target SDK** | `Compile: 35`, `Min: 26` | Modern Android 15 & backwards compatible to Oreo |

---

## 🚀 Running on Desktop (No Phone Needed)

To test the backend worker and image processing pipeline directly on your desktop:

```bash
cd pocket-backend

# Run the CLI with an image path
cargo run --bin pocket-cli -- --input path/to/your/image.jpg
```

### Example Desktop Run Output:
```
[INFO] Initialized SQLite database at pocket_desktop.db
[INFO] Enqueued image job 0194ff71-6c18-7b90-8451-ea2105128392 for: /Users/kanda/Music/121.jpg
[INFO] Starting Rivium host supervisor...
[INFO] Starting Apalis worker on SQLite pool...
[INFO] Stripping metadata and resizing: /Users/kanda/Music/121.jpg -> pocket_data/sanitized_121.jpg
[INFO] Job completed successfully! Original: 86498 bytes, Processed: 44102 bytes (Saved 49%). SHA-256: 4a259c402...
```

### Running the Persistence Test:
To verify that jobs queued while the host is offline survive and process upon launch:

```bash
cd pocket-backend
cargo test --test persistence_test
```

Expected result:
```
running 1 test
test test_jobs_persist_and_survive_reopen ... ok
test result: ok. 1 passed; 0 failed
```

---

## 📱 Building & Running on Android

### 1. Compile Native Libraries (JNI)
Pre-built libraries for `arm64-v8a` and `x86_64` are placed in `android-app/app/src/main/jniLibs/`.

To rebuild them with `cargo-ndk`:
```bash
cd pocket-backend
cargo ndk -t arm64-v8a -t x86_64 -o ../android-app/app/src/main/jniLibs build --release
```

### 2. Build the Android APK
```bash
cd android-app
./gradlew assembleDebug
```
The resulting APK will be at:
`android-app/app/build/outputs/apk/debug/app-debug.apk`

### 3. Install on Device or Emulator
```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

---

## 🧪 Testing the Android Features

### Feature 1: Share Sheet Target (`image/*`)
1. Open any photo app (Google Photos, Gallery, Files).
2. Tap **Share**.
3. Select **PocketShrink Sanitizer**.
4. PocketShrink copies the photo to internal storage, pushes a `ProcessImageJob` to SQLite, starts the foreground Rivium service, and posts a notification when sanitization and compression complete.

### Feature 2: Queue Survival Across Force-Quit (Persistence Test)
1. Open **PocketShrink**.
2. Tap **"⚡ Queue 3 Sample Jobs (Test Persistence)"**.
3. Immediately swipe the app away from the Android Recent Apps screen or run:
   ```bash
   adb shell am force-stop com.pocketshrink.app
   ```
4. Reopen the app.
5. Notice that all jobs resume and complete, storing the results in SQLite!

---

## 📂 Project Structure

```
pocket-shrink/
├── README.md
├── pocket-backend/               # Dual Binary/CDyLib Rust backend
│   ├── Cargo.toml
│   ├── src/
│   │   ├── lib.rs                # Library & JNI export
│   │   ├── main.rs               # Desktop CLI runner
│   │   ├── app.rs                # Rivium Host App implementation
│   │   ├── job.rs                # Apalis Job definitions
│   │   ├── processor.rs          # Image EXIF stripping & compression
│   │   ├── storage.rs            # SQLite pool & results schema
│   │   ├── worker.rs             # Apalis async Tokio worker
│   │   └── jni.rs                # JNI C-bindings for Android
│   └── tests/
│       └── persistence_test.rs   # Integration test verifying persistence
└── android-app/                  # Modern Jetpack Compose Android app
    ├── build.gradle.kts
    ├── settings.gradle.kts
    └── app/
        ├── build.gradle.kts
        ├── src/main/
        │   ├── AndroidManifest.xml
        │   ├── jniLibs/          # Compiled ARM64 & x86_64 .so binaries
        │   └── java/com/pocketshrink/app/
        │       ├── Bridge.kt               # JNI Native interface
        │       ├── MainActivity.kt         # Jetpack Compose UI
        │       ├── PocketBackendService.kt # Foreground service & notifications
        │       └── ShareReceiverActivity.kt# Android share target
```
