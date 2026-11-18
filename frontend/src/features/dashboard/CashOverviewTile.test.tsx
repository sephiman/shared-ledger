import { afterEach, beforeAll, describe, expect, it, vi } from "vitest";
import { cleanup, render, screen } from "@testing-library/react";
import { MemoryRouter } from "react-router-dom";
import type { BankCash, BankCashAccount } from "@/api/networth";
import { CashOverviewTile, groupCashByBank } from "@/features/dashboard/CashOverviewTile";
import i18n from "@/i18n";

let bankCash: BankCash | undefined;

vi.mock("@/api/networth", () => ({
  useBankCash: () => ({ data: bankCash }),
}));

beforeAll(async () => {
  await i18n.changeLanguage("en");
});

afterEach(() => {
  cleanup();
  bankCash = undefined;
});

function account(overrides: Partial<BankCashAccount> = {}): BankCashAccount {
  return {
    id: "a1",
    bankName: "ING",
    accountName: "Checking",
    ibanMasked: null,
    currency: "EUR",
    balance: "1000.00",
    balanceBase: "1000.00",
    balanceType: "CLBD",
    balanceDate: "2026-11-18",
    countsAsCash: true,
    paused: false,
    ...overrides,
  };
}

function renderTile() {
  return render(
    <MemoryRouter>
      <CashOverviewTile householdId="h1" currency="EUR" locale="en" />
    </MemoryRouter>,
  );
}

describe("CashOverviewTile", () => {
  it("renders nothing when there are no counted bank balances and no other cash", () => {
    bankCash = {
      accounts: [
        account({ id: "a1", countsAsCash: false, balanceBase: "500.00" }),
        account({ id: "a2", paused: true, balanceBase: "300.00" }),
        account({ id: "a3", balanceBase: null, balance: null }),
      ],
      bankTotal: "0.00",
      otherCash: "0.00",
      total: "0.00",
    };

    const { container } = renderTile();
    expect(container).toBeEmptyDOMElement();
  });

  it("sums counted accounts per bank, sorts largest first, and shows other cash", () => {
    bankCash = {
      accounts: [
        account({ id: "a1", bankName: "Revolut", balanceBase: "250.00" }),
        account({ id: "a2", bankName: "ING", accountName: "Checking", balanceBase: "1000.00" }),
        account({ id: "a3", bankName: "ING", accountName: "Savings", balanceBase: "2500.50" }),
        account({ id: "a4", bankName: "Brokerage", countsAsCash: false, balanceBase: "9000.00" }),
        account({ id: "a5", bankName: "PausedBank", paused: true, balanceBase: "400.00" }),
      ],
      bankTotal: "3750.50",
      otherCash: "150.00",
      total: "3900.50",
    };

    renderTile();

    expect(screen.getByText("Cash")).toBeInTheDocument();
    expect(screen.getByRole("link", { name: "View all" })).toHaveAttribute("href", "/networth?tab=flows&flow=cash");
    expect(screen.getByText("€3,900.50")).toBeInTheDocument();

    const items = screen.getAllByRole("listitem");
    expect(items.map((el) => el.textContent)).toEqual([
      "ING€3,500.50",
      "Revolut€250.00",
      "Other cash€150.00",
    ]);
    expect(screen.queryByText("Brokerage")).not.toBeInTheDocument();
    expect(screen.queryByText("PausedBank")).not.toBeInTheDocument();
  });

  it("renders when only other cash is non-zero", () => {
    bankCash = {
      accounts: [],
      bankTotal: "0.00",
      otherCash: "250.00",
      total: "250.00",
    };

    renderTile();

    const items = screen.getAllByRole("listitem");
    expect(items.map((el) => el.textContent)).toEqual(["Other cash€250.00"]);
  });

  it("groupCashByBank aggregates multiple accounts per bank", () => {
    const grouped = groupCashByBank([
      account({ id: "1", bankName: "A", balanceBase: "10.00" }),
      account({ id: "2", bankName: "B", balanceBase: "50.00" }),
      account({ id: "3", bankName: "A", balanceBase: "60.00" }),
    ]);

    expect(grouped.map((g) => [g.bankName, g.balance.toFixed(2)])).toEqual([
      ["A", "70.00"],
      ["B", "50.00"],
    ]);
  });
});
