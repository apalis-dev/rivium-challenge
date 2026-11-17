use rivium_host::Code;

fn main() -> Code {
    rivium_host::process::run::<link_digest::LinkDigest>()
}
