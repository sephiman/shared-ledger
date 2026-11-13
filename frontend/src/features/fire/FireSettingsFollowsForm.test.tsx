import type { AxiosResponse } from "axios";
import { QueryClientProvider } from "@tanstack/react-query";
import { MemoryRouter } from "react-router-dom";
import { render, screen, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import i18n from "@/i18n";
import { apiClient } from "@/api/client";
import type { FirePreviewRequest, FireProjection } from "@/api/fire";
import { createQueryClient } from "@/lib/queryClient";
import { FirePage } from "./FirePage";
import { makeProjection } from "./fireTestProjection";

vi.mock("@/auth/AuthContext", () => ({
  useActiveHousehold: () => ({ householdId: "h1", name: "Home", currency: "EUR", role: "owner" }),
}));

const saved = makeProjection();

/** What the backend answers for the unsaved form: the 24-month window has different bases. */
function previewFor(body: FirePreviewRequest): FireProjection {
  const settings = body.settings ?? saved.settings;
  const longWindow = settings.spendingLookbackMonths === 24;
  const swr = Number(settings.safeWithdrawalRatePct) / 100;
  return makeProjection({
    settings,
    tiers: saved.tiers.map((tier) =>
      tier.key === "fire" ? { ...tier, targetToday: (Number(tier.annualGrossSpending) / swr).toFixed(2) } : tier,
    ),
    spending: {
      ...saved.spending,
      derivedEssentialMonthly: longWindow ? "1350.00" : saved.spending.derivedEssentialMonthly,
      essentialMonthly: longWindow ? "1350.00" : saved.spending.essentialMonthly,
      derivedTotalMonthly: longWindow ? "2500.00" : saved.spending.derivedTotalMonthly,
      totalMonthly: longWindow ? "2500.00" : saved.spending.totalMonthly,
      lookbackMonths: settings.spendingLookbackMonths,
      aggregation: settings.spendingAggregation,
      excludeCurrentMonth: settings.excludeCurrentMonth,
      noSpendingSince: longWindow ? "2026-08" : null,
    },
  });
}

const previews: FirePreviewRequest[] = [];

function stubFireApi() {
  previews.length = 0;
  vi.spyOn(apiClient, "get").mockImplementation((url: string) => {
    if (url === "/households/h1/fire/settings") return Promise.resolve({ data: saved.settings } as AxiosResponse);
    if (url === "/households/h1/fire/projection") return Promise.resolve({ data: saved } as AxiosResponse);
    if (url === "/asset-classes") return Promise.resolve({ data: [{ code: "etfs" }] } as AxiosResponse);
    // History, levers and template suggestions are background extras this test does not look at.
    return new Promise<AxiosResponse>(() => undefined);
  });
  vi.spyOn(apiClient, "post").mockImplementation((url: string, body?: unknown) => {
    if (url !== "/households/h1/fire/preview") throw new Error(`unexpected POST ${url}`);
    previews.push(body as FirePreviewRequest);
    return Promise.resolve({ data: previewFor(body as FirePreviewRequest) } as AxiosResponse);
  });
}

function renderSettingsTab() {
  return render(
    <QueryClientProvider client={createQueryClient()}>
      <MemoryRouter initialEntries={["/fire?tab=settings"]}>
        <FirePage />
      </MemoryRouter>
    </QueryClientProvider>,
  );
}

function spendingSection(): HTMLElement {
  const summary = screen.getByText("Spending bases").closest("details");
  if (!summary) throw new Error("no spending section");
  return summary;
}

describe("FIRE settings: derived spending follows the form", () => {
  beforeEach(async () => {
    await i18n.changeLanguage("en");
    stubFireApi();
  });

  afterEach(() => {
    vi.restoreAllMocks();
  });

  it("shows the bases of the new window before saving, with texts, summary and amount in agreement", async () => {
    const user = userEvent.setup();
    renderSettingsTab();
    const section = await screen.findByText("Spending bases").then(() => spendingSection());
    expect(within(section).getByText("12m · average · essential €1,200 · total €2,000")).toBeInTheDocument();

    await user.click(within(section).getByRole("button", { name: "24m" }));

    expect(
      await within(section).findByText("24m · average · essential €1,350 · total €2,500", {}, { timeout: 3000 }),
    ).toBeInTheDocument();
    expect(within(section).getByText("Monthly average of the last 24 months of spending in essential categories, including the current month.")).toBeInTheDocument();
    expect(within(section).getByText("Monthly average of the last 24 months of all spending, including the current month.")).toBeInTheDocument();
    expect(within(section).getByText("€1,350")).toBeInTheDocument();
    expect(within(section).getByText("€2,500")).toBeInTheDocument();
    expect(within(section).queryByText("€1,200")).not.toBeInTheDocument();
    expect(within(section).getByText("No spending recorded since August 2026 — the target is probably understated.")).toBeInTheDocument();
    expect(within(section).getByText("Not saved yet")).toBeInTheDocument();

    // The bases were asked of the existing preview, for exactly the unsaved form.
    expect(previews.at(-1)?.settings).toEqual(expect.objectContaining({ spendingLookbackMonths: 24 }));
  });

  it("keeps the previous figure dimmed, with the text that describes it, until the new bases arrive", async () => {
    const user = userEvent.setup();
    renderSettingsTab();
    const section = await screen.findByText("Spending bases").then(() => spendingSection());

    await user.click(within(section).getByRole("button", { name: "24m" }));

    // Debounce not elapsed yet: the old figure and its own 12-month text, marked as pending.
    const essential = within(section).getByText("€1,200");
    expect(essential.closest("[aria-busy='true']")).not.toBeNull();
    expect(within(section).getByText("Monthly average of the last 12 months of all spending, including the current month.")).toBeInTheDocument();

    await within(section).findByText("€1,350", {}, { timeout: 3000 });
    expect(within(section).getByText("€1,350").closest("[aria-busy='true']")).toBeNull();
  });

  it("the withdrawal-rate line names the rate it was computed with, and follows the form", async () => {
    const user = userEvent.setup();
    renderSettingsTab();
    const question = await screen.findByText("How much do we withdraw per year once retired?");
    const field = question.parentElement?.querySelector("input");
    if (!field) throw new Error("no SWR field");
    expect(screen.getByText("Annual share of your wealth you would withdraw once retired. At 4% your FIRE target is €600,000; at 3.5% it would be €685,714.")).toBeInTheDocument();

    await user.clear(field);
    await user.type(field, "5");

    expect(
      await screen.findByText(
        "Annual share of your wealth you would withdraw once retired. At 5% your FIRE target is €480,000; at 4.5% it would be €533,333.",
        {},
        { timeout: 3000 },
      ),
    ).toBeInTheDocument();
    expect(screen.getByText("Not saved yet")).toBeInTheDocument();
  });
});
