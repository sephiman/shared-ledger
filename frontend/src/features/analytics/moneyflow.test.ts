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

  it("never folds a node listed in neverCollapse into Others, however small", () => {
    const data = response({
      income: "1000.00",
      expenses: "960.00",
      saved: "40.00",
      nodes: [
        node("income.salary", "income", "1000.00"),
        node("hub", "hub", "1000.00"),
        node("home", "expense", "950.00", "home"),
        node("leisure", "expense", "0.00", "leisure"),
        node("personal", "expense", "10.00", "personal"),
        node("saved", "saved", "40.00"),
      ],
      links: [],
    });

    const r = buildMoneyFlowSankey(data, { ...OPTS, neverCollapse: new Set(["expense:leisure"]) });

    expect(r.nodes.map((n) => n.id)).toEqual(["income.salary", "hub", "home", "leisure", "__others_expense__", "saved"]);
    expect(r.nodes[4].members?.map((m) => m.name)).toEqual(["personal"]);
  });
});

describe("buildMoneyFlowSankey with contributions and withdrawals", () => {
  /** Hub inflow equals hub outflow and equals the hub node; every other node's link carries exactly its amount. */
  function expectBalanced(r: ReturnType<typeof buildMoneyFlowSankey>) {
    const hub = r.nodes.findIndex((n) => n.side === "hub");
    const inflow = r.links.filter((l) => l.target === hub).reduce((a, l) => a + l.value, 0);
    const outflow = r.links.filter((l) => l.source === hub).reduce((a, l) => a + l.value, 0);
    expect(inflow).toBeCloseTo(outflow, 6);
    expect(r.nodes[hub].amount).toBeCloseTo(inflow, 6);
    r.nodes.forEach((n, i) => {
      if (i === hub) return;
      const link = r.links.find((l) => l.source === i || l.target === i);
      expect(link?.value).toBe(n.amount);
    });
  }

  it("balances a surplus partly contributed", () => {
    const r = buildMoneyFlowSankey(
      response({
        income: "1000.00",
        expenses: "400.00",
        saved: "600.00",
        nodes: [
          node("income.salary", "income", "1000.00"),
          node("hub", "hub", "1000.00"),
          node("home", "expense", "400.00", "home"),
          node("contribution:etfs", "contribution", "300.00"),
          node("stays_in_cash", "stays_in_cash", "300.00"),
        ],
      }),
      OPTS,
    );
    expect(r.nodes.map((n) => [n.id, n.column])).toEqual([
      ["income.salary", "left"],
      ["hub", "center"],
      ["home", "right"],
      ["contribution:etfs", "right"],
      ["stays_in_cash", "right"],
    ]);
    expectBalanced(r);
  });

  it("balances contributions beyond the surplus, drawing on cash", () => {
    const r = buildMoneyFlowSankey(
      response({
        income: "1000.00",
        expenses: "400.00",
        saved: "600.00",
        nodes: [
          node("income.salary", "income", "1000.00"),
          node("from_cash", "from_cash", "300.00"),
          node("hub", "hub", "1300.00"),
          node("home", "expense", "400.00", "home"),
          node("contribution:etfs", "contribution", "600.00"),
          node("contribution:crypto", "contribution", "300.00"),
        ],
      }),
      OPTS,
    );
    expect(r.nodes.find((n) => n.id === "from_cash")?.column).toBe("left");
    expectBalanced(r);
  });

  it("balances a deficit covered by a withdrawal", () => {
    const r = buildMoneyFlowSankey(
      response({
        income: "100.00",
        expenses: "700.00",
        deficit: "600.00",
        nodes: [
          node("income.salary", "income", "100.00"),
          node("withdrawal:crypto", "withdrawal", "800.00"),
          node("hub", "hub", "900.00"),
          node("home", "expense", "700.00", "home"),
          node("stays_in_cash", "stays_in_cash", "200.00"),
        ],
      }),
      OPTS,
    );
    expect(r.nodes.map((n) => n.id)).toEqual(["income.salary", "withdrawal:crypto", "hub", "home", "stays_in_cash"]);
    expect(r.nodes[1].column).toBe("left");
    expectBalanced(r);
  });

  it("balances a period with no movements", () => {
    const r = buildMoneyFlowSankey(
      response({
        income: "1000.00",
        expenses: "400.00",
        saved: "600.00",
        nodes: [
          node("income.salary", "income", "1000.00"),
          node("hub", "hub", "1000.00"),
          node("home", "expense", "400.00", "home"),
          node("stays_in_cash", "stays_in_cash", "600.00"),
        ],
      }),
      OPTS,
    );
    expectBalanced(r);
  });

  it("never folds a contribution or withdrawal class into Others, however small", () => {
    const r = buildMoneyFlowSankey(
      response({
        income: "10000.00",
        expenses: "9000.00",
        saved: "1000.00",
        nodes: [
          node("income.salary", "income", "10000.00"),
          node("withdrawal:crypto", "withdrawal", "5.00"),
          node("hub", "hub", "10005.00"),
          node("home", "expense", "9000.00", "home"),
          node("contribution:fund", "contribution", "5.00"),
          node("stays_in_cash", "stays_in_cash", "1000.00"),
        ],
      }),
      OPTS,
    );
    expect(r.nodes.map((n) => n.id)).toContain("withdrawal:crypto");
    expect(r.nodes.map((n) => n.id)).toContain("contribution:fund");
    expect(r.nodes.some((n) => n.side === "others")).toBe(false);
    expectBalanced(r);
  });
});

