use apalis::prelude::*;
use apalis_sqlite::{Config, SqliteStorage};
use serde::{Deserialize, Serialize};
use sha2::{Digest, Sha256};
use sqlx::SqlitePool;
use sqlx::sqlite::SqliteConnectOptions;
use std::path::{Path, PathBuf};
use std::time::Duration;

/// The one job type: fetch `url`, record its title and a content hash.
///
/// With `watch_every` set it is a *watch*: after each check the job schedules itself again
/// (Apalis `run_after`) and records a change when the page's visible text differs.
#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct DigestLink {
    pub url: String,
    #[serde(default)]
    pub watch_every: Option<u64>,
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
    sqlx::query(
        "CREATE TABLE IF NOT EXISTS watches (
            url TEXT PRIMARY KEY,
            title TEXT,
            interval_secs INTEGER NOT NULL,
            last_hash TEXT,
            last_text TEXT,
            last_checked INTEGER,
            changes INTEGER NOT NULL DEFAULT 0,
            last_change_at INTEGER,
            last_error TEXT
        )",
    )
    .execute(&pool)
    .await?;
    sqlx::query(
        "CREATE TABLE IF NOT EXISTS cookies (
            host TEXT PRIMARY KEY,
            header TEXT NOT NULL,
            saved_at INTEGER NOT NULL
        )",
    )
    .execute(&pool)
    .await?;
    sqlx::query(
        "CREATE TABLE IF NOT EXISTS changes (
            id INTEGER PRIMARY KEY AUTOINCREMENT,
            url TEXT NOT NULL,
            title TEXT,
            before TEXT,
            after TEXT,
            at INTEGER NOT NULL
        )",
    )
    .execute(&pool)
    .await?;
    Ok(pool)
}

/// Push a one-off digest. Safe to call while the worker is running (same SQLite file).
pub async fn enqueue(root: &Path, url: &str) -> Result<(), BoxDynError> {
    let pool = open(root).await?;
    push(&pool, url).await
}

pub async fn push(pool: &SqlitePool, url: &str) -> Result<(), BoxDynError> {
    push_job(pool, once(url), None).await
}

fn once(url: &str) -> DigestLink {
    DigestLink {
        url: url.to_string(),
        watch_every: None,
    }
}

async fn push_job(
    pool: &SqlitePool,
    job: DigestLink,
    after: Option<Duration>,
) -> Result<(), BoxDynError> {
    let mut backend = SqliteStorage::new(pool).with_config(queue_config());
    let mut task = TaskBuilder::new(job).max_attempts(5);
    if let Some(delay) = after {
        task = task.run_after(delay);
    }
    backend.push_task(task.build()).await?;
    Ok(())
}

/// One line of the inbox protocol:
///   `<url>`                      digest once
///   `watch <seconds> <url>`      re-check every N seconds and report changes
///   `unwatch <url>`              stop watching
///   `cookie <host> <header>`     use this login for pages on <host>
///   `uncookie <host>`            forget that login
/// Returns Err only for database trouble (the caller keeps the file and retries).
pub async fn handle_command(pool: &SqlitePool, text: &str) -> Result<(), BoxDynError> {
    let t = text.trim();
    if let Some(rest) = t.strip_prefix("cookie ") {
        // `cookie <host> <Cookie header>`: the session captured by the app's sign-in screen.
        if let Some((host, header)) = rest.trim().split_once(' ') {
            sqlx::query(
                "INSERT INTO cookies (host, header, saved_at) VALUES (?, ?, ?)
                 ON CONFLICT(host) DO UPDATE SET header = excluded.header, saved_at = excluded.saved_at",
            )
            .bind(host)
            .bind(header.trim())
            .bind(now())
            .execute(pool)
            .await?;
        }
    } else if let Some(host) = t.strip_prefix("uncookie ") {
        sqlx::query("DELETE FROM cookies WHERE host = ?")
            .bind(host.trim())
            .execute(pool)
            .await?;
    } else if let Some(url) = t.strip_prefix("unwatch ") {
        sqlx::query("DELETE FROM watches WHERE url = ?")
            .bind(url.trim())
            .execute(pool)
            .await?;
    } else if let Some(rest) = t.strip_prefix("watch ") {
        let Some((secs, url)) = rest.trim().split_once(' ') else {
            return Ok(());
        };
        let (Ok(secs), true) = (secs.parse::<u64>(), url.trim().starts_with("http")) else {
            return Ok(());
        };
        let secs = secs.max(5);
        // One watch per URL: the first insert starts the self-rescheduling chain.
        let started =
            sqlx::query("INSERT OR IGNORE INTO watches (url, interval_secs) VALUES (?, ?)")
                .bind(url.trim())
                .bind(secs as i64)
                .execute(pool)
                .await?
                .rows_affected();
        if started == 1 {
            let job = DigestLink {
                url: url.trim().to_string(),
                watch_every: Some(secs),
            };
            push_job(pool, job, None).await?;
        }
    } else if t.starts_with("http") {
        push(pool, t).await?;
    }
    Ok(())
}

