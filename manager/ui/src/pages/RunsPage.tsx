import { useState } from "react";
import { Alert, MenuItem, TextField } from "@mui/material";
import { api, type RunStatus } from "../api";
import { RUN_STATUS } from "../components/Chips";
import { PageHeader, PageStack } from "../components/Page";
import RunsTable from "../components/RunsTable";
import { isActive } from "../components/format";
import { useLoad } from "../hooks";

type Filter = "all" | "active" | RunStatus;

export default function RunsPage() {
  const runs = useLoad(() => api.runs(), [], 5000);
  const [filter, setFilter] = useState<Filter>("all");
  const shown = (runs.data ?? []).filter((run) =>
    filter === "all" ? true : filter === "active" ? isActive(run.status) : run.status === filter,
  );
  return (
    <PageStack>
      <PageHeader
        title="Runs"
        description="Every workflow run in your teams: started by hand, on a schedule, or by a new pull request."
        actions={
          <TextField
            select
            size="small"
            label="Show"
            value={filter}
            onChange={(e) => setFilter(e.target.value as Filter)}
            sx={{ minWidth: 180 }}
          >
            <MenuItem value="all">All runs</MenuItem>
            <MenuItem value="active">In progress</MenuItem>
            {(["completed", "failed", "cancelled"] as const).map((status) => (
              <MenuItem key={status} value={status}>
                {RUN_STATUS[status][0]}
              </MenuItem>
            ))}
          </TextField>
        }
      />
      {runs.error && <Alert severity="error">{runs.error}</Alert>}
      <RunsTable runs={runs.data ? shown : null} empty={filter === "all" ? undefined : "No runs match this filter."} />
    </PageStack>
  );
}
