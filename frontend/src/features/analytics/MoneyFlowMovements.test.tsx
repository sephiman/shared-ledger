import type { ReactElement } from "react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { cleanup, render, screen, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { MemoryRouter } from "react-router-dom";
import type { MoneyFlowMovementClass, MoneyFlowNode, MoneyFlowResponse } from "@/api/analytics";
import i18n from "@/i18n";

const calls = vi.hoisted(() => ({ movements: [] as boolean[] }));
const state = vi.hoisted(() => ({
  withMovements: true,
  deficitCovered: false,
  ordered: false,
  netRefund: false,
  large: false,
}));

const n = (id: string, side: MoneyFlowNode["side"], amount: string, groupCode: string | null = null): MoneyFlowNode => ({
  id,
  side,
  groupCode,
  amount,
});

const PLAIN: MoneyFlowResponse = {
  from: "2025-03-01",
  to: "2025-03-31",
  level: "group",
  income: "1000.00",
  expenses: "400.00",
  saved: "600.00",
  deficit: "0.00",
  nodes: [n("income.salary", "income", "1000.00"), n("hub", "hub", "1000.00"), n("home", "expense", "400.00", "home"), n("saved", "saved", "600.00")],
  links: [],
};

const ETFS: MoneyFlowMovementClass = {
  nodeId: "contribution:etfs",
  assetClassCode: "etfs",
  direction: "contribution",
  amount: "300.00",
  movementCount: 6,
  descriptions: [
    { description: "VWCE", amount: "150.00", count: 2 },
    { description: "IWDA", amount: "60.00", count: 1 },
    { description: "EIMI", amount: "40.00", count: 1 },
  ],
  moreDescriptions: 1,
  undescribedCount: 1,
  undescribedAmount: "30.00",
};
const CRYPTO_IN: MoneyFlowMovementClass = {
  nodeId: "contribution:crypto",
  assetClassCode: "crypto",
  direction: "contribution",
  amount: "50.00",
  movementCount: 1,
  descriptions: [],
  moreDescriptions: 0,
  undescribedCount: 1,
  undescribedAmount: "50.00",
};
const CRYPTO_OUT: MoneyFlowMovementClass = {
  nodeId: "withdrawal:crypto",
  assetClassCode: "crypto",
  direction: "withdrawal",
  amount: "20.00",
  movementCount: 1,
  descriptions: [],
  moreDescriptions: 0,
  undescribedCount: 1,
  undescribedAmount: "20.00",
};

/** income 1000 + withdrawn 20 − expenses 400 − contributed 350 = 270 stays in cash. */
const MOVEMENTS = { contributed: "350.00", withdrawn: "20.00", staysInCash: "270.00", fromCash: "0.00", classes: [ETFS, CRYPTO_IN, CRYPTO_OUT] };

/** What the backend answers "by group": one node per direction. */
const BY_GROUP: MoneyFlowResponse = {
  ...PLAIN,
  nodes: [
    n("income.salary", "income", "1000.00"),
    n("withdrawn", "withdrawal", "20.00"),
    n("hub", "hub", "1020.00"),
    n("home", "expense", "400.00", "home"),
    n("contributed", "contribution", "350.00"),
    n("stays_in_cash", "stays_in_cash", "270.00"),
  ],
  movements: MOVEMENTS,
};

/** And "by category": one node per class, for the same movements. */
const BY_CATEGORY: MoneyFlowResponse = {
  ...PLAIN,
  level: "category",
  nodes: [
    n("income.salary", "income", "1000.00"),
    n("withdrawal:crypto", "withdrawal", "20.00"),
    n("hub", "hub", "1020.00"),
    n("home.rent", "expense", "400.00", "home"),
    n("contribution:etfs", "contribution", "300.00"),
    n("contribution:crypto", "contribution", "50.00"),
    n("stays_in_cash", "stays_in_cash", "270.00"),
  ],
  movements: MOVEMENTS,
};

