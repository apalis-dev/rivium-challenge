-- App tables live beside apalis' Jobs/Workers tables in radar.db. apalis owns
-- _sqlx_migrations, so ours are idempotent statements run on every open.
CREATE TABLE IF NOT EXISTS auth (
    id         INTEGER PRIMARY KEY CHECK (id = 1),
    token      TEXT NOT NULL,
    login      TEXT NOT NULL,
    created_at INTEGER NOT NULL DEFAULT (strftime('%s', 'now'))
);

CREATE TABLE IF NOT EXISTS scans (
    id             INTEGER PRIMARY KEY AUTOINCREMENT,
    source_kind    TEXT NOT NULL CHECK (source_kind IN ('github', 'zip')),
    owner          TEXT,
    repo           TEXT,
    zip_path       TEXT,
    sha            TEXT,
    state          TEXT NOT NULL DEFAULT 'queued',   -- queued | running | done | failed
    stage          TEXT NOT NULL DEFAULT 'resolve',  -- resolve | download | scan | diff | done
    bytes_done     INTEGER NOT NULL DEFAULT 0,
    bytes_total    INTEGER,
    entries_done   INTEGER NOT NULL DEFAULT 0,
    entries_total  INTEGER,
    todo_count     INTEGER NOT NULL DEFAULT 0,
    new_count      INTEGER NOT NULL DEFAULT 0,
    resolved_count INTEGER NOT NULL DEFAULT 0,
    prev_scan_id   INTEGER,
    attempts       INTEGER NOT NULL DEFAULT 0,
    keep_files     INTEGER NOT NULL DEFAULT 0,
    files_deleted  INTEGER NOT NULL DEFAULT 0,
    cleanup_at     INTEGER,
    error          TEXT,
    created_at     INTEGER NOT NULL DEFAULT (strftime('%s', 'now')),
    finished_at    INTEGER
);
CREATE INDEX IF NOT EXISTS scans_source ON scans (source_kind, owner, repo, zip_path, state);

CREATE TABLE IF NOT EXISTS todos (
    scan_id     INTEGER NOT NULL REFERENCES scans (id) ON DELETE CASCADE,
    path        TEXT NOT NULL,
    line        INTEGER NOT NULL,
    tag         TEXT NOT NULL,
    text        TEXT NOT NULL,
    fingerprint TEXT NOT NULL,
    is_new      INTEGER NOT NULL DEFAULT 0
);
CREATE INDEX IF NOT EXISTS todos_scan ON todos (scan_id, fingerprint);
