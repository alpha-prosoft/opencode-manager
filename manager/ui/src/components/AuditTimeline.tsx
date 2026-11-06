import { useEffect, useState } from "react";
import { Accordion, AccordionDetails, AccordionSummary, Alert, Box, Button, Stack, Typography } from "@mui/material";
import DownloadIcon from "@mui/icons-material/Download";
import ExpandMoreIcon from "@mui/icons-material/ExpandMore";
import { api, type AuditEvent } from "../api";
import { CodeBlock, EmptyState, Mono } from "./Data";
import Markdown from "./Markdown";

const TYPE_NAMES: Record<string, string> = {
  message: "Message",
  text: "Text",
  reasoning: "Thinking",
  tool: "Tool call",
  conversation: "Conversation",
};

function summary(event: AuditEvent): string {
  const p = event.payload;
  switch (event.type) {
    case "message":
      return `${p.role === "user" ? "Prompt" : "Agent reply"}${p.tokens ? `, ${p.tokens.input ?? 0} tokens in, ${p.tokens.output ?? 0} out` : ""}`;
    case "text":
    case "reasoning":
      return (p.text ?? "").slice(0, 160);
    case "tool":
      return `${p.tool}: ${p.state?.title ?? p.state?.status ?? ""}`;
    case "conversation":
      return `“${p.title}”`;
    default:
      return event.type;
  }
}

function Detail({ event }: { event: AuditEvent }) {
  const p = event.payload;
  if (event.type === "text") return <Markdown>{p.text ?? ""}</Markdown>;
  if (event.type === "tool") {
    return (
      <Stack spacing={1}>
        <Typography variant="label">Input</Typography>
        <CodeBlock>{JSON.stringify(p.state?.input, null, 2)}</CodeBlock>
        <Typography variant="label">Output</Typography>
        <CodeBlock maxHeight={320}>{String(p.state?.output ?? p.state?.error ?? "").slice(0, 20000)}</CodeBlock>
      </Stack>
    );
  }
  return <CodeBlock>{JSON.stringify(p, null, 2)}</CodeBlock>;
}

export default function AuditTimeline({ sessionId }: { sessionId: string }) {
  const [events, setEvents] = useState<AuditEvent[] | null>(null);
  const [verification, setVerification] = useState<{ valid: boolean; checked: number } | null>(null);

  useEffect(() => {
    api.audit(sessionId).then(setEvents);
    api.verifyAudit(sessionId).then(setVerification);
  }, [sessionId]);

  if (events && events.length === 0) {
    return <EmptyState title="No audit records yet">Messages and tool calls are recorded here as the agent works.</EmptyState>;
  }
  return (
    <Stack spacing={2}>
      <Stack direction={{ xs: "column", sm: "row" }} spacing={2} sx={{ alignItems: { sm: "center" } }}>
        {verification && (
          <Alert severity={verification.valid ? "success" : "error"} sx={{ flexGrow: 1 }}>
            {verification.valid
              ? `Verified: all ${verification.checked} records are intact. They are stored outside the sandbox and can't be edited.`
              : "Verification failed: records were changed after they were written."}
          </Alert>
        )}
        <Button startIcon={<DownloadIcon />} href={`/api/sessions/${sessionId}/audit/export`}>
          Download (JSONL)
        </Button>
      </Stack>
      <Box>
        {(events ?? []).map((event) => (
          <Accordion key={event.seq}>
            <AccordionSummary expandIcon={<ExpandMoreIcon />}>
              <Stack direction="row" spacing={2} sx={{ alignItems: "baseline", minWidth: 0 }}>
                <Typography variant="body2" color="text.secondary" sx={{ minWidth: 76 }}>
                  {new Date(event.ts).toLocaleTimeString()}
                </Typography>
                <Typography variant="body2" sx={{ minWidth: 92, fontWeight: 600 }}>
                  {TYPE_NAMES[event.type] ?? event.type}
                </Typography>
                <Typography variant="body2" noWrap>
                  {summary(event)}
                </Typography>
              </Stack>
            </AccordionSummary>
            <AccordionDetails>
              <Stack spacing={1}>
                <Detail event={event} />
                <Typography variant="caption" color="text.secondary">
                  Record {event.seq}, fingerprint <Mono>{event.hash.slice(0, 16)}</Mono>
                </Typography>
              </Stack>
            </AccordionDetails>
          </Accordion>
        ))}
      </Box>
    </Stack>
  );
}
