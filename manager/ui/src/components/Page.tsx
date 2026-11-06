import type { ReactNode } from "react";
import { Link as RouterLink } from "react-router";
import { Box, Link, Stack, Typography } from "@mui/material";
import ArrowBackIcon from "@mui/icons-material/ArrowBack";

interface HeaderProps {
  title: ReactNode;
  description?: ReactNode;
  back?: { to: string; label: string };
  meta?: ReactNode;
  actions?: ReactNode;
}

export function PageHeader({ title, description, back, meta, actions }: HeaderProps) {
  return (
    <Stack spacing={1}>
      {back && (
        <Link
          component={RouterLink}
          to={back.to}
          variant="body2"
          color="text.secondary"
          sx={{ display: "inline-flex", alignItems: "center", gap: 0.5 }}
        >
          <ArrowBackIcon fontSize="inherit" />
          {back.label}
        </Link>
      )}
      <Stack direction={{ xs: "column", sm: "row" }} spacing={2} sx={{ alignItems: { sm: "center" } }}>
        <Box sx={{ flexGrow: 1, minWidth: 0 }}>
          <Typography variant="h1">{title}</Typography>
          {description && (
            <Typography color="text.secondary" sx={{ mt: 0.5, maxWidth: 760 }}>
              {description}
            </Typography>
          )}
        </Box>
        {actions && (
          <Stack direction="row" spacing={1} sx={{ flexShrink: 0, flexWrap: "wrap" }} useFlexGap>
            {actions}
          </Stack>
        )}
      </Stack>
      {meta && (
        <Stack direction="row" spacing={1} sx={{ alignItems: "center", flexWrap: "wrap", color: "text.secondary" }} useFlexGap>
          {meta}
        </Stack>
      )}
    </Stack>
  );
}

interface SectionProps {
  title: ReactNode;
  description?: ReactNode;
  action?: ReactNode;
  children: ReactNode;
}

export function Section({ title, description, action, children }: SectionProps) {
  return (
    <Stack spacing={1.5} component="section">
      <Stack direction="row" spacing={2} sx={{ alignItems: "flex-end" }}>
        <Box sx={{ flexGrow: 1 }}>
          <Typography variant="h2">{title}</Typography>
          {description && (
            <Typography variant="body2" color="text.secondary">
              {description}
            </Typography>
          )}
        </Box>
        {action}
      </Stack>
      {children}
    </Stack>
  );
}

export function PageStack({ children }: { children: ReactNode }) {
  return <Stack spacing={4}>{children}</Stack>;
}
