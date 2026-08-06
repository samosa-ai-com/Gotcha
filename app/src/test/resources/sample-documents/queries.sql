-- Project Falcon — schema and sample queries
CREATE TABLE IF NOT EXISTS sessions (
    id         TEXT PRIMARY KEY,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    user_label TEXT
);

CREATE TABLE IF NOT EXISTS attachments (
    id          INTEGER PRIMARY KEY AUTOINCREMENT,
    session_id  TEXT NOT NULL REFERENCES sessions(id),
    file_name   TEXT NOT NULL,
    mime_type   TEXT,
    size_bytes  INTEGER NOT NULL,
    ext_chars   INTEGER NOT NULL,
    truncated   INTEGER DEFAULT 0,
    created_at  TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

-- All attachments that were truncated (over the 40k char cap)
SELECT file_name, size_bytes, ext_chars
FROM attachments
WHERE truncated = 1
ORDER BY created_at DESC;

-- Attachment formats seen in the last 7 days
SELECT mime_type, COUNT(*) AS n
FROM attachments
WHERE created_at >= datetime('now', '-7 days')
GROUP BY mime_type
ORDER BY n DESC;

-- Most recent failed picks (corrupt or unsupported files are not inserted,
-- so this lists sessions that have zero attachments)
SELECT s.id, s.user_label
FROM sessions s
LEFT JOIN attachments a ON a.session_id = s.id
WHERE a.id IS NULL;
