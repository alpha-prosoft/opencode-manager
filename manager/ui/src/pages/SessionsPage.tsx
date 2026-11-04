import { useCallback, useEffect, useState } from "react";
import { Link as RouterLink } from "react-router";
import {
  Alert,
  Box,
  Button,
  Link,
  Paper,
  Stack,
  Table,
  TableBody,
  TableCell,
  TableContainer,
  TableHead,
  TableRow,
  ToggleButton,
  ToggleButtonGroup,
  Typography,
} from "@mui/material";
import AddIcon from "@mui/icons-material/Add";
import { api, type Session } from "../api";
import CreateSessionDialog from "../components/CreateSessionDialog";
import SessionActions from "../components/SessionActions";
import StatusChip from "../components/StatusChip";
import VisibilityChip from "../components/VisibilityChip";

const REFRESH_MS = 5000;

type Filter = "all" | "mine" | "public";

const FILTERS: Record<Filter, (session: Session) => boolean> = {
  all: () => true,
  mine: (session) => session.is_owner,
  public: (session) => session.visibility === "public",
};

export default function SessionsPage() {
  const [sessions, setSessions] = useState<Session[]>([]);
  const [filter, setFilter] = useState<Filter>("all");
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

  const visible = sessions.filter(FILTERS[filter]);

  return (
    <Stack spacing={3}>
      <Box sx={{ display: "flex", alignItems: "center", gap: 2, flexWrap: "wrap" }}>
        <Typography variant="h5" sx={{ flexGrow: 1 }}>
          Sessions
        </Typography>
        <ToggleButtonGroup
          size="small"
          exclusive
          value={filter}
          onChange={(_, value: Filter | null) => value && setFilter(value)}
        >
          <ToggleButton value="all">All</ToggleButton>
          <ToggleButton value="mine">Mine</ToggleButton>
          <ToggleButton value="public">Public</ToggleButton>
        </ToggleButtonGroup>
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
                <TableCell colSpan={6} align="center" sx={{ py: 6, color: "text.secondary" }}>
                  No sessions.
                </TableCell>
              </TableRow>
            )}
            {visible.map((session) => (
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
                <TableCell>{session.is_owner ? "you" : session.sub}</TableCell>
                <TableCell>
                  <VisibilityChip visibility={session.visibility} />
                </TableCell>
                <TableCell>
                  <StatusChip status={session.status} />
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
