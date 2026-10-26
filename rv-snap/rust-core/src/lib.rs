pub mod db;
pub mod export;
pub mod worker;

use apalis::prelude::*;
use apalis_sqlite::{Config, SqliteStorage};
use jni::objects::{JClass, JString};
use jni::sys::{jboolean, jstring};
use jni::JNIEnv;
use serde::{Deserialize, Serialize};
use std::path::Path;
use std::sync::OnceLock;
use std::time::Duration;
use tokio::runtime::Runtime;

pub use worker::ImageJob;

static RUNTIME: OnceLock<Runtime> = OnceLock::new();
static POOLS: OnceLock<std::sync::RwLock<std::collections::HashMap<String, sqlx::SqlitePool>>> = OnceLock::new();

pub fn get_runtime() -> &'static Runtime {
    RUNTIME.get_or_init(|| {
        tokio::runtime::Builder::new_multi_thread()
            .worker_threads(std::thread::available_parallelism().map(|n| n.get()).unwrap_or(4).max(4))
            .enable_all()
            .build()
            .expect("Failed to build Tokio runtime")
    })
}

pub async fn get_pool(db_path: &str) -> Result<sqlx::SqlitePool, sqlx::Error> {
    let pools = POOLS.get_or_init(|| std::sync::RwLock::new(std::collections::HashMap::new()));
    {
        let reader = pools
            .read()
            .map_err(|e| sqlx::Error::Configuration(format!("Pool lock poisoned: {e}").into()))?;
        if let Some(pool) = reader.get(db_path) {
            return Ok(pool.clone());
        }
    }
    let sqlite_url = format!("sqlite://{}?mode=rwc", db_path);
    let pool = sqlx::SqlitePool::connect(&sqlite_url).await?;
    {
        let mut writer = pools
            .write()
            .map_err(|e| sqlx::Error::Configuration(format!("Pool lock poisoned: {e}").into()))?;
        writer.insert(db_path.to_string(), pool.clone());
    }
    Ok(pool)
}

pub struct SnapApp;

#[derive(Default, Serialize, Deserialize)]
pub struct SnapConfig;

impl rivium_host::App for SnapApp {
    const NAME: &'static str = "rivium-snap";
    const VERSION: &'static str = "0.1.0";
    type Config = SnapConfig;

    fn services(
        _config: &Self::Config,
        _context: &rivium_host::Context<Self::Config>,
    ) -> rivium::Result<Vec<Box<dyn rivium::Service>>> {
        Ok(Vec::new())
    }
}

pub async fn run_background_engine(
    db_path: String,
    mut stop_rx: Option<tokio::sync::oneshot::Receiver<()>>,
) -> Result<(), Box<dyn std::error::Error + Send + Sync>> {
    let pool = get_pool(&db_path).await?;

    db::init_db(&pool).await?;

    let config = Config::default()
        .batch_size(500)
        .queue(std::any::type_name::<worker::ImageJob>());

    let backend = SqliteStorage::new(&pool)
        .with_config(config)
        .poll_with_interval(Duration::from_millis(50));

    let worker = WorkerBuilder::new("rivium-batch-worker")
        .backend(backend)
        .data(pool.clone())
        .build(worker::handle_image_job);

    let worker_service = rivium::service("batch-worker", rivium::Kind::Background, move |ctx| async move {
        ctx.ready();
        tokio::select! {
            _ = ctx.stopped() => {
                Ok(())
            }
            res = worker.run() => {
                match res {
                    Ok(_) => Ok(()),
                    Err(e) => Err(rivium::Error::internal(e.to_string())),
                }
            }
        }
    });

    let supervisor = rivium::rt::Supervisor::new(
        Duration::from_secs(5),
        Duration::from_secs(5),
    )
    .with(worker_service);

    let control = supervisor.control();

    if let Some(stop_receiver) = stop_rx.take() {
        tokio::spawn(async move {
            let _ = stop_receiver.await;
            control.stop(rivium::StopReason::Deadline(Duration::from_secs(2)));
        });
    }

    let _ = supervisor.run().await;

    Ok(())
}

#[no_mangle]
pub extern "system" fn Java_com_rivium_snap_RustBackend_startEngine(
    mut env: JNIEnv,
    _class: JClass,
    db_path: JString,
) {
    let path: String = match env.get_string(&db_path) {
        Ok(s) => s.into(),
        Err(_) => return,
    };

    let rt = get_runtime();
    rt.spawn(async move {
        let _ = run_background_engine(path, None).await;
    });
}

