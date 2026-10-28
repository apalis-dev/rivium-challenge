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

/// A failure the queue should not retry is permanent; everything else is worth
/// another attempt, which resumes from the bytes already on disk.
#[derive(Debug)]
pub enum MediaError {
    Permanent(String),
    Transient(String),
}

impl MediaError {
    pub fn reason(&self) -> &str {
        match self {
            Self::Permanent(reason) | Self::Transient(reason) => reason,
        }
    }
}

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

/// reqwest hides the useful part in the source chain; keep all of it.
fn describe(error: &reqwest::Error) -> String {
    use std::error::Error as _;
    let mut text = error.to_string();
    let mut source = error.source();
    while let Some(cause) = source {
        text.push_str(&format!(" <- {cause}"));
        source = cause.source();
    }
    text
}

fn client() -> Result<reqwest::Client, MediaError> {
    reqwest::Client::builder()
        .user_agent(UA)
        .timeout(std::time::Duration::from_secs(300))
        .build()
        .map_err(|e| MediaError::Transient(format!("cannot set up the http client: {e}")))
}

/// Fetch the post document itself.
async fn post(link: &str) -> Result<(String, Value), MediaError> {
    let id = post_id(link)
        .ok_or_else(|| MediaError::Permanent(format!("not an X post link: {link}")))?;
    let url = SYNDICATION.replace("{id}", &id);

    let response = client()?
        .get(&url)
        .send()
        .await
        .map_err(|e| MediaError::Transient(format!("could not reach X: {}", describe(&e))))?;
    if !response.status().is_success() {
        return Err(MediaError::Permanent(format!("post {id}: HTTP {}", response.status())));
    }
    let body: Value = response
        .json()
        .await
        .map_err(|e| MediaError::Transient(format!("unreadable reply from X: {e}")))?;

    if body.get("__typename").and_then(Value::as_str) == Some("TweetTombstone") {
        return Err(MediaError::Permanent(format!(
            "post {id} is not publicly available (deleted, protected or limited)"
        )));
    }
    Ok((id, body))
}

/// Ask X for the post and pick the best mp4.
pub async fn resolve(link: &str) -> Result<Resolved, MediaError> {
    let (id, body) = post(link).await?;

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

    best.ok_or_else(|| MediaError::Permanent(format!("post {id} has no video")))
}

/// Stream to `<name>.part`, resuming from whatever is already there, and only
/// rename to the final name once the file is complete. A dropped connection
/// therefore costs the bytes missed, not the whole download.
pub async fn download(url: &str, path: &Path) -> Result<u64, MediaError> {
    let part = path.with_file_name(format!(
        "{}.part",
        path.file_name().and_then(|n| n.to_str()).unwrap_or("download")
    ));
    if let Some(parent) = part.parent() {
        tokio::fs::create_dir_all(parent)
            .await
            .map_err(|e| MediaError::Transient(format!("cannot create the download folder: {e}")))?;
    }

    let mut already = tokio::fs::metadata(&part).await.map(|m| m.len()).unwrap_or(0);
    let mut request = client()?.get(url);
    if already > 0 {
        request = request.header("range", format!("bytes={already}-"));
    }
    let mut response = request
        .send()
        .await
        .map_err(|e| MediaError::Transient(format!("download interrupted: {}", describe(&e))))?;

    let status = response.status();
    let resuming = status == reqwest::StatusCode::PARTIAL_CONTENT;
    if !status.is_success() {
        return Err(MediaError::Transient(format!("stream refused: HTTP {status}")));
    }
    if already > 0 && !resuming {
        // The server chose to send the whole file; start over cleanly.
        already = 0;
    }

    let mut file = if resuming {
        tokio::fs::OpenOptions::new()
            .append(true)
            .open(&part)
            .await
            .map_err(|e| MediaError::Transient(format!("cannot reopen the partial file: {e}")))?
    } else {
        tokio::fs::File::create(&part)
            .await
            .map_err(|e| MediaError::Transient(format!("cannot create the partial file: {e}")))?
    };

    let mut written = already;
    loop {
        let chunk = response
            .chunk()
            .await
            .map_err(|e| MediaError::Transient(format!("connection dropped while downloading: {e}")))?;
        let Some(chunk) = chunk else { break };
        file.write_all(&chunk)
            .await
            .map_err(|e| MediaError::Transient(format!("cannot write to storage: {e}")))?;
        written += chunk.len() as u64;
    }
    file.flush()
        .await
        .map_err(|e| MediaError::Transient(format!("cannot flush to storage: {e}")))?;
    drop(file);

    tokio::fs::rename(&part, path)
        .await
        .map_err(|e| MediaError::Transient(format!("cannot finalise the file: {e}")))?;
    Ok(written)
}

/// Save one link: resolve it, download the best stream, report what happened.
pub async fn save_video(link: &str, into: &Path) -> Result<Value, MediaError> {
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

/// Collect what the card needs and keep the images on disk, so the UI can draw
/// without touching the network.
pub async fn save_card(link: &str, into: &Path) -> Result<Value, MediaError> {
    let (id, post) = self::post(link).await?;
    tokio::fs::create_dir_all(into)
        .await
        .map_err(|e| MediaError::Transient(format!("cannot create the download folder: {e}")))?;

    let text = post.get("text").and_then(Value::as_str).unwrap_or_default().to_owned();
    let name = post.pointer("/user/name").and_then(Value::as_str).unwrap_or("unknown").to_owned();
    let handle = post
        .pointer("/user/screen_name")
        .and_then(Value::as_str)
        .unwrap_or("unknown")
        .to_owned();
    let when = post
        .get("created_at")
        .and_then(Value::as_str)
        .map(|stamp| stamp.chars().take(10).collect::<String>())
        .unwrap_or_default();

    let mut avatar = None;
    if let Some(url) = post.pointer("/user/profile_image_url_https").and_then(Value::as_str) {
        let url = url.replace("_normal.", "_200x200.");
        let path = into.join(format!("{id}_avatar.jpg"));
        if download(&url, &path).await.is_ok() {
            avatar = Some(path.to_string_lossy().to_string());
        }
    }

    let mut thumb = None;
    if let Some(url) = post
        .pointer("/mediaDetails/0/media_url_https")
        .and_then(Value::as_str)
    {
        let path = into.join(format!("{id}_thumb.jpg"));
        if download(url, &path).await.is_ok() {
            thumb = Some(path.to_string_lossy().to_string());
        }
    }

    Ok(serde_json::json!({
        "kind": "card",
        "author": handle,
        "name": name,
        "date": when,
        "text": text,
        "avatar": avatar,
        "thumb": thumb,
        "source": format!("x.com/{handle}/status/{id}"),
    }))
}
