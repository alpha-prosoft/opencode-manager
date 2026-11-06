import { useEffect, useState, type FormEvent } from "react";
import { useNavigate } from "react-router";
import { Alert, Button, Dialog, DialogActions, DialogContent, DialogTitle, Stack, Typography } from "@mui/material";
import PlayArrowIcon from "@mui/icons-material/PlayArrow";
import TerminalIcon from "@mui/icons-material/Terminal";
import { api, type Activity, type Definition, type OpenPullRequest } from "../api";
import SchemaForm from "./SchemaForm";

interface Props {
  team: string;
  workflow: Definition | null;
  activity: Activity | undefined;
  onClose: () => void;
}

export default function RunDialog({ team, workflow, activity, onClose }: Props) {
  const navigate = useNavigate();
  const [inputs, setInputs] = useState<Record<string, unknown>>({});
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);
  const [pullRequests, setPullRequests] = useState<OpenPullRequest[]>([]);
  const takesPullRequest = Boolean(activity?.inputs_schema.properties?.pull_request);

  useEffect(() => {
    setInputs({});
    setError(null);
    setPullRequests([]);
    if (workflow && takesPullRequest) {
      api
        .openPullRequests(team, workflow.id)
        .then(setPullRequests)
        .catch(() => setPullRequests([]));
    }
  }, [workflow?.id, team, takesPullRequest]);

  if (!workflow || !activity) return null;
  const interactive = activity.interactive;

  const submit = async (event: FormEvent) => {
    event.preventDefault();
    setBusy(true);
    setError(null);
    try {
      const run = await api.startRun(team, workflow.id, inputs);
      navigate(`/runs/${run.id}`);
    } catch (e) {
      setError((e as Error).message);
    } finally {
      setBusy(false);
    }
  };

  return (
    <Dialog open onClose={onClose} fullWidth maxWidth="sm">
      <form onSubmit={submit}>
        <DialogTitle>{workflow.spec?.name}</DialogTitle>
        <DialogContent>
          <Stack spacing={2.5}>
            <Typography variant="body2" color="text.secondary">
              {workflow.spec?.description || activity.description}{" "}
              {interactive
                ? "A private sandbox starts with the workflow's agent; you can open it as soon as it's ready."
                : "The agent works in its own sandbox and reports a result. You can follow it on the run page."}
            </Typography>
            <SchemaForm schema={activity.inputs_schema} value={inputs} onChange={setInputs} pullRequests={pullRequests} />
            {error && <Alert severity="error">{error}</Alert>}
          </Stack>
        </DialogContent>
        <DialogActions>
          <Button onClick={onClose}>Cancel</Button>
          <Button type="submit" variant="contained" startIcon={interactive ? <TerminalIcon /> : <PlayArrowIcon />} disabled={busy}>
            {busy ? "Starting…" : interactive ? "Start session" : "Run"}
          </Button>
        </DialogActions>
      </form>
    </Dialog>
  );
}
