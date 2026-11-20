import { useMemo, useState } from "react";
import { Link, useNavigate } from "react-router-dom";
import { useTranslation } from "react-i18next";
import { useActiveHousehold } from "@/auth/AuthContext";
import { useYearsAvailable } from "@/api/analytics";
import { useAiSuggestionsOn } from "@/api/aiSuggestions";
import {
  ANALYTICS_AI_FOLLOWUP_MAX_CHARS,
  useAnalyticsAiPayloadPreview,
  useAnalyticsAiRuns,
  useAskAnalyticsAiFollowup,
  useDeleteAnalyticsAiRun,
  useDeleteSpendingReviewDecision,
  useRecordSpendingReviewDecision,
  useSpendingReviewCandidates,
  useStartAnalyticsAiReview,
  type AnalyticsAiRun,
  type AnalyticsBudgetFinding,
  type AnalyticsChangeFinding,
  type AnalyticsSavingsFinding,
  type RecordSpendingReviewDecisionRequest,
  type SinceLastMonthItem,
  type UpcomingCharge,
  type VerifiedMetric,
} from "@/api/analyticsAi";
import { useCategories, type Category } from "@/api/catalog";
import { apiErrorMessage } from "@/api/client";
import type { ProjectionScenario } from "@/api/questions";
import { StaleSpendingNotice } from "@/components/StaleSpendingNotice";
import { AiCitations } from "@/components/ai/AiCitations";
import { AiFollowupSection } from "@/components/ai/AiFollowupSection";
import { AiRunCardHeader, AiScopeChip } from "@/components/ai/AiRunCardHeader";
import { AiSeverityBadge } from "@/components/ai/AiSeverityBadge";
import { Badge, Button, Card, CardBody, FieldError, Label, Select } from "@/components/ui/primitives";
import { categoryLabelByCode } from "@/lib/categoryLabel";
import { cn } from "@/lib/cn";
import { formatDate, formatDayMonthYear, monthName } from "@/lib/dates";
import { formatWholeMoney } from "@/lib/money";
import {
  explorerUrlFromLink,
  formatReviewCitationLabel,
  formatReviewCitationValue,
  formatReviewMetricLabel,
  formatVerifiedMetricValue,
  transactionsUrlFromFilter,
} from "./reviewLinks";
import {
  MergedVariantsToggle,
  SavingsCandidatesPanel,
  type UnderlyingDataGroup,
} from "./SavingsCandidatesPanel";

function lastCompleteMonth(now: Date): { year: number; month: number } {
  const m = now.getMonth();
  if (m === 0) return { year: now.getFullYear() - 1, month: 12 };
  return { year: now.getFullYear(), month: m };
}

function parseYearMonth(ym: string | null | undefined): { year: number; month: number } | null {
  if (!ym) return null;
  const parts = ym.split("-");
  if (parts.length !== 2) return null;
  const y = Number(parts[0]);
  const m = Number(parts[1]);
  if (!Number.isInteger(y) || !Number.isInteger(m) || m < 1 || m > 12) return null;
  return { year: y, month: m };
}

/** Analytics "Spending review" tab: shows the deterministic report skeleton (Summary, Savings,
 *  What changed, Budgets) directly with or without AI, offers last-month / savings-only actions
 *  for empty or stale months, upgrades prose in-place when an AI run is triggered, and keeps
 *  full unfiltered tables in a collapsible "Underlying data" disclosure beneath the report. */
