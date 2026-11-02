use pocket_backend::app::PocketApp;
use pocket_backend::job::ProcessImageJob;
use pocket_backend::storage::{enqueue_job, get_completed_results, init_db};
use rivium_host::embedded::Host;
use rivium_host::Code;
use std::path::PathBuf;
use std::time::Duration;

#[tokio::test]
async fn test_jobs_persist_and_survive_reopen() -> Result<(), Box<dyn std::error::Error + Send + Sync>> {
    let test_dir = PathBuf::from("./target/test-persistence");
    let _ = tokio::fs::remove_dir_all(&test_dir).await;
    tokio::fs::create_dir_all(&test_dir).await?;

    let db_path = test_dir.join("pocket_jobs.db");
    let processed_dir = test_dir.join("processed");
    tokio::fs::create_dir_all(&processed_dir).await?;

    // Step 1: Create a test dummy image
    let sample_image_path = test_dir.join("sample.png");
    let img = image::RgbImage::new(400, 300);
    img.save(&sample_image_path)?;

    // Step 2: Open DB and ENQUEUE while host/worker is OFF (simulating app killed)
    let pool = init_db(&db_path).await?;
    let job1 = ProcessImageJob {
        id: "persist_job_1".into(),
        input_path: sample_image_path.to_string_lossy().to_string(),
        output_dir: processed_dir.to_string_lossy().to_string(),
        max_dimension: 800,
    };
    enqueue_job(&pool, job1).await?;

    // Verify 0 completed results yet
    let results_before = get_completed_results(&pool).await?;
    assert_eq!(results_before.len(), 0, "No results should be completed yet");

    // Step 3: Now start Rivium host (simulating app reopened)
    let host = Host::new::<PocketApp>();
    let start_args = vec!["--root".to_string(), test_dir.to_string_lossy().to_string()];
    let code = host.start(&start_args);
    assert_eq!(code, Code::Ok, "Rivium host must start cleanly");

    // Step 4: Wait for Apalis worker to pick up the persistent job from SQLite
    let mut completed = false;
    for _ in 0..20 {
        tokio::time::sleep(Duration::from_millis(300)).await;
        let results = get_completed_results(&pool).await?;
        if results.iter().any(|r| r.id == "persist_job_1") {
            completed = true;
            break;
        }
    }

    assert!(completed, "Persistent job from SQLite must be processed upon app restart!");

    // Step 5: Stop Rivium host gracefully
    host.stop(Duration::from_secs(2));

    Ok(())
}
