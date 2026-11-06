import { useState } from "react";
import { Link as RouterLink, useNavigate, useParams } from "react-router";
import { Alert, Box, Button, Link, List, ListItem, ListItemText, Paper, Stack, Step, StepLabel, Stepper, Typography } from "@mui/material";
import OpenInNewIcon from "@mui/icons-material/OpenInNew";
import ReplayIcon from "@mui/icons-material/Replay";
import StopIcon from "@mui/icons-material/Stop";
import { api, type RunDetail, type RunStatus } from "../api";
import { ActivityChip, RunStatusChip, SessionStatusChip } from "../components/Chips";
import { useConfirm } from "../components/ConfirmDialog";
import { DetailList, Mono, TwoColumns } from "../components/Data";
import BuildPanel from "../components/BuildPanel";
import Markdown from "../components/Markdown";
import { PageHeader, PageStack, Section } from "../components/Page";
import PatchView from "../components/PatchView";
import ReviewResult from "../components/ReviewResult";
import UsageStats from "../components/UsageStats";
import { dateTime, isActive, relativeTime, triggerLabel } from "../components/format";
import { useLoad } from "../hooks";

const AUTOMATED_STEPS = ["Queued", "Starting sandbox", "Agent working", "Collecting result", "Done"];
const CI_STEPS = ["Queued", "Starting sandbox", "Agent working", "Collecting result", "Following build", "Done"];
const SESSION_STEPS = ["Queued", "Starting sandbox", "Ready"];
const STEP_OF: Record<RunStatus, number> = {
  queued: 0,
  preparing: 1,
  running: 2,
  awaiting_result: 2,
  finalizing: 3,
  following_ci: 4,
  completed: 6,
  failed: -1,
  cancelled: -1,
};

function Progress({ run }: { run: RunDetail }) {
  const interactive = run.activity === "session";
  const steps = interactive ? SESSION_STEPS : run.status === "following_ci" || run.result?.ci ? CI_STEPS : AUTOMATED_STEPS;
  const step = interactive ? Math.min(STEP_OF[run.status], 1) : STEP_OF[run.status];
  return (
    <Paper sx={{ p: 2.5 }}>
      <Stepper activeStep={step} alternativeLabel>
        {steps.map((label) => (
          <Step key={label}>
            <StepLabel>{label}</StepLabel>
          </Step>
        ))}
      </Stepper>
      <Typography variant="body2" color="text.secondary" sx={{ mt: 2, textAlign: "center" }}>
        {run.status === "awaiting_result"
          ? "The agent is working in its sandbox. You can watch it live with Open session. This page updates by itself."
          : run.status === "following_ci"
            ? "The pull request is pushed. The platform checks its build every minute until it passes or fails."
            : "This page updates by itself."}
      </Typography>
    </Paper>
  );
}

function Implementation({ run }: { run: RunDetail }) {
  const result = run.result!;
  const impl = result.implementation;
  return (
    <Stack spacing={2}>
      {result.pull_request && (
        <Alert severity={result.pull_request.draft ? "warning" : "success"}>
          {result.pull_request.updated ? "Pushed to pull request " : result.pull_request.draft ? "Draft pull request " : "Pull request "}
          <Link href={result.pull_request.url} target="_blank" rel="noopener">
            #{result.pull_request.id}
          </Link>
          {result.pull_request.updated ? " " : " opened "}(<Mono>{result.pull_request.branch}</Mono> into{" "}
          <Mono>{result.pull_request.target}</Mono>). It is not merged.
          {result.pull_request.draft && " It's a draft because the agent couldn't verify the change."}
        </Alert>
      )}
      <Markdown>{impl.summary}</Markdown>
      {impl.changes.length > 0 && (
        <Box>
          <Typography variant="subtitle2">Changes</Typography>
          <List dense disablePadding>
            {impl.changes.map((change: string) => (
              <ListItem key={change} disableGutters>
                <ListItemText primary={`• ${change}`} />
              </ListItem>
            ))}
          </List>
        </Box>
      )}
      {impl.tests.length > 0 && (
        <Alert severity="info">
          <strong>How the agent verified it:</strong> {impl.tests.join("; ")}
        </Alert>
      )}
      {impl.notes && <Alert severity="warning">{impl.notes}</Alert>}
      <Typography variant="subtitle2">
        Patch: {result.files.length} file{result.files.length === 1 ? "" : "s"} changed
      </Typography>
      <PatchView patch={result.patch} name={`run-${run.id}.patch`} />
    </Stack>
  );
}

