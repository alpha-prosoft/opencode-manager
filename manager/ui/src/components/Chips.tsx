import { Chip, type ChipProps } from "@mui/material";
import BuildIcon from "@mui/icons-material/Build";
import CircleIcon from "@mui/icons-material/Circle";
import DescriptionIcon from "@mui/icons-material/Description";
import GroupsIcon from "@mui/icons-material/Groups";
import LockIcon from "@mui/icons-material/Lock";
import PublicIcon from "@mui/icons-material/Public";
import RateReviewIcon from "@mui/icons-material/RateReview";
import TerminalIcon from "@mui/icons-material/Terminal";
import WidgetsIcon from "@mui/icons-material/Widgets";
import type { RunStatus, SessionStatus, Visibility } from "../api";

type Color = ChipProps["color"];

const SESSION_STATUS: Record<SessionStatus, [string, Color]> = {
  running: ["Running", "success"],
  starting: ["Starting", "info"],
  stopped: ["Stopped", "default"],
  error: ["Error", "error"],
};

export const RUN_STATUS: Record<RunStatus, [string, Color]> = {
  queued: ["Queued", "default"],
  preparing: ["Starting sandbox", "info"],
  running: ["Agent working", "info"],
  awaiting_result: ["Agent working", "info"],
  finalizing: ["Collecting result", "info"],
  completed: ["Completed", "success"],
  failed: ["Failed", "error"],
  cancelled: ["Cancelled", "warning"],
};

export const ACTIVITIES: Record<string, { label: string; icon: typeof TerminalIcon }> = {
  session: { label: "Workspace", icon: TerminalIcon },
  review: { label: "Code review", icon: RateReviewIcon },
  implement: { label: "Implementation", icon: BuildIcon },
  analysis: { label: "Analysis", icon: DescriptionIcon },
};

export function activityMeta(activity: string) {
  return ACTIVITIES[activity] ?? { label: activity, icon: WidgetsIcon };
}

function StatusChip({ label, color }: { label: string; color: Color }) {
  return <Chip label={label} color={color} variant="outlined" icon={<CircleIcon sx={{ fontSize: "8px !important" }} />} />;
}

export function SessionStatusChip({ status }: { status: SessionStatus }) {
  const [label, color] = SESSION_STATUS[status];
  return <StatusChip label={label} color={color} />;
}

export function RunStatusChip({ status }: { status: RunStatus }) {
  const [label, color] = RUN_STATUS[status];
  return <StatusChip label={label} color={color} />;
}

const VISIBILITY = {
  private: { icon: <LockIcon />, label: "Private" },
  team: { icon: <GroupsIcon />, label: "Team" },
  public: { icon: <PublicIcon />, label: "Everyone" },
};

export function VisibilityChip({ visibility }: { visibility: Visibility }) {
  const { icon, label } = VISIBILITY[visibility];
  return <Chip variant="outlined" icon={icon} label={label} />;
}

export function ActivityChip({ activity }: { activity: string }) {
  const { label, icon: Icon } = activityMeta(activity);
  return <Chip variant="outlined" icon={<Icon />} label={label} />;
}

export function RoleChip({ role }: { role: string | null }) {
  if (!role) return null;
  return <Chip label={role === "admin" ? "Team admin" : "Member"} color={role === "admin" ? "primary" : "default"} variant="outlined" />;
}
