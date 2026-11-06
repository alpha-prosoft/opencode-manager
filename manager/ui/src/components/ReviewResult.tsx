import { Alert, Chip, List, ListItem, ListItemText, Paper, Stack, Typography, type ChipProps } from "@mui/material";
import CheckCircleOutlineIcon from "@mui/icons-material/CheckCircleOutlineOutlined";
import { CodeBlock, Mono } from "./Data";
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
        {result.plan.mode === "auto"
          ? `${result.posted} of ${result.plan.comments} comment(s) posted to the pull request (${result.plan.inline} on lines, ${result.plan.folded} in the summary).`
          : result.plan.mode === "dry-run"
            ? `Dry run: nothing was posted. ${result.plan.comments} comment(s) would be posted to the pull request.`
            : "Posting is off for this workflow: the review is only shown here."}
      </Typography>
      {review.findings.length === 0 && <Typography color="text.secondary">No findings.</Typography>}
      {review.findings.map((finding: any, index: number) => (
        <Paper key={index} sx={{ p: 2 }}>
          <Stack direction="row" spacing={1} sx={{ alignItems: "center", mb: 1, flexWrap: "wrap" }} useFlexGap>
            <Chip variant="outlined" label={finding.severity} color={SEVERITY[finding.severity]} />
            <Typography sx={{ fontWeight: 600 }}>{finding.title}</Typography>
            {finding.path && (
              <Mono>
                {finding.path}
                {finding.line ? `:${finding.line}` : ""}
              </Mono>
            )}
          </Stack>
          <Markdown>{finding.body}</Markdown>
          {finding.suggestion && <CodeBlock>{finding.suggestion}</CodeBlock>}
        </Paper>
      ))}
      {review.checked.length > 0 && (
        <Paper sx={{ p: 2 }}>
          <Typography variant="subtitle2" gutterBottom>
            Checked and found fine
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
