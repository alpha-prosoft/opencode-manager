import { Box, Paper, Typography } from "@mui/material";
import type { Usage } from "../api";
import { tokens } from "./format";

function Stat({ label, value }: { label: string; value: string }) {
  return (
    <Paper variant="outlined" sx={{ p: 1.5, minWidth: 120, flex: 1 }}>
      <Typography variant="caption" color="text.secondary">
        {label}
      </Typography>
      <Typography variant="h6">{value}</Typography>
    </Paper>
  );
}

export default function UsageStats({ usage }: { usage: Usage }) {
  return (
    <Box sx={{ display: "flex", gap: 1.5, flexWrap: "wrap" }}>
      <Stat label="LLM calls" value={String(usage.calls)} />
      <Stat label="Prompt tokens" value={tokens(usage.prompt_tokens)} />
      <Stat label="Completion tokens" value={tokens(usage.completion_tokens)} />
      <Stat label="Cached tokens" value={tokens(usage.cached_tokens)} />
      <Stat label="Avg latency" value={`${(usage.avg_latency_ms / 1000).toFixed(1)} s`} />
      {usage.errors > 0 && <Stat label="Errors" value={String(usage.errors)} />}
    </Box>
  );
}
