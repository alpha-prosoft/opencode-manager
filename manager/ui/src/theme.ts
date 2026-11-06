import { createTheme } from "@mui/material";
import "@fontsource/ibm-plex-sans/400.css";
import "@fontsource/ibm-plex-sans/500.css";
import "@fontsource/ibm-plex-sans/600.css";
import "@fontsource/ibm-plex-mono/400.css";
import "@fontsource/ibm-plex-mono/500.css";

declare module "@mui/material/styles" {
  interface TypographyVariants {
    mono: React.CSSProperties;
    label: React.CSSProperties;
  }
  interface TypographyVariantsOptions {
    mono?: React.CSSProperties;
    label?: React.CSSProperties;
  }
}

declare module "@mui/material/Typography" {
  interface TypographyPropsVariantOverrides {
    mono: true;
    label: true;
  }
}

const SANS = '"IBM Plex Sans", system-ui, sans-serif';
export const MONO = '"IBM Plex Mono", ui-monospace, monospace';

export const theme = createTheme({
  cssVariables: { colorSchemeSelector: "media" },
  colorSchemes: {
    light: {
      palette: {
        primary: { main: "#0B6E69", contrastText: "#FFFFFF" },
        secondary: { main: "#4A5866" },
        warning: { main: "#A15C07" },
        error: { main: "#B42318" },
        success: { main: "#2F7D4F" },
        info: { main: "#2D63A6" },
        background: { default: "#F3F5F6", paper: "#FFFFFF" },
        text: { primary: "#18212B", secondary: "#5B6875" },
        divider: "#DDE2E6",
      },
    },
    dark: {
      palette: {
        primary: { main: "#5CC2B8", contrastText: "#06201E" },
        secondary: { main: "#A3B1BE" },
        warning: { main: "#E0A04A" },
        error: { main: "#F07A6E" },
        success: { main: "#6CC08F" },
        info: { main: "#7EA8E0" },
        background: { default: "#0F1417", paper: "#161D21" },
        text: { primary: "#E6EBEF", secondary: "#9AA7B3" },
        divider: "#2A343B",
      },
    },
  },
  shape: { borderRadius: 8 },
  typography: {
    fontFamily: SANS,
    fontSize: 14,
    h1: { fontSize: "1.75rem", fontWeight: 600, lineHeight: 1.25, letterSpacing: "-0.01em" },
    h2: { fontSize: "1.125rem", fontWeight: 600, lineHeight: 1.35 },
    h3: { fontSize: "1rem", fontWeight: 600, lineHeight: 1.4 },
    h5: { fontSize: "1.5rem", fontWeight: 600 },
    h6: { fontSize: "1rem", fontWeight: 600 },
    subtitle1: { fontWeight: 500 },
    subtitle2: { fontWeight: 600 },
    body1: { fontSize: "0.9375rem", lineHeight: 1.55 },
    body2: { fontSize: "0.875rem", lineHeight: 1.5 },
    button: { textTransform: "none", fontWeight: 500, letterSpacing: 0 },
    mono: { fontFamily: MONO, fontSize: "0.8125rem" },
    label: { fontSize: "0.8125rem", fontWeight: 500, color: "var(--mui-palette-text-secondary)" },
  },
  components: {
    MuiCssBaseline: {
      styleOverrides: {
        "code, pre, kbd": { fontFamily: MONO },
        a: { color: "var(--mui-palette-primary-main)" },
      },
    },
    MuiTypography: {
      defaultProps: { variantMapping: { mono: "span", label: "span" } },
    },
    MuiPaper: {
      defaultProps: { variant: "outlined" },
    },
    MuiCard: {
      defaultProps: { variant: "outlined" },
    },
    MuiAppBar: {
      defaultProps: { elevation: 0, color: "inherit" },
      styleOverrides: { root: { borderBottom: "1px solid var(--mui-palette-divider)" } },
    },
    MuiButton: {
      defaultProps: { disableElevation: true },
    },
    MuiChip: {
      defaultProps: { size: "small" },
      styleOverrides: { root: { borderRadius: 6, fontWeight: 500 } },
    },
    MuiTabs: {
      styleOverrides: { root: { borderBottom: "1px solid var(--mui-palette-divider)", minHeight: 40 } },
    },
    MuiTab: {
      styleOverrides: { root: { minHeight: 40, paddingInline: 12, minWidth: 0, fontWeight: 500 } },
    },
    MuiTable: {
      defaultProps: { size: "small" },
    },
    MuiTableCell: {
      styleOverrides: {
        root: { paddingBlock: 10 },
        head: { fontSize: "0.8125rem", fontWeight: 600, color: "var(--mui-palette-text-secondary)", whiteSpace: "nowrap" },
      },
    },
    MuiTableRow: {
      styleOverrides: { root: { "&:last-child td": { borderBottom: 0 } } },
    },
    MuiAlert: {
      styleOverrides: { root: { alignItems: "flex-start" }, message: { overflowWrap: "anywhere" } },
    },
    MuiTooltip: {
      defaultProps: { arrow: true },
    },
    MuiLink: {
      defaultProps: { underline: "hover" },
    },
    MuiListItemButton: {
      styleOverrides: {
        root: {
          borderRadius: 6,
          marginInline: 8,
          paddingBlock: 6,
          "&.Mui-selected": { color: "var(--mui-palette-primary-main)", fontWeight: 600 },
          "&.Mui-selected .MuiListItemIcon-root": { color: "var(--mui-palette-primary-main)" },
        },
      },
    },
    MuiListItemIcon: {
      styleOverrides: { root: { minWidth: 34 } },
    },
    MuiDialogTitle: {
      styleOverrides: { root: { fontSize: "1.125rem", fontWeight: 600 } },
    },
    MuiAccordion: {
      defaultProps: { disableGutters: true, variant: "outlined" },
      styleOverrides: { root: { "&::before": { display: "none" } } },
    },
  },
});
