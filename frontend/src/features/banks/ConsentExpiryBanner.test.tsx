import { afterEach, beforeAll, describe, expect, it, vi } from "vitest";
import { cleanup, render, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { MemoryRouter } from "react-router-dom";
import type { BankConnection } from "@/api/banks";
import { ConsentExpiryBanner } from "@/features/banks/ConsentExpiryBanner";
import { consentNoticeKey } from "@/features/banks/consentUrgency";
import i18n from "@/i18n";

let connections: BankConnection[] = [];
let dismissed: string[] = [];
const mutate = vi.fn();

vi.mock("@/api/banks", () => ({
  useBankConfig: () => ({ data: { credentialsConfigured: true, connectionCount: connections.length } }),
  useBankConnections: () => ({ data: connections }),
}));

vi.mock("@/api/settings", () => ({
  useUpdateDismissedConsentNotices: () => ({ mutate, isPending: false }),
}));

vi.mock("@/auth/AuthContext", () => ({
  useAuth: () => ({ user: { dismissedConsentNotices: dismissed } }),
}));

beforeAll(async () => {
  await i18n.changeLanguage("en");
});

afterEach(() => {
  cleanup();
  vi.clearAllMocks();
  connections = [];
  dismissed = [];
});

/** Midday N local calendar days from today. */
function inDays(days: number): string {
  const d = new Date();
  return new Date(d.getFullYear(), d.getMonth(), d.getDate() + days, 12).toISOString();
}

function connection(id: string, overrides: Partial<BankConnection> = {}): BankConnection {
  return {
    id,
    provider: "enable_banking",
    aspspName: "ING",
    aspspCountry: "NL",
    label: "Joint account",
    status: "active",
    consentExpiresAt: inDays(30),
    lastSyncedAt: null,
    ingestionEnabled: true,
    syncFrequency: "twice_daily",
    accounts: [],
    lastSyncStatus: null,
    lastSyncError: null,
    canManage: false,
    ...overrides,
  };
}

function renderBanner() {
  render(
    <MemoryRouter>
      <ConsentExpiryBanner householdId="h1" />
    </MemoryRouter>,
  );
}

describe("consent expiry banner", () => {
  it("stays hidden eight days out and appears at seven, linking to Settings → Banks", () => {
    connections = [connection("c1", { consentExpiresAt: inDays(8) })];
    renderBanner();
    expect(screen.queryByRole("status")).not.toBeInTheDocument();

    cleanup();
    connections = [connection("c1", { consentExpiresAt: inDays(7) })];
    renderBanner();
    expect(screen.getByRole("status")).toHaveTextContent("Your Joint account bank connection expires in 7 days — re-link it");
    expect(screen.getByRole("link", { name: "Re-link" })).toHaveAttribute("href", "/settings#banks");
  });

  it("escalates the wording and colour once the consent has expired", () => {
    connections = [connection("c1", { status: "expired" })];
    renderBanner();

    const banner = screen.getByRole("status");
    expect(banner).toHaveTextContent("Your Joint account bank connection has expired — syncing stopped");
    expect(banner).toHaveClass("bg-red-50");
  });

  it("summarises several connections in one line", () => {
    connections = [connection("c1", { consentExpiresAt: inDays(3) }), connection("c2", { consentExpiresAt: inDays(5) })];
    renderBanner();

    expect(screen.getByRole("status")).toHaveTextContent("2 bank connections expire soon");
  });

  it("persists the dismissed connection and expiry pairs", async () => {
    const due = connection("c1", { consentExpiresAt: inDays(2) });
    connections = [due, connection("c2", { consentExpiresAt: inDays(40) })];
    renderBanner();

    await userEvent.setup().click(screen.getByRole("button", { name: "Dismiss" }));

    expect(mutate).toHaveBeenCalledWith([consentNoticeKey(due)]);
  });

  it("stays dismissed for the same cycle but returns after a re-link moves the expiry", () => {
    const due = connection("c1", { consentExpiresAt: inDays(2) });
    connections = [due];
    dismissed = [consentNoticeKey(due)];
    renderBanner();
    expect(screen.queryByRole("status")).not.toBeInTheDocument();

    cleanup();
    connections = [connection("c1", { consentExpiresAt: inDays(6) })];
    renderBanner();
    expect(screen.getByRole("status")).toBeInTheDocument();
  });

  it("returns for a new connection entering the window", () => {
    const first = connection("c1", { consentExpiresAt: inDays(2) });
    connections = [first, connection("c2", { label: "Savings", consentExpiresAt: inDays(4) })];
    dismissed = [consentNoticeKey(first)];
    renderBanner();

    expect(screen.getByRole("status")).toHaveTextContent("Your Savings bank connection expires in 4 days");
  });
});
