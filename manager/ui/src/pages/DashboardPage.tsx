import { useState } from "react";
import { Link as RouterLink } from "react-router";
import { Alert, Box, Button, IconButton, Paper, Stack, Tooltip, Typography } from "@mui/material";
import CloseIcon from "@mui/icons-material/Close";
import { api } from "../api";
import { useIdentity } from "../auth";
import { PageHeader, PageStack, Section } from "../components/Page";
import RunsTable from "../components/RunsTable";
import SessionsTable from "../components/SessionsTable";
import WorkflowList, { type TeamWorkflow } from "../components/WorkflowList";
import { isSessionLive } from "../components/format";
import { useLoad } from "../hooks";

const STEPS = [
  ["Pick a workflow", "Your team defines workflows in its repository: a code review, an implementation, an analysis, or a workspace."],
  [
    "The agent runs in a sandbox",
    "Each run gets its own locked-down OpenCode container with only the model, tools and network the team allows.",
  ],
  [
    "Get a result",
    "Review findings, a patch with test results, or a document. You can open the session to see exactly what the agent did.",
  ],
  ["Everything is recorded", "Every LLM call is metered and every message and tool call is kept in a tamper-evident audit trail."],
];

const INTRO_KEY = "intro-dismissed";

function HowItWorks() {
  const [hidden, setHidden] = useState(() => {
    try {
      return localStorage.getItem(INTRO_KEY) === "1";
    } catch {
      return false;
    }
  });
  if (hidden) return null;
  const dismiss = () => {
    setHidden(true);
    try {
      localStorage.setItem(INTRO_KEY, "1");
    } catch {
      /* storage unavailable */
    }
  };
  return (
    <Paper sx={{ p: 2.5, position: "relative" }}>
      <Tooltip title="Hide">
        <IconButton size="small" aria-label="Hide introduction" onClick={dismiss} sx={{ position: "absolute", top: 8, right: 8 }}>
          <CloseIcon fontSize="small" />
        </IconButton>
      </Tooltip>
      <Typography variant="h2" gutterBottom>
        How it works
      </Typography>
      <Box
        component="ol"
        sx={{
          display: "grid",
          gap: 2.5,
          gridTemplateColumns: { xs: "1fr", sm: "1fr 1fr", lg: "repeat(4, 1fr)" },
          m: 0,
          p: 0,
          listStyle: "none",
        }}
      >
        {STEPS.map(([title, body], index) => (
          <Stack component="li" key={title} direction="row" spacing={1.5}>
            <Typography variant="h3" color="primary" sx={{ minWidth: 16 }}>
              {index + 1}
            </Typography>
            <Box>
              <Typography variant="subtitle2">{title}</Typography>
              <Typography variant="body2" color="text.secondary">
                {body}
              </Typography>
            </Box>
          </Stack>
        ))}
      </Box>
    </Paper>
  );
}

export default function DashboardPage() {
  const identity = useIdentity();
  const [error, setError] = useState<string | null>(null);
  const catalog = useLoad(() => api.marketplace(), []);
  const workflows = useLoad(async () => {
    const teams = await api.teams();
    const details = await Promise.all(teams.map((team) => api.team(team.id)));
    return details.flatMap((team) =>
      team.definitions
        .filter((d) => d.kind === "workflows")
        .map((workflow): TeamWorkflow => ({ teamId: team.id, teamName: team.name, workflow })),
    );
  }, []);
  const runs = useLoad(() => api.runs(), [], 10000);
  const sessions = useLoad(() => api.sessions(), [], 10000);
  const live = (sessions.data ?? []).filter((s) => isSessionLive(s.status));
  const noTeams = workflows.data !== null && workflows.data.length === 0;

  return (
    <PageStack>
      <PageHeader
        title={`Hi ${identity.name ?? identity.sub}`}
        description="Run your team's agents in sandboxes, then review what they did."
      />
      <HowItWorks />
      {noTeams && (
        <Alert severity="info">
          You're not in a team yet, so there's nothing to run. A platform admin can add you to a team in the onboarding repository.
          Meanwhile you can open sessions others shared with everyone.
        </Alert>
      )}
      {error && <Alert severity="error">{error}</Alert>}
      {!noTeams && (
        <Section
          title="Start something"
          description="Workflows from all your teams. Workflows with problems are hidden; see the team page for details."
        >
          <WorkflowList workflows={workflows.data ?? []} catalog={catalog.data ?? []} showTeam showInvalid={false} />
        </Section>
      )}
      {live.length > 0 && (
        <Section title="Running now" description="Sandboxes that are up. Idle ones stop on their own and start again when opened.">
          <SessionsTable sessions={live} onChange={sessions.reload} onError={setError} />
        </Section>
      )}
      <Section
        title="Recent runs"
        action={
          <Button component={RouterLink} to="/runs" size="small">
            All runs
          </Button>
        }
      >
        <RunsTable runs={runs.data ? runs.data.slice(0, 5) : null} />
      </Section>
    </PageStack>
  );
}
