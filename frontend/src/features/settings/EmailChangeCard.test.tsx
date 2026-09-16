import { afterEach, beforeAll, describe, expect, it, vi } from "vitest";
import { cleanup, render, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { EmailChangeCard } from "@/features/settings/EmailChangeCard";
import { requestEmailChange } from "@/api/auth";
import i18n from "@/i18n";

const refresh = vi.fn();
const verified = { value: true };

vi.mock("@/api/auth", () => ({
  useAuthFeatures: () => ({ data: { passwordReset: true, emailChangeVerified: verified.value } }),
  requestEmailChange: vi.fn(),
}));

vi.mock("@/auth/AuthContext", () => ({
  useAuth: () => ({ user: { id: "u1", email: "old@example.com" }, refresh }),
}));

beforeAll(async () => {
  await i18n.changeLanguage("en");
});

afterEach(() => {
  cleanup();
  vi.clearAllMocks();
  verified.value = true;
});

async function fillIn(email: string, password: string) {
  const user = userEvent.setup();
  if (email) await user.type(screen.getByLabelText(i18n.t("settings.new_email")), email);
  if (password) await user.type(screen.getByLabelText(i18n.t("auth.current_password")), password);
  await user.click(screen.getByRole("button", { name: i18n.t("settings.change_email_cta") }));
}

describe("email change card", () => {
  it("rejects a malformed address before calling the server", async () => {
    render(<EmailChangeCard />);

    await fillIn("not-an-email", "password1234");

    expect(screen.getByText(i18n.t("errors.email_invalid"))).toBeInTheDocument();
    expect(requestEmailChange).not.toHaveBeenCalled();
  });

  it("asks for the current password before calling the server", async () => {
    render(<EmailChangeCard />);

    await fillIn("new@example.com", "");

    expect(screen.getByText(i18n.t("errors.field_required"))).toBeInTheDocument();
    expect(requestEmailChange).not.toHaveBeenCalled();
  });

  it("reports the address the confirmation link went to, without changing anything", async () => {
    vi.mocked(requestEmailChange).mockResolvedValue({ status: "pending", email: "new@example.com" });
    render(<EmailChangeCard />);

    await fillIn("new@example.com", "password1234");

    expect(requestEmailChange).toHaveBeenCalledWith("new@example.com", "password1234");
    expect(await screen.findByRole("status")).toHaveTextContent(
      i18n.t("settings.email_change_pending", { email: "new@example.com" }),
    );
    expect(refresh).not.toHaveBeenCalled();
  });

  it("picks up the new identity when the change applied at once", async () => {
    verified.value = false;
    vi.mocked(requestEmailChange).mockResolvedValue({ status: "applied", email: "new@example.com" });
    render(<EmailChangeCard />);

    expect(screen.getByText(i18n.t("settings.change_email_direct_hint"))).toBeInTheDocument();
    await fillIn("new@example.com", "password1234");

    expect(refresh).toHaveBeenCalled();
    expect(screen.queryByRole("status")).not.toBeInTheDocument();
  });

  it("shows the server's reason when the password is wrong", async () => {
    vi.mocked(requestEmailChange).mockRejectedValue({
      response: { data: { code: "PASSWORD_MISMATCH", message: "nope" } },
    });
    render(<EmailChangeCard />);

    await fillIn("new@example.com", "wrong-password");

    expect(await screen.findByText(i18n.t("errors.PASSWORD_MISMATCH"))).toBeInTheDocument();
  });
});
