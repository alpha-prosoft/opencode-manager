export type SessionStatus = "running" | "starting" | "stopped" | "missing" | "error";

export interface SessionAttributes {
  name: string;
  description: string;
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
  created_at: string;
  last_activity_at: string;
  stopped_at: string | null;
}

export interface Identity {
  sub: string;
  email: string;
  name: string | null;
}

const BASE = "/api";

async function request<T>(path: string, init?: RequestInit): Promise<T> {
  const response = await fetch(`${BASE}${path}`, {
    credentials: "include",
    headers: { "Content-Type": "application/json" },
    ...init,
  });
  if (!response.ok) {
    throw new Error(`${response.status} ${response.statusText}`);
  }
  return response.status === 204 ? (undefined as T) : response.json();
}

export const api = {
  me: () => request<Identity>("/me"),
  listSessions: () => request<Session[]>("/sessions"),
  getSession: (id: string) => request<Session>(`/sessions/${id}`),
  createSession: (data: SessionAttributes) =>
    request<Session>("/sessions", { method: "POST", body: JSON.stringify(data) }),
  startSession: (id: string) => request<Session>(`/sessions/${id}/start`, { method: "POST" }),
  stopSession: (id: string) => request<Session>(`/sessions/${id}/stop`, { method: "POST" }),
  deleteSession: (id: string) => request<void>(`/sessions/${id}`, { method: "DELETE" }),
};