describe("buildMoneyFlowSankey with net refunds", () => {
  it("puts a net refund source after income and before withdrawals, never collapsed, and balances", () => {
    const r = buildMoneyFlowSankey(
      response({
        income: "1000.00",
        expenses: "350.00",
        saved: "650.00",
        nodes: [
          node("income.salary", "income", "1000.00"),
          node("net_refund:outings", "net_refund", "5.00", "outings"),
          node("withdrawal:crypto", "withdrawal", "100.00"),
          node("hub", "hub", "1105.00"),
          node("home", "expense", "355.00", "home"),
          node("contribution:etfs", "contribution", "500.00"),
          node("stays_in_cash", "stays_in_cash", "250.00"),
        ],
      }),
      OPTS,
    );

    expect(r.nodes.map((n) => [n.id, n.column])).toEqual([
      ["income.salary", "left"],
      ["net_refund:outings", "left"],
      ["withdrawal:crypto", "left"],
      ["hub", "center"],
      ["home", "right"],
      ["contribution:etfs", "right"],
      ["stays_in_cash", "right"],
    ]);
    const hub = 3;
    const inflow = r.links.filter((l) => l.target === hub).reduce((a, l) => a + l.value, 0);
    const outflow = r.links.filter((l) => l.source === hub).reduce((a, l) => a + l.value, 0);
    expect(inflow).toBe(1105);
    expect(outflow).toBe(1105);
  });
});

describe("buildMoneyFlowSankey by group and by category", () => {
  it("draws per-direction and per-class movement nodes that add up to the same hub and side totals", () => {
    const base = { income: "1000.00", expenses: "400.00", saved: "600.00" };
    const byCategory = buildMoneyFlowSankey(
      response({
        ...base,
        level: "category",
        nodes: [
          node("income.salary", "income", "1000.00"),
          node("withdrawal:crypto", "withdrawal", "80.00"),
          node("withdrawal:fund", "withdrawal", "20.00"),
          node("hub", "hub", "1100.00"),
          node("home.rent", "expense", "400.00", "home"),
          node("contribution:etfs", "contribution", "500.00"),
          node("contribution:crypto", "contribution", "150.00"),
          node("stays_in_cash", "stays_in_cash", "50.00"),
        ],
      }),
      OPTS,
    );
    const byGroup = buildMoneyFlowSankey(
      response({
        ...base,
        nodes: [
          node("income.salary", "income", "1000.00"),
          node("withdrawn", "withdrawal", "100.00"),
          node("hub", "hub", "1100.00"),
          node("home", "expense", "400.00", "home"),
          node("contributed", "contribution", "650.00"),
          node("stays_in_cash", "stays_in_cash", "50.00"),
        ],
      }),
      OPTS,
    );

    const total = (r: typeof byGroup, side: string) => r.nodes.filter((n) => n.side === side).reduce((a, n) => a + n.amount, 0);
    for (const side of ["withdrawal", "contribution", "stays_in_cash", "hub"]) {
      expect(total(byGroup, side), side).toBe(total(byCategory, side));
    }
    expect(byGroup.nodes.map((n) => n.id)).toEqual(["income.salary", "withdrawn", "hub", "home", "contributed", "stays_in_cash"]);
    for (const r of [byGroup, byCategory]) {
      const hub = r.nodes.findIndex((n) => n.side === "hub");
      const inflow = r.links.filter((l) => l.target === hub).reduce((a, l) => a + l.value, 0);
      const outflow = r.links.filter((l) => l.source === hub).reduce((a, l) => a + l.value, 0);
      expect(inflow).toBe(1100);
      expect(outflow).toBe(1100);
    }
  });
});
