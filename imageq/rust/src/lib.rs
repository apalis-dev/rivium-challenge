//! imageq: "clean before you share".
//! Each photo becomes a job: strip hidden metadata (GPS, camera model),
//! fix rotation, save a clean copy + a small thumbnail, and record what was removed.
//! Jobs live in an Apalis SQLite queue, supervised by a Rivium service.

use std::io::Cursor;
use std::path::{Path, PathBuf};

use apalis::prelude::*;
use apalis_sqlite::{SqlitePool, SqlitePoolOptions, SqliteStorage};
use image::{ImageDecoder, ImageReader, codecs::jpeg::JpegEncoder};
use rivium::{Kind, Result as RvResult, Service, service};
use rivium_host::{App, Context};
use serde::{Deserialize, Serialize};
use sha2::{Digest, Sha256};

type AnyErr = BoxDynError;

/// The job: one photo to clean.
#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct PhotoJob {
    pub path: String,
}

#[derive(Default, Serialize, Deserialize)]
pub struct Config {}

pub fn db_path(root: &Path) -> PathBuf {
    root.join("imageq.db")
}

/// Open the pool and create Apalis tables + our `cleaned` results table.
pub async fn open(root: &Path) -> std::result::Result<SqlitePool, AnyErr> {
    for d in ["thumbs", "clean", "photos", "inbox"] {
        std::fs::create_dir_all(root.join(d))?;
    }
    let url = format!("sqlite://{}?mode=rwc", db_path(root).display());
    let pool = SqlitePoolOptions::new().max_connections(4).connect(&url).await?;
    SqliteStorage::setup(&pool).await?;
    sqlx::query(
        "CREATE TABLE IF NOT EXISTS cleaned (
            hash TEXT PRIMARY KEY,
            source TEXT NOT NULL,
            clean_path TEXT NOT NULL,
            thumb TEXT NOT NULL,
            width INTEGER, height INTEGER,
            had_gps INTEGER NOT NULL,
            camera TEXT NOT NULL,
            orig_bytes INTEGER, clean_bytes INTEGER,
            done_at INTEGER NOT NULL
        )",
    )
    .execute(&pool)
    .await?;
    Ok(pool)
}

/// Push a job (max 3 attempts). Works from any process that can open the same db file.
pub async fn enqueue(pool: &SqlitePool, path: &str) -> std::result::Result<(), AnyErr> {
    let mut backend = SqliteStorage::new(pool);
    let task = TaskBuilder::new(PhotoJob { path: path.into() })
        .max_attempts(3)
        .build();
    backend.push_task(task).await?;
    Ok(())
}

/// Move files dropped into <root>/inbox to <root>/photos and queue a job for each.
async fn scan_inbox(root: &Path, pool: &SqlitePool) -> std::result::Result<(), AnyErr> {
    let inbox = root.join("inbox");
    let photos = root.join("photos");
    tokio::fs::create_dir_all(&inbox).await?;
    tokio::fs::create_dir_all(&photos).await?;
    let mut dir = tokio::fs::read_dir(&inbox).await?;
    while let Some(entry) = dir.next_entry().await? {
        let path = entry.path();
        if !entry.file_type().await?.is_file() {
            continue;
        }
        // The app writes "name.part" and renames when done, so skip half-copied files.
        if path.extension().is_some_and(|e| e == "part") {
            continue;
        }
        let dest = photos.join(entry.file_name());
        tokio::fs::rename(&path, &dest).await?;
        enqueue(pool, &dest.to_string_lossy()).await?;
        tracing::info!(path = %dest.display(), "queued from inbox");
    }
    Ok(())
}

/// What the cleaning step found and produced.
#[derive(Debug)]
struct Report {
    had_gps: bool,
    camera: String,
    width: u32,
    height: u32,
    clean_bytes: u64,
}

/// Read the hidden metadata (before we throw it away).
fn inspect_exif(bytes: &[u8]) -> (bool, String) {
    let Ok(exif) = exif::Reader::new().read_from_container(&mut Cursor::new(bytes)) else {
        return (false, String::new());
    };
    let had_gps = exif
        .get_field(exif::Tag::GPSLatitude, exif::In::PRIMARY)
        .is_some()
        || exif
            .get_field(exif::Tag::GPSLongitude, exif::In::PRIMARY)
            .is_some();
    let field = |tag| {
        exif.get_field(tag, exif::In::PRIMARY)
            .map(|f| f.display_value().to_string().trim_matches('"').to_string())
    };
    let camera = [field(exif::Tag::Make), field(exif::Tag::Model)]
        .into_iter()
        .flatten()
        .collect::<Vec<_>>()
        .join(" ");
    (had_gps, camera)
}

