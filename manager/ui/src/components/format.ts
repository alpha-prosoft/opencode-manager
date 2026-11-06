export function relativeTime(value: string | null | undefined): string {
  if (!value) return "—";
  const seconds = Math.round((Date.now() - new Date(value).getTime()) / 1000);
  if (seconds < 0) return `in ${Math.round(-seconds / 60)} min`;
  if (seconds < 60) return "just now";
  const minutes = Math.round(seconds / 60);
  if (minutes < 60) return `${minutes} min ago`;
  const hours = Math.round(minutes / 60);
  if (hours < 24) return `${hours} h ago`;
  return new Date(value).toLocaleDateString();
}

export function dateTime(value: string | null | undefined): string {
  return value ? new Date(value).toLocaleString() : "—";
}

export function tokens(value: number | null | undefined): string {
  const n = value ?? 0;
  if (n >= 1_000_000) return `${(n / 1_000_000).toFixed(1)}M`;
  if (n >= 1_000) return `${(n / 1_000).toFixed(1)}k`;
  return String(n);
}

export function money(value: number | null | undefined): string {
  return value === null || value === undefined ? "—" : `$${value.toFixed(2)}`;
}

export function triggerLabel(trigger: Record<string, any>): string {
  if (trigger.retry_of) return `Re-run by ${trigger.by}`;
  if (trigger.type === "manual") return `Started by ${trigger.by}`;
  if (trigger.type === "pull_request") return `Pull request #${trigger.pull_request?.id} in ${trigger.scm?.repository}`;
  if (trigger.type === "schedule") return "Schedule";
  return trigger.type;
}

export function workflowTrigger(spec: Record<string, any>): string {
  const t = spec.trigger ?? { type: "manual" };
  if (t.type === "pull_request") return `Runs on new pull requests in ${t.repository}`;
  if (t.type === "schedule") return `Runs every ${t.every_minutes} min`;
  return "Started by hand";
}

export function isActive(status: string): boolean {
  return !["completed", "failed", "cancelled"].includes(status);
}

export function isSessionLive(status: string): boolean {
  return status === "running" || status === "starting";
}
