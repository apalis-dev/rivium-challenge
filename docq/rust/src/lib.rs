//! docq: convert Word (DOCX) files to PDF in the background.
//! Each file is one job. Conversion is office2pdf (pure Rust, Typst under the hood).
//! Jobs live in an Apalis SQLite queue, supervised by a Rivium service.

use std::path::{Path, PathBuf};

use apalis::prelude::*;
use apalis_sqlite::{SqlitePool, SqlitePoolOptions, SqliteStorage};
use office2pdf::config::{ConvertOptions, Format};
use rivium::{Kind, Result as RvResult, Service, service};
use rivium_host::{App, Context};
use serde::{Deserialize, Serialize};

type AnyErr = BoxDynError;

/// The job: one office file to turn into a PDF.
#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct DocJob {
    pub path: String,
}

#[derive(Default, Serialize, Deserialize)]
pub struct Config {}

pub fn db_path(root: &Path) -> PathBuf {
    root.join("docq.db")
}

/// Open the pool and create Apalis tables + our `converted` results table.
pub async fn open(root: &Path) -> std::result::Result<SqlitePool, AnyErr> {
    for d in ["inbox", "jobs", "pdfs"] {
        std::fs::create_dir_all(root.join(d))?;
    }
    let url = format!("sqlite://{}?mode=rwc", db_path(root).display());
    let pool = SqlitePoolOptions::new().max_connections(4).connect(&url).await?;
    SqliteStorage::setup(&pool).await?;
    sqlx::query(
        "CREATE TABLE IF NOT EXISTS converted (
            file TEXT PRIMARY KEY,
            source TEXT NOT NULL,
            pdf_path TEXT NOT NULL,
            bytes INTEGER NOT NULL,
            warnings INTEGER NOT NULL,
            done_at INTEGER NOT NULL
        )",
    )
    .execute(&pool)
    .await?;
    Ok(pool)
}

/// Push a job (max 3 attempts).
pub async fn enqueue(pool: &SqlitePool, path: &str) -> std::result::Result<(), AnyErr> {
    let mut backend = SqliteStorage::new(pool);
    let task = TaskBuilder::new(DocJob { path: path.into() })
        .max_attempts(3)
        .build();
    backend.push_task(task).await?;
    Ok(())
}

fn stamp() -> String {
    std::time::SystemTime::now()
        .duration_since(std::time::UNIX_EPOCH)
        .map(|d| d.as_millis().to_string())
        .unwrap_or_default()
}

/// Laptop helper: copy files into <root>/jobs and queue one job per file.
pub async fn enqueue_files(root: &Path, pool: &SqlitePool, files: &[PathBuf]) -> std::result::Result<usize, AnyErr> {
    for (i, f) in files.iter().enumerate() {
        let name = f.file_name().ok_or("bad path")?.to_string_lossy();
        let dest = root.join("jobs").join(format!("{}-{i}__{name}", stamp()));
        std::fs::copy(f, &dest)?;
        enqueue(pool, &dest.to_string_lossy()).await?;
    }
    Ok(files.len())
}

/// Move files dropped into <root>/inbox to <root>/jobs and queue each.
/// The app writes "name.part" and renames when the copy is complete.
async fn scan_inbox(root: &Path, pool: &SqlitePool) -> std::result::Result<(), AnyErr> {
    let inbox = root.join("inbox");
    let jobs = root.join("jobs");
    tokio::fs::create_dir_all(&inbox).await?;
    tokio::fs::create_dir_all(&jobs).await?;
    let mut dir = tokio::fs::read_dir(&inbox).await?;
    while let Some(entry) = dir.next_entry().await? {
        let path = entry.path();
        if !entry.file_type().await?.is_file() || path.extension().is_some_and(|e| e == "part") {
            continue;
        }
        let dest = jobs.join(entry.file_name());
        tokio::fs::rename(&path, &dest).await?;
        enqueue(pool, &dest.to_string_lossy()).await?;
        tracing::info!(file = %dest.display(), "queued from inbox");
    }
    Ok(())
}

