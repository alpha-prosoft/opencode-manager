import { useEffect, useState } from "react";
import { Link as RouterLink, Navigate, Outlet, useLocation } from "react-router";
import { AppBar, Box, Button, CircularProgress, Container, Toolbar, Typography } from "@mui/material";
import LogoutIcon from "@mui/icons-material/Logout";
import { ApiError, api, type Identity } from "./api";
import { IdentityContext } from "./auth";

type AuthState = { status: "loading" } | { status: "anonymous" } | { status: "ready"; identity: Identity };

export default function App() {
  const location = useLocation();
  const [auth, setAuth] = useState<AuthState>({ status: "loading" });

  useEffect(() => {
    api
      .me()
      .then((identity) => setAuth({ status: "ready", identity }))
      .catch((error) =>
        setAuth(error instanceof ApiError && error.status === 401 ? { status: "anonymous" } : { status: "loading" }),
      );
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

  return (
    <IdentityContext value={auth.identity}>
      <Box sx={{ minHeight: "100vh", bgcolor: "background.default" }}>
        <AppBar position="static" elevation={0}>
          <Toolbar sx={{ gap: 2 }}>
            <Typography
              variant="h6"
              component={RouterLink}
              to="/"
              sx={{ flexGrow: 1, color: "inherit", textDecoration: "none" }}
            >
              OpenCode Sessions
            </Typography>
            <Typography variant="body2">{auth.identity.name ?? auth.identity.sub}</Typography>
            <Button color="inherit" startIcon={<LogoutIcon />} onClick={logout}>
              Log out
            </Button>
          </Toolbar>
        </AppBar>
        <Container sx={{ py: 4 }}>
          <Outlet />
        </Container>
      </Box>
    </IdentityContext>
  );
}
