import { useState } from "react";
import { Alert, Box, Button, Chip, Dialog, DialogContent, DialogTitle, Paper, Stack, Typography } from "@mui/material";
import { api, type CredentialStatus } from "../api";
import { useConfirm } from "../components/ConfirmDialog";
import CredentialForm from "../components/CredentialForm";
import { EmptyState, ListRow, Mono } from "../components/Data";
import { PageHeader, PageStack } from "../components/Page";
import { relativeTime } from "../components/format";
import { useLoad } from "../hooks";

function StateChip({ credential }: { credential: CredentialStatus }) {
  if (credential.state === "own") return <Chip variant="outlined" color="success" label="Yours" />;
  if (credential.state === "platform") return <Chip variant="outlined" label="Shared account" />;
  return (
    <Chip
      variant="outlined"
      color={credential.required ? "warning" : "default"}
      label={credential.required ? "Needs your credential" : "Not set"}
    />
  );
}

export default function AccountsPage() {
  const data = useLoad(() => api.myCredentials(), []);
  const [editing, setEditing] = useState<CredentialStatus | null>(null);
  const [error, setError] = useState<string | null>(null);
  const { confirm, dialog } = useConfirm();
  const credentials = data.data?.credentials ?? [];

  const remove = (credential: CredentialStatus) =>
    confirm({
      title: `Remove your ${credential.name} credential?`,
      body:
        credential.provided_by === "platform_or_user"
          ? "Tools will use the shared account again."
          : "Tools that need it stop working for you until you add it again.",
      confirm: "Remove",
      action: () =>
        api
          .deleteMyCredential(credential.id)
          .then(data.reload)
          .catch((e: Error) => setError(e.message)),
    });

  return (
    <PageStack>
      <PageHeader
        title="Accounts"
        description="Credentials tools use on your behalf. They are stored encrypted on the platform and added to requests by the platform gateway; agents never see them."
      />
      {error && <Alert severity="error">{error}</Alert>}
      {data.data && credentials.length === 0 && (
        <EmptyState title="Nothing to connect">None of your teams' tools need personal credentials.</EmptyState>
      )}
      {credentials.length > 0 && (
        <Paper>
          {credentials.map((credential) => (
            <ListRow key={credential.id}>
              <Box sx={{ flexGrow: 1 }}>
                <Stack direction="row" spacing={1} sx={{ alignItems: "center" }}>
                  <Typography variant="h3">{credential.name}</Typography>
                  <StateChip credential={credential} />
                </Stack>
                <Typography variant="body2" color="text.secondary">
                  {credential.description}
                </Typography>
                {credential.state === "own" && (
                  <Typography variant="caption" color="text.secondary">
                    <Mono>{credential.hint}</Mono> · saved {relativeTime(credential.updated_at)}
                    {credential.verified_at ? " · checked" : ""}
                  </Typography>
                )}
              </Box>
              <Stack direction="row" spacing={1}>
                <Button variant={credential.state === "own" ? "text" : "contained"} onClick={() => setEditing(credential)}>
                  {credential.state === "own" ? "Replace" : "Add yours"}
                </Button>
                {credential.state === "own" && (
                  <Button color="error" onClick={() => remove(credential)}>
                    Remove
                  </Button>
                )}
              </Stack>
            </ListRow>
          ))}
        </Paper>
      )}
      <Dialog open={editing !== null} onClose={() => setEditing(null)} fullWidth maxWidth="sm">
        {editing && (
          <>
            <DialogTitle>Your {editing.name} credential</DialogTitle>
            <DialogContent>
              <CredentialForm
                name={editing.name}
                kind={editing.kind}
                help={editing.help}
                checks={Boolean(editing.test)}
                save={(value) => api.setMyCredential(editing.id, value)}
                onSaved={() => {
                  setEditing(null);
                  data.reload();
                }}
                extraActions={<Button onClick={() => setEditing(null)}>Cancel</Button>}
              />
            </DialogContent>
          </>
        )}
      </Dialog>
      {dialog}
    </PageStack>
  );
}
