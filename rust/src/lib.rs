//! Mchoro: photos into hand-drawn art, inked in their own colours.
//!
//! How it fits together:
//!
//! ```text
//!  Android (Share Sheet / Photo Picker)      Rust (this crate, run by Rivium)
//!  ------------------------------------      ---------------------------------------------
//!  copy photo  -> <root>/inbox/<id>.jpg  ->  inbox scanner moves it to pending/ and pushes
//!                                            a SketchPhoto job into SQLite (jobs.db)
//!                                            Apalis worker draws it + builds its palette
//!  app shows it <- <root>/outbox/<id>.json  <- ink mask <id>.png + M3 schemes
//! ```
//!
//! Rivium owns start/stop (on a laptop via `cargo run`, on Android via JNI).
//! Apalis owns the job queue. Because jobs live in SQLite, anything not yet done
//! when the app is closed is picked up again the next time it starts.

pub mod palette;
pub mod sketch;

use std::path::{Path, PathBuf};
use std::time::Duration;

use apalis::prelude::*;
use apalis_sqlite::{SqliteConnectOptions, SqlitePool, SqlitePoolOptions, SqliteStorage};
use image::ImageReader;
use rivium::{Error, Kind, Result, Service, service};
use rivium_host::{App, Context};
use serde::{Deserialize, Serialize};

/// Settings, changeable with `--set max_side=1200 --set demo_delay=5000`.
#[derive(Clone, Debug, Serialize, Deserialize)]
#[serde(default)]
pub struct Config {
    /// Longest side of the drawing, in pixels.
    pub max_side: u32,
    /// Milliseconds to wait before each job (for demos). 0 = no delay.
    pub demo_delay: u64,
    /// Also write `<id>.preview.png`, the sketch inked in its own colours (laptop testing).
    pub write_preview: bool,
}

impl Default for Config {
    fn default() -> Self {
        Self {
            max_side: 1600,
            demo_delay: 0,
            write_preview: false,
        }
    }
}

/// The one job type: "draw this photo".
#[derive(Clone, Debug, Serialize, Deserialize)]
pub struct SketchPhoto {
    /// File name stem, also used for the output names.
    pub id: String,
    /// Where the original waits while the job is queued.
    pub source: PathBuf,
}

/// What the phone reads back from `outbox/<id>.json`.
#[derive(Debug, Serialize, Deserialize)]
pub struct SketchResult {
    pub id: String,
    pub width: u32,
    pub height: u32,
    /// The ink mask: white pixels whose alpha is the amount of ink.
    pub sketch: String,
    #[serde(flatten)]
    pub palette: palette::Palette,
}

/// Shared settings handed to every job through `Data<Settings>`.
#[derive(Clone, Debug)]
struct Settings {
    outbox: PathBuf,
    max_side: u32,
    demo_delay: u64,
    write_preview: bool,
}

pub struct Mchoro;

impl App for Mchoro {
    const NAME: &'static str = "mchoro";
    const VERSION: &'static str = env!("CARGO_PKG_VERSION");
    type Config = Config;

    fn validate(config: &Config) -> Result<()> {
        if config.max_side < 64 {
            return Err(Error::invalid("max_side must be at least 64"));
        }
        Ok(())
    }

    fn services(config: &Config, context: &Context<Config>) -> Result<Vec<Box<dyn Service>>> {
        let root = context.paths().root().to_path_buf();
        let config = config.clone();
        Ok(vec![service("sketch-worker", Kind::Frontline, move |ctx| {
            run(ctx, root, config)
        })])
    }
}

async fn run(ctx: rivium::Context, root: PathBuf, config: Config) -> Result<()> {
    let inbox = root.join("inbox");
    let pending = root.join("pending");
    let outbox = root.join("outbox");
    for dir in [&inbox, &pending, &outbox] {
        std::fs::create_dir_all(dir)?;
    }

    let pool = open_db(&root.join("jobs.db")).await?;
    let storage = SqliteStorage::<SketchPhoto>::new(&pool).with_config(
        apalis_sqlite::Config::default()
            .queue("sketch-photo")
            .heartbeat_interval(Duration::from_secs(5))
            .batch_size(1),
    );

    let settings = Settings {
        outbox,
        max_side: config.max_side,
        demo_delay: config.demo_delay,
        write_preview: config.write_preview,
    };
    // A new name each run, so a quick restart isn't mistaken for a duplicate worker.
    let worker_name = format!("sketch-worker-{}", std::process::id());
    let worker = WorkerBuilder::new(worker_name)
        .backend(storage.clone())
        .data(settings)
        .concurrency(1) // one photo at a time: decoding big photos is memory hungry
        .retry(RetryPolicy::retries(3))
        .build(sketch_photo);

    ctx.ready();
    log(&format!("ready, watching {}", inbox.display()));

    // The worker stops (after finishing the current photo) when Rivium asks us to stop.
    let stop = ctx.clone();
    let worker = worker.run_until(async move {
        stop.stopped().await;
        Ok::<(), std::io::Error>(())
    });
    let scanner = scan_inbox(&ctx, &inbox, &pending, storage);

    // If the worker dies, stop scanning too, instead of queuing photos nobody will process.
    let worker_result = tokio::select! {
        result = worker => result,
        () = scanner => Ok(()),
    };
    pool.close().await;
    worker_result.map_err(|e| Error::internal(format!("worker failed: {e}")))?;
    log("stopped");
    Ok(())
}

