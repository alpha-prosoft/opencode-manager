import { useState } from "react";
import { Link as RouterLink } from "react-router";
import {
  Alert,
  Box,
  Button,
  Chip,
  Dialog,
  DialogContent,
  DialogTitle,
  Link,
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
import SyncIcon from "@mui/icons-material/Sync";
import { api, type AdminCredential, type ProvidedBy } from "../api";
import { useConfirm } from "../components/ConfirmDialog";
import CredentialForm from "../components/CredentialForm";
import { RUN_STATUS } from "../components/Chips";
import { EmptyState, ListRow, Mono, Row, Stat, StatGrid } from "../components/Data";
import { PageHeader, PageStack, Section } from "../components/Page";
import { dateTime, money, relativeTime, tokens } from "../components/format";
import { useLoad } from "../hooks";
import { sourceLabel } from "./TeamsPage";

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
  const runsToday = Object.values(o.runs_today).reduce((a, b) => a + b, 0);
  return (
    <Stack spacing={3}>
      <Row>
        <Button
          variant="contained"
          startIcon={<SyncIcon />}
          disabled={busy !== null}
          onClick={() =>
            run("sync", async () => {
              const result = await api.sync();
              if (result.error) throw new Error(`Sync failed: ${result.error}`);
              return "Synced the onboarding repository and all team repositories.";
            })
          }
        >
          {busy === "sync" ? "Syncing…" : "Sync repositories now"}
        </Button>
        <Button
          variant="outlined"
          disabled={busy !== null}
          onClick={() => run("idle", async () => `Stopped ${(await api.admin.stopIdle()).stopped.length} idle session(s).`)}
        >
          Stop idle sessions
        </Button>
      </Row>
      {report && <Alert severity={report.ok ? "success" : "error"}>{report.text}</Alert>}
      {o.status.error && <Alert severity="error">The last onboarding sync failed: {o.status.error}</Alert>}
      <StatGrid>
        <Stat label="Teams" value={o.teams} hint={`${o.users} people`} />
        <Stat label="Running sessions" value={o.sessions.running} hint={`${o.sessions.total} in total`} />
        <Stat
          label="Runs today"
          value={runsToday}
          hint={
            Object.entries(o.runs_today)
              .map(([status, count]) => `${count} ${RUN_STATUS[status as keyof typeof RUN_STATUS]?.[0].toLowerCase() ?? status}`)
              .join(", ") || "none yet"
          }
        />
        <Stat label="Tokens today" value={tokens(o.tokens_today)} />
        <Stat label="Estimated cost today" value={money(o.cost_today)} hint="from model prices in platform.json" />
      </StatGrid>
      <Typography variant="body2" color="text.secondary">
        Onboarding repository version <Mono>{o.status.commit?.slice(0, 12) ?? "—"}</Mono>, synced {relativeTime(o.status.synced_at)}.
      </Typography>
    </Stack>
  );
}

function Users() {
  const users = useLoad(() => api.admin.users(), []);
  const teams = [...new Set((users.data ?? []).flatMap((u) => Object.keys(u.teams)))].sort();
  return (
    <Section title="People and roles" description="Edit admins, team admins and members in platform.json in the onboarding repository.">
      <Paper sx={{ overflowX: "auto" }}>
        <Table>
          <TableHead>
            <TableRow>
              <TableCell>Person</TableCell>
              <TableCell>Platform</TableCell>
              {teams.map((team) => (
                <TableCell key={team}>{team}</TableCell>
              ))}
              <TableCell align="right">Runs, 30 days</TableCell>
              <TableCell align="right">Tokens, 30 days</TableCell>
            </TableRow>
          </TableHead>
          <TableBody>
            {(users.data ?? []).map((user) => (
              <TableRow key={user.username}>
                <TableCell sx={{ fontWeight: 600 }}>{user.username}</TableCell>
                <TableCell>{user.platform_admin && <Chip color="primary" label="Admin" />}</TableCell>
                {teams.map((team) => (
                  <TableCell key={team}>
                    {user.teams[team] && (
                      <Chip
                        variant="outlined"
                        color={user.teams[team] === "admin" ? "primary" : "default"}
                        label={user.teams[team] === "admin" ? "Admin" : "Member"}
                      />
                    )}
                  </TableCell>
                ))}
                <TableCell align="right">{user.runs_30d}</TableCell>
                <TableCell align="right">{tokens(user.tokens_30d)}</TableCell>
              </TableRow>
            ))}
          </TableBody>
        </Table>
      </Paper>
    </Section>
  );
}

