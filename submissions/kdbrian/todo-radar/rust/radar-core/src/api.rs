//! Localhost API the thin clients talk to. Bound to 127.0.0.1 and guarded by a per-launch token.

use std::{
    fmt::Write as _,
    sync::{Arc, Mutex},
    time::{Duration, Instant},
};

use apalis::prelude::*;
use axum::{
    Json, Router,
    extract::{Path, Query, Request, State},
    http::{StatusCode, header},
    middleware::{self, Next},
    response::{IntoResponse, Response},
    routing::{get, post},
};
use serde::{Deserialize, Serialize};

use crate::{
    Engine, ScanRepo, CleanupScan, github, scanner,
    store::{self, Scan, TodoRow},
};

#[derive(Clone)]
struct Api {
    engine: Engine,
    device: Arc<Mutex<Option<DeviceSession>>>,
}

struct DeviceSession {
    code: github::DeviceCode,
    expires: Instant,
    next_poll: Instant,
    interval: Duration,
    error: Option<String>,
}

pub fn router(engine: Engine) -> Router {
    let api = Api {
        engine,
        device: Arc::new(Mutex::new(None)),
    };
    let protected = Router::new()
        .route("/auth/device", post(start_device))
        .route("/auth/status", get(auth_status))
        .route("/auth/logout", post(logout))
        .route("/repos", get(repos))
        .route("/scans", get(list_scans).post(create_scan))
        .route("/scans/{id}", get(get_scan))
        .route("/scans/{id}/todos", get(todos))
        .route("/scans/{id}/report", get(report))
        .route("/scans/{id}/peek", get(peek))
        .route("/scans/{id}/keep", post(keep))
        .route("/scans/{id}/cleanup", post(cleanup_now))
        .route_layer(middleware::from_fn_with_state(api.clone(), require_token));
    Router::new()
        .route("/health", get(health))
        .merge(protected)
        .with_state(api)
}

/// The current GitHub token: a Device Flow sign-in, else the configured fallback.
pub async fn github_token(engine: &Engine) -> Option<String> {
    if let Ok(pool) = engine.db.ready().await {
        if let Ok(Some((token, _))) = store::token(pool).await {
            return Some(token);
        }
    }
    Some(engine.config.github_token.clone()).filter(|t| !t.is_empty())
}

pub struct ApiError(StatusCode, String);

impl IntoResponse for ApiError {
    /// Caller errors keep their message (clients show it); server errors are logged here and
    /// left to rivium-http's safe envelope, which never discloses private detail.
    fn into_response(self) -> Response {
        if self.0.is_server_error() {
            tracing::warn!(status = %self.0, error = %self.1, "request failed");
            return self.0.into_response();
        }
        let mut response = (self.0, Json(serde_json::json!({ "error": self.1 }))).into_response();
        response.extensions_mut().insert(rivium_http::NoEnvelope);
        response
    }
}

impl From<BoxDynError> for ApiError {
    fn from(error: BoxDynError) -> Self {
        ApiError(StatusCode::INTERNAL_SERVER_ERROR, error.to_string())
    }
}

/// Permanent GitHub refusals (bad client id, device flow disabled, 404) are the caller's to fix,
/// so their message is shown; transient upstream failures stay behind the safe envelope.
impl From<github::GhError> for ApiError {
    fn from(error: github::GhError) -> Self {
        match error {
            github::GhError::Permanent(message) => ApiError(StatusCode::UNPROCESSABLE_ENTITY, message),
            github::GhError::Transient(message) => ApiError(StatusCode::BAD_GATEWAY, message),
        }
    }
}

type ApiResult<T> = Result<T, ApiError>;

fn bad_request(message: impl Into<String>) -> ApiError {
    ApiError(StatusCode::BAD_REQUEST, message.into())
}

fn not_found() -> ApiError {
    ApiError(StatusCode::NOT_FOUND, "scan not found".into())
}

async fn require_token(State(api): State<Api>, request: Request, next: Next) -> Response {
    let expected = api.engine.config.api_token.as_bytes();
    let given = request
        .headers()
        .get("x-radar-token")
        .map(|v| v.as_bytes())
        .unwrap_or_default();
    // Length check leaks nothing useful; the byte comparison does not short-circuit.
    let ok = expected.is_empty()
        || (given.len() == expected.len()
            && given.iter().zip(expected).fold(0u8, |acc, (a, b)| acc | (a ^ b)) == 0);
    if ok {
        next.run(request).await
    } else {
        ApiError(StatusCode::UNAUTHORIZED, "missing or wrong X-Radar-Token".into()).into_response()
    }
}

