//! TODO Radar engine: a Rivium app hosting Apalis workers and a localhost API.
//!
//! Two services share one SQLite file: `worker` (Apalis scan + cleanup queues) and
//! `api` (an axum router served by `rivium_http::HttpServer`). The same app runs as a
//! process (`cargo run`) or embedded through JNI in the Android and desktop clients.

use std::{
    net::SocketAddr,
    path::PathBuf,
    sync::{
        Arc,
        atomic::{AtomicBool, Ordering},
    },
    time::Duration,
};

use apalis::prelude::*;
use apalis_sqlite::{SqliteConnectOptions, SqlitePool, SqlitePoolOptions, SqliteStorage};
use rivium::{Kind, ResultExt, Service, service};
use rivium_host::{App, Context};
use rivium_http::HttpServer;
use serde::{Deserialize, Serialize};
use sqlx::sqlite::{SqliteJournalMode, SqliteSynchronous};
use tokio::sync::OnceCell;

pub mod api;
pub mod github;
pub mod jobs;
pub mod scanner;
pub mod store;

pub use jobs::{CleanupScan, ScanRepo};

#[derive(Clone, Debug, Serialize, Deserialize)]
#[serde(default)]
pub struct Config {
    pub port: u16,
    /// Shared secret every API request must carry in `X-Radar-Token`; empty disables the check.
    pub api_token: String,
    /// OAuth App client id with Device Flow enabled.
    pub github_client_id: String,
    /// Optional pre-issued token (e.g. `gh auth token`) used when nobody signed in.
    pub github_token: String,
    /// Hard cap: larger repos are refused.
    pub max_repo_mb: u64,
    /// Soft limit: larger repos are flagged "large" in the UI but still scannable.
    pub warn_repo_mb: u64,
    pub max_todos: u32,
    pub keep_scans: u32,
    /// Downloads are deleted this long after a scan finishes unless the user keeps them.
    pub cleanup_delay_secs: u64,
}

impl Default for Config {
    fn default() -> Self {
        Self {
            port: 8737,
            api_token: String::new(),
            github_client_id: String::new(),
            github_token: String::new(),
            max_repo_mb: 1024,
            warn_repo_mb: 100,
            max_todos: 5000,
            keep_scans: 5,
            cleanup_delay_secs: 600,
        }
    }
}

/// Database handle shared by the workers and the API; opened and set up once per generation,
/// inside the runtime (Rivium builds services before Tokio is running).
#[derive(Clone)]
pub struct Db {
    options: SqliteConnectOptions,
    pool: Arc<OnceCell<SqlitePool>>,
}

impl Db {
    pub fn open(path: PathBuf) -> Self {
        let options = SqliteConnectOptions::new()
            .filename(path)
            .create_if_missing(true)
            .journal_mode(SqliteJournalMode::Wal)
            .synchronous(SqliteSynchronous::Normal)
            .busy_timeout(Duration::from_secs(5));
        Self {
            options,
            pool: Arc::new(OnceCell::new()),
        }
    }

    pub async fn ready(&self) -> Result<&SqlitePool, BoxDynError> {
        self.pool
            .get_or_try_init(|| async {
                let pool = SqlitePoolOptions::new()
                    .max_connections(4)
                    .connect_with(self.options.clone())
                    .await?;
                SqliteStorage::setup(&pool).await?;
                // apalis setup turns synchronous OFF; queued jobs must survive power loss.
                sqlx::query("PRAGMA synchronous = NORMAL").execute(&pool).await?;
                sqlx::raw_sql(include_str!("schema.sql")).execute(&pool).await?;
                Ok::<_, BoxDynError>(pool)
            })
            .await
    }
}

/// Everything a job handler or API route needs, rebuilt for every Rivium generation.
#[derive(Clone)]
pub struct Engine {
    pub db: Db,
    pub http: reqwest::Client,
    pub config: Arc<Config>,
    pub root: PathBuf,
    stopping: Arc<AtomicBool>,
}

impl Engine {
    pub fn new(config: Config, root: PathBuf) -> Result<Self, BoxDynError> {
        Ok(Self {
            db: Db::open(root.join("radar.db")),
            http: github::http_client()?,
            config: Arc::new(config),
            root,
            stopping: Arc::new(AtomicBool::new(false)),
        })
    }

