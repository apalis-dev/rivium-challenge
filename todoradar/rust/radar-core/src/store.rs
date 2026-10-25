//! All SQL for the app tables. Apalis' own tables are never touched here.

use apalis::prelude::BoxDynError;
use apalis_sqlite::SqlitePool;
use serde::Serialize;
use sqlx::FromRow;

use crate::scanner::Todo;

type Result<T> = std::result::Result<T, BoxDynError>;

#[derive(Debug, Clone, Serialize, FromRow)]
pub struct Scan {
    pub id: i64,
    pub source_kind: String,
    pub owner: Option<String>,
    pub repo: Option<String>,
    pub zip_path: Option<String>,
    pub sha: Option<String>,
    pub state: String,
    pub stage: String,
    pub bytes_done: i64,
    pub bytes_total: Option<i64>,
    pub entries_done: i64,
    pub entries_total: Option<i64>,
    pub todo_count: i64,
    pub new_count: i64,
    pub resolved_count: i64,
    pub prev_scan_id: Option<i64>,
    pub attempts: i64,
    pub keep_files: bool,
    pub files_deleted: bool,
    pub cleanup_at: Option<i64>,
    pub error: Option<String>,
    pub created_at: i64,
    pub finished_at: Option<i64>,
}

impl Scan {
    pub fn is_github(&self) -> bool {
        self.source_kind == "github"
    }

    /// Human label: `owner/repo` or the zip's file name.
    pub fn title(&self) -> String {
        match (&self.owner, &self.repo, &self.zip_path) {
            (Some(owner), Some(repo), _) => format!("{owner}/{repo}"),
            (_, _, Some(path)) => std::path::Path::new(path)
                .file_name()
                .map(|n| n.to_string_lossy().into_owned())
                .unwrap_or_else(|| path.clone()),
            _ => format!("scan #{}", self.id),
        }
    }

    /// Overall progress 0..=1 across the four stages, for progress bars.
    pub fn progress(&self) -> f64 {
        let fraction = |done: i64, total: Option<i64>| match total {
            Some(t) if t > 0 => (done as f64 / t as f64).clamp(0.0, 1.0),
            _ => 0.0,
        };
        // GitHub streams archives without a length; approach (never reach) the stage end.
        let download = match self.bytes_total {
            Some(_) => fraction(self.bytes_done, self.bytes_total),
            None => 1.0 - (-(self.bytes_done as f64) / 8_000_000.0).exp(),
        };
        match self.stage.as_str() {
            "resolve" => 0.02,
            "download" => 0.05 + 0.45 * download,
            "scan" => 0.5 + 0.45 * fraction(self.entries_done, self.entries_total),
            "diff" => 0.97,
            _ => 1.0,
        }
    }
}

#[derive(Debug, Clone, Serialize, FromRow)]
pub struct TodoRow {
    pub path: String,
    pub line: i64,
    pub tag: String,
    pub text: String,
    pub fingerprint: String,
    pub is_new: bool,
}

const SCAN_COLUMNS: &str = "id, source_kind, owner, repo, zip_path, sha, state, stage, bytes_done, \
    bytes_total, entries_done, entries_total, todo_count, new_count, resolved_count, prev_scan_id, \
    attempts, keep_files, files_deleted, cleanup_at, error, created_at, finished_at";

pub async fn insert_github(pool: &SqlitePool, owner: &str, repo: &str) -> Result<i64> {
    Ok(sqlx::query("INSERT INTO scans (source_kind, owner, repo) VALUES ('github', ?, ?)")
        .bind(owner)
        .bind(repo)
        .execute(pool)
        .await?
        .last_insert_rowid())
}

pub async fn insert_zip(pool: &SqlitePool, path: &str) -> Result<i64> {
    Ok(sqlx::query("INSERT INTO scans (source_kind, zip_path) VALUES ('zip', ?)")
        .bind(path)
        .execute(pool)
        .await?
        .last_insert_rowid())
}

pub async fn scan(pool: &SqlitePool, id: i64) -> Result<Option<Scan>> {
    Ok(sqlx::query_as(sqlx::AssertSqlSafe(format!("SELECT {SCAN_COLUMNS} FROM scans WHERE id = ?")))
        .bind(id)
        .fetch_optional(pool)
        .await?)
}

pub async fn scans(pool: &SqlitePool, active_only: bool, limit: i64) -> Result<Vec<Scan>> {
    let filter = if active_only {
        "WHERE state IN ('queued', 'running') OR (state = 'done' AND files_deleted = 0 AND keep_files = 0 AND cleanup_at IS NOT NULL)"
    } else {
        ""
    };
    Ok(sqlx::query_as(sqlx::AssertSqlSafe(format!(
        "SELECT {SCAN_COLUMNS} FROM scans {filter} ORDER BY id DESC LIMIT ?"
    )))
    .bind(limit)
    .fetch_all(pool)
    .await?)
}

