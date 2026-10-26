import { afterEach, beforeAll, describe, expect, it, vi } from "vitest";
import { cleanup, render, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import type { AxiosResponse } from "axios";
import { apiClient } from "@/api/client";
import i18n from "@/i18n";
import { AiSuggestionsCard } from "./AiSuggestionsCard";

const ok = <T,>(data: T) => Promise.resolve({ data } as AxiosResponse<T>);

function renderCard() {
  const qc = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return render(
    <QueryClientProvider client={qc}>
      <AiSuggestionsCard householdId="h1" />
    </QueryClientProvider>,
  );
}

beforeAll(async () => {
  await i18n.changeLanguage("en");
});

afterEach(() => {
  cleanup();
  vi.restoreAllMocks();
});

describe("AI suggestions setting", () => {
  it("is off by default and says what is sent, to which model and host, and what is not", async () => {
    vi.spyOn(apiClient, "get").mockImplementation((url: string) =>
      url === "/features"
        ? ok({ aiCategorySuggestions: true })
        : ok({ enabled: false, model: "deepseek-v4.1-flash", providerHost: "opencode.ai" }),
    );
    renderCard();

    expect(await screen.findByRole("checkbox", { name: "Offer \"Suggest with AI\" in the pending inbox" })).not.toBeChecked();
    const help = screen.getByText(/are sent to deepseek-v4\.1-flash at opencode\.ai/);
    expect(help.textContent).toContain("the counterparty, bank description, direction and booking month");
    expect(help.textContent).toContain("Amounts, the household's IBANs (every IBAN is removed from the text), account and household identifiers are not sent");
  });

  it("turns the setting on through the API", async () => {
    const user = userEvent.setup();
    vi.spyOn(apiClient, "get").mockImplementation((url: string) =>
      url === "/features"
        ? ok({ aiCategorySuggestions: true })
        : ok({ enabled: false, model: "m", providerHost: "h" }),
    );
    const put = vi.spyOn(apiClient, "put").mockImplementation(() => ok({ enabled: true, model: "m", providerHost: "h" }));
    renderCard();

    await user.click(await screen.findByRole("checkbox", { name: "Offer \"Suggest with AI\" in the pending inbox" }));

    expect(put).toHaveBeenCalledWith("/households/h1/ai-suggestions/settings", { enabled: true });
    await waitFor(() => expect(screen.getByRole("checkbox")).toBeChecked());
  });

  it("asks for nothing when the instance has no provider", async () => {
    const get = vi.spyOn(apiClient, "get").mockImplementation(() => ok({ aiCategorySuggestions: false }));
    const { container } = renderCard();

    await waitFor(() => expect(get).toHaveBeenCalledWith("/features"));
    expect(get).not.toHaveBeenCalledWith("/households/h1/ai-suggestions/settings");
    expect(container).toBeEmptyDOMElement();
  });
});