/// The text a person would read: no scripts, styles or tags, whitespace collapsed.
/// Hashing this (not the raw HTML) keeps nonces and markup churn from looking like changes.
fn visible_text(html: &str) -> String {
    let mut out = String::with_capacity(html.len() / 2);
    let lower = html.to_ascii_lowercase();
    let (mut i, mut in_tag, mut skip_until): (usize, bool, Option<&str>) = (0, false, None);
    let bytes = html.as_bytes();
    while i < bytes.len() {
        if let Some(end) = skip_until {
            match lower[i..].find(end) {
                Some(p) => i += p + end.len(),
                None => break,
            }
            skip_until = None;
            in_tag = false;
            continue;
        }
        let c = bytes[i];
        if c == b'<' {
            if lower[i..].starts_with("<script") {
                skip_until = Some("</script>");
            } else if lower[i..].starts_with("<style") {
                skip_until = Some("</style>");
            } else if lower[i..].starts_with("<!--") {
                skip_until = Some("-->");
            } else {
                in_tag = true;
                out.push(' ');
            }
            i += 1;
        } else if c == b'>' && in_tag {
            in_tag = false;
            i += 1;
        } else if !in_tag {
            // copy one full UTF-8 character
            let ch = html[i..].chars().next().unwrap();
            out.push(ch);
            i += ch.len_utf8();
        } else {
            i += 1;
        }
    }
    let text = out
        .replace("&nbsp;", " ")
        .replace("&amp;", "&")
        .replace("&lt;", "<")
        .replace("&gt;", ">")
        .replace("&quot;", "\"")
        .replace("&#39;", "'");
    text.split_whitespace().collect::<Vec<_>>().join(" ")
}

