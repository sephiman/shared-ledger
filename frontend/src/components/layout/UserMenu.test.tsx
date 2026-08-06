import { afterEach, describe, expect, it, vi } from "vitest";
import { cleanup, render, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { MemoryRouter } from "react-router-dom";
import { UserMenu } from "@/components/layout/UserMenu";
import { ThemeProvider } from "@/lib/theme";
import i18n from "@/i18n";

vi.mock("@/auth/AuthContext", () => ({
  useAuth: () => ({
    user: { email: "ada@example.com", households: [{ householdId: "h1", name: "Home" }], defaultHouseholdId: "h1" },
    logout: vi.fn(),
    activeHouseholdId: "h1",
    setActiveHouseholdId: vi.fn(),
  }),
}));

async function openMenu() {
  const user = userEvent.setup();
  render(
    <ThemeProvider>
      <MemoryRouter>
        <UserMenu />
      </MemoryRouter>
    </ThemeProvider>,
  );
  await user.click(screen.getByRole("button", { name: i18n.t("user.menu_aria") }));
  return user;
}

afterEach(async () => {
  cleanup();
  localStorage.clear();
  document.documentElement.className = "";
  await i18n.changeLanguage("en");
});

describe("theme selector", () => {
  it("offers OLED as a fourth option alongside the three that already existed", async () => {
    await openMenu();

    for (const label of ["Light", "Dark", "OLED", "System"]) {
      expect(screen.getByRole("button", { name: label })).toBeInTheDocument();
    }
  });

  it("applies OLED on click, adding the oled class on top of dark", async () => {
    const user = await openMenu();

    await user.click(screen.getByRole("button", { name: "OLED" }));

    expect(Array.from(document.documentElement.classList).sort()).toEqual(["dark", "oled"]);
    expect(localStorage.getItem("theme")).toBe("oled");
  });

  it("marks the active option, and moves the marking when another is picked", async () => {
    const user = await openMenu();
    const oled = screen.getByRole("button", { name: "OLED" });
    const dark = screen.getByRole("button", { name: "Dark" });

    await user.click(oled);
    expect(oled.className).toContain("text-primary");
    expect(dark.className).not.toContain("text-primary");

    await user.click(dark);
    expect(dark.className).toContain("text-primary");
    expect(oled.className).not.toContain("text-primary");
  });

  it("keeps OLED untranslated in Spanish, where the other options are localised", async () => {
    await i18n.changeLanguage("es");
    await openMenu();

    expect(screen.getByRole("button", { name: "Claro" })).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "Oscuro" })).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "Sistema" })).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "OLED" })).toBeInTheDocument();
  });
});
