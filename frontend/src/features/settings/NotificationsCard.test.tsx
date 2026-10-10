import { afterEach, beforeAll, describe, expect, it, vi } from "vitest";
import { cleanup, render, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import type { TelegramSettings } from "@/api/notifications";
import { NotificationsCard } from "@/features/settings/NotificationsCard";
import i18n from "@/i18n";

const mutateAsync = vi.fn().mockResolvedValue({});

const settings: TelegramSettings = {
  active: true,
  notifyTransactions: true,
  notifySnapshots: true,
  notifyMovements: true,
  notifyLendingPayments: true,
  notifyHoldings: true,
  notifyRecurringTxn: true,
  notifyRecurringLending: true,
  notifyBankMovements: true,
  notifyBankConnections: false,
  chatId: "chat-1",
  tokenConfigured: true,
};

vi.mock("@/api/notifications", () => ({
  useTelegramSettings: () => ({ data: settings }),
  useUpdateTelegramSettings: () => ({ mutateAsync, isPending: false }),
  useTestTelegram: () => ({ mutateAsync: vi.fn(), isPending: false }),
}));

beforeAll(async () => {
  await i18n.changeLanguage("en");
});

afterEach(() => {
  cleanup();
  vi.clearAllMocks();
});

describe("notifications card", () => {
  it("shows both bank toggles with their stored state", () => {
    render(<NotificationsCard householdId="h1" />);

    expect(screen.getByRole("checkbox", { name: "Bank movements (new to review, confirmations)" })).toBeChecked();
    expect(screen.getByRole("checkbox", { name: "Bank connection expiry" })).not.toBeChecked();
  });

  it("saves the bank toggles along with the rest", async () => {
    const user = userEvent.setup();
    render(<NotificationsCard householdId="h1" />);

    await user.click(screen.getByRole("checkbox", { name: "Bank movements (new to review, confirmations)" }));
    await user.click(screen.getByRole("checkbox", { name: "Bank connection expiry" }));
    await user.click(screen.getByRole("button", { name: "Save" }));

    expect(mutateAsync).toHaveBeenCalledWith(
      expect.objectContaining({ notifyBankMovements: false, notifyBankConnections: true }),
    );
  });
});
