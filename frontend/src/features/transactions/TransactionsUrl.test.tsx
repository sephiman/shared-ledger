import { afterEach, beforeAll, beforeEach, describe, expect, it, vi } from "vitest";
import { cleanup, render, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { MemoryRouter, Route, Routes } from "react-router-dom";
import i18n from "@/i18n";
import { fieldByLabel, LocationProbe } from "@/test/LocationProbe";
import { lastRequest, stubLedgerApi } from "@/test/ledgerApi";
import { TransactionsPage } from "./TransactionsPage";

vi.mock("@/auth/AuthContext", () => ({
  useActiveHousehold: () => ({ householdId: "h1", currency: "EUR", role: "owner" }),
}));

beforeAll(async () => {
  await i18n.changeLanguage("en");
});

beforeEach(() => stubLedgerApi());

afterEach(() => {
  cleanup();
  vi.restoreAllMocks();
});

function renderAt(url: string) {
  const qc = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return render(
    <QueryClientProvider client={qc}>
      <MemoryRouter initialEntries={[url]}>
        <Routes>
          <Route path="/transactions" element={<TransactionsPage />} />
        </Routes>
        <LocationProbe />
      </MemoryRouter>
    </QueryClientProvider>,
  );
}

const location = () => screen.getByTestId("location").textContent;

describe("transactions list filters in the URL", () => {
  it("writes a hand-set filter to the URL by replacing the entry, and requests it", async () => {
    const user = userEvent.setup();
    renderAt("/transactions");
    await screen.findByRole("option", { name: /Restaurants/ });

    await user.selectOptions(fieldByLabel("Direction"), "expense");
    await user.selectOptions(fieldByLabel("Category"), "outings.restaurants");
    await user.click(screen.getByRole("button", { name: "Refunds only" }));

    expect(location()).toBe("/transactions?direction=expense&categoryCode=outings.restaurants&isRefund=true");
    expect(screen.getByTestId("navigation").textContent).toBe("REPLACE");
    await waitFor(() =>
      expect(lastRequest("/transactions")).toEqual({
        size: 50, page: 0, direction: "expense", categoryCode: "outings.restaurants", isRefund: true,
        from: undefined, to: undefined, categoryGroup: undefined,
      }),
    );
  });

  it("still applies a deep link such as Cost of living's, group included", async () => {
    renderAt("/transactions?from=2026-01-01&to=2026-09-30&direction=expense&categoryGroup=outings");

    await waitFor(() =>
      expect(lastRequest("/transactions")).toMatchObject({
        from: "2026-01-01", to: "2026-09-30", direction: "expense", categoryGroup: "outings",
      }),
    );
    expect(screen.getByDisplayValue("2026-01-01")).toBeInTheDocument();
  });

  it("clears every list filter from the URL and keeps the rest", async () => {
    const user = userEvent.setup();
    renderAt("/transactions?direction=expense&categoryCode=outings.restaurants&tab=confirmed");

    await user.click(await screen.findByRole("button", { name: "Clear filters" }));

    expect(location()).toBe("/transactions?tab=confirmed");
  });
});
