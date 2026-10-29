# imageq

**Strip photo metadata before sharing.**

## What Problem Does This Solve?

**The Problem:** Every time you take a photo with your phone, it quietly bakes invisible data; like your exact GPS coordinates, when the photo was taken, and your device model directly into the image file. When you text or share that photo online, anyone can extract that private information.

**The Solution:** `imageq` sits quietly in your phone's share sheet. When you share a photo to it, an embedded background worker cleans the image by re-encoding it into a fresh JPEG, completely erasing all hidden metadata, and gives you a safe, private copy ready to send.

---

## Demos & Screenshots

* **Metadata Cleanup:** Before and after comparison showing stripped GPS and device metadata.
* **Crash Resilience:** App killed mid-processing, reopened, and seamlessly resuming remaining jobs.
* **Terminal output (`cargo run`):** `photo cleaned ... gps_removed=true`
* **Test Device:** Running on Android 14 (Samsung Galaxy A13).

---

## How It Works

* **Background Processing:** Uses an [Apalis](https://github.com/apalis-dev/apalis) SQLite queue (max 3 retries per photo).
* **Cross-Platform Engine:** The core Rust worker runs via [Rivium](https://github.com/rivium-rs/rivium) (`photo-worker`). It runs natively on desktop via `rivium-host` or inside the Android app using JNI (`rivium_jni`). Kotlin initializes the engine with `Bridge.nativeStart(["--root", filesDir])`.
* **Android Integration:** When you share images via `ACTION_SEND` or `SEND_MULTIPLE`, Kotlin copies the files into `files/inbox/` (`name.part`, then renames on completion) since Rust cannot read Android `content://` URIs directly. A background worker polls the inbox every 2 seconds to enqueue jobs.
* **Output & State:** Processed photos end up in `clean/<hash>.jpg` and `thumbs/<hash>.jpg`, with job details stored in a SQLite database (`cleaned` table). The Kotlin frontend reads this database to render the UI and fire progress notifications via an Android Foreground Service.
* **Persistence:** Because job states live in a local SQLite file, interrupting or force-closing the app won't lose work—it resumes right where it left off when reopened.

---

## Local Development (Desktop)

Requires Rust 1.92+.

```bash
cd rust
cargo run --release --bin imageq -- --root target/demo-root

# In a second terminal (or copy manually on Windows):
cp photo.jpg target/demo-root/inbox/

```

> **Tip:** Use a photo with location services enabled to test metadata removal. Processed outputs will appear in `target/demo-root/clean/`, logs in `logs/imageq/imageq.log`, and state entries in `imageq.db`.

---

## Android Build

```bash
# Add targets and toolchain
rustup target add armv7-linux-androideabi aarch64-linux-android
cargo install cargo-ndk

# Build native libs
cd rust
cargo ndk -t armeabi-v7a -t arm64-v8a -o ../android/app/src/main/jniLibs build --release --lib

```

1. Open `android/` in Android Studio.
2. Build and deploy to your test device.
3. Grant notification permissions, then test by sharing photos from your Gallery app.

> **Note:** The Android package name must remain `com.example.imageq` to match the JNI export configuration (`export!`).

---

## Tested Environment & Dependencies

| Component | Version / Environment |
| --- | --- |
| **Rust** | 1.99.1 |
| **Rivium Suite** | 0.2.0 (`rivium`, `rivium-host`, `rivium-jni`) |
| **Apalis Engine** | 1.0.0 (`apalis`, `apalis-sqlite`) |
| **Libraries** | `sqlx` 0.9 / `image` 0.25.10 / `kamadak-exif` 0.6 |
| **Android Toolchain** | `cargo-ndk` 4.1.2 / NDK 30.0.16248370 / `compileSdk` 37 |
| **Desktop Host** | Windows, `stable-x86_64-pc-windows-gnu` (MinGW GCC 15.2) |
| **Test Device** | Samsung Galaxy A13 (Android 14, 32-bit system ABI) |

---

## Real-Device Testing Notes

* **JNI Stability:** The Rivium JNI lifecycle (start, status queries, graceful stop) works reliably on physical devices.
* **Process Interruption:** Force-closing mid-batch causes no data loss; incomplete jobs are picked back up automatically upon re launch.
* **File Size Efficiency:** In testing, re-encoding at 90 JPEG quality reduced output file sizes by ~13–27% while removing metadata.
