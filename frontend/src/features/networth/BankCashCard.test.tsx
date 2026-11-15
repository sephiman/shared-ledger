import { afterEach, beforeAll, describe, expect, it, vi } from "vitest";
import { cleanup, render, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { BankCashCard } from "@/features/networth/BankCashCard";
import type { BankCash, BankCashAccount } from "@/api/networth";
import i18n from "@/i18n";

const toggleAccount = vi.fn();
const saveOther = vi.fn().mockResolvedValue(undefined);
let bankCash: BankCash | undefined;

vi.mock("@/auth/AuthContext", () => ({
  useActiveHousehold: () => ({ householdId: "h1", name: "Home", currency: "EUR", role: "owner" }),
}));

vi.mock("@/api/networth", () => ({
  useBankCash: () => ({ data: bankCash }),
  useUpdateBankCashAccount: () => ({ mutate: toggleAccount, isPending: false }),
  useUpdateOtherCash: () => ({ mutateAsync: saveOther, isPending: false }),
}));

beforeAll(async () => {
  await i18n.changeLanguage("en");
});

afterEach(() => {
  cleanup();
  vi.clearAllMocks();
});

function account(id: string, accountName: string, balance: string | null, countsAsCash = true): BankCashAccount {
  return {
    id,
    bankName: "ING",
    accountName,
    ibanMasked: null,
    currency: "EUR",
    balance,
    balanceBase: balance,
    balanceType: balance ? "CLBD" : null,
    balanceDate: balance ? "2026-11-14" : null,
    countsAsCash,
    paused: false,
  };
}

describe("bank cash card", () => {
  it("renders nothing without linked accounts", () => {
    bankCash = { accounts: [], bankTotal: "0.00", otherCash: "0.00", total: "0.00" };
    const { container } = render(<BankCashCard />);
    expect(container).toBeEmptyDOMElement();
  });

  it("lists balances, un-counts an account and saves other cash", async () => {
    bankCash = {
      accounts: [account("a1", "Checking", "1200.00"), account("a2", "Savings", null)],
      bankTotal: "1200.00",
      otherCash: "0.00",
      total: "1200.00",
    };
    const user = userEvent.setup();
    render(<BankCashCard />);

    expect(screen.getByText(i18n.t("networth.cash_bank_no_balance"))).toBeInTheDocument();
    await user.click(screen.getByRole("checkbox", { name: "Count ING · Checking as cash" }));
    expect(toggleAccount).toHaveBeenCalledWith({ accountId: "a1", countsAsCash: false });

    const save = screen.getByRole("button", { name: i18n.t("common.save") });
    expect(save).toBeDisabled();
    const other = screen.getByLabelText(i18n.t("networth.cash_other"));
    await user.clear(other);
    await user.type(other, "150");
    await user.click(save);
    expect(saveOther).toHaveBeenCalledWith("150");
  });
});
