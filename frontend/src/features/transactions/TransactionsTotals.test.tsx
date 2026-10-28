import { afterEach, beforeAll, describe, expect, it, vi } from "vitest";
import { cleanup, configure, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import i18n from "@/i18n";
import type { TransactionTotals } from "@/api/transactions";
import { fieldByLabel } from "@/test/LocationProbe";
import { lastRequest, listedTransaction, stubLedgerApi } from "@/test/ledgerApi";
import { renderViews } from "@/test/renderViews";

vi.mock("@/auth/AuthContext", () => ({
  useActiveHousehold: () => ({ householdId: "h1", currency: "EUR", role: "owner" }),
}));

configure({ asyncUtilTimeout: 4000 });

beforeAll(async () => {
  await i18n.changeLanguage("en");
});

afterEach(async () => {
  cleanup();
  vi.restoreAllMocks();
  await i18n.changeLanguage("en");
});

const page = { items: [listedTransaction()], page: 0, size: 50, total: 120 };
const totals = (patch: Partial<TransactionTotals>): TransactionTotals => ({
  count: 120, income: "0.00", expenses: "0.00", net: "0.00", ...patch,
});
const totalsLine = () => screen.findByRole("status", { name: "Totals" });

describe("the transactions list's totals line", () => {
  it("signs each figure exactly as the table signs its rows", async () => {
    const rows = [
      listedTransaction({ id: "e", direction: "expense", amount: "12.50" }),
      listedTransaction({ id: "r", direction: "expense", amount: "-20.00", isRefund: true }),
      listedTransaction({ id: "i", direction: "income", amount: "2400.00", categoryCode: "income.salary" }),
    ];
    stubLedgerApi(undefined, { page: { ...page, items: rows }, totals: () => totals({ count: 3, income: "2400.00", expenses: "-7.50", net: "2407.50" }) });
    renderViews("/transactions");

    const cells = await screen.findAllByRole("cell", { name: /€/ });
    expect(cells.map((c) => c.textContent)).toEqual(["-€12.50", "+€20.00", "+€2,400.00"]);
    expect((await totalsLine()).textContent).toBe("3 transactions · Income +€2,400.00 · Expenses +€7.50 · Net +€2,407.50");
  });

  it("with a direction set, counts and sums the whole filtered set", async () => {
    stubLedgerApi(undefined, { page, totals: () => totals({ expenses: "1534.20" }) });
    renderViews("/transactions?direction=expense");

    expect((await totalsLine()).textContent).toBe("120 transactions · -€1,534.20");
  });

  it("with income, sums income", async () => {
    stubLedgerApi(undefined, { page, totals: () => totals({ count: 1, income: "2400.00" }) });
    renderViews("/transactions?direction=income");

    expect((await totalsLine()).textContent).toBe("1 transaction · +€2,400.00");
  });

  it("with refunds only, sums what came back", async () => {
    stubLedgerApi(undefined, { page, totals: () => totals({ count: 2, expenses: "-35.50" }) });
    renderViews("/transactions?direction=expense&isRefund=true");

    expect((await totalsLine()).textContent).toBe("2 transactions · +€35.50");
  });

  it("a net loss reads negative, like an expense row", async () => {
    stubLedgerApi(undefined, { page, totals: () => totals({ count: 2, income: "50.00", expenses: "80.00", net: "-30.00" }) });
    renderViews("/transactions");

    expect((await totalsLine()).textContent).toBe("2 transactions · Income +€50.00 · Expenses -€80.00 · Net -€30.00");
  });

  it("with all directions, shows income, expenses and net", async () => {
    stubLedgerApi(undefined, { page, totals: () => totals({ count: 3, income: "2400.00", expenses: "100.00", net: "2300.00" }) });
    renderViews("/transactions");

    expect((await totalsLine()).textContent).toBe("3 transactions · Income +€2,400.00 · Expenses -€100.00 · Net +€2,300.00");
  });

  it("asks for the list's own filters, from a deep link, and again after every filter change", async () => {
    stubLedgerApi(undefined, { page, totals: () => totals({ expenses: "10.00" }) });
    const user = userEvent.setup();
    renderViews("/transactions?from=2026-01-01&to=2026-10-28&direction=expense&categoryGroup=outings");
    await totalsLine();

    expect(lastRequest("/transactions/totals")).toEqual({
      from: "2026-01-01", to: "2026-10-28", direction: "expense", categoryGroup: "outings",
      categoryCode: undefined, isRefund: undefined,
    });
    await user.selectOptions(fieldByLabel("Category"), "outings.restaurants");
    await waitFor(() => expect(lastRequest("/transactions/totals")).toMatchObject({ categoryCode: "outings.restaurants" }));
  });

  it("is hidden when the list is empty", async () => {
    stubLedgerApi(undefined, { totals: () => totals({ count: 0 }) });
    renderViews("/transactions?direction=expense");

    await screen.findByText("Nothing here yet.");
    expect(screen.queryByRole("status", { name: "Totals" })).not.toBeInTheDocument();
  });

  it("speaks Spanish", async () => {
    await i18n.changeLanguage("es");
    stubLedgerApi(undefined, { page, totals: () => totals({ count: 3, income: "2400.00", expenses: "100.00", net: "2300.00" }) });
    renderViews("/transactions");

    // Spanish puts a no-break space before the euro sign.
    expect((await screen.findByRole("status", { name: "Totales" })).textContent?.replace(/\u00a0/g, " ")).toBe(
      "3 transacciones · Ingresos +2400,00 € · Gastos -100,00 € · Neto +2300,00 €",
    );
  });
});
