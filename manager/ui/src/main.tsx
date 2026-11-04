import { StrictMode } from "react";
import { createRoot } from "react-dom/client";
import { createBrowserRouter, RouterProvider } from "react-router";
import { CssBaseline, ThemeProvider, createTheme } from "@mui/material";
import App from "./App";
import SessionsPage from "./pages/SessionsPage";
import SessionDetailPage from "./pages/SessionDetailPage";
import LoginPage from "./pages/LoginPage";

const theme = createTheme({ colorSchemes: { dark: true } });

const router = createBrowserRouter(
  [
    { path: "/login", element: <LoginPage /> },
    {
      path: "/",
      element: <App />,
      children: [
        { index: true, element: <SessionsPage /> },
        { path: "sessions/:sessionId", element: <SessionDetailPage /> },
      ],
    },
  ],
);

createRoot(document.getElementById("root")!).render(
  <StrictMode>
    <ThemeProvider theme={theme}>
      <CssBaseline />
      <RouterProvider router={router} />
    </ThemeProvider>
  </StrictMode>,
);
