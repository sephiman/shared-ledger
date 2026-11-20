import { afterEach, beforeAll, beforeEach, describe, expect, it, vi } from "vitest";
import { cleanup, render, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { MemoryRouter, Route, Routes } from "react-router-dom";
import i18n from "@/i18n";
import { fieldByLabel, LocationProbe } from "@/test/LocationProbe";
import { lastRequest, listedTransaction, stubLedgerApi } from "@/test/ledgerApi";
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
        from: undefined, to: undefined, amountFrom: undefined, amountTo: undefined, categoryGroup: undefined, q: undefined,
      }),
    );
  });

  it("writes q description search to URL, requests, totals, and CSV export, and clears it cleanly", async () => {
    stubLedgerApi(undefined, { page: { items: [listedTransaction()], page: 0, size: 50, total: 1 } });
    const user = userEvent.setup();
    renderAt("/transactions");
    await screen.findByRole("option", { name: /Restaurants/ });

    const searchInput = screen.getByRole("searchbox", { name: "Description" });
    await user.type(searchInput, "Vattenfall");

    await waitFor(() => expect(location()).toBe("/transactions?q=Vattenfall"));
    await waitFor(() => expect(lastRequest("/transactions")).toMatchObject({ q: "Vattenfall" }));
    await waitFor(() => expect(lastRequest("/transactions/totals")).toMatchObject({ q: "Vattenfall" }));
    expect(screen.getByRole("link", { name: "Export CSV" })).toHaveAttribute(
      "href",
      "/api/households/h1/transactions/export.csv?q=Vattenfall",
    );

    await user.click(screen.getByRole("button", { name: "Clear filters" }));
    expect(location()).toBe("/transactions");
    expect(searchInput).toHaveValue("");
  });

  it("writes amountFrom and amountTo to URL, requests, and CSV export, and clears them cleanly", async () => {
    const user = userEvent.setup();
    renderAt("/transactions");
    await screen.findByRole("option", { name: /Restaurants/ });

    await user.type(fieldByLabel("Amount from"), "10.50");
    await user.type(fieldByLabel("Amount to"), "200,25");

    await waitFor(() =>
      expect(location()).toBe("/transactions?amountFrom=10.50&amountTo=200.25"),
    );
    await waitFor(() =>
      expect(lastRequest("/transactions")).toMatchObject({ amountFrom: "10.50", amountTo: "200.25" }),
    );
    expect(screen.getByRole("link", { name: "Export CSV" })).toHaveAttribute(
      "href",
      "/api/households/h1/transactions/export.csv?amountFrom=10.50&amountTo=200.25",
    );

    await user.click(screen.getByRole("button", { name: "Clear filters" }));

    expect(location()).toBe("/transactions");
    expect(fieldByLabel("Amount from")).toHaveValue("");
    expect(fieldByLabel("Amount to")).toHaveValue("");
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

  it("clears every list filter and sort from the URL while keeping the tab", async () => {
    const user = userEvent.setup();
    renderAt("/transactions?direction=expense&categoryCode=outings.restaurants&tab=confirmed&sort=amount_desc");

    await user.click(await screen.findByRole("button", { name: "Clear filters" }));

    expect(location()).toBe("/transactions?tab=confirmed");
    expect(screen.queryByRole("button", { name: "Clear filters" })).not.toBeInTheDocument();
  });

  it("sorts by every column header and reflects sort state in aria-sort, URL, and API requests", async () => {
    stubLedgerApi(undefined, { page: { items: [listedTransaction()], page: 0, size: 50, total: 1 } });
    const user = userEvent.setup();
    renderAt("/transactions");

    const dateHeader = await screen.findByRole("columnheader", { name: "Date" });
    expect(dateHeader).toHaveAttribute("aria-sort", "descending");
    expect(screen.getByRole("columnheader", { name: "Amount" })).toHaveAttribute("aria-sort", "none");

    await user.click(screen.getByRole("button", { name: "Amount" }));
    expect(screen.getByRole("columnheader", { name: "Amount" })).toHaveAttribute("aria-sort", "descending");
    expect(screen.getByRole("columnheader", { name: "Date" })).toHaveAttribute("aria-sort", "none");
    expect(location()).toBe("/transactions?sort=amount_desc");
    await waitFor(() => expect(lastRequest("/transactions")).toMatchObject({ sort: "amount_desc" }));

    await user.click(screen.getByRole("button", { name: "Amount" }));
    expect(screen.getByRole("columnheader", { name: "Amount" })).toHaveAttribute("aria-sort", "ascending");
    expect(location()).toBe("/transactions?sort=amount_asc");

    await user.click(screen.getByRole("button", { name: "Category" }));
    expect(screen.getByRole("columnheader", { name: "Category" })).toHaveAttribute("aria-sort", "ascending");
    expect(location()).toBe("/transactions?sort=category_asc");
    await waitFor(() => expect(lastRequest("/transactions")).toMatchObject({ sort: "category_asc", locale: "en" }));

    await user.click(screen.getByRole("button", { name: "Description" }));
    expect(screen.getByRole("columnheader", { name: "Description" })).toHaveAttribute("aria-sort", "ascending");
    expect(location()).toBe("/transactions?sort=description_asc");

    // Clicking Date returns to default date_desc and removes sort from URL.
    await user.click(screen.getByRole("button", { name: "Date" }));
    expect(screen.getByRole("columnheader", { name: "Date" })).toHaveAttribute("aria-sort", "descending");
    expect(location()).toBe("/transactions");
  });
});
