import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { cleanup, render, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import type { MoneyFlowProjectionResponse, MoneyFlowResponse } from "@/api/analytics";
import i18n from "@/i18n";

const projectionCalls = vi.hoisted(() => ({ windows: [] as number[] }));

const REAL: MoneyFlowResponse = {
  from: "2026-10-01",
  to: "2026-10-31",
  level: "group",
  income: "3000.00",
  expenses: "1890.12",
  saved: "1109.88",
  deficit: "0.00",
  nodes: [],
  links: [],
};

const PROJECTION: MoneyFlowProjectionResponse = {
  year: 2026,
  month: 10,
  level: "group",
  dayOfMonth: 18,
  daysInMonth: 31,
  windowMonths: 3,
  monthsAvailable: 2,
  basisFromYear: 2026,
  basisFromMonth: 8,
  basisToYear: 2026,
  basisToMonth: 9,
  income: "3000.00",
  expenses: "2278.96",
  saved: "721.04",
  deficit: "0.00",
  nodes: [
    { id: "income.salary", side: "income", groupCode: null, amount: "3000.00" },
    { id: "hub", side: "hub", groupCode: null, amount: "3000.00" },
    { id: "home", side: "expense", groupCode: "home", amount: "2278.96" },
    { id: "saved", side: "saved", groupCode: null, amount: "721.04" },
  ],
  links: [
    { source: "income.salary", target: "hub", amount: "3000.00" },
    { source: "hub", target: "home", amount: "2278.96" },
    { source: "hub", target: "saved", amount: "721.04" },
  ],
  components: [
    { side: "income", id: "income.salary", actual: "3000.00", recurringDue: "0.00", discretionaryEstimate: "0.00", projected: "3000.00" },
    { side: "expense", id: "home", actual: "1890.12", recurringDue: "350.00", discretionaryEstimate: "38.84", projected: "2278.96" },
  ],
};

vi.mock("@/auth/AuthContext", () => ({
  useActiveHousehold: () => ({ householdId: "h1", name: "Home", currency: "EUR", role: "owner" }),
}));

vi.mock("@/api/catalog", () => ({ useCategories: () => ({ data: [] }) }));

vi.mock("@/api/analytics", async (importOriginal) => ({
  ...(await importOriginal<typeof import("@/api/analytics")>()),
  useYearsAvailable: () => ({ data: { years: [2025, 2026] } }),
  useMoneyFlow: () => ({ data: REAL, isLoading: false }),
  useMoneyFlowProjection: (_h: string, _level: string, windowMonths: number) => {
    projectionCalls.windows.push(windowMonths);
    return { data: { ...PROJECTION, windowMonths }, isLoading: false };
  },
  useForecast: () => ({ data: undefined }),
}));

const { MoneyFlowTab } = await import("./MoneyFlowTab");
const { ForecastTab } = await import("./ForecastTab");

beforeEach(() => {
  localStorage.clear();
  projectionCalls.windows = [];
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
    expect(screen.getByText(/Projected expenses/)).toBeInTheDocument();
    expect(screen.getByText(/Saved \(projected\)/)).toBeInTheDocument();
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
    expect(projectionCalls.windows.at(-1)).toBe(6);
    expect(screen.getByText("6-month moving average: Aug, Sep 2026 (2 of 6 months available)")).toBeInTheDocument();

    cleanup();
    render(<ForecastTab />);
    const forecastWindow = screen.getAllByRole("combobox")[1];
    expect(forecastWindow).toHaveValue("6");
  });
});
