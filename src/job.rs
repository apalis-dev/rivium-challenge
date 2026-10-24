use apalis::prelude::*;
use apalis_sqlite::{Config, SqliteStorage};
use serde::{Deserialize, Serialize};
use sha2::{Digest, Sha256};
use sqlx::SqlitePool;
use sqlx::sqlite::SqliteConnectOptions;
use std::path::{Path, PathBuf};
use std::time::Duration;

/// The one job type: fetch `url`, record its title and a content hash.
#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct DigestLink {
    pub url: String,
}

static SETUP: tokio::sync::Mutex<()> = tokio::sync::Mutex::const_new(());

/// Pusher and worker must share this: the queue name is part of the config, so a
/// mismatch means jobs are pushed to a queue nobody is listening on.
pub fn queue_config() -> Config {
    Config::default()
        .queue("digests")
        // Short heartbeat: jobs orphaned by a killed app are re-queued in ~10s, not ~60s.
        .heartbeat_interval(Duration::from_secs(5))
}

pub fn db_path(root: &Path) -> PathBuf {
    root.join("jobs.db")
}

/// Open (creating if needed) the shared database and run migrations.
pub async fn open(root: &Path) -> Result<SqlitePool, BoxDynError> {
    let _setup = SETUP.lock().await; // services open the db concurrently at startup
    std::fs::create_dir_all(root)?;
    let opts = SqliteConnectOptions::new()
        .filename(db_path(root))
        .create_if_missing(true)
        .busy_timeout(Duration::from_secs(10));
    let pool = SqlitePool::connect_with(opts).await?;
    SqliteStorage::setup(&pool).await?;
    sqlx::query(
        "CREATE TABLE IF NOT EXISTS digests (
            id INTEGER PRIMARY KEY AUTOINCREMENT,
            url TEXT NOT NULL,
            title TEXT,
            sha256 TEXT,
            bytes INTEGER,
            created_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP
        )",
    )
    .execute(&pool)
    .await?;
    Ok(pool)
}

/// Push a job. Safe to call while the worker is running (same SQLite file).
pub async fn enqueue(root: &Path, url: &str) -> Result<(), BoxDynError> {
    let pool = open(root).await?;
    push(&pool, url).await
}

pub async fn push(pool: &SqlitePool, url: &str) -> Result<(), BoxDynError> {
    let mut backend = SqliteStorage::new(pool).with_config(queue_config());
    let task = TaskBuilder::new(DigestLink {
        url: url.to_string(),
    })
    .max_attempts(5)
    .build();
    backend.push_task(task).await?;
    Ok(())
}

fn extract_title(html: &str) -> Option<String> {
    let lower = html.to_ascii_lowercase();
    let start = lower.find("<title")?;
    let start = start + lower[start..].find('>')? + 1;
    let end = start + lower[start..].find("</title")?;
    let t = html[start..end]
        .split_whitespace()
        .collect::<Vec<_>>()
        .join(" ");
    (!t.is_empty()).then_some(t)
}

/// Plenty of sites 403 a request with no User-Agent, so look like a browser.
fn client() -> reqwest::Client {
    reqwest::Client::builder()
        .user_agent("Mozilla/5.0 (Linux; Android 15) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130 Mobile Safari/537.36 Pocketcrab/0.1")
        .timeout(Duration::from_secs(20))
        .build()
        .expect("http client")
}

pub async fn digest(job: DigestLink, pool: Data<SqlitePool>) -> Result<(), BoxDynError> {
    // Failed attempts are retried by Apalis; pause so retries are not instant.
    let resp = match client()
        .get(&job.url)
        .send()
        .await
        .and_then(|r| r.error_for_status())
    {
        Ok(r) => r,
        Err(e) => {
            tokio::time::sleep(Duration::from_secs(5)).await;
            return Err(e.into());
        }
    };
    let body = resp.bytes().await?;
    let hash: String = Sha256::digest(&body)
        .iter()
        .map(|b| format!("{b:02x}"))
        .collect();
    let title = extract_title(&String::from_utf8_lossy(&body));
    sqlx::query("INSERT INTO digests (url, title, sha256, bytes) VALUES (?, ?, ?, ?)")
        .bind(&job.url)
        .bind(&title)
        .bind(&hash)
        .bind(body.len() as i64)
        .execute(&*pool)
        .await?;
    println!("digested {} -> {:?} {}", job.url, title, &hash[..12]);
    Ok(())
}

/// One row of what the UI shows: a finished digest, a job in flight, or a job that gave up.
#[derive(Serialize)]
struct Item {
    key: String,
    state: &'static str, // "done" | "working" | "failed"
    url: String,
    title: Option<String>,
    sha: Option<String>,
    bytes: i64,
    error: Option<String>,
    attempts: i64,
    ts: i64,
}

type Row = (
    String,
    String,
    Option<String>,
    Option<String>,
    Option<String>,
    i64,
    i64,
    Option<String>,
    Option<String>,
    i64,
);

/// Write `<root>/state.json` for the Android UI. The UI must not open jobs.db itself:
/// Android's SQLite and the SQLite inside this library would share the file's locks and
/// shared-memory mapping inside one process, which crashed with SIGBUS.
pub async fn write_snapshot(pool: &SqlitePool, root: &Path) -> Result<(), BoxDynError> {
    let rows: Vec<Row> = sqlx::query_as(
        "SELECT 'd', CAST(id AS TEXT), url, title, sha256, bytes,
                CAST(strftime('%s', created_at) AS INTEGER), NULL, NULL, 0 FROM digests
         UNION ALL
         SELECT 'j', id, json_extract(CAST(job AS TEXT), '$.url'), NULL, NULL, 0, run_at,
                status, json_extract(last_result, '$.Err'), attempts FROM Jobs WHERE status != 'Done'
         ORDER BY 7 DESC",
    )
    .fetch_all(pool)
    .await?;
    let items: Vec<Item> = rows
        .into_iter()
        .map(
            |(kind, id, url, title, sha, bytes, ts, status, error, attempts)| Item {
                state: match (kind.as_str(), status.as_deref()) {
                    ("d", _) => "done",
                    (_, Some("Failed" | "Killed")) => "failed",
                    _ => "working",
                },
                key: format!("{kind}{id}"),
                url: url.unwrap_or_default(),
                title,
                sha,
                bytes,
                error,
                attempts,
                ts,
            },
        )
        .collect();
    let tmp = root.join(".state.json.tmp");
    std::fs::write(&tmp, serde_json::to_vec(&items)?)?;
    std::fs::rename(tmp, root.join("state.json"))?; // atomic: readers never see a partial file
    Ok(())
}
