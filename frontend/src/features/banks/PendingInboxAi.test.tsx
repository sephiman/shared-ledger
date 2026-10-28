import { afterEach, beforeAll, beforeEach, describe, expect, it, vi } from "vitest";
import { cleanup, render, screen, waitFor, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { MemoryRouter, Route, Routes, useLocation } from "react-router-dom";
import type { AxiosResponse } from "axios";
import { apiClient } from "@/api/client";
import type { AiSuggestionRun, AiSuggestionSettings, AiSuggestionStatus } from "@/api/aiSuggestions";
import type { PendingMovement } from "@/api/banks";
import type { Category } from "@/api/catalog";
import i18n from "@/i18n";
import type { RulesPageState } from "./CategorizationRulesPage";
import { PendingInbox } from "./PendingInbox";

/** The inbox against a stubbed HTTP layer, so the real hooks decide what is requested and rendered. */

const category = (code: string, kind: "income" | "expense"): Category => ({
  code, kind, group: code.split(".")[0], sortOrder: 0, essential: false, name: `category.${code}`, custom: false,
});
const categories = [category("health.pharmacy", "expense"), category("groceries.groceries", "expense")];

const movement = (patch: Partial<PendingMovement>): PendingMovement => ({
  id: "m1", connectionId: "c1", connectionLabel: "ING", aspspName: "ING", accountId: "a1", accountName: null,
  bookingDate: "2026-09-16", valueDate: null, direction: "expense", amount: "4.99", originalAmount: null,
  originalCurrency: null, counterparty: "ETOS", description: "ETOS ALMERE", reference: null, status: "pending",
  suggestedCategoryCode: null, suggestionSource: null, suggestionConfidence: null, createdTransactionId: null,
  createdTransactionIds: [], createdMovementId: null, possibleDuplicate: false, ...patch,
});

const run = (patch: Partial<AiSuggestionRun>): AiSuggestionRun => ({
  id: "r1", status: "running", batchesTotal: 3, batchesDone: 1, batchesFailed: 0, itemsSent: 60,
  suggestionsStored: 0, itemsUnsuggested: 0, startedAt: "2026-10-26T10:00:00Z", finishedAt: null, ...patch,
});

interface Server {
  features: boolean;
  settings: AiSuggestionSettings;
  statuses: AiSuggestionStatus[];
  items: PendingMovement[];
}

let server: Server;
const requested: string[] = [];
const posted: string[] = [];

const ok = <T,>(data: T) => Promise.resolve({ data } as AxiosResponse<T>);

function route(url: string): unknown {
  if (url === "/features") return { ai: server.features };
  if (url === "/households/h1/ai-suggestions/settings") return server.settings;
  if (url === "/households/h1/banks/pending/ai-suggestions") {
    return server.statuses.length > 1 ? server.statuses.shift() : server.statuses[0];
  }
  if (url === "/households/h1/banks/connections") return [];
  if (url === "/households/h1/banks/pending") return { items: server.items, page: 0, size: 200, total: server.items.length };
  if (url === "/households/h1/categories") return categories;
  throw new Error(`unexpected GET ${url}`);
}

beforeAll(async () => {
  await i18n.changeLanguage("en");
});

beforeEach(() => {
  requested.length = 0;
  posted.length = 0;
  server = {
    features: true,
    settings: { enabled: true, model: "deepseek-v4.1-flash", providerHost: "opencode.ai" },
    statuses: [{ candidates: 2, run: null }],
    items: [
      movement({ id: "ai", counterparty: "ETOS", suggestedCategoryCode: "health.pharmacy", suggestionSource: "ai", suggestionConfidence: "0.93" }),
      movement({ id: "rule", counterparty: "Jumbo", suggestedCategoryCode: "groceries.groceries", suggestionSource: "rule" }),
    ],
  };
  vi.spyOn(apiClient, "get").mockImplementation((url: string) => {
    requested.push(url);
    return ok(route(url));
  });
  vi.spyOn(apiClient, "post").mockImplementation((url: string) => {
    posted.push(url);
    return ok(run({ batchesDone: 0 }));
  });
});

afterEach(() => {
  cleanup();
  vi.restoreAllMocks();
});

function renderInbox() {
  const qc = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return render(
    <QueryClientProvider client={qc}>
      <MemoryRouter>
        <PendingInbox householdId="h1" currency="EUR" locale="en" />
      </MemoryRouter>
    </QueryClientProvider>,
  );
}

const filterOptions = () =>
  within(screen.getByDisplayValue("All")).getAllByRole("option").map((o) => o.textContent);

describe("pending inbox with AI suggestions", () => {
  it("without a provider renders nothing AI-related and never asks for it", async () => {
    server.features = false;
    renderInbox();

    await screen.findByText("ETOS");
    expect(screen.queryByRole("button", { name: "Suggest with AI" })).not.toBeInTheDocument();
    expect(filterOptions()).toEqual(["All", "Uncategorized", "Categorized"]);
    expect(screen.queryByText("AI")).not.toBeInTheDocument();
    expect(requested.filter((u) => u.includes("ai-suggestions"))).toEqual([]);
  });

  it("with the household setting off shows no button, badge or AI filter", async () => {
    server.settings = { ...server.settings, enabled: false };
    renderInbox();

    await screen.findByText("ETOS");
    await waitFor(() => expect(requested).toContain("/households/h1/ai-suggestions/settings"));
    expect(screen.queryByRole("button", { name: "Suggest with AI" })).not.toBeInTheDocument();
    expect(filterOptions()).toEqual(["All", "Uncategorized", "Categorized"]);
    expect(requested).not.toContain("/households/h1/banks/pending/ai-suggestions");
  });

  it("with the setting on offers the button, explains what is shared and splits the filter by origin", async () => {
    renderInbox();

    const button = await screen.findByRole("button", { name: "Suggest with AI" });
    await waitFor(() => expect(button).toBeEnabled());
    // Opening it on hover/tap is the browser's <details>, which jsdom does not implement: checked in Playwright.
    const trigger = screen.getByLabelText("What does Suggest with AI share?");
    const tip = within(trigger.closest("details")!).getByRole("tooltip", { hidden: true });
    expect(tip.textContent).toContain("deepseek-v4.1-flash (opencode.ai)");
    expect(tip.textContent).toContain("Shared: counterparty, bank description, direction and booking month");
    expect(tip.textContent).toContain("Not shared: amounts, your IBANs, account and household identifiers");
    expect(filterOptions()).toEqual(["All", "Suggested by rule", "Suggested by AI", "Unsuggested"]);
  });

  it("marks only the AI-suggested row, with the confidence in the badge's tooltip", async () => {
    renderInbox();

    const badge = await screen.findByLabelText("Suggested by AI with 93% confidence. Check it before confirming.");
    expect(screen.getAllByText("AI")).toHaveLength(1);
    expect(within(screen.getByText("ETOS").closest("li")!).getByText("AI")).toBeInTheDocument();
    expect(within(badge.closest("details")!).getByRole("tooltip", { hidden: true }).textContent)
      .toBe("Suggested by AI with 93% confidence. Check it before confirming.");
  });

  it("drops the AI badge once the member picks another category for the row", async () => {
    const user = userEvent.setup();
    renderInbox();

    await screen.findByText("AI");
    const row = screen.getByText("ETOS").closest("li")!;
    await user.selectOptions(within(row).getByDisplayValue("Pharmacy"), "groceries.groceries");

    expect(screen.queryByText("AI")).not.toBeInTheDocument();
  });

  it("is disabled when nothing is left without a suggestion", async () => {
    server.statuses = [{ candidates: 0, run: null }];
    renderInbox();

    const button = await screen.findByRole("button", { name: "Suggest with AI" });
    await waitFor(() => expect(requested).toContain("/households/h1/banks/pending/ai-suggestions"));
    expect(button).toBeDisabled();
  });

  it("reports progress while running, then the result line", async () => {
    const user = userEvent.setup();
    renderInbox();
    const button = await screen.findByRole("button", { name: "Suggest with AI" });
    await waitFor(() => expect(button).toBeEnabled());
    server.statuses = [
      { candidates: 2, run: run({ batchesDone: 1 }) },
      { candidates: 0, run: run({ status: "completed", batchesDone: 3, batchesFailed: 1, suggestionsStored: 18, itemsUnsuggested: 17, finishedAt: "2026-10-26T10:01:00Z" }) },
    ];

    await user.click(button);

    expect(posted).toEqual(["/households/h1/banks/pending/ai-suggestions"]);
    expect(await screen.findByText("Suggesting with AI… batch 1 of 3")).toBeInTheDocument();
    expect(button).toBeDisabled();
    expect(
      await screen.findByText(
        "AI suggestions: 18 made, 17 below the confidence threshold or without a valid answer, 1 failed batches",
        undefined,
        { timeout: 3000 },
      ),
    ).toBeInTheDocument();
  });

  it("shows another member's run in progress and keeps the button disabled", async () => {
    server.statuses = [{ candidates: 5, run: run({ batchesDone: 2 }) }];
    renderInbox();

    expect(await screen.findByText("Suggesting with AI… batch 2 of 3")).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "Suggest with AI" })).toBeDisabled();
  });
});

// Stands in for the rules page (its own test covers rendering); shows the state the link handed over.
function BackTarget() {
  const state = useLocation().state as RulesPageState | null;
  return <p>back to {state?.backTo}</p>;
}

describe("pending inbox toolbar", () => {
  it("opens the rules page with a way back to the inbox", async () => {
    server.features = false;
    const user = userEvent.setup();
    const qc = new QueryClient({ defaultOptions: { queries: { retry: false } } });
    render(
      <QueryClientProvider client={qc}>
        <MemoryRouter initialEntries={["/transactions?tab=pending"]}>
          <Routes>
            <Route path="/transactions" element={<PendingInbox householdId="h1" currency="EUR" locale="en" />} />
            <Route path="/settings/rules" element={<BackTarget />} />
          </Routes>
        </MemoryRouter>
      </QueryClientProvider>,
    );

    await user.click(await screen.findByRole("link", { name: "Manage rules" }));

    expect(await screen.findByText("back to /transactions?tab=pending")).toBeInTheDocument();
  });
});
