import { useState } from "react";
import { Link as RouterLink, useNavigate, useParams } from "react-router";
import { Alert, Box, MenuItem, Paper, Stack, Tab, Table, TableBody, TableCell, TableRow, Tabs, TextField, Typography } from "@mui/material";
import { api, type Visibility } from "../api";
import AuditTimeline from "../components/AuditTimeline";
import LiveActivity from "../components/LiveActivity";
import { ActivityChip, SessionStatusChip, VisibilityChip } from "../components/Chips";
import SessionActions from "../components/SessionActions";
import UsageStats from "../components/UsageStats";
import { relativeTime } from "../components/format";
import { useLoad } from "../hooks";

export default function SessionPage() {
  const { sessionId = "" } = useParams();
  const navigate = useNavigate();
  const { data: session, error, reload } = useLoad(() => api.session(sessionId), [sessionId], 5000);
  const [tab, setTab] = useState(0);
  const [actionError, setActionError] = useState<string | null>(null);

  if (error) return <Alert severity="error">{error}</Alert>;
  if (!session) return null;

  const setVisibility = (visibility: Visibility) =>
    api.updateSession(session.id, { visibility }).then(reload).catch((e: Error) => setActionError(e.message));

  return (
    <Stack spacing={3}>
      <Stack direction="row" spacing={2} sx={{ alignItems: "center", flexWrap: "wrap" }} useFlexGap>
        {session.activity && <ActivityChip activity={session.activity} />}
        <Typography variant="h4" sx={{ flexGrow: 1 }}>
          {session.name}
        </Typography>
        <VisibilityChip visibility={session.visibility} />
        <SessionStatusChip status={session.status} />
        <SessionActions
          session={session}
          onChange={() => api.session(session.id).then(reload).catch(() => navigate("/sessions"))}
          onError={setActionError}
        />
      </Stack>
      {actionError && <Alert severity="error">{actionError}</Alert>}

      <Tabs value={tab} onChange={(_, value) => setTab(value)}>
        <Tab label="Overview" />
        <Tab label="Live activity" />
        <Tab label={`Audit trail (${session.audit_events})`} />
      </Tabs>

      {tab === 0 && (
        <Stack spacing={3}>
          <Paper variant="outlined" sx={{ p: 2 }}>
            <Table size="small">
              <TableBody>
                <TableRow>
                  <TableCell sx={{ width: 180, color: "text.secondary" }}>Team</TableCell>
                  <TableCell>{session.team_id ? <RouterLink to={`/teams/${session.team_id}`}>{session.team_id}</RouterLink> : "—"}</TableCell>
                </TableRow>
                <TableRow>
                  <TableCell sx={{ color: "text.secondary" }}>Run</TableCell>
                  <TableCell>{session.run_id ? <RouterLink to={`/runs/${session.run_id}`}>{session.run_id}</RouterLink> : "—"}</TableCell>
                </TableRow>
                <TableRow>
                  <TableCell sx={{ color: "text.secondary" }}>Owner</TableCell>
                  <TableCell>{session.sub}</TableCell>
                </TableRow>
                <TableRow>
                  <TableCell sx={{ color: "text.secondary" }}>Address</TableCell>
                  <TableCell sx={{ fontFamily: "monospace" }}>{session.url}</TableCell>
                </TableRow>
                <TableRow>
                  <TableCell sx={{ color: "text.secondary" }}>Created</TableCell>
                  <TableCell>{new Date(session.created_at).toLocaleString()}</TableCell>
                </TableRow>
                <TableRow>
                  <TableCell sx={{ color: "text.secondary" }}>Last activity</TableCell>
                  <TableCell>{relativeTime(session.last_activity_at)}</TableCell>
                </TableRow>
                {session.can_manage && (
                  <TableRow>
                    <TableCell sx={{ color: "text.secondary" }}>Visibility</TableCell>
                    <TableCell>
                      <TextField
                        select
                        size="small"
                        value={session.visibility}
                        onChange={(e) => setVisibility(e.target.value as Visibility)}
                        helperText="private: you · team: your team · public: every user"
                      >
                        <MenuItem value="private">private</MenuItem>
                        <MenuItem value="team">team</MenuItem>
                        <MenuItem value="public">public</MenuItem>
                      </TextField>
                    </TableCell>
                  </TableRow>
                )}
              </TableBody>
            </Table>
          </Paper>
          <Box>
            <Typography variant="h6" gutterBottom>
              LLM usage (metered by the platform gateway)
            </Typography>
            <UsageStats usage={session.usage} />
          </Box>
        </Stack>
      )}
      {tab === 1 && <LiveActivity sessionId={session.id} />}
      {tab === 2 && <AuditTimeline sessionId={session.id} />}
    </Stack>
  );
}
