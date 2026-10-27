//! Laptop client for `<root>/taskping.db`.
//!   submit <root> add <a> <b>          queue an Add job and wait for its result
//!   submit <root> table <base> <upto>  queue a long Table job, print its id and return
//!   submit <root> check <id>           print a job's state (pending / running:n/m / done:x)

use std::path::PathBuf;
use taskping::{DemoJob, check, submit, submit_and_wait};

#[tokio::main]
async fn main() -> Result<(), apalis::prelude::BoxDynError> {
    let args: Vec<String> = std::env::args().skip(1).collect();
    let usage = "usage: submit <root> add <a> <b> | table <base> <upto> | check <id>";
    let (root, rest) = args.split_first().ok_or(usage)?;
    let root = PathBuf::from(root);
    match rest {
        [cmd, a, b] if cmd == "add" => {
            let job = DemoJob::Add { a: a.parse()?, b: b.parse()? };
            println!("[client] result: {}", submit_and_wait(&root, job).await?);
        }
        [cmd, base, upto] if cmd == "table" => {
            let job = DemoJob::Table { base: base.parse()?, upto: upto.parse()? };
            println!("{}", submit(&root, job).await?);
        }
        [cmd, id] if cmd == "check" => println!("{}", check(&root, id).await?),
        _ => return Err(usage.into()),
    }
    Ok(())
}
