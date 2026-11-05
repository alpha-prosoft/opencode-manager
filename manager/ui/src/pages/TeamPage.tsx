import { useState } from "react";
import { Link as RouterLink, useParams } from "react-router";
import {
  Alert,
  Box,
  LinearProgress,
  Button,
  Card,
  CardActions,
  CardContent,
  Chip,
  Link,
  Paper,
  Stack,
  Tab,
  Table,
  TableBody,
  TableCell,
  TableHead,
  TableRow,
  Tabs,
  Typography,
} from "@mui/material";
import PlayArrowIcon from "@mui/icons-material/PlayArrow";
import { api, type Definition } from "../api";
import { ActivityChip, RunStatusChip } from "../components/Chips";
import RunDialog from "../components/RunDialog";
import UsageStats from "../components/UsageStats";
import { relativeTime, tokens, triggerLabel } from "../components/format";
import { useLoad } from "../hooks";

function Errors({ definition }: { definition: Definition }) {
  if (definition.valid) return null;
  return (
    <Alert severity="warning" sx={{ mt: 1 }}>
      <strong>{definition.source}</strong>
      <ul style={{ margin: 0, paddingLeft: 18 }}>
        {definition.errors.map((error) => (
          <li key={error}>{error}</li>
        ))}
      </ul>
    </Alert>
  );
}

function trigger(spec: Record<string, any>): string {
  const t = spec.trigger ?? { type: "manual" };
  if (t.type === "pull_request") return `pull requests in ${t.provider}:${t.repository} (every ${t.poll_minutes} min)`;
  if (t.type === "schedule") return `every ${t.every_minutes} min`;
  return "manual";
}

