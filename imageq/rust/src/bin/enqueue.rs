//! usage: enqueue --root <dir> <image-path>...
use std::path::PathBuf;

#[tokio::main]
async fn main() -> Result<(), Box<dyn std::error::Error + Send + Sync>> {
    let mut args = std::env::args().skip(1);
    let mut root = PathBuf::from("target/imageq-root");
    let mut files = Vec::new();
    while let Some(a) = args.next() {
        if a == "--root" {
            root = args.next().ok_or("--root needs a value")?.into();
        } else {
            files.push(a);
        }
    }
    let pool = imageq::open(&root).await?;
    for f in files {
        imageq::enqueue(&pool, &f).await?;
        println!("queued {f}");
    }
    Ok(())
}