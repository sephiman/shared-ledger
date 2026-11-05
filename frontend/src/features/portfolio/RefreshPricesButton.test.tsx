import { afterEach, beforeAll, beforeEach, describe, expect, it, vi } from "vitest";
import { act, cleanup, render, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import type { AxiosResponse } from "axios";
import { apiClient } from "@/api/client";
import type { PriceRefreshStatus, PriceRefreshTrigger } from "@/api/portfolio";
import i18n from "@/i18n";
import { RefreshPricesButton } from "./RefreshPricesButton";

const URL = "/households/h1/portfolio/refresh-prices";
const ok = <T,>(data: T) => Promise.resolve({ data } as AxiosResponse<T>);
const idle: PriceRefreshStatus = { running: false, lastFinishedAt: null, nextAllowedAt: null, equitiesStaleOnly: false };
const inFiveMinutes = () => new Date(Date.now() + 5 * 60_000).toISOString();

let serverStatus: PriceRefreshStatus;
let triggerAnswer: PriceRefreshTrigger;

beforeAll(async () => {
  await i18n.changeLanguage("en");
});

beforeEach(() => {
  vi.useFakeTimers({ shouldAdvanceTime: true });
  serverStatus = idle;
  triggerAnswer = { outcome: "started", status: { ...idle, running: true } };
  vi.spyOn(apiClient, "get").mockImplementation((url: string) => {
    if (url === URL) return ok(serverStatus);
    throw new Error(`unexpected GET ${url}`);
  });
  vi.spyOn(apiClient, "post").mockImplementation((url: string) => {
    if (url === URL) return ok(triggerAnswer);
    throw new Error(`unexpected POST ${url}`);
  });
});

afterEach(() => {
  cleanup();
  vi.useRealTimers();
  vi.restoreAllMocks();
});

function renderButton() {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  const invalidate = vi.spyOn(client, "invalidateQueries");
  render(
    <QueryClientProvider client={client}>
      <RefreshPricesButton householdId="h1" />
    </QueryClientProvider>,
  );
  return { invalidate };
}

describe("RefreshPricesButton", () => {
  it("refreshes, waits for the server run to finish, then reloads the portfolio and shows the cooldown", async () => {
    const user = userEvent.setup({ advanceTimers: vi.advanceTimersByTime });
    const { invalidate } = renderButton();

    await user.click(await screen.findByRole("button", { name: "Refresh now" }));
    expect(await screen.findByRole("button", { name: "Refreshing…" })).toBeDisabled();
    expect(invalidate).not.toHaveBeenCalledWith({ queryKey: ["portfolio", "h1"] });

    serverStatus = { ...idle, lastFinishedAt: new Date().toISOString(), nextAllowedAt: inFiveMinutes() };
    await act(() => vi.advanceTimersByTimeAsync(3000));

    await waitFor(() => expect(invalidate).toHaveBeenCalledWith({ queryKey: ["portfolio", "h1"] }));
    expect(screen.getByRole("button", { name: "Refresh now" })).toBeDisabled();
    expect(screen.getByText(/next refresh from/)).toBeInTheDocument();
  });

  it("stays shut while the household's cooldown runs and reopens once it lapses", async () => {
    serverStatus = { ...idle, lastFinishedAt: new Date().toISOString(), nextAllowedAt: inFiveMinutes() };
    renderButton();

    await waitFor(() => expect(screen.getByRole("button", { name: "Refresh now" })).toBeDisabled());
    expect(screen.getByText(/Refreshed at .* next refresh from/)).toBeInTheDocument();

    serverStatus = { ...idle, lastFinishedAt: serverStatus.lastFinishedAt };
    await act(() => vi.advanceTimersByTimeAsync(5 * 60_000));

    await waitFor(() => expect(screen.getByRole("button", { name: "Refresh now" })).toBeEnabled());
  });

  it("explains the limited refresh when the stock provider has a daily quota", async () => {
    serverStatus = { ...idle, equitiesStaleOnly: true };
    renderButton();

    expect(await screen.findByLabelText("What does Refresh now update?")).toBeInTheDocument();
    expect(screen.getByRole("tooltip")).toHaveTextContent(/only fetches stocks\/ETFs whose price is out of date/);
  });
});
