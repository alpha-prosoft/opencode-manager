export function relativeTime(value: string | null | undefined): string {
  if (!value) return "—";
  const seconds = Math.round((Date.now() - new Date(value).getTime()) / 1000);
  if (seconds < 60) return "just now";
  const minutes = Math.round(seconds / 60);
  if (minutes < 60) return `${minutes} min ago`;
  const hours = Math.round(minutes / 60);
  if (hours < 24) return `${hours} h ago`;
  return new Date(value).toLocaleDateString();
}

export function tokens(value: number): string {
  if (value >= 1_000_000) return `${(value / 1_000_000).toFixed(1)}M`;
  if (value >= 1_000) return `${(value / 1_000).toFixed(1)}k`;
  return String(value);
}

export function triggerLabel(trigger: Record<string, any>): string {
  if (trigger.type === "manual") return `manual · ${trigger.by}`;
  if (trigger.type === "pull_request") return `PR #${trigger.pull_request?.id} · ${trigger.scm?.repository}`;
  return trigger.type;
}
