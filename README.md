# docq

**Convert Word, PowerPoint, and Excel documents to PDF in the background.**

## What Problem Does This Solve?

**The Problem:** Mobile document conversion is often slow, unreliable, and heavily reliant on external third-party APIs that compromise document privacy. Processing large office documents (`.docx`, `.pptx`, `.xlsx`) locally on-device can easily lock up the main UI thread or get killed by the OS mid-process if the user switches apps.

**The Solution:** `docq` provides an embedded background worker powered by Rust and Typst (`office2pdf`). You can share office files directly to the app, where an Apalis SQLite queue processes them asynchronously into clean PDFs—completely offline and crash-resilient.

---

## Demos & Screenshots

- **Full Workflow Demo:** Converting documents on desktop and Android, including force-stop crash resilience mid-batch.

[INSERT DEMO VIDEO/GIF HERE]

- **Terminal Output (`cargo run`):** `converted to pdf ... source=report.docx bytes=120482 ms=342`

- **Test Device:** Running on Android 14 (Samsung Galaxy A13).

---

## How It Works

- **Background Processing:** Uses an [Apalis](https://github.com/apalis-dev/apalis) SQLite queue (max 3 retries per document).

- **Cross-Platform Engine:** The core Rust worker runs via [Rivium](https://github.com/rivium-rs/rivium) (`doc-worker`). It runs natively on desktop via `rivium-host` or inside the Android app using JNI (`rivium_jni`). Kotlin initializes the engine with `Bridge.nativeStart(["--root", filesDir])`.

- **Pure Rust Conversion:** Uses `office2pdf` under the hood (powered by Typst) to compile `.docx`, `.pptx`, and `.xlsx` files into standard PDFs without needing an external LibreOffice or system dependency.

- **Android Integration:** When you share documents via `ACTION_SEND` or `SEND_MULTIPLE`, Kotlin copies the files into `files/inbox/` (`name.part`, then renames on completion) since Rust cannot read Android `content://` URIs directly. A background worker polls the inbox every 2 seconds to enqueue jobs.

- **Output & State:** Processed PDFs end up in `pdfs/<stem>.pdf`, with job details stored in a SQLite database (`converted` table). The Kotlin frontend reads this database to render the UI and fire progress notifications via an Android Foreground Service.

- **Persistence & Crash Resilience:** Because job states live in a local SQLite database, interrupting or force-closing the app mid-batch won't lose work—incomplete jobs seamlessly resume right where they left off when re-opened.

---

## Local Development (Desktop)

Requires Rust 1.92+.

```bash
# Start the background worker
cargo run --release --bin docq -- --root target/demo-root

# In a second terminal, enqueue a document (.docx, .pptx, or .xlsx):
cargo run --release --bin enqueue -- --root target/demo-root .\report.docx

```

> **Tip:** Processed PDFs will appear in `target/demo-root/pdfs/`, and state entries in `docq.db`. You can inspect the jobs table directly via SQLite:
> 
> `sqlite3 target/demo-root/docq.db "SELECT * FROM converted;"`

## Android Build

Bash

```
# Add targets and toolchain
rustup target add armv7-linux-androideabi aarch64-linux-android
cargo install cargo-ndk

# Build native libs
cd rust
cargo ndk -t armeabi-v7a -t arm64-v8a -o ../android/app/src/main/jniLibs build --release --lib

```

1.  Open `android/` in Android Studio.
    
2.  Build and deploy to your test device.
    
3.  Grant notification permissions, then test by sharing `.docx`,  `.pptx`, or `.xlsx` files from any file manager.
    

> **Note:** The Android package name must remain `com.example.imageq` (or update accordingly) to match the JNI export configuration (`export!`).

## Tested Environment & Dependencies

**Component**

**Version / Environment**

**Rust**

1.99.1

**Rivium Suite**

0.2.0 (`rivium`, `rivium-host`, `rivium-jni`)

**Apalis Engine**

1.0.0 (`apalis`, `apalis-sqlite`)

**Document Engine**

`office2pdf` 0.9 (Typst-based pure Rust converter)

**Libraries**

`sqlx` 0.9 / `tokio` 1.x / `tracing`

**Android Toolchain**

`cargo-ndk` 4.1.2 / NDK 30.0.16248370 / `compileSdk` 37

**Desktop Host**

Windows, `stable-x86_64-pc-windows-gnu` (MinGW GCC 15.2)

**Test Device**

Samsung Galaxy A13 (Android 14, 32-bit system ABI)

## Real-Device Testing Notes

-   **JNI Stability:** The Rivium JNI lifecycle (start, status queries, graceful stop) works reliably on physical hardware.
    
-   **Batch Resiliency:** Force-stopping mid-batch causes zero data loss; remaining conversion tasks pick right back up upon relaunch.
    
-   **Pure-Rust Engine:** Converting Word files on-device avoids network latency and third-party API exposure while producing native PDF outputs.