export function SpendingReviewTab() {
  const { t, i18n } = useTranslation();
  const locale = i18n.language;
  const household = useActiveHousehold();
  const navigate = useNavigate();
  const now = useMemo(() => new Date(), []);
  const currentYear = now.getFullYear();
  const currentMonth = now.getMonth() + 1;
  const defaultPeriod = useMemo(() => lastCompleteMonth(now), [now]);

  const [year, setYear] = useState<number>(defaultPeriod.year);
  const [month, setMonth] = useState<number>(defaultPeriod.month);
  const [savingsOnlyMode, setSavingsOnlyMode] = useState(false);
  const [openIds, setOpenIds] = useState<Set<string>>(new Set());
  const [showPayload, setShowPayload] = useState(false);
  const [copiedPayload, setCopiedPayload] = useState(false);
  const [underlyingOpen, setUnderlyingOpen] = useState(false);
  const [underlyingGroup, setUnderlyingGroup] = useState<UnderlyingDataGroup | null>(null);
  const isOwner = household.role === "owner";

  const { data: yearsData } = useYearsAvailable(household.householdId);
  const { data: categories = [] } = useCategories(household.householdId);
  const yearOptions = useMemo(() => {
    const set = new Set<number>(yearsData?.years ?? []);
    set.add(defaultPeriod.year);
    set.add(currentYear);
    return Array.from(set).sort((a, b) => b - a);
  }, [yearsData, defaultPeriod.year, currentYear]);

  const maxMonthForYear = year === currentYear ? currentMonth : 12;
  const effectiveMonth = Math.min(month, maxMonthForYear);
  const requestMode: "full" | "savings_only" = savingsOnlyMode ? "savings_only" : "full";

  const candidatesQuery = useSpendingReviewCandidates(
    household.householdId,
    year,
    effectiveMonth,
    requestMode,
  );
  const period = candidatesQuery.data?.period;
  const candidates = candidatesQuery.data?.candidates;
  const decisions = candidatesQuery.data?.decisions ?? [];
  const skeleton = candidatesQuery.data?.skeleton ?? null;
  const recordDecision = useRecordSpendingReviewDecision(household.householdId);
  const deleteDecision = useDeleteSpendingReviewDecision(household.householdId);
  const decisionBusy = recordDecision.isPending || deleteDecision.isPending;

  // Three-level AI gate: when AI is absent or household toggle is off, no AI queries or UI appear.
  const ai = useAiSuggestionsOn(household.householdId);
  const runsQuery = useAnalyticsAiRuns(household.householdId, Boolean(ai));
  const payloadPreview = useAnalyticsAiPayloadPreview(
    household.householdId,
    year,
    effectiveMonth,
    Boolean(ai) && isOwner && showPayload,
    requestMode,
  );
  const start = useStartAnalyticsAiReview(household.householdId);
  const del = useDeleteAnalyticsAiRun(household.householdId);

  const storedRuns = runsQuery.data ?? [];
  const [latestRun, ...olderRuns] = storedRuns;
  const isRunning = storedRuns.some((r) => r.status === "running");

  const isEmptyOrStale = period?.reviewBlockedReason === "empty_or_stale" && !savingsOnlyMode;
  const lastDataYm = parseYearMonth(period?.lastMonthWithData);
  const lastDataLabel = lastDataYm
    ? `${monthName(lastDataYm.month, locale, "long")} ${lastDataYm.year}`
    : null;

  const handleSimulate = (scenario: ProjectionScenario) => {
    navigate("/analytics/money-flow", { state: { initialScenario: scenario } });
  };

  const openUnderlyingAt = (group: UnderlyingDataGroup) => {
    setUnderlyingOpen(true);
    setUnderlyingGroup(group);
  };

  const canStartAi =
    Boolean(ai) &&
    Boolean(period) &&
    (savingsOnlyMode || (period?.paceReviewEligible === true && period?.reviewBlockedReason == null)) &&
    !start.isPending &&
    !isRunning;

  // Avoid visually duplicating the exact same selected period when latestRun already covers it.
  const latestCoversCurrentSelection =
    Boolean(ai) &&
    latestRun?.status === "completed" &&
    latestRun.scope.periodYear === year &&
    latestRun.scope.periodMonth === effectiveMonth &&
    (savingsOnlyMode
      ? latestRun.scope.reviewKind === "savings_only"
      : latestRun.scope.reviewKind !== "savings_only");

  const formatCiteLabel = (c: { path: string; value: unknown; grounded: boolean; context?: string | null }) =>
    formatReviewCitationLabel(c, t);
  const formatCiteValue = (c: { path: string; value: unknown; grounded: boolean; context?: string | null }) =>
    formatReviewCitationValue(c, household.currency, locale);

  return (
    <div className="space-y-4">
      <Card>
        <CardBody className="space-y-3">
          <div className="flex flex-wrap items-start justify-between gap-3">
            <div>
              <h2 className="text-base font-semibold">{t("analytics.review.title")}</h2>
              <p className="mt-1 text-sm text-gray-600 dark:text-gray-300">
                {ai ? t("analytics.review.subtitle_ai") : t("analytics.review.subtitle_local")}
              </p>
            </div>
            <div className="flex flex-wrap items-end gap-2">
              <div>
                <Label className="text-xs">{t("analytics.select_year")}</Label>
                <Select
                  aria-label={t("analytics.select_year")}
                  value={year}
                  onChange={(e) => {
                    const nextYear = Number(e.target.value);
                    setYear(nextYear);
                    setSavingsOnlyMode(false);
                    if (nextYear === currentYear && month > currentMonth) setMonth(currentMonth);
                  }}
                >
                  {yearOptions.map((y) => (
                    <option key={y} value={y}>
                      {y}
                    </option>
                  ))}
                </Select>
              </div>
              <div>
                <Label className="text-xs">{t("analytics.select_month")}</Label>
                <Select
                  aria-label={t("analytics.select_month")}
                  value={effectiveMonth}
                  onChange={(e) => {
                    setMonth(Number(e.target.value));
                    setSavingsOnlyMode(false);
                  }}
                >
                  {Array.from({ length: maxMonthForYear }, (_, i) => i + 1).map((m) => (
                    <option key={m} value={m}>
                      {monthName(m, locale, "long")}
                      {year === currentYear && m === currentMonth ? ` (${t("analytics.review.pace_option_tag")})` : ""}
                    </option>
                  ))}
                </Select>
              </div>
            </div>
          </div>

          {period && (
            <div className="flex flex-wrap items-center gap-2 text-xs">
              <AiScopeChip
                label={
                  savingsOnlyMode
                    ? t("analytics.review.scope_savings_only")
                    : period.reviewKind === "pace_review"
                      ? t("analytics.review.scope_pace", {
                          month: monthName(period.month, locale, "long"),
                          year: period.year,
                          elapsed: period.daysElapsed,
                          total: period.daysInMonth,
                        })
                      : t("analytics.review.scope_complete", {
                          month: monthName(period.month, locale, "long"),
                          year: period.year,
                        })
                }
              />
              {period.reviewKind === "pace_review" && !period.paceReviewEligible && !savingsOnlyMode && (
                <Badge tone="amber">
                  {t("analytics.review.pace_too_early", {
                    minDays: period.minDaysElapsed,
                    elapsed: period.daysElapsed,
                  })}
                </Badge>
              )}
            </div>
          )}

          {period?.staleSpendingSince && !isEmptyOrStale && (
            <StaleSpendingNotice
              since={period.staleSpendingSince}
              messageKey="analytics.review.stale_spending_notice"
            />
          )}

          {isEmptyOrStale && (
            <div
              role="status"
              className="rounded-md border border-amber-300 bg-amber-50/70 p-3.5 text-sm dark:border-amber-900/50 dark:bg-amber-950/25"
            >
              <p className="font-semibold text-amber-900 dark:text-amber-200">
                {t("analytics.review.empty_or_stale_title")}
              </p>
              <p className="mt-1 text-xs text-amber-800 dark:text-amber-300">
                {t("analytics.review.empty_or_stale_body")}
              </p>
              <div className="mt-3 flex flex-wrap gap-2">
                {lastDataYm && lastDataLabel && (
                  <Button
                    variant="primary"
                    className="px-3 py-1.5 text-xs"
                    onClick={() => {
                      setYear(lastDataYm.year);
                      setMonth(lastDataYm.month);
                      setSavingsOnlyMode(false);
                    }}
                  >
                    {t("analytics.review.review_last_month_with_data", { period: lastDataLabel })}
                  </Button>
                )}
                <Button
                  variant="secondary"
                  className="px-3 py-1.5 text-xs"
                  onClick={() => setSavingsOnlyMode(true)}
                >
                  {t("analytics.review.review_savings_only")}
                </Button>
              </div>
            </div>
          )}

          {ai && (
            <>
              <div className="flex flex-wrap items-center gap-3">
                <Button
                  disabled={!canStartAi}
                  onClick={() =>
                    start.mutate(
                      savingsOnlyMode
                        ? { year, month: effectiveMonth, mode: "savings_only" }
                        : { year, month: effectiveMonth },
                    )
                  }
                >
                  {start.isPending || isRunning
                    ? t("analytics.review.starting")
                    : savingsOnlyMode
                      ? t("analytics.review.analyze_savings_only")
                      : period?.reviewKind === "pace_review"
                        ? t("analytics.review.analyze_pace")
                        : t("analytics.review.analyze")}
                </Button>
              </div>
              <p className="rounded-md border border-border bg-sunken px-3 py-2 text-xs text-gray-600 dark:text-gray-300">
                {t("analytics.review.info", { model: ai.model, host: ai.providerHost })}
              </p>
              {isOwner && !isEmptyOrStale && (
                <div className="space-y-2">
                  <button
                    type="button"
                    aria-expanded={showPayload}
                    onClick={() => setShowPayload((v) => !v)}
                    className="text-xs font-medium text-sky-700 hover:underline dark:text-sky-300"
                  >
                    {showPayload ? t("analytics.review.hide_payload") : t("analytics.review.show_payload")}
                  </button>
                  {showPayload && (
                    <div className="rounded-md border border-border bg-sunken p-3 space-y-2">
                      {payloadPreview.isLoading && (
                        <p className="text-xs text-gray-500 dark:text-gray-400">{t("common.loading")}</p>
                      )}
                      {payloadPreview.error && (
                        <FieldError message={apiErrorMessage(payloadPreview.error, t)} />
                      )}
                      {payloadPreview.data && (
                        <>
                          <div className="flex flex-wrap items-center justify-between gap-2 text-xs text-gray-600 dark:text-gray-300">
                            <span>
                              {t("analytics.review.payload_meta", {
                                bytes: payloadPreview.data.byteSize,
                                model: payloadPreview.data.model,
                                host: payloadPreview.data.providerHost,
                              })}
                            </span>
                            <Button
                              variant="secondary"
                              className="px-2 py-1 text-xs"
                              onClick={() => {
                                void navigator.clipboard?.writeText(payloadPreview.data.payloadJson);
                                setCopiedPayload(true);
                                window.setTimeout(() => setCopiedPayload(false), 1800);
                              }}
                            >
                              {copiedPayload ? t("analytics.review.copied") : t("analytics.review.copy_payload")}
                            </Button>
                          </div>
                          <pre
                            data-testid="payload-preview-json"
                            className="max-h-72 overflow-auto rounded border border-border bg-surface p-2 text-[11px] leading-snug text-gray-800 dark:text-gray-200 whitespace-pre-wrap break-all"
                          >
                            {payloadPreview.data.payloadJson}
                          </pre>
                        </>
                      )}
                    </div>
                  )}
                </div>
              )}
              <FieldError message={start.error ? apiErrorMessage(start.error, t) : null} />
            </>
          )}
        </CardBody>
      </Card>

      {ai && latestRun && (
        <ReviewRunCard
          householdId={household.householdId}
          currency={household.currency}
          run={latestRun}
          sinceLastMonth={latestCoversCurrentSelection ? (skeleton?.sinceLastMonth ?? []) : []}
          upcomingCharges={latestCoversCurrentSelection ? (skeleton?.upcomingCharges ?? []) : []}
          defaultOpen
          onDelete={del.mutate}
          onSimulate={handleSimulate}
          onOpenUnderlying={openUnderlyingAt}
          onRecordDecision={(req) => recordDecision.mutate(req)}
          onUndoDecision={(id) => deleteDecision.mutate(id)}
          decisionBusy={decisionBusy}
        />
      )}

      {!latestCoversCurrentSelection && skeleton && skeleton.mode !== "blocked" && (
        <Card>
          <CardBody className="space-y-4">
            <div className="flex flex-wrap items-center justify-between gap-2">
              <h3 className="text-sm font-semibold text-gray-900 dark:text-gray-100">
                {skeleton.mode === "savings_only"
                  ? t("analytics.review.scope_savings_only")
                  : `${monthName(effectiveMonth, locale, "long")} ${year}`}
              </h3>
              <Badge tone="neutral">{t("analytics.review.skeleton_badge")}</Badge>
            </div>
            <ReportSections
              summary={skeleton.summary}
              sinceLastMonth={skeleton.sinceLastMonth ?? []}
              savingsOpportunities={skeleton.savingsOpportunities}
              whatChanged={skeleton.whatChanged}
              budgetFindings={skeleton.budgetFindings}
              upcomingCharges={skeleton.upcomingCharges ?? []}
              savingsOmitted={skeleton.savingsOmitted}
              whatChangedOmitted={skeleton.whatChangedOmitted}
              budgetsOmitted={skeleton.budgetsOmitted}
              currency={household.currency}
              locale={locale}
              categories={categories}
              onSimulate={handleSimulate}
              onOpenUnderlying={openUnderlyingAt}
              onRecordDecision={(req) => recordDecision.mutate(req)}
              onUndoDecision={(id) => deleteDecision.mutate(id)}
              decisionBusy={decisionBusy}
              formatCiteLabel={formatCiteLabel}
              formatCiteValue={formatCiteValue}
            />
          </CardBody>
        </Card>
      )}

      {ai && olderRuns.length > 0 && (
        <div className="space-y-2">
          <h3 className="text-sm font-semibold text-gray-500 dark:text-gray-400">
            {t("analytics.review.history_title", { count: olderRuns.length })}
          </h3>
          {olderRuns.map((r) => (
            <ReviewRunCard
              key={r.id}
              householdId={household.householdId}
              currency={household.currency}
              run={r}
              defaultOpen={openIds.has(r.id)}
              onToggle={() =>
                setOpenIds((prev) => {
                  const next = new Set(prev);
                  if (next.has(r.id)) next.delete(r.id);
                  else next.add(r.id);
                  return next;
                })
              }
              onDelete={del.mutate}
              onSimulate={handleSimulate}
              onOpenUnderlying={openUnderlyingAt}
            />
          ))}
        </div>
      )}

      {candidatesQuery.isLoading && (
        <p className="text-sm text-gray-500 dark:text-gray-400">{t("common.loading")}</p>
      )}

      {period && candidates && (
        <SavingsCandidatesPanel
          householdId={household.householdId}
          currency={household.currency}
          period={period}
          candidates={candidates}
          decisions={decisions}
          onUndoDecision={(id) => deleteDecision.mutate(id)}
          decisionBusy={decisionBusy}
          underlyingMovers={skeleton?.underlyingMovers ?? []}
          underlyingBudgetOverruns={skeleton?.underlyingBudgetOverruns ?? []}
          open={underlyingOpen}
          focusGroup={underlyingGroup}
          onToggle={() => setUnderlyingOpen((v) => !v)}
        />
      )}
    </div>
  );
}

