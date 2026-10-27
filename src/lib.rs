mod app;
pub mod jobs;

use std::{
    panic::{AssertUnwindSafe, catch_unwind},
    path::{Path, PathBuf},
};

use jni::{
    JNIEnv,
    objects::{JClass, JString},
    sys::jstring,
};
use sha2::{Digest, Sha256};
use url::Url;

pub use app::LinkQueueApp;
pub use jobs::{
    JobResult, JobStatus, enqueue_blocking, enqueue_url, new_job_id, read_job_result,
    run_with_runtime,
};

// Rivium owns process/embedded host lifecycle; the extra JNI methods below
// provide LinkQueue's small queue/result API alongside its five lifecycle calls.
rivium_jni::export!(
    class = "com/example/linkqueue/NativeBridge",
    app = LinkQueueApp
);

/// A successfully processed URL.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct ProcessedUrl {
    pub normalized_url: String,
    pub sha256: String,
}

/// Validate, normalize, and hash an HTTP or HTTPS URL.
pub fn process_url(input: &str) -> Result<ProcessedUrl, String> {
    let input = input.trim();

    if input.is_empty() {
        return Err("URL cannot be empty".to_string());
    }

    let mut parsed = Url::parse(input).map_err(|e| format!("Invalid URL: {e}"))?;

    match parsed.scheme() {
        "http" | "https" => {}
        scheme => {
            return Err(format!(
                "Unsupported URL scheme: {scheme}. Only HTTP and HTTPS are allowed"
            ));
        }
    }

    if parsed.host_str().is_none() {
        return Err("URL must contain a valid host".to_string());
    }

    // Fragments are client-side identifiers and are excluded from
    // the normalized URL used for processing.
    parsed.set_fragment(None);

    let normalized_url = parsed.to_string();

    let digest = Sha256::digest(normalized_url.as_bytes());

    let sha256 = digest
        .iter()
        .map(|byte| format!("{byte:02x}"))
        .collect::<String>();

    Ok(ProcessedUrl {
        normalized_url,
        sha256,
    })
}

/// Enqueue a URL in the durable Apalis queue.
///
/// JNI signature: NativeBridge.nativeEnqueue(jobId, url, dataDirectory).
#[unsafe(no_mangle)]
pub extern "system" fn Java_com_example_linkqueue_NativeBridge_nativeEnqueue(
    mut env: JNIEnv,
    _class: JClass,
    job_id: JString,
    input: JString,
    data_directory: JString,
) -> jstring {
    let output = catch_unwind(AssertUnwindSafe(|| {
        let result = (|| -> Result<(), String> {
            let job_id = java_string(&mut env, &job_id)?;
            let input = java_string(&mut env, &input)?;
            let root = java_string(&mut env, &data_directory)?;
            let root = PathBuf::from(root);
            run_with_runtime(enqueue_url(job_id, input, root))
                .map_err(|error| error.to_string())?
                .map_err(|error| error.to_string())
        })();
        result.map_or_else(|error| format!("ERROR: {error}"), |_| String::new())
    }))
    .unwrap_or_else(|_| "ERROR: Rust queue operation failed".to_owned());
    jni_string(&mut env, output)
}

/// Read a completed or failed result for one previously enqueued job.
///
/// JNI signature: NativeBridge.nativeGetResult(jobId, dataDirectory).
#[unsafe(no_mangle)]
pub extern "system" fn Java_com_example_linkqueue_NativeBridge_nativeGetResult(
    mut env: JNIEnv,
    _class: JClass,
    job_id: JString,
    data_directory: JString,
) -> jstring {
    let output = catch_unwind(AssertUnwindSafe(|| {
        let result = (|| -> Result<String, String> {
            let job_id = java_string(&mut env, &job_id)?;
            let root = PathBuf::from(java_string(&mut env, &data_directory)?);
            read_job_result(&root, &job_id).map_err(|error| error.to_string())
        })();
        result.unwrap_or_else(|error| format!("ERROR: {error}"))
    }))
    .unwrap_or_else(|_| "ERROR: Rust result lookup failed".to_owned());
    jni_string(&mut env, output)
}

fn java_string(env: &mut JNIEnv, value: &JString) -> Result<String, String> {
    env.get_string(value)
        .map(String::from)
        .map_err(|error| format!("Could not read input: {error}"))
}

fn jni_string(env: &mut JNIEnv, value: String) -> jstring {
    env.new_string(value)
        .map(|value| value.into_raw())
        .unwrap_or(std::ptr::null_mut())
}

pub fn result_path(data_directory: &Path, job_id: &str) -> Result<PathBuf, String> {
    if job_id.is_empty()
        || !job_id
            .bytes()
            .all(|byte| byte.is_ascii_alphanumeric() || byte == b'-' || byte == b'_')
    {
        return Err("Invalid job ID".to_owned());
    }
    Ok(data_directory
        .join("results")
        .join(format!("{job_id}.json")))
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn normalizes_and_hashes_https_url() {
        let result = process_url("https://example.com").expect("URL should be valid");

        assert_eq!(result.normalized_url, "https://example.com/");
        assert_eq!(result.sha256.len(), 64);
    }

    #[test]
    fn rejects_unsupported_schemes() {
        assert!(process_url("ftp://example.com").is_err());
    }

    #[test]
    fn rejects_malformed_urls() {
        assert!(process_url("not a URL").is_err());
    }

    #[test]
    fn trims_surrounding_whitespace() {
        let result = process_url("  https://example.com  ").expect("URL should be valid");

        assert_eq!(result.normalized_url, "https://example.com/");
    }
}