    /// True once the host asked this generation to stop; long jobs checkpoint and yield.
    pub fn is_stopping(&self) -> bool {
        self.stopping.load(Ordering::Acquire)
    }

    pub fn request_stop(&self) {
        self.stopping.store(true, Ordering::Release);
    }

    pub async fn scan_queue(&self) -> Result<SqliteStorage<ScanRepo>, BoxDynError> {
        Ok(SqliteStorage::new(self.db.ready().await?).with_config(queue_config::<ScanRepo>()))
    }

    pub async fn cleanup_queue(&self) -> Result<SqliteStorage<CleanupScan>, BoxDynError> {
        Ok(SqliteStorage::new(self.db.ready().await?).with_config(queue_config::<CleanupScan>()))
    }

    pub fn cache_dir(&self) -> PathBuf {
        self.root.join("cache")
    }

    pub fn inbox_dir(&self) -> PathBuf {
        self.root.join("inbox")
    }
}

/// The sqlite backend fetches on its heartbeat, so a short beat keeps pickup latency low;
/// a worker that misses 5 beats (~10 s) has its tasks re-enqueued.
fn queue_config<T>() -> apalis_sqlite::Config {
    apalis_sqlite::Config::default()
        .queue(std::any::type_name::<T>())
        .heartbeat_interval(Duration::from_secs(2))
        .missed_heartbeats(5)
}

/// Transient scan failures (network, rate limits) retry with exponential backoff, 2 s → 60 s.
fn scan_retry() -> apalis::layers::retry::BackoffRetryPolicy<tower::retry::backoff::ExponentialBackoff> {
    use tower::retry::backoff::{ExponentialBackoffMaker, MakeBackoff};
    use tower::util::rng::HasherRng;
    let backoff = ExponentialBackoffMaker::new(Duration::from_secs(2), Duration::from_secs(60), 0.5, HasherRng::default())
        .expect("valid backoff bounds")
        .make_backoff();
    RetryPolicy::retries(jobs::MAX_ATTEMPTS as usize - 1).with_backoff(backoff)
}

pub struct RadarApp;

impl App for RadarApp {
    const NAME: &'static str = "todo-radar";
    const VERSION: &'static str = env!("CARGO_PKG_VERSION");
    type Config = Config;

    fn services(config: &Config, context: &Context<Config>) -> rivium::Result<Vec<Box<dyn Service>>> {
        let root = context.paths().root().to_path_buf();
        let engine = Engine::new(config.clone(), root).map_err(io).context("build engine")?;

        let worker_engine = engine.clone();
        let worker = service("worker", Kind::Background, move |ctx| async move {
            let engine = worker_engine;
            std::fs::create_dir_all(engine.cache_dir())?;
            std::fs::create_dir_all(engine.inbox_dir())?;
            let scans = engine.scan_queue().await.map_err(io).context("open scan queue")?;
            let cleanups = engine.cleanup_queue().await.map_err(io).context("open cleanup queue")?;

            // Unique names per generation: apalis re-enqueues a dead worker's tasks only when
            // they were locked under a *different* name than the live worker's.
            let generation = jobs::now_millis();
            let (scan_name, cleanup_name) = (format!("scan-{generation}"), format!("cleanup-{generation}"));
            let (scan_engine, cleanup_engine) = (engine.clone(), engine.clone());
            let monitor = Monitor::new()
                .register(move |_| {
                    WorkerBuilder::new(scan_name.clone())
                        .backend(scans.clone())
                        .concurrency(1)
                        .retry(scan_retry())
                        .data(scan_engine.clone())
                        .build(jobs::scan_repo)
                })
                .register(move |_| {
                    WorkerBuilder::new(cleanup_name.clone())
                        .backend(cleanups.clone())
                        .data(cleanup_engine.clone())
                        .build(jobs::cleanup_scan)
                })
                // A paused scan has already checkpointed; don't wait for it to finish.
                .shutdown_timeout(Duration::from_secs(1));
            ctx.ready();
            monitor
                .run_with_signal(async move {
                    ctx.stopped().await;
                    engine.request_stop();
                    Ok(())
                })
                .await
                .context("worker monitor")
        });

        let address = SocketAddr::from(([127, 0, 0, 1], config.port));
        Ok(vec![
            worker,
            Box::new(HttpServer::new("api", address, api::router(engine))),
        ])
    }
}

fn io(error: BoxDynError) -> std::io::Error {
    std::io::Error::other(error.to_string())
}
