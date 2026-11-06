import { StrictMode } from "react";
import { createRoot } from "react-dom/client";
import { createBrowserRouter, Navigate, RouterProvider } from "react-router";
import { CssBaseline, ThemeProvider } from "@mui/material";
import App from "./App";
import { theme } from "./theme";
import AdminPage from "./pages/AdminPage";
import DashboardPage from "./pages/DashboardPage";
import LoginPage from "./pages/LoginPage";
import AccountsPage from "./pages/AccountsPage";
import ActivitiesPage from "./pages/ActivitiesPage";
import HarnessesPage from "./pages/HarnessesPage";
import SetupPage from "./pages/SetupPage";
import RunPage from "./pages/RunPage";
import RunsPage from "./pages/RunsPage";
import SessionPage from "./pages/SessionPage";
import SessionsPage from "./pages/SessionsPage";
import TeamPage from "./pages/TeamPage";
import TeamsPage from "./pages/TeamsPage";

const router = createBrowserRouter([
  { path: "/login", element: <LoginPage /> },
  {
    path: "/",
    element: <App />,
    children: [
      { index: true, element: <DashboardPage /> },
      { path: "activities", element: <ActivitiesPage /> },
      { path: "harnesses", element: <HarnessesPage /> },
      { path: "accounts", element: <AccountsPage /> },
      { path: "setup", element: <SetupPage /> },
      { path: "marketplace", element: <Navigate to="/activities" replace /> },
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
