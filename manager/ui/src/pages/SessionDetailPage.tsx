import { useCallback, useEffect, useState } from "react";
import { useNavigate, useParams } from "react-router";
import { Alert, Box, Button, Chip, FormControlLabel, Paper, Stack, Switch, Typography } from "@mui/material";
import ArrowBackIcon from "@mui/icons-material/ArrowBack";
import { api, type Session } from "../api";
import SessionActions from "../components/SessionActions";
import StatusChip from "../components/StatusChip";
import VisibilityChip from "../components/VisibilityChip";

function Field({ label, children }: { label: string; children: React.ReactNode }) {
  return (
    <Box>
      <Typography variant="caption" color="text.secondary">
        {label}
      </Typography>
      <Typography component="div">{children}</Typography>
    </Box>
  );
}

const formatDate = (value: string | null) => (value ? new Date(value).toLocaleString() : "—");

export default function SessionDetailPage() {
  const { sessionId = "" } = useParams();
  const navigate = useNavigate();
  const [session, setSession] = useState<Session | null>(null);
  const [error, setError] = useState<string | null>(null);

  const load = useCallback(() => {
    api
      .getSession(sessionId)
      .then(setSession)
      .catch((e: Error) => (e.message.startsWith("404") ? navigate("/") : setError(e.message)));
  }, [sessionId, navigate]);

  useEffect(load, [load]);

  return (
    <Stack spacing={3}>
      <Box>
        <Button startIcon={<ArrowBackIcon />} onClick={() => navigate("/")}>
          All sessions
        </Button>
      </Box>
      {error && <Alert severity="error">{error}</Alert>}
      {session && (
        <Paper variant="outlined" sx={{ p: 3 }}>
          <Stack spacing={2}>
            <Box sx={{ display: "flex", alignItems: "center", gap: 2 }}>
              <Typography variant="h5" sx={{ flexGrow: 1 }}>
                {session.name}
              </Typography>
              <VisibilityChip visibility={session.visibility} />
              <StatusChip status={session.status} />
              <SessionActions session={session} onChange={load} onError={setError} />
            </Box>
            {session.description && <Typography>{session.description}</Typography>}
            {session.is_owner && (
              <FormControlLabel
                control={
                  <Switch
                    checked={session.visibility === "public"}
                    onChange={(e) =>
                      api
                        .updateSession(session.id, { visibility: e.target.checked ? "public" : "private" })
                        .then(setSession)
                        .catch((error: Error) => setError(error.message))
                    }
                  />
                }
                label="Public: every logged-in user can see and open this session"
              />
            )}
            <Field label="ID">{session.id}</Field>
            <Field label="Owner">
              {session.email} ({session.sub})
            </Field>
            <Field label="URL">{session.url}</Field>
            <Field label="Image">{session.image}</Field>
            <Field label="Created">{formatDate(session.created_at)}</Field>
            <Field label="Last activity">{formatDate(session.last_activity_at)}</Field>
            <Field label="Stopped">{formatDate(session.stopped_at)}</Field>
            <Field label="Labels">
              <Stack direction="row" spacing={0.5} useFlexGap sx={{ flexWrap: "wrap" }}>
                {Object.entries(session.labels).map(([key, value]) => (
                  <Chip key={key} size="small" variant="outlined" label={`${key}=${value}`} />
                ))}
              </Stack>
            </Field>
          </Stack>
        </Paper>
      )}
    </Stack>
  );
}
