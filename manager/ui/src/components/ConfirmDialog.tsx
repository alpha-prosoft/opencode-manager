import { useState, type ReactNode } from "react";
import { Button, Dialog, DialogActions, DialogContent, DialogContentText, DialogTitle } from "@mui/material";

interface Request {
  title: string;
  body: ReactNode;
  confirm: string;
  action: () => Promise<unknown>;
}

export function useConfirm() {
  const [request, setRequest] = useState<Request | null>(null);
  const [busy, setBusy] = useState(false);
  const close = () => !busy && setRequest(null);
  const run = async () => {
    if (!request) return;
    setBusy(true);
    try {
      await request.action();
    } finally {
      setBusy(false);
      setRequest(null);
    }
  };
  const dialog = (
    <Dialog open={request !== null} onClose={close} maxWidth="xs" fullWidth>
      <DialogTitle>{request?.title}</DialogTitle>
      <DialogContent>
        <DialogContentText>{request?.body}</DialogContentText>
      </DialogContent>
      <DialogActions>
        <Button onClick={close} disabled={busy}>
          Keep
        </Button>
        <Button onClick={run} color="error" variant="contained" disabled={busy}>
          {request?.confirm}
        </Button>
      </DialogActions>
    </Dialog>
  );
  return { confirm: setRequest, dialog };
}
