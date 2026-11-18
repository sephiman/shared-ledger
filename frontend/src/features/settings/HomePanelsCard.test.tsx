import { afterEach, beforeAll, describe, expect, it, vi } from "vitest";
import { cleanup, render, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { HomePanelsCard } from "@/features/settings/HomePanelsCard";
import i18n from "@/i18n";

const mutate = vi.fn();
let hiddenHomePanels: string[] = [];

vi.mock("@/auth/AuthContext", () => ({
  useAuth: () => ({
    user: {
      id: "u1",
      email: "user@example.com",
      locale: "en",
      defaultHouseholdId: "h1",
      hiddenHomePanels,
      portfolioReturnBasis: "OPEN_COST",
      dismissedConsentNotices: [],
      households: [{ householdId: "h1", name: "Home", currency: "EUR", role: "owner" }],
    },
  }),
}));

vi.mock("@/api/settings", () => ({
  useUpdateHomePanels: () => ({ mutate, isPending: false }),
}));

beforeAll(async () => {
  await i18n.changeLanguage("en");
});

afterEach(() => {
  cleanup();
  hiddenHomePanels = [];
  vi.clearAllMocks();
});

describe("HomePanelsCard", () => {
  it("lists all seven panels in Home order with Cash right before Pending bank movements", () => {
    render(<HomePanelsCard />);

    const checkboxes = screen.getAllByRole("checkbox");
    const labels = checkboxes.map((el) => el.closest("label")!.textContent?.replace(/ⓘ/g, "").trim());
    expect(labels).toEqual([
      "Savings rate",
      "Cost of living",
      "Current wealth",
      "Portfolio",
      "Money lent",
      "Cash",
      "Pending bank movements",
    ]);
  });

  it("toggles Cash visibility via useUpdateHomePanels", async () => {
    const user = userEvent.setup();
    render(<HomePanelsCard />);

    await user.click(screen.getByRole("checkbox", { name: /^Cash/ }));
    expect(mutate).toHaveBeenCalledWith(["cash"], expect.any(Object));
  });

  it("reflects Cash as unchecked when hidden", () => {
    hiddenHomePanels = ["cash"];
    render(<HomePanelsCard />);

    expect(screen.getByRole("checkbox", { name: /^Cash/ })).not.toBeChecked();
    expect(screen.getByRole("checkbox", { name: /^Pending bank movements/ })).toBeChecked();
  });
});