/// Short before/after excerpts around where two texts differ, cut on word boundaries with two
/// words of context either side: "...X Price: $500 Stock: 12 left" -> "...X Price: $450 Stock: 9 left".
fn diff_snippet(old: &str, new: &str) -> (String, String) {
    let a: Vec<&str> = old.split_whitespace().collect();
    let b: Vec<&str> = new.split_whitespace().collect();
    let mut p = 0;
    while p < a.len() && p < b.len() && a[p] == b[p] {
        p += 1;
    }
    let mut s = 0;
    while s < a.len() - p && s < b.len() - p && a[a.len() - 1 - s] == b[b.len() - 1 - s] {
        s += 1;
    }
    const CTX: usize = 2;
    const MAX_CHARS: usize = 160;
    let from = p.saturating_sub(CTX);
    let cut = |v: &[&str], end: usize| -> String {
        let to = (end + CTX).min(v.len());
        let mut t = v[from.min(to)..to].join(" ");
        if t.chars().count() > MAX_CHARS {
            t = t.chars().take(MAX_CHARS).collect::<String>() + "\u{2026}";
        } else if to < v.len() {
            t.push('\u{2026}');
        }
        if from > 0 {
            t.insert(0, '\u{2026}');
        }
        t
    };
    (cut(&a, a.len() - s), cut(&b, b.len() - s))
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

async fn fetch(job: &DigestLink, pool: &SqlitePool) -> Result<(String, Vec<u8>), BoxDynError> {
    let mut req = client().get(&job.url);
    // A saved login is sent to its own host only. (reqwest also drops the Cookie header if a
    // redirect leaves the host.) The value is never printed or logged.
    if let Some(host) = reqwest::Url::parse(&job.url)
        .ok()
        .and_then(|u| u.host_str().map(str::to_owned))
    {
        let cookie: Option<String> =
            sqlx::query_scalar("SELECT header FROM cookies WHERE host = ?")
                .bind(host)
                .fetch_optional(pool)
                .await?;
        if let Some(cookie) = cookie {
            req = req.header(reqwest::header::COOKIE, cookie);
        }
    }
    let body = req.send().await?.error_for_status()?.bytes().await?;
    let hash: String = Sha256::digest(&body)
        .iter()
        .map(|b| format!("{b:02x}"))
        .collect();
    Ok((hash, body.to_vec()))
}

fn now() -> i64 {
    std::time::SystemTime::now()
        .duration_since(std::time::UNIX_EPOCH)
        .map(|d| d.as_secs() as i64)
        .unwrap_or(0)
}

pub async fn digest(job: DigestLink, pool: Data<SqlitePool>) -> Result<(), BoxDynError> {
    if let Some(every) = job.watch_every {
        return watch_check(job, every, &pool).await;
    }
    // Failed attempts are retried by Apalis; pause so retries are not instant.
    let (hash, body) = match fetch(&job, &pool).await {
        Ok(ok) => ok,
        Err(e) => {
            tokio::time::sleep(Duration::from_secs(5)).await;
            return Err(e);
        }
    };
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

/// One check of a watched page. It never returns Err: a failed fetch is recorded and the next
/// check is scheduled anyway, so a flaky network cannot silently end the watch.
async fn watch_check(job: DigestLink, every: u64, pool: &SqlitePool) -> Result<(), BoxDynError> {
    let row: Option<(Option<String>, Option<String>)> =
        sqlx::query_as("SELECT last_hash, last_text FROM watches WHERE url = ?")
            .bind(&job.url)
            .fetch_optional(pool)
            .await?;
    let Some((last_hash, last_text)) = row else {
        return Ok(()); // unwatched while this check was waiting: end the chain
    };
    match fetch(&job, pool).await {
        Err(e) => {
            sqlx::query("UPDATE watches SET last_checked = ?, last_error = ? WHERE url = ?")
                .bind(now())
                .bind(e.to_string())
                .bind(&job.url)
                .execute(pool)
                .await?;
            println!("watch error {} -> {e}", job.url);
        }
        Ok((_, body)) => {
            let html = String::from_utf8_lossy(&body);
            let title = extract_title(&html);
            let mut text = visible_text(&html);
            text.truncate(
                text.char_indices()
                    .nth(200_000)
                    .map_or(text.len(), |(i, _)| i),
            );
            let hash: String = Sha256::digest(text.as_bytes())
                .iter()
                .map(|b| format!("{b:02x}"))
                .collect();
            let changed = last_hash.as_deref().is_some_and(|h| h != hash);
            if changed {
                let (before, after) = diff_snippet(last_text.as_deref().unwrap_or(""), &text);
                sqlx::query(
                    "INSERT INTO changes (url, title, before, after, at) VALUES (?, ?, ?, ?, ?)",
                )
                .bind(&job.url)
                .bind(&title)
                .bind(&before)
                .bind(&after)
                .bind(now())
                .execute(pool)
                .await?;
                println!("CHANGED {} : {before:?} -> {after:?}", job.url);
            } else {
                println!("watched {} (unchanged) {}", job.url, &hash[..12]);
            }
            sqlx::query(
                "UPDATE watches SET title = ?, last_hash = ?, last_text = ?, last_checked = ?,
                        last_error = NULL, changes = changes + ?,
                        last_change_at = CASE WHEN ? = 1 THEN ? ELSE last_change_at END
                 WHERE url = ?",
            )
            .bind(&title)
            .bind(&hash)
            .bind(&text)
            .bind(now())
            .bind(changed as i64)
            .bind(changed as i64)
            .bind(now())
            .bind(&job.url)
            .execute(pool)
            .await?;
        }
    }
    // Apalis scheduling: the next check is a new task that becomes runnable after `every` seconds.
    push_job(pool, job, Some(Duration::from_secs(every))).await?;
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

#[derive(Serialize)]
struct Watch {
    url: String,
    title: Option<String>,
    every: i64,
    checked: Option<i64>,
    changes: i64,
    changed_at: Option<i64>,
    error: Option<String>,
}

#[derive(Serialize)]
struct Change {
    id: i64,
    url: String,
    title: Option<String>,
    before: Option<String>,
    after: Option<String>,
    at: i64,
}

type WatchRow = (
    String,
    Option<String>,
    i64,
    Option<i64>,
    i64,
    Option<i64>,
    Option<String>,
);
type ChangeRow = (
    i64,
    String,
    Option<String>,
    Option<String>,
    Option<String>,
    i64,
);

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
           AND json_extract(CAST(job AS TEXT), '$.watch_every') IS NULL
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
    let watch_rows: Vec<WatchRow> = sqlx::query_as(
        "SELECT url, title, interval_secs, last_checked, changes, last_change_at, last_error
         FROM watches ORDER BY COALESCE(last_change_at, 0) DESC, url",
    )
    .fetch_all(pool)
    .await?;
    let watches: Vec<Watch> = watch_rows
        .into_iter()
        .map(
            |(url, title, every, checked, changes, changed_at, error)| Watch {
                url,
                title,
                every,
                checked,
                changes,
                changed_at,
                error,
            },
        )
        .collect();
    let change_rows: Vec<ChangeRow> = sqlx::query_as(
        "SELECT id, url, title, before, after, at FROM changes ORDER BY id DESC LIMIT 50",
    )
    .fetch_all(pool)
    .await?;
    let changes: Vec<Change> = change_rows
        .into_iter()
        .map(|(id, url, title, before, after, at)| Change {
            id,
            url,
            title,
            before,
            after,
            at,
        })
        .collect();
    let signed_in: Vec<String> = sqlx::query_scalar("SELECT host FROM cookies ORDER BY host")
        .fetch_all(pool)
        .await?;
    let tmp = root.join(".state.json.tmp");
    let state = serde_json::json!({
        "items": items, "watches": watches, "changes": changes, "signed_in": signed_in
    });
    std::fs::write(&tmp, serde_json::to_vec(&state)?)?;
    std::fs::rename(tmp, root.join("state.json"))?; // atomic: readers never see a partial file
    Ok(())
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn visible_text_drops_scripts_styles_comments_and_tags() {
        let html = "<html><head><style>p{color:red}</style><script>var n=1;</script></head>\
                    <body><!-- hidden --><h1>Hi &amp; bye</h1><p>Price:\n  $5</p></body></html>";
        assert_eq!(visible_text(html), "Hi & bye Price: $5");
    }

    #[test]
    fn visible_text_ignores_markup_only_changes() {
        let a = visible_text("<p class=\"a\">Price: $5</p><script>nonce=1</script>");
        let b = visible_text("<div><p class=\"b\">Price: $5</p></div><script>nonce=2</script>");
        assert_eq!(a, b);
    }

    #[test]
    fn visible_text_keeps_non_ascii() {
        assert_eq!(
            visible_text("<p>caf\u{e9} \u{1f980}</p>"),
            "caf\u{e9} \u{1f980}"
        );
    }

    #[test]
    fn diff_snippet_shows_only_the_changed_words() {
        let filler = "lorem ipsum ".repeat(50);
        let old = format!("{filler}Price: $500 Stock: 12 left {filler}");
        let new = format!("{filler}Price: $450 Stock: 9 left {filler}");
        let (before, after) = diff_snippet(&old, &new);
        assert!(before.contains("$500") && after.contains("$450"));
        assert!(before.contains("12") && after.contains("9"));
        assert!(before.starts_with('\u{2026}') && before.ends_with('\u{2026}'));
        assert!(before.chars().count() < 80, "{before}");
    }

    #[test]
    fn diff_snippet_handles_pure_insertions_and_deletions() {
        let (b, a) = diff_snippet("abc", "abc def");
        assert_eq!((b.as_str(), a.as_str()), ("abc", "abc def"));
        let (b, a) = diff_snippet("abc def", "abc");
        assert_eq!((b.as_str(), a.as_str()), ("abc def", "abc"));
    }

    #[test]
    fn diff_snippet_caps_a_total_rewrite() {
        let old = "alpha ".repeat(100);
        let new = "omega ".repeat(100);
        let (b, a) = diff_snippet(&old, &new);
        assert!(b.chars().count() <= 162 && a.chars().count() <= 162);
    }

    #[tokio::test]
    async fn commands_start_one_watch_per_url_and_unwatch_removes_it() {
        let dir = std::env::temp_dir().join(format!("pocketcrab-test-{}", now()));
        let pool = open(&dir).await.unwrap();
        for _ in 0..2 {
            handle_command(&pool, "watch 60 https://example.com")
                .await
                .unwrap();
        }
        let n: i64 = sqlx::query_scalar("SELECT COUNT(*) FROM watches")
            .fetch_one(&pool)
            .await
            .unwrap();
        let jobs: i64 = sqlx::query_scalar("SELECT COUNT(*) FROM Jobs")
            .fetch_one(&pool)
            .await
            .unwrap();
        assert_eq!(
            (n, jobs),
            (1, 1),
            "second watch of the same URL must not start a second chain"
        );
        handle_command(&pool, "unwatch https://example.com")
            .await
            .unwrap();
        let n: i64 = sqlx::query_scalar("SELECT COUNT(*) FROM watches")
            .fetch_one(&pool)
            .await
            .unwrap();
        assert_eq!(n, 0);
        pool.close().await;
        let _ = std::fs::remove_dir_all(dir);
    }

    #[tokio::test]
    async fn cookie_commands_save_replace_and_forget_a_login_per_host() {
        let dir = std::env::temp_dir().join(format!("pocketcrab-cookies-{}", now()));
        let pool = open(&dir).await.unwrap();
        handle_command(&pool, "cookie example.com session=one; theme=dark")
            .await
            .unwrap();
        handle_command(&pool, "cookie example.com session=two")
            .await
            .unwrap();
        handle_command(&pool, "cookie other.org a=b").await.unwrap();
        let header: String =
            sqlx::query_scalar("SELECT header FROM cookies WHERE host = 'example.com'")
                .fetch_one(&pool)
                .await
                .unwrap();
        assert_eq!(
            header, "session=two",
            "a new login replaces the old one for that host"
        );
        handle_command(&pool, "uncookie example.com").await.unwrap();
        let hosts: Vec<String> = sqlx::query_scalar("SELECT host FROM cookies")
            .fetch_all(&pool)
            .await
            .unwrap();
        assert_eq!(hosts, vec!["other.org".to_string()]);
        pool.close().await;
        let _ = std::fs::remove_dir_all(dir);
    }
}
