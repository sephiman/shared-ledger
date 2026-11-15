import { afterEach, describe, expect, it } from "vitest";
import { act, cleanup, render, screen } from "@testing-library/react";
import { MemoryRouter } from "react-router-dom";
import { AppFooter } from "@/components/layout/AppFooter";
import { ThemeProvider } from "@/lib/theme";
import i18n from "@/i18n";
import logoDark from "@/assets/SharedLedgerDark.png";
import logoLight from "@/assets/SharedLedgerLight.png";

function renderFooter() {
  return render(
    <ThemeProvider>
      <MemoryRouter>
        <AppFooter />
      </MemoryRouter>
    </ThemeProvider>,
  );
}

afterEach(async () => {
  cleanup();
  localStorage.clear();
  document.documentElement.className = "";
  await i18n.changeLanguage("en");
});

describe("AppFooter", () => {
  it("shows the SharedLedger logo, attribution, and manual link in English and Spanish", async () => {
    renderFooter();

    expect(screen.getByRole("img", { name: "Shared Ledger" })).toHaveAttribute("src", logoLight);
    expect(screen.getByRole("link", { name: "sephimandev" })).toHaveAttribute("href", "https://github.com/sephiman");
    expect(screen.getByText((_, el) => el?.tagName === "SPAN" && el.textContent === "a sephimandev app")).toBeInTheDocument();
    expect(screen.getByRole("link", { name: "Help" })).toHaveAttribute("href", "/manual");

    await act(async () => {
      await i18n.changeLanguage("es");
    });

    expect(screen.getByRole("link", { name: "sephimandev" })).toHaveAttribute("href", "https://github.com/sephiman");
    expect(screen.getByText((_, el) => el?.tagName === "SPAN" && el.textContent === "una app de sephimandev")).toBeInTheDocument();
    expect(screen.getByRole("link", { name: "Ayuda" })).toHaveAttribute("href", "/manual");
  });

  it("uses the dark logo variant when dark theme is active", () => {
    localStorage.setItem("theme", "dark");
    renderFooter();

    expect(screen.getByRole("img", { name: "Shared Ledger" })).toHaveAttribute("src", logoDark);
  });
});
