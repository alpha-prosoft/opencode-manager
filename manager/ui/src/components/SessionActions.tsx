import { useNavigate } from "react-router";
import { Button, IconButton, Stack, Tooltip } from "@mui/material";
import CallSplitIcon from "@mui/icons-material/CallSplit";
import DeleteOutlineIcon from "@mui/icons-material/DeleteOutlined";
import LockOutlinedIcon from "@mui/icons-material/LockOutlined";
import OpenInNewIcon from "@mui/icons-material/OpenInNew";
import PlayArrowIcon from "@mui/icons-material/PlayArrow";
import StopIcon from "@mui/icons-material/Stop";
import { api, type Session } from "../api";
import { useConfirm } from "./ConfirmDialog";
import { isSessionLive } from "./format";

interface Props {
  session: Session;
  onChange: () => void;
  onError: (message: string) => void;
  onDeleted?: () => void;
  prominent?: boolean;
}

export default function SessionActions({ session, onChange, onError, onDeleted, prominent = false }: Props) {
  const navigate = useNavigate();
  const { confirm, dialog } = useConfirm();
  const live = isSessionLive(session.status);
  const run =
    (action: () => Promise<unknown>, after = onChange) =>
    () =>
      action()
        .then(after)
        .catch((error: Error) => onError(error.message));
  const forkAndOpen = () =>
    api
      .forkSession(session.id)
      .then((copy) => navigate(`/sessions/${copy.id}`))
      .catch((error: Error) => onError(error.message));
  const remove = () =>
    confirm({
      title: "Delete this session?",
      body: `"${session.name}" and its workspace files will be deleted. The audit trail is kept.`,
      confirm: "Delete session",
      action: run(() => api.deleteSession(session.id), onDeleted ?? onChange),
    });
  const lock = () =>
    confirm({
      title: "Lock this session?",
      body: "It stops, and nobody can continue in it, you included. Anyone who can see it can still read it and fork it to continue in their own copy. Locking can't be undone.",
      confirm: "Lock session",
      action: run(() => api.lockSession(session.id)),
    });

  return (
    <Stack direction="row" spacing={0.5} sx={{ justifyContent: "flex-end", alignItems: "center" }}>
      {session.can_open ? (
        <Button
          size="small"
          variant={prominent ? "contained" : "text"}
          startIcon={<OpenInNewIcon />}
          href={session.open_url}
          target="_blank"
          rel="noopener"
        >
          Open
        </Button>
      ) : (
        <Tooltip title="Continue in your own private copy, with your own credentials">
          <Button size="small" variant={prominent ? "contained" : "text"} startIcon={<CallSplitIcon />} onClick={forkAndOpen}>
            Fork
          </Button>
        </Tooltip>
      )}
      {prominent && session.can_open && (
        <Tooltip title="Make your own private copy to continue in">
          <Button size="small" startIcon={<CallSplitIcon />} onClick={forkAndOpen}>
            Fork
          </Button>
        </Tooltip>
      )}
      {!live && session.can_open && (
        <Tooltip title="Start the sandbox">
          <IconButton size="small" aria-label="Start session" onClick={run(() => api.startSession(session.id))}>
            <PlayArrowIcon fontSize="small" />
          </IconButton>
        </Tooltip>
      )}
      {live && session.can_manage && (
        <Tooltip title="Stop the sandbox. Files are kept; opening it starts it again.">
          <IconButton size="small" aria-label="Stop session" onClick={run(() => api.stopSession(session.id))}>
            <StopIcon fontSize="small" />
          </IconButton>
        </Tooltip>
      )}
      {prominent && session.is_owner && !session.locked_at && (
        <Tooltip title="Freeze this session so it can only be forked">
          <IconButton size="small" aria-label="Lock session" onClick={lock}>
            <LockOutlinedIcon fontSize="small" />
          </IconButton>
        </Tooltip>
      )}
      {session.can_manage && (
        <Tooltip title="Delete session">
          <IconButton size="small" aria-label="Delete session" color="error" onClick={remove}>
            <DeleteOutlineIcon fontSize="small" />
          </IconButton>
        </Tooltip>
      )}
      {dialog}
    </Stack>
  );
}
