export type SessionStatus = "running" | "starting" | "stopped" | "error";
export type Visibility = "private" | "team" | "public";
export type RunStatus = "queued" | "preparing" | "running" | "awaiting_result" | "finalizing" | "completed" | "failed";

export interface Usage {
  calls: number;
  prompt_tokens: number;
  completion_tokens: number;
  cached_tokens: number;
  errors: number;
  avg_latency_ms: number;
}

export interface Session {
  id: string;
  sub: string;
  name: string;
  description: string;
  visibility: Visibility;
  labels: Record<string, string>;
  team_id: string | null;
  run_id: string | null;
  activity: string | null;
  status: SessionStatus;
  url: string;
  open_url: string;
  is_owner: boolean;
  can_manage: boolean;
  created_at: string;
  last_activity_at: string;
  stopped_at: string | null;
}

export interface SessionDetail extends Session {
  usage: Usage;
  audit_events: number;
}

export interface Me {
  sub: string;
  email: string;
  name: string | null;
  platform_admin: boolean;
  teams: { id: string; role: "admin" | "member" }[];
}

export interface JsonSchema {
  type?: string;
  title?: string;
  description?: string;
  default?: unknown;
  enum?: string[];
  properties?: Record<string, JsonSchema>;
  required?: string[];
  anyOf?: JsonSchema[];
  minLength?: number;
  maxLength?: number;
  minimum?: number;
  maximum?: number;
  $ref?: string;
  $defs?: Record<string, JsonSchema>;
  const?: string;
}

export interface Activity {
  id: string;
  name: string;
  category: string;
  description: string;
  icon: string;
  interactive: boolean;
  result_tool: string | null;
  options_schema: JsonSchema;
  inputs_schema: JsonSchema;
}

export interface TeamSummary {
  id: string;
  name: string;
  description: string;
  repository: { url: string; branch: string; path: string };
  commit: string | null;
  synced_at: string | null;
  status: string;
  error: string | null;
  role: "admin" | "member" | null;
  counts: { agents: number; harnesses: number; workflows: number };
  invalid: number;
}

export interface Definition {
  kind: "agents" | "harnesses" | "workflows";
  id: string;
  source: string;
  spec: Record<string, any> | null;
  errors: string[];
  valid: boolean;
}

export interface TeamDetail extends TeamSummary {
  members: string[];
  admins: string[];
  secrets: string[];
  definitions: Definition[];
  usage: Usage;
  usage_by_model: { model: string; calls: number; prompt_tokens: number; completion_tokens: number }[];
  budget: { daily_tokens: number | null; used_today: number };
}

export interface Run {
  id: string;
  team_id: string;
  workflow_id: string;
  activity: string;
  status: RunStatus;
  trigger: Record<string, any>;
  inputs: Record<string, any>;
  session_id: string | null;
  error: string | null;
  created_by: string;
  created_at: string;
  updated_at: string;
  has_result?: boolean;
  workflow_name?: string;
}

export interface RunDetail extends Run {
  result: Record<string, any> | null;
  session: Session | null;
  usage: Usage;
}

export interface PlatformStatus {
  commit: string | null;
  synced_at: string | null;
  error: string | null;
  configured: boolean;
  teams: number;
  llm_connections: { id: string; type: string; models: string[] }[];
  allowed_images: string[];
}

export interface AuditEvent {
  seq: number;
  ts: string;
  type: string;
  key: string;
  hash: string;
  payload: Record<string, any>;
}

export class ApiError extends Error {
  constructor(
    readonly status: number,
    message: string,
  ) {
    super(message);
  }
}

async function request<T>(path: string, init?: RequestInit): Promise<T> {
  const response = await fetch(`/api${path}`, {
    credentials: "include",
    headers: { "Content-Type": "application/json" },
    ...init,
  });
  if (!response.ok) {
    const body = await response.json().catch(() => null);
    const detail = Array.isArray(body?.detail) ? body.detail.map((d: any) => d.msg).join("; ") : body?.detail;
    throw new ApiError(response.status, detail ?? `${response.status} ${response.statusText}`);
  }
  return response.status === 204 ? (undefined as T) : response.json();
}

const post = <T>(path: string, body?: unknown) =>
  request<T>(path, { method: "POST", body: body === undefined ? undefined : JSON.stringify(body) });

export const api = {
  login: (username: string) => post<Me>("/auth/login", { username }),
  logout: () => post<void>("/auth/logout"),
  me: () => request<Me>("/me"),
  platform: () => request<PlatformStatus>("/platform"),
  sync: () => post<Record<string, any>>("/platform/sync"),
  marketplace: () => request<Activity[]>("/marketplace"),
  teams: () => request<TeamSummary[]>("/teams"),
  team: (id: string) => request<TeamDetail>(`/teams/${id}`),
  startRun: (team: string, workflow: string, inputs: Record<string, unknown>) =>
    post<Run>(`/teams/${team}/workflows/${workflow}/runs`, inputs),
  runs: (team?: string) => request<Run[]>(`/runs${team ? `?team=${team}` : ""}`),
  run: (id: string) => request<RunDetail>(`/runs/${id}`),
  sessions: () => request<Session[]>("/sessions"),
  session: (id: string) => request<SessionDetail>(`/sessions/${id}`),
  updateSession: (id: string, data: Partial<Pick<Session, "name" | "description" | "visibility">>) =>
    request<Session>(`/sessions/${id}`, { method: "PATCH", body: JSON.stringify(data) }),
  startSession: (id: string) => post<Session>(`/sessions/${id}/start`),
  stopSession: (id: string) => post<Session>(`/sessions/${id}/stop`),
  deleteSession: (id: string) => request<void>(`/sessions/${id}`, { method: "DELETE" }),
  audit: (id: string, after = 0) => request<AuditEvent[]>(`/sessions/${id}/audit?after=${after}&limit=500`),
  verifyAudit: (id: string) => request<{ valid: boolean; checked: number; broken_at?: number }>(`/sessions/${id}/audit/verify`),
};
