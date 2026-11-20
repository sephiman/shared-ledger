import { afterEach, beforeAll, describe, expect, it, vi } from "vitest";
import { cleanup, render, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { MemoryRouter, Route, Routes, useLocation } from "react-router-dom";
import { apiClient } from "@/api/client";
import type { AnalyticsAiRun, SpendingReviewCandidatesResponse } from "@/api/analyticsAi";
import i18n from "@/i18n";
import { explorerSearch } from "@/features/analytics/explorerUrl";
import { withTransactionFilters } from "@/features/transactions/txFilters";
import { SpendingReviewTab } from "./SpendingReviewTab";
import { explorerUrlFromLink, transactionsUrlFromFilter } from "./reviewLinks";

let mockedRole: "owner" | "member" = "owner";

vi.mock("@/auth/AuthContext", () => ({
  useActiveHousehold: () => ({ householdId: "h1", currency: "EUR", role: mockedRole }),
}));

beforeAll(async () => {
  await i18n.changeLanguage("en");
});

afterEach(() => {
  mockedRole = "owner";
  cleanup();
  vi.restoreAllMocks();
});

const SAMPLE_CANDIDATES: SpendingReviewCandidatesResponse = {
  period: {
    year: 2026,
    month: 10,
    reviewKind: "complete_month",
    windowFrom: "2025-11-01",
    windowTo: "2026-10-31",
    daysElapsed: 31,
    daysInMonth: 31,
    minDaysElapsed: 7,
    paceReviewEligible: true,
    staleSpendingSince: null,
    periodHasNoExpenses: false,
    monthsAvailable: 12,
  },
  candidates: {
    recurringCharges: [
      {
        id: "rec_1",
        merchant: "HBO",
        payloadLabel: "HBO",
        anonymized: false,
        categoryCode: "outings.subscriptions",
        groupCode: "outings",
        essential: false,
        cadence: "monthly",
        source: "detected",
        occurrences: 12,
        latestAmount: "6.99",
        monthlyCost: "6.99",
        annualCost: "83.88",
        priorYearAmount: "4.99",
        yoyDeltaAbs: "2.00",
        yoyDeltaPct: 40.1,
        potentialMonthlySaving: "6.99",
        potentialAnnualSaving: "83.88",
        txFilter: {
          from: "2025-11-01",
          to: "2026-10-31",
          direction: "expense",
          categoryCode: "outings.subscriptions",
          q: "HBO",
        },
        explorerLink: {
          scopeType: "category",
          scopeCode: "outings.subscriptions",
          preset: "custom",
          from: "2025-11-01",
          to: "2026-10-31",
        },
      },
      {
        id: "rec_2",
        merchant: "Carlos Ruiz Bizum",
        payloadLabel: "Recurring charge in home.rent",
        anonymized: true,
        categoryCode: "home.rent",
        groupCode: "home",
        essential: true,
        cadence: "monthly",
        source: "detected",
        occurrences: 12,
        latestAmount: "650.00",
        monthlyCost: "650.00",
        annualCost: "7800.00",
        priorYearAmount: null,
        yoyDeltaAbs: null,
        yoyDeltaPct: null,
        potentialMonthlySaving: null,
        potentialAnnualSaving: null,
        txFilter: {
          from: "2025-11-01",
          to: "2026-10-31",
          direction: "expense",
          categoryCode: "home.rent",
          q: "Carlos Ruiz Bizum",
        },
        explorerLink: {
          scopeType: "category",
          scopeCode: "home.rent",
          preset: "custom",
          from: "2025-11-01",
          to: "2026-10-31",
        },
      },
    ],
    topNonEssentialMerchants: [],
    otherMerchants: { total: "120.00", purchases: 5, merchantCount: 4 },
    feesAndInterest: { fees: [], loanInterest: null },
    lumpyCategories: [],
    summary: {
      nonEssentialRecurringMonthly: "6.99",
      nonEssentialRecurringAnnual: "83.88",
      feesMonthly: "0.00",
      feesAnnual: "0.00",
      lumpyTrimMonthly: "0.00",
      lumpyTrimAnnual: "0.00",
      combinedPotentialMonthly: "6.99",
      combinedPotentialAnnual: "83.88",
    },
    cutCombinations: [],
  },
  skeleton: {
    mode: "full",
    summary: "October closed with €3,000 in expenses and €5,000 in income.",
    savingsOpportunities: [
      {
        findingId: "sav_rec_1",
        candidateId: "rec_1",
        localName: "HBO",
        mergedVariants: [],
        categoryCode: "outings.subscriptions",
        groupCode: "outings",
        title: "HBO recurring charge",
        detail: "HBO costs €6.99 a month, or €83.88 a year.",
        whyFlagged: "",
        severity: "opportunity",
        action: "cancellable",
        potentialMonthlySaving: "6.99",
        potentialAnnualSaving: "83.88",
        metrics: [{ key: "annualCost", kind: "money", value: "83.88" }],
        citations: [],
        txFilter: {
          from: "2025-11-01",
          to: "2026-10-31",
          direction: "expense",
          categoryCode: "outings.subscriptions",
          q: "HBO",
        },
        explorerLink: {
          scopeType: "category",
          scopeCode: "outings.subscriptions",
          preset: "custom",
          from: "2025-11-01",
          to: "2026-10-31",
        },
        simulationScenario: {
          freeze: [],
          adjustments: [{ scope: { type: "category", code: "outings.subscriptions" }, kind: "amount", value: -6.99 }],
          level: "category",
          windowMonths: null,
          modeOverride: null,
          savingsTarget: null,
        },
      },
    ],
    whatChanged: [],
    budgetFindings: [],
    savingsOmitted: 2,
    whatChangedOmitted: 0,
    budgetsOmitted: 0,
    underlyingMovers: [],
    underlyingBudgetOverruns: [],
  },
};

const SAMPLE_RUN: AnalyticsAiRun = {
  id: "run-1",
  status: "completed",
  createdAt: "2026-11-19T08:00:00Z",
  finishedAt: "2026-11-19T08:00:04Z",
  scope: {
    periodYear: 2026,
    periodMonth: 10,
    reviewKind: "complete_month",
    daysElapsed: 31,
    daysInMonth: 31,
    staleSpendingSince: null,
    hasBudgets: false,
  },
  model: "deepseek-v4.1-flash",
  promptVersion: "ar6",
  summary: "October closed below your twelve-month average.",
  savingsOpportunities: [
    {
      findingId: "sav_rec_1",
      candidateId: "rec_1",
      localName: "HBO",
      categoryCode: "outings.subscriptions",
      groupCode: "outings",
      title: "Review HBO subscription",
      detail: "Price increased over the last year.",
      whyFlagged: "",
      severity: "opportunity",
      action: "cancellable",
      potentialMonthlySaving: "6.99",
      potentialAnnualSaving: "83.88",
      metrics: [{ key: "annualCost", kind: "money", value: "83.88" }],
      citations: [],
      txFilter: SAMPLE_CANDIDATES.candidates.recurringCharges[0].txFilter,
      explorerLink: SAMPLE_CANDIDATES.candidates.recurringCharges[0].explorerLink,
      simulationScenario: {
        freeze: [],
        adjustments: [{ scope: { type: "category", code: "outings.subscriptions" }, kind: "amount", value: -6.99 }],
        level: "category",
        windowMonths: null,
        modeOverride: null,
        savingsTarget: null,
      },
    },
  ],
  whatChanged: [],
  budgetFindings: [],
  dataGaps: [],
  savingsOmitted: 0,
  whatChangedOmitted: 0,
  budgetsOmitted: 0,
  findingsDropped: 0,
  citationsTotal: 0,
  citationsUngrounded: 0,
  inputTokens: 1200,
  outputTokens: 210,
  reasoningTokens: 0,
  latencyMs: 2400,
  errorCode: null,
  followups: [],
};

function MoneyFlowSpy() {
  const loc = useLocation();
  return <pre data-testid="money-flow-state">{JSON.stringify(loc.state)}</pre>;
}

function stubApi({ aiEnabled, runs = [] }: { aiEnabled: boolean; runs?: AnalyticsAiRun[] }) {
  const calledUrls: string[] = [];
  vi.spyOn(apiClient, "get").mockImplementation(async (url: string) => {
    calledUrls.push(url);
    if (url === "/features") return { data: { ai: aiEnabled } };
    if (url.endsWith("/ai-suggestions/settings")) {
      return { data: { enabled: aiEnabled, model: "deepseek-v4.1-flash", providerHost: "opencode.ai" } };
    }
    if (url.endsWith("/analytics/years-available")) return { data: { years: [2026, 2025] } };
    if (url.endsWith("/categories")) {
      return {
        data: [
          { code: "outings.subscriptions", kind: "expense", group: "outings", name: "Subscriptions", essential: false, custom: false },
          { code: "home.rent", kind: "expense", group: "home", name: "Rent", essential: true, custom: false },
        ],
      };
    }
    if (url.endsWith("/analytics/spending-review/candidates")) return { data: SAMPLE_CANDIDATES };
    if (url.endsWith("/analytics/ai-review")) return { data: runs };
    if (url.endsWith("/analytics/ai-review/payload-preview")) {
      return {
        data: {
          periodYear: 2026,
          periodMonth: 10,
          reviewKind: "complete_month",
          model: "deepseek-v4.1-flash",
          providerHost: "opencode.ai",
          byteSize: 42,
          truncatedSections: [],
          payloadJson: '{"scope":{"currency":"EUR","periodMonth":10}}',
        },
      };
    }
    return { data: {} };
  });
  return calledUrls;
}

function renderTab() {
  const qc = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return render(
    <QueryClientProvider client={qc}>
      <MemoryRouter initialEntries={["/analytics/review"]}>
        <Routes>
          <Route path="/analytics/review" element={<SpendingReviewTab />} />
          <Route path="/analytics/money-flow" element={<MoneyFlowSpy />} />
        </Routes>
      </MemoryRouter>
    </QueryClientProvider>,
  );
}

describe("SpendingReviewTab & deep links", () => {
  it("builds candidate deep links identical to manual Transactions and Explorer URL helpers", () => {
    const filter = SAMPLE_CANDIDATES.candidates.recurringCharges[0].txFilter;
    const manualTx = `/transactions?${withTransactionFilters(new URLSearchParams(), {
      from: filter.from,
      to: filter.to,
      direction: filter.direction,
      categoryCode: filter.categoryCode ?? undefined,
      q: filter.q ?? undefined,
    }).toString()}`;
    expect(transactionsUrlFromFilter(filter)).toBe(manualTx);

    const expLink = SAMPLE_CANDIDATES.candidates.recurringCharges[0].explorerLink;
    const manualExp = `/analytics/explorer?${explorerSearch({
      scope: { type: "category", code: "outings.subscriptions" },
      range: { preset: "custom", from: "2025-11-01", to: "2026-10-31" },
    }).toString()}`;
    expect(explorerUrlFromLink(expLink)).toBe(manualExp);
  });

  it("renders the deterministic report skeleton with 'and N more' opening Underlying data and ZERO AI requests when AI is off", async () => {
    const called = stubApi({ aiEnabled: false });
    const user = userEvent.setup();
    renderTab();

    // Deterministic report skeleton is rendered directly without AI
    expect(await screen.findByText("HBO recurring charge")).toBeInTheDocument();
    expect(screen.getByText("HBO costs €6.99 a month, or €83.88 a year.")).toBeInTheDocument();
    expect(screen.queryByRole("button", { name: /Write with AI/i })).not.toBeInTheDocument();
    expect(called.some((u) => u.includes("/analytics/ai-review"))).toBe(false);

    // Clicking "and 2 more" opens the Underlying data disclosure with real local names (including anonymized P2P)
    await user.click(screen.getByRole("button", { name: "and 2 more" }));
    expect(await screen.findByText("Carlos Ruiz Bizum")).toBeInTheDocument();
  });

  it("renders grounded AI report, omits empty sections, and wires Simulate in Money flow to Money flow route state", async () => {
    stubApi({ aiEnabled: true, runs: [SAMPLE_RUN] });
    const user = userEvent.setup();
    renderTab();

    expect(await screen.findByText("Review HBO subscription")).toBeInTheDocument();
    expect(screen.getByText("Where the money could be saved")).toBeInTheDocument();
    // Empty sections are omitted & Strengths is removed
    expect(screen.queryByText("What changed")).not.toBeInTheDocument();
    expect(screen.queryByText("Budgets")).not.toBeInTheDocument();
    expect(screen.queryByText("Strengths")).not.toBeInTheDocument();

    await user.click(screen.getByRole("button", { name: "Simulate in Money flow" }));
    const stateEl = await screen.findByTestId("money-flow-state");
    expect(stateEl.textContent).toContain("outings.subscriptions");
    expect(stateEl.textContent).toContain("-6.99");
  });

  it("exposes 'Show exactly what will be sent' payload preview only to owners", async () => {
    const ownerCalls = stubApi({ aiEnabled: true, runs: [] });
    const user = userEvent.setup();
    renderTab();

    const toggle = await screen.findByRole("button", { name: "Show exactly what will be sent" });
    expect(ownerCalls.some((u) => u.includes("payload-preview"))).toBe(false);

    await user.click(toggle);
    const pre = await screen.findByTestId("payload-preview-json");
    expect(pre.textContent).toBe('{"scope":{"currency":"EUR","periodMonth":10}}');
    expect(ownerCalls.some((u) => u.includes("payload-preview"))).toBe(true);

    // Non-owner member never sees the disclosure and never fetches payload-preview
    cleanup();
    vi.restoreAllMocks();
    mockedRole = "member";
    const memberCalls = stubApi({ aiEnabled: true, runs: [] });
    renderTab();
    expect(await screen.findByRole("button", { name: /Write with AI/i })).toBeInTheDocument();
    expect(screen.queryByRole("button", { name: "Show exactly what will be sent" })).not.toBeInTheDocument();
    expect(memberCalls.some((u) => u.includes("payload-preview"))).toBe(false);
  });

  it("shows merged payee variants locally on recurring and merchant rows inside Underlying data (Postcode Loterij fixture)", async () => {
    const postcodeVariants = ["Nat Postcode", "Postcode Loterij", "Nat Postcode Loterij", "National Postcode"];
    const withVariants: SpendingReviewCandidatesResponse = {
      ...SAMPLE_CANDIDATES,
      skeleton: {
        ...SAMPLE_CANDIDATES.skeleton!,
        savingsOpportunities: [],
      },
      candidates: {
        ...SAMPLE_CANDIDATES.candidates,
        recurringCharges: [
          {
            ...SAMPLE_CANDIDATES.candidates.recurringCharges[0],
            id: "rec_postcode",
            merchant: "Nat Postcode",
            mergedVariants: postcodeVariants,
            payloadLabel: "Nat Postcode",
            categoryCode: "outings.subscriptions",
          },
          SAMPLE_CANDIDATES.candidates.recurringCharges[1],
        ],
        topNonEssentialMerchants: [
          {
            id: "merch_postcode",
            merchant: "Nat Postcode",
            mergedVariants: postcodeVariants,
            payloadLabel: "Nat Postcode",
            anonymized: false,
            categoryCode: "outings.subscriptions",
            groupCode: "outings",
            essential: false,
            total: "252.00",
            purchases: 12,
            averageTicket: "21.00",
            shareOfCategoryPct: 48.0,
            monthlyEquivalent: "21.00",
            potentialMonthlySaving: null,
            potentialAnnualSaving: null,
            txFilter: {
              from: "2025-11-01",
              to: "2026-10-31",
              direction: "expense",
              categoryCode: "outings.subscriptions",
              q: "Postcode",
            },
            explorerLink: {
              scopeType: "category",
              scopeCode: "outings.subscriptions",
              preset: "custom",
              from: "2025-11-01",
              to: "2026-10-31",
            },
          },
        ],
      },
    };
    vi.spyOn(apiClient, "get").mockImplementation(async (url: string) => {
      if (url === "/features") return { data: { ai: false } };
      if (url.endsWith("/analytics/years-available")) return { data: { years: [2026] } };
      if (url.endsWith("/categories")) {
        return {
          data: [
            { code: "outings.subscriptions", kind: "expense", group: "outings", name: "Subscriptions", essential: false, custom: false },
            { code: "home.rent", kind: "expense", group: "home", name: "Rent", essential: true, custom: false },
          ],
        };
      }
      if (url.endsWith("/analytics/spending-review/candidates")) return { data: withVariants };
      return { data: {} };
    });

    const user = userEvent.setup();
    renderTab();

    // Open Underlying data disclosure
    await user.click(await screen.findByRole("button", { name: /Underlying data/i }));

    // Both the recurring row and the merchant row render a "4 variants" toggle with tooltip
    const toggles = await screen.findAllByRole("button", { name: "4 variants" });
    expect(toggles).toHaveLength(2);
    expect(toggles[0]).toHaveAttribute(
      "title",
      "Includes: Nat Postcode, Postcode Loterij, Nat Postcode Loterij, National Postcode",
    );

    // Expanding reveals the merged variants inline
    await user.click(toggles[0]);
    expect(
      screen.getByText("Includes: Nat Postcode, Postcode Loterij, Nat Postcode Loterij, National Postcode"),
    ).toBeInTheDocument();
  });

  it("offers last month with data and 12-month savings-only actions when the selected month is empty or stale", async () => {
    const staleResp: SpendingReviewCandidatesResponse = {
      ...SAMPLE_CANDIDATES,
      period: {
        ...SAMPLE_CANDIDATES.period,
        staleSpendingSince: "2026-05",
        periodHasNoExpenses: true,
        lastMonthWithData: "2026-05",
        reviewBlockedReason: "empty_or_stale",
      },
      skeleton: {
        mode: "blocked",
        summary: null,
        savingsOpportunities: [],
        whatChanged: [],
        budgetFindings: [],
        savingsOmitted: 0,
        whatChangedOmitted: 0,
        budgetsOmitted: 0,
        underlyingMovers: [],
        underlyingBudgetOverruns: [],
      },
    };
    const requestedParams: Array<unknown> = [];
    vi.spyOn(apiClient, "get").mockImplementation(async (url: string, config?: { params?: unknown }) => {
      if (url === "/features") return { data: { ai: false } };
      if (url.endsWith("/analytics/years-available")) return { data: { years: [2026] } };
      if (url.endsWith("/categories")) return { data: [] };
      if (url.endsWith("/analytics/spending-review/candidates")) {
        requestedParams.push(config?.params);
        if ((config?.params as { mode?: string } | undefined)?.mode === "savings_only") {
          return {
            data: {
              ...SAMPLE_CANDIDATES,
              skeleton: { ...SAMPLE_CANDIDATES.skeleton!, mode: "savings_only", summary: null },
            },
          };
        }
        return { data: staleResp };
      }
      return { data: {} };
    });

    const user = userEvent.setup();
    renderTab();

    expect(await screen.findByText("No recent spending recorded for this month")).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "Review May 2026" })).toBeInTheDocument();
    await user.click(screen.getByRole("button", { name: "Review 12-month savings only" }));

    expect(await screen.findByText("HBO recurring charge")).toBeInTheDocument();
    expect(requestedParams.some((p) => (p as { mode?: string } | undefined)?.mode === "savings_only")).toBe(true);
  });

  it("renders a one-click Adjust budget link on budget overrun findings pointing to that period and category", async () => {
    const withBudget: SpendingReviewCandidatesResponse = {
      ...SAMPLE_CANDIDATES,
      skeleton: {
        ...SAMPLE_CANDIDATES.skeleton!,
        budgetFindings: [
          {
            findingId: "bud_transport_car_maintenance",
            categoryCode: "transport.car_maintenance",
            title: "Car maintenance fits an annual budget better",
            detail: "Car maintenance reached €533 against a €50 budget — €483 over (10.7× the budget).",
            severity: "warning",
            metrics: [],
            citations: [],
            txFilter: { from: "2026-05-01", to: "2026-05-31", direction: "expense", categoryCode: "transport.car_maintenance" },
            explorerLink: null,
          },
        ],
      },
    };
    vi.spyOn(apiClient, "get").mockImplementation(async (url: string) => {
      if (url === "/features") return { data: { ai: false } };
      if (url.endsWith("/analytics/years-available")) return { data: { years: [2026] } };
      if (url.endsWith("/categories")) return { data: [] };
      if (url.endsWith("/analytics/spending-review/candidates")) return { data: withBudget };
      return { data: {} };
    });

    renderTab();

    const adjustLink = await screen.findByRole("link", { name: "Adjust budget" });
    expect(adjustLink).toHaveAttribute("href", "/budgets?year=2026&month=5&category=transport.car_maintenance");
  });
});
