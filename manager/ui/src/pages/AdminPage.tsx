import { useState } from "react";
import {
  Alert,
  Box,
  Button,
  Chip,
  MenuItem,
  Paper,
  Stack,
  Tab,
  Table,
  TableBody,
  TableCell,
  TableHead,
  TableRow,
  Tabs,
  TextField,
  Typography,
} from "@mui/material";
import CheckCircleIcon from "@mui/icons-material/CheckCircle";
import ErrorIcon from "@mui/icons-material/Error";
import SyncIcon from "@mui/icons-material/Sync";
import { api } from "../api";
import { relativeTime, tokens } from "../components/format";
import { useLoad } from "../hooks";

function Stat({ label, value, hint }: { label: string; value: string; hint?: string }) {
  return (
    <Paper variant="outlined" sx={{ p: 2, flex: 1, minWidth: 160 }}>
      <Typography variant="caption" color="text.secondary">
        {label}
      </Typography>
      <Typography variant="h5">{value}</Typography>
      {hint && (
        <Typography variant="caption" color="text.secondary">
          {hint}
        </Typography>
      )}
    </Paper>
  );
}

function Overview() {
  const overview = useLoad(() => api.admin.overview(), [], 15000);
  const [report, setReport] = useState<{ ok: boolean; text: string } | null>(null);
  const [busy, setBusy] = useState<string | null>(null);

  const run = async (name: string, action: () => Promise<string>) => {
    setBusy(name);
    try {
      setReport({ ok: true, text: await action() });
      overview.reload();
    } catch (e) {
      setReport({ ok: false, text: (e as Error).message });
    } finally {
      setBusy(null);
    }
  };
  const o = overview.data;
  if (!o) return null;
  return (
    <Stack spacing={3}>
      <Stack direction="row" spacing={1}>
        <Button
          variant="contained"
          startIcon={<SyncIcon />}
          disabled={busy !== null}
          onClick={() =>
            run("sync", async () => {
              const result = await api.sync();
              return result.error ? Promise.reject(new Error(result.error)) : `Synced onboarding ${String(result.commit).slice(0, 12)}.`;
            })
          }
        >
          {busy === "sync" ? "Syncing…" : "Sync now"}
        </Button>
        <Button
          variant="outlined"
          disabled={busy !== null}
          onClick={() => run("idle", async () => `Stopped ${(await api.admin.stopIdle()).stopped.length} idle session(s).`)}
        >
          Stop idle sessions
        </Button>
      </Stack>
      {report && <Alert severity={report.ok ? "success" : "error"}>{report.text}</Alert>}
      {o.status.error && <Alert severity="error">Onboarding sync: {o.status.error}</Alert>}
      <Box sx={{ display: "flex", gap: 2, flexWrap: "wrap" }}>
        <Stat label="Teams" value={String(o.teams)} hint={`${o.users} users`} />
        <Stat label="Running sessions" value={String(o.sessions.running)} hint={`${o.sessions.total} total`} />
        <Stat
          label="Runs today"
          value={String(Object.values(o.runs_today).reduce((a, b) => a + b, 0))}
          hint={Object.entries(o.runs_today).map(([k, v]) => `${v} ${k}`).join(" · ") || "none"}
        />
        <Stat label="Tokens today" value={tokens(o.tokens_today)} />
        <Stat label="Estimated cost today" value={o.cost_today === null ? "—" : `$${o.cost_today.toFixed(2)}`} hint="from model pricing" />
      </Box>
      <Typography variant="body2" color="text.secondary">
        Onboarding <code>{o.status.commit?.slice(0, 16) ?? "—"}</code> · synced {relativeTime(o.status.synced_at)}
      </Typography>
    </Stack>
  );
}

function Users() {
  const users = useLoad(() => api.admin.users(), []);
  const teams = [...new Set((users.data ?? []).flatMap((u) => Object.keys(u.teams)))].sort();
  return (
    <Paper variant="outlined" sx={{ overflowX: "auto" }}>
      <Table size="small">
        <TableHead>
          <TableRow>
            <TableCell>User</TableCell>
            <TableCell>Platform</TableCell>
            {teams.map((team) => (
              <TableCell key={team}>{team}</TableCell>
            ))}
            <TableCell align="right">Runs (30 d)</TableCell>
            <TableCell align="right">Tokens (30 d)</TableCell>
          </TableRow>
        </TableHead>
        <TableBody>
          {(users.data ?? []).map((user) => (
            <TableRow key={user.username}>
              <TableCell sx={{ fontWeight: 600 }}>{user.username}</TableCell>
              <TableCell>{user.platform_admin && <Chip size="small" color="primary" label="admin" />}</TableCell>
              {teams.map((team) => (
                <TableCell key={team}>
                  {user.teams[team] && (
                    <Chip size="small" variant={user.teams[team] === "admin" ? "filled" : "outlined"} label={user.teams[team]} />
                  )}
                </TableCell>
              ))}
              <TableCell align="right">{user.runs_30d}</TableCell>
              <TableCell align="right">{tokens(user.tokens_30d)}</TableCell>
            </TableRow>
          ))}
        </TableBody>
      </Table>
      <Typography variant="caption" color="text.secondary" sx={{ display: "block", p: 1.5 }}>
        Roles are managed in the onboarding repository (platform.json): <code>admins</code>, and each team's <code>admins</code> and{" "}
        <code>members</code>.
      </Typography>
    </Paper>
  );
}

