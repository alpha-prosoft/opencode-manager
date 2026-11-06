import { useEffect, useState, type FormEvent } from "react";
import { useNavigate, useSearchParams } from "react-router";
import { Alert, Autocomplete, Box, Button, Chip, Paper, Stack, TextField, Typography } from "@mui/material";
import { api, type Persona } from "../api";
import { isSessionEntry, safeNext } from "../auth";

export default function LoginPage() {
  const navigate = useNavigate();
  const [params] = useSearchParams();
  const [personas, setPersonas] = useState<Persona[]>([]);
  const [username, setUsername] = useState("");
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    api.personas().then(setPersonas).catch(() => setPersonas([]));
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
    if (isSessionEntry(next)) window.location.assign(next);
    else navigate(next, { replace: true });
  };

  return (
    <Box sx={{ display: "grid", placeItems: "center", minHeight: "100vh", bgcolor: "background.default", px: 2 }}>
      <Paper variant="outlined" sx={{ p: 4, width: "100%", maxWidth: 440 }}>
        <form onSubmit={submit}>
          <Stack spacing={3}>
            <Box>
              <Typography variant="h5">Agent Platform</Typography>
              <Typography variant="body2" color="text.secondary">
                Test login: pick a persona or type any username. Roles come from the onboarding repository.
              </Typography>
            </Box>
            <Autocomplete
              freeSolo
              options={personas}
              getOptionLabel={(option) => (typeof option === "string" ? option : option.username)}
              inputValue={username}
              onInputChange={(_, value) => setUsername(value)}
              renderOption={(props, option) => {
                const { key, ...rest } = props as typeof props & { key: string };
                return (
                  <Box component="li" key={key} {...rest} sx={{ display: "block !important" }}>
                    <Typography sx={{ fontWeight: 600 }}>{option.username}</Typography>
                    <Stack direction="row" spacing={0.5} useFlexGap sx={{ flexWrap: "wrap", mt: 0.5 }}>
                      {option.roles.map((role) => (
                        <Chip key={role} size="small" label={role} color={role === "Platform admin" ? "primary" : "default"} />
                      ))}
                    </Stack>
                  </Box>
                );
              }}
              renderInput={(props) => <TextField {...props} label="Username" autoFocus required />}
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
