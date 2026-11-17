//! Link Digest: an Apalis job queue (SQLite) supervised by a Rivium service.

mod app;
mod job;

pub use app::LinkDigest;
pub use job::{DigestLink, db_path, enqueue};

// Entry points for the Android app (class com.horace.linkdigest.Bridge).
rivium_jni::export!(class = "com/horace/linkdigest/Bridge", app = LinkDigest);