function Usage() {
  const [days, setDays] = useState(7);
  const usage = useLoad(() => api.admin.usage(days), [days]);
  const rows = usage.data ?? [];
  const priced = rows.some((r) => r.cost !== null);
  const total = rows.reduce((sum, r) => sum + (r.cost ?? 0), 0);
  return (
    <Section
      title="Usage and cost"
      description={
        priced
          ? `Estimated cost: ${money(total)}. Models without a price in platform.json show no cost.`
          : "Add model prices to llm_connections in platform.json to see cost estimates."
      }
      action={
        <TextField select size="small" value={days} onChange={(e) => setDays(Number(e.target.value))}>
          {[1, 7, 30, 90].map((d) => (
            <MenuItem key={d} value={d}>
              Last {d === 1 ? "24 hours" : `${d} days`}
            </MenuItem>
          ))}
        </TextField>
      }
    >
      <Paper sx={{ overflowX: "auto" }}>
        <Table>
          <TableHead>
            <TableRow>
              <TableCell>Team</TableCell>
              <TableCell>Model</TableCell>
              <TableCell align="right">Calls</TableCell>
              <TableCell align="right">Prompt</TableCell>
              <TableCell align="right">From cache</TableCell>
              <TableCell align="right">Completion</TableCell>
              <TableCell align="right">Failed</TableCell>
              <TableCell align="right">Cost</TableCell>
            </TableRow>
          </TableHead>
          <TableBody>
            {rows.length === 0 && (
              <TableRow>
                <TableCell colSpan={8} sx={{ color: "text.secondary" }}>
                  No LLM calls in this period.
                </TableCell>
              </TableRow>
            )}
            {rows.map((row) => (
              <TableRow key={`${row.team_id}-${row.connection}-${row.model}`}>
                <TableCell>{row.team_id || "—"}</TableCell>
                <TableCell>{row.model}</TableCell>
                <TableCell align="right">{row.calls}</TableCell>
                <TableCell align="right">{tokens(row.prompt_tokens)}</TableCell>
                <TableCell align="right">{tokens(row.cached_tokens)}</TableCell>
                <TableCell align="right">{tokens(row.completion_tokens)}</TableCell>
                <TableCell align="right">{row.errors}</TableCell>
                <TableCell align="right">{money(row.cost)}</TableCell>
              </TableRow>
            ))}
          </TableBody>
        </Table>
      </Paper>
    </Section>
  );
}

const PROVIDED_BY: Record<ProvidedBy, string> = {
  platform: "Admins only",
  platform_or_user: "Admins, people may use their own",
  user: "Each person",
};

function Credentials() {
  const credentials = useLoad(() => api.admin.credentials(), []);
  const [editing, setEditing] = useState<AdminCredential | null>(null);
  const [error, setError] = useState<string | null>(null);
  const { confirm, dialog } = useConfirm();
  const clear = (credential: AdminCredential) =>
    confirm({
      title: `Clear the platform value of ${credential.name}?`,
      body: "Anything using the shared value stops working until it is set again. Values from secret files stay in place.",
      confirm: "Clear",
      action: () =>
        api.admin
          .deleteCredential(credential.id)
          .then(credentials.reload)
          .catch((e: Error) => setError(e.message)),
    });
  return (
    <Section
      title="Credentials"
      description="Declared in platform.json; values are set here and stored encrypted. Values are never shown. Secret files under /config/secrets still work as platform values."
    >
      {error && <Alert severity="error">{error}</Alert>}
      <Paper sx={{ overflowX: "auto" }}>
        <Table>
          <TableHead>
            <TableRow>
              <TableCell>Credential</TableCell>
              <TableCell>Provided by</TableCell>
              <TableCell>Platform value</TableCell>
              <TableCell align="right">Personal values</TableCell>
              <TableCell>Used by</TableCell>
              <TableCell />
            </TableRow>
          </TableHead>
          <TableBody>
            {(credentials.data ?? []).map((credential) => (
              <TableRow key={credential.id}>
                <TableCell>
                  <Typography sx={{ fontWeight: 600 }}>{credential.name}</Typography>
                  <Mono>{credential.id}</Mono>
                  {credential.hosts.length > 0 && (
                    <Typography variant="body2" color="text.secondary">
                      Sent only to <Mono>{credential.hosts.join(", ")}</Mono>
                    </Typography>
                  )}
                </TableCell>
                <TableCell>{PROVIDED_BY[credential.provided_by]}</TableCell>
                <TableCell>
                  {credential.provided_by === "user" ? (
                    "—"
                  ) : credential.platform_value === "admin" ? (
                    <Chip variant="outlined" color="success" label={`Set · ${credential.hint}`} />
                  ) : credential.platform_value === "file" ? (
                    <Chip variant="outlined" color="success" label="From secret file" />
                  ) : (
                    <Chip variant="outlined" color="error" label="Missing" />
                  )}
                </TableCell>
                <TableCell align="right">{credential.provided_by === "platform" ? "—" : credential.personal_values}</TableCell>
                <TableCell>{credential.used_by.join(", ") || "—"}</TableCell>
                <TableCell align="right" sx={{ whiteSpace: "nowrap" }}>
                  {credential.provided_by !== "user" && (
                    <Button size="small" onClick={() => setEditing(credential)}>
                      {credential.platform_value ? "Replace" : "Set"}
                    </Button>
                  )}
                  {credential.platform_value === "admin" && (
                    <Button size="small" color="error" onClick={() => clear(credential)}>
                      Clear
                    </Button>
                  )}
                </TableCell>
              </TableRow>
            ))}
          </TableBody>
        </Table>
      </Paper>
      <Dialog open={editing !== null} onClose={() => setEditing(null)} fullWidth maxWidth="sm">
        {editing && (
          <>
            <DialogTitle>Platform value of {editing.name}</DialogTitle>
            <DialogContent>
              <CredentialForm
                name={editing.name}
                kind={editing.kind}
                help={
                  editing.provided_by === "platform_or_user"
                    ? "Used by everyone who hasn't added their own, and by automated runs."
                    : "Used by everyone and by automated runs."
                }
                save={(value) => api.admin.setCredential(editing.id, value)}
                onSaved={() => {
                  setEditing(null);
                  credentials.reload();
                }}
                extraActions={<Button onClick={() => setEditing(null)}>Cancel</Button>}
              />
            </DialogContent>
          </>
        )}
      </Dialog>
      {dialog}
    </Section>
  );
}

