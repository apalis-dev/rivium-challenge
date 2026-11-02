use crate::job::ProcessImageJob;
use crate::processor::process_image;
use crate::storage::{save_result, DbPool};
use apalis::prelude::*;

async fn handle_image_job(job: ProcessImageJob, pool: Data<DbPool>) {
    tracing::info!("Received job {} for input: {}", job.id, job.input_path);
    match process_image(&job) {
        Ok(result) => {
            tracing::info!(
                "Job {} successfully processed! Original: {}B -> {}B ({}% saved), SHA-256: {}",
                result.id,
                result.original_size_bytes,
                result.processed_size_bytes,
                result.saved_percent,
                result.sha256_hash
            );
            if let Err(e) = save_result(&pool, &result).await {
                tracing::error!("Failed to save result for job {}: {}", result.id, e);
            }
        }
        Err(e) => {
            tracing::error!("Failed to process image job {}: {}", job.id, e);
        }
    }
}

pub async fn run_worker(
    pool: DbPool,
    mut stopped_rx: tokio::sync::watch::Receiver<bool>,
) -> Result<(), WorkerError> {
    let storage = apalis_sqlite::SqliteStorage::new(&pool);

    let worker = WorkerBuilder::new("pocket-image-worker")
        .backend(storage)
        .data(pool)
        .build(handle_image_job);

    tokio::select! {
        res = worker.run() => {
            if let Err(e) = res {
                tracing::error!("Apalis worker ended with error: {:?}", e);
            }
        }
        _ = stopped_rx.changed() => {
            tracing::info!("Apalis worker received shutdown signal, stopping.");
        }
    }

    Ok(())
}