async fn health() -> Json<serde_json::Value> {
    Json(serde_json::json!({ "ok": true, "version": env!("CARGO_PKG_VERSION") }))
}

// ---------- auth ----------

#[derive(Serialize)]
struct AuthStatus {
    /// signed_out | pending | signed_in | error
    state: &'static str,
    login: Option<String>,
    /// "device" when signed in through Device Flow, "config" for a configured token.
    source: Option<&'static str>,
    user_code: Option<String>,
    verification_uri: Option<String>,
    expires_in: Option<u64>,
    message: Option<String>,
    device_flow_available: bool,
}

async fn start_device(State(api): State<Api>) -> ApiResult<Json<AuthStatus>> {
    let client_id = &api.engine.config.github_client_id;
    if client_id.is_empty() {
        return Err(bad_request("github_client_id is not configured"));
    }
    let code = github::device_code(&api.engine.http, client_id).await?;
    let now = Instant::now();
    *api.device.lock().unwrap() = Some(DeviceSession {
        expires: now + Duration::from_secs(code.expires_in),
        interval: Duration::from_secs(code.interval.max(1)),
        next_poll: now + Duration::from_secs(code.interval.max(1)),
        code,
        error: None,
    });
    // Finish sign-in even when no client is polling (e.g. the code is entered on a PC
    // while the phone app sits in the background).
    let background = api.clone();
    tokio::spawn(async move {
        loop {
            let wait = {
                let guard = background.device.lock().unwrap();
                match guard.as_ref() {
                    Some(s) if s.error.is_none() => s.next_poll.saturating_duration_since(Instant::now()),
                    _ => break,
                }
            };
            tokio::time::sleep(wait).await;
            if background.engine.is_stopping() {
                break;
            }
            if let Err(error) = poll_once(&background).await {
                tracing::warn!(error = %error.1, "device flow poll failed");
            }
        }
    });
    auth_status(State(api)).await
}

/// One paced Device Flow exchange. The next slot is reserved *before* awaiting GitHub, so the
/// background task and status requests never poll concurrently (which would earn `slow_down`).
async fn poll_once(api: &Api) -> ApiResult<()> {
    let engine = &api.engine;
    let device_code = {
        let mut guard = api.device.lock().unwrap();
        let Some(session) = guard.as_mut() else { return Ok(()) };
        let now = Instant::now();
        if now >= session.expires {
            session.error = Some("code expired, start again".into());
            return Ok(());
        }
        if session.error.is_some() || now < session.next_poll {
            return Ok(());
        }
        session.next_poll = now + session.interval;
        session.code.device_code.clone()
    };
    let poll = github::poll_token(&engine.http, &engine.config.github_client_id, &device_code).await?;
    if let github::Poll::Token(token) = poll {
        let login = github::user_login(&engine.http, &token).await?;
        store::save_token(engine.db.ready().await?, &token, &login).await?;
        *api.device.lock().unwrap() = None;
    } else if let Some(session) = api.device.lock().unwrap().as_mut() {
        match poll {
            github::Poll::SlowDown => {
                session.interval += Duration::from_secs(5);
                session.next_poll = Instant::now() + session.interval;
            }
            github::Poll::Failed(message) => session.error = Some(message),
            _ => {}
        }
    }
    Ok(())
}

