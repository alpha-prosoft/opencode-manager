import { useState } from "react";
import { Link as RouterLink } from "react-router";
import {
  Alert,
  Box,
  Link,
  Paper,
  Stack,
  Table,
  TableBody,
  TableCell,
  TableHead,
  TableRow,
  ToggleButton,
  ToggleButtonGroup,
  Typography,
} from "@mui/material";
import { api, type Session } from "../api";
import { ActivityChip, SessionStatusChip, VisibilityChip } from "../components/Chips";
import SessionActions from "../components/SessionActions";
import { relativeTime } from "../components/format";
import { useLoad } from "../hooks";

type Filter = "all" | "mine" | "running";

const FILTERS: Record<Filter, (session: Session) => boolean> = {
  all: () => true,
  mine: (session) => session.is_owner,
  running: (session) => session.status === "running" || session.status === "starting",
};

export default function SessionsPage() {
  const sessions = useLoad(() => api.sessions(), [], 5000);
  const [filter, setFilter] = useState<Filter>("all");
  const [error, setError] = useState<string | null>(null);
  const visible = (sessions.data ?? []).filter(FILTERS[filter]);

  return (
    <Stack spacing={3}>
      <Box sx={{ display: "flex", alignItems: "center", gap: 2 }}>
        <Typography variant="h4" sx={{ flexGrow: 1 }}>
          Sessions
        </Typography>
        <ToggleButtonGroup size="small" exclusive value={filter} onChange={(_, value: Filter | null) => value && setFilter(value)}>
          <ToggleButton value="all">All</ToggleButton>
          <ToggleButton value="mine">Mine</ToggleButton>
          <ToggleButton value="running">Running</ToggleButton>
        </ToggleButtonGroup>
      </Box>
      <Typography color="text.secondary">
        Every session is a sandboxed OpenCode instance. Start new ones from a team's workflows.
      </Typography>
      {(error || sessions.error) && <Alert severity="error">{error || sessions.error}</Alert>}
      <Paper variant="outlined">
        <Table>
          <TableHead>
            <TableRow>
              <TableCell>Name</TableCell>
              <TableCell>Team</TableCell>
              <TableCell>Owner</TableCell>
              <TableCell>Visibility</TableCell>
              <TableCell>Status</TableCell>
              <TableCell>Last activity</TableCell>
              <TableCell align="right">Actions</TableCell>
            </TableRow>
          </TableHead>
          <TableBody>
            {visible.length === 0 && (
              <TableRow>
                <TableCell colSpan={7} align="center" sx={{ py: 6, color: "text.secondary" }}>
                  No sessions.
                </TableCell>
              </TableRow>
            )}
            {visible.map((session) => (
              <TableRow key={session.id} hover>
                <TableCell>
                  <Stack direction="row" spacing={1} sx={{ alignItems: "center" }}>
                    {session.activity && <ActivityChip activity={session.activity} />}
                    <Link component={RouterLink} to={`/sessions/${session.id}`}>
                      {session.name}
                    </Link>
                  </Stack>
                </TableCell>
                <TableCell>{session.team_id}</TableCell>
                <TableCell>{session.is_owner ? "you" : session.sub}</TableCell>
                <TableCell>
                  <VisibilityChip visibility={session.visibility} />
                </TableCell>
                <TableCell>
                  <SessionStatusChip status={session.status} />
                </TableCell>
                <TableCell>{relativeTime(session.last_activity_at)}</TableCell>
                <TableCell align="right">
                  <SessionActions session={session} onChange={sessions.reload} onError={setError} />
                </TableCell>
              </TableRow>
            ))}
          </TableBody>
        </Table>
      </Paper>
    </Stack>
  );
}
