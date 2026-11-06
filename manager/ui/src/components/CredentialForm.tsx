import { useState, type FormEvent, type ReactNode } from "react";
import { Alert, Button, Stack, TextField, Typography } from "@mui/material";
import type { CredentialInput } from "../api";

interface Props {
  name: string;
  kind: "token" | "basic" | "ssh_key";
  help?: string;
  checks?: boolean;
  save: (value: CredentialInput) => Promise<{ message: string }>;
  onSaved: () => void;
  extraActions?: ReactNode;
}

const SECRET_LABEL = { token: "Token", basic: "Password or API token", ssh_key: "Private key" };

export default function CredentialForm({ name, kind, help, checks, save, onSaved, extraActions }: Props) {
  const [username, setUsername] = useState("");
  const [secret, setSecret] = useState("");
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const submit = async (event: FormEvent) => {
    event.preventDefault();
    setBusy(true);
    setError(null);
    try {
      await save({ secret, ...(kind === "basic" ? { username } : {}) });
      setSecret("");
      onSaved();
    } catch (e) {
      setError((e as Error).message);
    } finally {
      setBusy(false);
    }
  };

  return (
    <form onSubmit={submit}>
      <Stack spacing={2} sx={{ pt: 1 }}>
        {help && (
          <Typography variant="body2" color="text.secondary">
            {help}
          </Typography>
        )}
        {kind === "basic" && (
          <TextField label="Username" value={username} onChange={(e) => setUsername(e.target.value)} autoComplete="off" required />
        )}
        <TextField
          label={SECRET_LABEL[kind]}
          value={secret}
          onChange={(e) => setSecret(e.target.value)}
          type={kind === "ssh_key" ? "text" : "password"}
          multiline={kind === "ssh_key"}
          minRows={kind === "ssh_key" ? 4 : undefined}
          autoComplete="off"
          required
          helperText="Stored encrypted. It is never shown again and never reaches agent sandboxes."
        />
        {error && <Alert severity="error">{error}</Alert>}
        <Stack direction="row" spacing={1}>
          <Button type="submit" variant="contained" disabled={busy || !secret}>
            {busy ? "Saving…" : checks ? `Check and save ${name}` : `Save ${name}`}
          </Button>
          {extraActions}
        </Stack>
      </Stack>
    </form>
  );
}
