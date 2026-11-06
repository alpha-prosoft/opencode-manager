import { Box, Button, Paper, Stack, Typography } from "@mui/material";
import DownloadIcon from "@mui/icons-material/Download";

function color(line: string): string | undefined {
  if (line.startsWith("+++") || line.startsWith("---")) return "text.secondary";
  if (line.startsWith("+")) return "success.main";
  if (line.startsWith("-")) return "error.main";
  if (line.startsWith("@@")) return "info.main";
  return undefined;
}

export default function PatchView({ patch, name }: { patch: string; name: string }) {
  const download = () => {
    const url = URL.createObjectURL(new Blob([patch], { type: "text/x-diff" }));
    Object.assign(document.createElement("a"), { href: url, download: name }).click();
    URL.revokeObjectURL(url);
  };
  if (!patch) return <Typography color="text.secondary">The agent didn't change any files.</Typography>;
  return (
    <Stack spacing={1} sx={{ alignItems: "flex-start" }}>
      <Button size="small" startIcon={<DownloadIcon />} onClick={download}>
        Download patch
      </Button>
      <Paper sx={{ p: 2, overflow: "auto", maxHeight: 600, width: "100%" }}>
        <Box component="pre" sx={{ m: 0, typography: "mono" }}>
          {patch.split("\n").map((line, index) => (
            <Box
              key={index}
              component="span"
              sx={{ display: "block", color: color(line), fontWeight: line.startsWith("diff ") ? 600 : 400 }}
            >
              {line || " "}
            </Box>
          ))}
        </Box>
      </Paper>
    </Stack>
  );
}
