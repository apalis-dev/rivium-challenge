use crate::job::{digest, open, push, queue_config, write_snapshot};
use apalis::prelude::*;
use apalis_sqlite::SqliteStorage;
use rivium::{Kind, Result, Service, service};
use rivium_host::{App, Context};
use serde::{Deserialize, Serialize};

#[derive(Default, Serialize, Deserialize)]
pub struct Config {}

pub struct LinkDigest;

impl App for LinkDigest {
    const NAME: &'static str = "link-digest";
    const VERSION: &'static str = env!("CARGO_PKG_VERSION");
    type Config = Config;

    fn services(_: &Config, context: &Context<Config>) -> Result<Vec<Box<dyn Service>>> {
        let root = context.paths().root().to_path_buf();
        let inbox_root = root.clone();
        let inbox = service("inbox", Kind::Frontline, move |ctx| async move {
            // Drop a file containing a URL into <root>/inbox and it becomes a job.
            // The Android app uses this to hand over shared links.
            let dir = inbox_root.join("inbox");
            std::fs::create_dir_all(&dir).expect("create inbox");
            let pool = open(&inbox_root).await.expect("open job database");
            ctx.ready();
            loop {
                if let Ok(entries) = std::fs::read_dir(&dir) {
                    for entry in entries.flatten() {
                        if entry.file_name().to_string_lossy().starts_with('.') {
                            continue;
                        } // still being written
                        let path = entry.path();
                        let Ok(text) = std::fs::read_to_string(&path) else {
                            continue;
                        };
                        let url = text.trim();
                        if url.starts_with("http") && push(&pool, url).await.is_err() {
                            continue; // keep the file, retry next tick
                        }
                        let _ = std::fs::remove_file(&path);
                    }
                }
                tokio::select! {
                    _ = tokio::time::sleep(std::time::Duration::from_millis(500)) => {}
                    _ = ctx.stopped() => break,
                }
            }
            pool.close().await;
            Ok(())
        });
        let snap_root = root.clone();
        let snapshot = service("snapshot", Kind::Frontline, move |ctx| async move {
            let pool = open(&snap_root).await.expect("open job database");
            ctx.ready();
            loop {
                if let Err(e) = write_snapshot(&pool, &snap_root).await {
                    eprintln!("snapshot error: {e}");
                }
                tokio::select! {
                    _ = tokio::time::sleep(std::time::Duration::from_secs(1)) => {}
                    _ = ctx.stopped() => break,
                }
            }
            pool.close().await;
            Ok(())
        });
        Ok(vec![
            inbox,
            snapshot,
            service("digest-worker", Kind::Frontline, move |ctx| async move {
                let pool = open(&root).await.expect("open job database");
                let backend = SqliteStorage::new(&pool)
                    .with_config(queue_config())
                    .poll_with_interval(std::time::Duration::from_secs(1));
                let worker = WorkerBuilder::new(format!(
                    "digest-{}",
                    std::time::SystemTime::now()
                        .duration_since(std::time::UNIX_EPOCH)
                        .map(|d| d.as_millis())
                        .unwrap_or(0)
                ))
                .backend(backend)
                .data(pool.clone())
                .build(digest);
                ctx.ready();
                tokio::select! {
                    r = worker.run() => { if let Err(e) = r { eprintln!("worker error: {e}"); } }
                    _ = ctx.stopped() => {}
                }
                pool.close().await;
                Ok(())
            }),
        ])
    }
}