/// Decode, apply the rotation flag, and re-encode. A fresh JPEG carries no EXIF/GPS.
/// Blocking and CPU heavy: call from spawn_blocking.
fn clean_photo(bytes: Vec<u8>, clean_out: &Path, thumb_out: &Path) -> std::result::Result<Report, AnyErr> {
    let (had_gps, camera) = inspect_exif(&bytes);

    let mut decoder = ImageReader::new(Cursor::new(&bytes))
        .with_guessed_format()?
        .into_decoder()?;
    let orientation = decoder.orientation()?;
    let mut img = image::DynamicImage::from_decoder(decoder)?;
    img.apply_orientation(orientation);
    drop(bytes);

    // Small preview for the app's list (made first, so we can then consume `img`).
    img.thumbnail(256, 256).to_rgb8().save(thumb_out)?;

    // Clean copy at full size. into_rgb8 reuses the buffer when it is already RGB
    // (JPEG can't hold alpha, so everything goes through RGB).
    let rgb = img.into_rgb8();
    let tmp = clean_out.with_extension("jpg.tmp");
    {
        let file = std::fs::File::create(&tmp)?;
        let mut w = std::io::BufWriter::new(file);
        JpegEncoder::new_with_quality(&mut w, 90).encode_image(&rgb)?;
    }
    std::fs::rename(&tmp, clean_out)?;

    Ok(Report {
        had_gps,
        camera,
        width: rgb.width(),
        height: rgb.height(),
        clean_bytes: std::fs::metadata(clean_out)?.len(),
    })
}

/// The handler Apalis calls: logs the failure, then returns the error so Apalis retries.
async fn process_photo(
    job: PhotoJob,
    pool: Data<SqlitePool>,
    root: Data<PathBuf>,
) -> std::result::Result<(), AnyErr> {
    match run_photo(&job, &pool, &root).await {
        Ok(()) => Ok(()),
        Err(e) => {
            tracing::warn!(path = %job.path, error = %e, "photo failed");
            Err(e)
        }
    }
}

async fn run_photo(job: &PhotoJob, pool: &SqlitePool, root: &Path) -> std::result::Result<(), AnyErr> {
    let bytes = tokio::fs::read(&job.path).await?;
    let orig_bytes = bytes.len() as i64;
    let hash = hex::encode(Sha256::digest(&bytes));
    let clean_path = root.join("clean").join(format!("{hash}.jpg"));
    let thumb_path = root.join("thumbs").join(format!("{hash}.jpg"));

    let report = tokio::task::spawn_blocking({
        let (c, t) = (clean_path.clone(), thumb_path.clone());
        move || clean_photo(bytes, &c, &t)
    })
    .await??;

    sqlx::query(
        "INSERT OR REPLACE INTO cleaned
         (hash, source, clean_path, thumb, width, height, had_gps, camera, orig_bytes, clean_bytes, done_at)
         VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, strftime('%s','now'))",
    )
    .bind(&hash)
    .bind(&job.path)
    .bind(clean_path.to_string_lossy().to_string())
    .bind(thumb_path.to_string_lossy().to_string())
    .bind(report.width as i64)
    .bind(report.height as i64)
    .bind(report.had_gps as i64)
    .bind(&report.camera)
    .bind(orig_bytes)
    .bind(report.clean_bytes as i64)
    .execute(pool)
    .await?;

    // The original is no longer needed once the clean copy exists.
    let _ = tokio::fs::remove_file(&job.path).await;

    tracing::info!(
        hash = &hash[..12],
        gps_removed = report.had_gps,
        camera = %report.camera,
        width = report.width,
        height = report.height,
        "photo cleaned"
    );
    Ok(())
}

pub struct ImageQ;

impl App for ImageQ {
    const NAME: &'static str = "imageq";
    const VERSION: &'static str = env!("CARGO_PKG_VERSION");
    type Config = Config;

    fn services(_: &Config, ctx: &Context<Config>) -> RvResult<Vec<Box<dyn Service>>> {
        let root = ctx.paths().root().to_path_buf();
        Ok(vec![service("photo-worker", Kind::Frontline, move |sctx| async move {
            let pool = open(&root)
                .await
                .map_err(|e| rivium::Error::internal(e.to_string()))?;
            let backend = SqliteStorage::new(&pool);

            // Unique per start: Apalis refuses a worker name it has seen before.
            let name = format!(
                "photo-worker-{}-{}",
                std::process::id(),
                std::time::SystemTime::now()
                    .duration_since(std::time::UNIX_EPOCH)
                    .map(|d| d.as_millis())
                    .unwrap_or(0)
            );
            let worker = WorkerBuilder::new(name)
                .backend(backend)
                .data(pool.clone())
                .data(root.clone())
                .build(process_photo);

            let inbox_loop = async {
                let mut tick = tokio::time::interval(std::time::Duration::from_secs(2));
                loop {
                    tick.tick().await;
                    if let Err(e) = scan_inbox(&root, &pool).await {
                        tracing::warn!(error = %e, "inbox scan failed");
                    }
                }
            };

            sctx.ready();
            tokio::select! {
                r = worker.run() => { r.map_err(|e| rivium::Error::internal(e.to_string()))?; }
                _ = inbox_loop => {}
                _ = sctx.stopped() => {}
            }
            Ok(())
        })])
    }
}

rivium_jni::export!(class = "com/example/imageq/Bridge", app = ImageQ);
