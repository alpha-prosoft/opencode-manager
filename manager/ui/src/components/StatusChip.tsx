import { Chip, type ChipProps } from "@mui/material";
import type { SessionStatus } from "../api";

const COLORS: Record<SessionStatus, ChipProps["color"]> = {
  running: "success",
  starting: "info",
  stopped: "default",
  error: "error",
};

export default function StatusChip({ status }: { status: SessionStatus }) {
  return <Chip size="small" label={status} color={COLORS[status]} />;
}
