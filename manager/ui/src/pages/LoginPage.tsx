import { useState, type FormEvent } from "react";
import { useNavigate, useSearchParams } from "react-router";
import { Alert, Box, Button, Paper, Stack, TextField, Typography } from "@mui/material";
import { api } from "../api";
import { isSessionEntry, safeNext } from "../auth";

export default function LoginPage() {
  const navigate = useNavigate();
  const [params] = useSearchParams();
  const [username, setUsername] = useState("");
  const [error, setError] = useState<string | null>(null);

  const submit = async (event: FormEvent) => {
    event.preventDefault();
    setError(null);
    try {
      await api.login(username);
    } catch (e) {
      setError((e as Error).message);
      return;
    }
    const next = safeNext(params.get("next"));
    if (isSessionEntry(next)) {
      window.location.assign(next);
    } else {
      navigate(next, { replace: true });
    }
  };

  return (
    <Box sx={{ display: "grid", placeItems: "center", minHeight: "100vh", bgcolor: "background.default", px: 2 }}>
      <Paper variant="outlined" sx={{ p: 4, width: "100%", maxWidth: 360 }}>
        <form onSubmit={submit}>
          <Stack spacing={3}>
            <Typography variant="h5">OpenCode Sessions</Typography>
            <TextField
              label="Username"
              value={username}
              onChange={(e) => setUsername(e.target.value)}
              autoFocus
              required
              helperText="Letters, digits, dot, dash or underscore"
            />
            {error && <Alert severity="error">{error}</Alert>}
            <Button type="submit" variant="contained" disabled={!username.trim()}>
              Log in
            </Button>
          </Stack>
        </form>
      </Paper>
    </Box>
  );
}
