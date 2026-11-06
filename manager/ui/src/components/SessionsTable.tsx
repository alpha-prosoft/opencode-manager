import { Link as RouterLink } from "react-router";
import { Link, Paper, Table, TableBody, TableCell, TableHead, TableRow, Typography } from "@mui/material";
import type { Session } from "../api";
import { ActivityChip, SessionStatusChip, VisibilityChip } from "./Chips";
import { EmptyState, WIDE_ONLY } from "./Data";
import SessionActions from "./SessionActions";
import { relativeTime } from "./format";

interface Props {
  sessions: Session[] | null;
  onChange: () => void;
  onError: (message: string) => void;
  empty?: string;
}

export default function SessionsTable({ sessions, onChange, onError, empty }: Props) {
  if (sessions && sessions.length === 0) {
    return <EmptyState title="No sessions">{empty ?? "Start a workspace from Home, or run a workflow."}</EmptyState>;
  }
  return (
    <Paper sx={{ overflowX: "auto" }}>
      <Table>
        <TableHead>
          <TableRow>
            <TableCell>Session</TableCell>
            <TableCell sx={WIDE_ONLY}>Activity</TableCell>
            <TableCell sx={WIDE_ONLY}>Owner</TableCell>
            <TableCell sx={WIDE_ONLY}>Shared with</TableCell>
            <TableCell>Status</TableCell>
            <TableCell sx={WIDE_ONLY}>Last activity</TableCell>
            <TableCell />
          </TableRow>
        </TableHead>
        <TableBody>
          {(sessions ?? []).map((session) => (
            <TableRow key={session.id} hover>
              <TableCell>
                <Link component={RouterLink} to={`/sessions/${session.id}`} sx={{ fontWeight: 500 }}>
                  {session.name}
                </Link>
                {session.team_id && (
                  <Typography variant="body2" color="text.secondary">
                    {session.team_id}
                  </Typography>
                )}
              </TableCell>
              <TableCell sx={WIDE_ONLY}>{session.activity && <ActivityChip activity={session.activity} />}</TableCell>
              <TableCell sx={WIDE_ONLY}>{session.is_owner ? "You" : session.sub}</TableCell>
              <TableCell sx={WIDE_ONLY}>
                <VisibilityChip visibility={session.visibility} />
              </TableCell>
              <TableCell>
                <SessionStatusChip status={session.status} />
              </TableCell>
              <TableCell sx={{ ...WIDE_ONLY, whiteSpace: "nowrap" }}>{relativeTime(session.last_activity_at)}</TableCell>
              <TableCell>
                <SessionActions session={session} onChange={onChange} onError={onError} />
              </TableCell>
            </TableRow>
          ))}
        </TableBody>
      </Table>
    </Paper>
  );
}
