import { Alert, Box, Chip, Paper, Stack, Table, TableBody, TableCell, TableHead, TableRow, Typography } from "@mui/material";
import BlockIcon from "@mui/icons-material/Block";
import { api, type ActivityEvent } from "../api";
import { relativeTime } from "./format";
import { useLoad } from "../hooks";

function describe(event: ActivityEvent): string {
  if (event.kind === "event") {
    const details = (() => {
      try {
        return JSON.parse(event.detail.details ?? "{}");
      } catch {
        return {};
      }
    })();
    if (event.detail.event === "file.edited") return `edited ${details.file}`;
    if (event.detail.event === "session.status") return `status ${details.status?.type ?? ""}`;
    return event.detail.event;
  }
  if (event.kind === "tool.blocked") return `${event.tool} blocked: ${event.detail.reason}`;
  if (event.kind === "tool.end") return `${event.tool} · ${event.detail.title || ""}`;
  return `${event.tool} started`;
}

export default function LiveActivity({ sessionId }: { sessionId: string }) {
  const { data } = useLoad(() => api.activity(sessionId), [sessionId], 3000);
  if (!data) return null;
  return (
    <Stack spacing={2}>
      <Stack direction="row" spacing={1} sx={{ alignItems: "center", flexWrap: "wrap" }} useFlexGap>
        <Chip label={data.state} color={data.state === "busy" ? "info" : "default"} />
        {data.current && (
          <Typography>
            Running <strong>{data.current.tool}</strong> since {new Date(data.current.ts).toLocaleTimeString()}
          </Typography>
        )}
        <Typography variant="body2" color="text.secondary">
          Last signal {relativeTime(data.last_seen)}
        </Typography>
      </Stack>
      {data.blocked > 0 && (
        <Alert severity="warning" icon={<BlockIcon />}>
          The harness policy blocked {data.blocked} tool call(s).
        </Alert>
      )}
      <Box sx={{ display: "grid", gridTemplateColumns: { xs: "1fr", md: "1fr 1fr" }, gap: 2 }}>
        <Paper variant="outlined">
          <Table size="small">
            <TableHead>
              <TableRow>
                <TableCell>Tool</TableCell>
                <TableCell align="right">Calls</TableCell>
                <TableCell align="right">Avg</TableCell>
                <TableCell align="right">Max</TableCell>
              </TableRow>
            </TableHead>
            <TableBody>
              {data.tools.map((tool) => (
                <TableRow key={tool.tool}>
                  <TableCell sx={{ fontFamily: "monospace" }}>{tool.tool}</TableCell>
                  <TableCell align="right">{tool.calls}</TableCell>
                  <TableCell align="right">{Math.round(tool.avg_ms ?? 0)} ms</TableCell>
                  <TableCell align="right">{tool.max_ms ?? 0} ms</TableCell>
                </TableRow>
              ))}
              {data.tools.length === 0 && (
                <TableRow>
                  <TableCell colSpan={4} sx={{ color: "text.secondary" }}>
                    No tool calls yet.
                  </TableCell>
                </TableRow>
              )}
            </TableBody>
          </Table>
        </Paper>
        <Paper variant="outlined" sx={{ p: 2 }}>
          <Typography variant="subtitle2" gutterBottom>
            Files edited
          </Typography>
          {data.files_edited.length === 0 && <Typography color="text.secondary">None.</Typography>}
          {data.files_edited.map((file) => (
            <Typography key={file} variant="body2" sx={{ fontFamily: "monospace" }}>
              {file}
            </Typography>
          ))}
        </Paper>
      </Box>
      <Paper variant="outlined" sx={{ p: 2, maxHeight: 420, overflowY: "auto" }}>
        {data.events.map((event) => (
          <Stack key={event.id} direction="row" spacing={1.5} sx={{ py: 0.5, alignItems: "center" }}>
            <Typography variant="caption" color="text.secondary" sx={{ minWidth: 72 }}>
              {new Date(event.ts).toLocaleTimeString()}
            </Typography>
            <Chip
              size="small"
              variant="outlined"
              label={event.kind}
              color={event.kind === "tool.blocked" ? "warning" : event.kind === "tool.end" ? "success" : "default"}
            />
            <Typography variant="body2" noWrap sx={{ flexGrow: 1 }}>
              {describe(event)}
            </Typography>
            {event.duration_ms !== null && (
              <Typography variant="caption" color="text.secondary">
                {event.duration_ms} ms
              </Typography>
            )}
          </Stack>
        ))}
        {data.events.length === 0 && <Typography color="text.secondary">Waiting for the session monitor…</Typography>}
      </Paper>
      <Typography variant="caption" color="text.secondary">
        Reported by the platform's read-only OpenCode plugin inside the sandbox. The tamper-evident record is the audit trail.
      </Typography>
    </Stack>
  );
}
