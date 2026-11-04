import { Chip } from "@mui/material";
import LockIcon from "@mui/icons-material/Lock";
import PublicIcon from "@mui/icons-material/Public";
import type { Visibility } from "../api";

export default function VisibilityChip({ visibility }: { visibility: Visibility }) {
  return visibility === "public" ? (
    <Chip size="small" variant="outlined" color="primary" icon={<PublicIcon />} label="public" />
  ) : (
    <Chip size="small" variant="outlined" icon={<LockIcon />} label="private" />
  );
}
