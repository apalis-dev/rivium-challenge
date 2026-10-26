use crate::db;
use crate::extract::{article_from_html, Article};
use apalis::prelude::*;
use apalis_core::error::AbortError;
use apalis_sqlite::SqlitePool;
use reqwest::{Client, StatusCode};
use serde::{Deserialize, Serialize};
use std::sync::Arc;
use std::time::Duration;
use url::Url;

/// Attempts before a link is parked as FAILED. Apalis' own retry budget is set
/// higher, so this counter (which survives app restarts) is the one that decides.
pub const MAX_ATTEMPTS: i64 = 6;
const MAX_PAGE_BYTES: usize = 4 * 1024 * 1024;

#[derive(Debug, Serialize, Deserialize, Clone)]
pub struct SaveLink {
    pub article_id: String,
    pub url: String,
}

#[derive(Clone)]
pub struct JobContext {
    pub pool: SqlitePool,
    pub http: Client,
}

#[derive(Debug)]
enum FetchError {
    /// Offline, timeout, 5xx, rate-limited: worth trying again later.
    Transient(String),
    /// 404, bad URL, redirect loop: retrying won't help.
    Permanent(String),
}

pub fn http_client() -> reqwest::Result<Client> {
    let roots = rustls::RootCertStore { roots: webpki_roots::TLS_SERVER_ROOTS.to_vec() };
    let mut tls = rustls::ClientConfig::builder_with_provider(Arc::new(rustls::crypto::ring::default_provider()))
        .with_safe_default_protocol_versions()
        .expect("ring supports the default TLS versions")
        .with_root_certificates(roots)
        .with_no_client_auth();
    tls.alpn_protocols = vec![b"h2".to_vec(), b"http/1.1".to_vec()];

    Client::builder()
        .tls_backend_preconfigured(tls)
        // Sites like YouTube sniff the user agent and serve "unsupported browser" pages
        // to anything unfamiliar, so present as current Chrome on Android.
        .user_agent(
            "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) \
             Chrome/141.0.0.0 Mobile Safari/537.36",
        )
        .connect_timeout(Duration::from_secs(10))
        .timeout(Duration::from_secs(30))
        .build()
}

pub async fn save_link(job: SaveLink, ctx: Data<JobContext>) -> Result<(), BoxDynError> {
    let pool = &ctx.pool;
    let Some(attempt) = db::mark_fetching(pool, &job.article_id).await? else {
        tracing::info!("Article {} was deleted before it was fetched; skipping", job.article_id);
        return Ok(());
    };
    tracing::info!("Fetching {} (attempt {attempt}/{MAX_ATTEMPTS})", job.url);

    match fetch_article(&ctx.http, &job.url).await {
        Ok(article) => {
            db::mark_saved(pool, &job.article_id, &article).await?;
            match article.kind {
                "video" => tracing::info!(
                    "Saved video \"{}\" from {} ({}s)",
                    article.title,
                    article.site,
                    article.duration_seconds
                ),
                _ => tracing::info!(
                    "Saved {} \"{}\" from {} ({} words, {} min read)",
                    article.kind,
                    article.title,
                    article.site,
                    article.word_count,
                    article.reading_minutes
                ),
            }
            Ok(())
        }
        Err(FetchError::Permanent(msg)) => {
            tracing::warn!("Giving up on {}: {msg}", job.url);
            db::mark_failed(pool, &job.article_id, &msg, false).await?;
            Err(AbortError::new(msg).into())
        }
        Err(FetchError::Transient(msg)) if attempt >= MAX_ATTEMPTS => {
            tracing::warn!("Still failing after {attempt} attempts, parking {}: {msg}", job.url);
            db::mark_failed(pool, &job.article_id, &msg, true).await?;
            Err(AbortError::new(msg).into())
        }
        Err(FetchError::Transient(msg)) => {
            tracing::info!("Temporary failure for {}: {msg}; will retry", job.url);
            db::mark_retrying(pool, &job.article_id, &msg).await?;
            Err(msg.into())
        }
    }
}

async fn fetch_article(http: &Client, raw_url: &str) -> Result<Article, FetchError> {
    let url = Url::parse(raw_url).map_err(|e| FetchError::Permanent(format!("invalid link: {e}")))?;
    let mut resp = http.get(url.clone()).send().await.map_err(classify)?;

    let status = resp.status();
    if !status.is_success() {
        let msg = format!("server answered {status}");
        let transient = status.is_server_error()
            || matches!(status, StatusCode::REQUEST_TIMEOUT | StatusCode::TOO_MANY_REQUESTS);
        return Err(if transient { FetchError::Transient(msg) } else { FetchError::Permanent(msg) });
    }

    let final_url = resp.url().clone();
    let content_type = resp
        .headers()
        .get(reqwest::header::CONTENT_TYPE)
        .and_then(|v| v.to_str().ok())
        .unwrap_or("text/html")
        .to_ascii_lowercase();
    let is_html = content_type.contains("html") || content_type.contains("xml");
    if !is_html && !content_type.starts_with("text/plain") {
        return Ok(Article::link_only(&final_url, content_type.split(';').next().unwrap_or("file")));
    }

    let mut bytes = Vec::new();
    while let Some(chunk) = resp.chunk().await.map_err(classify)? {
        bytes.extend_from_slice(&chunk);
        if bytes.len() >= MAX_PAGE_BYTES {
            break;
        }
    }
    let text = String::from_utf8_lossy(&bytes).into_owned();

    // HTML parsing is CPU-bound; keep it off the async worker threads.
    tokio::task::spawn_blocking(move || {
        if is_html {
            article_from_html(&text, &final_url)
        } else {
            let mut a = Article::link_only(&final_url, "text");
            a.kind = "article";
            a.word_count = text.split_whitespace().count();
            a.reading_minutes = crate::extract::reading_minutes(a.word_count);
            a.description.clear();
            a.body = text;
            a
        }
    })
    .await
    .map_err(|e| FetchError::Transient(format!("parser crashed: {e}")))
}

fn classify(e: reqwest::Error) -> FetchError {
    if e.is_builder() || e.is_redirect() {
        FetchError::Permanent(e.to_string())
    } else if e.is_timeout() {
        FetchError::Transient("timed out".into())
    } else if e.is_connect() {
        FetchError::Transient("no connection".into())
    } else {
        FetchError::Transient(e.to_string())
    }
}
