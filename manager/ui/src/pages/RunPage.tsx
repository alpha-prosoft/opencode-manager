import { useState } from "react";
import { Link as RouterLink, useNavigate, useParams } from "react-router";
import {
  Alert,
  Box,
  Button,
  LinearProgress,
  List,
  ListItem,
  ListItemText,
  Paper,
  Stack,
  Table,
  TableBody,
  TableCell,
  TableRow,
  Typography,
} from "@mui/material";
import OpenInNewIcon from "@mui/icons-material/OpenInNew";
import ReplayIcon from "@mui/icons-material/Replay";
import StopIcon from "@mui/icons-material/Stop";
import { api, type RunDetail } from "../api";
import { ActivityChip, RunStatusChip, SessionStatusChip } from "../components/Chips";
import Markdown from "../components/Markdown";
import PatchView from "../components/PatchView";
import ReviewResult from "../components/ReviewResult";
import UsageStats from "../components/UsageStats";
import { relativeTime, triggerLabel } from "../components/format";
import { useLoad } from "../hooks";

const FINISHED = new Set(["completed", "failed", "cancelled"]);

function Result({ run }: { run: RunDetail }) {
  const result = run.result;
  if (!result) return null;
  if (run.activity === "review") return <ReviewResult result={result} />;
  if (run.activity === "analysis") return <Markdown>{result.document.markdown}</Markdown>;
  if (run.activity === "implement") {
    const impl = result.implementation;
    return (
      <Stack spacing={2}>
        <Markdown>{impl.summary}</Markdown>
        {impl.changes.length > 0 && (
          <Box>
            <Typography variant="subtitle2">Changes</Typography>
            <List dense>
              {impl.changes.map((change: string) => (
                <ListItem key={change} disableGutters>
                  <ListItemText primary={`• ${change}`} />
                </ListItem>
              ))}
            </List>
          </Box>
        )}
        {impl.tests.length > 0 && (
          <Alert severity="info">
            <strong>Verification:</strong> {impl.tests.join("; ")}
          </Alert>
        )}
        {impl.notes && <Alert severity="warning">{impl.notes}</Alert>}
        <Typography variant="subtitle2">Patch ({result.files.length} files)</Typography>
        <PatchView patch={result.patch} name={`run-${run.id}.patch`} />
      </Stack>
    );
  }
  return null;
}

export default function RunPage() {
  const { runId = "" } = useParams();
  const navigate = useNavigate();
  const { data: run, error, reload } = useLoad(() => api.run(runId), [runId], 4000);
  const [actionError, setActionError] = useState<string | null>(null);
  if (error) return <Alert severity="error">{error}</Alert>;
  if (!run) return null;
  const busy = !FINISHED.has(run.status);
  const act = (action: () => Promise<unknown>) => action().catch((e: Error) => setActionError(e.message));

  return (
    <Stack spacing={3}>
      <Stack direction="row" spacing={2} sx={{ alignItems: "center", flexWrap: "wrap" }} useFlexGap>
        <ActivityChip activity={run.activity} />
        <Typography variant="h4" sx={{ flexGrow: 1 }}>
          {run.workflow_name ?? run.workflow_id}
        </Typography>
        <RunStatusChip status={run.status} />
        {busy && (
          <Button color="warning" startIcon={<StopIcon />} onClick={() => act(() => api.cancelRun(run.id).then(reload))}>
            Cancel
          </Button>
        )}
        {!busy && run.activity !== "session" && (
          <Button startIcon={<ReplayIcon />} onClick={() => act(() => api.retryRun(run.id).then((next) => navigate(`/runs/${next.id}`)))}>
            Run again
          </Button>
        )}
        {run.session && (
          <Button variant="outlined" startIcon={<OpenInNewIcon />} href={run.session.open_url} target="_blank" rel="noopener">
            Open session
          </Button>
        )}
      </Stack>
      {busy && (
        <Box>
          <LinearProgress />
          <Typography variant="body2" color="text.secondary" sx={{ mt: 1 }}>
            {run.status === "awaiting_result"
              ? "The agent is working in its sandbox. This page refreshes automatically."
              : "Preparing the sandbox…"}
          </Typography>
        </Box>
      )}
      {run.error && <Alert severity={run.status === "cancelled" ? "warning" : "error"}>{run.error}</Alert>}
      {actionError && <Alert severity="error">{actionError}</Alert>}

      <Paper variant="outlined" sx={{ p: 2 }}>
        <Table size="small">
          <TableBody>
            <TableRow>
              <TableCell sx={{ width: 160, color: "text.secondary" }}>Team</TableCell>
              <TableCell>
                <RouterLink to={`/teams/${run.team_id}`}>{run.team_id}</RouterLink>
              </TableCell>
            </TableRow>
            <TableRow>
              <TableCell sx={{ color: "text.secondary" }}>Trigger</TableCell>
              <TableCell>{triggerLabel(run.trigger)}</TableCell>
            </TableRow>
            <TableRow>
              <TableCell sx={{ color: "text.secondary" }}>Started</TableCell>
              <TableCell>
                {new Date(run.created_at).toLocaleString()} ({relativeTime(run.created_at)})
              </TableCell>
            </TableRow>
            {run.session && (
              <TableRow>
                <TableCell sx={{ color: "text.secondary" }}>Session</TableCell>
                <TableCell>
                  <RouterLink to={`/sessions/${run.session.id}`}>{run.session.name}</RouterLink>{" "}
                  <SessionStatusChip status={run.session.status} />
                </TableCell>
              </TableRow>
            )}
            {Object.entries(run.inputs).filter(([, value]) => value !== null && value !== "").map(([key, value]) => (
              <TableRow key={key}>
                <TableCell sx={{ color: "text.secondary" }}>{key}</TableCell>
                <TableCell sx={{ whiteSpace: "pre-wrap" }}>{String(value ?? "")}</TableCell>
              </TableRow>
            ))}
          </TableBody>
        </Table>
      </Paper>

      <Box>
        <Typography variant="h6" gutterBottom>
          Usage
        </Typography>
        <UsageStats usage={run.usage} />
      </Box>

      {run.result && (
        <Box>
          <Typography variant="h6" gutterBottom>
            Result
          </Typography>
          <Paper variant="outlined" sx={{ p: 3 }}>
            <Result run={run} />
          </Paper>
        </Box>
      )}
    </Stack>
  );
}
