//! TaskPing: one Apalis job type and one worker, running inside a Rivium-managed service.

use apalis::prelude::*;
use apalis_sqlite::{Config as SqliteConfig, SqlitePool, SqliteStorage};
use futures::StreamExt;
use rivium::{Kind, ResultExt, Service, service};
use rivium_host::{App, Context};
use serde::{Deserialize, Serialize};
use std::{path::Path, time::Duration};

mod jni_bridge;

/// The SQLite job database lives at `<root>/taskping.db`.
pub const DB_FILE: &str = "taskping.db";

/// How long one step of a `Table` job takes.
const STEP: Duration = Duration::from_secs(1);

/// The only job type.
#[derive(Debug, Clone, Serialize, Deserialize)]
pub enum DemoJob {
    /// Instant: a + b.
    Add { a: i64, b: i64 },
    /// Long-running: base×1, base×2, … base×upto, one step per second; returns the sum.
    /// Progress is checkpointed in SQLite so a restarted job resumes where it stopped.
    Table { base: i64, upto: i64 },
}

/// Worker handler. The return value is persisted by apalis-sqlite as the task result.
pub async fn process_demo_job(
    job: DemoJob,
    id: TaskId,
    pool: Data<SqlitePool>,
) -> Result<i64, BoxDynError> {
    match job {
        DemoJob::Add { a, b } => {
            println!("[worker] Add {a} + {b} = {}", a + b);
            Ok(a + b)
        }
        DemoJob::Table { base, upto } => {
            let id = id.to_string();
            let done: i64 = sqlx::query_scalar("SELECT step FROM taskping_progress WHERE task_id = ?")
                .bind(&id)
                .fetch_optional(&*pool)
                .await?
                .unwrap_or(0);
            if done > 0 {
                println!("[worker] Table {base}: resuming after step {done}/{upto}");
            }
            for step in done + 1..=upto {
                tokio::time::sleep(STEP).await;
                sqlx::query(
                    "INSERT INTO taskping_progress (task_id, step, total) VALUES (?, ?, ?)
                     ON CONFLICT(task_id) DO UPDATE SET step = excluded.step",
                )
                .bind(&id)
                .bind(step)
                .bind(upto)
                .execute(&*pool)
                .await?;
                println!("[worker] Table {base}: {base} x {step} = {}", base * step);
            }
            Ok(base * upto * (upto + 1) / 2)
        }
    }
}

/// Open (or create) the SQLite job database and run Apalis migrations.
pub async fn open_storage(root: &Path) -> Result<SqliteStorage<DemoJob>, BoxDynError> {
    let db = root.join(DB_FILE);
    let pool = SqlitePool::connect(&format!("sqlite://{}?mode=rwc", db.display())).await?;
    SqliteStorage::setup(&pool).await?;
    sqlx::query(
        "CREATE TABLE IF NOT EXISTS taskping_progress
         (task_id TEXT PRIMARY KEY, step INTEGER NOT NULL, total INTEGER NOT NULL)",
    )
    .execute(&pool)
    .await?;
    // The worker registers only after the first heartbeat tick (default 30s), so keep it short.
    let config = SqliteConfig::default().heartbeat_interval(Duration::from_secs(2));
    Ok(SqliteStorage::new(&pool).with_config(config))
}

/// Client side: queue one DemoJob and return its id immediately.
pub async fn submit(root: &Path, job: DemoJob) -> Result<TaskId, BoxDynError> {
    let mut storage = open_storage(root).await?;
    let id = TaskId::from_string(ulid::Ulid::new().to_string());
    storage
        .push_task(TaskBuilder::new(job).task_id(id.clone()).build())
        .await?;
    println!("[client] queued DemoJob {id}");
    Ok(id)
}

/// Client side: queue one DemoJob and wait until a worker stores its result.
pub async fn submit_and_wait(root: &Path, job: DemoJob) -> Result<i64, BoxDynError> {
    let id = submit(root, job).await?;
    let done: TaskResult<i64> = open_storage(root)
        .await?
        .wait_for_single(id)
        .next()
        .await
        .ok_or("result stream ended")??;
    Ok(done.result?)
}

/// Client side: one-line job state, readable from any process, e.g. after the app reopens.
/// `done:<result>`, `failed:<error>`, `running:<step>/<total>` or `pending`.
pub async fn check(root: &Path, id: &str) -> Result<String, BoxDynError> {
    let mut storage = open_storage(root).await?;
    let finished: Vec<TaskResult<i64>> = storage.check_status([TaskId::from_string(id)]).await?;
    if let Some(done) = finished.first() {
        return Ok(match &done.result {
            Ok(value) => format!("done:{value}"),
            Err(e) => format!("failed:{e}"),
        });
    }
    let progress: Option<(i64, i64)> =
        sqlx::query_as("SELECT step, total FROM taskping_progress WHERE task_id = ?")
            .bind(id)
            .fetch_optional(storage.pool())
            .await?;
    Ok(match progress {
        Some((step, total)) => format!("running:{step}/{total}"),
        None => "pending".to_owned(),
    })
}

#[derive(Default, Serialize, Deserialize)]
pub struct Config {}

/// The Rivium application: a single service that owns the Apalis worker.
pub struct TaskPingApp;

impl App for TaskPingApp {
    const NAME: &'static str = "taskping";
    const VERSION: &'static str = env!("CARGO_PKG_VERSION");
    type Config = Config;

    fn services(_: &Config, host: &Context<Config>) -> rivium::Result<Vec<Box<dyn Service>>> {
        let root = host.paths().root().to_path_buf();
        Ok(vec![service("apalis-worker", Kind::Frontline, |ctx| async move {
            let storage = open_storage(&root)
                .await
                .map_err(|e| rivium::Error::internal(e.to_string()))?;
            let pool = storage.pool().clone();
            // Unique name per run: apalis-sqlite rejects a name still registered by a just-stopped run.
            let worker = WorkerBuilder::new(format!("taskping-worker-{}", ulid::Ulid::new()))
                .backend(storage)
                .data(pool)
                .build(process_demo_job);
            ctx.ready();
            println!("[service] apalis worker running; db at {}", root.join(DB_FILE).display());
            // Rivium owns the lifetime: the worker stops when the service is asked to stop.
            worker
                .run_until(async move {
                    ctx.stopped().await;
                    Ok::<(), WorkerError>(())
                })
                .await
                .context("apalis worker failed")?;
            println!("[service] apalis worker stopped");
            Ok(())
        })])
    }
}