function ReportSections({
  summary,
  sinceLastMonth = [],
  savingsOpportunities,
  whatChanged,
  budgetFindings,
  upcomingCharges = [],
  savingsOmitted = 0,
  whatChangedOmitted = 0,
  budgetsOmitted = 0,
  currency,
  locale,
  categories,
  onSimulate,
  onOpenUnderlying,
  onRecordDecision,
  onUndoDecision,
  decisionBusy = false,
  formatCiteLabel,
  formatCiteValue,
}: {
  summary: string | null;
  sinceLastMonth?: SinceLastMonthItem[];
  savingsOpportunities: AnalyticsSavingsFinding[];
  whatChanged: AnalyticsChangeFinding[];
  budgetFindings: AnalyticsBudgetFinding[];
  upcomingCharges?: UpcomingCharge[];
  savingsOmitted?: number;
  whatChangedOmitted?: number;
  budgetsOmitted?: number;
  currency: string;
  locale: string;
  categories: Category[];
  onSimulate: (scenario: ProjectionScenario) => void;
  onOpenUnderlying: (group: UnderlyingDataGroup) => void;
  onRecordDecision?: (req: RecordSpendingReviewDecisionRequest) => void;
  onUndoDecision?: (decisionId: string) => void;
  decisionBusy?: boolean;
  formatCiteLabel: (c: { path: string; value: unknown; grounded: boolean; context?: string | null }) => string;
  formatCiteValue: (c: { path: string; value: unknown; grounded: boolean; context?: string | null }) => string;
}) {
  const { t } = useTranslation();
  const hasAny =
    Boolean(summary) ||
    sinceLastMonth.length > 0 ||
    savingsOpportunities.length > 0 ||
    whatChanged.length > 0 ||
    budgetFindings.length > 0 ||
    upcomingCharges.length > 0;

  if (!hasAny) {
    return (
      <p className="py-3 text-center text-sm text-gray-500 dark:text-gray-400">
        {t("analytics.review.no_candidates")}
      </p>
    );
  }

  return (
    <>
      {sinceLastMonth.length > 0 && (
        <section
          aria-label={t("analytics.review.since_last_month_title")}
          className="rounded-lg border border-sky-200 bg-sky-50/40 p-4 dark:border-sky-900/40 dark:bg-sky-950/20"
        >
          <h4 className="text-xs font-semibold uppercase tracking-wider text-sky-800 dark:text-sky-300">
            {t("analytics.review.since_last_month_title")}
          </h4>
          <ul className="mt-2.5 space-y-2">
            {sinceLastMonth.map((item) => {
              const badgeKey =
                item.changeType === "stopped_recurring"
                  ? "since_last_month_stopped"
                  : item.changeType === "new_recurring"
                    ? "since_last_month_new"
                    : item.changeType === "decision_resurfaced"
                      ? "since_last_month_resurfaced"
                      : item.changeType === "decision_expired"
                        ? "since_last_month_expired"
                        : "since_last_month_changed";
              const tone =
                item.changeType === "stopped_recurring"
                  ? "green"
                  : item.changeType === "decision_resurfaced" || item.changeType === "decision_expired"
                    ? "amber"
                    : "sky";
              return (
                <li
                  key={item.id}
                  className="flex flex-wrap items-start justify-between gap-2 rounded-md border border-border bg-white/90 px-3 py-2 text-sm dark:bg-surface"
                >
                  <div className="space-y-0.5">
                    <div className="flex flex-wrap items-center gap-2">
                      <Badge tone={tone}>{t(`analytics.review.${badgeKey}`)}</Badge>
                      <span className="font-medium text-gray-900 dark:text-gray-100">{item.title}</span>
                    </div>
                    <p className="text-xs text-gray-600 dark:text-gray-300">{item.detail}</p>
                  </div>
                  {item.txFilter && (
                    <Link
                      to={transactionsUrlFromFilter(item.txFilter)}
                      className="text-xs text-sky-700 hover:underline dark:text-sky-300"
                    >
                      {t("analytics.review.see_transactions")}
                    </Link>
                  )}
                </li>
              );
            })}
          </ul>
        </section>
      )}

      {summary && (
        <div className="rounded-md border border-sky-200 bg-sky-50/60 p-3.5 text-sm leading-relaxed text-gray-800 dark:border-sky-900/40 dark:bg-sky-950/25 dark:text-gray-200">
          <p className="text-xs font-semibold uppercase tracking-wider text-sky-800 dark:text-sky-300">
            {t("analytics.review.summary_title")}
          </p>
          <p className="mt-1">{summary}</p>
        </div>
      )}

      {savingsOpportunities.length > 0 && (
        <section className="rounded-lg border border-emerald-200 bg-emerald-50/50 p-4 dark:border-emerald-900/40 dark:bg-emerald-950/20">
          <h4 className="text-xs font-semibold uppercase tracking-wider text-emerald-800 dark:text-emerald-300">
            {t("analytics.review.savings_title")}
          </h4>
          <ul className="mt-3 space-y-3">
            {savingsOpportunities.map((item, i) => (
              <SavingsFindingCard
                key={`${item.findingId || item.candidateId}-${i}`}
                item={item}
                currency={currency}
                locale={locale}
                categoryLabel={item.categoryCode ? categoryLabelByCode(item.categoryCode, categories, t) : null}
                onSimulate={onSimulate}
                onRecordDecision={onRecordDecision}
                onUndoDecision={onUndoDecision}
                decisionBusy={decisionBusy}
                formatCiteLabel={formatCiteLabel}
                formatCiteValue={formatCiteValue}
              />
            ))}
          </ul>
          {savingsOmitted > 0 && (
            <button
              type="button"
              onClick={() => onOpenUnderlying("savings")}
              className="mt-3 text-xs font-medium text-emerald-800 hover:underline dark:text-emerald-300"
            >
              {t("analytics.review.and_n_more", { count: savingsOmitted })}
            </button>
          )}
        </section>
      )}

      {whatChanged.length > 0 && (
        <section className="rounded-lg border border-amber-200 bg-amber-50/50 p-4 dark:border-amber-900/40 dark:bg-amber-950/20">
          <h4 className="text-xs font-semibold uppercase tracking-wider text-amber-800 dark:text-amber-300">
            {t("analytics.review.changed_title")}
          </h4>
          <ul className="mt-3 space-y-3">
            {whatChanged.map((item, i) => (
              <ChangeFindingCard
                key={`${item.findingId || item.categoryCode || i}-${i}`}
                item={item}
                currency={currency}
                locale={locale}
                onUndoDecision={onUndoDecision}
                decisionBusy={decisionBusy}
                formatCiteLabel={formatCiteLabel}
                formatCiteValue={formatCiteValue}
              />
            ))}
          </ul>
          {whatChangedOmitted > 0 && (
            <button
              type="button"
              onClick={() => onOpenUnderlying("changes")}
              className="mt-3 text-xs font-medium text-amber-800 hover:underline dark:text-amber-300"
            >
              {t("analytics.review.and_n_more", { count: whatChangedOmitted })}
            </button>
          )}
        </section>
      )}

      {budgetFindings.length > 0 && (
        <section className="rounded-lg border border-indigo-200 bg-indigo-50/50 p-4 dark:border-indigo-900/40 dark:bg-indigo-950/20">
          <h4 className="text-xs font-semibold uppercase tracking-wider text-indigo-800 dark:text-indigo-300">
            {t("analytics.review.budgets_title")}
          </h4>
          <ul className="mt-3 space-y-2.5">
            {budgetFindings.map((item, i) => (
              <BudgetFindingCard
                key={`${item.findingId || item.categoryCode || i}-${i}`}
                item={item}
                currency={currency}
                locale={locale}
                formatCiteLabel={formatCiteLabel}
                formatCiteValue={formatCiteValue}
              />
            ))}
          </ul>
          {budgetsOmitted > 0 && (
            <button
              type="button"
              onClick={() => onOpenUnderlying("budgets")}
              className="mt-3 text-xs font-medium text-indigo-800 hover:underline dark:text-indigo-300"
            >
              {t("analytics.review.and_n_more", { count: budgetsOmitted })}
            </button>
          )}
        </section>
      )}

      {upcomingCharges.length > 0 && (
        <section
          aria-label={t("analytics.review.upcoming_title")}
          className="rounded-lg border border-violet-200 bg-violet-50/40 p-4 dark:border-violet-900/40 dark:bg-violet-950/20"
        >
          <h4 className="text-xs font-semibold uppercase tracking-wider text-violet-800 dark:text-violet-300">
            {t("analytics.review.upcoming_title")}
          </h4>
          <ul className="mt-2.5 space-y-2">
            {upcomingCharges.map((u) => (
              <li
                key={u.id}
                className="flex flex-wrap items-start justify-between gap-2 rounded-md border border-border bg-white/90 px-3 py-2.5 text-sm dark:bg-surface"
              >
                <div className="space-y-0.5">
                  <div className="flex flex-wrap items-center gap-2">
                    <span className="font-semibold text-gray-900 dark:text-gray-100">{u.merchant}</span>
                    <Badge tone="neutral">{t(`analytics.review.cadence_${u.cadence}`)}</Badge>
                    {u.kept && <Badge tone="green">{t("analytics.review.upcoming_kept_badge")}</Badge>}
                    <span className="text-xs text-gray-500 dark:text-gray-400">
                      {categoryLabelByCode(u.categoryCode, categories, t)}
                    </span>
                  </div>
                  <p className="text-xs text-gray-600 dark:text-gray-300">
                    {t("analytics.review.upcoming_window", {
                      from: formatDayMonthYear(u.expectedFrom, locale),
                      to: formatDayMonthYear(u.expectedTo, locale),
                    })}
                    {" · "}
                    <span className="font-semibold tabular-nums">
                      {formatWholeMoney(u.typicalAmount, currency, locale)}
                    </span>
                    {u.compareBeforeRenewal && (
                      <span className="ml-1.5 text-amber-700 dark:text-amber-300">
                        · {t("analytics.review.upcoming_compare_note")}
                      </span>
                    )}
                  </p>
                </div>
                <div className="inline-flex items-center gap-2 text-xs">
                  <Link
                    to={transactionsUrlFromFilter(u.txFilter)}
                    className="text-sky-700 hover:underline dark:text-sky-300"
                  >
                    {t("analytics.review.see_transactions")}
                  </Link>
                  <Link
                    to={explorerUrlFromLink(u.explorerLink)}
                    className="text-gray-600 hover:underline dark:text-gray-300"
                  >
                    {t("analytics.review.see_explorer")}
                  </Link>
                </div>
              </li>
            ))}
          </ul>
        </section>
      )}
    </>
  );
}