function Usage() {
  const [days, setDays] = useState(7);
  const usage = useLoad(() => api.admin.usage(days), [days]);
  const rows = usage.data ?? [];
  const total = rows.reduce((sum, r) => sum + (r.cost ?? 0), 0);
  return (
    <Stack spacing={2}>
      <Stack direction="row" spacing={2} sx={{ alignItems: "center" }}>
        <TextField select size="small" label="Period" value={days} onChange={(e) => setDays(Number(e.target.value))}>
          {[1, 7, 30, 90].map((d) => (
            <MenuItem key={d} value={d}>
              last {d} day{d > 1 ? "s" : ""}
            </MenuItem>
          ))}
        </TextField>
        <Typography>
          Estimated cost: <strong>${total.toFixed(2)}</strong>
        </Typography>
      </Stack>
      <Paper variant="outlined">
        <Table size="small">
          <TableHead>
            <TableRow>
              <TableCell>Team</TableCell>
              <TableCell>Model</TableCell>
              <TableCell align="right">Calls</TableCell>
              <TableCell align="right">Prompt</TableCell>
              <TableCell align="right">Cached</TableCell>
              <TableCell align="right">Completion</TableCell>
              <TableCell align="right">Errors</TableCell>
              <TableCell align="right">Cost</TableCell>
            </TableRow>
          </TableHead>
          <TableBody>
            {rows.map((row) => (
              <TableRow key={`${row.team_id}-${row.model}`}>
                <TableCell>{row.team_id}</TableCell>
                <TableCell>{row.model}</TableCell>
                <TableCell align="right">{row.calls}</TableCell>
                <TableCell align="right">{tokens(row.prompt_tokens)}</TableCell>
                <TableCell align="right">{tokens(row.cached_tokens)}</TableCell>
                <TableCell align="right">{tokens(row.completion_tokens)}</TableCell>
                <TableCell align="right">{row.errors}</TableCell>
                <TableCell align="right">{row.cost === null ? "—" : `$${row.cost.toFixed(2)}`}</TableCell>
              </TableRow>
            ))}
          </TableBody>
        </Table>
      </Paper>
    </Stack>
  );
}

function Secrets() {
  const secrets = useLoad(() => api.admin.secrets(), []);
  return (
    <Paper variant="outlined">
      <Table size="small">
        <TableHead>
          <TableRow>
            <TableCell>Secret</TableCell>
            <TableCell>Configured</TableCell>
            <TableCell>Used by</TableCell>
            <TableCell>Allowed hosts</TableCell>
          </TableRow>
        </TableHead>
        <TableBody>
          {(secrets.data ?? []).map((secret) => (
            <TableRow key={secret.name}>
              <TableCell sx={{ fontFamily: "monospace" }}>{secret.name}</TableCell>
              <TableCell>
                {secret.configured ? <CheckCircleIcon color="success" fontSize="small" /> : <Chip size="small" color="error" label="missing" />}
              </TableCell>
              <TableCell>{secret.used_by.join(", ")}</TableCell>
              <TableCell sx={{ fontFamily: "monospace" }}>{secret.hosts.join(", ") || "—"}</TableCell>
            </TableRow>
          ))}
        </TableBody>
      </Table>
      <Typography variant="caption" color="text.secondary" sx={{ display: "block", p: 1.5 }}>
        Values are never shown. Secrets come from files in /config/secrets or PLATFORM_SECRET_* variables.
      </Typography>
    </Paper>
  );
}

