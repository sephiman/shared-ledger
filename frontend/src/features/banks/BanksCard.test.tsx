import { afterEach, beforeAll, beforeEach, describe, expect, it, vi } from "vitest";
import { cleanup, render, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { MemoryRouter } from "react-router-dom";
import type { AxiosRequestConfig, AxiosResponse } from "axios";
import { apiClient } from "@/api/client";
import type { Aspsp, BankConfig, BankConnection } from "@/api/banks";
import i18n from "@/i18n";
import { BanksCard } from "./BanksCard";

const ok = <T,>(data: T) => Promise.resolve({ data } as AxiosResponse<T>);

let aspspRequests: string[] = [];

beforeAll(async () => {
  await i18n.changeLanguage("en");
});

beforeEach(() => {
  aspspRequests = [];
  vi.spyOn(apiClient, "get").mockImplementation((url: string, config?: AxiosRequestConfig) => {
    if (url === "/households/h1/banks/connections") return ok<BankConnection[]>([]);
    if (url === "/households/h1/banks/config") {
      return ok<BankConfig>({ credentialsConfigured: true, connectionCount: 0, nextSyncTimes: [] });
    }
    if (url === "/households/h1/banks/aspsps") {
      const country = (config?.params as { country?: string } | undefined)?.country ?? "";
      aspspRequests.push(country);
      const list: Aspsp[] = country === "ES"
        ? [{ name: "Bankinter", country: "ES", logoUrl: null }]
        : [{ name: "ING", country, logoUrl: null }];
      return ok<Aspsp[]>(list);
    }
    throw new Error(`unexpected GET ${url}`);
  });
});

afterEach(() => {
  cleanup();
  vi.restoreAllMocks();
});

function pinViewerLocation(timeZone: string, languages: string[]) {
  const resolved = Intl.DateTimeFormat().resolvedOptions();
  vi.spyOn(Intl.DateTimeFormat.prototype, "resolvedOptions").mockReturnValue({ ...resolved, timeZone });
  vi.spyOn(navigator, "languages", "get").mockReturnValue(languages);
}

function renderCard() {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return render(
    <MemoryRouter>
      <QueryClientProvider client={client}>
        <BanksCard householdId="h1" locale="en" />
      </QueryClientProvider>
    </MemoryRouter>,
  );
}

describe("BanksCard", () => {
  it("defaults the bank country to the viewer's location and fetches its banks", async () => {
    pinViewerLocation("Europe/Amsterdam", ["en-US"]);
    renderCard();

    const countrySelect = await screen.findByLabelText("Country");
    expect(countrySelect).toHaveValue("NL");
    await waitFor(() => expect(aspspRequests).toContain("NL"));
  });

  it("defaults to ES when the viewer is in Madrid even with an English browser locale", async () => {
    pinViewerLocation("Europe/Madrid", ["en-US"]);
    renderCard();

    const countrySelect = await screen.findByLabelText("Country");
    expect(countrySelect).toHaveValue("ES");
    await waitFor(() => expect(aspspRequests).toContain("ES"));
    expect(await screen.findByRole("option", { name: "Bankinter" })).toBeInTheDocument();
  });

  it("supports countries across the EEA open-banking area", async () => {
    pinViewerLocation("Europe/Warsaw", ["en-US"]);
    renderCard();

    expect(await screen.findByLabelText("Country")).toHaveValue("PL");
    await waitFor(() => expect(aspspRequests).toContain("PL"));
  });

  it("falls back to ES when the viewer is outside supported bank countries", async () => {
    pinViewerLocation("America/New_York", ["en-US"]);
    renderCard();

    expect(await screen.findByLabelText("Country")).toHaveValue("ES");
    await waitFor(() => expect(aspspRequests).toContain("ES"));
  });

  it("lets the user switch country and reloads banks for the chosen country", async () => {
    pinViewerLocation("Europe/Madrid", ["es-ES"]);
    const user = userEvent.setup();
    renderCard();

    const countrySelect = await screen.findByLabelText("Country");
    expect(countrySelect).toHaveValue("ES");

    await user.selectOptions(countrySelect, "DE");

    expect(countrySelect).toHaveValue("DE");
    await waitFor(() => expect(aspspRequests).toContain("DE"));
  });
});
