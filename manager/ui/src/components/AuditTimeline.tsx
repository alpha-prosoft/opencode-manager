import { useEffect, useState } from "react";
import { Accordion, AccordionDetails, AccordionSummary, Alert, Box, Button, Chip, Stack, Typography } from "@mui/material";
import DownloadIcon from "@mui/icons-material/Download";
import ExpandMoreIcon from "@mui/icons-material/ExpandMore";
import VerifiedIcon from "@mui/icons-material/Verified";
import { api, type AuditEvent } from "../api";
import Markdown from "./Markdown";

function summary(event: AuditEvent): string {
  const p = event.payload;
  switch (event.type) {
    case "message":
      return `${p.role} message${p.tokens ? ` · ${p.tokens.input ?? 0} in / ${p.tokens.output ?? 0} out tokens` : ""}`;
    case "text":
      return (p.text ?? "").slice(0, 140);
    case "reasoning":
      return `reasoning: ${(p.text ?? "").slice(0, 120)}`;
    case "tool":
      return `${p.tool} · ${p.state?.status}${p.state?.title ? ` · ${p.state.title}` : ""}`;
    case "conversation":
      return `conversation “${p.title}”`;
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
        <Typography variant="caption">Input</Typography>
        <Box component="pre" sx={{ m: 0, p: 1, bgcolor: "action.hover", borderRadius: 1, overflowX: "auto", fontSize: "0.8rem" }}>
          {JSON.stringify(p.state?.input, null, 2)}
        </Box>
        <Typography variant="caption">Output</Typography>
        <Box component="pre" sx={{ m: 0, p: 1, bgcolor: "action.hover", borderRadius: 1, overflowX: "auto", maxHeight: 300, fontSize: "0.8rem" }}>
          {String(p.state?.output ?? p.state?.error ?? "").slice(0, 20000)}
        </Box>
      </Stack>
    );
  }
  return (
    <Box component="pre" sx={{ m: 0, overflowX: "auto", fontSize: "0.8rem" }}>
      {JSON.stringify(p, null, 2)}
    </Box>
  );
}

const COLORS: Record<string, "primary" | "secondary" | "success" | "default" | "info"> = {
  message: "primary",
  text: "success",
  tool: "secondary",
  reasoning: "info",
};

export default function AuditTimeline({ sessionId }: { sessionId: string }) {
  const [events, setEvents] = useState<AuditEvent[]>([]);
  const [verification, setVerification] = useState<{ valid: boolean; checked: number } | null>(null);

  useEffect(() => {
    api.audit(sessionId).then(setEvents);
    api.verifyAudit(sessionId).then(setVerification);
  }, [sessionId]);

  return (
    <Stack spacing={2}>
      <Stack direction="row" spacing={2} sx={{ alignItems: "center", flexWrap: "wrap" }} useFlexGap>
        {verification && (
          <Alert icon={<VerifiedIcon />} severity={verification.valid ? "success" : "error"} sx={{ flexGrow: 1 }}>
            {verification.valid
              ? `Hash chain intact: ${verification.checked} events recorded on the host, append-only.`
              : "Hash chain broken: the audit trail was modified."}
          </Alert>
        )}
        <Button startIcon={<DownloadIcon />} href={`/api/sessions/${sessionId}/audit/export`}>
          Export JSONL
        </Button>
      </Stack>
      {events.length === 0 && <Typography color="text.secondary">No events recorded yet.</Typography>}
      <Box>
        {events.map((event) => (
          <Accordion key={event.seq} disableGutters variant="outlined">
            <AccordionSummary expandIcon={<ExpandMoreIcon />}>
              <Stack direction="row" spacing={1.5} sx={{ alignItems: "center", minWidth: 0 }}>
                <Typography variant="caption" color="text.secondary" sx={{ minWidth: 72 }}>
                  {new Date(event.ts).toLocaleTimeString()}
                </Typography>
                <Chip size="small" label={event.type} color={COLORS[event.type] ?? "default"} variant="outlined" />
                <Typography variant="body2" noWrap>
                  {summary(event)}
                </Typography>
              </Stack>
            </AccordionSummary>
            <AccordionDetails>
              <Detail event={event} />
              <Typography variant="caption" color="text.secondary" sx={{ fontFamily: "monospace", display: "block", mt: 1 }}>
                #{event.seq} · sha256 {event.hash.slice(0, 16)}…
              </Typography>
            </AccordionDetails>
          </Accordion>
        ))}
      </Box>
    </Stack>
  );
}
