import { useState, type FormEvent } from "react";
import {
  Alert,
  Button,
  Dialog,
  DialogActions,
  DialogContent,
  DialogTitle,
  Stack,
  TextField,
} from "@mui/material";
import { api, type Session } from "../api";

interface Props {
  open: boolean;
  onClose: () => void;
  onCreated: (session: Session) => void;
}

function parsePairs(text: string): Record<string, string> {
  return Object.fromEntries(
    text
      .split("\n")
      .map((line) => line.trim())
      .filter((line) => line.includes("="))
      .map((line) => {
        const index = line.indexOf("=");
        return [line.slice(0, index).trim(), line.slice(index + 1).trim()];
      }),
  );
}

export default function CreateSessionDialog({ open, onClose, onCreated }: Props) {
  const [name, setName] = useState("");
  const [description, setDescription] = useState("");
  const [labels, setLabels] = useState("");
  const [env, setEnv] = useState("");
  const [error, setError] = useState<string | null>(null);
  const [submitting, setSubmitting] = useState(false);

  const submit = async (event: FormEvent) => {
    event.preventDefault();
    setSubmitting(true);
    setError(null);
    try {
      const session = await api.createSession({
        name,
        description,
        labels: parsePairs(labels),
        env: parsePairs(env),
      });
      setName("");
      setDescription("");
      setLabels("");
      setEnv("");
      onCreated(session);
    } catch (e) {
      setError((e as Error).message);
    } finally {
      setSubmitting(false);
    }
  };

  return (
    <Dialog open={open} onClose={onClose} fullWidth maxWidth="sm">
      <form onSubmit={submit}>
        <DialogTitle>New session</DialogTitle>
        <DialogContent>
          <Stack spacing={2} sx={{ mt: 1 }}>
            <TextField label="Name" value={name} onChange={(e) => setName(e.target.value)} required autoFocus />
            <TextField
              label="Description"
              value={description}
              onChange={(e) => setDescription(e.target.value)}
              multiline
              minRows={2}
            />
            <TextField
              label="Labels"
              helperText="One key=value per line"
              value={labels}
              onChange={(e) => setLabels(e.target.value)}
              multiline
              minRows={2}
            />
            <TextField
              label="Environment"
              helperText="Extra Open WebUI env vars, one KEY=value per line"
              value={env}
              onChange={(e) => setEnv(e.target.value)}
              multiline
              minRows={2}
            />
            {error && <Alert severity="error">{error}</Alert>}
          </Stack>
        </DialogContent>
        <DialogActions>
          <Button onClick={onClose}>Cancel</Button>
          <Button type="submit" variant="contained" disabled={submitting || !name}>
            Create
          </Button>
        </DialogActions>
      </form>
    </Dialog>
  );
}
