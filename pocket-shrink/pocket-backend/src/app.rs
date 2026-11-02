use crate::storage::init_db;
use crate::worker::run_worker;
use rivium::{Kind, Result, Service, service};
use rivium_host::{App, Context};
use serde::{Deserialize, Serialize};

#[derive(Default, Serialize, Deserialize)]
pub struct PocketConfig {
    #[serde(default)]
    pub db_name: Option<String>,
}

pub struct PocketApp;

impl App for PocketApp {
    const NAME: &'static str = "pocket-shrink";
    const VERSION: &'static str = env!("CARGO_PKG_VERSION");
    type Config = PocketConfig;

    fn services(_config: &PocketConfig, ctx: &Context<PocketConfig>) -> Result<Vec<Box<dyn Service>>> {
        let root = ctx.paths().root().to_path_buf();
        let db_path = root.join("pocket_jobs.db");

        Ok(vec![service("pocket-worker", Kind::Frontline, move |svc_ctx| {
            let db_path = db_path.clone();
            async move {
                tracing::info!("Starting PocketShrink service with DB at {:?}", db_path);
                let pool = match init_db(&db_path).await {
                    Ok(p) => p,
                    Err(e) => {
                        tracing::error!("Failed to initialize DB: {:?}", e);
                        return Err(rivium::Error::internal(format!("DB init error: {e}")));
                    }
                };

                let (tx, rx) = tokio::sync::watch::channel(false);
                let worker_handle = tokio::spawn(async move {
                    if let Err(e) = run_worker(pool, rx).await {
                        tracing::error!("Worker error: {:?}", e);
                    }
                });

                svc_ctx.ready();

                // Wait for Rivium shutdown signal
                svc_ctx.stopped().await;
                let _ = tx.send(true);
                let _ = worker_handle.await;

                tracing::info!("PocketShrink service stopped cleanly.");
                Ok(())
            }
        })])
    }
}