fn format_of(path: &Path) -> std::result::Result<Format, AnyErr> {
    match path.extension().and_then(|e| e.to_str()).map(|e| e.to_ascii_lowercase()).as_deref() {
        Some("docx") => Ok(Format::Docx),
        Some("pptx") => Ok(Format::Pptx),
        Some("xlsx") => Ok(Format::Xlsx),
        _ => Err("unsupported file type (need .docx, .pptx or .xlsx)".into()),
    }
}

/// "1793..-0__report.docx" -> "report.docx"
fn pretty(name: &str) -> String {
    name.split_once("__").map(|(_, n)| n).unwrap_or(name).to_string()
}

/// The handler Apalis calls: logs the failure, then returns the error so Apalis retries.
async fn process_doc(
    job: DocJob,
    pool: Data<SqlitePool>,
    root: Data<PathBuf>,
) -> std::result::Result<(), AnyErr> {
    match run_doc(&job, &pool, &root).await {
        Ok(()) => Ok(()),
        Err(e) => {
            tracing::warn!(path = %job.path, error = %e, "conversion failed");
            Err(e)
        }
    }
}

async fn run_doc(job: &DocJob, pool: &SqlitePool, root: &Path) -> std::result::Result<(), AnyErr> {
    let src = PathBuf::from(&job.path);
    let fmt = format_of(&src)?;
    let file = src.file_name().ok_or("bad path")?.to_string_lossy().to_string();
    let stem = src.file_stem().ok_or("bad path")?.to_string_lossy().to_string();
    let pdf_path = root.join("pdfs").join(format!("{stem}.pdf"));

    let bytes = tokio::fs::read(&src).await?;
    let started = std::time::Instant::now();
    let (pdf, warnings) = tokio::task::spawn_blocking(move || -> std::result::Result<(Vec<u8>, usize), AnyErr> {
        let r = office2pdf::convert_bytes(&bytes, fmt, &ConvertOptions::default())?;
        Ok((r.pdf, r.warnings.len()))
    })
    .await??;

    let tmp = pdf_path.with_extension("pdf.tmp");
    tokio::fs::write(&tmp, &pdf).await?;
    tokio::fs::rename(&tmp, &pdf_path).await?;

    sqlx::query(
        "INSERT OR REPLACE INTO converted (file, source, pdf_path, bytes, warnings, done_at)
         VALUES (?, ?, ?, ?, ?, strftime('%s','now'))",
    )
    .bind(&file)
    .bind(pretty(&file))
    .bind(pdf_path.to_string_lossy().to_string())
    .bind(pdf.len() as i64)
    .bind(warnings as i64)
    .execute(pool)
    .await?;

    // Only delete our own working copy, never a file the user pointed us at.
    if src.starts_with(root.join("jobs")) {
        let _ = tokio::fs::remove_file(&src).await;
    }
    tracing::info!(source = %pretty(&file), bytes = pdf.len(), warnings, ms = started.elapsed().as_millis() as u64, "converted to pdf");
    Ok(())
}

pub struct Docq;

impl App for Docq {
    const NAME: &'static str = "docq";
    const VERSION: &'static str = env!("CARGO_PKG_VERSION");
    type Config = Config;

    fn services(_: &Config, ctx: &Context<Config>) -> RvResult<Vec<Box<dyn Service>>> {
        let root = ctx.paths().root().to_path_buf();
        Ok(vec![service("doc-worker", Kind::Frontline, move |sctx| async move {
            let pool = open(&root)
                .await
                .map_err(|e| rivium::Error::internal(e.to_string()))?;
            let backend = SqliteStorage::new(&pool);

            // Unique per start: Apalis refuses a worker name it has seen before.
            let name = format!("doc-worker-{}-{}", std::process::id(), stamp());
            let worker = WorkerBuilder::new(name)
                .backend(backend)
                .concurrency(1)
                .data(pool.clone())
                .data(root.clone())
                .build(process_doc);

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

rivium_jni::export!(class = "com/example/docq/Bridge", app = Docq);