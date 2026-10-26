use crate::db::CompletedWork;
use std::fs::File;
use std::io::{BufReader, BufWriter, Read, Write};
use std::path::Path;
use zip::write::SimpleFileOptions;
use zip::CompressionMethod;
use zip::ZipWriter;

pub fn create_zip_archive(
    works: &[CompletedWork],
    output_path: &Path,
) -> Result<u64, Box<dyn std::error::Error + Send + Sync>> {
    let file = File::create(output_path)?;
    let writer = BufWriter::new(file);
    let mut zip = ZipWriter::new(writer);
    let options = SimpleFileOptions::default()
        .compression_method(CompressionMethod::Deflated)
        .unix_permissions(0o644);

    let mut buffer = Vec::new();

    for work in works {
        let path = Path::new(&work.output_path);
        if path.exists() && path.is_file() {
            let file_name = path
                .file_name()
                .map(|s| s.to_string_lossy().into_owned())
                .unwrap_or_else(|| format!("{}.jpg", work.id));

            zip.start_file(file_name, options)?;
            let mut f = BufReader::new(File::open(path)?);
            buffer.clear();
            f.read_to_end(&mut buffer)?;
            zip.write_all(&buffer)?;
        }
    }

    let mut finished = zip.finish()?;
    finished.flush()?;
    Ok(output_path.metadata()?.len())
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn create_zip_archive_should_produce_valid_zip_when_works_contain_files() {
        let temp_dir = std::env::temp_dir().join(format!("snap_test_{}", uuid::Uuid::new_v4()));
        std::fs::create_dir_all(&temp_dir).unwrap();
        let sample_file = temp_dir.join("test.jpg");
        std::fs::write(&sample_file, b"sample image bytes").unwrap();

        let works = vec![CompletedWork {
            id: "job-1".to_string(),
            original_uri: "content://sample".to_string(),
            output_path: sample_file.to_string_lossy().into_owned(),
            original_size: 100,
            compressed_size: 18,
            blake3_hash: "abc123hash".to_string(),
            duration_ms: 12,
            processed_at: 1000,
        }];

        let zip_path = temp_dir.join("output.zip");
        let result = create_zip_archive(&works, &zip_path);
        assert!(result.is_ok());
        assert!(zip_path.exists());
        assert!(zip_path.metadata().unwrap().len() > 0);

        let _ = std::fs::remove_dir_all(&temp_dir);
    }
}