function ReviewRunCard({
  householdId,
  currency,
  run,
  sinceLastMonth = [],
  upcomingCharges = [],
  defaultOpen,
  onToggle,
  onDelete,
  onSimulate,
  onOpenUnderlying,
  onRecordDecision,
  onUndoDecision,
  decisionBusy = false,
}: {
  householdId: string;
  currency: string;
  run: AnalyticsAiRun;
  sinceLastMonth?: SinceLastMonthItem[];
  upcomingCharges?: UpcomingCharge[];
  defaultOpen: boolean;
  onToggle?: () => void;
  onDelete: (id: string) => void;
  onSimulate: (scenario: ProjectionScenario) => void;
  onOpenUnderlying: (group: UnderlyingDataGroup) => void;
  onRecordDecision?: (req: RecordSpendingReviewDecisionRequest) => void;
  onUndoDecision?: (decisionId: string) => void;
  decisionBusy?: boolean;
}) {
  const { t, i18n } = useTranslation();
  const locale = i18n.language;
  const { data: categories = [] } = useCategories(householdId);
  const [open, setOpen] = useState(defaultOpen);
  const [question, setQuestion] = useState("");
  const ask = useAskAnalyticsAiFollowup(householdId);

  const toggle = () => {
    setOpen((v) => !v);
    onToggle?.();
  };

  const periodLabel =
    run.scope.reviewKind === "savings_only"
      ? t("analytics.review.scope_savings_only")
      : `${monthName(run.scope.periodMonth, locale, "long")} ${run.scope.periodYear}`;
  const ungrounded = run.citationsUngrounded ?? 0;

  const formatCiteLabel = (c: { path: string; value: unknown; grounded: boolean; context?: string | null }) =>
    formatReviewCitationLabel(c, t);
  const formatCiteValue = (c: { path: string; value: unknown; grounded: boolean; context?: string | null }) =>
    formatReviewCitationValue(c, currency, locale);

  const buildMarkdown = () => {
    const lines: string[] = [
      `# ${t("analytics.review.title")} — ${periodLabel} (${formatDate(run.createdAt, locale, "d MMM yyyy HH:mm")})`,
      `**${t("analytics.review.scope_model")}:** ${run.model}`,
      "",
    ];
    if (run.summary) lines.push(`## ${t("analytics.review.summary_title")}`, run.summary, "");
    if (run.savingsOpportunities.length) {
      lines.push(`## ${t("analytics.review.savings_title")}`);
      for (const s of run.savingsOpportunities) {
        const titleHasName =
          s.localName && s.title.toLowerCase().includes(s.localName.toLowerCase());
        const who = s.localName && !titleHasName ? ` (${s.localName})` : "";
        const pot = s.potentialMonthlySaving
          ? ` — ${formatWholeMoney(s.potentialMonthlySaving, currency, locale)}/mo`
          : "";
        lines.push(`- **${s.title}${who}**${pot}: ${s.detail}`);
      }
      lines.push("");
    }
    if (run.whatChanged.length) {
      lines.push(`## ${t("analytics.review.changed_title")}`);
      for (const c of run.whatChanged) lines.push(`- **${c.title}**: ${c.detail}`);
      lines.push("");
    }
    if (run.budgetFindings.length) {
      lines.push(`## ${t("analytics.review.budgets_title")}`);
      for (const b of run.budgetFindings) lines.push(`- **${b.title}**: ${b.detail}`);
      lines.push("");
    }
    if (run.dataGaps.length) {
      lines.push(`## ${t("analytics.review.data_gaps_title")}`);
      for (const g of run.dataGaps) lines.push(`- **${g.topic}**: ${g.reason}`);
      lines.push("");
    }
    return lines.join("\n");
  };

  const starters = [
    t("analytics.review.followup_starter_1"),
    t("analytics.review.followup_starter_2"),
    t("analytics.review.followup_starter_3"),
  ];

  const chips = (
    <>
      <AiScopeChip label={periodLabel} />
      {run.scope.reviewKind === "pace_review" && (
        <AiScopeChip
          label={t("analytics.review.scope_pace_short", {
            elapsed: run.scope.daysElapsed,
            total: run.scope.daysInMonth,
          })}
        />
      )}
      <AiScopeChip label={run.model} />
    </>
  );

  return (
    <Card>
      <CardBody className="space-y-4">
        <AiRunCardHeader
          createdAt={run.createdAt}
          status={run.status}
          model={run.model}
          citationsUngrounded={run.citationsUngrounded}
          groundedOkLabel={t("analytics.review.grounded_ok")}
          groundedOffLabel={t("analytics.review.grounded_off", {
            off: ungrounded,
            total: run.citationsTotal ?? 0,
          })}
          copyReportLabel={t("analytics.review.copy_report")}
          copiedLabel={t("analytics.review.copied")}
          confirmDeleteMessage={t("analytics.review.confirm_delete")}
          chips={chips}
          onToggle={toggle}
          onBuildMarkdown={buildMarkdown}
          onDelete={() => onDelete(run.id)}
        />

        {run.status === "running" && (
          <p role="status" className="text-sm text-gray-500 dark:text-gray-400">
            {t("analytics.review.running")}
          </p>
        )}
        {run.status === "failed" && (
          <p className="text-sm text-red-600 dark:text-red-400">
            {t("analytics.review.failed")}:{" "}
            {t(`analytics.review.error_${run.errorCode ?? "INTERNAL_ERROR"}`, { defaultValue: run.errorCode ?? "" })}
          </p>
        )}
        {run.status === "interrupted" && (
          <p className="text-sm text-gray-500 dark:text-gray-400">{t("analytics.review.interrupted")}</p>
        )}

        {open && run.status === "completed" && (
          <>
            {run.scope.staleSpendingSince && run.scope.reviewKind !== "savings_only" && (
              <StaleSpendingNotice
                since={run.scope.staleSpendingSince}
                messageKey="analytics.review.stale_spending_notice"
              />
            )}

            <ReportSections
              summary={run.summary}
              sinceLastMonth={sinceLastMonth}
              savingsOpportunities={run.savingsOpportunities}
              whatChanged={run.whatChanged}
              budgetFindings={run.scope.hasBudgets ? run.budgetFindings : []}
              upcomingCharges={upcomingCharges}
              savingsOmitted={run.savingsOmitted ?? 0}
              whatChangedOmitted={run.whatChangedOmitted ?? 0}
              budgetsOmitted={run.budgetsOmitted ?? 0}
              currency={currency}
              locale={locale}
              categories={categories}
              onSimulate={onSimulate}
              onOpenUnderlying={onOpenUnderlying}
              onRecordDecision={onRecordDecision}
              onUndoDecision={onUndoDecision}
              decisionBusy={decisionBusy}
              formatCiteLabel={formatCiteLabel}
              formatCiteValue={formatCiteValue}
            />

            {run.dataGaps.length > 0 && (
              <section className="rounded-md border border-border bg-gray-50 p-3 dark:bg-sunken">
                <p className="text-xs font-semibold uppercase tracking-wide text-gray-500 dark:text-gray-400">
                  {t("analytics.review.data_gaps_title")}
                </p>
                <ul className="mt-2 space-y-2">
                  {run.dataGaps.map((g, i) => (
                    <li key={i} className="space-y-1">
                      <p className="text-sm font-medium">{g.topic}</p>
                      <p className="text-xs text-gray-500 dark:text-gray-400">{g.reason}</p>
                      <AiCitations citations={g.citations} formatLabel={formatCiteLabel} formatValue={formatCiteValue} />
                    </li>
                  ))}
                </ul>
              </section>
            )}

            <AiFollowupSection
              title={t("analytics.review.followup_title")}
              followups={run.followups}
              starters={starters}
              question={question}
              maxChars={ANALYTICS_AI_FOLLOWUP_MAX_CHARS}
              placeholder={t("analytics.review.followup_placeholder")}
              askLabel={t("analytics.review.followup_ask")}
              askingLabel={t("analytics.review.followup_asking")}
              isPending={ask.isPending}
              errorMessage={ask.error ? apiErrorMessage(ask.error, t) : null}
              onQuestionChange={setQuestion}
              onAsk={(q) =>
                ask.mutate(
                  { runId: run.id, question: q },
                  { onSuccess: () => setQuestion("") },
                )
              }
              formatCitationLabel={formatCiteLabel}
              formatCitationValue={formatCiteValue}
            />

            {(run.inputTokens !== null || run.latencyMs !== null) && (
              <p className="text-xs text-gray-400">
                {t("analytics.review.usage", {
                  input: run.inputTokens ?? 0,
                  output: run.outputTokens ?? 0,
                  seconds: Math.round((run.latencyMs ?? 0) / 100) / 10,
                })}
              </p>
            )}
          </>
        )}
      </CardBody>
    </Card>
  );
}

