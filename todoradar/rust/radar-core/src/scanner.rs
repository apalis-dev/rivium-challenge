//! Pure, synchronous zip scanning: no database, no network, so it is easy to test.

use std::{io::Read, path::Path, sync::LazyLock};

use regex::Regex;
use sha2::{Digest, Sha256};

/// Entries above this size are skipped (generated or vendored blobs).
const MAX_ENTRY_BYTES: u64 = 1024 * 1024;
const SNIFF_BYTES: usize = 8 * 1024;
const SKIP_DIRS: &[&str] = &[
    "node_modules/", "vendor/", "target/", ".git/", "dist/", "build/", "third_party/",
];

static MARKER: LazyLock<Regex> =
    LazyLock::new(|| Regex::new(r"\b(TODO|FIXME|HACK|XXX)\b(?:\(([^)]*)\))?:?\s*(.*)").unwrap());

#[derive(Clone, Debug, PartialEq, Eq)]
pub struct Todo {
    pub path: String,
    pub line: u32,
    pub tag: String,
    pub text: String,
    pub fingerprint: String,
}

#[derive(Debug, Default)]
pub struct Chunk {
    pub todos: Vec<Todo>,
    /// Index of the next entry to scan; equals `entries_total` when finished.
    pub next_entry: usize,
    pub entries_total: usize,
}

/// Number of entries in the archive, or an error if it is not a readable zip.
pub fn entry_count(zip: &Path) -> zip::result::ZipResult<usize> {
    Ok(zip::ZipArchive::new(std::fs::File::open(zip)?)?.len())
}

/// Scan `count` entries starting at `start`. Re-opening per chunk keeps every checkpoint
/// independent, so a resumed job needs nothing but the saved index.
pub fn scan_chunk(zip: &Path, start: usize, count: usize) -> zip::result::ZipResult<Chunk> {
    let mut archive = zip::ZipArchive::new(std::fs::File::open(zip)?)?;
    let total = archive.len();
    let end = (start + count).min(total);
    let root = common_root(&archive);
    let mut todos = Vec::new();
    for index in start..end {
        let mut entry = archive.by_index(index)?;
        if entry.is_dir() || entry.size() > MAX_ENTRY_BYTES {
            continue;
        }
        let name = entry.name()?.into_owned();
        let path = name.strip_prefix(root.as_str()).unwrap_or(&name).to_owned();
        if path.is_empty() || skipped(&path) {
            continue;
        }
        let mut bytes = Vec::with_capacity(entry.size() as usize);
        entry.read_to_end(&mut bytes)?;
        todos.extend(scan_text(&path, &bytes));
    }
    Ok(Chunk {
        todos,
        next_entry: end,
        entries_total: total,
    })
}

/// Read one file (path relative to the archive's common root) for the code peek.
pub fn read_entry(zip: &Path, path: &str) -> Option<Vec<u8>> {
    let mut archive = zip::ZipArchive::new(std::fs::File::open(zip).ok()?).ok()?;
    let name = format!("{}{path}", common_root(&archive));
    let mut entry = archive.by_name(&name).ok()?;
    if entry.size() > MAX_ENTRY_BYTES {
        return None;
    }
    let mut bytes = Vec::with_capacity(entry.size() as usize);
    entry.read_to_end(&mut bytes).ok()?;
    Some(bytes)
}

/// Find markers in one file's bytes; binary files yield nothing.
pub fn scan_text(path: &str, bytes: &[u8]) -> Vec<Todo> {
    if bytes[..bytes.len().min(SNIFF_BYTES)].contains(&0) {
        return Vec::new();
    }
    let text = String::from_utf8_lossy(bytes);
    text.lines()
        .enumerate()
        .filter_map(|(index, line)| {
            let captures = MARKER.captures(line)?;
            let tag = captures[1].to_owned();
            let owner = captures.get(2).map(|m| m.as_str().trim()).filter(|s| !s.is_empty());
            let body = clean(&captures[3]);
            let text = match owner {
                Some(owner) => format!("({owner}) {body}"),
                None => body,
            };
            Some(Todo {
                fingerprint: fingerprint(path, &tag, &text),
                path: path.to_owned(),
                line: index as u32 + 1,
                tag,
                text,
            })
        })
        .collect()
}

