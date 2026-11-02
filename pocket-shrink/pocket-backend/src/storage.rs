use crate::job::{JobResult, ProcessImageJob};
use apalis::prelude::*;
use apalis_sqlite::SqliteStorage;
use sqlx::sqlite::{SqliteConnectOptions, SqlitePoolOptions};
use sqlx::{Pool, Row, Sqlite};
use std::path::Path;
use std::str::FromStr;

pub type DbPool = Pool<Sqlite>;

pub async fn init_db(db_path: &Path) -> Result<DbPool, Box<dyn std::error::Error + Send + Sync>> {
    if let Some(parent) = db_path.parent() {
        tokio::fs::create_dir_all(parent).await?;
    }

    let db_str = db_path.to_string_lossy();
    let options = SqliteConnectOptions::from_str(&format!("sqlite://{}", db_str))?
        .create_if_missing(true);

    let pool = SqlitePoolOptions::new()
        .max_connections(5)
        .connect_with(options)
        .await?;

    // Setup Apalis schema
    SqliteStorage::setup(&pool).await?;

    // Setup results table
    sqlx::query(
        r#"
        CREATE TABLE IF NOT EXISTS pocket_results (
            id TEXT PRIMARY KEY,
            input_path TEXT NOT NULL,
            output_path TEXT NOT NULL,
            original_size_bytes INTEGER NOT NULL,
            processed_size_bytes INTEGER NOT NULL,
            sha256_hash TEXT NOT NULL,
            width INTEGER NOT NULL,
            height INTEGER NOT NULL,
            saved_percent INTEGER NOT NULL,
            completed_at INTEGER NOT NULL
        );
        "#,
    )
    .execute(&pool)
    .await?;

    Ok(pool)
}

pub async fn save_result(
    pool: &DbPool,
    result: &JobResult,
) -> Result<(), Box<dyn std::error::Error + Send + Sync>> {
    sqlx::query(
        r#"
        INSERT OR REPLACE INTO pocket_results (
            id, input_path, output_path, original_size_bytes, processed_size_bytes,
            sha256_hash, width, height, saved_percent, completed_at
        ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?);
        "#,
    )
    .bind(&result.id)
    .bind(&result.input_path)
    .bind(&result.output_path)
    .bind(result.original_size_bytes as i64)
    .bind(result.processed_size_bytes as i64)
    .bind(&result.sha256_hash)
    .bind(result.width as i64)
    .bind(result.height as i64)
    .bind(result.saved_percent as i64)
    .bind(result.completed_at)
    .execute(pool)
    .await?;

    Ok(())
}

pub async fn get_completed_results(
    pool: &DbPool,
) -> Result<Vec<JobResult>, Box<dyn std::error::Error + Send + Sync>> {
    let rows = sqlx::query(
        r#"
        SELECT id, input_path, output_path, original_size_bytes, processed_size_bytes,
               sha256_hash, width, height, saved_percent, completed_at
        FROM pocket_results
        ORDER BY completed_at DESC
        LIMIT 50;
        "#,
    )
    .fetch_all(pool)
    .await?;

    let mut list = Vec::new();
    for row in rows {
        list.push(JobResult {
            id: row.get("id"),
            input_path: row.get("input_path"),
            output_path: row.get("output_path"),
            original_size_bytes: row.get::<i64, _>("original_size_bytes") as u64,
            processed_size_bytes: row.get::<i64, _>("processed_size_bytes") as u64,
            sha256_hash: row.get("sha256_hash"),
            width: row.get::<i64, _>("width") as u32,
            height: row.get::<i64, _>("height") as u32,
            saved_percent: row.get::<i64, _>("saved_percent") as u32,
            completed_at: row.get("completed_at"),
        });
    }

    Ok(list)
}

pub async fn enqueue_job(
    pool: &DbPool,
    job: ProcessImageJob,
) -> Result<String, Box<dyn std::error::Error + Send + Sync>> {
    let job_id = job.id.clone();
    let mut storage = SqliteStorage::new(pool);
    storage.push(job).await?;
    Ok(job_id)
}
