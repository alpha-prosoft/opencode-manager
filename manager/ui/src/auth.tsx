import { createContext, useContext } from "react";
import type { Me } from "./api";

export const IdentityContext = createContext<Me | null>(null);

export function useIdentity(): Me {
  const identity = useContext(IdentityContext);
  if (!identity) {
    throw new Error("useIdentity must be used inside an authenticated layout");
  }
  return identity;
}

export function safeNext(next: string | null): string {
  return next && next.startsWith("/") && !next.startsWith("//") ? next : "/";
}

export function isSessionEntry(path: string): boolean {
  return /^\/sessions\/[^/]+\/[^/]+$/.test(path);
}
