//! Laptop runner: `cargo run -- --root target/demo`, then drop photos into target/demo/inbox, drawings appear in target/demo/outbox.

fn main() -> rivium_host::Code {
    rivium_host::process::run::<mchoro::Mchoro>()
}
