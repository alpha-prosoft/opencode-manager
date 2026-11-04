import { useCallback, useEffect, useState } from "react";
import { Link as RouterLink } from "react-router";
import {
  Alert,
  Box,
  Button,
  Chip,
  Link,
  Paper,
  Stack,
  Table,
  TableBody,
  TableCell,
  TableContainer,
  TableHead,
  TableRow,
  Typography,
} from "@mui/material";
import AddIcon from "@mui/icons-material/Add";
import { api, type Session } from "../api";
import CreateSessionDialog from "../components/CreateSessionDialog";
import SessionActions from "../components/SessionActions";
import StatusChip from "../components/StatusChip";

const REFRESH_MS = 5000;

export default function SessionsPage() {
  const [sessions, setSessions] = useState<Session[]>([]);
  const [error, setError] = useState<string | null>(null);
  const [creating, setCreating] = useState(false);

  const load = useCallback(() => {
    api
      .listSessions()
      .then((data) => {
        setSessions(data);
        setError(null);
      })
      .catch((e: Error) => setError(e.message));
  }, []);

  useEffect(() => {
    load();
    const timer = setInterval(load, REFRESH_MS);
    return () => clearInterval(timer);
  }, [load]);

  return (
    <Stack spacing={3}>
      <Box sx={{ display: "flex", alignItems: "center", justifyContent: "space-between" }}>
        <Typography variant="h5">Sessions</Typography>
        <Button variant="contained" startIcon={<AddIcon />} onClick={() => setCreating(true)}>
          New session
        </Button>
      </Box>

      {error && <Alert severity="error">{error}</Alert>}

      <TableContainer component={Paper} variant="outlined">
        <Table>
          <TableHead>
            <TableRow>
              <TableCell>Name</TableCell>
              <TableCell>Status</TableCell>
              <TableCell>Labels</TableCell>
              <TableCell>Last activity</TableCell>
              <TableCell align="right">Actions</TableCell>
            </TableRow>
          </TableHead>
          <TableBody>
            {sessions.length === 0 && (
              <TableRow>
                <TableCell colSpan={5} align="center" sx={{ py: 6, color: "text.secondary" }}>
                  No sessions yet.
                </TableCell>
              </TableRow>
            )}
            {sessions.map((session) => (
              <TableRow key={session.id} hover>
                <TableCell>
                  <Link component={RouterLink} to={`/sessions/${session.id}`}>
                    {session.name}
                  </Link>
                  {session.description && (
                    <Typography variant="body2" color="text.secondary" noWrap sx={{ maxWidth: 320 }}>
                      {session.description}
                    </Typography>
                  )}
                </TableCell>
                <TableCell>
                  <StatusChip status={session.status} />
                </TableCell>
                <TableCell>
                  <Stack direction="row" spacing={0.5} useFlexGap sx={{ flexWrap: "wrap" }}>
                    {Object.entries(session.labels).map(([key, value]) => (
                      <Chip key={key} size="small" variant="outlined" label={`${key}=${value}`} />
                    ))}
                  </Stack>
                </TableCell>
                <TableCell>{new Date(session.last_activity_at).toLocaleString()}</TableCell>
                <TableCell align="right">
                  <SessionActions session={session} onChange={load} onError={setError} />
                </TableCell>
              </TableRow>
            ))}
          </TableBody>
        </Table>
      </TableContainer>

      <CreateSessionDialog
        open={creating}
        onClose={() => setCreating(false)}
        onCreated={() => {
          setCreating(false);
          load();
        }}
      />
    </Stack>
  );
}
