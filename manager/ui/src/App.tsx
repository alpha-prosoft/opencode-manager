import { useEffect, useState } from "react";
import { Link as RouterLink, Outlet } from "react-router";
import { AppBar, Box, Container, Toolbar, Typography } from "@mui/material";
import { api, type Identity } from "./api";

export default function App() {
  const [identity, setIdentity] = useState<Identity | null>(null);

  useEffect(() => {
    api.me().then(setIdentity).catch(() => setIdentity(null));
  }, []);

  return (
    <Box sx={{ minHeight: "100vh", bgcolor: "background.default" }}>
      <AppBar position="static" elevation={0}>
        <Toolbar>
          <Typography
            variant="h6"
            component={RouterLink}
            to="/"
            sx={{ flexGrow: 1, color: "inherit", textDecoration: "none" }}
          >
            OpenCode Sessions
          </Typography>
          {identity && <Typography variant="body2">{identity.email}</Typography>}
        </Toolbar>
      </AppBar>
      <Container sx={{ py: 4 }}>
        <Outlet />
      </Container>
    </Box>
  );
}
