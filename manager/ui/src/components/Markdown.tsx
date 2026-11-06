import { Box } from "@mui/material";
import ReactMarkdown from "react-markdown";
import remarkGfm from "remark-gfm";

export default function Markdown({ children }: { children: string }) {
  return (
    <Box
      sx={{
        "& h1, & h2, & h3": { mt: 2.5, mb: 1, lineHeight: 1.3, fontWeight: 600 },
        "& h1": { fontSize: "1.375rem" },
        "& h2": { fontSize: "1.175rem" },
        "& h3": { fontSize: "1rem" },
        "& > :first-child": { mt: 0 },
        maxWidth: 820,
        "& p, & li": { lineHeight: 1.6 },
        "& table": { borderCollapse: "collapse", width: "100%", my: 2, fontSize: "0.9rem" },
        "& th, & td": { border: 1, borderColor: "divider", p: 1, textAlign: "left", verticalAlign: "top" },
        "& th": { bgcolor: "action.hover" },
        "& code": { typography: "mono", bgcolor: "action.hover", px: 0.5, borderRadius: 0.5 },
        "& pre": { bgcolor: "action.hover", p: 2, borderRadius: 1, overflowX: "auto" },
        "& pre code": { bgcolor: "transparent", p: 0 },
      }}
    >
      <ReactMarkdown remarkPlugins={[remarkGfm]}>{children}</ReactMarkdown>
    </Box>
  );
}
