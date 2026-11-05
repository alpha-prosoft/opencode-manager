import { Link as RouterLink } from "react-router";
import { Alert, Chip, Link, Paper, Stack, Table, TableBody, TableCell, TableHead, TableRow, Typography } from "@mui/material";
import { api } from "../api";
import { relativeTime } from "../components/format";
import { useLoad } from "../hooks";

export default function TeamsPage() {
  const teams = useLoad(() => api.teams(), []);
  return (
    <Stack spacing={3}>
      <Typography variant="h4">Teams</Typography>
      {teams.error && <Alert severity="error">{teams.error}</Alert>}
      <Paper variant="outlined">
        <Table>
          <TableHead>
            <TableRow>
              <TableCell>Team</TableCell>
              <TableCell>Repository</TableCell>
              <TableCell>Definitions</TableCell>
              <TableCell>Synced</TableCell>
              <TableCell>Your role</TableCell>
            </TableRow>
          </TableHead>
          <TableBody>
            {(teams.data ?? []).map((team) => (
              <TableRow key={team.id} hover>
                <TableCell>
                  <Link component={RouterLink} to={`/teams/${team.id}`}>
                    {team.name}
                  </Link>
                  <Typography variant="body2" color="text.secondary">
                    {team.description}
                  </Typography>
                </TableCell>
                <TableCell sx={{ fontFamily: "monospace", fontSize: "0.85rem" }}>
                  {team.repository.url}
                  <br />
                  {team.repository.branch} · {team.commit?.slice(0, 8)}
                </TableCell>
                <TableCell>
                  <Stack direction="row" spacing={0.5} useFlexGap sx={{ flexWrap: "wrap" }}>
                    <Chip size="small" label={`${team.counts.workflows} workflows`} />
                    <Chip size="small" label={`${team.counts.agents} agents`} variant="outlined" />
                    <Chip size="small" label={`${team.counts.harnesses} harnesses`} variant="outlined" />
                    {team.invalid > 0 && <Chip size="small" color="warning" label={`${team.invalid} invalid`} />}
                  </Stack>
                </TableCell>
                <TableCell>
                  {team.status === "error" ? <Chip size="small" color="error" label="sync error" /> : relativeTime(team.synced_at)}
                </TableCell>
                <TableCell>{team.role}</TableCell>
              </TableRow>
            ))}
          </TableBody>
        </Table>
      </Paper>
    </Stack>
  );
}
