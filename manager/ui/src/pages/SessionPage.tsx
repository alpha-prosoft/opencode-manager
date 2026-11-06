import { useState } from "react";
import { Link as RouterLink, useNavigate, useParams } from "react-router";
import { Alert, Box, Link, MenuItem, Stack, Tab, Tabs, TextField, Typography } from "@mui/material";
import { api, type Visibility } from "../api";
import AuditTimeline from "../components/AuditTimeline";
import { ActivityChip, SessionStatusChip, VisibilityChip } from "../components/Chips";
import { DetailList, Mono, TwoColumns } from "../components/Data";
import LiveActivity from "../components/LiveActivity";
import { PageHeader, PageStack, Section } from "../components/Page";
import SessionActions from "../components/SessionActions";
import UsageStats from "../components/UsageStats";
import { dateTime, isSessionLive, relativeTime } from "../components/format";
import { useLoad } from "../hooks";

const SHARING: Record<Visibility, string> = {
  private: "Only you (and team admins)",
  team: "Everyone in the team",
  public: "Everyone on the platform",
};

export default function SessionPage() {
  const { sessionId = "" } = useParams();
  const navigate = useNavigate();
  const { data: session, error, reload } = useLoad(() => api.session(sessionId), [sessionId], 5000);
  const [tab, setTab] = useState(0);
  const [actionError, setActionError] = useState<string | null>(null);

  if (error) return <Alert severity="error">{error}</Alert>;
  if (!session) return null;

  const setVisibility = (visibility: Visibility) =>
    api
      .updateSession(session.id, { visibility })
      .then(reload)
      .catch((e: Error) => setActionError(e.message));

  return (
    <PageStack>
      <PageHeader
        back={{ to: "/sessions", label: "Sessions" }}
        title={session.name}
        description={session.description || undefined}
        meta={
          <>
            <SessionStatusChip status={session.status} />
            {session.activity && <ActivityChip activity={session.activity} />}
            <VisibilityChip visibility={session.visibility} />
            <Typography variant="body2">Last activity {relativeTime(session.last_activity_at)}</Typography>
          </>
        }
        actions={
          <SessionActions session={session} prominent onChange={reload} onError={setActionError} onDeleted={() => navigate("/sessions")} />
        }
      />
      {actionError && <Alert severity="error">{actionError}</Alert>}

      <Box>
        <Tabs value={tab} onChange={(_, value) => setTab(value)} variant="scrollable">
          <Tab label="Overview" />
          <Tab label="Live activity" />
          <Tab label={`Audit trail (${session.audit_events})`} />
        </Tabs>
      </Box>

      {tab === 0 && (
        <Stack spacing={4}>
          <TwoColumns>
            <Section title="Details">
              <DetailList
                items={[
                  ["Owner", session.is_owner ? "You" : session.sub],
                  [
                    "Team",
                    session.team_id ? (
                      <Link component={RouterLink} to={`/teams/${session.team_id}`}>
                        {session.team_id}
                      </Link>
                    ) : (
                      "—"
                    ),
                  ],
                  [
                    "Started by run",
                    session.run_id ? (
                      <Link component={RouterLink} to={`/runs/${session.run_id}`}>
                        <Mono>{session.run_id}</Mono>
                      </Link>
                    ) : (
                      "—"
                    ),
                  ],
                  ["Created", dateTime(session.created_at)],
                  ["Address", <Mono>{session.url}</Mono>],
                ]}
              />
            </Section>
            <Section title="Sharing" description="Who can see and open this session.">
              {session.can_manage ? (
                <TextField select fullWidth value={session.visibility} onChange={(e) => setVisibility(e.target.value as Visibility)}>
                  {(Object.keys(SHARING) as Visibility[]).map((option) => (
                    <MenuItem key={option} value={option}>
                      {SHARING[option]}
                    </MenuItem>
                  ))}
                </TextField>
              ) : (
                <Typography>{SHARING[session.visibility]}</Typography>
              )}
            </Section>
          </TwoColumns>
          {session.usage.calls > 0 && (
            <Section title="LLM usage" description="Measured by the platform's gateway, not reported by the agent.">
              <UsageStats usage={session.usage} />
            </Section>
          )}
        </Stack>
      )}
      {tab === 1 && <LiveActivity sessionId={session.id} live={isSessionLive(session.status)} />}
      {tab === 2 && <AuditTimeline sessionId={session.id} />}
    </PageStack>
  );
}
