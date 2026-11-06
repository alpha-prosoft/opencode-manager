import { Chip, type ChipProps } from "@mui/material";
import BuildIcon from "@mui/icons-material/Build";
import DescriptionIcon from "@mui/icons-material/Description";
import GroupsIcon from "@mui/icons-material/Groups";
import LockIcon from "@mui/icons-material/Lock";
import PublicIcon from "@mui/icons-material/Public";
import RateReviewIcon from "@mui/icons-material/RateReview";
import TerminalIcon from "@mui/icons-material/Terminal";
import WidgetsIcon from "@mui/icons-material/Widgets";
import type { RunStatus, SessionStatus, Visibility } from "../api";

const SESSION_COLORS: Record<SessionStatus, ChipProps["color"]> = {
  running: "success",
  starting: "info",
  stopped: "default",
  error: "error",
};

const RUN_COLORS: Record<RunStatus, ChipProps["color"]> = {
  queued: "default",
  preparing: "info",
  running: "info",
  awaiting_result: "info",
  finalizing: "info",
  completed: "success",
  failed: "error",
  cancelled: "warning",
};

export const ACTIVITY_ICONS: Record<string, typeof TerminalIcon> = {
  session: TerminalIcon,
  review: RateReviewIcon,
  implement: BuildIcon,
  analysis: DescriptionIcon,
};

export function SessionStatusChip({ status }: { status: SessionStatus }) {
  return <Chip size="small" label={status} color={SESSION_COLORS[status]} />;
}

export function RunStatusChip({ status }: { status: RunStatus }) {
  return <Chip size="small" label={status.replace("_", " ")} color={RUN_COLORS[status]} variant={status === "completed" ? "outlined" : "filled"} />;
}

export function VisibilityChip({ visibility }: { visibility: Visibility }) {
  const icon = { private: <LockIcon />, team: <GroupsIcon />, public: <PublicIcon /> }[visibility];
  return <Chip size="small" variant="outlined" icon={icon} label={visibility} />;
}

export function ActivityChip({ activity }: { activity: string }) {
  const Icon = ACTIVITY_ICONS[activity] ?? WidgetsIcon;
  return <Chip size="small" variant="outlined" color="primary" icon={<Icon />} label={activity} />;
}
