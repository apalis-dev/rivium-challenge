use std::{env, path::PathBuf};

use rivium_challenge::{LinkQueueApp, enqueue_blocking, new_job_id};

fn main() {
    let mut args = env::args().skip(1);
    match args.next().as_deref() {
        Some("enqueue") => {
            let Some(url) = args.next() else {
                eprintln!("Usage: cargo run -- enqueue <URL> [data-directory]");
                std::process::exit(64);
            };
            let data_directory = args
                .next()
                .map(PathBuf::from)
                .unwrap_or_else(rivium_challenge::jobs::default_data_directory);
            let job_id = new_job_id(&url);

            match enqueue_blocking(job_id.clone(), url, data_directory) {
                Ok(()) => {
                    println!("Queued job {job_id}");
                    return;
                }
                Err(error) => {
                    eprintln!("Could not enqueue URL: {error}");
                    std::process::exit(1);
                }
            }
        }
        Some("--help") | Some("-h") => {
            print_help();
            return;
        }
        _ => std::process::exit(rivium_host::process::run::<LinkQueueApp>().ffi()),
    }
}

fn print_help() {
    println!("LinkQueue durable URL worker");
    println!();
    println!("Start the Rivium worker:");
    println!("  cargo run -- --root target/linkqueue --set data_dir=target/linkqueue");
    println!();
    println!("Enqueue a URL:");
    println!("  cargo run -- enqueue https://example.com target/linkqueue");
}
