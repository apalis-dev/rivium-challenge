//! usage: enqueue --root <dir> <file.docx>...   (copies each file and queues one conversion job)
use std::path::PathBuf;

#[tokio::main]
async fn main() -> Result<(), Box<dyn std::error::Error + Send + Sync>> {
    let mut args = std::env::args().skip(1);
    let mut root = PathBuf::from("target/docq-root");
    let mut files: Vec<PathBuf> = Vec::new();
    while let Some(a) = args.next() {
        if a == "--root" {
            root = args.next().ok_or("--root needs a value")?.into();
        } else {
            files.push(a.into());
        }
    }
    let pool = docq::open(&root).await?;
    let n = docq::enqueue_files(&root, &pool, &files).await?;
    println!("queued {n} file(s)");
    Ok(())
}