/// Reports sign-in state, opportunistically taking a due Device Flow poll slot.
async fn auth_status(State(api): State<Api>) -> ApiResult<Json<AuthStatus>> {
    let engine = &api.engine;
    let pool = engine.db.ready().await?;
    poll_once(&api).await?;

    let available = !engine.config.github_client_id.is_empty();
    if let Some((_, login)) = store::token(pool).await? {
        return Ok(Json(AuthStatus::signed_in(login, "device", available)));
    }
    let pending = api.device.lock().unwrap().as_ref().map(|session| AuthStatus {
        state: if session.error.is_some() { "error" } else { "pending" },
        login: None,
        source: None,
        user_code: Some(session.code.user_code.clone()),
        verification_uri: Some(session.code.verification_uri.clone()),
        expires_in: Some(session.expires.saturating_duration_since(Instant::now()).as_secs()),
        message: session.error.clone(),
        device_flow_available: available,
    });
    if let Some(status) = pending {
        return Ok(Json(status));
    }
    if !engine.config.github_token.is_empty() {
        let login = github::user_login(&engine.http, &engine.config.github_token).await?;
        return Ok(Json(AuthStatus::signed_in(login, "config", available)));
    }
    Ok(Json(AuthStatus {
        state: "signed_out",
        login: None,
        source: None,
        user_code: None,
        verification_uri: None,
        expires_in: None,
        message: None,
        device_flow_available: available,
    }))
}

impl AuthStatus {
    fn signed_in(login: String, source: &'static str, available: bool) -> Self {
        AuthStatus {
            state: "signed_in",
            login: Some(login),
            source: Some(source),
            user_code: None,
            verification_uri: None,
            expires_in: None,
            message: None,
            device_flow_available: available,
        }
    }
}

async fn logout(State(api): State<Api>) -> ApiResult<StatusCode> {
    store::clear_token(api.engine.db.ready().await?).await?;
    *api.device.lock().unwrap() = None;
    Ok(StatusCode::NO_CONTENT)
}

// ---------- repos ----------

#[derive(Deserialize)]
struct RepoQuery {
    #[serde(default)]
    q: String,
}

#[derive(Serialize)]
struct RepoView {
    #[serde(flatten)]
    repo: github::Repo,
    /// Over the hard cap: cannot be scanned.
    too_large: bool,
    /// Over the soft limit: allowed, but the download takes a while (and mobile data).
    large: bool,
}

async fn repos(State(api): State<Api>, Query(query): Query<RepoQuery>) -> ApiResult<Json<Vec<RepoView>>> {
    let engine = &api.engine;
    let token = github_token(engine).await;
    let query = query.q.trim();
    let repos = match (query.is_empty(), token.as_deref()) {
        (true, Some(token)) => github::my_repos(&engine.http, token).await?,
        (true, None) => return Err(ApiError(StatusCode::UNAUTHORIZED, "sign in to list your repos".into())),
        (false, token) => github::search_repos(&engine.http, token, query).await?,
    };
    let (cap_kb, warn_kb) = (engine.config.max_repo_mb * 1024, engine.config.warn_repo_mb * 1024);
    Ok(Json(
        repos
            .into_iter()
            .map(|repo| RepoView { too_large: repo.size_kb > cap_kb, large: repo.size_kb > warn_kb, repo })
            .collect(),
    ))
}

// ---------- scans ----------

#[derive(Serialize)]
struct ScanView {
    #[serde(flatten)]
    scan: Scan,
    title: String,
    progress: f64,
    web_url: Option<String>,
}

impl From<Scan> for ScanView {
    fn from(scan: Scan) -> Self {
        let web_url = match (&scan.owner, &scan.repo) {
            (Some(owner), Some(repo)) => Some(match &scan.sha {
                Some(sha) => format!("https://github.com/{owner}/{repo}/tree/{sha}"),
                None => format!("https://github.com/{owner}/{repo}"),
            }),
            _ => None,
        };
        ScanView {
            title: scan.title(),
            progress: scan.progress(),
            web_url,
            scan,
        }
    }
}

#[derive(Deserialize)]
struct NewScan {
    owner: Option<String>,
    repo: Option<String>,
    zip_path: Option<String>,
}

fn valid_name(name: &str) -> bool {
    !name.is_empty()
        && name.len() <= 100
        && name.bytes().all(|b| b.is_ascii_alphanumeric() || b"-_.".contains(&b))
        && name != "."
        && name != ".."
}

