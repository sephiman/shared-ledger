import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { cleanup, render, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import type { AxiosResponse } from "axios";
import type { ReactElement } from "react";
import { I18nextProvider } from "react-i18next";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { apiClient } from "@/api/client";
import type { FireAiFollowup, FireAiRun, FireQuestionAnswer, FireQuestionWhatIf } from "@/api/fireAi";
import i18n from "@/i18n";
import { FireAiAnalysisTab } from "./FireAiAnalysisTab";
import { FireWhatIfBar, initialWhatIfDraft } from "./FireWhatIfBar";
import {
  applyFireQuestionWhatIf,
  describeFireQuestionWhatIf,
  describeFireUnsupported,
} from "./fireQuestionWhatIf";
import { makeProjection } from "./fireTestProjection";

function ok<T>(data: T): Promise<AxiosResponse<T>> {
  return Promise.resolve({ data } as AxiosResponse<T>);
}

function renderWithQuery(ui: ReactElement) {
  const client = new QueryClient({
    defaultOptions: {
      queries: { retry: false },
      mutations: { retry: false },
    },
  });
  return render(
    <QueryClientProvider client={client}>
      <I18nextProvider i18n={i18n}>{ui}</I18nextProvider>
    </QueryClientProvider>,
  );
}

function stubAiSettings(enabled: boolean, extraGet?: (url: string) => unknown) {
  return vi.spyOn(apiClient, "get").mockImplementation((url: string) => {
    if (url === "/features") return ok({ ai: true });
    if (url === "/households/h1/ai-suggestions/settings") {
      return ok({ enabled, model: "gpt-test", providerHost: "api.test" });
    }
    if (extraGet) return ok(extraGet(url));
    return ok([]);
  });
}

function makeCompletedRun(overrides?: Partial<FireAiRun>): FireAiRun {
  return {
    id: "run-1",
    status: "completed",
    createdAt: "2026-11-15T12:00:00Z",
    finishedAt: "2026-11-15T12:00:04Z",
    scope: {
      referenceTier: "fire",
      referenceScenarioIndex: 1,
      referenceScenarioName: "Base",
      snapshotDate: "2026-11-01",
      coveragePercent: 25.0,
      estimatedYear: 2042,
      probability: 74,
    },
    model: "gpt-test",
    promptVersion: "fire1",
    summary: "On track to hit FIRE around 2042 with 74% Monte Carlo probability.",
    evolution: "Probability improved by 3 points since the previous review.",
    strengths: [
      {
        title: "Healthy savings rate",
        detail: "37.5% savings rate gives steady accumulation.",
        citations: [{ path: "assumptionsVsReality.contributions.savingsRatePct", value: 37.5, grounded: true }],
      },
    ],
    improvements: [
      {
        title: "Raise monthly contribution",
        detail: "Adding 300/month brings the estimated year closer.",
        severity: "opportunity",
        citations: [{ path: "headline.startingValue", value: "999999.00", grounded: false }],
      },
    ],
    suggestedWhatIfs: [
      {
        title: "Save 300 more per month",
        rationale: "Pushes probability above 85%.",
        expectedImpact: "+11 probability points",
        preview: { monthlyContribution: "1300.00" },
        citations: [{ path: "headline.probabilityOfReachingTargetPct", value: 74, grounded: true }],
      },
    ],
    observations: [
      {
        claim: "Derived total spending matches settings.",
        confidence: "high",
        citations: [{ path: "assumptionsVsReality.spending.totalMonthly", value: "2000.00", grounded: true }],
      },
    ],
    dataGaps: [
      {
        topic: "12 uncovered XIRR months",
        reason: "Earlier balances predate tracked movements.",
        citations: [{ path: "assumptionsVsReality.returns.actualXirr.uncoveredMonths", value: 12, grounded: true }],
      },
    ],
    observationsCount: 1,
    dataGapsCount: 1,
    citationsTotal: 2,
    citationsUngrounded: 1,
    inputTokens: 1200,
    outputTokens: 450,
    reasoningTokens: 0,
    latencyMs: 3200,
    errorCode: null,
    followups: [],
    ...overrides,
  };
}

describe("FIRE AI features", () => {
  beforeEach(async () => {
    await i18n.changeLanguage("en");
  });

  afterEach(() => {
    cleanup();
    vi.restoreAllMocks();
  });

  it("applyFireQuestionWhatIf applies delta, percent, set overrides, and appends future events", () => {
    const base = makeProjection({
      settings: {
        ...makeProjection().settings,
        cashFlowEvents: [{ year: 2032, kind: "spending_delta", amount: "-400.00", label: "Car", template: null }],
      },
    });
    const intent: FireQuestionWhatIf = {
      monthlyContribution: { kind: "delta", value: "300" },
      monthlySpending: { kind: "percent", value: "-10" },
      targetYear: { kind: "delta", value: -4 },
      safeWithdrawalRatePct: { kind: "set", value: "3.5" },
      lumpSum: "25000",
      returnShiftPct: "-1",
      cashFlowEvents: [
        { year: 2035, kind: "lump_sum", amount: "50000", label: "Inheritance", template: "windfall" },
      ],
    };

    const draft = applyFireQuestionWhatIf(intent, base);
    expect(draft.monthlyContribution).toBe("1300.00");
    expect(draft.monthlySpending).toBe("1800.00");
    expect(draft.monthlyEssentialSpending).toBe("1080.00");
    expect(draft.targetYear).toBe("2042");
    expect(draft.safeWithdrawalRatePct).toBe("3.5");
    expect(draft.lumpSum).toBe("25000");
    expect(draft.returnShiftPct).toBe("-1");
    expect(draft.cashFlowEvents).toHaveLength(2);
    expect(draft.cashFlowEvents?.[1]).toEqual({
      year: 2035,
      kind: "lump_sum",
      amount: "50000",
      label: "Inheritance",
      template: "windfall",
    });

    const chips = describeFireQuestionWhatIf(intent, "EUR", "en", i18n.t);
    expect(chips.some((c) => c.includes("Contribution"))).toBe(true);
    expect(chips.some((c) => c.includes("Target year"))).toBe(true);
    expect(chips.some((c) => c.includes("Inheritance (2035"))).toBe(true);

    expect(describeFireUnsupported([{ kind: "tax", label: "capital gains rate" }], i18n.t)).toContain(
      "capital gains rate",
    );
  });

  it("hides FireWhatIfAsk when AI is disabled", async () => {
    stubAiSettings(false);
    const base = makeProjection();

    renderWithQuery(
      <FireWhatIfBar
        baseProjection={base}
        draft={initialWhatIfDraft(base)}
        isActive={false}
        isLoading={false}
        isOwner={true}
        currency="EUR"
        householdId="h1"
        onChange={vi.fn()}
        onReset={vi.fn()}
        onApply={vi.fn()}
      />,
    );

    await waitFor(() => expect(apiClient.get).toHaveBeenCalled());
    expect(screen.queryByRole("textbox", { name: i18n.t("fire.ai_what_if_label") })).not.toBeInTheDocument();
  });

  it("translates a natural-language What-If question into draft updates and displays chips", async () => {
    const user = userEvent.setup();
    stubAiSettings(true);
    const answer: FireQuestionAnswer = {
      understood: true,
      whatIf: {
        monthlyContribution: { kind: "delta", value: "300" },
        monthlySpending: null,
        targetYear: { kind: "set", value: 2038 },
        safeWithdrawalRatePct: null,
        lumpSum: null,
        returnShiftPct: null,
        cashFlowEvents: [],
      },
      unsupported: [{ kind: "inflation", label: "3% inflation" }],
    };
    const postSpy = vi.spyOn(apiClient, "post").mockResolvedValue({ data: answer } as AxiosResponse);
    const onChange = vi.fn();
    const base = makeProjection();

    renderWithQuery(
      <FireWhatIfBar
        baseProjection={base}
        draft={initialWhatIfDraft(base)}
        isActive={false}
        isLoading={false}
        isOwner={true}
        currency="EUR"
        householdId="h1"
        onChange={onChange}
        onReset={vi.fn()}
        onApply={vi.fn()}
      />,
    );

    const input = await screen.findByRole("textbox", { name: i18n.t("fire.ai_what_if_label") });
    expect(screen.getByText(/No balances, spending or FIRE figures leave the server.*gpt-test.*api\.test|gpt-test at api\.test\. No balances, spending or FIRE figures leave the server/)).toBeInTheDocument();

    const exampleChip = screen.getByRole("button", { name: i18n.t("fire.ai_what_if_example_1") });
    await user.click(exampleChip);
    expect(input).toHaveValue(i18n.t("fire.ai_what_if_example_1"));
    await user.clear(input);
    await user.type(input, "Save 300 more and retire in 2038 with 3% inflation");
    await user.click(screen.getByRole("button", { name: i18n.t("fire.ai_what_if_button") }));

    await waitFor(() =>
      expect(postSpy).toHaveBeenCalledWith("/households/h1/fire/question", {
        question: "Save 300 more and retire in 2038 with 3% inflation",
        language: "en",
      }),
    );
    expect(onChange).toHaveBeenCalledWith(
      expect.objectContaining({
        monthlyContribution: "1300.00",
        targetYear: "2038",
      }),
    );
    expect(await screen.findByText(/Target year 2038/)).toBeInTheDocument();
    expect(screen.getByText(/Not modeled in What-If: 3% inflation/)).toBeInTheDocument();
  });

  it("renders completed AI Plan Review, grounding badge, Preview in What-If, and follow-up Q&A", async () => {
    const user = userEvent.setup();
    const summaryText = "On track to hit FIRE around 2042 with 74% Monte Carlo probability.";
    const evolutionText = "Probability improved by 3 points since the previous review.";
    const run = makeCompletedRun({ summary: summaryText, evolution: evolutionText });
    stubAiSettings(true, (url) => {
      if (url === "/households/h1/fire/ai-analysis") return [run];
      return [];
    });
    const followup: FireAiFollowup = {
      id: "fu-1",
      question: "What is my biggest single risk?",
      answer: "Lower sequence returns in the first 5 years.",
      citations: [{ path: "headline.probabilityOfReachingTargetPct", value: 74, grounded: true }],
      citationsTotal: 1,
      citationsUngrounded: 0,
      inputTokens: 400,
      outputTokens: 120,
      latencyMs: 900,
      createdAt: "2026-11-15T12:01:00Z",
    };
    const postSpy = vi.spyOn(apiClient, "post").mockResolvedValue({ data: followup } as AxiosResponse);
    const onPreviewWhatIf = vi.fn();

    renderWithQuery(
      <FireAiAnalysisTab householdId="h1" projection={makeProjection()} onPreviewWhatIf={onPreviewWhatIf} />,
    );

    expect(await screen.findByText(summaryText)).toBeInTheDocument();
    expect(screen.getByText(/pre-computed FIRE summary metrics.*gpt-test at api\.test/)).toBeInTheDocument();
    expect(screen.getByText(evolutionText)).toBeInTheDocument();
    expect(screen.getByText("1 of 2 citations off")).toBeInTheDocument();
    expect(screen.getByText("Healthy savings rate")).toBeInTheDocument();
    expect(screen.getByText("Raise monthly contribution")).toBeInTheDocument();
    expect(screen.getByText(/Contribution.*1,300/)).toBeInTheDocument();

    await user.click(screen.getByRole("button", { name: i18n.t("fire.ai.preview_what_if") }));
    expect(onPreviewWhatIf).toHaveBeenCalledWith({ monthlyContribution: "1300.00" });

    const followupInput = screen.getByPlaceholderText(i18n.t("fire.ai.followup_placeholder"));
    await user.type(followupInput, "What is my biggest single risk?");
    await user.click(screen.getByRole("button", { name: i18n.t("fire.ai.followup_ask") }));

    await waitFor(() =>
      expect(postSpy).toHaveBeenCalledWith("/households/h1/fire/ai-analysis/run-1/followups", {
        question: "What is my biggest single risk?",
      }),
    );
  });
});
