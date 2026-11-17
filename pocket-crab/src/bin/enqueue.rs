//! `cargo run --bin enqueue -- <root> <url>`
#[tokio::main]
async fn main() {
    let mut a = std::env::args().skip(1);
    let (Some(root), Some(url)) = (a.next(), a.next()) else {
        eprintln!("usage: enqueue <root> <url>");
        std::process::exit(2);
    };
    link_digest::enqueue(root.as_ref(), &url)
        .await
        .expect("enqueue");
    println!("queued {url}");
}
