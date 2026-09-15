import { afterEach, beforeAll, describe, expect, it, vi } from "vitest";
import { cleanup, render, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { MemoryRouter, Route, Routes } from "react-router-dom";
import { ResetPasswordPage } from "@/auth/ResetPasswordPage";
import { confirmPasswordReset, validatePasswordResetToken } from "@/api/auth";
import i18n from "@/i18n";

vi.mock("@/api/auth", () => ({
  useAuthFeatures: () => ({ data: { passwordReset: true } }),
  validatePasswordResetToken: vi.fn(),
  confirmPasswordReset: vi.fn(),
}));

function renderAt(search: string) {
  render(
    <MemoryRouter initialEntries={[`/reset-password${search}`]}>
      <Routes>
        <Route path="/reset-password" element={<ResetPasswordPage />} />
        <Route path="/login" element={<p>login page</p>} />
      </Routes>
    </MemoryRouter>,
  );
}

beforeAll(async () => {
  await i18n.changeLanguage("en");
});

afterEach(() => {
  cleanup();
  vi.clearAllMocks();
});

describe("reset password page", () => {
  it("rejects a mismatched confirmation before calling the server", async () => {
    vi.mocked(validatePasswordResetToken).mockResolvedValue();
    renderAt("?token=abc");
    const user = userEvent.setup();

    await user.type(await screen.findByLabelText(i18n.t("auth.new_password")), "password1234");
    await user.type(screen.getByLabelText(i18n.t("auth.confirm_new_password")), "password9999");
    await user.click(screen.getByRole("button", { name: i18n.t("auth.set_new_password") }));

    expect(screen.getByText(i18n.t("errors.password_confirmation_mismatch"))).toBeInTheDocument();
    expect(confirmPasswordReset).not.toHaveBeenCalled();
  });

  it("submits matching passwords and lands on the login page flagged as reset", async () => {
    vi.mocked(validatePasswordResetToken).mockResolvedValue();
    vi.mocked(confirmPasswordReset).mockResolvedValue();
    renderAt("?token=abc");
    const user = userEvent.setup();

    await user.type(await screen.findByLabelText(i18n.t("auth.new_password")), "password1234");
    await user.type(screen.getByLabelText(i18n.t("auth.confirm_new_password")), "password1234");
    await user.click(screen.getByRole("button", { name: i18n.t("auth.set_new_password") }));

    expect(await screen.findByText("login page")).toBeInTheDocument();
    expect(confirmPasswordReset).toHaveBeenCalledWith("abc", "password1234");
  });

  it("offers a new link when the token is rejected, without saying why", async () => {
    vi.mocked(validatePasswordResetToken).mockRejectedValue({
      response: { data: { code: "PASSWORD_RESET_TOKEN_INVALID", message: "nope" } },
    });
    renderAt("?token=expired");

    expect(await screen.findByRole("alert")).toHaveTextContent(i18n.t("auth.reset_link_invalid"));
    expect(screen.getByRole("link", { name: i18n.t("auth.request_new_link") })).toHaveAttribute("href", "/forgot-password");
    expect(screen.queryByLabelText(i18n.t("auth.new_password"))).not.toBeInTheDocument();
  });

  it("treats a missing token as an invalid link without asking the server", () => {
    renderAt("");

    expect(screen.getByRole("alert")).toHaveTextContent(i18n.t("auth.reset_link_invalid"));
    expect(validatePasswordResetToken).not.toHaveBeenCalled();
  });
});