function VerifiedMetricStrip({
  metrics,
  currency,
  locale,
}: {
  metrics: VerifiedMetric[];
  currency: string;
  locale: string;
}) {
  const { t } = useTranslation();
  if (metrics.length === 0) return null;
  return (
    <div className="mt-2 flex flex-wrap gap-1.5">
      {metrics.map((m, i) => (
        <span
          key={`${m.key}-${i}`}
          className="rounded border border-border bg-sunken px-2 py-0.5 text-xs tabular-nums text-gray-700 dark:text-gray-200"
        >
          <span className="text-gray-500 dark:text-gray-400">{formatReviewMetricLabel(m.key, t)}:</span>{" "}
          <span className="font-medium">{formatVerifiedMetricValue(m, currency, locale)}</span>
        </span>
      ))}
    </div>
  );
}

function SavingsFindingCard({
  item,
  currency,
  locale,
  categoryLabel,
  onSimulate,
  onRecordDecision,
  onUndoDecision,
  decisionBusy = false,
  formatCiteLabel,
  formatCiteValue,
}: {
  item: AnalyticsSavingsFinding;
  currency: string;
  locale: string;
  categoryLabel: string | null;
  onSimulate: (scenario: ProjectionScenario) => void;
  onRecordDecision?: (req: RecordSpendingReviewDecisionRequest) => void;
  onUndoDecision?: (decisionId: string) => void;
  decisionBusy?: boolean;
  formatCiteLabel: (c: { path: string; value: unknown; grounded: boolean; context?: string | null }) => string;
  formatCiteValue: (c: { path: string; value: unknown; grounded: boolean; context?: string | null }) => string;
}) {
  const { t } = useTranslation();
  const [splitting, setSplitting] = useState(false);
  const variants = item.mergedVariants ?? [];
  const [groupChoice, setGroupChoice] = useState<Record<string, "A" | "B">>({});

  const titleHasName =
    item.localName != null && item.title.toLowerCase().includes(item.localName.toLowerCase());
  const canDecide = Boolean(onRecordDecision && item.subjectKind && item.categoryCode && item.localName);
  const canSplit = Boolean(canDecide && variants.length > 1);

  const openSplit = () => {
    const initial: Record<string, "A" | "B"> = {};
    variants.forEach((v, idx) => {
      initial[v] = idx === 0 ? "A" : "B";
    });
    setGroupChoice(initial);
    setSplitting(true);
  };

  const submitSplit = () => {
    if (!onRecordDecision || !item.subjectKind || !item.categoryCode || !item.localName) return;
    const groupA = variants.filter((v) => (groupChoice[v] ?? "A") === "A");
    const groupB = variants.filter((v) => groupChoice[v] === "B");
    if (groupA.length === 0 || groupB.length === 0) return;
    onRecordDecision({
      subjectKind: item.subjectKind,
      categoryCode: item.categoryCode,
      decisionType: "split",
      displayLabel: variants.join(" / "),
      memberKeys: item.memberKeys,
      splitGroups: [groupA, groupB],
    });
    setSplitting(false);
  };

  const recordSimple = (decisionType: "keep" | "compared" | "not_recurring") => {
    if (!onRecordDecision || !item.subjectKind || !item.categoryCode || !item.localName) return;
    onRecordDecision({
      subjectKind: item.subjectKind,
      categoryCode: item.categoryCode,
      decisionType,
      displayLabel: item.localName,
      memberKeys: item.memberKeys && item.memberKeys.length > 0 ? item.memberKeys : [item.localName],
      amountAtDecision: item.amountAtDecision ?? item.potentialMonthlySaving ?? null,
    });
  };

  const groupACount = variants.filter((v) => (groupChoice[v] ?? "A") === "A").length;
  const groupBCount = variants.filter((v) => groupChoice[v] === "B").length;

  return (
    <li
      className={cn(
        "rounded-md border border-border border-l-4 bg-white/90 p-3 dark:bg-surface",
        item.severity === "warning" ? "border-l-amber-500" : "border-l-emerald-500",
      )}
    >
      <div className="flex flex-wrap items-start justify-between gap-2">
        <div className="space-y-0.5">
          <div className="flex flex-wrap items-center gap-2">
            <AiSeverityBadge severity={item.severity} keyPrefix="analytics.review.severity_" />
            <p className="text-sm font-semibold text-gray-900 dark:text-gray-100">{item.title}</p>
            {item.action === "cancellable" && (
              <Badge tone="sky">{t("analytics.review.action_cancellable")}</Badge>
            )}
            {item.action === "negotiable" && (
              <Badge tone="amber">{t("analytics.review.action_negotiable")}</Badge>
            )}
            {item.localName && !titleHasName && <Badge tone="neutral">{item.localName}</Badge>}
            <MergedVariantsToggle variants={item.mergedVariants} />
            {categoryLabel && <span className="text-xs text-gray-500 dark:text-gray-400">{categoryLabel}</span>}
          </div>
          {item.potentialMonthlySaving != null && Number(item.potentialMonthlySaving) > 0 && (
            <p className="text-xs font-semibold tabular-nums text-emerald-700 dark:text-emerald-300">
              {t("analytics.review.potential_saving_line", {
                monthly: formatWholeMoney(item.potentialMonthlySaving, currency, locale),
                annual: item.potentialAnnualSaving
                  ? formatWholeMoney(item.potentialAnnualSaving, currency, locale)
                  : formatWholeMoney(Number(item.potentialMonthlySaving) * 12, currency, locale),
              })}
            </p>
          )}
        </div>
        <div className="flex flex-wrap items-center gap-2">
          {item.simulationScenario && (
            <Button variant="secondary" className="px-2.5 py-1 text-xs" onClick={() => onSimulate(item.simulationScenario!)}>
              {t("analytics.review.simulate_money_flow")}
            </Button>
          )}
          {item.txFilter && (
            <Link
              to={transactionsUrlFromFilter(item.txFilter)}
              className="rounded border border-border-strong bg-raised px-2 py-1 text-xs text-sky-700 hover:bg-raised-hover dark:text-sky-300"
            >
              {t("analytics.review.see_transactions")}
            </Link>
          )}
          {item.explorerLink && (
            <Link
              to={explorerUrlFromLink(item.explorerLink)}
              className="rounded border border-border-strong bg-raised px-2 py-1 text-xs text-gray-700 hover:bg-raised-hover dark:text-gray-200"
            >
              {t("analytics.review.see_explorer")}
            </Link>
          )}
        </div>
      </div>
      <p className="mt-1.5 text-sm leading-relaxed text-gray-700 dark:text-gray-300">{item.detail}</p>
      <VerifiedMetricStrip metrics={item.metrics} currency={currency} locale={locale} />
      {(canDecide || (item.decision && onUndoDecision)) && (
        <div className="mt-2.5 flex flex-wrap items-center gap-1.5 border-t border-border pt-2">
          {canDecide && (
            <>
              <button
                type="button"
                disabled={decisionBusy}
                onClick={() => recordSimple("keep")}
                className="rounded border border-border bg-sunken px-2 py-1 text-xs text-gray-700 hover:bg-raised-hover disabled:opacity-50 dark:text-gray-200"
              >
                {t("analytics.review.decision_keep")}
              </button>
              <button
                type="button"
                disabled={decisionBusy}
                onClick={() => recordSimple("compared")}
                className="rounded border border-border bg-sunken px-2 py-1 text-xs text-gray-700 hover:bg-raised-hover disabled:opacity-50 dark:text-gray-200"
              >
                {t("analytics.review.decision_compared")}
              </button>
              {item.subjectKind === "recurring" && (
                <button
                  type="button"
                  disabled={decisionBusy}
                  onClick={() => recordSimple("not_recurring")}
                  className="rounded border border-border bg-sunken px-2 py-1 text-xs text-gray-700 hover:bg-raised-hover disabled:opacity-50 dark:text-gray-200"
                >
                  {t("analytics.review.decision_not_recurring")}
                </button>
              )}
              {canSplit && (
                <button
                  type="button"
                  disabled={decisionBusy}
                  onClick={() => (splitting ? setSplitting(false) : openSplit())}
                  className="rounded border border-border bg-sunken px-2 py-1 text-xs text-gray-700 hover:bg-raised-hover disabled:opacity-50 dark:text-gray-200"
                >
                  {t("analytics.review.decision_split")}
                </button>
              )}
            </>
          )}
          {item.decision && onUndoDecision && (
            <button
              type="button"
              disabled={decisionBusy}
              onClick={() => onUndoDecision(item.decision!.id)}
              className="ml-auto rounded border border-amber-300 bg-amber-50 px-2 py-1 text-xs text-amber-800 hover:bg-amber-100 disabled:opacity-50 dark:border-amber-800 dark:bg-amber-950/30 dark:text-amber-200"
            >
              {t("analytics.review.decision_undo")}
            </button>
          )}
        </div>
      )}
      {splitting && canSplit && (
        <div className="mt-2 rounded-md border border-border bg-sunken p-2.5 text-xs space-y-2">
          <p className="font-medium text-gray-800 dark:text-gray-200">{t("analytics.review.decision_split_title")}</p>
          <p className="text-gray-500 dark:text-gray-400">{t("analytics.review.decision_split_hint")}</p>
          <ul className="space-y-1.5">
            {variants.map((v) => {
              const chosen = groupChoice[v] ?? "A";
              return (
                <li key={v} className="flex flex-wrap items-center justify-between gap-2">
                  <span className="font-medium text-gray-800 dark:text-gray-200">{v}</span>
                  <div className="inline-flex gap-1">
                    <button
                      type="button"
                      onClick={() => setGroupChoice((prev) => ({ ...prev, [v]: "A" }))}
                      className={cn(
                        "rounded border px-2 py-0.5 text-xs",
                        chosen === "A"
                          ? "border-sky-600 bg-sky-600 text-white"
                          : "border-border bg-surface text-gray-700 dark:text-gray-300",
                      )}
                    >
                      {t("analytics.review.decision_split_group_a")}
                    </button>
                    <button
                      type="button"
                      onClick={() => setGroupChoice((prev) => ({ ...prev, [v]: "B" }))}
                      className={cn(
                        "rounded border px-2 py-0.5 text-xs",
                        chosen === "B"
                          ? "border-sky-600 bg-sky-600 text-white"
                          : "border-border bg-surface text-gray-700 dark:text-gray-300",
                      )}
                    >
                      {t("analytics.review.decision_split_group_b")}
                    </button>
                  </div>
                </li>
              );
            })}
          </ul>
          <div className="flex items-center gap-2">
            <Button
              variant="primary"
              className="px-2.5 py-1 text-xs"
              disabled={decisionBusy || groupACount === 0 || groupBCount === 0}
              onClick={submitSplit}
            >
              {t("analytics.review.decision_split_confirm")}
            </Button>
            <Button variant="ghost" className="px-2 py-1 text-xs" onClick={() => setSplitting(false)}>
              {t("common.cancel")}
            </Button>
          </div>
        </div>
      )}
      {item.citations.length > 0 && (
        <div className="mt-2">
          <AiCitations citations={item.citations} formatLabel={formatCiteLabel} formatValue={formatCiteValue} />
        </div>
      )}
    </li>
  );
}

