import { afterEach, beforeAll, beforeEach, describe, expect, it, vi } from "vitest";
import { cleanup, render, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { MemoryRouter, Route, Routes } from "react-router-dom";
import i18n from "@/i18n";
import { fieldByLabel, LocationProbe } from "@/test/LocationProbe";
import { lastRequest, stubLedgerApi } from "@/test/ledgerApi";
import { ExplorerTab } from "./ExplorerTab";
import { explorerSearch, explorerStateFromSearch } from "./explorerUrl";

vi.mock("@/auth/AuthContext", () => ({
  useActiveHousehold: () => ({ householdId: "h1", currency: "EUR", role: "owner" }),
}));

beforeAll(async () => {
  await i18n.changeLanguage("en");
});

beforeEach(() => {
  sessionStorage.clear();
  stubLedgerApi();
});

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
          <Route path="/analytics/explorer" element={<ExplorerTab />} />
        </Routes>
        <LocationProbe />
      </MemoryRouter>
    </QueryClientProvider>,
  );
}

const location = () => screen.getByTestId("location").textContent ?? "";

describe("Explorer state in the URL", () => {
  it("writes the main panel's scope, range and overlay by replacing the entry", async () => {
    const user = userEvent.setup();
    renderAt("/analytics/explorer");
    await screen.findByRole("option", { name: /Restaurants/ });
    const scope = fieldByLabel("Scope");

    await user.selectOptions(scope, "category:outings.restaurants");
    await user.selectOptions(fieldByLabel("Range"), "6m");
    await user.click(screen.getByRole("checkbox", { name: "Overlay same period one year earlier" }));

    expect(location()).toBe("/analytics/explorer?scope=category%3Aoutings.restaurants&range=6m&prior=0");
    expect(screen.getByTestId("navigation").textContent).toBe("REPLACE");
  });

  it("opening a written URL sends the same request the hand-set view sent", async () => {
    const user = userEvent.setup();
    const first = renderAt("/analytics/explorer");
    await screen.findByRole("option", { name: /Restaurants/ });
    await user.selectOptions(fieldByLabel("Scope"), "category:outings.restaurants");
    await user.click(screen.getByRole("checkbox", { name: "Overlay same period one year earlier" }));
    await waitFor(() => expect(lastRequest("/analytics/explorer")).toMatchObject({ yoyOverlay: false }));
    const url = location();
    const byHand = lastRequest("/analytics/explorer");
    first.unmount();

    stubLedgerApi();
    renderAt(url);

    await waitFor(() => expect(lastRequest("/analytics/explorer")).toEqual(byHand));
  });

  it("writes the stacked panel's selection and range without touching the main panel's", async () => {
    const user = userEvent.setup();
    renderAt("/analytics/explorer?scope=group%3Agroceries&range=1y&prior=1");
    await user.click(await screen.findByRole("checkbox", { name: /Outings/ }));

    expect(location()).toBe(
      "/analytics/explorer?scope=group%3Agroceries&range=1y&prior=1&stacked=group%3Aoutings&stackedRange=1y",
    );
  });

  it("round-trips a custom range through the serialisation", () => {
    const state = {
      scope: { type: "category" as const, code: "outings.restaurants" },
      range: { preset: "custom" as const, from: "2025-06-01", to: "2025-08-31" },
      prior: false,
    };
    const params = explorerSearch(state);

    expect(params.toString()).toBe("scope=category%3Aoutings.restaurants&range=custom&from=2025-06-01&to=2025-08-31&prior=0");
    expect(explorerStateFromSearch(params)).toMatchObject(state);
  });
});
