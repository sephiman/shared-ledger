import { afterEach, beforeAll, describe, expect, it, vi } from "vitest";
import { cleanup, render, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { BankAccountList } from "@/features/banks/BankAccountList";
import type { BankAccount, BankConnection } from "@/api/banks";
import i18n from "@/i18n";

const mutate = vi.fn();

vi.mock("@/api/banks", () => ({
  useUpdateAccount: () => ({ mutate, isPending: false }),
}));

beforeAll(async () => {
  await i18n.changeLanguage("en");
});

afterEach(() => {
  cleanup();
  vi.clearAllMocks();
});

function account(id: string, name: string, ingestionEnabled: boolean): BankAccount {
  return { id, name, ibanMasked: `••••${id}`, currency: "EUR", ingestionEnabled };
}

function connection(accounts: BankAccount[], overrides: Partial<BankConnection> = {}): BankConnection {
  return {
    id: "c1",
    provider: "enable_banking",
    aspspName: "Bankinter",
    aspspCountry: "ES",
    label: null,
    status: "active",
    consentExpiresAt: null,
    lastSyncedAt: null,
    ingestionEnabled: true,
    syncFrequency: "twice_daily",
    accounts,
    lastSyncStatus: null,
    lastSyncError: null,
    canManage: true,
    ...overrides,
  };
}

describe("bank account list", () => {
  it("shows each account's state and pauses only the one clicked", async () => {
    render(<BankAccountList householdId="h1" connection={connection([account("1111", "Checking", true), account("2222", "Savings", false)])} />);

    expect(screen.getByText(i18n.t("banks.account_active"))).toBeInTheDocument();
    expect(screen.getByText(i18n.t("banks.account_paused"))).toBeInTheDocument();
    await userEvent.setup().click(screen.getByRole("button", { name: /^Pause Checking/ }));

    expect(mutate).toHaveBeenCalledWith({ connectionId: "c1", accountId: "1111", ingestionEnabled: false });
    expect(screen.getByRole("button", { name: /^Resume Savings/ })).toBeInTheDocument();
  });

  it("explains that every account is paused", () => {
    render(<BankAccountList householdId="h1" connection={connection([account("1111", "Checking", false)])} />);

    expect(screen.getByText(i18n.t("banks.accounts_all_paused"))).toBeInTheDocument();
  });

  it("lets a member who cannot manage the connection see states but not change them", () => {
    render(<BankAccountList householdId="h1" connection={connection([account("1111", "Checking", true)], { canManage: false })} />);

    expect(screen.getByText(i18n.t("banks.account_active"))).toBeInTheDocument();
    expect(screen.queryByRole("button")).not.toBeInTheDocument();
  });
});
