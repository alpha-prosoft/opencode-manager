import { useEffect, useState, type FormEvent } from "react";
import { useNavigate, useSearchParams } from "react-router";
import { Alert, Autocomplete, Avatar, Box, Button, Chip, Paper, Stack, TextField, Typography } from "@mui/material";
import HubOutlinedIcon from "@mui/icons-material/HubOutlined";
import { api, type Persona } from "../api";
import { isSessionEntry, safeNext } from "../auth";
import { Row } from "../components/Data";

export default function LoginPage() {
  const navigate = useNavigate();
  const [params] = useSearchParams();
  const [personas, setPersonas] = useState<Persona[]>([]);
  const [username, setUsername] = useState("");
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    api
      .personas()
      .then(setPersonas)
      .catch(() => setPersonas([]));
  }, []);

  const submit = async (event: FormEvent) => {
    event.preventDefault();
    setError(null);
    try {
      await api.login(username.trim());
    } catch (e) {
      setError((e as Error).message);
      return;
    }
    const next = safeNext(params.get("next"));
    const { pending } = await api.myCredentials().catch(() => ({ pending: [] as string[] }));
    if (pending.length > 0) navigate(`/setup?next=${encodeURIComponent(next)}`, { replace: true });
    else if (isSessionEntry(next)) window.location.assign(next);
    else navigate(next, { replace: true });
  };

  return (
    <Box sx={{ display: "grid", placeItems: "center", minHeight: "100vh", px: 2 }}>
      <Paper sx={{ p: 4, width: "100%", maxWidth: 420 }}>
        <form onSubmit={submit}>
          <Stack spacing={3}>
            <Stack spacing={1.5}>
              <Avatar variant="rounded" sx={{ bgcolor: "primary.main", color: "primary.contrastText" }}>
                <HubOutlinedIcon />
              </Avatar>
              <Typography variant="h1">Log in to Agent Platform</Typography>
              <Typography variant="body2" color="text.secondary">
                Test mode: no password needed. Pick someone to see the platform with their role, or type any name.
              </Typography>
            </Stack>
            <Autocomplete
              freeSolo
              options={personas}
              getOptionLabel={(option) => (typeof option === "string" ? option : option.username)}
              inputValue={username}
              onInputChange={(_, value) => setUsername(value)}
              renderOption={(props, option) => {
                const { key, ...rest } = props as typeof props & { key: string };
                return (
                  <Box component="li" key={key} {...rest}>
                    <Stack spacing={0.5}>
                      <Typography sx={{ fontWeight: 600 }}>{option.username}</Typography>
                      <Row>
                        {option.roles.map((role) => (
                          <Chip key={role} variant="outlined" label={role} color={role === "Platform admin" ? "primary" : "default"} />
                        ))}
                      </Row>
                    </Stack>
                  </Box>
                );
              }}
              renderInput={(props) => <TextField {...props} label="Username" autoFocus required />}
            />
            {error && <Alert severity="error">{error}</Alert>}
            <Button type="submit" variant="contained" size="large" disabled={!username.trim()}>
              Log in
            </Button>
          </Stack>
        </form>
      </Paper>
    </Box>
  );
}
