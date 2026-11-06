import { useState } from "react";
import { Link as RouterLink } from "react-router";
import { Alert, Box, Button, Link, Paper, Stack, Tooltip, Typography } from "@mui/material";
import PlayArrowIcon from "@mui/icons-material/PlayArrow";
import TerminalIcon from "@mui/icons-material/Terminal";
import type { Activity, Definition } from "../api";
import { activityMeta } from "./Chips";
import { EmptyState, IconTile, ListRow } from "./Data";
import RunDialog from "./RunDialog";
import { workflowTrigger } from "./format";

export interface TeamWorkflow {
  teamId: string;
  teamName: string;
  workflow: Definition;
}

interface Props {
  workflows: TeamWorkflow[];
  catalog: Activity[];
  showTeam?: boolean;
  showInvalid?: boolean;
}

export function DefinitionErrors({ definition }: { definition: Definition }) {
  if (definition.valid) return null;
  return (
    <Alert severity="warning">
      <strong>{definition.source}</strong> can't be used:
      <Box component="ul" sx={{ m: 0, pl: 2.5 }}>
        {definition.errors.map((error) => (
          <li key={error}>{error}</li>
        ))}
      </Box>
    </Alert>
  );
}

export default function WorkflowList({ workflows, catalog, showTeam = false, showInvalid = true }: Props) {
  const [selected, setSelected] = useState<TeamWorkflow | null>(null);
  const activity = (id?: string) => catalog.find((a) => a.id === id);
  const shown = workflows.filter((item) => showInvalid || item.workflow.valid);
  if (shown.length === 0) return <EmptyState title="No workflows">Teams add workflows as JSON files in their repository.</EmptyState>;

  const teams = [...new Map(shown.map((item) => [item.teamId, item.teamName])).entries()];
  const row = (item: TeamWorkflow) => {
    const { workflow } = item;
    const spec = workflow.spec;
    const meta = activityMeta(spec?.activity ?? "");
    const interactive = activity(spec?.activity)?.interactive;
    const automatic = spec?.trigger && spec.trigger.type !== "manual";
    return (
      <ListRow key={`${item.teamId}/${workflow.id}`}>
        <IconTile icon={meta.icon} />
        <Box sx={{ flexGrow: 1, minWidth: 0 }}>
          <Typography variant="h3">{spec?.name ?? workflow.id}</Typography>
          <Typography variant="body2" color="text.secondary">
            {spec?.description}
          </Typography>
          <Typography variant="caption" color="text.secondary">
            {[meta.label, spec && workflowTrigger(spec)].filter(Boolean).join(" · ")}
          </Typography>
          {!workflow.valid && (
            <Box sx={{ mt: 1 }}>
              <DefinitionErrors definition={workflow} />
            </Box>
          )}
        </Box>
        <Tooltip title={automatic ? "Also runs automatically. You can start it by hand too." : ""}>
          <span>
            <Button
              variant={interactive ? "contained" : "outlined"}
              startIcon={interactive ? <TerminalIcon /> : <PlayArrowIcon />}
              disabled={!workflow.valid}
              onClick={() => setSelected(item)}
              sx={{ flexShrink: 0 }}
            >
              {interactive ? "Start session" : "Run"}
            </Button>
          </span>
        </Tooltip>
      </ListRow>
    );
  };
  const dialog = (
    <RunDialog
      team={selected?.teamId ?? ""}
      workflow={selected?.workflow ?? null}
      activity={activity(selected?.workflow.spec?.activity)}
      onClose={() => setSelected(null)}
    />
  );
  if (!showTeam) {
    return (
      <Paper>
        {shown.map(row)}
        {dialog}
      </Paper>
    );
  }
  return (
    <Stack spacing={2}>
      {teams.map(([teamId, teamName]) => (
        <Box key={teamId}>
          <Link
            component={RouterLink}
            to={`/teams/${teamId}`}
            variant="subtitle2"
            color="text.secondary"
            sx={{ display: "block", mb: 0.75 }}
          >
            {teamName}
          </Link>
          <Paper>{shown.filter((item) => item.teamId === teamId).map(row)}</Paper>
        </Box>
      ))}
      {dialog}
    </Stack>
  );
}
