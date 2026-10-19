import type { ReactElement } from "react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { cleanup, render, screen, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import type { MoneyFlowProjectionResponse, MoneyFlowResponse } from "@/api/analytics";
import i18n from "@/i18n";

const calls = vi.hoisted(() => ({ windows: [] as number[], realLevels: [] as string[], projectionLevels: [] as string[] }));

type Level = "group" | "category";
type FlowNode = MoneyFlowResponse["nodes"][number];
type Components = MoneyFlowProjectionResponse["components"][number];

const income = (id: string, amount: string): FlowNode => ({ id, side: "income", groupCode: null, amount });
const hub = (amount: string): FlowNode => ({ id: "hub", side: "hub", groupCode: null, amount });
const expense = (id: string, groupCode: string, amount: string): FlowNode => ({ id, side: "expense", groupCode, amount });
const saved = (amount: string): FlowNode => ({ id: "saved", side: "saved", groupCode: null, amount });
const part = (side: Components["side"], id: string, actual: string, recurringDue: string, discretionaryEstimate: string, projected: string): Components => ({
  side,
  id,
  actual,
  recurringDue,
  discretionaryEstimate,
  projected,
});

const REAL: Record<Level, MoneyFlowResponse> = {
  group: {
    from: "2026-10-01",
    to: "2026-10-31",
    level: "group",
    income: "3000.00",
    expenses: "1890.12",
    saved: "1109.88",
    deficit: "0.00",
    nodes: [income("income.salary", "3000.00"), hub("3000.00"), expense("home", "home", "1890.12"), saved("1109.88")],
    links: [],
  },
  category: {
    from: "2026-10-01",
    to: "2026-10-31",
    level: "category",
    income: "3000.00",
    expenses: "1890.12",
    saved: "1109.88",
    deficit: "0.00",
    nodes: [
      income("income.salary", "3000.00"),
      hub("3000.00"),
      expense("home.rent", "home", "1500.00"),
      expense("groceries.groceries", "groceries", "390.12"),
      saved("1109.88"),
    ],
    links: [],
  },
};

const PROJECTION_BASE = {
  year: 2026,
  month: 10,
  dayOfMonth: 18,
  daysInMonth: 31,
  windowMonths: 3,
  monthsAvailable: 2,
  basisFromYear: 2026,
  basisFromMonth: 8,
  basisToYear: 2026,
  basisToMonth: 9,
  deficit: "0.00",
  links: [],
};