const NO_MOVEMENTS: MoneyFlowResponse = {
  ...PLAIN,
  nodes: [n("income.salary", "income", "1000.00"), n("hub", "hub", "1000.00"), n("home", "expense", "400.00", "home"), n("stays_in_cash", "stays_in_cash", "600.00")],
  movements: { contributed: "0.00", withdrawn: "0.00", staysInCash: "600.00", fromCash: "0.00", classes: [] },
};

/** Income 1000, expenses 1800 (Deficit 800), €1,200 withdrawn from crypto: cash still went up by €400. */
const DEFICIT_COVERED: MoneyFlowResponse = {
  ...PLAIN,
  income: "1000.00",
  expenses: "1800.00",
  saved: "0.00",
  deficit: "800.00",
  nodes: [
    n("income.salary", "income", "1000.00"),
    n("withdrawn", "withdrawal", "1200.00"),
    n("hub", "hub", "2200.00"),
    n("home", "expense", "1800.00", "home"),
    n("stays_in_cash", "stays_in_cash", "400.00"),
  ],
  movements: { contributed: "0.00", withdrawn: "1200.00", staysInCash: "400.00", fromCash: "0.00", classes: [] },
};

/** Recharts' own sorting would put the large contribution first; the data's order must win. */
const ORDERED: MoneyFlowResponse = {
  ...PLAIN,
  income: "1000.00",
  expenses: "450.00",
  saved: "550.00",
  nodes: [
    n("income.salary", "income", "1000.00"),
    n("withdrawn", "withdrawal", "60.00"),
    n("from_cash", "from_cash", "10.00"),
    n("hub", "hub", "1070.00"),
    n("home", "expense", "400.00", "home"),
    n("groceries", "expense", "50.00", "groceries"),
    n("contributed", "contribution", "620.00"),
  ],
  movements: { contributed: "620.00", withdrawn: "60.00", staysInCash: "0.00", fromCash: "10.00", classes: [] },
};

/** Outings refunded €70 against €20 spent: a net refund of €50 feeding the hub, with the option off. */
const NET_REFUND: MoneyFlowResponse = {
  ...PLAIN,
  expenses: "350.00",
  saved: "650.00",
  nodes: [
    n("income.salary", "income", "1000.00"),
    n("net_refund:outings", "net_refund", "50.00", "outings"),
    n("hub", "hub", "1050.00"),
    n("home", "expense", "400.00", "home"),
    n("saved", "saved", "650.00"),
  ],
};

/** Five- and four-digit amounts, to pin the thousands grouping (Spanish Intl skips it on four digits by default). */
const LARGE_OFF: MoneyFlowResponse = {
  ...PLAIN,
  income: "12345.67",
  expenses: "2895.68",
  saved: "9449.99",
  nodes: [
    n("income.salary", "income", "12345.67"),
    n("hub", "hub", "12345.67"),
    n("home", "expense", "2895.68", "home"),
    n("saved", "saved", "9449.99"),
  ],
};
const LARGE_ON: MoneyFlowResponse = {
  ...LARGE_OFF,
  nodes: [
    n("income.salary", "income", "12345.67"),
    n("hub", "hub", "12345.67"),
    n("home", "expense", "2895.68", "home"),
    n("stays_in_cash", "stays_in_cash", "9449.99"),
  ],
  movements: { contributed: "0.00", withdrawn: "0.00", staysInCash: "9449.99", fromCash: "0.00", classes: [] },
};

vi.mock("@/auth/AuthContext", () => ({
  useActiveHousehold: () => ({ householdId: "h1", name: "Home", currency: "EUR", role: "owner" }),
}));

// jsdom measures every container at 0×0, so the responsive wrapper would never draw the diagram.
vi.mock("recharts", async (importOriginal) => {
  const { cloneElement } = await import("react");
  return {
    ...(await importOriginal<typeof import("recharts")>()),
    ResponsiveContainer: ({ children }: { children: ReactElement<{ width?: number; height?: number }> }) =>
      cloneElement(children, { width: 800, height: 448 }),
  };
});