export default function TeamPage() {
  const { teamId = "" } = useParams();
  const team = useLoad(() => api.team(teamId), [teamId]);
  const catalog = useLoad(() => api.marketplace(), []);
  const runs = useLoad(() => api.runs(teamId), [teamId], 10000);
  const [tab, setTab] = useState(0);
  const [selected, setSelected] = useState<Definition | null>(null);

  if (team.error) return <Alert severity="error">{team.error}</Alert>;
  if (!team.data) return null;
  const t = team.data;
  const byKind = (kind: Definition["kind"]) => t.definitions.filter((d) => d.kind === kind);
  const activity = (id?: string) => catalog.data?.find((a) => a.id === id);

  return (
    <Stack spacing={3}>
      <Box>
        <Typography variant="h4">{t.name}</Typography>
        <Typography color="text.secondary">{t.description}</Typography>
        <Typography variant="body2" color="text.secondary" sx={{ fontFamily: "monospace", mt: 1 }}>
          {t.repository.url} · {t.repository.branch} · {t.commit?.slice(0, 8)} · synced {relativeTime(t.synced_at)}
        </Typography>
        {t.error && <Alert severity="error" sx={{ mt: 1 }}>{t.error}</Alert>}
      </Box>

      <Tabs value={tab} onChange={(_, value) => setTab(value)}>
        <Tab label={`Workflows (${t.counts.workflows})`} />
        <Tab label={`Agents (${t.counts.agents})`} />
        <Tab label={`Harnesses (${t.counts.harnesses})`} />
        <Tab label="Runs" />
        <Tab label="Usage & members" />
      </Tabs>

      {tab === 0 && (
        <Box sx={{ display: "grid", gridTemplateColumns: "repeat(auto-fill, minmax(320px, 1fr))", gap: 2 }}>
          {byKind("workflows").map((workflow) => (
            <Card key={workflow.id} variant="outlined" sx={{ display: "flex", flexDirection: "column" }}>
              <CardContent sx={{ flexGrow: 1 }}>
                <Stack direction="row" spacing={1} sx={{ alignItems: "center", mb: 1 }}>
                  {workflow.spec && <ActivityChip activity={workflow.spec.activity} />}
                  <Typography variant="h6">{workflow.spec?.name ?? workflow.id}</Typography>
                </Stack>
                <Typography variant="body2" color="text.secondary">
                  {workflow.spec?.description}
                </Typography>
                {workflow.spec && (
                  <Typography variant="caption" color="text.secondary" component="div" sx={{ mt: 1 }}>
                    agent <strong>{workflow.spec.agent}</strong> · harness <strong>{workflow.spec.harness}</strong> · trigger{" "}
                    {trigger(workflow.spec)}
                  </Typography>
                )}
                <Errors definition={workflow} />
              </CardContent>
              <CardActions>
                <Button
                  variant="contained"
                  size="small"
                  startIcon={<PlayArrowIcon />}
                  disabled={!workflow.valid}
                  onClick={() => setSelected(workflow)}
                >
                  {activity(workflow.spec?.activity)?.interactive ? "Launch" : "Run now"}
                </Button>
              </CardActions>
            </Card>
          ))}
        </Box>
      )}

      {tab === 1 &&
        byKind("agents").map((agent) => (
          <Paper key={agent.id} variant="outlined" sx={{ p: 2 }}>
            <Typography variant="h6">{agent.id}</Typography>
            <Typography color="text.secondary">{agent.spec?.description}</Typography>
            <Typography variant="caption" sx={{ fontFamily: "monospace" }}>
              {agent.source} {agent.spec?.prompt_file ? `· prompt ${agent.spec.prompt_file}` : ""}
              {agent.spec?.temperature !== undefined && agent.spec?.temperature !== null ? ` · temperature ${agent.spec.temperature}` : ""}
            </Typography>
            <Errors definition={agent} />
          </Paper>
        ))}

      {tab === 2 && (
        <Paper variant="outlined">
          <Table>
            <TableHead>
              <TableRow>
                <TableCell>Harness</TableCell>
                <TableCell>LLM</TableCell>
                <TableCell>Network</TableCell>
                <TableCell>Permissions</TableCell>
                <TableCell>Resources</TableCell>
              </TableRow>
            </TableHead>
            <TableBody>
              {byKind("harnesses").map((h) => (
                <TableRow key={h.id}>
                  <TableCell>
                    <strong>{h.id}</strong>
                    <Typography variant="body2" color="text.secondary">
                      {h.spec?.description}
                    </Typography>
                    <Errors definition={h} />
                  </TableCell>
                  <TableCell sx={{ fontFamily: "monospace", fontSize: "0.85rem" }}>
                    {h.spec?.llm.connection}: {h.spec?.llm.models.join(" → ")}
                  </TableCell>
                  <TableCell>
                    <Chip size="small" color={h.spec?.network === "internet" ? "warning" : "success"} label={h.spec?.network} />
                  </TableCell>
                  <TableCell sx={{ fontFamily: "monospace", fontSize: "0.85rem" }}>
                    {Object.entries(h.spec?.permissions ?? {}).map(([tool, value]) => `${tool}:${value}`).join(" ") || "defaults"}
                  </TableCell>
                  <TableCell>
                    {h.spec?.resources.cpus} CPU · {h.spec?.resources.memory_mb} MB · idle {h.spec?.idle_minutes} min
                  </TableCell>
                </TableRow>
              ))}
            </TableBody>
          </Table>
        </Paper>
      )}

      {tab === 3 && (
        <Paper variant="outlined">
          <Table size="small">
            <TableHead>
              <TableRow>
                <TableCell>Run</TableCell>
                <TableCell>Workflow</TableCell>
                <TableCell>Trigger</TableCell>
                <TableCell>Status</TableCell>
                <TableCell>Started</TableCell>
              </TableRow>
            </TableHead>
            <TableBody>
              {(runs.data ?? []).map((run) => (
                <TableRow key={run.id} hover>
                  <TableCell>
                    <Link component={RouterLink} to={`/runs/${run.id}`} sx={{ fontFamily: "monospace" }}>
                      {run.id}
                    </Link>
                  </TableCell>
                  <TableCell>{run.workflow_name ?? run.workflow_id}</TableCell>
                  <TableCell>{triggerLabel(run.trigger)}</TableCell>
                  <TableCell>
                    <RunStatusChip status={run.status} />
                  </TableCell>
                  <TableCell>{relativeTime(run.created_at)}</TableCell>
                </TableRow>
              ))}
            </TableBody>
          </Table>
        </Paper>
      )}

      {tab === 4 && (
        <Stack spacing={3}>
          <Box>
            {t.budget.daily_tokens && (
              <Box sx={{ mb: 3 }}>
                <Typography variant="h6" gutterBottom>
                  Daily token budget
                </Typography>
                <LinearProgress
                  variant="determinate"
                  value={Math.min(100, (100 * t.budget.used_today) / t.budget.daily_tokens)}
                  color={t.budget.used_today >= t.budget.daily_tokens ? "error" : "primary"}
                  sx={{ height: 10, borderRadius: 5, maxWidth: 480 }}
                />
                <Typography variant="body2" color="text.secondary" sx={{ mt: 0.5 }}>
                  {tokens(t.budget.used_today)} of {tokens(t.budget.daily_tokens)} tokens used today (UTC)
                </Typography>
              </Box>
            )}
            <Typography variant="h6" gutterBottom>
              LLM usage, last 7 days
            </Typography>
            <UsageStats usage={t.usage} />
            {t.usage_by_model.length > 0 && (
              <Table size="small" sx={{ mt: 2 }}>
                <TableHead>
                  <TableRow>
                    <TableCell>Model</TableCell>
                    <TableCell align="right">Calls</TableCell>
                    <TableCell align="right">Prompt tokens</TableCell>
                    <TableCell align="right">Completion tokens</TableCell>
                  </TableRow>
                </TableHead>
                <TableBody>
                  {t.usage_by_model.map((row) => (
                    <TableRow key={row.model}>
                      <TableCell>{row.model}</TableCell>
                      <TableCell align="right">{row.calls}</TableCell>
                      <TableCell align="right">{tokens(row.prompt_tokens)}</TableCell>
                      <TableCell align="right">{tokens(row.completion_tokens)}</TableCell>
                    </TableRow>
                  ))}
                </TableBody>
              </Table>
            )}
          </Box>
          <Box>
            <Typography variant="h6" gutterBottom>
              Members
            </Typography>
            <Stack direction="row" spacing={1} useFlexGap sx={{ flexWrap: "wrap" }}>
              {t.admins.map((user) => (
                <Chip key={`a-${user}`} label={`${user} (admin)`} color="primary" />
              ))}
              {t.members.filter((user) => !t.admins.includes(user)).map((user) => (
                <Chip key={user} label={user} />
              ))}
            </Stack>
            <Typography variant="body2" color="text.secondary" sx={{ mt: 2 }}>
              Platform secrets granted to this team: {t.secrets.length ? t.secrets.join(", ") : "none"}
            </Typography>
          </Box>
        </Stack>
      )}

      <RunDialog team={teamId} workflow={selected} activity={activity(selected?.spec?.activity)} onClose={() => setSelected(null)} />
    </Stack>
  );
}
