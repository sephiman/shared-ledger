import { describe, expect, it } from "vitest";
import type { MoneyFlowNode, MoneyFlowResponse } from "@/api/analytics";
import { OTHERS_THRESHOLD_SHARE, buildMoneyFlowSankey, resolveMoneyFlowRange } from "./moneyflow";

const OPTS = {
  labelOf: (n: MoneyFlowNode) => n.id,
  colorOf: () => "#000000",
  othersLabel: "Others",
  othersColor: "#999999",
};

function response(partial: Partial<MoneyFlowResponse>): MoneyFlowResponse {
  return {
    from: "2025-03-01",
    to: "2025-03-31",
    level: "group",
    income: "0.00",
    expenses: "0.00",
    saved: "0.00",
    deficit: "0.00",
    nodes: [],
    links: [],
    ...partial,
  };
}

const node = (id: string, side: MoneyFlowNode["side"], amount: string, groupCode: string | null = null): MoneyFlowNode => ({
  id,
  side,
  groupCode,
  amount,
});

describe("resolveMoneyFlowRange", () => {
  const today = new Date(2025, 6, 18); // 2025-07-18

  it("resolves a calendar month", () => {
    expect(resolveMoneyFlowRange({ scope: "month", year: 2025, month: 2, customFrom: "", customTo: "" }, today))
      .toEqual({ from: "2025-02-01", to: "2025-02-28" });
  });

  it("resolves a calendar year", () => {
    expect(resolveMoneyFlowRange({ scope: "year", year: 2024, month: 1, customFrom: "", customTo: "" }, today))
      .toEqual({ from: "2024-01-01", to: "2024-12-31" });
  });

  it("resolves trailing 12 months ending with the current month", () => {
    expect(resolveMoneyFlowRange({ scope: "trailing12", year: 2025, month: 7, customFrom: "", customTo: "" }, today))
      .toEqual({ from: "2024-08-01", to: "2025-07-31" });
  });

  it("resolves trailing 12 months across a year boundary", () => {
    const january = new Date(2026, 0, 5);
    expect(resolveMoneyFlowRange({ scope: "trailing12", year: 2026, month: 1, customFrom: "", customTo: "" }, january))
      .toEqual({ from: "2025-02-01", to: "2026-01-31" });
  });

  it("resolves year-to-date up to today", () => {
    expect(resolveMoneyFlowRange({ scope: "ytd", year: 2025, month: 7, customFrom: "", customTo: "" }, today))
      .toEqual({ from: "2025-01-01", to: "2025-07-18" });
  });

  it("passes a valid custom range through and rejects incomplete or inverted ones", () => {
    const base = { scope: "custom" as const, year: 2025, month: 7 };
    expect(resolveMoneyFlowRange({ ...base, customFrom: "2025-01-15", customTo: "2025-02-20" }, today))
      .toEqual({ from: "2025-01-15", to: "2025-02-20" });
    expect(resolveMoneyFlowRange({ ...base, customFrom: "", customTo: "2025-02-20" }, today)).toBeNull();
    expect(resolveMoneyFlowRange({ ...base, customFrom: "2025-03-01", customTo: "2025-02-20" }, today)).toBeNull();
  });
});

describe("buildMoneyFlowSankey", () => {
  it("returns empty data for an empty period", () => {
    expect(buildMoneyFlowSankey(response({}), OPTS)).toEqual({ nodes: [], links: [] });
  });

  it("maps semantic nodes to index-based links around the hub", () => {
    const data = response({
      income: "1100.00",
      expenses: "500.00",
      saved: "600.00",
      nodes: [
        node("income.salary", "income", "1000.00"),
        node("income.financial", "income", "100.00"),
        node("hub", "hub", "1100.00"),
        node("home", "expense", "300.00", "home"),
        node("groceries", "expense", "200.00", "groceries"),
        node("saved", "saved", "600.00"),
      ],
      links: [],
    });

    const r = buildMoneyFlowSankey(data, OPTS);
    expect(r.nodes.map((n) => n.id)).toEqual([
      "income.salary", "income.financial", "hub", "home", "groceries", "saved",
    ]);
    expect(r.nodes.map((n) => n.column)).toEqual(["left", "left", "center", "right", "right", "right"]);
    expect(r.links).toEqual([
      { source: 0, target: 2, value: 1000 },
      { source: 1, target: 2, value: 100 },
      { source: 2, target: 3, value: 300 },
      { source: 2, target: 4, value: 200 },
      { source: 2, target: 5, value: 600 },
    ]);
    // Shares are relative to the node's own side.
    expect(r.nodes[0].share).toBeCloseTo((1000 / 1100) * 100, 5);
    expect(r.nodes[0].shareOf).toBe("income");
    expect(r.nodes[3].share).toBeCloseTo(60, 5);
    expect(r.nodes[3].shareOf).toBe("expenses");
    expect(r.nodes[5].share).toBeCloseTo((600 / 1100) * 100, 5);
    expect(r.nodes[5].shareOf).toBe("income");
    expect(r.nodes[2].share).toBeNull();
  });

  it("collapses flows below the threshold into Others but keeps flows exactly at it", () => {
    expect(OTHERS_THRESHOLD_SHARE).toBe(0.02);
    const data = response({
      income: "1000.00",
      expenses: "1000.00",
      nodes: [
        node("income.salary", "income", "980.00"),
        node("income.other", "income", "10.00"),
        node("income.benefits", "income", "10.00"),
        node("hub", "hub", "1000.00"),
        node("home", "expense", "950.00", "home"),
        node("personal", "expense", "30.00", "personal"),
        node("shopping", "expense", "20.00", "shopping"), // exactly 2% -> stays
      ],
      links: [],
    });

    const r = buildMoneyFlowSankey(data, OPTS);
    expect(r.nodes.map((n) => n.id)).toEqual([
      "income.salary", "__others_income__", "hub", "home", "personal", "shopping",
    ]);
    const others = r.nodes[1];
    expect(others.amount).toBe(20);
    expect(others.share).toBeCloseTo(2, 5);
    expect(others.members).toEqual([
      { name: "income.other", amount: 10, share: 1 },
      { name: "income.benefits", amount: 10, share: 1 },
    ]);
    expect(r.links[1]).toEqual({ source: 1, target: 2, value: 20 });
  });

  it("keeps the deficit node on the left and never collapses it", () => {
    const data = response({
      income: "100.00",
      expenses: "700.00",
      deficit: "600.00",
      nodes: [
        node("income.salary", "income", "100.00"),
        node("deficit", "deficit", "600.00"),
        node("hub", "hub", "700.00"),
        node("home", "expense", "700.00", "home"),
      ],
      links: [],
    });

    const r = buildMoneyFlowSankey(data, OPTS);
    expect(r.nodes.map((n) => n.id)).toEqual(["income.salary", "deficit", "hub", "home"]);
    const deficit = r.nodes[1];
    expect(deficit.column).toBe("left");
    expect(deficit.share).toBeCloseTo((600 / 700) * 100, 5);
    expect(deficit.shareOf).toBe("expenses");
    expect(r.links).toContainEqual({ source: 1, target: 2, value: 600 });
  });
});
