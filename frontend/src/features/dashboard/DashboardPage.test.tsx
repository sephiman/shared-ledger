import { afterEach, beforeAll, describe, expect, it, vi } from "vitest";
import { cleanup, render, screen, waitFor } from "@testing-library/react";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { MemoryRouter } from "react-router-dom";
import type { AxiosResponse } from "axios";
import { apiClient } from "@/api/client";
import { AuthProvider, useAuth, type Me } from "@/auth/AuthContext";
import i18n from "@/i18n";
import { DashboardPage } from "./DashboardPage";

const ok = <T,>(data: T) => Promise.resolve({ data } as AxiosResponse<T>);

function stubApi(hiddenHomePanels: string[] = []) {
  const user: Me = {
    id: "u1",
    email: "user@example.com",
    locale: "en",
    defaultHouseholdId: "h1",
    hiddenHomePanels,
    portfolioReturnBasis: "OPEN_COST",
    dismissedConsentNotices: [],
    households: [{ householdId: "h1", name: "Home", currency: "EUR", role: "owner" }],
  };

  return vi.spyOn(apiClient, "get").mockImplementation((url: string) => {
    if (url === "/auth/csrf") return ok({});
    if (url === "/auth/me") return ok(user);
    if (url === "/households/h1/analytics/month" || url === "/households/h1/analytics/year") {
      return ok({ income: "3000.00", expenses: "1500.00", savings: "1500.00", savingsRate: 50, byGroup: [] });
    }
    if (url === "/households/h1/analytics/dashboard-extras") {
      return ok({
        trailing12: { income: "0", expenses: "0", savings: "0", rate: 0 },
        ytd: { income: "0", expenses: "0", savings: "0", rate: 0 },
        currentMonth: { income: "0", expenses: "0", savings: "0", rate: 0 },
        sparkline: [],
      });
    }
    if (url === "/households/h1/analytics/cost-of-living") {
      return ok({ monthsAvailable: 0, essentialMonthlyAverage: "0", totalMonthlyAverage: "0" });
    }
    if (url === "/asset-classes") return ok([]);
    if (url === "/households/h1/snapshots/previous-for-prefill") return ok({ previous: null, activeLiabilities: [] });
    if (url === "/households/h1/assets" || url === "/households/h1/liabilities") return ok([]);
    if (url === "/households/h1/portfolio/valuation") return ok({ byClass: {} });
    if (url === "/households/h1/snapshots/named-values") return ok({ assets: {}, liabilities: {}, cash: null });
    if (url === "/households/h1/portfolio/summary") return ok({ holdings: [] });
    if (url === "/households/h1/lendings") return ok({ activeCount: 0, totalOutstandingActive: "0.00", top: [] });
    if (url === "/households/h1/cash/bank") {
      return ok({
        accounts: [
          {
            id: "a1",
            bankName: "Mock ASPSP",
            accountName: "Checking",
            ibanMasked: null,
            currency: "EUR",
            balance: "188.32",
            balanceBase: "188.32",
            balanceType: "CLBD",
            balanceDate: "2026-11-18",
            countsAsCash: true,
            paused: false,
          },
        ],
        bankTotal: "188.32",
        otherCash: "20.00",
        total: "208.32",
      });
    }
    if (url === "/households/h1/banks/config") {
      return ok({ credentialsConfigured: true, connectionCount: 1, nextSyncTimes: [] });
    }
    if (url === "/households/h1/banks/pending/count") {
      return ok({ count: 91, byConnection: [{ connectionId: "c1", label: "Mock ASPSP", count: 91 }] });
    }
    throw new Error(`unexpected GET ${url}`);
  });
}

function ReadyDashboard() {
  const { user, activeHouseholdId } = useAuth();
  return user && activeHouseholdId ? <DashboardPage /> : null;
}

function renderDashboard(hiddenHomePanels: string[] = []) {
  const spy = stubApi(hiddenHomePanels);
  const qc = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  render(
    <QueryClientProvider client={qc}>
      <MemoryRouter>
        <AuthProvider>
          <ReadyDashboard />
        </AuthProvider>
      </MemoryRouter>
    </QueryClientProvider>,
  );
  return spy;
}

beforeAll(async () => {
  await i18n.changeLanguage("en");
  if (!globalThis.ResizeObserver) {
    globalThis.ResizeObserver = class {
      observe() {}
      unobserve() {}
      disconnect() {}
    } as unknown as typeof ResizeObserver;
  }
});

afterEach(() => {
  cleanup();
  vi.restoreAllMocks();
});

describe("DashboardPage home panels", () => {
  it("renders the Cash tile directly before Pending bank movements", async () => {
    renderDashboard();

    const cashHeading = await screen.findByText("Cash");
    const pendingHeading = await screen.findByText("Pending bank movements");

    expect(screen.getByText("€208.32")).toBeInTheDocument();
    expect(screen.getByText("€188.32")).toBeInTheDocument();
    expect(screen.getByText("€20.00")).toBeInTheDocument();
    expect(cashHeading.compareDocumentPosition(pendingHeading) & Node.DOCUMENT_POSITION_FOLLOWING).toBeTruthy();
  });

  it("does not mount or fetch Cash when hidden in user settings", async () => {
    const spy = renderDashboard(["cash"]);

    await screen.findByText("Pending bank movements");
    await waitFor(() => {
      expect(screen.queryByText("Cash")).not.toBeInTheDocument();
    });
    expect(spy.mock.calls.some(([url]) => url === "/households/h1/cash/bank")).toBe(false);
  });
});
