pub mod db;
pub mod extract;
pub mod job;
pub mod jni_bridge;

use apalis::layers::retry::backoff::{ExponentialBackoffMaker, MakeBackoff};
use apalis::layers::retry::{HasherRng, RetryPolicy};
use apalis::prelude::*;
use apalis_core::error::AbortError;
use apalis_sqlite::{SqlitePool, SqliteStorage};
use futures::StreamExt;
use job::{save_link, JobContext, SaveLink};
use rivium::{service, Kind, Result, Service};
use rivium_host::{App, Context};
use serde::{Deserialize, Serialize};
use std::sync::RwLock;
use std::time::Duration;

#[derive(Serialize, Deserialize)]
pub struct Config {
    #[serde(default = "default_db_filename")]
    pub db_filename: String,
    /// Links to save at startup, e.g. `--set 'save=["https://example.com"]'`.
    #[serde(default)]
    pub save: Vec<String>,
}

impl Default for Config {
    fn default() -> Self {
        Self { db_filename: default_db_filename(), save: Vec::new() }
    }
}

const WORKER_NAME: &str = "reader-worker";

fn default_db_filename() -> String {
    "pocket_reader.db".to_string()
}

/// What the JNI bridge needs to talk to the running host. Replaced on every
/// start so an Android stop/start cycle never leaves callers on a stale pool.
#[derive(Clone)]
pub struct Shared {
    pub pool: SqlitePool,
    pub runtime: tokio::runtime::Handle,
}

static SHARED: RwLock<Option<Shared>> = RwLock::new(None);

pub fn shared() -> Option<Shared> {
    SHARED.read().unwrap_or_else(|e| e.into_inner()).clone()
}

fn set_shared(value: Option<Shared>) {
    *SHARED.write().unwrap_or_else(|e| e.into_inner()) = value;
}

fn internal(context: &str, e: impl std::fmt::Display) -> rivium::Error {
    rivium::Error::new(rivium::error::kinds::INTERNAL, format!("{context}: {e}"))
}

// Apalis' built-in abort check downcasts the Box itself, so a boxed AbortError
// would still be retried; look inside the box instead.
fn should_retry(e: &BoxDynError) -> bool {
    e.downcast_ref::<AbortError>().is_none()
}

pub struct PocketApp;

impl App for PocketApp {
    const NAME: &'static str = "pocket-backend";
    const VERSION: &'static str = env!("CARGO_PKG_VERSION");
    type Config = Config;

    fn services(config: &Config, ctx: &Context<Config>) -> Result<Vec<Box<dyn Service>>> {
        let db_path = ctx.paths().root().join(&config.db_filename);

        let worker = service("reader-worker", Kind::Frontline, move |service_ctx| async move {
            tracing::info!("Opening reading list at {}", db_path.display());
            let (pool, insert_hook) = db::connect(&db_path).await.map_err(|e| internal("database", e))?;
            db::expire_stale_worker(&pool, WORKER_NAME).await.map_err(|e| internal("database", e))?;
            let http = job::http_client().map_err(|e| internal("http client", e))?;

            // The insert hook wakes the worker instantly. The tick is a safety net for
            // jobs Apalis re-queues itself (e.g. orphans after the app was killed) and
            // for the brief window where a hook fires before its insert commits.
            let tick = futures::stream::unfold(tokio::time::interval(Duration::from_secs(5)), |mut iv| async move {
                iv.tick().await;
                Some(((), iv))
            });
            let wake = futures::stream::select(insert_hook, tick).boxed();
            let backend = SqliteStorage::<SaveLink>::new(&pool).poll_with_stream(wake);

            let backoff = ExponentialBackoffMaker::new(
                Duration::from_secs(5),
                Duration::from_secs(120),
                0.3,
                HasherRng::default(),
            )
            .map_err(|e| internal("backoff", e))?
            .make_backoff();
            let retry = RetryPolicy::retries(job::MAX_ATTEMPTS as usize * 2)
                .with_backoff(backoff)
                .retry_if(should_retry);

            let worker = WorkerBuilder::new(WORKER_NAME)
                .backend(backend)
                .data(JobContext { pool: pool.clone(), http })
                .concurrency(3)
                .retry(retry)
                .build(save_link);

            set_shared(Some(Shared { pool: pool.clone(), runtime: tokio::runtime::Handle::current() }));
            service_ctx.ready();
            tracing::info!("Reader worker ready");

            tokio::select! {
                _ = service_ctx.stopped() => tracing::info!("Stop requested; shutting down reader worker"),
                res = worker.run() => {
                    if let Err(e) = res {
                        tracing::error!("Reader worker exited with error: {e:?}");
                    }
                }
            }
            set_shared(None);
            pool.close().await;
            Ok(())
        });

        let mut services = vec![worker];

        if !config.save.is_empty() {
            let links = config.save.clone();
            services.push(service("inbox", Kind::Frontline, move |service_ctx| async move {
                service_ctx.ready();
                let shared = loop {
                    if let Some(s) = shared() {
                        break s;
                    }
                    tokio::select! {
                        _ = service_ctx.stopped() => return Ok(()),
                        _ = tokio::time::sleep(Duration::from_millis(50)) => {}
                    }
                };
                for raw in &links {
                    match extract::find_link(raw) {
                        Some(url) => match db::save_link(&shared.pool, &url).await {
                            Ok(o) if o.duplicate => tracing::info!("Already in your list ({}): {url}", o.status),
                            Ok(_) => tracing::info!("Queued {url}"),
                            Err(e) => tracing::error!("Could not queue {url}: {e}"),
                        },
                        None => tracing::warn!("Not a link, skipped: {raw}"),
                    }
                }
                service_ctx.stopped().await;
                Ok(())
            }));
        }

        Ok(services)
    }
}

rivium_jni::export!(class = "dev/apalis/riviumchallenge/RiviumBridge", app = PocketApp);
