import { Link as RouterLink } from "react-router";
import { Alert, Box, Button, Card, CardActionArea, CardContent, Chip, Paper, Stack, Typography } from "@mui/material";
import GroupsIcon from "@mui/icons-material/Groups";
import { api } from "../api";
import { useIdentity } from "../auth";
import { ActivityChip, RunStatusChip, SessionStatusChip } from "../components/Chips";
import { relativeTime, triggerLabel } from "../components/format";
import { useLoad } from "../hooks";

export default function DashboardPage() {
  const identity = useIdentity();
  const teams = useLoad(() => api.teams(), []);
  const runs = useLoad(() => api.runs(), [], 10000);
  const sessions = useLoad(() => api.sessions(), [], 10000);
  const running = (sessions.data ?? []).filter((s) => s.status === "running" || s.status === "starting");

  return (
    <Stack spacing={4}>
      <Box>
        <Typography variant="h4">Welcome, {identity.name ?? identity.sub}</Typography>
        <Typography color="text.secondary">
          Run your team's agent workflows in sandboxed OpenCode sessions. Every LLM call and message is metered and audited.
        </Typography>
      </Box>

      {teams.data?.length === 0 && (
        <Alert severity="info">
          You are not a member of any team yet. Ask a platform admin to add you to a team in the onboarding repository.
        </Alert>
      )}

      <Box>
        <Typography variant="h6" gutterBottom>
          Your teams
        </Typography>
        <Box sx={{ display: "grid", gridTemplateColumns: "repeat(auto-fill, minmax(260px, 1fr))", gap: 2 }}>
          {(teams.data ?? []).map((team) => (
            <Card key={team.id} variant="outlined">
              <CardActionArea component={RouterLink} to={`/teams/${team.id}`} sx={{ height: "100%" }}>
                <CardContent>
                  <Stack direction="row" spacing={1} sx={{ alignItems: "center", mb: 1 }}>
                    <GroupsIcon color="primary" />
                    <Typography variant="h6">{team.name}</Typography>
                  </Stack>
                  <Typography variant="body2" color="text.secondary" sx={{ mb: 1.5 }}>
                    {team.description}
                  </Typography>
                  <Stack direction="row" spacing={1} useFlexGap sx={{ flexWrap: "wrap" }}>
                    <Chip size="small" label={`${team.counts.workflows} workflows`} />
                    <Chip size="small" label={team.role ?? "viewer"} variant="outlined" />
                    {team.invalid > 0 && <Chip size="small" color="warning" label={`${team.invalid} invalid`} />}
                  </Stack>
                </CardContent>
              </CardActionArea>
            </Card>
          ))}
        </Box>
      </Box>

      <Box sx={{ display: "grid", gridTemplateColumns: { xs: "1fr", md: "1fr 1fr" }, gap: 3 }}>
        <Paper variant="outlined" sx={{ p: 2 }}>
          <Stack direction="row" sx={{ justifyContent: "space-between", alignItems: "center", mb: 1 }}>
            <Typography variant="h6">Recent runs</Typography>
            <Button component={RouterLink} to="/runs" size="small">
              All runs
            </Button>
          </Stack>
          {(runs.data ?? []).slice(0, 6).map((run) => (
            <Stack
              key={run.id}
              direction="row"
              spacing={1}
              component={RouterLink}
              to={`/runs/${run.id}`}
              sx={{ alignItems: "center", py: 1, borderTop: 1, borderColor: "divider", color: "inherit", textDecoration: "none" }}
            >
              <ActivityChip activity={run.activity} />
              <Box sx={{ flexGrow: 1, minWidth: 0 }}>
                <Typography variant="body2" noWrap>
                  {run.team_id} / {run.workflow_id}
                </Typography>
                <Typography variant="caption" color="text.secondary">
                  {triggerLabel(run.trigger)} · {relativeTime(run.created_at)}
                </Typography>
              </Box>
              <RunStatusChip status={run.status} />
            </Stack>
          ))}
          {runs.data?.length === 0 && <Typography color="text.secondary">No runs yet. Start one from a team page.</Typography>}
        </Paper>

        <Paper variant="outlined" sx={{ p: 2 }}>
          <Stack direction="row" sx={{ justifyContent: "space-between", alignItems: "center", mb: 1 }}>
            <Typography variant="h6">Running sessions</Typography>
            <Button component={RouterLink} to="/sessions" size="small">
              All sessions
            </Button>
          </Stack>
          {running.map((session) => (
            <Stack
              key={session.id}
              direction="row"
              spacing={1}
              component={RouterLink}
              to={`/sessions/${session.id}`}
              sx={{ alignItems: "center", py: 1, borderTop: 1, borderColor: "divider", color: "inherit", textDecoration: "none" }}
            >
              {session.activity && <ActivityChip activity={session.activity} />}
              <Typography variant="body2" noWrap sx={{ flexGrow: 1 }}>
                {session.name}
              </Typography>
              <SessionStatusChip status={session.status} />
            </Stack>
          ))}
          {running.length === 0 && <Typography color="text.secondary">Nothing is running right now.</Typography>}
        </Paper>
      </Box>
    </Stack>
  );
}