function ChangeFindingCard({
  item,
  currency,
  locale,
  onUndoDecision,
  decisionBusy = false,
  formatCiteLabel,
  formatCiteValue,
}: {
  item: AnalyticsChangeFinding;
  currency: string;
  locale: string;
  onUndoDecision?: (decisionId: string) => void;
  decisionBusy?: boolean;
  formatCiteLabel: (c: { path: string; value: unknown; grounded: boolean; context?: string | null }) => string;
  formatCiteValue: (c: { path: string; value: unknown; grounded: boolean; context?: string | null }) => string;
}) {
  const { t } = useTranslation();
  return (
    <li
      className={cn(
        "rounded-md border border-border border-l-4 bg-white/90 p-3 dark:bg-surface",
        item.severity === "critical"
          ? "border-l-red-500"
          : item.severity === "warning"
            ? "border-l-amber-500"
            : "border-l-emerald-500",
      )}
    >
      <div className="flex flex-wrap items-start justify-between gap-2">
        <div className="flex flex-wrap items-center gap-2">
          <AiSeverityBadge severity={item.severity} keyPrefix="analytics.review.severity_" />
          <Badge tone="neutral">{t(`analytics.review.change_kind_${item.changeKind}`, { defaultValue: item.changeKind })}</Badge>
          <p className="text-sm font-semibold text-gray-900 dark:text-gray-100">{item.title}</p>
          {item.decision?.decisionType === "keep" && (
            <Badge tone="green">{t("analytics.review.upcoming_kept_badge")}</Badge>
          )}
          <MergedVariantsToggle variants={item.mergedVariants} />
        </div>
        <div className="flex flex-wrap items-center gap-2 text-xs">
          {item.decision && onUndoDecision && (
            <button
              type="button"
              disabled={decisionBusy}
              onClick={() => onUndoDecision(item.decision!.id)}
              className="text-amber-700 hover:underline disabled:opacity-50 dark:text-amber-300"
            >
              {t("analytics.review.decision_undo")}
            </button>
          )}
          {item.txFilter && (
            <Link
              to={transactionsUrlFromFilter(item.txFilter)}
              className="text-sky-700 hover:underline dark:text-sky-300"
            >
              {t("analytics.review.see_transactions")}
            </Link>
          )}
          {item.explorerLink && (
            <Link
              to={explorerUrlFromLink(item.explorerLink)}
              className="text-gray-600 hover:underline dark:text-gray-300"
            >
              {t("analytics.review.see_explorer")}
            </Link>
          )}
        </div>
      </div>
      <p className="mt-1.5 text-sm leading-relaxed text-gray-700 dark:text-gray-300">{item.detail}</p>
      <VerifiedMetricStrip metrics={item.metrics} currency={currency} locale={locale} />
      {item.citations.length > 0 && (
        <div className="mt-2">
          <AiCitations citations={item.citations} formatLabel={formatCiteLabel} formatValue={formatCiteValue} />
        </div>
      )}
    </li>
  );
}

