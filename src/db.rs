use crate::extract::Article;
use crate::job::SaveLink;
use apalis::prelude::TaskSink;
use apalis_sqlite::callback::HookCallbackListener;
use apalis_sqlite::{SqlitePool, SqliteStorage};
use serde::Serialize;
use sqlx::Row;
use std::path::Path;
use std::sync::atomic::{AtomicU32, Ordering};
use std::time::{SystemTime, UNIX_EPOCH};

pub type DbResult<T> = Result<T, Box<dyn std::error::Error + Send + Sync>>;

pub fn now_ms() -> i64 {
    SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .unwrap_or_default()
        .as_millis() as i64
}

fn new_id() -> String {
    static SEQ: AtomicU32 = AtomicU32::new(0);
    format!("a{:x}{:04x}", now_ms(), SEQ.fetch_add(1, Ordering::Relaxed) & 0xffff)
}

/// Opens the database with an update hook so the worker wakes the moment a job row is inserted.
pub async fn connect(db_path: &Path) -> DbResult<(SqlitePool, HookCallbackListener)> {
    if let Some(parent) = db_path.parent() {
        tokio::fs::create_dir_all(parent).await?;
    }
    let path = db_path.to_str().ok_or("database path is not UTF-8")?;
    let uri = format!("sqlite://{}?mode=rwc", path.replace('\\', "/"));
    let (pool, hook) = SqliteStorage::connect_with_callback(&uri)?;

    SqliteStorage::setup(&pool).await?;
    sqlx::raw_sql(
        r#"
        CREATE TABLE IF NOT EXISTS articles (
            id              TEXT PRIMARY KEY,
            url             TEXT NOT NULL UNIQUE,
            status          TEXT NOT NULL,
            title           TEXT NOT NULL DEFAULT '',
            site            TEXT NOT NULL DEFAULT '',
            description     TEXT NOT NULL DEFAULT '',
            body            TEXT NOT NULL DEFAULT '',
            word_count      INTEGER NOT NULL DEFAULT 0,
            reading_minutes INTEGER NOT NULL DEFAULT 0,
            attempts        INTEGER NOT NULL DEFAULT 0,
            last_error      TEXT NOT NULL DEFAULT '',
            retryable       INTEGER NOT NULL DEFAULT 0,
            notified        INTEGER NOT NULL DEFAULT 1,
            created_at      INTEGER NOT NULL,
            updated_at      INTEGER NOT NULL,
            saved_at        INTEGER,
            read_at         INTEGER
        );
        CREATE INDEX IF NOT EXISTS idx_articles_created ON articles(created_at DESC);
        "#,
    )
    .execute(&pool)
    .await?;
    add_missing_columns(&pool).await?;
    Ok((pool, hook))
}

/// Columns added after the first release; ALTER keeps existing reading lists.
async fn add_missing_columns(pool: &SqlitePool) -> DbResult<()> {
    let existing: Vec<String> = sqlx::query("SELECT name FROM pragma_table_info('articles')")
        .fetch_all(pool)
        .await?
        .iter()
        .map(|r| r.get("name"))
        .collect();
    let wanted = [
        ("kind", "ALTER TABLE articles ADD COLUMN kind TEXT NOT NULL DEFAULT 'article'"),
        ("author", "ALTER TABLE articles ADD COLUMN author TEXT NOT NULL DEFAULT ''"),
        ("duration_seconds", "ALTER TABLE articles ADD COLUMN duration_seconds INTEGER NOT NULL DEFAULT 0"),
    ];
    for (column, ddl) in wanted {
        if !existing.iter().any(|c| c == column) {
            sqlx::query(ddl).execute(pool).await?;
        }
    }
    Ok(())
}

/// Apalis refuses to register a worker name seen within the last heartbeat, assuming
/// another process owns it. Rivium's lock on the data root guarantees we're the only
/// host, so a leftover row is from a killed run: expire it so registration succeeds
/// and Apalis re-queues that run's in-flight jobs straight away.
pub async fn expire_stale_worker(pool: &SqlitePool, worker: &str) -> DbResult<()> {
    sqlx::query("UPDATE Workers SET last_seen = 0 WHERE id = ?")
        .bind(worker)
        .execute(pool)
        .await?;
    Ok(())
}

#[derive(Debug, Serialize)]
pub struct SaveOutcome {
    pub id: String,
    pub duplicate: bool,
    pub status: String,
}

