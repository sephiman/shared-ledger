import { describe, expect, it } from "vitest";
import { HOME_PANELS, isPanelVisible, normalizeHiddenPanels, setPanelVisible } from "./homePanels";

describe("homePanels", () => {
  it("lists exactly the six configurable panels, in Home order", () => {
    expect(HOME_PANELS.map((p) => p.id)).toEqual([
      "savings_rate",
      "cost_of_living",
      "current_wealth",
      "portfolio",
      "money_lent",
      "pending_bank",
    ]);
  });

  it("defaults to every panel visible", () => {
    for (const p of HOME_PANELS) {
      expect(isPanelVisible(undefined, p.id)).toBe(true);
      expect(isPanelVisible([], p.id)).toBe(true);
    }
  });

  it("hiding and re-showing a panel round-trips", () => {
    const off = setPanelVisible([], "portfolio", false);
    expect(off).toEqual(["portfolio"]);
    expect(isPanelVisible(off, "portfolio")).toBe(false);
    expect(isPanelVisible(off, "savings_rate")).toBe(true);

    const on = setPanelVisible(off, "portfolio", true);
    expect(on).toEqual([]);
    expect(isPanelVisible(on, "portfolio")).toBe(true);
  });

  it("keeps other hidden panels when toggling one", () => {
    const hidden = setPanelVisible(["money_lent"], "savings_rate", false);
    expect(hidden).toEqual(["savings_rate", "money_lent"]);
    expect(setPanelVisible(hidden, "money_lent", true)).toEqual(["savings_rate"]);
  });

  it("normalizes: drops unknown ids, dedupes, and orders like the registry", () => {
    expect(normalizeHiddenPanels(["expenses_chart", "portfolio", "savings_rate", "portfolio"])).toEqual([
      "savings_rate",
      "portfolio",
    ]);
    expect(normalizeHiddenPanels(undefined)).toEqual([]);
  });

  it("setPanelVisible ignores stale unknown ids from older payloads", () => {
    expect(setPanelVisible(["legacy_panel"], "pending_bank", false)).toEqual(["pending_bank"]);
  });
});
