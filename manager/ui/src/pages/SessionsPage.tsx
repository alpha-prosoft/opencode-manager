import { useState } from "react";
import { Alert, ToggleButton, ToggleButtonGroup } from "@mui/material";
import { api, type Session } from "../api";
import { PageHeader, PageStack } from "../components/Page";
import SessionsTable from "../components/SessionsTable";
import { isSessionLive } from "../components/format";
import { useLoad } from "../hooks";

type Filter = "all" | "mine" | "running";

const FILTERS: Record<Filter, (session: Session) => boolean> = {
  all: () => true,
  mine: (session) => session.is_owner,
  running: (session) => isSessionLive(session.status),
};

export default function SessionsPage() {
  const sessions = useLoad(() => api.sessions(), [], 5000);
  const [filter, setFilter] = useState<Filter>("all");
  const [error, setError] = useState<string | null>(null);

  return (
    <PageStack>
      <PageHeader
        title="Sessions"
        description="A session is one sandboxed OpenCode instance. You see your own, your team's shared ones, and ones shared with everyone."
        actions={
          <ToggleButtonGroup size="small" exclusive value={filter} onChange={(_, value: Filter | null) => value && setFilter(value)}>
            <ToggleButton value="all">All</ToggleButton>
            <ToggleButton value="mine">Mine</ToggleButton>
            <ToggleButton value="running">Running</ToggleButton>
          </ToggleButtonGroup>
        }
      />
      {(error || sessions.error) && <Alert severity="error">{error || sessions.error}</Alert>}
      <SessionsTable
        sessions={sessions.data ? sessions.data.filter(FILTERS[filter]) : null}
        onChange={sessions.reload}
        onError={setError}
        empty={filter === "all" ? undefined : "No sessions match this filter."}
      />
    </PageStack>
  );
}