function Connections() {
  const status = useLoad(() => api.platform(), []);
  const [results, setResults] = useState<Record<string, string>>({});
  const test = async (id: string) => {
    setResults((r) => ({ ...r, [id]: "testing…" }));
    const result = await api.admin.testConnection(id);
    setResults((r) => ({ ...r, [id]: result.ok ? `OK · ${result.model} · ${result.latency_ms} ms` : `Failed: ${result.error}` }));
  };
  return (
    <Stack spacing={2}>
      {(status.data?.llm_connections ?? []).map((connection) => (
        <Paper key={connection.id} variant="outlined" sx={{ p: 2 }}>
          <Stack direction="row" spacing={2} sx={{ alignItems: "center" }}>
            <Box sx={{ flexGrow: 1 }}>
              <Typography sx={{ fontWeight: 600 }}>
                {connection.id} <Chip size="small" label={connection.type} />
              </Typography>
              <Typography variant="body2" color="text.secondary">
                {connection.models.join(", ")}
              </Typography>
            </Box>
            {results[connection.id] && (
              <Typography variant="body2" color={results[connection.id].startsWith("OK") ? "success.main" : "error.main"}>
                {results[connection.id].startsWith("OK") ? <CheckCircleIcon fontSize="inherit" /> : results[connection.id] === "testing…" ? null : <ErrorIcon fontSize="inherit" />}{" "}
                {results[connection.id]}
              </Typography>
            )}
            <Button variant="outlined" onClick={() => test(connection.id)}>
              Test
            </Button>
          </Stack>
        </Paper>
      ))}
      <Typography variant="body2" color="text.secondary">
        Allowed images: {(status.data?.allowed_images ?? []).join(", ")}
      </Typography>
    </Stack>
  );
}

function Schedules() {
  const schedules = useLoad(() => api.admin.schedules(), [], 15000);
  return (
    <Paper variant="outlined">
      <Table size="small">
        <TableHead>
          <TableRow>
            <TableCell>Schedule</TableCell>
            <TableCell>Next run</TableCell>
            <TableCell>Last run</TableCell>
          </TableRow>
        </TableHead>
        <TableBody>
          {(schedules.data ?? []).map((schedule) => (
            <TableRow key={schedule.id}>
              <TableCell sx={{ fontFamily: "monospace" }}>
                {schedule.id} {schedule.paused && <Chip size="small" label="paused" />}
              </TableCell>
              <TableCell>{schedule.next_run ? new Date(schedule.next_run).toLocaleString() : "—"}</TableCell>
              <TableCell>{relativeTime(schedule.last_run)}</TableCell>
            </TableRow>
          ))}
        </TableBody>
      </Table>
    </Paper>
  );
}

function Teams() {
  const teams = useLoad(() => api.teams(), []);
  return (
    <Paper variant="outlined">
      <Table size="small">
        <TableHead>
          <TableRow>
            <TableCell>Team</TableCell>
            <TableCell>Source</TableCell>
            <TableCell>Sync</TableCell>
            <TableCell>Definitions</TableCell>
          </TableRow>
        </TableHead>
        <TableBody>
          {(teams.data ?? []).map((team) => (
            <TableRow key={team.id}>
              <TableCell>{team.name}</TableCell>
              <TableCell sx={{ fontFamily: "monospace", fontSize: "0.8rem" }}>
                <Chip size="small" label={team.repository.type} sx={{ mr: 1 }} />
                {team.repository.url ?? team.repository.location}
              </TableCell>
              <TableCell>
                {team.status === "ok" ? <Chip size="small" color="success" label="ok" /> : <Chip size="small" color="error" label={team.error ?? "error"} />}{" "}
                {relativeTime(team.synced_at)}
              </TableCell>
              <TableCell>
                {team.counts.workflows} workflows · {team.counts.agents} agents · {team.counts.harnesses} harnesses
                {team.invalid > 0 && <Chip size="small" color="warning" label={`${team.invalid} invalid`} sx={{ ml: 1 }} />}
              </TableCell>
            </TableRow>
          ))}
        </TableBody>
      </Table>
    </Paper>
  );
}

const TABS = [
  ["Overview", Overview],
  ["Users & roles", Users],
  ["Usage & cost", Usage],
  ["Secrets", Secrets],
  ["LLM connections", Connections],
  ["Schedules", Schedules],
  ["Teams", Teams],
] as const;

export default function AdminPage() {
  const [tab, setTab] = useState(0);
  const Panel = TABS[tab][1];
  return (
    <Stack spacing={3}>
      <Typography variant="h4">Platform administration</Typography>
      <Tabs value={tab} onChange={(_, value) => setTab(value)} variant="scrollable">
        {TABS.map(([label]) => (
          <Tab key={label} label={label} />
        ))}
      </Tabs>
      <Panel />
    </Stack>
  );
}