async fn create_scan(State(api): State<Api>, Json(body): Json<NewScan>) -> ApiResult<(StatusCode, Json<ScanView>)> {
    let engine = &api.engine;
    let pool = engine.db.ready().await?;
    let id = match (body.owner, body.repo, body.zip_path) {
        (Some(owner), Some(repo), None) => {
            if !valid_name(&owner) || !valid_name(&repo) {
                return Err(bad_request("invalid owner or repo"));
            }
            store::insert_github(pool, &owner, &repo).await?
        }
        (None, None, Some(path)) => {
            let path = std::path::Path::new(&path);
            if !path.is_absolute() || !path.is_file() {
                return Err(bad_request("zip_path must be an absolute path to a file"));
            }
            store::insert_zip(pool, &path.to_string_lossy()).await?
        }
        _ => return Err(bad_request("send {owner, repo} or {zip_path}")),
    };
    engine
        .scan_queue()
        .await?
        .push(ScanRepo { scan_id: id })
        .await
        .map_err(|e| ApiError(StatusCode::INTERNAL_SERVER_ERROR, e.to_string()))?;
    let scan = store::scan(pool, id).await?.ok_or_else(not_found)?;
    Ok((StatusCode::CREATED, Json(scan.into())))
}

#[derive(Deserialize)]
struct ListQuery {
    #[serde(default)]
    active: bool,
    limit: Option<i64>,
}

async fn list_scans(State(api): State<Api>, Query(query): Query<ListQuery>) -> ApiResult<Json<Vec<ScanView>>> {
    let pool = api.engine.db.ready().await?;
    let scans = store::scans(pool, query.active, query.limit.unwrap_or(50).clamp(1, 200)).await?;
    Ok(Json(scans.into_iter().map(ScanView::from).collect()))
}

async fn get_scan(State(api): State<Api>, Path(id): Path<i64>) -> ApiResult<Json<ScanView>> {
    let pool = api.engine.db.ready().await?;
    Ok(Json(store::scan(pool, id).await?.ok_or_else(not_found)?.into()))
}

#[derive(Deserialize)]
struct TodoQuery {
    #[serde(default)]
    filter: String,
}

#[derive(Serialize)]
struct TodoView {
    #[serde(flatten)]
    todo: TodoRow,
    web_url: Option<String>,
    editor_url: Option<String>,
}

fn links(scan: &Scan, todo: &TodoRow) -> (Option<String>, Option<String>) {
    match (&scan.owner, &scan.repo, &scan.sha) {
        (Some(owner), Some(repo), Some(sha)) => (
            Some(format!("https://github.com/{owner}/{repo}/blob/{sha}/{}#L{}", todo.path, todo.line)),
            Some(format!("https://github.dev/{owner}/{repo}/blob/{sha}/{}#L{}", todo.path, todo.line)),
        ),
        _ => (None, None),
    }
}

async fn todos(State(api): State<Api>, Path(id): Path<i64>, Query(query): Query<TodoQuery>) -> ApiResult<Json<Vec<TodoView>>> {
    let pool = api.engine.db.ready().await?;
    let scan = store::scan(pool, id).await?.ok_or_else(not_found)?;
    let rows = store::todos(pool, id, query.filter == "new").await?;
    Ok(Json(
        rows.into_iter()
            .map(|todo| {
                let (web_url, editor_url) = links(&scan, &todo);
                TodoView { todo, web_url, editor_url }
            })
            .collect(),
    ))
}

async fn keep(State(api): State<Api>, Path(id): Path<i64>) -> ApiResult<Json<ScanView>> {
    let pool = api.engine.db.ready().await?;
    if !store::set_keep(pool, id, true).await? {
        return Err(not_found());
    }
    Ok(Json(store::scan(pool, id).await?.ok_or_else(not_found)?.into()))
}

/// "Delete now": undo a keep and run the cleanup job immediately.
async fn cleanup_now(State(api): State<Api>, Path(id): Path<i64>) -> ApiResult<Json<ScanView>> {
    let engine = &api.engine;
    let pool = engine.db.ready().await?;
    if !store::set_keep(pool, id, false).await? {
        return Err(not_found());
    }
    engine
        .cleanup_queue()
        .await?
        .push(CleanupScan { scan_id: id })
        .await
        .map_err(|e| ApiError(StatusCode::INTERNAL_SERVER_ERROR, e.to_string()))?;
    Ok(Json(store::scan(pool, id).await?.ok_or_else(not_found)?.into()))
}