async fn open_db(path: &Path) -> Result<SqlitePool> {
    let options = SqliteConnectOptions::new()
        .filename(path)
        .create_if_missing(true);
    let pool = SqlitePoolOptions::new()
        .connect_with(options)
        .await
        .map_err(|e| Error::internal(format!("open {}: {e}", path.display())))?;
    SqliteStorage::setup(&pool)
        .await
        .map_err(|e| Error::internal(format!("set up job tables: {e}")))?;
    Ok(pool)
}

/// Every half second, turn new files in `inbox/` into queued jobs.
///
/// The phone writes `<id>.part` and renames it when the copy is complete, so we
/// never pick up a half-written photo.
async fn scan_inbox(
    ctx: &rivium::Context,
    inbox: &Path,
    pending: &Path,
    mut storage: SqliteStorage<SketchPhoto>,
) {
    loop {
        if let Ok(entries) = std::fs::read_dir(inbox) {
            for entry in entries.flatten() {
                let path = entry.path();
                if !path.is_file() || path.extension().is_some_and(|e| e == "part") {
                    continue;
                }
                let Some(name) = path.file_name().map(|n| n.to_string_lossy().into_owned()) else {
                    continue;
                };
                let id = Path::new(&name)
                    .file_stem()
                    .map(|s| s.to_string_lossy().into_owned())
                    .unwrap_or_else(|| name.clone());
                let source = pending.join(&name);
                if let Err(e) = std::fs::rename(&path, &source) {
                    log(&format!("could not move {name}: {e}"));
                    continue;
                }
                let job = SketchPhoto { id: id.clone(), source: source.clone() };
                match storage.push(job).await {
                    Ok(()) => log(&format!("queued {id}")),
                    Err(e) => {
                        log(&format!("could not queue {id}: {e}"));
                        let _ = std::fs::rename(&source, &path); // try again next scan
                    }
                }
            }
        }
        tokio::select! {
            () = ctx.stopped() => return,
            () = tokio::time::sleep(Duration::from_millis(500)) => {}
        }
    }
}

/// The job handler Apalis calls for each `SketchPhoto`.
async fn sketch_photo(job: SketchPhoto, settings: Data<Settings>) -> std::result::Result<(), BoxDynError> {
    let settings = (*settings).clone();
    let id = job.id.clone();
    if settings.demo_delay > 0 {
        tokio::time::sleep(Duration::from_millis(settings.demo_delay)).await;
    }
    // Image work is CPU heavy, so keep it off the async threads.
    let result = tokio::task::spawn_blocking(move || draw(&job, &settings)).await??;
    log(&format!(
        "drew {id}: {}x{}, seed {}",
        result.width, result.height, result.palette.swatches[0].seed
    ));
    Ok(())
}

fn draw(job: &SketchPhoto, settings: &Settings) -> std::result::Result<SketchResult, BoxDynError> {
    let img = ImageReader::open(&job.source)?.with_guessed_format()?.decode()?;
    let img = if img.width().max(img.height()) > settings.max_side {
        img.resize(settings.max_side, settings.max_side, image::imageops::FilterType::Triangle)
    } else {
        img
    };

    let palette = palette::from_image(&img);
    let mask = sketch::pencil(&img);

    // Write to temp names first, so the phone never sees half a file.
    let sketch_path = settings.outbox.join(format!("{}.png", job.id));
    write_atomically(&sketch_path, |tmp| mask.save_with_format(tmp, image::ImageFormat::Png).map_err(Into::into))?;

    if settings.write_preview {
        let light = &palette.swatches[0].schemes["tonal_spot"].light;
        let preview = sketch::render(&mask, palette::rgb(&light["primary"]), palette::rgb(&light["surface"]));
        let preview_path = settings.outbox.join(format!("{}.preview.png", job.id));
        write_atomically(&preview_path, |tmp| preview.save_with_format(tmp, image::ImageFormat::Png).map_err(Into::into))?;
    }

    let result = SketchResult {
        id: job.id.clone(),
        width: mask.width(),
        height: mask.height(),
        sketch: sketch_path.to_string_lossy().into_owned(),
        palette,
    };
    // The JSON is written last: when it exists, everything it points to is complete.
    let json_path = settings.outbox.join(format!("{}.json", job.id));
    let json = serde_json::to_vec_pretty(&result)?;
    write_atomically(&json_path, |tmp| std::fs::write(tmp, &json).map_err(Into::into))?;

    // The original is no longer needed once the result is safely written.
    let _ = std::fs::remove_file(&job.source);
    Ok(result)
}

/// Writes `<path>.part` with `write`, then renames it into place in one step.
fn write_atomically(
    path: &Path,
    write: impl FnOnce(&Path) -> std::result::Result<(), BoxDynError>,
) -> std::result::Result<(), BoxDynError> {
    let mut tmp = path.as_os_str().to_owned();
    tmp.push(".part");
    let tmp = PathBuf::from(tmp);
    write(&tmp)?;
    std::fs::rename(&tmp, path)?;
    Ok(())
}

/// Goes through Rivium's logging: terminal on a laptop, logcat on Android.
fn log(message: &str) {
    tracing::info!("{message}");
}

// Android entry points (nativeStart, nativeStop, ...) for the Kotlin `RustBridge` class.
rivium_jni::export!(class = "io/github/samuel0kello/mchoro/RustBridge", app = Mchoro);