/// Stable identity for diffing scans: moving a TODO to another line is not "new".
pub fn fingerprint(path: &str, tag: &str, text: &str) -> String {
    let digest = Sha256::digest(format!("{path}\u{1f}{tag}\u{1f}{}", text.trim()));
    digest[..12].iter().map(|b| format!("{b:02x}")).collect()
}

/// GitHub zipballs wrap everything in `owner-repo-sha/`; strip a folder shared by every entry.
fn common_root(archive: &zip::ZipArchive<std::fs::File>) -> String {
    let first = match archive.file_names().next() {
        Some(Ok(name)) => name.into_owned(),
        _ => return String::new(),
    };
    let Some((first, _)) = first.split_once('/') else {
        return String::new();
    };
    let root = format!("{first}/");
    if archive.file_names().all(|name| name.is_ok_and(|n| n.starts_with(&root))) {
        root
    } else {
        String::new()
    }
}

fn skipped(path: &str) -> bool {
    let lower = path.to_ascii_lowercase();
    SKIP_DIRS.iter().any(|dir| lower.starts_with(dir) || lower.contains(&format!("/{dir}")))
        || lower.ends_with(".min.js")
        || lower.ends_with(".min.css")
        || lower.ends_with(".lock")
}

/// Drop trailing comment closers and cap the length shown in the UI.
fn clean(body: &str) -> String {
    let body = body.trim().trim_end_matches("*/").trim_end_matches("-->").trim();
    let mut text: String = body.chars().take(200).collect();
    if body.chars().count() > 200 {
        text.push('…');
    }
    text
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::io::Write;

    #[test]
    fn finds_markers_with_owner_and_line_numbers() {
        let source = b"fn main() {\n    // TODO(ben): handle shutdown\n    let x = 1; // FIXME overflow */\n}\n// not a todo: TODOS\n";
        let todos = scan_text("src/main.rs", source);
        assert_eq!(todos.len(), 2);
        assert_eq!((todos[0].line, todos[0].tag.as_str()), (2, "TODO"));
        assert_eq!(todos[0].text, "(ben) handle shutdown");
        assert_eq!((todos[1].line, todos[1].text.as_str()), (3, "overflow"));
    }

    #[test]
    fn binary_files_are_ignored() {
        assert!(scan_text("logo.png", b"\x89PNG\0\0TODO: nope").is_empty());
    }

    #[test]
    fn fingerprint_ignores_line_moves_but_not_text() {
        let a = scan_text("a.rs", b"// TODO: one");
        let b = scan_text("a.rs", b"\n\n// TODO: one");
        let c = scan_text("a.rs", b"// TODO: two");
        assert_eq!(a[0].fingerprint, b[0].fingerprint);
        assert_ne!(a[0].fingerprint, c[0].fingerprint);
    }

    #[test]
    fn chunked_scan_resumes_from_index_and_skips_vendored() {
        let dir = std::env::temp_dir().join(format!("radar-scan-{}", std::process::id()));
        std::fs::create_dir_all(&dir).unwrap();
        let path = dir.join("repo.zip");
        let mut zip = zip::ZipWriter::new(std::fs::File::create(&path).unwrap());
        let options = zip::write::SimpleFileOptions::default();
        for i in 0..5 {
            zip.start_file(format!("owner-repo-abc/src/f{i}.rs"), options).unwrap();
            writeln!(zip, "// TODO: item {i}").unwrap();
        }
        zip.start_file("owner-repo-abc/node_modules/x.js", options).unwrap();
        writeln!(zip, "// TODO: vendored").unwrap();
        zip.finish().unwrap();

        let first = scan_chunk(&path, 0, 3).unwrap();
        let rest = scan_chunk(&path, first.next_entry, 100).unwrap();
        assert_eq!(first.entries_total, 6);
        assert_eq!(first.todos.len(), 3);
        assert_eq!(rest.todos.len(), 2, "vendored file skipped");
        assert_eq!(rest.next_entry, 6);
        assert_eq!(first.todos[0].path, "src/f0.rs");
        std::fs::remove_dir_all(dir).ok();
    }
}
