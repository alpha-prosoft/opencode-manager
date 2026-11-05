import { useEffect, useState } from "react";
import { Link as RouterLink, Navigate, Outlet, useLocation } from "react-router";
import {
  AppBar,
  Box,
  Button,
  CircularProgress,
  Container,
  Drawer,
  List,
  ListItemButton,
  ListItemIcon,
  ListItemText,
  Toolbar,
  Typography,
} from "@mui/material";
import AdminPanelSettingsIcon from "@mui/icons-material/AdminPanelSettings";
import DashboardIcon from "@mui/icons-material/Dashboard";
import GroupsIcon from "@mui/icons-material/Groups";
import HistoryIcon from "@mui/icons-material/History";
import LogoutIcon from "@mui/icons-material/Logout";
import StorefrontIcon from "@mui/icons-material/Storefront";
import TerminalIcon from "@mui/icons-material/Terminal";
import { ApiError, api, type Me } from "./api";
import { IdentityContext } from "./auth";

const DRAWER_WIDTH = 220;

type AuthState = { status: "loading" } | { status: "anonymous" } | { status: "ready"; identity: Me };

const NAV = [
  { to: "/", label: "Dashboard", icon: <DashboardIcon /> },
  { to: "/marketplace", label: "Marketplace", icon: <StorefrontIcon /> },
  { to: "/teams", label: "Teams", icon: <GroupsIcon /> },
  { to: "/runs", label: "Runs", icon: <HistoryIcon /> },
  { to: "/sessions", label: "Sessions", icon: <TerminalIcon /> },
];

export default function App() {
  const location = useLocation();
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
  const items = auth.identity.platform_admin ? [...NAV, { to: "/admin", label: "Admin", icon: <AdminPanelSettingsIcon /> }] : NAV;
  const active = (to: string) => (to === "/" ? location.pathname === "/" : location.pathname.startsWith(to));

  return (
    <IdentityContext value={auth.identity}>
      <Box sx={{ display: "flex", minHeight: "100vh", bgcolor: "background.default" }}>
        <AppBar position="fixed" elevation={0} sx={{ zIndex: (theme) => theme.zIndex.drawer + 1 }}>
          <Toolbar sx={{ gap: 2 }}>
            <Typography variant="h6" component={RouterLink} to="/" sx={{ flexGrow: 1, color: "inherit", textDecoration: "none" }}>
              Agent Platform
            </Typography>
            <Typography variant="body2">{auth.identity.name ?? auth.identity.sub}</Typography>
            <Button color="inherit" startIcon={<LogoutIcon />} onClick={logout}>
              Log out
            </Button>
          </Toolbar>
        </AppBar>
        <Drawer
          variant="permanent"
          sx={{ width: DRAWER_WIDTH, flexShrink: 0, "& .MuiDrawer-paper": { width: DRAWER_WIDTH, boxSizing: "border-box" } }}
        >
          <Toolbar />
          <List>
            {items.map((item) => (
              <ListItemButton key={item.to} component={RouterLink} to={item.to} selected={active(item.to)}>
                <ListItemIcon>{item.icon}</ListItemIcon>
                <ListItemText primary={item.label} />
              </ListItemButton>
            ))}
          </List>
        </Drawer>
        <Box component="main" sx={{ flexGrow: 1, minWidth: 0 }}>
          <Toolbar />
          <Container maxWidth="lg" sx={{ py: 4 }}>
            <Outlet />
          </Container>
        </Box>
      </Box>
    </IdentityContext>
  );
}
