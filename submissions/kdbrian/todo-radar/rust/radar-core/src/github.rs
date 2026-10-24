//! Thin GitHub client: Device Flow sign-in, repo listing/search, and zipball downloads.

use std::time::Duration;

use apalis::prelude::BoxDynError;
use reqwest::{Client, RequestBuilder, Response, StatusCode, header};
use serde::{Deserialize, Serialize};

const API: &str = "https://api.github.com";
const LOGIN: &str = "https://github.com/login";

/// HTTPS client with ring + bundled Mozilla roots, so Android needs no platform verifier.
pub fn http_client() -> Result<Client, BoxDynError> {
    let _ = rustls::crypto::ring::default_provider().install_default();
    let roots = rustls::RootCertStore {
        roots: webpki_roots::TLS_SERVER_ROOTS.to_vec(),
    };
    let tls = rustls::ClientConfig::builder()
        .with_root_certificates(roots)
        .with_no_client_auth();
    Ok(Client::builder()
        .tls_backend_preconfigured(tls)
        .user_agent(concat!("todo-radar/", env!("CARGO_PKG_VERSION")))
        .connect_timeout(Duration::from_secs(10))
        .build()?)
}

/// Failures the scan job must tell apart: transient ones are retried, the rest are final.
#[derive(Debug)]
pub enum GhError {
    Transient(String),
    Permanent(String),
}

impl std::fmt::Display for GhError {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        match self {
            GhError::Transient(m) | GhError::Permanent(m) => f.write_str(m),
        }
    }
}

impl std::error::Error for GhError {}

impl From<reqwest::Error> for GhError {
    fn from(error: reqwest::Error) -> Self {
        GhError::Transient(format!("network: {error}"))
    }
}

fn api(client: &Client, token: Option<&str>, url: String) -> RequestBuilder {
    let request = client
        .get(url)
        .header(header::ACCEPT, "application/vnd.github+json")
        .header("X-GitHub-Api-Version", "2022-11-28");
    match token {
        Some(token) => request.bearer_auth(token),
        None => request,
    }
}