function McpServers() {
  const servers = useLoad(() => api.admin.mcpServers(), []);
  return (
    <Section
      title="MCP servers"
      description="Declared in platform.json. Sandboxes reach them only through the platform gateway, which adds credentials, enforces the tool list and records every call in the audit trail."
    >
      {servers.data?.length === 0 && (
        <EmptyState title="No MCP servers">Add mcp_servers to platform.json in the onboarding repository.</EmptyState>
      )}
      {(servers.data?.length ?? 0) > 0 && (
        <Paper>
          {(servers.data ?? []).map((server) => (
            <ListRow key={server.id}>
              <Box sx={{ flexGrow: 1 }}>
                <Row>
                  <Typography variant="h3">{server.name}</Typography>
                  <Chip
                    variant="outlined"
                    color={server.reachable ? "success" : "error"}
                    label={server.reachable ? "Reachable" : `Unreachable (${server.detail})`}
                  />
                  {!server.credential_ready && <Chip variant="outlined" color="error" label={`${server.credential} missing`} />}
                </Row>
                <Typography variant="body2" color="text.secondary">
                  {server.description}
                </Typography>
                <Typography variant="body2" color="text.secondary">
                  <Mono>{server.url}</Mono>
                  {server.credential && (
                    <>
                      {" "}
                      · credential <Mono>{server.credential}</Mono>
                    </>
                  )}
                </Typography>
                <Typography variant="caption" color="text.secondary">
                  Tools: {server.tools ? server.tools.join(", ") : "all"} · used by {server.harnesses.join(", ") || "no harness yet"}
                </Typography>
              </Box>
            </ListRow>
          ))}
        </Paper>
      )}
    </Section>
  );
}

type TestResult = { state: "testing" } | { state: "ok" | "failed"; text: string };

function Connections() {
  const status = useLoad(() => api.platform(), []);
  const [results, setResults] = useState<Record<string, TestResult>>({});
  const test = async (id: string) => {
    setResults((r) => ({ ...r, [id]: { state: "testing" } }));
    let next: TestResult;
    try {
      const result = await api.admin.testConnection(id);
      next = result.ok ? { state: "ok", text: `Answered in ${result.latency_ms} ms` } : { state: "failed", text: result.error ?? "Failed" };
    } catch (e) {
      next = { state: "failed", text: (e as Error).message };
    }
    setResults((r) => ({ ...r, [id]: next }));
  };
  return (
    <Section
      title="LLM connections"
      description="Sends a tiny prompt to the first model of a connection to check its address and credentials."
    >
      <Paper>
        {(status.data?.llm_connections ?? []).map((connection) => {
          const result = results[connection.id];
          return (
            <ListRow key={connection.id}>
              <Box sx={{ flexGrow: 1 }}>
                <Typography variant="h3">{connection.id}</Typography>
                <Typography variant="body2" color="text.secondary">
                  {connection.type}: {connection.models.join(", ")}
                </Typography>
                {result && result.state !== "testing" && (
                  <Typography variant="body2" color={result.state === "ok" ? "success.main" : "error.main"}>
                    {result.text}
                  </Typography>
                )}
              </Box>
              <Button variant="outlined" disabled={result?.state === "testing"} onClick={() => test(connection.id)}>
                {result?.state === "testing" ? "Testing…" : "Test connection"}
              </Button>
            </ListRow>
          );
        })}
      </Paper>
      <Typography variant="body2" color="text.secondary">
        Sandbox images teams may use: <Mono>{(status.data?.allowed_images ?? []).join(", ")}</Mono>
      </Typography>
    </Section>
  );
}

