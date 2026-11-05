import sqlite3
from contextlib import contextmanager
from pathlib import Path

MIGRATIONS = [
    """
    CREATE TABLE IF NOT EXISTS sessions (
        id TEXT PRIMARY KEY,
        sub TEXT NOT NULL,
        email TEXT NOT NULL,
        name TEXT NOT NULL,
        description TEXT NOT NULL,
        visibility TEXT NOT NULL DEFAULT 'private',
        labels TEXT NOT NULL,
        env TEXT NOT NULL,
        image TEXT NOT NULL,
        secret_key TEXT NOT NULL,
        created_at TEXT NOT NULL,
        last_activity_at TEXT NOT NULL,
        stopped_at TEXT
    );
    CREATE INDEX IF NOT EXISTS sessions_sub ON sessions (sub);
    CREATE TABLE IF NOT EXISTS review_comments (
        session_id TEXT NOT NULL,
        key TEXT NOT NULL,
        comment_id INTEGER NOT NULL,
        posted_at TEXT NOT NULL,
        PRIMARY KEY (session_id, key)
    );
    """,
    """
    ALTER TABLE sessions ADD COLUMN team_id TEXT;
    ALTER TABLE sessions ADD COLUMN run_id TEXT;
    ALTER TABLE sessions ADD COLUMN activity TEXT;
    CREATE TABLE platform_state (key TEXT PRIMARY KEY, value TEXT NOT NULL);
    CREATE TABLE teams (
        id TEXT PRIMARY KEY,
        spec TEXT NOT NULL,
        commit_id TEXT,
        synced_at TEXT,
        status TEXT NOT NULL,
        error TEXT
    );
    CREATE TABLE team_members (
        team_id TEXT NOT NULL,
        username TEXT NOT NULL,
        role TEXT NOT NULL,
        PRIMARY KEY (team_id, username)
    );
    CREATE TABLE definitions (
        team_id TEXT NOT NULL,
        kind TEXT NOT NULL,
        id TEXT NOT NULL,
        source TEXT NOT NULL,
        spec TEXT,
        errors TEXT NOT NULL,
        PRIMARY KEY (team_id, kind, id)
    );
    CREATE TABLE runs (
        id TEXT PRIMARY KEY,
        team_id TEXT NOT NULL,
        workflow_id TEXT NOT NULL,
        activity TEXT NOT NULL,
        status TEXT NOT NULL,
        trigger TEXT NOT NULL,
        inputs TEXT NOT NULL,
        session_id TEXT,
        result TEXT,
        error TEXT,
        created_by TEXT NOT NULL,
        created_at TEXT NOT NULL,
        updated_at TEXT NOT NULL
    );
    CREATE INDEX runs_team ON runs (team_id, created_at);
    CREATE TABLE trigger_items (
        trigger_key TEXT NOT NULL,
        item TEXT NOT NULL,
        run_id TEXT,
        seen_at TEXT NOT NULL,
        PRIMARY KEY (trigger_key, item)
    );
    CREATE TABLE llm_usage (
        id INTEGER PRIMARY KEY AUTOINCREMENT,
        ts TEXT NOT NULL,
        session_id TEXT NOT NULL,
        run_id TEXT,
        team_id TEXT,
        connection TEXT NOT NULL,
        model TEXT NOT NULL,
        status INTEGER NOT NULL,
        prompt_tokens INTEGER NOT NULL,
        completion_tokens INTEGER NOT NULL,
        cached_tokens INTEGER NOT NULL,
        latency_ms INTEGER NOT NULL
    );
    CREATE INDEX llm_usage_session ON llm_usage (session_id);
    CREATE TABLE audit_events (
        seq INTEGER PRIMARY KEY AUTOINCREMENT,
        session_id TEXT NOT NULL,
        ts TEXT NOT NULL,
        type TEXT NOT NULL,
        key TEXT NOT NULL,
        payload TEXT NOT NULL,
        prev_hash TEXT NOT NULL,
        hash TEXT NOT NULL,
        UNIQUE (session_id, key)
    );
    CREATE TRIGGER audit_events_no_update BEFORE UPDATE ON audit_events
        BEGIN SELECT RAISE(ABORT, 'audit events are append-only'); END;
    CREATE TRIGGER audit_events_no_delete BEFORE DELETE ON audit_events
        BEGIN SELECT RAISE(ABORT, 'audit events are append-only'); END;
    """,
]


class Database:
    def __init__(self, path: str):
        Path(path).parent.mkdir(parents=True, exist_ok=True)
        self.path = path
        self._migrate()

    @contextmanager
    def connect(self):
        db = sqlite3.connect(self.path, timeout=30)
        db.row_factory = sqlite3.Row
        db.execute("PRAGMA journal_mode=WAL")
        try:
            yield db
            db.commit()
        finally:
            db.close()

    def _migrate(self) -> None:
        with self.connect() as db:
            db.execute("CREATE TABLE IF NOT EXISTS schema_version (version INTEGER NOT NULL)")
            row = db.execute("SELECT version FROM schema_version").fetchone()
            version = row["version"] if row else 0
            if row is None:
                db.execute("INSERT INTO schema_version (version) VALUES (0)")
            for number, script in enumerate(MIGRATIONS[version:], start=version + 1):
                for statement in _statements(script):
                    try:
                        db.execute(statement)
                    except sqlite3.OperationalError as error:
                        if "duplicate column" not in str(error) and "already exists" not in str(error):
                            raise
                db.execute("UPDATE schema_version SET version = ?", (number,))


def _statements(script: str) -> list[str]:
    statements, buffer = [], []
    for line in script.strip().splitlines():
        buffer.append(line)
        joined = "\n".join(buffer).strip()
        if joined.endswith(";") and (not joined.upper().startswith("CREATE TRIGGER") or joined.upper().endswith("END;")):
            statements.append(joined)
            buffer = []
    return statements