function Result({ run }: { run: RunDetail }) {
  if (!run.result) return null;
  if (run.activity === "review") return <ReviewResult result={run.result} />;
  if (run.activity === "analysis") return <Markdown>{run.result.document.markdown}</Markdown>;
  if (run.activity === "implement") return <Implementation run={run} />;
  return null;
}

export default function RunPage() {
  const { runId = "" } = useParams();
  const navigate = useNavigate();
  const { data: run, error, reload } = useLoad(() => api.run(runId), [runId], 4000);
  const [actionError, setActionError] = useState<string | null>(null);
  const { confirm, dialog } = useConfirm();
  if (error) return <Alert severity="error">{error}</Alert>;
  if (!run) return null;

  const active = isActive(run.status);
  const act = (action: () => Promise<unknown>) => action().catch((e: Error) => setActionError(e.message));
  const cancel = () =>
    confirm({
      title: "Cancel this run?",
      body: "The agent is stopped and no result is collected. You can run it again later with the same inputs.",
      confirm: "Cancel run",
      action: () => act(() => api.cancelRun(run.id).then(reload)),
    });
  const inputs = Object.entries(run.inputs).filter(([, value]) => value !== null && value !== "");

  return (
    <PageStack>
      <PageHeader
        back={{ to: "/runs", label: "Runs" }}
        title={run.workflow_name ?? run.workflow_id}
        meta={
          <>
            <RunStatusChip status={run.status} />
            <ActivityChip activity={run.activity} />
            <Typography variant="body2">
              {triggerLabel(run.trigger)} · {relativeTime(run.created_at)}
            </Typography>
          </>
        }
        actions={
          <>
            {run.session && (
              <Button
                variant={run.activity === "session" ? "contained" : "outlined"}
                startIcon={<OpenInNewIcon />}
                href={run.session.open_url}
                target="_blank"
                rel="noopener"
              >
                Open session
              </Button>
            )}
            {active && (
              <Button color="warning" startIcon={<StopIcon />} onClick={cancel}>
                Cancel run
              </Button>
            )}
            {!active && run.activity !== "session" && (
              <Button
                startIcon={<ReplayIcon />}
                onClick={() => act(() => api.retryRun(run.id).then((next) => navigate(`/runs/${next.id}`)))}
              >
                Run again
              </Button>
            )}
          </>
        }
      />
      {dialog}
      {active && <Progress run={run} />}
      {run.error && (
        <Alert severity={run.status === "cancelled" ? "warning" : "error"}>
          {run.status === "cancelled" ? run.error : `The run failed: ${run.error}`}
        </Alert>
      )}
      {actionError && <Alert severity="error">{actionError}</Alert>}

      {run.result && (
        <Section title="Result">
          <Paper sx={{ p: { xs: 2, md: 3 } }}>
            <Result run={run} />
          </Paper>
        </Section>
      )}

      {run.result?.ci && (
        <Section title="Build" description="Read from the CI system through its MCP server.">
          <BuildPanel ci={run.result.ci} />
        </Section>
      )}

      <TwoColumns>
        <Section title="Inputs">
          <DetailList items={inputs.length ? inputs.map(([key, value]) => [key.replace(/_/g, " "), String(value)]) : [["", "No inputs"]]} />
        </Section>
        <Section title="Details">
          <DetailList
            items={[
              [
                "Team",
                <Link component={RouterLink} to={`/teams/${run.team_id}`}>
                  {run.team_id}
                </Link>,
              ],
              ["Started", dateTime(run.created_at)],
              ["Run ID", <Mono>{run.id}</Mono>],
              ...(run.session
                ? ([
                    [
                      "Session",
                      <Stack direction="row" spacing={1} sx={{ alignItems: "center" }}>
                        <Link component={RouterLink} to={`/sessions/${run.session.id}`}>
                          {run.session.name}
                        </Link>
                        <SessionStatusChip status={run.session.status} />
                      </Stack>,
                    ],
                  ] as [string, React.ReactNode][])
                : []),
            ]}
          />
        </Section>
      </TwoColumns>

      {run.usage.calls > 0 && (
        <Section title="LLM usage" description="Measured by the platform's gateway, not reported by the agent.">
          <UsageStats usage={run.usage} />
        </Section>
      )}
    </PageStack>
  );
}