vi.mock("@/api/catalog", () => ({ useCategories: () => ({ data: [] }) }));
// The month projection has its own tests (MoneyFlowTab.test.tsx), including its note about contributions and withdrawals.
vi.mock("./MoneyFlowProjectionPanel", () => ({ MoneyFlowProjectionPanel: () => null }));

vi.mock("@/api/analytics", async (importOriginal) => ({
  ...(await importOriginal<typeof import("@/api/analytics")>()),
  useYearsAvailable: () => ({ data: { years: [2025] } }),
  useMoneyFlow: (_h: string, _from: string, _to: string, level: string, _enabled: boolean, movements = false) => {
    calls.movements.push(movements);
    if (state.large) return { data: movements ? LARGE_ON : LARGE_OFF, isLoading: false };
    if (!movements) return { data: state.netRefund ? NET_REFUND : PLAIN, isLoading: false };
    if (state.ordered) return { data: ORDERED, isLoading: false };
    if (state.deficitCovered) return { data: DEFICIT_COVERED, isLoading: false };
    if (!state.withMovements) return { data: NO_MOVEMENTS, isLoading: false };
    return { data: level === "category" ? BY_CATEGORY : BY_GROUP, isLoading: false };
  },
}));

const { MoneyFlowTab } = await import("./MoneyFlowTab");

const renderTab = () =>
  render(
    <MemoryRouter>
      <MoneyFlowTab />
    </MemoryRouter>,
  );
const toggle = () => screen.getByRole("checkbox", { name: /^(Show contributions and withdrawals|Mostrar aportaciones y retiradas)$/ });
const diagram = () => within(document.querySelector("svg.recharts-surface") as unknown as HTMLElement);
const diagramText = () => document.querySelector("svg.recharts-surface")?.textContent ?? "";
/** Scope, year, month, breakdown: the breakdown is the fourth select of the real panel. */
const breakdown = () => screen.getAllByRole("combobox")[3];
const totalsLine = () => (screen.getByText(/^(Income|Ingresos):/).parentElement as HTMLElement).textContent;

beforeEach(() => {
  localStorage.clear();
  calls.movements = [];
  state.withMovements = true;
  state.deficitCovered = false;
  state.ordered = false;
  state.netRefund = false;
  state.large = false;
  vi.useFakeTimers({ toFake: ["Date"] });
  vi.setSystemTime(new Date(2025, 2, 18));
});

afterEach(async () => {
  cleanup();
  vi.useRealTimers();
  localStorage.clear();
  await i18n.changeLanguage("en");
});

