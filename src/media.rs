//! Resolving and downloading media for a shared link.
//!
//! Public posts are read through the endpoint X uses for embedded posts. It
//! returns every stream with its bitrate and dimensions, so we can take the
//! highest one instead of guessing.

use std::path::{Path, PathBuf};

use serde_json::Value;
use tokio::io::AsyncWriteExt;

const UA: &str = "Mozilla/5.0 (Linux; Android 15) x-pocket/0.1";
const SYNDICATION: &str = "https://cdn.syndication.twimg.com/tweet-result?id={id}&lang=en&token=a";

/// Where downloads land. Passed to the worker as data.
#[derive(Clone, Debug)]
pub struct Downloads(pub PathBuf);

/// What we learned about a post before downloading it.
pub struct Resolved {
    pub author: String,
    pub url: String,
    pub height: u32,
    pub bitrate: u64,
}

pub fn post_id(link: &str) -> Option<String> {
    let marker = "/status/";
    let start = link.find(marker)? + marker.len();
    let rest = &link[start..];
    let id: String = rest.chars().take_while(char::is_ascii_digit).collect();
    (id.len() >= 15).then_some(id)
}

fn client() -> Result<reqwest::Client, String> {
    reqwest::Client::builder()
        .user_agent(UA)
        .timeout(std::time::Duration::from_secs(120))
        .build()
        .map_err(|e| format!("client: {e}"))
}

/// Ask X for the post and pick the best mp4.
pub async fn resolve(link: &str) -> Result<Resolved, String> {
    let id = post_id(link).ok_or_else(|| format!("not an X post link: {link}"))?;
    let url = SYNDICATION.replace("{id}", &id);

    let response = client()?.get(&url).send().await.map_err(|e| format!("fetch: {e}"))?;
    if !response.status().is_success() {
        return Err(format!("post {id}: HTTP {}", response.status()));
    }
    let body: Value = response.json().await.map_err(|e| format!("decode: {e}"))?;

    if body.get("__typename").and_then(Value::as_str) == Some("TweetTombstone") {
        return Err(format!("post {id} is not publicly available"));
    }

    let author = body
        .pointer("/user/screen_name")
        .and_then(Value::as_str)
        .unwrap_or("unknown")
        .to_owned();

    let mut best: Option<Resolved> = None;
    if let Some(media) = body.get("mediaDetails").and_then(Value::as_array) {
        for item in media {
            let Some(variants) = item
                .pointer("/video_info/variants")
                .and_then(Value::as_array)
            else {
                continue;
            };
            for variant in variants {
                let Some(stream) = variant.get("url").and_then(Value::as_str) else { continue };
                if !stream.contains(".mp4") {
                    continue;
                }
                let bitrate = variant.get("bitrate").and_then(Value::as_u64).unwrap_or(0);
                let height = stream
                    .split('/')
                    .find_map(|part| part.split_once('x').and_then(|(_, h)| h.parse::<u32>().ok()))
                    .unwrap_or(0);
                if best.as_ref().is_none_or(|current| bitrate > current.bitrate) {
                    best = Some(Resolved {
                        author: author.clone(),
                        url: stream.to_owned(),
                        height,
                        bitrate,
                    });
                }
            }
        }
    }

    best.ok_or_else(|| format!("post {id} has no video (text or photos only)"))
}

/// Stream the file to disk, a chunk at a time.
pub async fn download(url: &str, path: &Path) -> Result<u64, String> {
    let mut response = client()?.get(url).send().await.map_err(|e| format!("stream: {e}"))?;
    if !response.status().is_success() {
        return Err(format!("stream: HTTP {}", response.status()));
    }

    if let Some(parent) = path.parent() {
        tokio::fs::create_dir_all(parent)
            .await
            .map_err(|e| format!("mkdir: {e}"))?;
    }

    let mut file = tokio::fs::File::create(path)
        .await
        .map_err(|e| format!("create: {e}"))?;
    let mut written = 0u64;
    while let Some(chunk) = response.chunk().await.map_err(|e| format!("read: {e}"))? {
        file.write_all(&chunk).await.map_err(|e| format!("write: {e}"))?;
        written += chunk.len() as u64;
    }
    file.flush().await.map_err(|e| format!("flush: {e}"))?;
    Ok(written)
}

/// Save one link: resolve it, download the best stream, report what happened.
pub async fn save_video(link: &str, into: &Path) -> Result<Value, String> {
    let resolved = resolve(link).await?;
    let name = format!(
        "{}_{}p.mp4",
        resolved.author,
        if resolved.height > 0 { resolved.height.to_string() } else { "video".into() }
    );
    let path = into.join(&name);
    let bytes = download(&resolved.url, &path).await?;

    Ok(serde_json::json!({
        "file": path.to_string_lossy(),
        "name": name,
        "bytes": bytes,
        "height": resolved.height,
        "bitrate": resolved.bitrate,
        "author": resolved.author,
    }))
}
