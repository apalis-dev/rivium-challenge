use rivium_host::Code;

fn main() -> Code {
    rivium_host::process::run::<docq::Docq>()
}
