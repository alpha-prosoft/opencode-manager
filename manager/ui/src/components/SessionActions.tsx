import { IconButton, Stack, Tooltip } from "@mui/material";
import DeleteIcon from "@mui/icons-material/Delete";
import OpenInNewIcon from "@mui/icons-material/OpenInNew";
import PlayArrowIcon from "@mui/icons-material/PlayArrow";
import StopIcon from "@mui/icons-material/Stop";
import { api, type Session } from "../api";

interface Props {
  session: Session;
  onChange: () => void;
  onError: (message: string) => void;
}

export default function SessionActions({ session, onChange, onError }: Props) {
  const isActive = session.status === "running" || session.status === "starting";
  const run = (action: () => Promise<unknown>) => () =>
    action()
      .then(onChange)
      .catch((error: Error) => onError(error.message));

  return (
    <Stack direction="row" spacing={0.5} sx={{ justifyContent: "flex-end" }}>
      <Tooltip title="Open in OpenCode">
        <IconButton href={session.open_url} target="_blank" rel="noopener" color="primary">
          <OpenInNewIcon />
        </IconButton>
      </Tooltip>
      {!isActive && (
        <Tooltip title="Start">
          <IconButton onClick={run(() => api.startSession(session.id))}>
            <PlayArrowIcon />
          </IconButton>
        </Tooltip>
      )}
      {isActive && session.can_manage && (
        <Tooltip title="Stop">
          <IconButton onClick={run(() => api.stopSession(session.id))}>
            <StopIcon />
          </IconButton>
        </Tooltip>
      )}
      {session.can_manage && (
        <Tooltip title="Delete (the audit trail is kept)">
          <IconButton
            color="error"
            onClick={run(async () => {
              if (window.confirm(`Delete session "${session.name}" and its workspace? The audit trail is kept.`)) {
                await api.deleteSession(session.id);
              }
            })}
          >
            <DeleteIcon />
          </IconButton>
        </Tooltip>
      )}
    </Stack>
  );
}
