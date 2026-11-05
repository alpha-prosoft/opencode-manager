import { Link as RouterLink } from "react-router";
import { Alert, Link, Paper, Stack, Table, TableBody, TableCell, TableHead, TableRow, Typography } from "@mui/material";
import { api } from "../api";
import { ActivityChip, RunStatusChip } from "../components/Chips";
import { relativeTime, triggerLabel } from "../components/format";
import { useLoad } from "../hooks";

export default function RunsPage() {
  const runs = useLoad(() => api.runs(), [], 5000);
  return (
    <Stack spacing={3}>
      <Typography variant="h4">Runs</Typography>
      {runs.error && <Alert severity="error">{runs.error}</Alert>}
      <Paper variant="outlined">
        <Table>
          <TableHead>
            <TableRow>
              <TableCell>Run</TableCell>
              <TableCell>Activity</TableCell>
              <TableCell>Team / workflow</TableCell>
              <TableCell>Trigger</TableCell>
              <TableCell>Status</TableCell>
              <TableCell>Started</TableCell>
            </TableRow>
          </TableHead>
          <TableBody>
            {runs.data?.length === 0 && (
              <TableRow>
                <TableCell colSpan={6} align="center" sx={{ py: 6, color: "text.secondary" }}>
                  No runs yet.
                </TableCell>
              </TableRow>
            )}
            {(runs.data ?? []).map((run) => (
              <TableRow key={run.id} hover>
                <TableCell>
                  <Link component={RouterLink} to={`/runs/${run.id}`} sx={{ fontFamily: "monospace" }}>
                    {run.id}
                  </Link>
                </TableCell>
                <TableCell>
                  <ActivityChip activity={run.activity} />
                </TableCell>
                <TableCell>
                  {run.team_id} / {run.workflow_id}
                </TableCell>
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
    </Stack>
  );
}
