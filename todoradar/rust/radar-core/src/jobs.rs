//! The two Apalis job types. Job payloads only carry a scan id; the `scans` row holds the
//! real state, so every handler is idempotent and resumes from the last checkpoint.

use std::{path::PathBuf, time::Duration};

use apalis::prelude::*;
use futures_util::StreamExt;
use serde::{Deserialize, Serialize};
use tokio::io::AsyncWriteExt;

use crate::{Engine, github, github::GhError, scanner, store, store::Scan};

/// Entries scanned between checkpoints.
const CHUNK: usize = 200;
/// Download progress is persisted (and the stop flag checked) every this many bytes.
const DOWNLOAD_STEP: u64 = 256 * 1024;
/// Attempts before a transient failure becomes final.
pub const MAX_ATTEMPTS: i64 = 6;

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct ScanRepo {
    pub scan_id: i64,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct CleanupScan {
    pub scan_id: i64,
}

enum Outcome {
    Done,
    /// The host is stopping; progress is saved and the task will be re-enqueued.
    Paused,
}

pub async fn scan_repo(job: ScanRepo, engine: Data<Engine>) -> Result<(), BoxDynError> {
    let pool = engine.db.ready().await?;
    let Some(scan) = store::scan(pool, job.scan_id).await? else {
        return Ok(());
    };
    if matches!(scan.state.as_str(), "done" | "failed") {
        return Ok(());
    }
    store::start_attempt(pool, scan.id).await?;

    match run(&engine, scan.clone()).await {
        Ok(Outcome::Done) => Ok(()),
        Ok(Outcome::Paused) => {
            // Never complete a paused task: the monitor drops it on shutdown and the
            // backend re-enqueues it for the next generation.
            std::future::pending::<()>().await;
            Ok(())
        }
        Err(GhError::Permanent(message)) => {
            store::fail(pool, scan.id, &message).await?;
            Ok(())
        }
        Err(GhError::Transient(message)) if scan.attempts + 1 >= MAX_ATTEMPTS => {
            store::fail(pool, scan.id, &format!("gave up after {MAX_ATTEMPTS} attempts: {message}")).await?;
            Ok(())
        }
        Err(GhError::Transient(message)) => {
            store::note_error(pool, scan.id, &format!("retrying: {message}")).await?;
            Err(message.into())
        }
    }
}

async fn run(engine: &Engine, scan: Scan) -> Result<Outcome, GhError> {
    let pool = engine.db.ready().await.map_err(internal)?;
    let token = crate::api::github_token(engine).await;
    let token = token.as_deref();

    let zip = if scan.is_github() {
        let (owner, name) = (scan.owner.clone().unwrap_or_default(), scan.repo.clone().unwrap_or_default());
        // Resolve: size cap + head commit, skipping work when nothing changed.
        let sha = match &scan.sha {
            Some(sha) => sha.clone(),
            None => {
                store::set_stage(pool, scan.id, "resolve").await.map_err(internal)?;
                let repo = github::repo(&engine.http, token, &owner, &name).await?;
                let cap_kb = engine.config.max_repo_mb * 1024;
                if repo.size_kb > cap_kb {
                    return Err(GhError::Permanent(format!(
                        "repo is {} MB, over the {} MB cap",
                        repo.size_kb / 1024,
                        engine.config.max_repo_mb
                    )));
                }
                let sha = github::head_sha(&engine.http, token, &owner, &name, &repo.default_branch).await?;
                store::set_resolved(pool, scan.id, &sha, None).await.map_err(internal)?;
                sha
            }
        };
        if let Some(prev) = store::previous(pool, &scan).await.map_err(internal)? {
            if prev.sha.as_deref() == Some(sha.as_str()) && scan.stage != "scan" {
                store::copy_todos(pool, prev.id, scan.id).await.map_err(internal)?;
                return finish(engine, scan.id, Some(prev.id)).await;
            }
        }
        let zip = engine.cache_dir().join(format!("{}.zip", scan.id));
        if !zip.exists() {
            store::set_stage(pool, scan.id, "download").await.map_err(internal)?;
            if !download(engine, &scan, token, &owner, &name, &sha, &zip).await? {
                return Ok(Outcome::Paused);
            }
        }
        zip
    } else {
        let path = PathBuf::from(scan.zip_path.clone().unwrap_or_default());
        if !path.is_file() {
            return Err(GhError::Permanent(format!("zip not found: {}", path.display())));
        }
        path
    };

    // Scan in checkpointed chunks, resuming from the saved entry index.
    store::set_stage(pool, scan.id, "scan").await.map_err(internal)?;
    let mut next = store::scan(pool, scan.id).await.map_err(internal)?.map_or(0, |s| s.entries_done as usize);
    loop {
        if engine.is_stopping() {
            return Ok(Outcome::Paused);
        }
        let path = zip.clone();
        let chunk = tokio::task::spawn_blocking(move || scanner::scan_chunk(&path, next, CHUNK))
            .await
            .map_err(|e| GhError::Transient(format!("scanner task: {e}")))?
            .map_err(|e| GhError::Permanent(format!("not a readable zip: {e}")))?;
        let todos_so_far = store::scan(pool, scan.id).await.map_err(internal)?.map_or(0, |s| s.todo_count);
        let room = (engine.config.max_todos as i64 - todos_so_far).max(0) as usize;
        let todos = &chunk.todos[..chunk.todos.len().min(room)];
        store::checkpoint(pool, scan.id, todos, chunk.next_entry, chunk.entries_total)
            .await
            .map_err(internal)?;
        next = chunk.next_entry;
        if next >= chunk.entries_total {
            break;
        }
    }

    store::set_stage(pool, scan.id, "diff").await.map_err(internal)?;
    let prev = store::previous(pool, &scan).await.map_err(internal)?.map(|p| p.id);
    finish(engine, scan.id, prev).await
}

/// Finish the scan and schedule the default deletion of its download.
async fn finish(engine: &Engine, id: i64, prev: Option<i64>) -> Result<Outcome, GhError> {
    let pool = engine.db.ready().await.map_err(internal)?;
    let delay = Duration::from_secs(engine.config.cleanup_delay_secs);
    let cleanup_at = now() + delay.as_secs() as i64;
    store::finish(pool, id, prev, cleanup_at).await.map_err(internal)?;
    let task = TaskBuilder::new(CleanupScan { scan_id: id }).run_after(delay).build();
    engine
        .cleanup_queue()
        .await
        .map_err(internal)?
        .push_task(task)
        .await
        .map_err(|e| GhError::Transient(format!("queue cleanup: {e}")))?;
    Ok(Outcome::Done)
}

/// Stream the zipball to `<zip>.part`, resuming with a Range request when possible.
/// Returns false when interrupted by a stop request (progress is kept on disk).
async fn download(
    engine: &Engine,
    scan: &Scan,
    token: Option<&str>,
    owner: &str,
    name: &str,
    sha: &str,
    zip: &std::path::Path,
) -> Result<bool, GhError> {
    let pool = engine.db.ready().await.map_err(internal)?;
    let part = zip.with_extension("zip.part");
    let existing = tokio::fs::metadata(&part).await.map(|m| m.len()).unwrap_or(0);
    let (response, resumed) = github::zipball(&engine.http, token, owner, name, sha, existing).await?;
    let mut done = if resumed { existing } else { 0 };
    let total = response.content_length().map(|len| len + done);
    let cap = engine.config.max_repo_mb * 1024 * 1024;
    if total.is_some_and(|t| t > cap) {
        return Err(GhError::Permanent(format!("download is over the {} MB cap", engine.config.max_repo_mb)));
    }
    let mut file = tokio::fs::OpenOptions::new()
        .create(true)
        .write(true)
        .append(resumed)
        .truncate(!resumed)
        .open(&part)
        .await
        .map_err(|e| GhError::Transient(format!("cache file: {e}")))?;
    store::set_download(pool, scan.id, done as i64, total.map(|t| t as i64)).await.map_err(internal)?;

    let mut stream = response.bytes_stream();
    let mut last_saved = done;
    while let Some(bytes) = stream.next().await {
        let bytes = bytes?;
        done += bytes.len() as u64;
        if done > cap {
            drop(file);
            let _ = tokio::fs::remove_file(&part).await;
            return Err(GhError::Permanent(format!("download passed the {} MB cap", engine.config.max_repo_mb)));
        }
        file.write_all(&bytes).await.map_err(|e| GhError::Transient(format!("write: {e}")))?;
        if done - last_saved >= DOWNLOAD_STEP {
            last_saved = done;
            file.flush().await.map_err(|e| GhError::Transient(format!("flush: {e}")))?;
            store::set_download(pool, scan.id, done as i64, None).await.map_err(internal)?;
            if engine.is_stopping() {
                return Ok(false);
            }
        }
    }
    file.flush().await.map_err(|e| GhError::Transient(format!("flush: {e}")))?;
    drop(file);
    tokio::fs::rename(&part, zip).await.map_err(|e| GhError::Transient(format!("rename: {e}")))?;
    store::set_download(pool, scan.id, done as i64, Some(done as i64)).await.map_err(internal)?;
    Ok(true)
}

/// Default deletion of a scan's download (unless kept), then pruning to `keep_scans`.
pub async fn cleanup_scan(job: CleanupScan, engine: Data<Engine>) -> Result<(), BoxDynError> {
    let pool = engine.db.ready().await?;
    let Some(scan) = store::scan(pool, job.scan_id).await? else {
        return Ok(());
    };
    if !scan.keep_files && !scan.files_deleted {
        for path in owned_files(&engine, &scan) {
            match tokio::fs::remove_file(&path).await {
                Ok(()) => {}
                Err(e) if e.kind() == std::io::ErrorKind::NotFound => {}
                Err(e) => return Err(e.into()),
            }
        }
        // Shared zips live in inbox/<uuid>/; drop the folder once it is empty.
        if let Some(dir) = scan.zip_path.as_deref().map(std::path::Path::new).and_then(|p| p.parent()) {
            if dir.starts_with(engine.inbox_dir()) && dir != engine.inbox_dir() {
                let _ = tokio::fs::remove_dir(dir).await;
            }
        }
        store::mark_files_deleted(pool, scan.id).await?;
    }
    for id in store::prune(pool, &scan, engine.config.keep_scans).await? {
        // Pruned scans may still own a kept download.
        let _ = tokio::fs::remove_file(engine.cache_dir().join(format!("{id}.zip"))).await;
    }
    Ok(())
}

/// Files this scan may delete: its cached download, and a shared zip only if it lives in
/// our inbox. Zips picked from elsewhere on disk are the user's and are never deleted.
fn owned_files(engine: &Engine, scan: &Scan) -> Vec<PathBuf> {
    let cache = engine.cache_dir();
    let mut files = vec![
        cache.join(format!("{}.zip", scan.id)),
        cache.join(format!("{}.zip.part", scan.id)),
    ];
    if let Some(path) = &scan.zip_path {
        let path = PathBuf::from(path);
        if path.starts_with(engine.inbox_dir()) {
            files.push(path);
        }
    }
    files
}

fn internal(error: BoxDynError) -> GhError {
    GhError::Transient(error.to_string())
}

pub fn now() -> i64 {
    std::time::SystemTime::now()
        .duration_since(std::time::UNIX_EPOCH)
        .map_or(0, |d| d.as_secs() as i64)
}

pub fn now_millis() -> u128 {
    std::time::SystemTime::now()
        .duration_since(std::time::UNIX_EPOCH)
        .map_or(0, |d| d.as_millis())
}