pub async fn start_attempt(pool: &SqlitePool, id: i64) -> Result<()> {
    sqlx::query("UPDATE scans SET state = 'running', attempts = attempts + 1, error = NULL WHERE id = ?")
        .bind(id)
        .execute(pool)
        .await?;
    Ok(())
}

pub async fn set_stage(pool: &SqlitePool, id: i64, stage: &str) -> Result<()> {
    sqlx::query("UPDATE scans SET stage = ? WHERE id = ?")
        .bind(stage)
        .bind(id)
        .execute(pool)
        .await?;
    Ok(())
}

pub async fn set_resolved(pool: &SqlitePool, id: i64, sha: &str, size_hint: Option<i64>) -> Result<()> {
    sqlx::query("UPDATE scans SET sha = ?, bytes_total = COALESCE(bytes_total, ?) WHERE id = ?")
        .bind(sha)
        .bind(size_hint)
        .bind(id)
        .execute(pool)
        .await?;
    Ok(())
}

pub async fn set_download(pool: &SqlitePool, id: i64, done: i64, total: Option<i64>) -> Result<()> {
    sqlx::query("UPDATE scans SET bytes_done = ?, bytes_total = COALESCE(?, bytes_total) WHERE id = ?")
        .bind(done)
        .bind(total)
        .bind(id)
        .execute(pool)
        .await?;
    Ok(())
}

/// Persist one scanned chunk and its resume index atomically.
pub async fn checkpoint(pool: &SqlitePool, id: i64, todos: &[Todo], next_entry: usize, total: usize) -> Result<()> {
    let mut tx = pool.begin().await?;
    for todo in todos {
        sqlx::query("INSERT INTO todos (scan_id, path, line, tag, text, fingerprint) VALUES (?, ?, ?, ?, ?, ?)")
            .bind(id)
            .bind(&todo.path)
            .bind(todo.line)
            .bind(&todo.tag)
            .bind(&todo.text)
            .bind(&todo.fingerprint)
            .execute(&mut *tx)
            .await?;
    }
    sqlx::query(
        "UPDATE scans SET entries_done = ?, entries_total = ?, todo_count = todo_count + ? WHERE id = ?",
    )
    .bind(next_entry as i64)
    .bind(total as i64)
    .bind(todos.len() as i64)
    .bind(id)
    .execute(&mut *tx)
    .await?;
    tx.commit().await?;
    Ok(())
}

/// The latest finished scan of the same source, used as the diff baseline.
pub async fn previous(pool: &SqlitePool, scan: &Scan) -> Result<Option<Scan>> {
    Ok(sqlx::query_as(sqlx::AssertSqlSafe(format!(
        "SELECT {SCAN_COLUMNS} FROM scans WHERE state = 'done' AND id < ? AND source_kind = ? \
         AND owner IS ? AND repo IS ? AND zip_path IS ? ORDER BY id DESC LIMIT 1"
    )))
    .bind(scan.id)
    .bind(&scan.source_kind)
    .bind(&scan.owner)
    .bind(&scan.repo)
    .bind(&scan.zip_path)
    .fetch_optional(pool)
    .await?)
}

/// Copy a finished scan's TODOs when the commit did not change ("no changes").
pub async fn copy_todos(pool: &SqlitePool, from: i64, to: i64) -> Result<()> {
    let mut tx = pool.begin().await?;
    sqlx::query("DELETE FROM todos WHERE scan_id = ?").bind(to).execute(&mut *tx).await?;
    sqlx::query(
        "INSERT INTO todos (scan_id, path, line, tag, text, fingerprint) \
         SELECT ?, path, line, tag, text, fingerprint FROM todos WHERE scan_id = ?",
    )
    .bind(to)
    .bind(from)
    .execute(&mut *tx)
    .await?;
    sqlx::query("UPDATE scans SET todo_count = (SELECT COUNT(*) FROM todos WHERE scan_id = ?) WHERE id = ?")
        .bind(to)
        .bind(to)
        .execute(&mut *tx)
        .await?;
    tx.commit().await?;
    Ok(())
}

/// Mark new TODOs against the baseline, record counts and finish the scan.
pub async fn finish(pool: &SqlitePool, id: i64, prev: Option<i64>, cleanup_at: i64) -> Result<()> {
    let mut tx = pool.begin().await?;
    let (new_count, resolved_count) = match prev {
        Some(prev) => {
            let new = sqlx::query(
                "UPDATE todos SET is_new = 1 WHERE scan_id = ? AND fingerprint NOT IN \
                 (SELECT fingerprint FROM todos WHERE scan_id = ?)",
            )
            .bind(id)
            .bind(prev)
            .execute(&mut *tx)
            .await?
            .rows_affected() as i64;
            let resolved: i64 = sqlx::query_scalar(
                "SELECT COUNT(*) FROM todos WHERE scan_id = ? AND fingerprint NOT IN \
                 (SELECT fingerprint FROM todos WHERE scan_id = ?)",
            )
            .bind(prev)
            .bind(id)
            .fetch_one(&mut *tx)
            .await?;
            (new, resolved)
        }
        None => (0, 0),
    };
    sqlx::query(
        "UPDATE scans SET state = 'done', stage = 'done', new_count = ?, resolved_count = ?, \
         prev_scan_id = ?, cleanup_at = ?, finished_at = strftime('%s', 'now') WHERE id = ?",
    )
    .bind(new_count)
    .bind(resolved_count)
    .bind(prev)
    .bind(cleanup_at)
    .bind(id)
    .execute(&mut *tx)
    .await?;
    tx.commit().await?;
    Ok(())
}

