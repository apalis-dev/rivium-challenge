use crate::job::{JobResult, ProcessImageJob};
use sha2::{Digest, Sha256};
use std::fs;
use std::path::Path;
use std::time::{SystemTime, UNIX_EPOCH};

pub fn process_image(job: &ProcessImageJob) -> Result<JobResult, Box<dyn std::error::Error + Send + Sync>> {
    let input_path = Path::new(&job.input_path);
    if !input_path.exists() {
        return Err(format!("Input file does not exist: {}", job.input_path).into());
    }

    let original_metadata = fs::metadata(input_path)?;
    let original_size = original_metadata.len();

    // Ensure output directory exists
    let output_dir = Path::new(&job.output_dir);
    fs::create_dir_all(output_dir)?;

    // Open image
    let img = image::open(input_path)?;
    let (orig_width, orig_height) = (img.width(), img.height());

    // Scale down if dimensions exceed max_dimension
    let max_dim = job.max_dimension.max(320);
    let resized_img = if orig_width > max_dim || orig_height > max_dim {
        img.resize(max_dim, max_dim, image::imageops::FilterType::Lanczos3)
    } else {
        img
    };

    let (final_width, final_height) = (resized_img.width(), resized_img.height());

    // Output path: clean, stripped of metadata
    let output_file_name = format!("{}_scrubbed.jpg", job.id);
    let output_path = output_dir.join(output_file_name);

    // Save as JPEG (which strips EXIF and compresses)
    resized_img.save(&output_path)?;

    // Read processed file and compute cryptographic SHA-256 hash
    let processed_bytes = fs::read(&output_path)?;
    let processed_size = processed_bytes.len() as u64;

    let mut hasher = Sha256::new();
    hasher.update(&processed_bytes);
    let sha256_hash = hex::encode(hasher.finalize());

    let saved_percent = if original_size > processed_size {
        (((original_size - processed_size) as f64 / original_size as f64) * 100.0).round() as u32
    } else {
        0
    };

    let now_ts = SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .unwrap_or_default()
        .as_secs() as i64;

    Ok(JobResult {
        id: job.id.clone(),
        input_path: job.input_path.clone(),
        output_path: output_path.to_string_lossy().to_string(),
        original_size_bytes: original_size,
        processed_size_bytes: processed_size,
        sha256_hash,
        width: final_width,
        height: final_height,
        saved_percent,
        completed_at: now_ts,
    })
}
