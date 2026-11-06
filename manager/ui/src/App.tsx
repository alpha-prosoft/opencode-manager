import { useEffect, useState } from "react";
import { Link as RouterLink, Navigate, Outlet, useLocation } from "react-router";
import {
  AppBar,
  Avatar,
  Box,
  CircularProgress,
  Container,
  Drawer,
  IconButton,
  List,
  ListItemButton,
  ListItemIcon,
  ListItemText,
  ListSubheader,
  Stack,
  Toolbar,
  Tooltip,
  Typography,
  useMediaQuery,
  useTheme,
} from "@mui/material";
import AdminPanelSettingsOutlinedIcon from "@mui/icons-material/AdminPanelSettingsOutlined";
import GroupsOutlinedIcon from "@mui/icons-material/GroupsOutlined";
import HistoryIcon from "@mui/icons-material/History";
import HomeOutlinedIcon from "@mui/icons-material/HomeOutlined";
import HubOutlinedIcon from "@mui/icons-material/HubOutlined";
import LogoutIcon from "@mui/icons-material/Logout";
import MenuIcon from "@mui/icons-material/Menu";
import TerminalIcon from "@mui/icons-material/Terminal";
import WidgetsOutlinedIcon from "@mui/icons-material/WidgetsOutlined";
import { ApiError, api, type Me } from "./api";
import { IdentityContext } from "./auth";

const DRAWER_WIDTH = 236;

type AuthState = { status: "loading" } | { status: "anonymous" } | { status: "ready"; identity: Me };

const NAV = [
  {
    group: "Work",
    items: [
      { to: "/", label: "Home", icon: <HomeOutlinedIcon /> },
      { to: "/runs", label: "Runs", icon: <HistoryIcon /> },
      { to: "/sessions", label: "Sessions", icon: <TerminalIcon /> },
    ],
  },
  {
    group: "Library",
    items: [
      { to: "/teams", label: "Teams", icon: <GroupsOutlinedIcon /> },
      { to: "/activities", label: "Activities", icon: <WidgetsOutlinedIcon /> },
    ],
  },
];

const ADMIN_NAV = { group: "Platform", items: [{ to: "/admin", label: "Admin", icon: <AdminPanelSettingsOutlinedIcon /> }] };

function roleLabel(identity: Me): string {
  if (identity.platform_admin) return "Platform admin";
  if (identity.teams.some((team) => team.role === "admin")) return "Team admin";
  if (identity.teams.length) return "Team member";
  return "No team yet";
}

function Brand() {
  return (
    <Stack
      direction="row"
      spacing={1.25}
      component={RouterLink}
      to="/"
      sx={{ alignItems: "center", color: "text.primary", textDecoration: "none" }}
    >
      <Avatar variant="rounded" sx={{ width: 30, height: 30, bgcolor: "primary.main", color: "primary.contrastText" }}>
        <HubOutlinedIcon fontSize="small" />
      </Avatar>
      <Typography variant="h3">Agent Platform</Typography>
    </Stack>
  );
}

function Sidebar({ identity, onNavigate, onLogout }: { identity: Me; onNavigate: () => void; onLogout: () => void }) {
  const location = useLocation();
  const groups = identity.platform_admin ? [...NAV, ADMIN_NAV] : NAV;
  const active = (to: string) => (to === "/" ? location.pathname === "/" : location.pathname.startsWith(to));
  const name = identity.name ?? identity.sub;
  return (
    <Stack sx={{ height: "100%" }}>
      <Toolbar>
        <Brand />
      </Toolbar>
      <Box sx={{ flexGrow: 1, overflowY: "auto" }}>
        {groups.map(({ group, items }) => (
          <List key={group} dense subheader={<ListSubheader disableSticky>{group}</ListSubheader>}>
            {items.map((item) => (
              <ListItemButton key={item.to} component={RouterLink} to={item.to} selected={active(item.to)} onClick={onNavigate}>
                <ListItemIcon>{item.icon}</ListItemIcon>
                <ListItemText primary={item.label} />
              </ListItemButton>
            ))}
          </List>
        ))}
      </Box>
      <Stack direction="row" spacing={1.5} sx={{ p: 2, borderTop: 1, borderColor: "divider", alignItems: "center" }}>
        <Avatar sx={{ width: 32, height: 32, fontSize: "0.875rem" }}>{name.slice(0, 1).toUpperCase()}</Avatar>
        <Box sx={{ flexGrow: 1, minWidth: 0 }}>
          <Typography variant="body2" noWrap sx={{ fontWeight: 600 }}>
            {name}
          </Typography>
          <Typography variant="caption" color="text.secondary">
            {roleLabel(identity)}
          </Typography>
        </Box>
        <Tooltip title="Log out">
          <IconButton size="small" aria-label="Log out" onClick={onLogout}>
            <LogoutIcon fontSize="small" />
          </IconButton>
        </Tooltip>
      </Stack>
    </Stack>
  );
}

export default function App() {
  const location = useLocation();
  const theme = useTheme();
  const desktop = useMediaQuery(theme.breakpoints.up("md"));
  const [open, setOpen] = useState(false);
  const [auth, setAuth] = useState<AuthState>({ status: "loading" });

  useEffect(() => {
    api
      .me()
      .then((identity) => setAuth({ status: "ready", identity }))
      .catch((error) => setAuth(error instanceof ApiError && error.status === 401 ? { status: "anonymous" } : { status: "loading" }));
  }, []);

  if (auth.status === "loading") {
    return (
      <Box sx={{ display: "grid", placeItems: "center", minHeight: "100vh" }}>
        <CircularProgress />
      </Box>
    );
  }
  if (auth.status === "anonymous") {
    return <Navigate to={`/login?next=${encodeURIComponent(location.pathname)}`} replace />;
  }

  const logout = async () => {
    await api.logout();
    setAuth({ status: "anonymous" });
  };
  const sidebar = <Sidebar identity={auth.identity} onNavigate={() => setOpen(false)} onLogout={logout} />;

  return (
    <IdentityContext value={auth.identity}>
      <Box sx={{ display: "flex", minHeight: "100vh" }}>
        {desktop ? (
          <Drawer variant="permanent" sx={{ width: DRAWER_WIDTH, flexShrink: 0, "& .MuiDrawer-paper": { width: DRAWER_WIDTH } }}>
            {sidebar}
          </Drawer>
        ) : (
          <>
            <AppBar position="fixed">
              <Toolbar>
                <IconButton edge="start" aria-label="Open navigation" onClick={() => setOpen(true)} sx={{ mr: 1 }}>
                  <MenuIcon />
                </IconButton>
                <Brand />
              </Toolbar>
            </AppBar>
            <Drawer open={open} onClose={() => setOpen(false)} sx={{ "& .MuiDrawer-paper": { width: DRAWER_WIDTH } }}>
              {sidebar}
            </Drawer>
          </>
        )}
        <Box component="main" sx={{ flexGrow: 1, minWidth: 0 }}>
          {!desktop && <Toolbar />}
          <Container maxWidth="lg" sx={{ py: { xs: 3, md: 5 } }}>
            <Outlet />
          </Container>
        </Box>
      </Box>
    </IdentityContext>
  );
}
