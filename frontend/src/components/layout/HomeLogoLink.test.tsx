import { afterEach, describe, expect, it, vi } from "vitest";
import { cleanup, render, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { MemoryRouter, RouterProvider, createMemoryRouter } from "react-router-dom";
import { HomeLogoLink } from "@/components/layout/HomeLogoLink";
import { LoginPage } from "@/auth/LoginPage";
import { ThemeProvider } from "@/lib/theme";
import i18n from "@/i18n";

vi.mock("@/auth/AuthContext", () => ({
  useAuth: () => ({ login: vi.fn() }),
}));

/** The logo lives in the shell, so every route renders it; the marker text tells us where we landed. */
function renderAt(path: string) {
  const router = createMemoryRouter(
    [
      { path: "/dashboard", element: <Shell marker="home view" /> },
      { path: "/analytics/daily", element: <Shell marker="deep view" /> },
    ],
    { initialEntries: [path] },
  );
  render(
    <ThemeProvider>
      <RouterProvider router={router} />
    </ThemeProvider>,
  );
  return router;
}

function Shell({ marker }: { marker: string }) {
  return (
    <>
      <HomeLogoLink />
      <p>{marker}</p>
    </>
  );
}

afterEach(async () => {
  cleanup();
  await i18n.changeLanguage("en");
});

describe("HomeLogoLink", () => {
  it("is a link to home with a destination-describing accessible name", () => {
    renderAt("/analytics/daily");

    const link = screen.getByRole("link", { name: "Go to Home" });
    expect(link).toHaveAttribute("href", "/dashboard");
    // The logo image itself is unchanged: same alt, same sizing classes, no layout-affecting additions.
    expect(screen.getByAltText("Shared Ledger")).toHaveClass("h-10", "w-auto");
  });

  it("navigates from a deep page to home on click, client-side", async () => {
    const user = userEvent.setup();
    const router = renderAt("/analytics/daily");
    expect(screen.getByText("deep view")).toBeInTheDocument();

    await user.click(screen.getByRole("link", { name: "Go to Home" }));

    expect(router.state.location.pathname).toBe("/dashboard");
    expect(screen.getByText("home view")).toBeInTheDocument();
  });

  it("navigates home when activated with Enter from the keyboard", async () => {
    const user = userEvent.setup();
    const router = renderAt("/analytics/daily");

    await user.tab();
    expect(screen.getByRole("link", { name: "Go to Home" })).toHaveFocus();
    await user.keyboard("{Enter}");

    expect(router.state.location.pathname).toBe("/dashboard");
  });

  it("replaces instead of pushing when already on home, so history does not pile up", async () => {
    const user = userEvent.setup();
    const router = renderAt("/dashboard");

    await user.click(screen.getByRole("link", { name: "Go to Home" }));

    expect(router.state.location.pathname).toBe("/dashboard");
    expect(router.state.historyAction).toBe("REPLACE");
  });

  it("labels the link in the active locale", async () => {
    await i18n.changeLanguage("es");
    renderAt("/analytics/daily");

    expect(screen.getByRole("link", { name: "Ir a Inicio" })).toHaveAttribute("href", "/dashboard");
  });

  it("leaves the login-page logo inert, so it cannot lead into a protected route", () => {
    render(
      <ThemeProvider>
        <MemoryRouter initialEntries={["/login"]}>
          <LoginPage />
        </MemoryRouter>
      </ThemeProvider>,
    );

    expect(screen.getByAltText("Shared Ledger")).toBeInTheDocument();
    expect(screen.queryByRole("link", { name: "Go to Home" })).not.toBeInTheDocument();
    expect(screen.queryByRole("link", { name: /dashboard/i })).not.toBeInTheDocument();
  });
});
