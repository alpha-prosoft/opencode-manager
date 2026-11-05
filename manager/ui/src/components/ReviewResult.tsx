import { Alert, Box, Chip, List, ListItem, ListItemText, Paper, Stack, Typography, type ChipProps } from "@mui/material";
import CheckCircleOutlineIcon from "@mui/icons-material/CheckCircleOutlineOutlined";
import Markdown from "./Markdown";

const SEVERITY: Record<string, ChipProps["color"]> = {
  blocker: "error",
  major: "warning",
  minor: "info",
  question: "secondary",
  nit: "default",
};

const VERDICT: Record<string, "success" | "warning" | "error"> = {
  APPROVE: "success",
  APPROVE_WITH_NITS: "warning",
  REQUEST_CHANGES: "error",
};

export default function ReviewResult({ result }: { result: Record<string, any> }) {
  const review = result.review;
  return (
    <Stack spacing={2}>
      <Alert severity={VERDICT[review.verdict] ?? "info"}>
        <strong>{review.verdict.replace(/_/g, " ")}</strong> — {review.summary}
      </Alert>
      <Typography variant="body2" color="text.secondary">
        Post mode <strong>{result.plan.mode}</strong>: {result.plan.comments} comment(s) planned ({result.plan.inline} inline,{" "}
        {result.plan.folded} folded into the summary), {result.posted} posted.
      </Typography>
      {review.findings.length === 0 && <Typography color="text.secondary">No findings.</Typography>}
      {review.findings.map((finding: any, index: number) => (
        <Paper key={index} variant="outlined" sx={{ p: 2 }}>
          <Stack direction="row" spacing={1} sx={{ alignItems: "center", mb: 1, flexWrap: "wrap" }} useFlexGap>
            <Chip size="small" label={finding.severity} color={SEVERITY[finding.severity]} />
            <Typography sx={{ fontWeight: 600 }}>{finding.title}</Typography>
            {finding.path && (
              <Typography variant="body2" sx={{ fontFamily: "monospace", color: "text.secondary" }}>
                {finding.path}
                {finding.line ? `:${finding.line}` : ""}
              </Typography>
            )}
          </Stack>
          <Markdown>{finding.body}</Markdown>
          {finding.suggestion && (
            <Box component="pre" sx={{ bgcolor: "action.hover", p: 1.5, borderRadius: 1, overflowX: "auto", fontSize: "0.85rem" }}>
              {finding.suggestion}
            </Box>
          )}
        </Paper>
      ))}
      {review.checked.length > 0 && (
        <Paper variant="outlined" sx={{ p: 2 }}>
          <Typography variant="subtitle2" gutterBottom>
            Checked and OK
          </Typography>
          <List dense disablePadding>
            {review.checked.map((item: string, index: number) => (
              <ListItem key={index} disableGutters>
                <CheckCircleOutlineIcon fontSize="small" color="success" sx={{ mr: 1 }} />
                <ListItemText primary={item} />
              </ListItem>
            ))}
          </List>
        </Paper>
      )}
    </Stack>
  );
}
