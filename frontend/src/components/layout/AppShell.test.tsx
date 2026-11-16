import { afterEach, describe, expect, it, vi } from "vitest";
import { cleanup, render } from "@testing-library/react";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { MemoryRouter, Route, Routes } from "react-router-dom";
import { AppShell } from "@/components/layout/AppShell";
import { ThemeProvider } from "@/lib/theme";
import "@/i18n";

vi.mock("@/auth/AuthContext", () => ({
  useAuth: () => ({ user: null, logout: vi.fn(), activeHouseholdId: null, setActiveHouseholdId: vi.fn() }),
  useActiveHousehold: () => ({ householdId: "h1", name: "Home", currency: "EUR", role: "owner" as const }),
}));

vi.mock("@/api/banks", () => ({
  useBankConfig: () => ({ data: { credentialsConfigured: false, connectionCount: 0, nextSyncTimes: [] } }),
  usePendingCount: () => ({ data: { count: 0 } }),
}));

vi.mock("@/features/banks/ConsentExpiryBanner", () => ({
  ConsentExpiryBanner: () => null,
}));

afterEach(() => {
  cleanup();
});

describe("AppShell", () => {
  it("clips the outer viewport shell without making it a scroll container", () => {
    const qc = new QueryClient({ defaultOptions: { queries: { retry: false } } });
    const { container } = render(
      <QueryClientProvider client={qc}>
        <ThemeProvider>
          <MemoryRouter initialEntries={["/settings#danger-zone"]}>
            <Routes>
              <Route element={<AppShell />}>
                <Route path="/settings" element={<div id="danger-zone">Danger zone</div>} />
              </Route>
            </Routes>
          </MemoryRouter>
        </ThemeProvider>
      </QueryClientProvider>,
    );

    const shell = container.firstElementChild;
    expect(shell).toHaveClass("overflow-clip");
    expect(shell).not.toHaveClass("overflow-hidden");
  });
});