/// Stores the link and queues a fetch. Sharing a link that's already saved is a no-op;
/// sharing one that previously failed tries it again.
pub async fn save_link(pool: &SqlitePool, url: &url::Url) -> DbResult<SaveOutcome> {
    let existing = sqlx::query("SELECT id, status FROM articles WHERE url = ?")
        .bind(url.as_str())
        .fetch_optional(pool)
        .await?;

    let id = match existing {
        Some(row) => {
            let id: String = row.get("id");
            let status: String = row.get("status");
            if status != "FAILED" {
                return Ok(SaveOutcome { id, duplicate: true, status });
            }
            id
        }
        None => {
            let id = new_id();
            let now = now_ms();
            sqlx::query(
                "INSERT INTO articles (id, url, status, title, site, created_at, updated_at) VALUES (?, ?, 'QUEUED', ?, ?, ?, ?)",
            )
            .bind(&id)
            .bind(url.as_str())
            .bind(url.as_str())
            .bind(crate::extract::site_name(url))
            .bind(now)
            .bind(now)
            .execute(pool)
            .await?;
            id
        }
    };

    enqueue(pool, &id, url.as_str()).await?;
    Ok(SaveOutcome { id, duplicate: false, status: "QUEUED".into() })
}

async fn enqueue(pool: &SqlitePool, id: &str, url: &str) -> DbResult<()> {
    sqlx::query(
        "UPDATE articles SET status = 'QUEUED', attempts = 0, last_error = '', notified = 1, updated_at = ? WHERE id = ?",
    )
    .bind(now_ms())
    .bind(id)
    .execute(pool)
    .await?;

    let mut storage: SqliteStorage<SaveLink> = SqliteStorage::new(pool);
    if let Err(e) = storage.push(SaveLink { article_id: id.into(), url: url.into() }).await {
        mark_failed(pool, id, &format!("could not queue: {e}"), true).await?;
        return Err(e.into());
    }
    Ok(())
}

pub async fn retry(pool: &SqlitePool, id: &str) -> DbResult<bool> {
    let row = sqlx::query("SELECT url FROM articles WHERE id = ? AND status = 'FAILED'")
        .bind(id)
        .fetch_optional(pool)
        .await?;
    let Some(row) = row else { return Ok(false) };
    let url: String = row.get("url");
    enqueue(pool, id, &url).await?;
    Ok(true)
}

/// Re-queues links that failed for network reasons, e.g. when connectivity comes back.
pub async fn retry_failed(pool: &SqlitePool) -> DbResult<usize> {
    let rows = sqlx::query("SELECT id, url FROM articles WHERE status = 'FAILED' AND retryable = 1")
        .fetch_all(pool)
        .await?;
    for row in &rows {
        let id: String = row.get("id");
        let url: String = row.get("url");
        enqueue(pool, &id, &url).await?;
    }
    Ok(rows.len())
}

pub async fn delete(pool: &SqlitePool, id: &str) -> DbResult<bool> {
    let res = sqlx::query("DELETE FROM articles WHERE id = ?").bind(id).execute(pool).await?;
    Ok(res.rows_affected() > 0)
}

/// Bumps the attempt counter and returns it; the job uses it to decide when to give up.
pub async fn mark_fetching(pool: &SqlitePool, id: &str) -> DbResult<Option<i64>> {
    let row = sqlx::query(
        "UPDATE articles SET status = 'FETCHING', attempts = attempts + 1, updated_at = ? WHERE id = ? RETURNING attempts",
    )
    .bind(now_ms())
    .bind(id)
    .fetch_optional(pool)
    .await?;
    Ok(row.map(|r| r.get("attempts")))
}

pub async fn mark_saved(pool: &SqlitePool, id: &str, a: &Article) -> DbResult<()> {
    let now = now_ms();
    sqlx::query(
        "UPDATE articles SET status = 'SAVED', kind = ?, title = ?, site = ?, author = ?, description = ?, body = ?, \
         word_count = ?, reading_minutes = ?, duration_seconds = ?, last_error = '', retryable = 0, notified = 0, \
         saved_at = ?, updated_at = ? WHERE id = ?",
    )
    .bind(a.kind)
    .bind(&a.title)
    .bind(&a.site)
    .bind(&a.author)
    .bind(&a.description)
    .bind(&a.body)
    .bind(a.word_count as i64)
    .bind(a.reading_minutes as i64)
    .bind(a.duration_seconds as i64)
    .bind(now)
    .bind(now)
    .bind(id)
    .execute(pool)
    .await?;
    Ok(())
}

