use pocket_backend::PocketApp;
use rivium_host::Code;

fn main() -> Code {
    rivium_host::process::run::<PocketApp>()
}
