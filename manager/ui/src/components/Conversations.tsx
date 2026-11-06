import { Button, Paper, Typography, Box } from "@mui/material";
import OpenInNewIcon from "@mui/icons-material/OpenInNew";
import { api, type Session } from "../api";
import { EmptyState, ListRow } from "./Data";
import { relativeTime } from "./format";
import { useLoad } from "../hooks";

export default function Conversations({ session }: { session: Session }) {
  const { data } = useLoad(() => api.conversations(session.id), [session.id], 10000);
  if (!data) return null;
  if (data.length === 0) {
    return <EmptyState title="No conversations yet">Each chat started in this instance shows up here.</EmptyState>;
  }
  return (
    <Paper>
      {data.map((conversation) => (
        <ListRow key={conversation.id}>
          <Box sx={{ flexGrow: 1 }}>
            <Typography variant="h3">{conversation.title}</Typography>
            <Typography variant="body2" color="text.secondary">
              {conversation.messages} message{conversation.messages === 1 ? "" : "s"}
              {conversation.last ? ` · last ${relativeTime(conversation.last)}` : ""}
            </Typography>
          </Box>
          {session.can_open && (
            <Button
              size="small"
              startIcon={<OpenInNewIcon />}
              href={`${session.open_url}?conversation=${encodeURIComponent(conversation.id)}`}
              target="_blank"
              rel="noopener"
            >
              Open
            </Button>
          )}
        </ListRow>
      ))}
    </Paper>
  );
}
