import { Link as RouterLink } from "react-router";
import { Alert, Card, CardActionArea, CardContent, Box, Chip, Stack, Typography } from "@mui/material";
import { api, type TeamSummary } from "../api";
import { RoleChip } from "../components/Chips";
import { EmptyState, Row } from "../components/Data";
import { PageHeader, PageStack } from "../components/Page";
import { relativeTime } from "../components/format";
import { useLoad } from "../hooks";

export function sourceLabel(team: TeamSummary): string {
  const repo = team.repository;
  const kind = { git: "Git", url: "Archive", path: "Local folder" }[repo.type];
  return `${kind}: ${repo.url ?? repo.location}${repo.branch ? ` (${repo.branch})` : ""}`;
}

export default function TeamsPage() {
  const teams = useLoad(() => api.teams(), []);
  return (
    <PageStack>
      <PageHeader
        title="Teams"
        description="Each team keeps its agents, harnesses and workflows as JSON in its own repository. The platform syncs them every few minutes."
      />
      {teams.error && <Alert severity="error">{teams.error}</Alert>}
      {teams.data?.length === 0 && (
        <EmptyState title="You're not in any team">A platform admin adds teams and members in the onboarding repository.</EmptyState>
      )}
      <Box sx={{ display: "grid", gap: 2, gridTemplateColumns: "repeat(auto-fill, minmax(300px, 1fr))" }}>
        {(teams.data ?? []).map((team) => (
          <Card key={team.id}>
            <CardActionArea component={RouterLink} to={`/teams/${team.id}`} sx={{ height: "100%" }}>
              <CardContent component={Stack} spacing={1}>
                <Stack direction="row" spacing={1} sx={{ alignItems: "center" }}>
                  <Typography variant="h3" sx={{ flexGrow: 1 }}>
                    {team.name}
                  </Typography>
                  <RoleChip role={team.role} />
                </Stack>
                <Typography variant="body2" color="text.secondary">
                  {team.description}
                </Typography>
                <Row>
                  <Chip label={`${team.counts.workflows} workflows`} />
                  {team.invalid > 0 && <Chip color="warning" variant="outlined" label={`${team.invalid} with problems`} />}
                  {team.status === "error" && <Chip color="error" variant="outlined" label="Sync failed" />}
                </Row>
                <Typography variant="caption" color="text.secondary" noWrap>
                  {sourceLabel(team)} · synced {relativeTime(team.synced_at)}
                </Typography>
              </CardContent>
            </CardActionArea>
          </Card>
        ))}
      </Box>
    </PageStack>
  );
}