/// Markdown report for sharing: summary, new and resolved TODOs, then everything by file.
async fn report(State(api): State<Api>, Path(id): Path<i64>) -> ApiResult<Response> {
    let pool = api.engine.db.ready().await?;
    let scan = store::scan(pool, id).await?.ok_or_else(not_found)?;
    let todos = store::todos(pool, id, false).await?;
    let resolved = match scan.prev_scan_id {
        Some(prev) => store::resolved(pool, id, prev).await?,
        None => Vec::new(),
    };
    let item = |todo: &TodoRow| {
        let (web, _) = links(&scan, todo);
        let location = format!("{}:{}", todo.path, todo.line);
        let location = web.map_or(format!("`{location}`"), |url| format!("[`{location}`]({url})"));
        format!("- **{}** {} — {}\n", todo.tag, location, todo.text.replace('\n', " "))
    };

    let mut md = format!("# TODO Radar · {}\n\n", scan.title());
    if let Some(sha) = &scan.sha {
        let _ = writeln!(md, "Commit `{}`  ", &sha[..sha.len().min(7)]);
    }
    let _ = writeln!(
        md,
        "**{}** TODOs · **+{}** new · **−{}** resolved\n",
        scan.todo_count, scan.new_count, scan.resolved_count
    );
    let new: Vec<_> = todos.iter().filter(|t| t.is_new).collect();
    if !new.is_empty() {
        md.push_str("## New since last scan\n\n");
        new.iter().take(100).for_each(|t| md.push_str(&item(t)));
        md.push('\n');
    }
    if !resolved.is_empty() {
        md.push_str("## Resolved since last scan\n\n");
        resolved.iter().take(100).for_each(|t| md.push_str(&item(t)));
        md.push('\n');
    }
    md.push_str("## All TODOs\n");
    let mut current = "";
    for todo in todos.iter().take(500) {
        if todo.path != current {
            current = &todo.path;
            let _ = write!(md, "\n### `{current}`\n\n");
        }
        md.push_str(&item(todo));
    }
    if todos.len() > 500 {
        let _ = writeln!(md, "\n…and {} more.", todos.len() - 500);
    }
    Ok(([(header::CONTENT_TYPE, "text/markdown; charset=utf-8")], md).into_response())
}

#[derive(Deserialize)]
struct PeekQuery {
    path: String,
    line: u32,
    #[serde(default = "default_context")]
    context: u32,
}

fn default_context() -> u32 {
    6
}

#[derive(Serialize)]
struct Peek {
    path: String,
    /// Line number of `lines[0]`.
    start: u32,
    highlight: u32,
    lines: Vec<String>,
    /// "cache" (the scan's zip) or "github" (fetched at the scanned commit).
    source: &'static str,
}

/// A few lines of code around a TODO, for the clients' inline code viewer.
async fn peek(State(api): State<Api>, Path(id): Path<i64>, Query(q): Query<PeekQuery>) -> ApiResult<Json<Peek>> {
    let engine = &api.engine;
    if q.path.is_empty() || q.path.starts_with('/') || q.path.split('/').any(|seg| seg == "..") || q.line == 0 {
        return Err(bad_request("invalid path or line"));
    }
    let scan = store::scan(engine.db.ready().await?, id).await?.ok_or_else(not_found)?;
    let zip = match &scan.zip_path {
        Some(path) => std::path::PathBuf::from(path),
        None => engine.cache_dir().join(format!("{id}.zip")),
    };
    let cached = tokio::task::spawn_blocking({
        let (zip, path) = (zip.clone(), q.path.clone());
        move || scanner::read_entry(&zip, &path)
    })
    .await
    .ok()
    .flatten();
    let (bytes, source) = match cached {
        Some(bytes) => (bytes, "cache"),
        None => match (&scan.owner, &scan.repo, &scan.sha) {
            (Some(owner), Some(repo), Some(sha)) => {
                let token = github_token(engine).await;
                (github::file_at(&engine.http, token.as_deref(), owner, repo, sha, &q.path).await?, "github")
            }
            _ => return Err(ApiError(StatusCode::GONE, "the zip for this scan was deleted".into())),
        },
    };
    let text = String::from_utf8_lossy(&bytes);
    let context = q.context.min(30);
    let start = q.line.saturating_sub(context).max(1);
    let lines = text
        .lines()
        .skip(start as usize - 1)
        .take((q.line - start + context + 1) as usize)
        .map(|l| l.chars().take(240).collect())
        .collect();
    Ok(Json(Peek { path: q.path, start, highlight: q.line, lines, source }))
}
