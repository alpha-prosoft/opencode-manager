export type SessionStatus = "running" | "starting" | "stopped" | "error";
export type Visibility = "private" | "team" | "public";
export type RunStatus =
  "queued" | "preparing" | "running" | "awaiting_result" | "finalizing" | "following_ci" | "completed" | "failed" | "cancelled";

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
  can_open: boolean;
  open_blocked: "locked" | "personal" | "private" | "hidden" | null;
  personal: boolean;
  locked_by: string | null;
  locked_at: string | null;
  forked_from: string | null;
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
  repository: { type: "git" | "url" | "path"; url?: string; location?: string; branch?: string; path: string };
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
  outcome?: RunOutcome | null;
}

export interface RunOutcome {
  pull_request?: { id: number; url: string; draft?: boolean; updated?: boolean };
  build?: { state: BuildState; number: number | null; follow_up_run?: string | null };
  verdict?: string;
}

export type BuildState = "waiting" | "running" | "passed" | "failed" | "timeout";

export interface BuildInfo {
  job: string;
  state: BuildState;
  detail?: string | null;
  checked_at?: string;
  follow_up_run?: string | null;
  build: {
    number: number;
    result: string | null;
    building: boolean;
    url: string;
    duration_ms: number | null;
    stages: { name: string; status: string; duration_ms: number | null }[] | null;
  } | null;
}

export interface OpenPullRequest {
  id: number;
  title: string;
  author: string;
  branch: string;
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

export interface Persona {
  username: string;
  roles: string[];
}

export interface ActivityEvent {
  id: number;
  ts: string;
  kind: "tool.start" | "tool.end" | "tool.blocked" | "event";
  tool: string | null;
  call_id: string | null;
  duration_ms: number | null;
  detail: Record<string, any>;
}

export interface SessionActivity {
  state: "busy" | "idle" | "unknown";
  current: ActivityEvent | null;
  events: ActivityEvent[];
  tools: { tool: string; calls: number; avg_ms: number; max_ms: number }[];
  blocked: number;
  files_edited: string[];
  last_seen: string | null;
}

export interface AdminOverview {
  status: { commit: string | null; synced_at: string | null; error: string | null };
  teams: number;
  users: number;
  sessions: { total: number; running: number };
  runs_today: Record<string, number>;
  tokens_today: number;
  cost_today: number | null;
}

export interface AdminUser {
  username: string;
  platform_admin: boolean;
  teams: Record<string, "admin" | "member">;
  runs_30d: number;
  tokens_30d: number;
}

export interface UsageRow {
  team_id: string;
  connection: string;
  model: string;
  calls: number;
  prompt_tokens: number;
  completion_tokens: number;
  cached_tokens: number;
  errors: number;
  cost: number | null;
}

export type ProvidedBy = "platform" | "platform_or_user" | "user";

export interface CredentialStatus {
  id: string;
  name: string;
  description: string;
  kind: "token" | "basic" | "ssh_key";
  hosts: string[];
  provided_by: ProvidedBy;
  help: string;
  test: { url: string } | null;
  state: "own" | "platform" | "missing";
  required: boolean;
  hint: string | null;
  updated_at: string | null;
  verified_at: string | null;
}

export interface AdminCredential {
  id: string;
  name: string;
  description: string;
  kind: CredentialStatus["kind"];
  provided_by: ProvidedBy;
  declared: boolean;
  hosts: string[];
  platform_value: "admin" | "file" | null;
  hint: string | null;
  updated_at: string | null;
  updated_by: string | null;
  personal_values: number;
  used_by: string[];
}

export interface McpServerStatus {
  id: string;
  name: string;
  description: string;
  url: string;
  credential: string | null;
  tools: string[] | null;
  reachable: boolean;
  detail: string;
  harnesses: string[];
  credential_ready: boolean;
}

export interface HarnessCard {
  team_id: string;
  team_name: string;
  id: string;
  valid: boolean;
  errors: string[];
  description: string;
  network: "none" | "internet";
  models: string[];
  skills: string[];
  mcp_servers: { id: string; name: string; description: string; tools: string[] | null }[];
  credentials: { id: string; name: string; provided_by: ProvidedBy; source: "user" | "platform" | null }[];
  ready: boolean;
  workflows: { id: string; name: string }[];
}

export interface Conversation {
  id: string;
  title: string;
  messages: number;
  started: string | null;
  last: string | null;
  open_path: string;
}

export interface CredentialInput {
  secret: string;
  username?: string;
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

const put = <T>(path: string, body: unknown) => request<T>(path, { method: "PUT", body: JSON.stringify(body) });

export const api = {
  login: (username: string) => post<Me>("/auth/login", { username }),
  personas: () => request<Persona[]>("/auth/personas"),
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
  activity: (id: string) => request<SessionActivity>(`/sessions/${id}/activity`),
  conversations: (id: string) => request<Conversation[]>(`/sessions/${id}/conversations`),
  forkSession: (id: string) => post<Session>(`/sessions/${id}/fork`),
  lockSession: (id: string) => post<Session>(`/sessions/${id}/lock`),
  harnesses: () => request<HarnessCard[]>("/harnesses"),
  myCredentials: () => request<{ credentials: CredentialStatus[]; pending: string[] }>("/me/credentials"),
  setMyCredential: (id: string, value: CredentialInput) => put<{ message: string }>(`/me/credentials/${id}`, value),
  deleteMyCredential: (id: string) => request<void>(`/me/credentials/${id}`, { method: "DELETE" }),
  markCredentialsSeen: (ids: string[]) => post<void>("/me/credentials/seen", { credentials: ids }),
  cancelRun: (id: string) => post<Run>(`/runs/${id}/cancel`),
  openPullRequests: (team: string, workflow: string) => request<OpenPullRequest[]>(`/teams/${team}/workflows/${workflow}/pull-requests`),
  retryRun: (id: string) => post<Run>(`/runs/${id}/retry`),
  admin: {
    overview: () => request<AdminOverview>("/admin/overview"),
    users: () => request<AdminUser[]>("/admin/users"),
    usage: (days: number) => request<UsageRow[]>(`/admin/usage?days=${days}`),
    testConnection: (id: string) => post<{ ok: boolean; model?: string; latency_ms?: number; error?: string }>(`/admin/llm/${id}/test`),
    schedules: () => request<{ id: string; next_run: string | null; last_run: string | null; paused: boolean }[]>("/admin/schedules"),
    stopIdle: () => post<{ stopped: string[] }>("/admin/sessions/stop-idle"),
    credentials: () => request<AdminCredential[]>("/admin/credentials"),
    setCredential: (id: string, value: CredentialInput) => put<{ message: string }>(`/admin/credentials/${id}`, value),
    deleteCredential: (id: string) => request<void>(`/admin/credentials/${id}`, { method: "DELETE" }),
    mcpServers: () => request<McpServerStatus[]>("/admin/mcp-servers"),
  },
  audit: (id: string, after = 0) => request<AuditEvent[]>(`/sessions/${id}/audit?after=${after}&limit=500`),
  verifyAudit: (id: string) => request<{ valid: boolean; checked: number; broken_at?: number }>(`/sessions/${id}/audit/verify`),
};