function budgetUrlForFinding(item: AnalyticsBudgetFinding): string {
  const params = new URLSearchParams();
  const from = item.txFilter?.from;
  if (from && /^\d{4}-\d{2}-\d{2}$/.test(from)) {
    params.set("year", String(Number(from.slice(0, 4))));
    params.set("month", String(Number(from.slice(5, 7))));
  }
  if (item.categoryCode) {
    params.set("category", item.categoryCode);
  }
  const qs = params.toString();
  return qs ? `/budgets?${qs}` : "/budgets";
}

function BudgetFindingCard({
  item,
  currency,
  locale,
  formatCiteLabel,
  formatCiteValue,
}: {
  item: AnalyticsBudgetFinding;
  currency: string;
  locale: string;
  formatCiteLabel: (c: { path: string; value: unknown; grounded: boolean; context?: string | null }) => string;
  formatCiteValue: (c: { path: string; value: unknown; grounded: boolean; context?: string | null }) => string;
}) {
  const { t } = useTranslation();
  return (
    <li className="rounded-md border border-border border-l-4 border-l-indigo-500 bg-white/90 p-3 dark:bg-surface">
      <div className="flex flex-wrap items-start justify-between gap-2">
        <div className="flex flex-wrap items-center gap-2">
          <AiSeverityBadge severity={item.severity} keyPrefix="analytics.review.severity_" />
          <p className="text-sm font-semibold text-gray-900 dark:text-gray-100">{item.title}</p>
        </div>
        <div className="flex flex-wrap items-center gap-2 text-xs">
          <Link
            to={budgetUrlForFinding(item)}
            className="font-medium text-indigo-700 hover:underline dark:text-indigo-300"
          >
            {t("analytics.review.adjust_budget")}
          </Link>
          {item.txFilter && (
            <Link
              to={transactionsUrlFromFilter(item.txFilter)}
              className="text-sky-700 hover:underline dark:text-sky-300"
            >
              {t("analytics.review.see_transactions")}
            </Link>
          )}
          {item.explorerLink && (
            <Link
              to={explorerUrlFromLink(item.explorerLink)}
              className="text-gray-600 hover:underline dark:text-gray-300"
            >
              {t("analytics.review.see_explorer")}
            </Link>
          )}
        </div>
      </div>
      <p className="mt-1.5 text-sm leading-relaxed text-gray-700 dark:text-gray-300">{item.detail}</p>
      <VerifiedMetricStrip metrics={item.metrics} currency={currency} locale={locale} />
      {item.citations.length > 0 && (
        <div className="mt-2">
          <AiCitations citations={item.citations} formatLabel={formatCiteLabel} formatValue={formatCiteValue} />
        </div>
      )}
    </li>
  );
}
