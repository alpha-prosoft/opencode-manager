import { Link as RouterLink } from "react-router";
import { Box, Chip, Link, Paper, Table, TableBody, TableCell, TableHead, TableRow, Typography } from "@mui/material";
import type { Run, RunOutcome } from "../api";
import { BuildChip } from "./BuildPanel";
import { ActivityChip, RunStatusChip } from "./Chips";
import { EmptyState, Row, WIDE_ONLY } from "./Data";
import { relativeTime, triggerLabel } from "./format";

interface Props {
  runs: Run[] | null;
  showTeam?: boolean;
  empty?: string;
}

const VERDICT: Record<string, [string, "success" | "warning" | "error"]> = {
  APPROVE: ["Approved", "success"],
  APPROVE_WITH_NITS: ["Approved with nits", "success"],
  REQUEST_CHANGES: ["Changes requested", "error"],
};

function Outcome({ outcome }: { outcome: RunOutcome }) {
  const pr = outcome.pull_request;
  const verdict = outcome.verdict ? VERDICT[outcome.verdict] : null;
  return (
    <Box sx={{ mt: 0.75 }}>
      <Row>
        {pr && (
          <Chip
            variant="outlined"
            component="a"
            href={pr.url}
            target="_blank"
            rel="noopener"
            clickable
            label={`${pr.updated ? "Pushed to" : pr.draft ? "Draft" : "PR"} #${pr.id}`}
          />
        )}
        {outcome.build && <BuildChip state={outcome.build.state} />}
        {verdict && <Chip variant="outlined" color={verdict[1]} label={verdict[0]} />}
      </Row>
    </Box>
  );
}

export default function RunsTable({ runs, showTeam = true, empty = "Runs appear here once you run a workflow." }: Props) {
  if (runs && runs.length === 0) return <EmptyState title="No runs yet">{empty}</EmptyState>;
  return (
    <Paper sx={{ overflowX: "auto" }}>
      <Table>
        <TableHead>
          <TableRow>
            <TableCell>Workflow</TableCell>
            <TableCell sx={WIDE_ONLY}>Activity</TableCell>
            {showTeam && <TableCell sx={WIDE_ONLY}>Team</TableCell>}
            <TableCell>Status</TableCell>
            <TableCell>Started</TableCell>
          </TableRow>
        </TableHead>
        <TableBody>
          {(runs ?? []).map((run) => (
            <TableRow key={run.id} hover>
              <TableCell>
                <Link component={RouterLink} to={`/runs/${run.id}`} sx={{ fontWeight: 500 }}>
                  {run.workflow_name ?? run.workflow_id}
                </Link>
                <Typography variant="body2" color="text.secondary">
                  {triggerLabel(run.trigger)}
                </Typography>
                {run.outcome && <Outcome outcome={run.outcome} />}
              </TableCell>
              <TableCell sx={WIDE_ONLY}>
                <ActivityChip activity={run.activity} />
              </TableCell>
              {showTeam && <TableCell sx={WIDE_ONLY}>{run.team_id}</TableCell>}
              <TableCell>
                <RunStatusChip status={run.status} />
              </TableCell>
              <TableCell sx={{ whiteSpace: "nowrap" }}>{relativeTime(run.created_at)}</TableCell>
            </TableRow>
          ))}
        </TableBody>
      </Table>
    </Paper>
  );
}