// Outings has nothing actual yet: its whole projected amount is the discretionary estimate.
const PROJECTION: Record<Level, MoneyFlowProjectionResponse> = {
  group: {
    ...PROJECTION_BASE,
    level: "group",
    income: "3000.00",
    expenses: "2399.36",
    saved: "600.64",
    nodes: [
      income("income.salary", "3000.00"),
      hub("3000.00"),
      expense("home", "home", "2278.96"),
      expense("outings", "outings", "120.40"),
      saved("600.64"),
    ],
    components: [
      part("income", "income.salary", "3000.00", "0.00", "0.00", "3000.00"),
      part("expense", "home", "1890.12", "350.00", "38.84", "2278.96"),
      part("expense", "outings", "0.00", "0.00", "120.40", "120.40"),
    ],
  },
  category: {
    ...PROJECTION_BASE,
    level: "category",
    income: "3000.00",
    expenses: "1928.96",
    saved: "1071.04",
    nodes: [
      income("income.salary", "3000.00"),
      hub("3000.00"),
      expense("home.rent", "home", "1500.00"),
      expense("groceries.groceries", "groceries", "428.96"),
      saved("1071.04"),
    ],
    components: [
      part("income", "income.salary", "3000.00", "0.00", "0.00", "3000.00"),
      part("expense", "home.rent", "1500.00", "0.00", "0.00", "1500.00"),
      part("expense", "groceries.groceries", "390.12", "0.00", "38.84", "428.96"),
    ],
  },
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

vi.mock("@/api/analytics", async (importOriginal) => ({
  ...(await importOriginal<typeof import("@/api/analytics")>()),
  useYearsAvailable: () => ({ data: { years: [2025, 2026] } }),
  useMoneyFlow: (_h: string, _from: string, _to: string, level: Level) => {
    calls.realLevels.push(level);
    return { data: REAL[level], isLoading: false };
  },
  useMoneyFlowProjection: (_h: string, level: Level, windowMonths: number) => {
    calls.projectionLevels.push(level);
    calls.windows.push(windowMonths);
    return { data: { ...PROJECTION[level], windowMonths }, isLoading: false };
  },
  useForecast: () => ({ data: undefined }),
}));

const { MoneyFlowTab } = await import("./MoneyFlowTab");

/** A figure in a panel's totals row, by its label ("Saved (projected): €600.64"). */
const total = (label: string) => screen.getByText(new RegExp(`^${label.replace(/[()]/g, "\\$&")}:`));

/** The real diagram is drawn first, the projection second. */
const surface = (which: "real" | "projection") => {
  const svgs = document.querySelectorAll<SVGSVGElement>("svg.recharts-surface");
  expect(svgs).toHaveLength(2);
  return svgs[which === "real" ? 0 : 1];
};
const diagram = (which: "real" | "projection") => within(surface(which) as unknown as HTMLElement);

/** Ribbons carry no role (the node is the accessible control), so they are found by the node they lead to. */
const ribbons = (which: "real" | "projection") => Array.from(surface(which).querySelectorAll<SVGPathElement>(".recharts-sankey-links path"));
const ribbonTo = (key: string) => {
  const found = ribbons("projection").find((p) => p.dataset.flowEnd === key);
  expect(found).toBeDefined();
  return found as SVGPathElement;
};

const node = (name: RegExp) => screen.getByRole("button", { name });
const projectionBreakdown = () => screen.getByRole("combobox", { name: "Projection breakdown" });
const realBreakdown = () => screen.getAllByRole("combobox")[3];
const { ForecastTab } = await import("./ForecastTab");

beforeEach(() => {
  localStorage.clear();
  calls.windows = [];
  calls.realLevels = [];
  calls.projectionLevels = [];
  vi.useFakeTimers({ toFake: ["Date"] });
  vi.setSystemTime(new Date(2026, 9, 18));
});

afterEach(async () => {
  cleanup();
  vi.useRealTimers();
  localStorage.clear();
  await i18n.changeLanguage("en");
});

describe("month projection panel", () => {
  it("shows below the real panel for the current month, with its basis and assumptions", () => {
    render(<MoneyFlowTab />);

    expect(screen.getByRole("button", { name: /Month projection/ })).toHaveAttribute("aria-expanded", "true");
    expect(screen.getByText("Based on 2 of the last 3 months (Aug–Sep 2026)")).toBeInTheDocument();
    expect(screen.getByText("Day 18 of 31 (58% of the month elapsed)")).toBeInTheDocument();
    expect(screen.getByText("3-month moving average: Aug, Sep 2026 (2 of 3 months available)")).toBeInTheDocument();
    expect(total("Projected expenses")).toHaveTextContent("€2,399.36");
    expect(total("Saved (projected)")).toHaveTextContent("€600.64");
  });

  it("does not render for a past month", async () => {
    const user = userEvent.setup();
    render(<MoneyFlowTab />);

    const [, , monthSelect] = screen.getAllByRole("combobox");
    await user.selectOptions(monthSelect, "9");

    expect(screen.queryByRole("button", { name: /Month projection/ })).not.toBeInTheDocument();
  });

  it("does not render for a range that includes the current month", async () => {
    const user = userEvent.setup();
    render(<MoneyFlowTab />);

    await user.selectOptions(screen.getAllByRole("combobox")[0], "ytd");

    expect(screen.queryByRole("button", { name: /Month projection/ })).not.toBeInTheDocument();
  });

  it("remembers that it was collapsed", async () => {
    const user = userEvent.setup();
    render(<MoneyFlowTab />);

    await user.click(screen.getByRole("button", { name: /Month projection/ }));
    expect(screen.queryByText(/Day 18 of 31/)).not.toBeInTheDocument();

    cleanup();
    render(<MoneyFlowTab />);
    expect(screen.getByRole("button", { name: /Month projection/ })).toHaveAttribute("aria-expanded", "false");
  });

  it("shares its window with the Forecast", async () => {
    const user = userEvent.setup();
    render(<MoneyFlowTab />);

    await user.selectOptions(screen.getByRole("combobox", { name: "Window" }), "6");
    expect(calls.windows.at(-1)).toBe(6);
    expect(screen.getByText("6-month moving average: Aug, Sep 2026 (2 of 6 months available)")).toBeInTheDocument();

    cleanup();
    render(<ForecastTab />);
    const forecastWindow = screen.getAllByRole("combobox")[1];
    expect(forecastWindow).toHaveValue("6");
  });
});

describe("month projection node toggles", () => {
  it("drops exactly a node's projected remainder on click and restores the original figures on a second click", async () => {
    const user = userEvent.setup();
    render(<MoneyFlowTab />);

    await user.click(node(/Home: count only actuals/));

    expect(node(/Home: count only actuals/)).toHaveAttribute("aria-pressed", "true");
    expect(diagram("projection").getByText("€1,890.12")).toBeInTheDocument();
    expect(total("Projected expenses")).toHaveTextContent("€2,010.52");
    expect(total("Saved (projected)")).toHaveTextContent("€989.48");
    expect(screen.getByText("Projection disabled for 1 group")).toBeInTheDocument();

    await user.click(node(/Home: count only actuals/));

    expect(node(/Home: count only actuals/)).toHaveAttribute("aria-pressed", "false");
    expect(total("Projected expenses")).toHaveTextContent("€2,399.36");
    expect(total("Saved (projected)")).toHaveTextContent("€600.64");
    expect(screen.queryByText(/Projection disabled for/)).not.toBeInTheDocument();
  });

  it("keeps a node with no actuals yet clickable, showing its exact zero rather than any drawing value", async () => {
    const user = userEvent.setup();
    render(<MoneyFlowTab />);

    await user.click(node(/Outings: count only actuals/));

    expect(node(/Outings: count only actuals/)).toHaveAttribute("aria-pressed", "true");
    expect(diagram("projection").getByText("€0.00")).toBeInTheDocument();
    expect(total("Projected expenses")).toHaveTextContent("€2,278.96");
    expect(total("Saved (projected)")).toHaveTextContent("€721.04");

    await user.click(node(/Outings: count only actuals/));
    expect(node(/Outings: count only actuals/)).toHaveAttribute("aria-pressed", "false");
    expect(total("Projected expenses")).toHaveTextContent("€2,399.36");
  });

  it("says in the tooltip that the projection is off and how much estimate it leaves out", async () => {
    const user = userEvent.setup();
    render(<MoneyFlowTab />);

    await user.click(node(/Home: count only actuals/));
    await user.hover(node(/Home: count only actuals/));

    expect(
      await screen.findByText("Projection disabled — showing actuals only (€388.84 estimated remainder excluded)"),
    ).toBeInTheDocument();
  });

  it("toggles a node from its ribbon and restores it with a second click", async () => {
    const user = userEvent.setup();
    render(<MoneyFlowTab />);

    await user.click(ribbonTo("expense:home"));

    expect(node(/Home: count only actuals/)).toHaveAttribute("aria-pressed", "true");
    expect(total("Projected expenses")).toHaveTextContent("€2,010.52");
    expect(total("Saved (projected)")).toHaveTextContent("€989.48");

    // The switched-off node's remaining actuals-only ribbon is still the way back.
    await user.click(ribbonTo("expense:home"));

    expect(node(/Home: count only actuals/)).toHaveAttribute("aria-pressed", "false");
    expect(total("Projected expenses")).toHaveTextContent("€2,399.36");
    expect(total("Saved (projected)")).toHaveTextContent("€600.64");
  });

  it("toggles an income node from its ribbon", async () => {
    const user = userEvent.setup();
    render(<MoneyFlowTab />);

    await user.click(ribbonTo("income:income.salary"));

    expect(node(/Salary: count only actuals/)).toHaveAttribute("aria-pressed", "true");
  });

  it("leaves the Saved ribbon and every real ribbon inert", async () => {
    const user = userEvent.setup();
    render(<MoneyFlowTab />);

    const projected = ribbons("projection");
    const savedRibbon = projected.filter((p) => p.dataset.flowEnd === undefined);
    expect(projected.map((p) => p.dataset.flowEnd).filter(Boolean)).toEqual(["income:income.salary", "expense:home", "expense:outings"]);
    expect(savedRibbon).toHaveLength(1);
    const real = ribbons("real");
    expect(real.length).toBeGreaterThan(0);
    expect(real.some((p) => p.dataset.flowEnd !== undefined || p.classList.contains("cursor-pointer"))).toBe(false);

    for (const ribbon of [...savedRibbon, ...real]) await user.click(ribbon);

    expect(screen.queryByRole("button", { pressed: true })).not.toBeInTheDocument();
    expect(total("Saved (projected)")).toHaveTextContent("€600.64");
  });

  it("restores every node at once", async () => {
    const user = userEvent.setup();
    render(<MoneyFlowTab />);

    await user.click(node(/Home: count only actuals/));
    await user.click(node(/Outings: count only actuals/));
    expect(screen.getByText("Projection disabled for 2 groups")).toBeInTheDocument();

    await user.click(screen.getByRole("button", { name: "Restore all" }));

    expect(screen.queryByRole("button", { pressed: true })).not.toBeInTheDocument();
    expect(total("Saved (projected)")).toHaveTextContent("€600.64");
    expect(screen.queryByRole("button", { name: "Restore all" })).not.toBeInTheDocument();
  });

  it("is keyboard operable", async () => {
    const user = userEvent.setup();
    render(<MoneyFlowTab />);

    node(/Home: count only actuals/).focus();
    await user.keyboard("{Enter}");

    expect(node(/Home: count only actuals/)).toHaveAttribute("aria-pressed", "true");
  });

  it("leaves the real diagram's nodes non-interactive", () => {
    render(<MoneyFlowTab />);

    expect(diagram("real").queryAllByRole("button")).toHaveLength(0);
  });

  it("clears the toggles when the projection's breakdown changes, without bringing them back", async () => {
    const user = userEvent.setup();
    render(<MoneyFlowTab />);

    await user.click(node(/Home: count only actuals/));
    await user.selectOptions(projectionBreakdown(), "category");
    expect(screen.queryByText(/Projection disabled for/)).not.toBeInTheDocument();
    expect(total("Saved (projected)")).toHaveTextContent("€1,071.04");

    await user.selectOptions(projectionBreakdown(), "group");
    expect(node(/Home: count only actuals/)).toHaveAttribute("aria-pressed", "false");
    expect(total("Saved (projected)")).toHaveTextContent("€600.64");
  });

  it("clears the toggles when the window changes, without bringing them back", async () => {
    const user = userEvent.setup();
    render(<MoneyFlowTab />);

    await user.click(node(/Home: count only actuals/));
    await user.selectOptions(screen.getByRole("combobox", { name: "Window" }), "6");
    expect(screen.queryByText(/Projection disabled for/)).not.toBeInTheDocument();

    await user.selectOptions(screen.getByRole("combobox", { name: "Window" }), "3");
    expect(node(/Home: count only actuals/)).toHaveAttribute("aria-pressed", "false");
    expect(total("Saved (projected)")).toHaveTextContent("€600.64");
  });

  it("starts over on reload: group breakdown, nothing disabled", async () => {
    const user = userEvent.setup();
    render(<MoneyFlowTab />);
    await user.selectOptions(projectionBreakdown(), "category");
    await user.click(node(/Groceries: count only actuals/));
    expect(screen.getByText("Projection disabled for 1 category")).toBeInTheDocument();

    cleanup();
    render(<MoneyFlowTab />);

    expect(projectionBreakdown()).toHaveValue("group");
    expect(calls.projectionLevels.at(-1)).toBe("group");
    expect(screen.queryByRole("button", { pressed: true })).not.toBeInTheDocument();
    expect(screen.queryByText(/Projection disabled for/)).not.toBeInTheDocument();
  });
});

describe("independent breakdowns", () => {
  it("lets the real and the projected panels show different breakdowns at the same time", async () => {
    const user = userEvent.setup();
    render(<MoneyFlowTab />);

    await user.selectOptions(realBreakdown(), "category");

    expect(calls.realLevels.at(-1)).toBe("category");
    expect(calls.projectionLevels.at(-1)).toBe("group");
    expect(diagram("real").getByText(/Rent$/)).toBeInTheDocument();
    expect(diagram("real").queryByText(/Home$/)).not.toBeInTheDocument();
    expect(diagram("projection").getByText(/Home$/)).toBeInTheDocument();
    expect(diagram("projection").queryByText(/Rent$/)).not.toBeInTheDocument();

    await user.selectOptions(realBreakdown(), "group");
    await user.selectOptions(projectionBreakdown(), "category");

    expect(realBreakdown()).toHaveValue("group");
    expect(diagram("real").getByText(/Home$/)).toBeInTheDocument();
    expect(diagram("projection").getByText(/Rent$/)).toBeInTheDocument();
  });
});
