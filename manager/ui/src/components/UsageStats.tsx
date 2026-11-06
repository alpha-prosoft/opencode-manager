import type { Usage } from "../api";
import { Stat, StatGrid } from "./Data";
import { tokens } from "./format";

export default function UsageStats({ usage }: { usage: Usage }) {
  return (
    <StatGrid>
      <Stat label="LLM calls" value={usage.calls} />
      <Stat label="Prompt tokens" value={tokens(usage.prompt_tokens)} hint={`${tokens(usage.cached_tokens)} from cache`} />
      <Stat label="Completion tokens" value={tokens(usage.completion_tokens)} />
      <Stat label="Average latency" value={`${(usage.avg_latency_ms / 1000).toFixed(1)} s`} />
      {usage.errors > 0 && <Stat label="Failed calls" value={usage.errors} />}
    </StatGrid>
  );
}
