//! Laptop host: runs the Rivium-managed Apalis worker until Ctrl-C.

fn main() -> rivium_host::Code {
    rivium_host::process::run::<taskping::TaskPingApp>()
}
