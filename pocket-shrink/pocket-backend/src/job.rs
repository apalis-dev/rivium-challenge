use serde::{Deserialize, Serialize};

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq, Eq)]
pub struct ProcessImageJob {
    pub id: String,
    pub input_path: String,
    pub output_dir: String,
    pub max_dimension: u32,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct JobResult {
    pub id: String,
    pub input_path: String,
    pub output_path: String,
    pub original_size_bytes: u64,
    pub processed_size_bytes: u64,
    pub sha256_hash: String,
    pub width: u32,
    pub height: u32,
    pub saved_percent: u32,
    pub completed_at: i64,
}
