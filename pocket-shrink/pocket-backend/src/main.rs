use pocket_backend::app::PocketApp;
use pocket_backend::job::ProcessImageJob;
use pocket_backend::storage::{get_completed_results, init_db};
use rivium_host::embedded::Host;
use rivium_host::Code;
use std::path::PathBuf;
use std::time::Duration;

#[tokio::main]
async fn main() -> Result<(), Box<dyn std::error::Error + Send + Sync>> {
    let args: Vec<String> = std::env::args().collect();

    let mut root_dir = PathBuf::from("./pocket-data");
    let mut input_file: Option<String> = None;
    let mut max_dim: u32 = 1280;

    let mut i = 1;
    while i < args.len() {
        match args[i].as_str() {
            "--root" => {
                if i + 1 < args.len() {
                    root_dir = PathBuf::from(&args[i + 1]);
                    i += 1;
                }
            }
            "--input" | "--enqueue" => {
                if i + 1 < args.len() {
                    input_file = Some(args[i + 1].clone());
                    i += 1;
                }
            }
            "--max-dim" => {
                if i + 1 < args.len() {
                    max_dim = args[i + 1].parse().unwrap_or(1280);
                    i += 1;
                }
            }
            "--help" | "-h" => {
                println!("PocketShrink CLI - Rivium + Apalis Pocket Backend");
                println!();
                println!("Usage:");
                println!("  cargo run -- [OPTIONS]");
                println!();
                println!("Options:");
                println!("  --root <PATH>        Set application root directory (default: ./pocket-data)");
                println!("  --input <FILE>       Enqueue an image to be processed by the Apalis worker");
                println!("  --max-dim <PIXELS>   Max width/height for resizing (default: 1280)");
                println!("  --help, -h           Print this help menu");
                return Ok(());
            }
            _ => {
                if input_file.is_none() && !args[i].starts_with('-') {
                    input_file = Some(args[i].clone());
                }
            }
        }
        i += 1;
    }

    tokio::fs::create_dir_all(&root_dir).await?;
    let db_path = root_dir.join("pocket_jobs.db");
    let output_dir = root_dir.join("processed");
    tokio::fs::create_dir_all(&output_dir).await?;

    println!("========================================================");
    println!("  PocketShrink Desktop Host (Rivium + Apalis Worker)");
    println!("========================================================");
    println!("Root directory: {}", root_dir.display());
    println!("Database path:  {}", db_path.display());

    // Initialize database pool
    let pool = init_db(&db_path).await?;

    // If an image was passed to enqueue, push it to SQLite queue
    if let Some(ref image_path) = input_file {
        let abs_input = std::fs::canonicalize(image_path)
            .unwrap_or_else(|_| PathBuf::from(image_path));
        if !abs_input.exists() {
            eprintln!("Error: Input file does not exist: {}", abs_input.display());
            std::process::exit(1);
        }

        let job_id = format!("job_{}", std::time::SystemTime::now().duration_since(std::time::UNIX_EPOCH)?.as_millis());
        let job = ProcessImageJob {
            id: job_id.clone(),
            input_path: abs_input.to_string_lossy().to_string(),
            output_dir: output_dir.to_string_lossy().to_string(),
            max_dimension: max_dim,
        };

        println!("[1/3] Enqueuing image job into SQLite...");
        pocket_backend::storage::enqueue_job(&pool, job).await?;
        println!("      Enqueued job ID: {}", job_id);
    }

    // Start Rivium embedded host
    println!("[2/3] Starting Rivium embedded supervisor host...");
    let host = Host::new::<PocketApp>();
    let start_args = vec!["--root".to_string(), root_dir.to_string_lossy().to_string()];
    let code = host.start(&start_args);
    if code != Code::Ok {
        eprintln!("Failed to start Rivium host: {:?}: {:?}", code, host.last_error());
        std::process::exit(1);
    }
    println!("      Rivium status: {:?}", host.status());

    if input_file.is_some() {
        println!("[3/3] Waiting for Apalis worker to process the queued job...");
        // Wait up to 10 seconds for completion
        let mut completed = false;
        for _ in 0..20 {
            tokio::time::sleep(Duration::from_millis(500)).await;
            let results = get_completed_results(&pool).await?;
            if !results.is_empty() {
                println!();
                println!("========================================================");
                println!("  ✅ Job Completed Successfully!");
                println!("========================================================");
                for r in results.iter().take(1) {
                    println!("Job ID:         {}", r.id);
                    println!("Original File:  {} ({} bytes)", r.input_path, r.original_size_bytes);
                    println!("Output File:    {} ({} bytes)", r.output_path, r.processed_size_bytes);
                    println!("Dimensions:     {}x{}", r.width, r.height);
                    println!("Data Saved:     {}%", r.saved_percent);
                    println!("SHA-256 Hash:   {}", r.sha256_hash);
                }
                completed = true;
                break;
            }
        }

        if !completed {
            println!("Timed out waiting for job completion.");
        }

        println!("Stopping Rivium host gracefully...");
        host.stop(Duration::from_secs(2));
        println!("Stopped. Status: {:?}", host.status());
    } else {
        println!("Worker running. Press Ctrl-C to stop.");
        tokio::signal::ctrl_c().await?;
        println!("Stopping Rivium host gracefully...");
        host.stop(Duration::from_secs(2));
        println!("Stopped. Status: {:?}", host.status());
    }

    Ok(())
}
