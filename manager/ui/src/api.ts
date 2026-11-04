export type SessionStatus = "running" | "starting" | "stopped" | "error";
export type Visibility = "private" | "public";

export interface SessionAttributes {
  name: string;
  description: string;
  visibility: Visibility;
  labels: Record<string, string>;
  env: Record<string, string>;
}

export interface Session extends SessionAttributes {
  id: string;
  sub: string;
  email: string;
  image: string;
  status: SessionStatus;
  url: string;
  open_url: string;
  is_owner: boolean;
  created_at: string;
  last_activity_at: string;
  stopped_at: string | null;
}

export interface Identity {
  sub: string;
  email: string;
  name: string | null;
}

export class ApiError extends Error {
  constructor(
    readonly status: number,
    message: string,
  ) {
    super(message);
  }
}

const BASE = "/api";

async function request<T>(path: string, init?: RequestInit): Promise<T> {
  const response = await fetch(`${BASE}${path}`, {
    credentials: "include",
    headers: { "Content-Type": "application/json" },
    ...init,
  });
  if (!response.ok) {
    const body = await response.json().catch(() => null);
    throw new ApiError(response.status, body?.detail ?? `${response.status} ${response.statusText}`);
  }
  return response.status === 204 ? (undefined as T) : response.json();
}

export const api = {
  login: (username: string) =>
    request<Identity>("/auth/login", { method: "POST", body: JSON.stringify({ username }) }),
  logout: () => request<void>("/auth/logout", { method: "POST" }),
  me: () => request<Identity>("/me"),
  listSessions: () => request<Session[]>("/sessions"),
  getSession: (id: string) => request<Session>(`/sessions/${id}`),
  createSession: (data: SessionAttributes) =>
    request<Session>("/sessions", { method: "POST", body: JSON.stringify(data) }),
  updateSession: (id: string, data: Partial<SessionAttributes>) =>
    request<Session>(`/sessions/${id}`, { method: "PATCH", body: JSON.stringify(data) }),
  startSession: (id: string) => request<Session>(`/sessions/${id}/start`, { method: "POST" }),
  stopSession: (id: string) => request<Session>(`/sessions/${id}/stop`, { method: "POST" }),
  deleteSession: (id: string) => request<void>(`/sessions/${id}`, { method: "DELETE" }),
};
