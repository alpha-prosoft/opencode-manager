from ..db import Database
from ..store import now


class UsageStore:
    def __init__(self, db: Database):
        self.db = db

    def record(self, *, session_id, run_id, team_id, connection, model, status, prompt, completion, cached, latency_ms) -> None:
        with self.db.connect() as db:
            db.execute(
                "INSERT INTO llm_usage (ts, session_id, run_id, team_id, connection, model, status, prompt_tokens, "
                "completion_tokens, cached_tokens, latency_ms) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                (now(), session_id, run_id, team_id, connection, model, status, prompt, completion, cached, latency_ms),
            )

    def _summary(self, where: str, params: tuple) -> dict:
        with self.db.connect() as db:
            row = db.execute(
                "SELECT COUNT(*) AS calls, COALESCE(SUM(prompt_tokens), 0) AS prompt_tokens, "
                "COALESCE(SUM(completion_tokens), 0) AS completion_tokens, COALESCE(SUM(cached_tokens), 0) AS cached_tokens, "
                "COALESCE(SUM(status >= 400), 0) AS errors, COALESCE(AVG(latency_ms), 0) AS avg_latency_ms "
                f"FROM llm_usage WHERE {where}",
                params,
            ).fetchone()
        return {k: int(row[k]) for k in row.keys()}

    def for_session(self, session_id: str) -> dict:
        return self._summary("session_id = ?", (session_id,))

    def for_run(self, run_id: str) -> dict:
        return self._summary("run_id = ?", (run_id,))

    def tokens_since(self, team_id: str, since: str) -> int:
        with self.db.connect() as db:
            row = db.execute(
                "SELECT COALESCE(SUM(prompt_tokens + completion_tokens), 0) FROM llm_usage WHERE team_id = ? AND ts >= ?",
                (team_id, since),
            ).fetchone()
        return int(row[0])

    def for_team(self, team_id: str, since: str) -> dict:
        return self._summary("team_id = ? AND ts >= ?", (team_id, since))

    def by_model(self, team_ids: set[str], since: str) -> list[dict]:
        if not team_ids:
            return []
        marks = ",".join("?" * len(team_ids))
        with self.db.connect() as db:
            rows = db.execute(
                "SELECT team_id, model, COUNT(*) AS calls, SUM(prompt_tokens) AS prompt_tokens, "
                "SUM(completion_tokens) AS completion_tokens FROM llm_usage "
                f"WHERE team_id IN ({marks}) AND ts >= ? GROUP BY team_id, model ORDER BY team_id, model",
                (*team_ids, since),
            ).fetchall()
        return [dict(r) for r in rows]

    def by_team_model(self, since: str) -> list[dict]:
        with self.db.connect() as db:
            rows = db.execute(
                "SELECT COALESCE(team_id, '') AS team_id, connection, model, COUNT(*) AS calls, SUM(prompt_tokens) AS prompt_tokens, "
                "SUM(completion_tokens) AS completion_tokens, SUM(cached_tokens) AS cached_tokens, SUM(status >= 400) AS errors "
                "FROM llm_usage WHERE ts >= ? GROUP BY team_id, connection, model ORDER BY team_id, model",
                (since,),
            ).fetchall()
        return [dict(r) for r in rows]

    def by_user(self, since: str) -> list[dict]:
        with self.db.connect() as db:
            rows = db.execute(
                "SELECT r.created_by AS username, COUNT(DISTINCT r.id) AS runs, COALESCE(SUM(u.prompt_tokens + u.completion_tokens), 0) AS tokens "
                "FROM runs r LEFT JOIN llm_usage u ON u.run_id = r.id WHERE r.created_at >= ? GROUP BY r.created_by ORDER BY tokens DESC",
                (since,),
            ).fetchall()
        return [dict(r) for r in rows]

    def totals_for_metrics(self) -> list[dict]:
        with self.db.connect() as db:
            rows = db.execute(
                "SELECT COALESCE(team_id, '') AS team_id, model, COUNT(*) AS calls, SUM(prompt_tokens) AS prompt_tokens, "
                "SUM(completion_tokens) AS completion_tokens, SUM(status >= 400) AS errors "
                "FROM llm_usage GROUP BY team_id, model"
            ).fetchall()
        return [dict(r) for r in rows]
