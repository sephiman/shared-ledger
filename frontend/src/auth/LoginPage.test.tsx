import { afterEach, beforeAll, describe, expect, it, vi } from "vitest";
import { cleanup, render, screen } from "@testing-library/react";
import { MemoryRouter } from "react-router-dom";
import { LoginPage } from "@/auth/LoginPage";
import { useAuthFeatures } from "@/api/auth";
import { ThemeProvider } from "@/lib/theme";
import i18n from "@/i18n";

vi.mock("@/auth/AuthContext", () => ({
  useAuth: () => ({ login: vi.fn() }),
}));

vi.mock("@/api/auth", () => ({
  useAuthFeatures: vi.fn(),
}));

function renderLogin(passwordReset: boolean | undefined, search = "") {
  vi.mocked(useAuthFeatures).mockReturnValue({
    data: passwordReset === undefined ? undefined : { passwordReset },
  } as unknown as ReturnType<typeof useAuthFeatures>);
  render(
    <ThemeProvider>
      <MemoryRouter initialEntries={[`/login${search}`]}>
        <LoginPage />
      </MemoryRouter>
    </ThemeProvider>,
  );
}

beforeAll(async () => {
  await i18n.changeLanguage("en");
});

afterEach(() => {
  cleanup();
  vi.clearAllMocks();
});

describe("login page password-reset entry", () => {
  it("offers the forgot-password link once the server reports the reset as enabled", () => {
    renderLogin(true);

    expect(screen.getByRole("link", { name: i18n.t("auth.forgot_password") })).toHaveAttribute("href", "/forgot-password");
  });

  it("shows no forgot-password link while the reset is disabled or not yet known", () => {
    renderLogin(false);
    expect(screen.queryByRole("link", { name: i18n.t("auth.forgot_password") })).not.toBeInTheDocument();
    cleanup();

    renderLogin(undefined);
    expect(screen.queryByRole("link", { name: i18n.t("auth.forgot_password") })).not.toBeInTheDocument();
  });

  it("confirms the new password when arriving from a completed reset", () => {
    renderLogin(true, "?reset=done");

    expect(screen.getByRole("status")).toHaveTextContent(i18n.t("auth.reset_done"));
  });
});