pub async fn fail(pool: &SqlitePool, id: i64, message: &str) -> Result<()> {
    sqlx::query("UPDATE scans SET state = 'failed', error = ?, finished_at = strftime('%s', 'now') WHERE id = ?")
        .bind(message)
        .bind(id)
        .execute(pool)
        .await?;
    Ok(())
}

/// A retryable failure: keep the scan running but show why it is waiting.
pub async fn note_error(pool: &SqlitePool, id: i64, message: &str) -> Result<()> {
    sqlx::query("UPDATE scans SET error = ? WHERE id = ?")
        .bind(message)
        .bind(id)
        .execute(pool)
        .await?;
    Ok(())
}

pub async fn todos(pool: &SqlitePool, id: i64, only_new: bool) -> Result<Vec<TodoRow>> {
    let filter = if only_new { "AND is_new = 1" } else { "" };
    Ok(sqlx::query_as(sqlx::AssertSqlSafe(format!(
        "SELECT path, line, tag, text, fingerprint, is_new FROM todos WHERE scan_id = ? {filter} \
         ORDER BY path, line LIMIT 10000"
    )))
    .bind(id)
    .fetch_all(pool)
    .await?)
}

/// Fingerprints present in the baseline but gone now, for the report.
pub async fn resolved(pool: &SqlitePool, id: i64, prev: i64) -> Result<Vec<TodoRow>> {
    Ok(sqlx::query_as(
        "SELECT path, line, tag, text, fingerprint, 0 AS is_new FROM todos WHERE scan_id = ? \
         AND fingerprint NOT IN (SELECT fingerprint FROM todos WHERE scan_id = ?) ORDER BY path, line LIMIT 500",
    )
    .bind(prev)
    .bind(id)
    .fetch_all(pool)
    .await?)
}

pub async fn set_keep(pool: &SqlitePool, id: i64, keep: bool) -> Result<bool> {
    Ok(sqlx::query("UPDATE scans SET keep_files = ? WHERE id = ?")
        .bind(keep)
        .bind(id)
        .execute(pool)
        .await?
        .rows_affected()
        > 0)
}

pub async fn mark_files_deleted(pool: &SqlitePool, id: i64) -> Result<()> {
    sqlx::query("UPDATE scans SET files_deleted = 1 WHERE id = ?")
        .bind(id)
        .execute(pool)
        .await?;
    Ok(())
}

/// Delete all but the newest `keep` finished scans of this scan's source. Returns removed ids.
pub async fn prune(pool: &SqlitePool, scan: &Scan, keep: u32) -> Result<Vec<i64>> {
    let mut tx = pool.begin().await?;
    let ids: Vec<i64> = sqlx::query_scalar(
        "SELECT id FROM scans WHERE state IN ('done', 'failed') AND source_kind = ? AND owner IS ? \
         AND repo IS ? AND zip_path IS ? ORDER BY id DESC LIMIT -1 OFFSET ?",
    )
    .bind(&scan.source_kind)
    .bind(&scan.owner)
    .bind(&scan.repo)
    .bind(&scan.zip_path)
    .bind(keep as i64)
    .fetch_all(&mut *tx)
    .await?;
    for id in &ids {
        sqlx::query("DELETE FROM todos WHERE scan_id = ?").bind(id).execute(&mut *tx).await?;
        sqlx::query("DELETE FROM scans WHERE id = ?").bind(id).execute(&mut *tx).await?;
    }
    tx.commit().await?;
    Ok(ids)
}

pub async fn token(pool: &SqlitePool) -> Result<Option<(String, String)>> {
    Ok(sqlx::query_as("SELECT token, login FROM auth WHERE id = 1")
        .fetch_optional(pool)
        .await?)
}

pub async fn save_token(pool: &SqlitePool, token: &str, login: &str) -> Result<()> {
    sqlx::query("INSERT OR REPLACE INTO auth (id, token, login) VALUES (1, ?, ?)")
        .bind(token)
        .bind(login)
        .execute(pool)
        .await?;
    Ok(())
}

pub async fn clear_token(pool: &SqlitePool) -> Result<()> {
    sqlx::query("DELETE FROM auth").execute(pool).await?;
    Ok(())
}