describe("Money flow contributions and withdrawals option", () => {
  it("is off by default and asks only for the plain diagram", () => {
    renderTab();

    expect(toggle()).not.toBeChecked();
    expect(calls.movements.every((on) => !on)).toBe(true);
    expect(screen.queryByText("Stays in cash")).toBeNull();
    expect(screen.queryByText(/^Contributed:/)).toBeNull();
    expect(screen.queryByText(/^Withdrawn:/)).toBeNull();
    expect(screen.getByText(/^Saved:/)).toHaveTextContent("€600.00");
  });

  it("by group draws one node per direction, adds separate totals, and keeps the existing figures", async () => {
    const user = userEvent.setup();
    renderTab();

    await user.click(toggle());

    expect(calls.movements.at(-1)).toBe(true);
    expect(diagram().getByText("Contributed to wealth")).toBeInTheDocument();
    expect(diagram().getByText("Withdrawn from wealth")).toBeInTheDocument();
    expect(diagram().getByText("Stays in cash")).toBeInTheDocument();
    expect(diagram().queryByText("ETFs")).toBeNull();
    expect(screen.getByText(/^Contributed:/)).toHaveTextContent("€350.00");
    expect(screen.getByText(/^Withdrawn:/)).toHaveTextContent("€20.00");
    expect(screen.getByText(/^Income:/)).toHaveTextContent("€1,000.00");
    expect(screen.getByText(/^Expenses:/)).toHaveTextContent("€400.00");
    expect(screen.getByText(/^Saved:/)).toHaveTextContent("€600.00");
  });

  it("by category draws one node per class, and the totals line is identical in both breakdowns", async () => {
    const user = userEvent.setup();
    renderTab();
    await user.click(toggle());
    const byGroup = totalsLine();

    await user.selectOptions(breakdown(), "category");

    expect(diagram().getByText("ETFs")).toBeInTheDocument();
    expect(diagram().getByText("Crypto")).toBeInTheDocument();
    expect(diagram().getByText("Withdrawn from Crypto")).toBeInTheDocument();
    expect(diagram().queryByText("Contributed to wealth")).toBeNull();
    expect(totalsLine()).toBe(byGroup);
  });

  it("by group lists the classes behind a direction in its tooltip, largest first", async () => {
    const user = userEvent.setup();
    renderTab();
    await user.click(toggle());

    await user.hover(screen.getByRole("button", { name: /^Contributed to wealth: / }));

    const tip = document.querySelector(".recharts-tooltip-wrapper") as HTMLElement;
    expect(await within(tip).findByText("ETFs:")).toBeInTheDocument();
    const rows = within(tip).getAllByRole("listitem").map((li) => li.textContent);
    expect(rows).toEqual(["ETFs: €300.00 · 6 movements", "Crypto: €50.00 · 1 movement"]);
  });

  it("by group pins the classes behind a direction with a link filtered by type and period, without a class", async () => {
    const user = userEvent.setup();
    renderTab();
    await user.click(toggle());

    await user.click(screen.getByRole("button", { name: /^Contributed to wealth: / }));

    const card = screen.getByRole("region", { name: "Contributed to wealth" });
    expect(card).toHaveTextContent("Contributed to wealth €350.00 · 7 movements");
    expect(within(card).getAllByRole("listitem").map((li) => li.textContent)).toEqual([
      "ETFs: €300.00 · 6 movements",
      "Crypto: €50.00 · 1 movement",
    ]);
    expect(within(card).getByRole("link", { name: "See in Wealth → Flows" })).toHaveAttribute(
      "href",
      "/networth?tab=flows&flow=movements&type=contribution&from=2025-03-01&to=2025-03-31",
    );
  });

  it("by category pins a class's movements with a link filtered to that class, type and period", async () => {
    const user = userEvent.setup();
    renderTab();
    await user.click(toggle());
    await user.selectOptions(breakdown(), "category");

    await user.click(screen.getByRole("button", { name: /^ETFs: €300\.00/ }));

    const card = screen.getByRole("region", { name: "ETFs" });
    expect(within(card).getByText(/VWCE/)).toBeInTheDocument();
    expect(within(card).getByText(/2 movements/)).toBeInTheDocument();
    expect(within(card).getByText("and 1 more")).toBeInTheDocument();
    expect(within(card).getByText(/No description/).closest("li")).toHaveTextContent("No description: €30.00 · 1 movement");
    expect(within(card).getByRole("link", { name: "See in Wealth → Flows" })).toHaveAttribute(
      "href",
      "/networth?tab=flows&flow=movements&type=contribution&class=etfs&from=2025-03-01&to=2025-03-31",
    );

    await user.click(within(card).getByRole("button", { name: "Close details" }));
    expect(screen.queryByRole("region", { name: "ETFs" })).toBeNull();
  });

  it("ends the totals line with the period's cash result, only with the option on", async () => {
    const user = userEvent.setup();
    renderTab();
    expect(screen.queryByText(/^Cash:/)).toBeNull();

    await user.click(toggle());

    expect(screen.getByText(/^Cash:/)).toHaveTextContent("Cash: +€270.00");
    const totals = screen.getByText(/^Income:/).parentElement as HTMLElement;
    expect(totals.lastElementChild).toHaveTextContent(/^Cash:/);
  });

  it("reads a deficit covered by a withdrawal as cash going up, not a contradiction", async () => {
    state.deficitCovered = true;
    const user = userEvent.setup();
    renderTab();

    await user.click(toggle());

    expect(screen.getByText(/^Deficit:/)).toHaveTextContent("€800.00");
    expect(screen.getByText(/^Withdrawn:/)).toHaveTextContent("€1,200.00");
    expect(screen.getByText(/^Cash:/)).toHaveTextContent("Cash: +€400.00");
  });

  it("shows a negative cash result when contributions drew cash down", async () => {
    state.ordered = true;
    const user = userEvent.setup();
    renderTab();

    await user.click(toggle());

    expect(screen.getByText(/^Cash:/)).toHaveTextContent("Cash: -€10.00");
  });

  it("keeps the input order: expenses, then contributions, then the cash term; income, withdrawals, From cash", async () => {
    state.ordered = true;
    const user = userEvent.setup();
    renderTab();

    await user.click(toggle());

    const labels = Array.from(document.querySelectorAll<SVGTextElement>("svg.recharts-surface text")).map((text) => ({
      name: text.querySelector("tspan")?.textContent ?? "",
      anchor: text.getAttribute("text-anchor"),
      y: Number(text.getAttribute("y")),
    }));
    const column = (anchor: string) => labels.filter((l) => l.anchor === anchor).sort((a, b) => a.y - b.y).map((l) => l.name);
    expect(column("end")).toEqual(["🏠 Home", "🛒 Groceries", "Contributed to wealth"]);
    expect(column("start")).toEqual(["Salary", "Withdrawn from wealth", "From cash"]);
  });

  it("draws a group the refunds turned net negative as a source on the left, with the option off", async () => {
    state.netRefund = true;
    renderTab();

    expect(diagram().getByText("Net refunds in Outings")).toBeInTheDocument();
    expect(toggle()).not.toBeChecked();

    cleanup();
    await i18n.changeLanguage("es");
    renderTab();
    expect(diagram().getByText("Devoluciones netas en Ocio")).toBeInTheDocument();
  });

  it("is remembered in this browser once turned on", async () => {
    const user = userEvent.setup();
    const first = renderTab();
    await user.click(toggle());
    first.unmount();

    renderTab();

    expect(toggle()).toBeChecked();
    expect(calls.movements.at(-1)).toBe(true);
  });

  it("stays on for a browser that turned it on under its old name, and moves the setting to the new key", () => {
    localStorage.setItem("sl.analytics.moneyFlowInvestments", "1");

    renderTab();

    expect(toggle()).toBeChecked();
    expect(calls.movements.at(-1)).toBe(true);
    expect(localStorage.getItem("sl.analytics.moneyFlowMovements")).toBe("1");
    expect(localStorage.getItem("sl.analytics.moneyFlowInvestments")).toBeNull();
  });

  it("never says investments for the option, in either language", async () => {
    const user = userEvent.setup();
    renderTab();
    await user.click(toggle());
    await user.click(screen.getByRole("button", { name: "About contributions and withdrawals" }));
    expect(document.body.textContent).not.toMatch(/invest/i);

    cleanup();
    await i18n.changeLanguage("es");
    renderTab();
    await user.click(screen.getByRole("button", { name: "Sobre las aportaciones y retiradas" }));
    expect(document.body.textContent).not.toMatch(/inversi/i);
  });

  it("names the per-direction nodes without portfolio, cartera or retiro, in either language", async () => {
    const user = userEvent.setup();
    renderTab();
    await user.click(toggle());
    expect(diagramText()).not.toMatch(/portfolio|invest/i);

    cleanup();
    await i18n.changeLanguage("es");
    renderTab();
    expect(diagram().getByText("Aportado a patrimonio")).toBeInTheDocument();
    expect(diagram().getByText("Retirado de patrimonio")).toBeInTheDocument();
    expect(diagramText()).not.toMatch(/cartera|\bretiros?\b|inversi/i);
  });

  it("says so when the period has no movements", async () => {
    state.withMovements = false;
    const user = userEvent.setup();
    renderTab();

    await user.click(toggle());

    expect(screen.getByText(/No contributions or withdrawals recorded as movements in Wealth → Flows for this period/)).toBeInTheDocument();
    expect(screen.getByText(/^Contributed:/)).toHaveTextContent("€0.00");
  });

  it("explains what the figures are and are not, with a link to Flows", async () => {
    const user = userEvent.setup();
    renderTab();

    await user.click(screen.getByRole("button", { name: "About contributions and withdrawals" }));

    const tip = screen.getByRole("tooltip");
    expect(tip.textContent).toMatch(/^These are the contributions and withdrawals you record as movements in Wealth → Flows/);
    expect(tip).toHaveTextContent("not come from the portfolio");
    expect(tip).toHaveTextContent("shows as one withdrawal from the first and one contribution to the second");
    expect(within(tip).getByRole("link", { name: "Go to Flows" })).toHaveAttribute("href", "/networth?tab=flows&flow=movements");
  });

  it("speaks Spanish", async () => {
    await i18n.changeLanguage("es");
    const user = userEvent.setup();
    renderTab();

    await user.click(toggle());

    expect(diagram().getByText("Retirado de patrimonio")).toBeInTheDocument();
    expect(diagram().getByText("Se queda en efectivo")).toBeInTheDocument();
    expect(screen.getByText(/^Aportado:/)).toBeInTheDocument();
    expect(screen.getByText(/^Retirado:/)).toBeInTheDocument();
    expect(screen.getByText(/^Efectivo:/)).toHaveTextContent("Efectivo: +270,00 €");

    await user.selectOptions(breakdown(), "category");
    expect(diagram().getByText("Retirado de Cripto")).toBeInTheDocument();
  });
});

