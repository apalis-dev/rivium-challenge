use apalis_sqlite::SqliteStorage;
use serde::{Deserialize, Serialize};
use sqlx::{Row, SqlitePool};

#[derive(Debug, Clone, Serialize, Deserialize, Default)]
pub struct QueueStats {
    pub pending: i64,
    pub running: i64,
    pub completed: i64,
    pub failed: i64,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct CompletedWork {
    pub id: String,
    pub original_uri: String,
    pub output_path: String,
    pub original_size: i64,
    pub compressed_size: i64,
    pub blake3_hash: String,
    pub duration_ms: i64,
    pub processed_at: i64,
}

pub async fn init_db(pool: &SqlitePool) -> Result<(), sqlx::Error> {
    sqlx::query("PRAGMA journal_mode = WAL;").execute(pool).await?;
    sqlx::query("PRAGMA synchronous = NORMAL;").execute(pool).await?;
    sqlx::query("PRAGMA busy_timeout = 10000;").execute(pool).await?;
    sqlx::query("PRAGMA cache_size = -131072;").execute(pool).await?;
    sqlx::query("PRAGMA mmap_size = 4294967296;").execute(pool).await?;
    sqlx::query("PRAGMA temp_store = MEMORY;").execute(pool).await?;
    sqlx::query("PRAGMA locking_mode = NORMAL;").execute(pool).await?;

    SqliteStorage::setup(pool)
        .await
        .map_err(|e| sqlx::Error::Configuration(e.to_string().into()))?;

    sqlx::query(
        r#"
        CREATE TABLE IF NOT EXISTS completed_works (
            id TEXT PRIMARY KEY,
            original_uri TEXT NOT NULL,
            output_path TEXT NOT NULL,
            original_size INTEGER NOT NULL,
            compressed_size INTEGER NOT NULL,
            blake3_hash TEXT NOT NULL,
            duration_ms INTEGER NOT NULL,
            processed_at INTEGER NOT NULL
        );
        CREATE INDEX IF NOT EXISTS idx_completed_works_processed_at 
        ON completed_works(processed_at DESC);
        "#,
    )
    .execute(pool)
    .await?;

    Ok(())
}

pub async fn get_queue_stats(pool: &SqlitePool) -> Result<QueueStats, sqlx::Error> {
    let mut stats = QueueStats::default();

    let rows = sqlx::query("SELECT status, COUNT(*) as count FROM Jobs GROUP BY status")
        .fetch_all(pool)
        .await?;

    for row in rows {
        let status: String = row.get("status");
        let count: i64 = row.get("count");
        match status.as_str() {
            "Pending" | "Queued" => stats.pending += count,
            "Running" => stats.running += count,
            "Done" | "Completed" => stats.completed += count,
            "Failed" | "Killed" => stats.failed += count,
            _ => {}
        }
    }

    Ok(stats)
}

pub async fn record_completed_work(
    pool: &SqlitePool,
    work: &CompletedWork,
) -> Result<(), sqlx::Error> {
    sqlx::query(
        r#"
        INSERT OR REPLACE INTO completed_works 
        (id, original_uri, output_path, original_size, compressed_size, blake3_hash, duration_ms, processed_at)
        VALUES (?, ?, ?, ?, ?, ?, ?, ?)
        "#,
    )
    .bind(&work.id)
    .bind(&work.original_uri)
    .bind(&work.output_path)
    .bind(work.original_size)
    .bind(work.compressed_size)
    .bind(&work.blake3_hash)
    .bind(work.duration_ms)
    .bind(work.processed_at)
    .execute(pool)
    .await?;

    Ok(())
}

pub async fn get_all_completed_works(
    pool: &SqlitePool,
) -> Result<Vec<CompletedWork>, sqlx::Error> {
    let rows = sqlx::query(
        "SELECT id, original_uri, output_path, original_size, compressed_size, blake3_hash, duration_ms, processed_at FROM completed_works ORDER BY processed_at DESC"
    )
    .fetch_all(pool)
    .await?;

    let works = rows
        .into_iter()
        .map(|row| CompletedWork {
            id: row.get("id"),
            original_uri: row.get("original_uri"),
            output_path: row.get("output_path"),
            original_size: row.get("original_size"),
            compressed_size: row.get("compressed_size"),
            blake3_hash: row.get("blake3_hash"),
            duration_ms: row.get("duration_ms"),
            processed_at: row.get("processed_at"),
        })
        .collect();

    Ok(works)
}

pub async fn get_completed_works_by_ids(
    pool: &SqlitePool,
    ids: &[String],
) -> Result<Vec<CompletedWork>, sqlx::Error> {
    if ids.is_empty() {
        return Ok(Vec::new());
    }

    let mut query_builder = sqlx::QueryBuilder::new(
        "SELECT id, original_uri, output_path, original_size, compressed_size, blake3_hash, duration_ms, processed_at FROM completed_works WHERE id IN ("
    );
    let mut separated = query_builder.separated(", ");
    for id in ids {
        separated.push_bind(id);
    }
    separated.push_unseparated(") ORDER BY processed_at DESC");

    let rows = query_builder.build().fetch_all(pool).await?;

    let works = rows
        .into_iter()
        .map(|row| CompletedWork {
            id: row.get("id"),
            original_uri: row.get("original_uri"),
            output_path: row.get("output_path"),
            original_size: row.get("original_size"),
            compressed_size: row.get("compressed_size"),
            blake3_hash: row.get("blake3_hash"),
            duration_ms: row.get("duration_ms"),
            processed_at: row.get("processed_at"),
        })
        .collect();

    Ok(works)
}

#[cfg(test)]
mod tests {
    use super::*;

    #[tokio::test]
    async fn record_and_query_works_should_match_specified_ids() {
        let pool = SqlitePool::connect("sqlite::memory:").await.unwrap();
        init_db(&pool).await.unwrap();

        let work1 = CompletedWork {
            id: "id-1".to_string(),
            original_uri: "content://1".to_string(),
            output_path: "/tmp/1.jpg".to_string(),
            original_size: 1000,
            compressed_size: 400,
            blake3_hash: "hash1".to_string(),
            duration_ms: 50,
            processed_at: 100,
        };
        let work2 = CompletedWork {
            id: "id-2".to_string(),
            original_uri: "content://2".to_string(),
            output_path: "/tmp/2.jpg".to_string(),
            original_size: 2000,
            compressed_size: 800,
            blake3_hash: "hash2".to_string(),
            duration_ms: 60,
            processed_at: 200,
        };

        record_completed_work(&pool, &work1).await.unwrap();
        record_completed_work(&pool, &work2).await.unwrap();

        let queried = get_completed_works_by_ids(&pool, &["id-2".to_string()]).await.unwrap();
        assert_eq!(queried.len(), 1);
        assert_eq!(queried[0].id, "id-2");
        assert_eq!(queried[0].original_size, 2000);

        let empty = get_completed_works_by_ids(&pool, &[]).await.unwrap();
        assert!(empty.is_empty());
    }
}