pub async fn mark_retrying(pool: &SqlitePool, id: &str, error: &str) -> DbResult<()> {
    sqlx::query("UPDATE articles SET status = 'RETRYING', last_error = ?, updated_at = ? WHERE id = ?")
        .bind(error)
        .bind(now_ms())
        .bind(id)
        .execute(pool)
        .await?;
    Ok(())
}

pub async fn mark_failed(pool: &SqlitePool, id: &str, error: &str, retryable: bool) -> DbResult<()> {
    sqlx::query(
        "UPDATE articles SET status = 'FAILED', last_error = ?, retryable = ?, notified = 0, updated_at = ? WHERE id = ?",
    )
    .bind(error)
    .bind(retryable)
    .bind(now_ms())
    .bind(id)
    .execute(pool)
    .await?;
    Ok(())
}

#[derive(Debug, Serialize)]
pub struct ArticleRow {
    pub id: String,
    pub url: String,
    pub status: String,
    pub kind: String,
    pub title: String,
    pub site: String,
    pub author: String,
    pub description: String,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub body: Option<String>,
    pub word_count: i64,
    pub reading_minutes: i64,
    pub duration_seconds: i64,
    pub attempts: i64,
    pub last_error: String,
    pub created_at: i64,
    pub saved_at: Option<i64>,
    pub read_at: Option<i64>,
}

macro_rules! list_columns {
    () => {
        "id, url, status, kind, title, site, author, description, word_count, reading_minutes, \
         duration_seconds, attempts, last_error, created_at, saved_at, read_at"
    };
}

fn row_to_article(row: &sqlx::sqlite::SqliteRow, with_body: bool) -> ArticleRow {
    ArticleRow {
        id: row.get("id"),
        url: row.get("url"),
        status: row.get("status"),
        kind: row.get("kind"),
        title: row.get("title"),
        site: row.get("site"),
        author: row.get("author"),
        description: row.get("description"),
        body: with_body.then(|| row.get("body")),
        word_count: row.get("word_count"),
        reading_minutes: row.get("reading_minutes"),
        duration_seconds: row.get("duration_seconds"),
        attempts: row.get("attempts"),
        last_error: row.get("last_error"),
        created_at: row.get("created_at"),
        saved_at: row.get("saved_at"),
        read_at: row.get("read_at"),
    }
}

pub async fn list_articles(pool: &SqlitePool, limit: i64) -> DbResult<Vec<ArticleRow>> {
    let rows = sqlx::query(concat!("SELECT ", list_columns!(), " FROM articles ORDER BY created_at DESC LIMIT ?"))
        .bind(limit)
        .fetch_all(pool)
        .await?;
    Ok(rows.iter().map(|r| row_to_article(r, false)).collect())
}

/// Opening an article counts as reading it.
pub async fn open_article(pool: &SqlitePool, id: &str) -> DbResult<Option<ArticleRow>> {
    sqlx::query("UPDATE articles SET read_at = COALESCE(read_at, ?) WHERE id = ? AND status = 'SAVED'")
        .bind(now_ms())
        .bind(id)
        .execute(pool)
        .await?;
    let row = sqlx::query(concat!("SELECT ", list_columns!(), ", body FROM articles WHERE id = ?"))
        .bind(id)
        .fetch_optional(pool)
        .await?;
    Ok(row.map(|r| row_to_article(&r, true)))
}

/// Finished articles the phone hasn't announced yet. Stored in the DB so a kill
/// between "saved" and "notified" still produces the notification on next launch.
pub async fn take_unnotified(pool: &SqlitePool) -> DbResult<Vec<ArticleRow>> {
    let rows = sqlx::query(concat!(
        "UPDATE articles SET notified = 1 WHERE notified = 0 AND status IN ('SAVED', 'FAILED') RETURNING ",
        list_columns!()
    ))
    .fetch_all(pool)
    .await?;
    Ok(rows.iter().map(|r| row_to_article(r, false)).collect())
}

#[derive(Debug, Serialize, Default)]
pub struct Counts {
    pub unread: i64,
    pub pending: i64,
    pub failed: i64,
}

pub async fn counts(pool: &SqlitePool) -> DbResult<Counts> {
    let row = sqlx::query(
        "SELECT \
           COALESCE(SUM(status = 'SAVED' AND read_at IS NULL), 0) AS unread, \
           COALESCE(SUM(status IN ('QUEUED', 'FETCHING', 'RETRYING')), 0) AS pending, \
           COALESCE(SUM(status = 'FAILED'), 0) AS failed \
         FROM articles",
    )
    .fetch_one(pool)
    .await?;
    Ok(Counts { unread: row.get("unread"), pending: row.get("pending"), failed: row.get("failed") })
}
