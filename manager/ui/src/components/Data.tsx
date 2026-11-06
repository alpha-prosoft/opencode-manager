import type { ReactNode } from "react";
import { Alert, Avatar, Box, Paper, Stack, Table, TableBody, TableCell, TableRow, Typography } from "@mui/material";
import type { SvgIconComponent } from "@mui/icons-material";

export function EmptyState({ title, children }: { title: string; children?: ReactNode }) {
  return (
    <Paper sx={{ p: 4, textAlign: "center", borderStyle: "dashed" }}>
      <Typography variant="h3">{title}</Typography>
      {children && (
        <Typography variant="body2" color="text.secondary" sx={{ mt: 0.5, maxWidth: 520, mx: "auto" }}>
          {children}
        </Typography>
      )}
    </Paper>
  );
}

export function DetailList({ items }: { items: [string, ReactNode][] }) {
  return (
    <Paper>
      <Table>
        <TableBody>
          {items.map(([label, value]) => (
            <TableRow key={label}>
              <TableCell component="th" scope="row" sx={{ width: { xs: 104, sm: 180 }, color: "text.secondary", verticalAlign: "top" }}>
                {label}
              </TableCell>
              <TableCell sx={{ whiteSpace: "pre-wrap", overflowWrap: "anywhere" }}>{value}</TableCell>
            </TableRow>
          ))}
        </TableBody>
      </Table>
    </Paper>
  );
}

export function Stat({ label, value, hint }: { label: string; value: ReactNode; hint?: ReactNode }) {
  return (
    <Paper sx={{ px: 2, py: 1.5 }}>
      <Typography variant="label">{label}</Typography>
      <Typography variant="h5">{value}</Typography>
      {hint && (
        <Typography variant="caption" color="text.secondary">
          {hint}
        </Typography>
      )}
    </Paper>
  );
}

export function StatGrid({ children }: { children: ReactNode }) {
  return <Box sx={{ display: "grid", gap: 1.5, gridTemplateColumns: "repeat(auto-fill, minmax(160px, 1fr))" }}>{children}</Box>;
}

export function CodeBlock({ children, maxHeight }: { children: ReactNode; maxHeight?: number }) {
  return (
    <Box
      component="pre"
      sx={{ m: 0, p: 1.5, bgcolor: "action.hover", borderRadius: 1, overflow: "auto", maxHeight, typography: "mono", whiteSpace: "pre" }}
    >
      {children}
    </Box>
  );
}

export function TwoColumns({ children }: { children: ReactNode }) {
  return <Box sx={{ display: "grid", gap: 3, gridTemplateColumns: { xs: "1fr", md: "1fr 1fr" }, alignItems: "start" }}>{children}</Box>;
}

export function Mono({ children }: { children: ReactNode }) {
  return <Typography variant="mono">{children}</Typography>;
}

export function Row({ children }: { children: ReactNode }) {
  return (
    <Stack direction="row" spacing={1} sx={{ alignItems: "center", flexWrap: "wrap" }} useFlexGap>
      {children}
    </Stack>
  );
}

export function ListRow({ children, dense = false }: { children: ReactNode; dense?: boolean }) {
  return (
    <Stack
      direction={dense ? "row" : { xs: "column", sm: "row" }}
      spacing={2}
      sx={{
        px: 2,
        py: dense ? 1 : 2,
        alignItems: dense ? "center" : { sm: "center" },
        "& + &": { borderTop: 1, borderColor: "divider" },
      }}
    >
      {children}
    </Stack>
  );
}

export function IconTile({ icon: Icon }: { icon: SvgIconComponent }) {
  return (
    <Avatar variant="rounded" sx={{ bgcolor: "action.hover", color: "primary.main" }}>
      <Icon />
    </Avatar>
  );
}

export const WIDE_ONLY = { display: { xs: "none", md: "table-cell" } } as const;

export function DefinitionErrorsList({ errors }: { errors: string[] }) {
  if (errors.length === 0) return null;
  return (
    <Alert severity="warning">
      <Box component="ul" sx={{ m: 0, pl: 2.5 }}>
        {errors.map((error) => (
          <li key={error}>{error}</li>
        ))}
      </Box>
    </Alert>
  );
}