/// Map GitHub status codes onto retry semantics, including rate limits.
async fn check(response: Response) -> Result<Response, GhError> {
    let status = response.status();
    if status.is_success() {
        return Ok(response);
    }
    let remaining = response
        .headers()
        .get("x-ratelimit-remaining")
        .and_then(|v| v.to_str().ok())
        .map(str::to_owned);
    let body = response.text().await.unwrap_or_default();
    let message = serde_json::from_str::<serde_json::Value>(&body)
        .ok()
        .and_then(|v| {
            v["message"].as_str().or(v["error_description"].as_str()).map(str::to_owned)
        })
        .unwrap_or(body);
    Err(match status {
        StatusCode::TOO_MANY_REQUESTS => GhError::Transient(format!("rate limited: {message}")),
        StatusCode::FORBIDDEN if remaining.as_deref() == Some("0") => {
            GhError::Transient(format!("rate limited: {message}"))
        }
        s if s.is_server_error() => GhError::Transient(format!("GitHub {s}: {message}")),
        s => GhError::Permanent(format!("GitHub {s}: {message}")),
    })
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct Repo {
    pub owner: String,
    pub name: String,
    pub full_name: String,
    pub description: Option<String>,
    pub size_kb: u64,
    pub stars: u64,
    pub language: Option<String>,
    pub pushed_at: Option<String>,
    pub default_branch: String,
    pub private: bool,
}

#[derive(Deserialize)]
struct RawRepo {
    name: String,
    full_name: String,
    description: Option<String>,
    size: u64,
    stargazers_count: u64,
    language: Option<String>,
    pushed_at: Option<String>,
    default_branch: String,
    private: bool,
    owner: RawOwner,
}

#[derive(Deserialize)]
struct RawOwner {
    login: String,
}

impl From<RawRepo> for Repo {
    fn from(raw: RawRepo) -> Self {
        Repo {
            owner: raw.owner.login,
            name: raw.name,
            full_name: raw.full_name,
            description: raw.description,
            size_kb: raw.size,
            stars: raw.stargazers_count,
            language: raw.language,
            pushed_at: raw.pushed_at,
            default_branch: raw.default_branch,
            private: raw.private,
        }
    }
}

pub async fn repo(client: &Client, token: Option<&str>, owner: &str, name: &str) -> Result<Repo, GhError> {
    let response = check(api(client, token, format!("{API}/repos/{owner}/{name}")).send().await?).await?;
    Ok(response.json::<RawRepo>().await?.into())
}

/// The signed-in user's repos, most recently pushed first.
pub async fn my_repos(client: &Client, token: &str) -> Result<Vec<Repo>, GhError> {
    let url = format!("{API}/user/repos?sort=pushed&per_page=50&affiliation=owner,collaborator,organization_member");
    let response = check(api(client, Some(token), url).send().await?).await?;
    Ok(response.json::<Vec<RawRepo>>().await?.into_iter().map(Repo::from).collect())
}

pub async fn search_repos(client: &Client, token: Option<&str>, query: &str) -> Result<Vec<Repo>, GhError> {
    #[derive(Deserialize)]
    struct Page {
        items: Vec<RawRepo>,
    }
    let request = api(client, token, format!("{API}/search/repositories"))
        .query(&[("q", query), ("per_page", "30")]);
    let page: Page = check(request.send().await?).await?.json().await?;
    Ok(page.items.into_iter().map(Repo::from).collect())
}

pub async fn head_sha(client: &Client, token: Option<&str>, owner: &str, name: &str, branch: &str) -> Result<String, GhError> {
    #[derive(Deserialize)]
    struct Commit {
        sha: String,
    }
    let url = format!("{API}/repos/{owner}/{name}/commits/{branch}");
    Ok(check(api(client, token, url).send().await?).await?.json::<Commit>().await?.sha)
}

/// Start (or resume, when `offset > 0`) a zipball download. Returns the response and whether
/// the server honoured the range; if not, the caller must restart from zero.
pub async fn zipball(
    client: &Client,
    token: Option<&str>,
    owner: &str,
    name: &str,
    sha: &str,
    offset: u64,
) -> Result<(Response, bool), GhError> {
    let mut request = api(client, token, format!("{API}/repos/{owner}/{name}/zipball/{sha}"));
    if offset > 0 {
        request = request.header(header::RANGE, format!("bytes={offset}-"));
    }
    let response = check(request.send().await?).await?;
    let resumed = offset > 0 && response.status() == StatusCode::PARTIAL_CONTENT;
    Ok((response, resumed))
}

/// Raw file contents at a commit (used by the code peek once the zip is gone).
pub async fn file_at(
    client: &Client,
    token: Option<&str>,
    owner: &str,
    name: &str,
    sha: &str,
    path: &str,
) -> Result<Vec<u8>, GhError> {
    let encoded: Vec<String> = path.split('/').map(urlencode).collect();
    let url = format!("{API}/repos/{owner}/{name}/contents/{}?ref={sha}", encoded.join("/"));
    let response = api(client, token, url)
        .header(header::ACCEPT, "application/vnd.github.raw")
        .send()
        .await?;
    Ok(check(response).await?.bytes().await?.to_vec())
}

fn urlencode(segment: &str) -> String {
    segment
        .bytes()
        .map(|b| match b {
            b'A'..=b'Z' | b'a'..=b'z' | b'0'..=b'9' | b'-' | b'_' | b'.' | b'~' => (b as char).to_string(),
            _ => format!("%{b:02X}"),
        })
        .collect()
}

pub async fn user_login(client: &Client, token: &str) -> Result<String, GhError> {
    #[derive(Deserialize)]
    struct User {
        login: String,
    }
    Ok(check(api(client, Some(token), format!("{API}/user")).send().await?).await?.json::<User>().await?.login)
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct DeviceCode {
    pub device_code: String,
    pub user_code: String,
    pub verification_uri: String,
    pub expires_in: u64,
    pub interval: u64,
}

pub async fn device_code(client: &Client, client_id: &str) -> Result<DeviceCode, GhError> {
    let response = client
        .post(format!("{LOGIN}/device/code"))
        .header(header::ACCEPT, "application/json")
        .form(&[("client_id", client_id), ("scope", "")])
        .send()
        .await?;
    Ok(check(response).await?.json().await?)
}

pub enum Poll {
    Pending,
    SlowDown,
    Token(String),
    Failed(String),
}

/// One Device Flow exchange attempt; the client drives polling at the advertised interval.
pub async fn poll_token(client: &Client, client_id: &str, device_code: &str) -> Result<Poll, GhError> {
    #[derive(Deserialize)]
    struct Reply {
        access_token: Option<String>,
        error: Option<String>,
        error_description: Option<String>,
    }
    let response = client
        .post(format!("{LOGIN}/oauth/access_token"))
        .header(header::ACCEPT, "application/json")
        .form(&[
            ("client_id", client_id),
            ("device_code", device_code),
            ("grant_type", "urn:ietf:params:oauth:grant-type:device_code"),
        ])
        .send()
        .await?;
    let reply: Reply = check(response).await?.json().await?;
    Ok(match (reply.access_token, reply.error.as_deref()) {
        (Some(token), _) => Poll::Token(token),
        (None, Some("authorization_pending")) => Poll::Pending,
        (None, Some("slow_down")) => Poll::SlowDown,
        (None, error) => Poll::Failed(
            reply
                .error_description
                .or(error.map(str::to_owned))
                .unwrap_or_else(|| "sign-in failed".into()),
        ),
    })
}
