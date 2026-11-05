import { useEffect, useState, type FormEvent } from "react";
import { useNavigate } from "react-router";
import { Alert, Button, Dialog, DialogActions, DialogContent, DialogTitle, Stack, Typography } from "@mui/material";
import PlayArrowIcon from "@mui/icons-material/PlayArrow";
import { api, type Activity, type Definition } from "../api";
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

  useEffect(() => {
    setInputs({});
    setError(null);
  }, [workflow?.id]);

  if (!workflow || !activity) return null;

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
          <Stack spacing={2} sx={{ mt: 1 }}>
            <Typography variant="body2" color="text.secondary">
              {workflow.spec?.description || activity.description}
            </Typography>
            <SchemaForm schema={activity.inputs_schema} value={inputs} onChange={setInputs} />
            {error && <Alert severity="error">{error}</Alert>}
          </Stack>
        </DialogContent>
        <DialogActions>
          <Button onClick={onClose}>Cancel</Button>
          <Button type="submit" variant="contained" startIcon={<PlayArrowIcon />} disabled={busy}>
            {activity.interactive ? "Launch" : "Run"}
          </Button>
        </DialogActions>
      </form>
    </Dialog>
  );
}
