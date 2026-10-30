use std::{error::Error, path::Path, time::Duration};

use apalis::prelude::*;
use apalis_sqlite::{SqlitePool, SqliteStorage};
use serde::{Deserialize, Serialize};
use sha2::Digest;
use sqlx::sqlite::{SqliteConnectOptions, SqlitePoolOptions};
use tokio::fs;

use crate::{process_url, result_path};

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct ProcessUrlJob {
    pub job_id: String,
    pub url: String,
    pub data_directory: String,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "lowercase")]
pub enum JobStatus {
    Completed,
    Failed,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct JobResult {
    pub job_id: String,
    pub original_url: String,
    pub status: JobStatus,
    pub normalized_url: Option<String>,
    pub sha256: Option<String>,
    pub error: Option<String>,
}

pub async fn create_storage(
    data_directory: &Path,
) -> Result<(SqlitePool, SqliteStorage<ProcessUrlJob>), Box<dyn Error + Send + Sync>> {
    std::fs::create_dir_all(data_directory)?;
    let database_path = data_directory.join("linkqueue.db");
    let options = SqliteConnectOptions::new()
        .filename(database_path)
        .create_if_missing(true);
    let pool = SqlitePoolOptions::new()
        .max_connections(5)
        .connect_with(options)
        .await?;

    SqliteStorage::setup(&pool).await?;
    Ok((pool.clone(), SqliteStorage::new(&pool)))
}

pub async fn enqueue_url(
    job_id: String,
    url: String,
    data_directory: std::path::PathBuf,
) -> Result<(), Box<dyn Error + Send + Sync>> {
    result_path(&data_directory, &job_id).map_err(std::io::Error::other)?;
    let (_pool, mut storage) = create_storage(&data_directory).await?;
    storage
        .push(ProcessUrlJob {
            job_id,
            url,
            data_directory: data_directory.to_string_lossy().into_owned(),
        })
        .await?;
    Ok(())
}

pub fn read_job_result(data_directory: &Path, job_id: &str) -> std::io::Result<String> {
    let path = result_path(data_directory, job_id).map_err(std::io::Error::other)?;
    match std::fs::read_to_string(path) {
        Ok(result) => Ok(result),
        Err(error) if error.kind() == std::io::ErrorKind::NotFound => Ok(String::new()),
        Err(error) => Err(error),
    }
}

pub async fn process_url_job(
    job: ProcessUrlJob,
    _context: WorkerContext,
) -> Result<JobResult, BoxDynError> {
    let result = match process_url(&job.url) {
        Ok(processed) => JobResult {
            job_id: job.job_id,
            original_url: job.url,
            status: JobStatus::Completed,
            normalized_url: Some(processed.normalized_url),
            sha256: Some(processed.sha256),
            error: None,
        },
        Err(error) => JobResult {
            job_id: job.job_id,
            original_url: job.url,
            status: JobStatus::Failed,
            normalized_url: None,
            sha256: None,
            error: Some(error),
        },
    };

    persist_result(Path::new(&job.data_directory), &result).await?;
    println!("URL job {}: {:?}", result.job_id, result.status);
    Ok(result)
}

async fn persist_result(data_directory: &Path, result: &JobResult) -> Result<(), BoxDynError> {
    let result_path = result_path(data_directory, &result.job_id).map_err(std::io::Error::other)?;
    let parent = result_path
        .parent()
        .ok_or_else(|| std::io::Error::other("Result path has no parent"))?;
    fs::create_dir_all(parent).await?;

    let temporary_path = result_path.with_extension("json.tmp");
    let bytes = serde_json::to_vec(result)?;
    fs::write(&temporary_path, bytes).await?;
    fs::rename(temporary_path, result_path).await?;
    Ok(())
}

pub async fn run_worker(
    data_directory: &Path,
    stop: impl std::future::Future<Output = ()>,
) -> Result<(), Box<dyn Error + Send + Sync>> {
    let (_pool, storage) = create_storage(data_directory).await?;
    let worker = WorkerBuilder::new("linkqueue-worker")
        .backend(storage)
        .build(process_url_job);

    tokio::select! {
        result = worker.run() => result.map_err(Into::into),
        _ = stop => Ok(()),
    }
}

pub fn run_with_runtime<T>(
    future: impl std::future::Future<Output = T>,
) -> Result<T, std::io::Error> {
    let runtime = tokio::runtime::Builder::new_multi_thread()
        .enable_all()
        .worker_threads(2)
        .thread_name("linkqueue-rust")
        .build()?;
    Ok(runtime.block_on(future))
}

pub fn enqueue_blocking(
    job_id: String,
    url: String,
    data_directory: std::path::PathBuf,
) -> Result<(), Box<dyn Error + Send + Sync>> {
    run_with_runtime(enqueue_url(job_id, url, data_directory))??;
    Ok(())
}

pub fn default_data_directory() -> std::path::PathBuf {
    std::env::var_os("LINKQUEUE_DATA_DIR")
        .map(std::path::PathBuf::from)
        .unwrap_or_else(|| std::path::PathBuf::from("linkqueue-data"))
}

pub fn new_job_id(url: &str) -> String {
    use std::sync::atomic::{AtomicU64, Ordering};
    static COUNTER: AtomicU64 = AtomicU64::new(0);

    let timestamp = std::time::SystemTime::now()
        .duration_since(std::time::UNIX_EPOCH)
        .unwrap_or(Duration::ZERO)
        .as_nanos();
    let sequence = COUNTER.fetch_add(1, Ordering::Relaxed);
    let digest = sha2::Sha256::digest(format!("{timestamp}:{sequence}:{url}").as_bytes());
    digest.iter().map(|byte| format!("{byte:02x}")).collect()
}