#[no_mangle]
pub extern "system" fn Java_com_rivium_snap_RustBackend_enqueueBulkJobs(
    mut env: JNIEnv,
    _class: JClass,
    db_path: JString,
    json_uris: JString,
) {
    let path: String = match env.get_string(&db_path) {
        Ok(s) => s.into(),
        Err(_) => return,
    };
    let json_str: String = match env.get_string(&json_uris) {
        Ok(s) => s.into(),
        Err(_) => return,
    };

    let rt = get_runtime();
    rt.spawn(async move {
        if let Ok(uris) = serde_json::from_str::<Vec<String>>(&json_str) {
            if let Ok(pool) = get_pool(&path).await {
                let mut storage: SqliteStorage<worker::ImageJob> = SqliteStorage::new(&pool);

                let output_dir = Path::new(&path)
                    .parent()
                    .map(|p| p.join("processed_images").to_string_lossy().into_owned());

                let jobs: Vec<worker::ImageJob> = uris
                    .into_iter()
                    .map(|uri| worker::ImageJob {
                        id: uuid::Uuid::new_v4().to_string(),
                        uri,
                        output_dir: output_dir.clone(),
                    })
                    .collect();

                const CHUNK_SIZE: usize = 5000;
                for chunk in jobs.chunks(CHUNK_SIZE) {
                    let _ = storage.push_bulk(chunk.to_vec()).await;
                }
            }
        }
    });
}

#[no_mangle]
pub extern "system" fn Java_com_rivium_snap_RustBackend_getQueueStats<'local>(
    mut env: JNIEnv<'local>,
    _class: JClass<'local>,
    db_path: JString<'local>,
) -> jstring {
    let path: String = match env.get_string(&db_path) {
        Ok(s) => s.into(),
        Err(_) => return std::ptr::null_mut(),
    };

    let rt = get_runtime();
    let stats = rt.block_on(async {
        if let Ok(pool) = get_pool(&path).await {
            db::get_queue_stats(&pool).await.unwrap_or_default()
        } else {
            db::QueueStats::default()
        }
    });

    let json = serde_json::to_string(&stats).unwrap_or_else(|_| "{}".to_string());
    match env.new_string(json) {
        Ok(js) => js.into_raw(),
        Err(_) => std::ptr::null_mut(),
    }
}

#[no_mangle]
pub extern "system" fn Java_com_rivium_snap_RustBackend_getCompletedWorks<'local>(
    mut env: JNIEnv<'local>,
    _class: JClass<'local>,
    db_path: JString<'local>,
) -> jstring {
    let path: String = match env.get_string(&db_path) {
        Ok(s) => s.into(),
        Err(_) => return std::ptr::null_mut(),
    };

    let rt = get_runtime();
    let works = rt.block_on(async {
        if let Ok(pool) = get_pool(&path).await {
            db::get_all_completed_works(&pool).await.unwrap_or_default()
        } else {
            Vec::new()
        }
    });

    let json = serde_json::to_string(&works).unwrap_or_else(|_| "[]".to_string());
    match env.new_string(json) {
        Ok(js) => js.into_raw(),
        Err(_) => std::ptr::null_mut(),
    }
}

#[no_mangle]
pub extern "system" fn Java_com_rivium_snap_RustBackend_exportZip(
    mut env: JNIEnv,
    _class: JClass,
    db_path: JString,
    output_path: JString,
) -> jboolean {
    let db_p: String = match env.get_string(&db_path) {
        Ok(s) => s.into(),
        Err(_) => return 0,
    };
    let out_p: String = match env.get_string(&output_path) {
        Ok(s) => s.into(),
        Err(_) => return 0,
    };

    let rt = get_runtime();
    let success = rt.block_on(async {
        if let Ok(pool) = get_pool(&db_p).await {
            if let Ok(works) = db::get_all_completed_works(&pool).await {
                export::create_zip_archive(&works, Path::new(&out_p)).is_ok()
            } else {
                false
            }
        } else {
            false
        }
    });

    if success { 1 } else { 0 }
}

#[no_mangle]
pub extern "system" fn Java_com_rivium_snap_RustBackend_exportZipSelected(
    mut env: JNIEnv,
    _class: JClass,
    db_path: JString,
    output_path: JString,
    json_ids: JString,
) -> jboolean {
    let db_p: String = match env.get_string(&db_path) {
        Ok(s) => s.into(),
        Err(_) => return 0,
    };
    let out_p: String = match env.get_string(&output_path) {
        Ok(s) => s.into(),
        Err(_) => return 0,
    };
    let ids_json: String = match env.get_string(&json_ids) {
        Ok(s) => s.into(),
        Err(_) => return 0,
    };

    let ids: Vec<String> = serde_json::from_str(&ids_json).unwrap_or_default();

    let rt = get_runtime();
    let success = rt.block_on(async {
        if let Ok(pool) = get_pool(&db_p).await {
            if let Ok(works) = db::get_completed_works_by_ids(&pool, &ids).await {
                export::create_zip_archive(&works, Path::new(&out_p)).is_ok()
            } else {
                false
            }
        } else {
            false
        }
    });

    if success { 1 } else { 0 }
}
