import { afterEach, beforeAll, describe, expect, it, vi } from "vitest";
import { cleanup, render, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import type { TelegramSettings } from "@/api/notifications";
import { NotificationsCard } from "@/features/settings/NotificationsCard";
import i18n from "@/i18n";

const mutateAsync = vi.fn().mockResolvedValue({});
const testMutateAsync = vi.fn().mockResolvedValue({ ok: true, description: null });

const baseSettings: TelegramSettings = {
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

let currentSettings: TelegramSettings = baseSettings;

vi.mock("@/api/notifications", () => ({
  useTelegramSettings: () => ({ data: currentSettings }),
  useUpdateTelegramSettings: () => ({ mutateAsync, isPending: false }),
  useTestTelegram: () => ({ mutateAsync: testMutateAsync, isPending: false }),
}));

beforeAll(async () => {
  await i18n.changeLanguage("en");
});

afterEach(() => {
  currentSettings = baseSettings;
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

  it("disables test notification until a token and chat id are saved", async () => {
    currentSettings = { ...baseSettings, chatId: null, tokenConfigured: false };
    const user = userEvent.setup();
    render(<NotificationsCard householdId="h1" />);

    const testBtn = screen.getByRole("button", { name: "Send test notification" });
    expect(testBtn).toBeDisabled();

    await user.type(screen.getByPlaceholderText("-1001234567890"), "-100999");
    expect(testBtn).toBeDisabled();
    expect(screen.getByText("Save your changes before sending a test notification.")).toBeInTheDocument();
  });

  it("disables test notification when the saved chat id is edited without saving", async () => {
    const user = userEvent.setup();
    render(<NotificationsCard householdId="h1" />);

    const testBtn = screen.getByRole("button", { name: "Send test notification" });
    expect(testBtn).toBeEnabled();

    const chatInput = screen.getByPlaceholderText("-1001234567890");
    await user.clear(chatInput);
    await user.type(chatInput, "-100222");

    expect(testBtn).toBeDisabled();
    expect(screen.getByText("Save your changes before sending a test notification.")).toBeInTheDocument();
  });

  it("shows the 4-step setup help and copies getUpdates URL with the entered token without rendering it", async () => {
    const user = userEvent.setup();
    const writeText = vi.spyOn(navigator.clipboard, "writeText");
    render(<NotificationsCard householdId="h1" />);

    await user.click(screen.getByRole("button", { name: "How do I get a bot token and chat ID?" }));

    expect(screen.getByText("Create a bot")).toBeInTheDocument();
    expect(screen.getByText("Add the bot to your chat")).toBeInTheDocument();
    expect(screen.getByText("Find the chat ID")).toBeInTheDocument();
    expect(screen.getByText("Save and send a test")).toBeInTheDocument();
    expect(screen.getByText("result[].channel_post.chat.id")).toBeInTheDocument();
    expect(screen.getByText("result[].my_chat_member.chat.id")).toBeInTheDocument();

    const tokenInput = screen.getByPlaceholderText("A token is configured — leave blank to keep it");
    await user.type(tokenInput, "999:secret-token");

    const copyBtn = screen.getByRole("button", { name: "Copy getUpdates URL with your token" });
    await user.click(copyBtn);

    expect(writeText).toHaveBeenCalledWith("https://api.telegram.org/bot999:secret-token/getUpdates");
    expect(screen.queryByText(/999:secret-token/)).not.toBeInTheDocument();
  });
});
