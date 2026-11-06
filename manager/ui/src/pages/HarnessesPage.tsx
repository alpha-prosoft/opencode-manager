import { Link as RouterLink } from "react-router";
import { Alert, Box, Button, Chip, Paper, Stack, Typography } from "@mui/material";
import CheckCircleIcon from "@mui/icons-material/CheckCircle";
import { api, type HarnessCard } from "../api";
import { DefinitionErrorsList, EmptyState, Mono, Row } from "../components/Data";
import { PageHeader, PageStack } from "../components/Page";
import { useLoad } from "../hooks";

function credentialText(credential: HarnessCard["credentials"][number]): string {
  if (credential.source === "user") return `${credential.name}: your account`;
  if (credential.source === "platform") return `${credential.name}: shared account`;
  return `${credential.name}: needs your credential`;
}

function Harness({ harness }: { harness: HarnessCard }) {
  const missing = harness.credentials.filter((c) => !c.source);
  return (
    <Paper sx={{ p: 2.5 }}>
      <Stack spacing={1.5}>
        <Row>
          <Typography variant="h3">{harness.id}</Typography>
          <Typography variant="body2" color="text.secondary">
            {harness.team_name}
          </Typography>
          <Box sx={{ flexGrow: 1 }} />
          {harness.ready ? (
            <Chip variant="outlined" color="success" icon={<CheckCircleIcon />} label="Ready for you" />
          ) : (
            <Chip variant="outlined" color="warning" label={harness.valid ? "Needs setup" : "Has problems"} />
          )}
        </Row>
        {harness.description && <Typography color="text.secondary">{harness.description}</Typography>}
        <Row>
          <Chip
            variant="outlined"
            color={harness.network === "internet" ? "warning" : "success"}
            label={harness.network === "internet" ? "Internet access" : "No internet"}
          />
          {harness.models[0] && <Chip variant="outlined" label={harness.models[0]} />}
          {harness.skills.map((skill) => (
            <Chip key={skill} variant="outlined" label={`Skill: ${skill}`} />
          ))}
        </Row>
        {harness.mcp_servers.map((server) => (
          <Box key={server.id}>
            <Typography variant="subtitle2">{server.name}</Typography>
            <Typography variant="body2" color="text.secondary">
              {server.description}
            </Typography>
            {server.tools && (
              <Typography variant="caption" color="text.secondary">
                Tools: <Mono>{server.tools.join(", ")}</Mono>
              </Typography>
            )}
          </Box>
        ))}
        {harness.credentials.length > 0 && <Typography variant="body2">{harness.credentials.map(credentialText).join(" · ")}</Typography>}
        {missing.length > 0 && (
          <Alert
            severity="warning"
            action={
              <Button component={RouterLink} to="/accounts" color="inherit" size="small">
                Add
              </Button>
            }
          >
            Add your {missing.map((c) => c.name).join(" and ")} credential to use these tools.
          </Alert>
        )}
        <DefinitionErrorsList errors={harness.errors} />
        {harness.workflows.length > 0 && (
          <Typography variant="caption" color="text.secondary">
            Used by: {harness.workflows.map((w) => w.name).join(", ")}
          </Typography>
        )}
      </Stack>
    </Paper>
  );
}

export default function HarnessesPage() {
  const harnesses = useLoad(() => api.harnesses(), []);
  return (
    <PageStack>
      <PageHeader
        title="Harnesses"
        description="The kinds of agent sandboxes your teams offer: which model, tools, skills and network each one gets. Tools run behind the platform gateway, using your own credentials where you've added them."
      />
      {harnesses.data?.length === 0 && <EmptyState title="No harnesses">Your teams haven't defined any harnesses yet.</EmptyState>}
      <Box sx={{ display: "grid", gap: 2, gridTemplateColumns: { xs: "1fr", lg: "1fr 1fr" }, alignItems: "start" }}>
        {(harnesses.data ?? []).map((harness) => (
          <Harness key={`${harness.team_id}/${harness.id}`} harness={harness} />
        ))}
      </Box>
    </PageStack>
  );
}
