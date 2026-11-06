import { Link as RouterLink } from "react-router";
import { Alert, Box, Chip, Link, Paper, Stack, Typography, type ChipProps } from "@mui/material";
import type { BuildInfo, BuildState } from "../api";
import { Mono, Row } from "./Data";
import { relativeTime } from "./format";

const STATE: Record<BuildState, [string, ChipProps["color"]]> = {
  waiting: ["Waiting for the build", "default"],
  running: ["Building", "info"],
  passed: ["Build passed", "success"],
  failed: ["Build failed", "error"],
  timeout: ["Stopped waiting", "warning"],
};

const STAGE_COLOR: Record<string, ChipProps["color"]> = {
  SUCCESS: "success",
  FAILED: "error",
  IN_PROGRESS: "info",
  PAUSED_PENDING_INPUT: "warning",
  ABORTED: "warning",
};

export function BuildChip({ state }: { state: BuildState }) {
  const [label, color] = STATE[state];
  return <Chip variant="outlined" color={color} label={label} />;
}

export default function BuildPanel({ ci }: { ci: BuildInfo }) {
  const build = ci.build;
  return (
    <Paper sx={{ p: 2 }}>
      <Stack spacing={1.5}>
        <Row>
          <BuildChip state={ci.state} />
          {build && (
            <Link href={build.url} target="_blank" rel="noopener">
              Build #{build.number}
            </Link>
          )}
          <Typography variant="body2" color="text.secondary">
            <Mono>{ci.job}</Mono> · checked {relativeTime(ci.checked_at)}
          </Typography>
        </Row>
        {ci.detail && (
          <Typography variant="body2" color="text.secondary">
            {ci.detail}
          </Typography>
        )}
        {build?.stages && build.stages.length > 0 && (
          <Box>
            <Row>
              {build.stages.map((stage) => (
                <Chip key={stage.name} variant="outlined" color={STAGE_COLOR[stage.status] ?? "default"} label={stage.name} />
              ))}
            </Row>
          </Box>
        )}
        {ci.follow_up_run && (
          <Alert severity="info">
            The build failed, so an analysis was started:{" "}
            <Link component={RouterLink} to={`/runs/${ci.follow_up_run}`}>
              open the failure analysis
            </Link>
            .
          </Alert>
        )}
      </Stack>
    </Paper>
  );
}
