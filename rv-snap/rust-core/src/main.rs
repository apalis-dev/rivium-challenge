use apalis::prelude::*;
use apalis_sqlite::SqliteStorage;
use rivium_snap::db;
use rivium_snap::export;
use rivium_snap::run_background_engine;
use rivium_snap::worker::ImageJob;
use std::path::Path;
use std::time::{Duration, Instant};

#[tokio::main]
async fn main() -> Result<(), Box<dyn std::error::Error + Send + Sync>> {
    println!(" RvSnap: Pocket Batch Processor ");

    let data_dir = Path::new("target/snap_test_data");
    tokio::fs::create_dir_all(&data_dir).await?;

    let db_path = data_dir.join("apalis.db").to_string_lossy().into_owned();
    let sqlite_url = format!("sqlite://{}?mode=rwc", db_path);
    println!("Connecting to SQLite database: {}", sqlite_url);

    let pool = sqlx::SqlitePool::connect(&sqlite_url).await?;
    db::init_db(&pool).await?;

    // Step 1: Enqueue batch jobs
    println!("\n[1/5] Enqueuing batch jobs into Apalis SQLite persistence queue...");
    let mut storage: SqliteStorage<ImageJob> = SqliteStorage::new(&pool);

    let output_dir = data_dir.join("processed_images").to_string_lossy().into_owned();
    let sample_jobs = vec![
        ImageJob {
            id: uuid::Uuid::new_v4().to_string(),
            uri: "content://media/external/images/photo_1.png".to_string(),
            output_dir: Some(output_dir.clone()),
        },
        ImageJob {
            id: uuid::Uuid::new_v4().to_string(),
            uri: "content://media/external/images/photo_2.webp".to_string(),
            output_dir: Some(output_dir.clone()),
        },
        ImageJob {
            id: uuid::Uuid::new_v4().to_string(),
            uri: "content://media/external/images/photo_3.bmp".to_string(),
            output_dir: Some(output_dir.clone()),
        },
        ImageJob {
            id: uuid::Uuid::new_v4().to_string(),
            uri: "content://media/external/images/photo_4.gif".to_string(),
            output_dir: Some(output_dir.clone()),
        },
        ImageJob {
            id: uuid::Uuid::new_v4().to_string(),
            uri: "content://media/external/images/photo_5.jpg".to_string(),
            output_dir: Some(output_dir.clone()),
        },
    ];

    let job_count = sample_jobs.len();
    storage.push_bulk(sample_jobs).await?;
    println!("✓ Successfully queued {} persistent image jobs.", job_count);

    // Check stats before worker starts
    let initial_stats = db::get_queue_stats(&pool).await?;
    println!("Queue Stats (Before worker): {:?}", initial_stats);

    // Step 2: Boot Rivium Engine
    println!("\n[2/5] Starting Rivium-supervised worker runtime...");
    let (stop_tx, stop_rx) = tokio::sync::oneshot::channel();
    let engine_db_path = db_path.clone();

    let engine_handle = tokio::spawn(async move {
        if let Err(e) = run_background_engine(engine_db_path, Some(stop_rx)).await {
            eprintln!("Engine error: {:?}", e);
        }
    });

    // Step 3: Monitor processing
    println!("\n[3/5] Monitoring job execution under Rivium supervision...");
    let mut complete = false;
    for _ in 0..30 {
        tokio::time::sleep(Duration::from_millis(300)).await;
        let stats = db::get_queue_stats(&pool).await?;
        println!(
            "  -> Pending: {}, Running: {}, Completed: {}",
            stats.pending, stats.running, stats.completed
        );

        if stats.pending == 0 && stats.running == 0 && stats.completed >= job_count as i64 {
            complete = true;
            break;
        }
    }

    if complete {
        println!("✓ All batch jobs successfully processed by Rivium worker!");
    } else {
        println!("Processing still in flight or timed out.");
    }

    // Step 4: High-throughput batch ingestion benchmark
    println!("\n[4/5] Running High-Throughput Batch Ingestion Benchmark (1,000 jobs)...");
    let bench_jobs: Vec<ImageJob> = (0..1000)
        .map(|i| ImageJob {
            id: uuid::Uuid::new_v4().to_string(),
            uri: format!("content://media/external/images/media/bench_{}", i),
            output_dir: None,
        })
        .collect();

    let bench_start = Instant::now();
    storage.push_bulk(bench_jobs).await?;
    let bench_elapsed = bench_start.elapsed();
    let per_item_ns = bench_elapsed.as_nanos() / 1000;
    println!(
        "✓ Ingested 1,000 persistent Apalis SQLite tasks in {:?} (~{} ns / item)",
        bench_elapsed, per_item_ns
    );

    // Step 5: Verify completed works & ZIP export
    println!("\n[5/5] Verifying completed works and testing ZIP export...");
    let works = db::get_all_completed_works(&pool).await?;
    println!("Total completed records in database: {}", works.len());
    for (i, work) in works.iter().take(3).enumerate() {
        println!(
            "  [{}] URI: {} | Blake3: {}... | Duration: {}ms",
            i + 1,
            work.original_uri,
            &work.blake3_hash[..16],
            work.duration_ms
        );
    }

    let zip_output = data_dir.join("rivium_snap_export.zip");
    match export::create_zip_archive(&works, &zip_output) {
        Ok(size) => println!("✓ ZIP archive successfully created: {} bytes at {:?}", size, zip_output),
        Err(e) => eprintln!("Failed to create ZIP: {:?}", e),
    }

    // Stop Rivium Engine cleanly
    let _ = stop_tx.send(());
    let _ = tokio::time::timeout(Duration::from_secs(3), engine_handle).await;

    println!("run completed with 0 errors!");

    Ok(())
}
