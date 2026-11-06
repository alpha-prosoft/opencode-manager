import { Alert, Box, Chip, Paper, Stack, Table, TableBody, TableCell, TableHead, TableRow, Typography } from "@mui/material";
import { api, type ActivityEvent } from "../api";
import { EmptyState, ListRow, Mono, Row, TwoColumns } from "./Data";
import { Section } from "./Page";
import { relativeTime } from "./format";
import { useLoad } from "../hooks";

function parse(raw: unknown): Record<string, any> {
  if (typeof raw !== "string") return (raw as Record<string, any>) ?? {};
  try {
    return JSON.parse(raw);
  } catch {
    return {};
  }
}

const EVENT_NAMES: Record<string, string> = {
  "session.idle": "Agent finished and is waiting",
  "session.error": "Agent hit an error",
  "session.compacted": "Conversation was compacted",
  "permission.asked": "Agent asked for permission",
  "permission.replied": "Permission answered",
  "todo.updated": "Agent updated its to-do list",
};

function describe(event: ActivityEvent): { text: string; color: "default" | "warning" | "success" | "info" } {
  if (event.kind === "tool.blocked") return { text: `Blocked ${event.tool}: ${event.detail.reason}`, color: "warning" };
  if (event.kind === "tool.end") return { text: `${event.tool}${event.detail.title ? `: ${event.detail.title}` : ""}`, color: "success" };
  if (event.kind === "tool.start") return { text: `${event.tool} started`, color: "info" };
  const name = event.detail.event as string;
  const details = parse(event.detail.details);
  if (name === "file.edited") return { text: `Edited ${details.file}`, color: "default" };
  if (name === "session.status") return { text: `Agent is ${details.status?.type ?? "active"}`, color: "default" };
  return { text: EVENT_NAMES[name] ?? name, color: "default" };
}

export default function LiveActivity({ sessionId, live }: { sessionId: string; live: boolean }) {
  const { data } = useLoad(() => api.activity(sessionId), [sessionId], 3000);
  if (!data) return null;
  if (data.events.length === 0) {
    return (
      <EmptyState title="Nothing reported yet">
        The session's monitor reports here as soon as the agent starts working. Sessions created before monitoring was added stay empty.
      </EmptyState>
    );
  }
  const shown = data.events.filter(
    (event) => event.kind !== "tool.start" && !(event.kind === "event" && event.detail.event === "session.status"),
  );
  return (
    <Stack spacing={4}>
      <Row>
        <Chip
          variant="outlined"
          color={live && data.state === "busy" ? "info" : "default"}
          label={!live ? "Session is stopped" : data.state === "busy" ? "Agent is working" : "Agent is idle"}
        />
        {live && data.current && (
          <Typography variant="body2">
            Running <strong>{data.current.tool}</strong> since {new Date(data.current.ts).toLocaleTimeString()}
          </Typography>
        )}
        <Typography variant="body2" color="text.secondary">
          Last update {relativeTime(data.last_seen)}
        </Typography>
      </Row>
      {data.blocked > 0 && (
        <Alert severity="warning">
          The harness policy blocked {data.blocked} tool call{data.blocked === 1 ? "" : "s"}. The agent was told the action isn't allowed.
        </Alert>
      )}
      <TwoColumns>
        <Section title="Tools used">
          <Paper>
            <Table>
              <TableHead>
                <TableRow>
                  <TableCell>Tool</TableCell>
                  <TableCell align="right">Calls</TableCell>
                  <TableCell align="right">Average</TableCell>
                  <TableCell align="right">Longest</TableCell>
                </TableRow>
              </TableHead>
              <TableBody>
                {data.tools.map((tool) => (
                  <TableRow key={tool.tool}>
                    <TableCell>
                      <Mono>{tool.tool}</Mono>
                    </TableCell>
                    <TableCell align="right">{tool.calls}</TableCell>
                    <TableCell align="right">{Math.round(tool.avg_ms ?? 0)} ms</TableCell>
                    <TableCell align="right">{tool.max_ms ?? 0} ms</TableCell>
                  </TableRow>
                ))}
              </TableBody>
            </Table>
          </Paper>
        </Section>
        <Section title="Files edited">
          <Paper sx={{ p: 2 }}>
            {data.files_edited.length === 0 && <Typography color="text.secondary">No files edited.</Typography>}
            {data.files_edited.map((file) => (
              <Box key={file}>
                <Mono>{file}</Mono>
              </Box>
            ))}
          </Paper>
        </Section>
      </TwoColumns>
      <Section
        title="Timeline"
        description="Newest first. Reported by the platform's monitor inside the sandbox; the audit trail is the complete record."
      >
        <Paper sx={{ maxHeight: 480, overflowY: "auto" }}>
          {shown.map((event) => {
            const { text, color } = describe(event);
            return (
              <ListRow key={event.id} dense>
                <Typography variant="body2" color="text.secondary" sx={{ minWidth: 76 }}>
                  {new Date(event.ts).toLocaleTimeString()}
                </Typography>
                <Typography variant="body2" noWrap sx={{ flexGrow: 1, color: color === "warning" ? "warning.main" : undefined }}>
                  {text}
                </Typography>
                {event.duration_ms !== null && (
                  <Typography variant="body2" color="text.secondary">
                    {event.duration_ms} ms
                  </Typography>
                )}
              </ListRow>
            );
          })}
        </Paper>
      </Section>
    </Stack>
  );
}