describe("Money flow amounts", () => {
  it("group thousands on four- and five-digit amounts in Spanish, with the option off and on", async () => {
    state.large = true;
    await i18n.changeLanguage("es");
    const user = userEvent.setup();
    renderTab();

    expect(diagram().getAllByText("12.345,67 €")).toHaveLength(2); // salary and the hub
    expect(diagram().getByText("2.895,68 €")).toBeInTheDocument();
    expect(diagram().getByText("9.449,99 €")).toBeInTheDocument();
    expect(screen.getByText(/^Ingresos:/)).toHaveTextContent("Ingresos: 12.345,67 €");
    expect(screen.getByText(/^Gastos:/)).toHaveTextContent("Gastos: 2.895,68 €");
    expect(screen.getByText(/^Ahorrado:/)).toHaveTextContent("Ahorrado: 9.449,99 €");

    await user.click(toggle());

    expect(diagram().getByText("9.449,99 €")).toBeInTheDocument();
    expect(screen.getByText(/^Efectivo:/)).toHaveTextContent("Efectivo: +9.449,99 €");
  });

  it("group thousands on four- and five-digit amounts in English, with the option off and on", async () => {
    state.large = true;
    const user = userEvent.setup();
    renderTab();

    expect(diagram().getAllByText("€12,345.67")).toHaveLength(2); // salary and the hub
    expect(diagram().getByText("€2,895.68")).toBeInTheDocument();
    expect(screen.getByText(/^Saved:/)).toHaveTextContent("Saved: €9,449.99");

    await user.click(toggle());

    expect(screen.getByText(/^Cash:/)).toHaveTextContent("Cash: +€9,449.99");
  });
});
