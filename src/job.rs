//! The one job this app knows how to do: describe a piece of text.

use std::time::{Duration, Instant};

use apalis::prelude::{BoxDynError, Data};
use serde::{Deserialize, Serialize};
use sqlx::SqlitePool;

/// Text to describe. `work_ms` only exists so a job can be made slow on
/// purpose while testing shutdown and crash recovery.
#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct Job {
    pub id: String,
    pub text: String,
    #[serde(default)]
    pub work_ms: Option<u64>,
}

/// What the phone shows when the job is done.
#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct Outcome {
    pub chars: usize,
    pub words: usize,
    pub unique_words: usize,
    pub hash: String,
    pub elapsed_ms: u64,
}

impl Job {
    pub fn run(&self) -> Outcome {
        let started = Instant::now();
        let chars = self.text.chars().count();
        let words = self.text.split_whitespace().count();
        let mut seen: Vec<String> = self
            .text
            .split_whitespace()
            .map(|word| word.trim_matches(|c: char| !c.is_alphanumeric()).to_lowercase())
            .filter(|word| !word.is_empty())
            .collect();
        seen.sort();
        seen.dedup();

        Outcome {
            chars,
            words,
            unique_words: seen.len(),
            hash: format!("{:016x}", fnv1a64(self.text.as_bytes())),
            elapsed_ms: started.elapsed().as_millis() as u64,
        }
    }
}

/// FNV-1a: short, stable, and identical on every platform.
fn fnv1a64(bytes: &[u8]) -> u64 {
    let mut hash = 0xcbf2_9ce4_8422_2325u64;
    for byte in bytes {
        hash ^= u64::from(*byte);
        hash = hash.wrapping_mul(0x100_0000_01b3);
    }
    hash
}

/// Apalis calls this once per attempt. It is idempotent: re-running a job
/// overwrites the same row instead of adding a second result.
pub async fn handle(job: Job, pool: Data<SqlitePool>) -> Result<(), BoxDynError> {
    sqlx::query("UPDATE job_view SET status = 'running', attempts = attempts + 1 WHERE id = ?1")
        .bind(&job.id)
        .execute(&*pool)
        .await?;

    if let Some(ms) = job.work_ms {
        tokio::time::sleep(Duration::from_millis(ms)).await;
    }

    let outcome = job.run();
    sqlx::query(
        "UPDATE job_view SET status = 'done', result = ?2, done_at = ?3 WHERE id = ?1",
    )
    .bind(&job.id)
    .bind(serde_json::to_string(&outcome)?)
    .bind(crate::queue::now_ms())
    .execute(&*pool)
    .await?;

    Ok(())
}

#[cfg(test)]
mod tests {
    use super::*;

    fn job(text: &str) -> Job {
        Job {
            id: "t".into(),
            text: text.into(),
            work_ms: None,
        }
    }

    #[test]
    fn counts_words_and_characters() {
        let out = job("the quick brown fox").run();
        assert_eq!(out.chars, 19);
        assert_eq!(out.words, 4);
        assert_eq!(out.unique_words, 4);
    }

    #[test]
    fn repeated_words_collapse_and_punctuation_is_ignored() {
        let out = job("fox. Fox, FOX! dog").run();
        assert_eq!(out.words, 4);
        assert_eq!(out.unique_words, 2);
    }

    #[test]
    fn hash_is_stable_and_text_dependent() {
        assert_eq!(job("hello").run().hash, job("hello").run().hash);
        assert_ne!(job("hello").run().hash, job("hello!").run().hash);
        assert_eq!(job("hello").run().hash.len(), 16);
    }
}
