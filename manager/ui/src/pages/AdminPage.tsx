import { useState } from "react";
import { Alert, Box, Button, Chip, Paper, Stack, Table, TableBody, TableCell, TableHead, TableRow, Typography } from "@mui/material";
import SyncIcon from "@mui/icons-material/Sync";
import { api } from "../api";
import { relativeTime } from "../components/format";
import { useLoad } from "../hooks";

export default function AdminPage() {
  const status = useLoad(() => api.platform(), []);
  const teams = useLoad(() => api.teams(), []);
  const [syncing, setSyncing] = useState(false);
  const [report, setReport] = useState<string | null>(null);

  const sync = async () => {
    setSyncing(true);
    try {
      const result = await api.sync();
      setReport(result.error ? `Sync failed: ${result.error}` : `Synced onboarding ${String(result.commit).slice(0, 8)}.`);
      status.reload();
      teams.reload();
    } catch (e) {
      setReport((e as Error).message);
    } finally {
      setSyncing(false);
    }
  };

  const s = status.data;
  return (
    <Stack spacing={3}>
      <Stack direction="row" sx={{ alignItems: "center" }}>
        <Typography variant="h4" sx={{ flexGrow: 1 }}>
          Platform administration
        </Typography>
        <Button variant="contained" startIcon={<SyncIcon />} onClick={sync} disabled={syncing}>
          {syncing ? "Syncing…" : "Sync now"}
        </Button>
      </Stack>
      {report && <Alert severity={report.startsWith("Sync failed") ? "error" : "success"}>{report}</Alert>}
      {s && (
        <Paper variant="outlined" sx={{ p: 2 }}>
          <Typography variant="h6" gutterBottom>
            Onboarding repository
          </Typography>
          {!s.configured && <Alert severity="warning">No onboarding repository configured (MANAGER_ONBOARDING_URL).</Alert>}
          {s.error && <Alert severity="error">{s.error}</Alert>}
          <Typography>
            Commit <code>{s.commit?.slice(0, 8) ?? "—"}</code> · synced {relativeTime(s.synced_at)} · {s.teams} team(s)
          </Typography>
          <Box sx={{ mt: 2 }}>
            <Typography variant="subtitle2">LLM connections</Typography>
            {s.llm_connections.map((c) => (
              <Typography key={c.id} variant="body2" sx={{ fontFamily: "monospace" }}>
                {c.id} ({c.type}): {c.models.join(", ")}
              </Typography>
            ))}
            <Typography variant="subtitle2" sx={{ mt: 1 }}>
              Allowed images
            </Typography>
            {s.allowed_images.map((image) => (
              <Typography key={image} variant="body2" sx={{ fontFamily: "monospace" }}>
                {image}
              </Typography>
            ))}
          </Box>
        </Paper>
      )}
      <Paper variant="outlined">
        <Table>
          <TableHead>
            <TableRow>
              <TableCell>Team</TableCell>
              <TableCell>Sync</TableCell>
              <TableCell>Definitions</TableCell>
            </TableRow>
          </TableHead>
          <TableBody>
            {(teams.data ?? []).map((team) => (
              <TableRow key={team.id}>
                <TableCell>{team.name}</TableCell>
                <TableCell>
                  {team.status === "ok" ? <Chip size="small" color="success" label="ok" /> : <Chip size="small" color="error" label={team.error ?? "error"} />}{" "}
                  {relativeTime(team.synced_at)}
                </TableCell>
                <TableCell>
                  {team.counts.workflows} workflows, {team.counts.agents} agents, {team.counts.harnesses} harnesses
                  {team.invalid > 0 && <Chip size="small" color="warning" label={`${team.invalid} invalid`} sx={{ ml: 1 }} />}
                </TableCell>
              </TableRow>
            ))}
          </TableBody>
        </Table>
      </Paper>
      <Typography variant="body2" color="text.secondary">
        Prometheus metrics are available at <code>/metrics</code> for platform administrators.
      </Typography>
    </Stack>
  );
}