const JOB_NAMES: Record<string, string> = {
  "platform-sync": "Sync repositories",
  "reap-sessions": "Stop idle sessions",
};

function jobName(id: string) {
  if (JOB_NAMES[id]) return JOB_NAMES[id];
  if (id.startsWith("trigger-")) return `Check for new pull requests (${id.slice(8)})`;
  return id;
}

function Schedules() {
  const schedules = useLoad(() => api.admin.schedules(), [], 15000);
  return (
    <Section title="Background jobs" description="Recurring jobs the platform runs on Temporal.">
      <Paper sx={{ overflowX: "auto" }}>
        <Table>
          <TableHead>
            <TableRow>
              <TableCell>Job</TableCell>
              <TableCell>Last run</TableCell>
              <TableCell>Next run</TableCell>
            </TableRow>
          </TableHead>
          <TableBody>
            {(schedules.data ?? []).map((schedule) => (
              <TableRow key={schedule.id}>
                <TableCell>
                  {jobName(schedule.id)} {schedule.paused && <Chip variant="outlined" label="Paused" />}
                </TableCell>
                <TableCell>{relativeTime(schedule.last_run)}</TableCell>
                <TableCell>{dateTime(schedule.next_run)}</TableCell>
              </TableRow>
            ))}
          </TableBody>
        </Table>
      </Paper>
    </Section>
  );
}

function Teams() {
  const teams = useLoad(() => api.teams(), []);
  return (
    <Section
      title="Team repositories"
      description="Sync state of every team. Definitions with problems can't run until they are fixed in the repository."
    >
      <Paper sx={{ overflowX: "auto" }}>
        <Table>
          <TableHead>
            <TableRow>
              <TableCell>Team</TableCell>
              <TableCell>Source</TableCell>
              <TableCell>Last sync</TableCell>
              <TableCell>Definitions</TableCell>
            </TableRow>
          </TableHead>
          <TableBody>
            {(teams.data ?? []).map((team) => (
              <TableRow key={team.id}>
                <TableCell>
                  <Link component={RouterLink} to={`/teams/${team.id}`}>
                    {team.name}
                  </Link>
                </TableCell>
                <TableCell>{sourceLabel(team)}</TableCell>
                <TableCell>
                  {team.status === "ok" ? (
                    relativeTime(team.synced_at)
                  ) : (
                    <Chip variant="outlined" color="error" label={team.error ?? "Failed"} />
                  )}
                </TableCell>
                <TableCell>
                  {team.counts.workflows} workflows, {team.counts.agents} agents, {team.counts.harnesses} harnesses
                  {team.invalid > 0 && <Chip variant="outlined" color="warning" label={`${team.invalid} with problems`} sx={{ ml: 1 }} />}
                </TableCell>
              </TableRow>
            ))}
          </TableBody>
        </Table>
      </Paper>
    </Section>
  );
}

const TABS = [
  ["Overview", Overview],
  ["People", Users],
  ["Usage & cost", Usage],
  ["Teams", Teams],
  ["LLM connections", Connections],
  ["Credentials", Credentials],
  ["MCP servers", McpServers],
  ["Background jobs", Schedules],
] as const;

export default function AdminPage() {
  const [tab, setTab] = useState(0);
  const Panel = TABS[tab][1];
  return (
    <PageStack>
      <PageHeader
        title="Admin"
        description="Platform-wide health, people, spend and configuration. Configuration itself lives in the onboarding repository; this page shows its effect."
      />
      <Box>
        <Tabs value={tab} onChange={(_, value) => setTab(value)} variant="scrollable">
          {TABS.map(([label]) => (
            <Tab key={label} label={label} />
          ))}
        </Tabs>
      </Box>
      <Panel />
    </PageStack>
  );
}
