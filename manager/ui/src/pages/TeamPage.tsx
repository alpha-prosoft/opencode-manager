import { useState } from "react";
import { useParams } from "react-router";
import {
  Alert,
  Box,
  Chip,
  LinearProgress,
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
import { api, type Definition, type TeamDetail } from "../api";
import { RoleChip } from "../components/Chips";
import { Mono, Row } from "../components/Data";
import { PageHeader, PageStack, Section } from "../components/Page";
import RunsTable from "../components/RunsTable";
import UsageStats from "../components/UsageStats";
import WorkflowList, { DefinitionErrors } from "../components/WorkflowList";
import { relativeTime, tokens } from "../components/format";
import { useLoad } from "../hooks";
import { sourceLabel } from "./TeamsPage";

const PERMISSION = { allow: "allowed", ask: "asks first", deny: "blocked" } as Record<string, string>;

function HarnessCard({ harness }: { harness: Definition }) {
  const spec = harness.spec;
  const policy = [...(spec?.policy?.deny_commands ?? []), ...(spec?.policy?.deny_paths ?? [])];
  const permissions = Object.entries(spec?.permissions ?? {});
  return (
    <Paper sx={{ p: 2 }}>
      <Stack spacing={1.25}>
        <Row>
          <Typography variant="h3">{harness.id}</Typography>
          {spec && (
            <Chip
              variant="outlined"
              color={spec.network === "internet" ? "warning" : "success"}
              label={spec.network === "internet" ? "Internet access" : "No internet"}
            />
          )}
        </Row>
        {spec?.description && (
          <Typography variant="body2" color="text.secondary">
            {spec.description}
          </Typography>
        )}
        <DefinitionErrors definition={harness} />
        {spec && (
          <Box
            component="dl"
            sx={{ m: 0, display: "grid", gridTemplateColumns: "max-content 1fr", columnGap: 2, rowGap: 0.75, typography: "body2" }}
          >
            <Typography component="dt" variant="label">
              Models
            </Typography>
            <dd style={{ margin: 0 }}>{spec.llm.models.join(", then ")}</dd>
            <Typography component="dt" variant="label">
              Tools
            </Typography>
            <dd style={{ margin: 0 }}>
              {permissions.length
                ? permissions.map(([tool, value]) => `${tool} ${PERMISSION[value as string] ?? value}`).join(", ")
                : "OpenCode defaults"}
            </dd>
            <Typography component="dt" variant="label">
              Resources
            </Typography>
            <dd style={{ margin: 0 }}>
              {spec.resources.cpus} CPU, {spec.resources.memory_mb} MB memory, stops after {spec.idle_minutes} idle min
            </dd>
            {policy.length > 0 && (
              <>
                <Typography component="dt" variant="label">
                  Always blocked
                </Typography>
                <dd style={{ margin: 0 }}>
                  <Row>
                    {policy.map((rule: string) => (
                      <Chip key={rule} variant="outlined" color="warning" label={<Mono>{rule}</Mono>} />
                    ))}
                  </Row>
                </dd>
              </>
            )}
          </Box>
        )}
      </Stack>
    </Paper>
  );
}

function AgentCard({ agent }: { agent: Definition }) {
  return (
    <Paper sx={{ p: 2 }}>
      <Stack spacing={0.75}>
        <Typography variant="h3">{agent.id}</Typography>
        {agent.spec?.description && (
          <Typography variant="body2" color="text.secondary">
            {agent.spec.description}
          </Typography>
        )}
        <Typography variant="caption" color="text.secondary">
          Instructions in <Mono>{agent.spec?.prompt_file ?? agent.source}</Mono>
        </Typography>
        <DefinitionErrors definition={agent} />
      </Stack>
    </Paper>
  );
}

function Setup({ team }: { team: TeamDetail }) {
  const of = (kind: Definition["kind"]) => team.definitions.filter((d) => d.kind === kind);
  const grid = { display: "grid", gap: 2, gridTemplateColumns: "repeat(auto-fill, minmax(320px, 1fr))" };
  return (
    <Stack spacing={4}>
      <Section title="Agents" description="Who does the work: the instructions given to the model.">
        <Box sx={grid}>
          {of("agents").map((agent) => (
            <AgentCard key={agent.id} agent={agent} />
          ))}
        </Box>
      </Section>
      <Section
        title="Harnesses"
        description="Where the work happens: model, tools, network and limits of the sandbox. The agent cannot change these."
      >
        <Box sx={grid}>
          {of("harnesses").map((harness) => (
            <HarnessCard key={harness.id} harness={harness} />
          ))}
        </Box>
      </Section>
    </Stack>
  );
}

function People({ team }: { team: TeamDetail }) {
  const budget = team.budget;
  const used = budget.daily_tokens ? Math.min(100, (100 * budget.used_today) / budget.daily_tokens) : 0;
  const members = team.members.filter((user) => !team.admins.includes(user));
  return (
    <Stack spacing={4}>
      {budget.daily_tokens && (
        <Section title="Daily token budget" description="When the team reaches it, agents can't call the LLM until midnight UTC.">
          <Paper sx={{ p: 2 }}>
            <LinearProgress
              variant="determinate"
              value={used}
              color={used >= 100 ? "error" : used > 80 ? "warning" : "primary"}
              sx={{ height: 8, borderRadius: 4 }}
            />
            <Typography variant="body2" color="text.secondary" sx={{ mt: 1 }}>
              {tokens(budget.used_today)} of {tokens(budget.daily_tokens)} tokens used today
            </Typography>
          </Paper>
        </Section>
      )}
      <Section title="LLM usage" description="Last 7 days, measured by the platform's gateway.">
        <UsageStats usage={team.usage} />
        {team.usage_by_model.length > 0 && (
          <Paper>
            <Table>
              <TableHead>
                <TableRow>
                  <TableCell>Model</TableCell>
                  <TableCell align="right">Calls</TableCell>
                  <TableCell align="right">Prompt tokens</TableCell>
                  <TableCell align="right">Completion tokens</TableCell>
                </TableRow>
              </TableHead>
              <TableBody>
                {team.usage_by_model.map((row) => (
                  <TableRow key={row.model}>
                    <TableCell>{row.model}</TableCell>
                    <TableCell align="right">{row.calls}</TableCell>
                    <TableCell align="right">{tokens(row.prompt_tokens)}</TableCell>
                    <TableCell align="right">{tokens(row.completion_tokens)}</TableCell>
                  </TableRow>
                ))}
              </TableBody>
            </Table>
          </Paper>
        )}
      </Section>
      <Section
        title="Members"
        description="Team admins can also stop, delete and re-share any team session. Membership is managed in the onboarding repository."
      >
        <Row>
          {team.admins.map((user) => (
            <Chip key={user} color="primary" variant="outlined" label={`${user} · admin`} />
          ))}
          {members.map((user) => (
            <Chip key={user} variant="outlined" label={user} />
          ))}
        </Row>
        <Typography variant="body2" color="text.secondary">
          Secrets this team's workflows may use: {team.secrets.length ? team.secrets.join(", ") : "none"}
        </Typography>
      </Section>
    </Stack>
  );
}

export default function TeamPage() {
  const { teamId = "" } = useParams();
  const team = useLoad(() => api.team(teamId), [teamId]);
  const catalog = useLoad(() => api.marketplace(), []);
  const runs = useLoad(() => api.runs(teamId), [teamId], 10000);
  const [tab, setTab] = useState(0);

  if (team.error) return <Alert severity="error">{team.error}</Alert>;
  if (!team.data) return null;
  const t = team.data;
  const workflows = t.definitions.filter((d) => d.kind === "workflows").map((workflow) => ({ teamId: t.id, teamName: t.name, workflow }));

  return (
    <PageStack>
      <PageHeader
        back={{ to: "/teams", label: "Teams" }}
        title={t.name}
        description={t.description}
        actions={<RoleChip role={t.role} />}
        meta={
          <Typography variant="caption">
            {sourceLabel(t)} · version <Mono>{t.commit?.slice(0, 12) ?? "—"}</Mono> · synced {relativeTime(t.synced_at)}
          </Typography>
        }
      />
      {t.error && <Alert severity="error">Last sync failed: {t.error}</Alert>}
      <Box>
        <Tabs value={tab} onChange={(_, value) => setTab(value)} variant="scrollable">
          <Tab label="Workflows" />
          <Tab label="Runs" />
          <Tab label="Agents & harnesses" />
          <Tab label="People & usage" />
        </Tabs>
      </Box>
      {tab === 0 && <WorkflowList workflows={workflows} catalog={catalog.data ?? []} />}
      {tab === 1 && <RunsTable runs={runs.data} showTeam={false} empty="Nobody has run this team's workflows yet." />}
      {tab === 2 && <Setup team={t} />}
      {tab === 3 && <People team={t} />}
    </PageStack>
  );
}
