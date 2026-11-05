import { StrictMode } from "react";
import { createRoot } from "react-dom/client";
import { createBrowserRouter, RouterProvider } from "react-router";
import { CssBaseline, ThemeProvider, createTheme } from "@mui/material";
import App from "./App";
import AdminPage from "./pages/AdminPage";
import DashboardPage from "./pages/DashboardPage";
import LoginPage from "./pages/LoginPage";
import MarketplacePage from "./pages/MarketplacePage";
import RunPage from "./pages/RunPage";
import RunsPage from "./pages/RunsPage";
import SessionPage from "./pages/SessionPage";
import SessionsPage from "./pages/SessionsPage";
import TeamPage from "./pages/TeamPage";
import TeamsPage from "./pages/TeamsPage";

const theme = createTheme({ colorSchemes: { dark: true }, shape: { borderRadius: 10 } });

const router = createBrowserRouter([
  { path: "/login", element: <LoginPage /> },
  {
    path: "/",
    element: <App />,
    children: [
      { index: true, element: <DashboardPage /> },
      { path: "marketplace", element: <MarketplacePage /> },
      { path: "teams", element: <TeamsPage /> },
      { path: "teams/:teamId", element: <TeamPage /> },
      { path: "runs", element: <RunsPage /> },
      { path: "runs/:runId", element: <RunPage /> },
      { path: "sessions", element: <SessionsPage /> },
      { path: "sessions/:sessionId", element: <SessionPage /> },
      { path: "admin", element: <AdminPage /> },
    ],
  },
]);

createRoot(document.getElementById("root")!).render(
  <StrictMode>
    <ThemeProvider theme={theme}>
      <CssBaseline />
      <RouterProvider router={router} />
    </ThemeProvider>
  </StrictMode>,
);
