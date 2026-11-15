import { useState, type FormEvent } from "react";
import type { TFunction } from "i18next";
import { useTranslation } from "react-i18next";
import type { FirePreviewRequest, FireProjection, FireTierKey } from "@/api/fire";
import { useAiSuggestionsOn } from "@/api/aiSuggestions";
import {
  FIRE_AI_FOLLOWUP_MAX_CHARS,
  useAskFireAiFollowup,
  useDeleteFireAiRun,
  useFireAiRuns,
  useStartFireAiAnalysis,
  type FireAiCitation,
  type FireAiDataGap,
  type FireAiObservation,
  type FireAiRun,
  type FireAiSuggestedWhatIf,
} from "@/api/fireAi";
import { apiErrorMessage } from "@/api/client";
import { Button, Card, CardBody, FieldError } from "@/components/ui/primitives";
import { cn } from "@/lib/cn";
import { formatDate } from "@/lib/dates";
import { formatWholeMoney } from "@/lib/money";
import { formatFireCitationPath } from "./fireCitationLabel";
import {
  estimatedYearText,
  formatFirePercent,
  resolveReferenceScenario,
  resolveReferenceTier,
  scenarioDisplayName,
  tierPlainName,
} from "./fireLabels";

interface Props {
  householdId: string;
  currency?: string;
  projection: FireProjection | undefined;
  onPreviewWhatIf: (preview: FirePreviewRequest) => void;
}

/**
 * The "AI plan review" tab on the FIRE page: sends pre-computed FIRE aggregates to the
 * configured provider, verifies every cited number against the snapshot, and links suggested
 * tweaks straight into the interactive What-If bar.
 */
export function FireAiAnalysisTab({ householdId, currency = "EUR", projection, onPreviewWhatIf }: Props) {
  const { t, i18n } = useTranslation();
  const locale = i18n.language;
  const ai = useAiSuggestionsOn(householdId);
  const runs = useFireAiRuns(householdId, Boolean(ai));
  const start = useStartFireAiAnalysis(householdId);
  const del = useDeleteFireAiRun(householdId);
  const [openIds, setOpenIds] = useState<Set<string>>(new Set());

  if (!ai) return null;

  const stored = runs.data ?? [];
  const [latest, ...history] = stored;
  const isRunning = stored.some((r) => r.status === "running");

  const refTier = projection ? resolveReferenceTier(projection) : undefined;
  const { scenario: refScenario, index: refIdx } = projection
    ? resolveReferenceScenario(projection)
    : { scenario: undefined, index: 0 };
  const refStat = refScenario && refTier ? refScenario.tierStats.find((s) => s.tier === refTier.key) : undefined;

  return (
    <div className="space-y-4">
      <Card>
        <CardBody className="space-y-3">
          <h2 className="text-base font-semibold">{t("fire.ai.title")}</h2>
          <p className="text-sm text-gray-600 dark:text-gray-300">{t("fire.ai.subtitle")}</p>
          {projection && refTier && (
            <div className="flex flex-wrap items-center gap-2 text-xs text-gray-500 dark:text-gray-400">
              <ScopeChip
                label={`${t("fire.ai.scope_tier")}: ${tierPlainName(refTier.key, projection.settings.fatMultiplier, locale, t)}`}
              />
              <ScopeChip
                label={`${t("fire.ai.scope_scenario")}: ${scenarioDisplayName(projection.scenarios, refIdx, t)}`}
              />
              {refTier.coveragePercent != null && (
                <ScopeChip
                  label={`${t("fire.ai.scope_coverage")}: ${formatFirePercent(refTier.coveragePercent, locale, 1)}`}
                />
              )}
              {refStat && (
                <ScopeChip
                  label={`${t("fire.ai.scope_est_year")}: ${estimatedYearText(refStat.estimatedYear, projection.settings.targetYear, t)}`}
                />
              )}
            </div>
          )}
          <div className="flex flex-wrap items-center gap-3">
            <Button
              disabled={!projection || start.isPending || isRunning}
              onClick={() => start.mutate()}
            >
              {start.isPending || isRunning ? t("fire.ai.starting") : t("fire.ai.analyze")}
            </Button>
          </div>
          <p className="rounded-md border border-border bg-sunken px-3 py-2 text-xs text-gray-600 dark:text-gray-300">
            {t("fire.ai.info", { model: ai.model, host: ai.providerHost })}
          </p>
          <FieldError message={start.error ? apiErrorMessage(start.error, t) : null} />
        </CardBody>
      </Card>

      {latest && (
        <RunCard
          householdId={householdId}
          currency={currency}
          run={latest}
          fatMultiplier={projection?.settings.fatMultiplier ?? "1.5"}
          defaultOpen
          onDelete={del.mutate}
          onPreviewWhatIf={onPreviewWhatIf}
        />
      )}

      {history.length > 0 && (
        <div className="space-y-2">
          <h3 className="text-sm font-semibold text-gray-500 dark:text-gray-400">
            {t("fire.ai.history_title", { count: history.length })}
          </h3>
          {history.map((r) => (
            <RunCard
              key={r.id}
              householdId={householdId}
              currency={currency}
              run={r}
              fatMultiplier={projection?.settings.fatMultiplier ?? "1.5"}
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
              onPreviewWhatIf={onPreviewWhatIf}
            />
          ))}
        </div>
      )}

      {stored.length === 0 && !runs.isLoading && (
        <p className="py-6 text-center text-sm text-gray-500 dark:text-gray-400">{t("fire.ai.no_runs")}</p>
      )}
    </div>
  );
}

function ScopeChip({ label }: { label: string }) {
  return (
    <span className="rounded border border-border bg-gray-100 px-2 py-0.5 text-xs text-gray-700 dark:bg-chip dark:text-gray-200">
      {label}
    </span>
  );
}

function formatScopeTier(rawTier: string, fatMultiplier: string, locale: string, t: TFunction): string {
  if (rawTier === "lean" || rawTier === "fire" || rawTier === "fat" || rawTier === "custom") {
    return tierPlainName(rawTier as FireTierKey, fatMultiplier, locale, t);
  }
  return rawTier;
}

function RunCard({
  householdId,
  currency,
  run,
  fatMultiplier,
  defaultOpen,
  onToggle,
  onDelete,
  onPreviewWhatIf,
}: {
  householdId: string;
  currency: string;
  run: FireAiRun;
  fatMultiplier: string;
  defaultOpen: boolean;
  onToggle?: () => void;
  onDelete: (id: string) => void;
  onPreviewWhatIf: (preview: FirePreviewRequest) => void;
}) {
  const { t, i18n } = useTranslation();
  const locale = i18n.language;
  const [open, setOpen] = useState(defaultOpen);
  const [copied, setCopied] = useState(false);
  const [question, setQuestion] = useState("");
  const ask = useAskFireAiFollowup(householdId);

  const toggle = () => {
    setOpen((v) => !v);
    onToggle?.();
  };
  const ungrounded = run.citationsUngrounded ?? 0;
  const tierLabel = formatScopeTier(run.scope.referenceTier, fatMultiplier, locale, t);

  const copyReport = () => {
    const lines: string[] = [
      `# ${t("fire.ai.title")} (${formatDate(run.createdAt, locale, "d MMM yyyy HH:mm")})`,
      `**${t("fire.ai.scope_tier")}:** ${tierLabel} · **${t("fire.ai.scope_scenario")}:** ${run.scope.referenceScenarioName} · **${t("fire.ai.scope_model")}:** ${run.model}`,
      "",
    ];
    if (run.summary) lines.push(`## ${t("fire.ai.summary_title")}`, run.summary, "");
    if (run.evolution) lines.push(`## ${t("fire.ai.evolution_title")}`, run.evolution, "");
    if (run.strengths.length) {
      lines.push(`## ${t("fire.ai.strengths_title")}`);
      for (const s of run.strengths) lines.push(`- **${s.title}**: ${s.detail}`);
      lines.push("");
    }
    if (run.improvements.length) {
      lines.push(`## ${t("fire.ai.improvements_title")}`);
      for (const imp of run.improvements) {
        const sev = imp.severity ? `[${imp.severity.toUpperCase()}] ` : "";
        lines.push(`- ${sev}**${imp.title}**: ${imp.detail}`);
      }
      lines.push("");
    }
    if (run.suggestedWhatIfs.length) {
      lines.push(`## ${t("fire.ai.what_ifs_title")}`);
      for (const w of run.suggestedWhatIfs) {
        const impact = w.expectedImpact ? ` _(${w.expectedImpact})_` : "";
        lines.push(`- **${w.title}**: ${w.rationale}${impact}`);
      }
      lines.push("");
    }
    if (run.observations.length) {
      lines.push(`## ${t("fire.ai.observations_title")}`);
      for (const obs of run.observations) lines.push(`- ${obs.claim}`);
      lines.push("");
    }
    if (run.followups.length) {
      lines.push(`## ${t("fire.ai.followup_title")}`);
      for (const f of run.followups) lines.push(`**Q:** ${f.question}`, `**A:** ${f.answer}`, "");
    }
    void navigator.clipboard.writeText(lines.join("\n")).then(() => {
      setCopied(true);
      window.setTimeout(() => setCopied(false), 2000);
    });
  };

  return (
    <Card>
      <CardBody className="space-y-3">
        <div className="flex flex-wrap items-center justify-between gap-2">
          <button type="button" onClick={toggle} className="flex flex-wrap items-center gap-x-2.5 gap-y-1 text-left">
            <span className="font-medium">{formatDate(run.createdAt, locale, "d MMM yyyy HH:mm")}</span>
            <ScopeChip label={`${tierLabel} · ${run.scope.referenceScenarioName}`} />
            {run.scope.coveragePercent != null && (
              <ScopeChip label={formatFirePercent(run.scope.coveragePercent, locale, 1)} />
            )}
            {run.status === "completed" && (
              <span
                className={cn(
                  "rounded px-2 py-0.5 text-xs",
                  ungrounded === 0
                    ? "bg-emerald-100 text-emerald-700 dark:bg-emerald-900/40 dark:text-emerald-300"
                    : "bg-amber-100 text-amber-700 dark:bg-amber-900/40 dark:text-amber-300",
                )}
              >
                {ungrounded === 0
                  ? t("fire.ai.grounded_ok")
                  : t("fire.ai.grounded_off", { off: ungrounded, total: run.citationsTotal ?? 0 })}
              </span>
            )}
          </button>
          <div className="flex items-center gap-2">
            {run.status === "completed" && (
              <button
                type="button"
                onClick={copyReport}
                title={t("fire.ai.copy_report")}
                className="inline-flex items-center gap-1 rounded border border-border-strong bg-raised px-2 py-1 text-xs text-gray-700 hover:bg-raised-hover dark:text-gray-200"
              >
                <span>{copied ? "✓" : "📋"}</span>
                <span>{copied ? t("fire.ai.copied") : t("fire.ai.copy_report")}</span>
              </button>
            )}
            <span className="text-xs text-gray-400">{run.model}</span>
            <button
              type="button"
              aria-label={t("common.delete")}
              title={t("common.delete")}
              onClick={() => {
                if (window.confirm(t("fire.ai.confirm_delete"))) onDelete(run.id);
              }}
              className="rounded p-1 text-gray-500 hover:bg-item-hover hover:text-red-600 dark:text-gray-300 dark:hover:text-red-400"
            >
              ✕
            </button>
          </div>
        </div>

        {run.status === "running" && (
          <p role="status" className="text-sm text-gray-500 dark:text-gray-400">
            {t("fire.ai.running")}
          </p>
        )}
        {run.status === "failed" && (
          <p className="text-sm text-red-600 dark:text-red-400">
            {t("fire.ai.failed")}: {t(`fire.ai.error_${run.errorCode ?? "INTERNAL_ERROR"}`, { defaultValue: run.errorCode ?? "" })}
          </p>
        )}
        {run.status === "interrupted" && (
          <p className="text-sm text-gray-500 dark:text-gray-400">{t("fire.ai.interrupted")}</p>
        )}

        {open && run.status === "completed" && (
          <>
            {run.summary && (
              <div className="rounded-md border border-sky-200 bg-sky-50/60 p-3.5 text-sm leading-relaxed text-gray-800 dark:border-sky-900/40 dark:bg-sky-950/25 dark:text-gray-200">
                <p className="text-xs font-semibold uppercase tracking-wider text-sky-800 dark:text-sky-300">
                  {t("fire.ai.summary_title")}
                </p>
                <p className="mt-1">{run.summary}</p>
              </div>
            )}

            {run.evolution && (
              <div className="rounded-md border border-blue-200 bg-blue-50/60 p-3.5 text-sm leading-relaxed text-blue-950 dark:border-blue-900/40 dark:bg-blue-950/25 dark:text-blue-200">
                <p className="text-xs font-semibold uppercase tracking-wider text-blue-800 dark:text-blue-300">
                  {t("fire.ai.evolution_title")}
                </p>
                <p className="mt-1">{run.evolution}</p>
              </div>
            )}

            {run.strengths.length > 0 && (
              <div className="rounded-lg border border-emerald-200 bg-emerald-50/50 p-4 dark:border-emerald-900/40 dark:bg-emerald-950/20">
                <h4 className="text-xs font-semibold uppercase tracking-wider text-emerald-800 dark:text-emerald-300">
                  {t("fire.ai.strengths_title")}
                </h4>
                <ul className="mt-3 space-y-2.5">
                  {run.strengths.map((item, i) => (
                    <li key={i} className="rounded-md border border-border border-l-4 border-l-emerald-500 bg-white/90 p-3 dark:bg-surface">
                      <p className="text-sm font-semibold text-gray-900 dark:text-gray-100">{item.title}</p>
                      <p className="mt-1 text-sm leading-relaxed text-gray-700 dark:text-gray-300">{item.detail}</p>
                      {item.citations.length > 0 && (
                        <div className="mt-2">
                          <Citations citations={item.citations} />
                        </div>
                      )}
                    </li>
                  ))}
                </ul>
              </div>
            )}

            {run.improvements.length > 0 && (
              <div className="rounded-lg border border-amber-200 bg-amber-50/50 p-4 dark:border-amber-900/40 dark:bg-amber-950/20">
                <h4 className="text-xs font-semibold uppercase tracking-wider text-amber-800 dark:text-amber-300">
                  {t("fire.ai.improvements_title")}
                </h4>
                <ul className="mt-3 space-y-3">
                  {run.improvements.map((item, i) => (
                    <li
                      key={i}
                      className={cn(
                        "rounded-md border border-border border-l-4 bg-white/90 p-3 dark:bg-surface",
                        item.severity === "critical"
                          ? "border-l-red-500"
                          : item.severity === "warning"
                            ? "border-l-amber-500"
                            : "border-l-emerald-500",
                      )}
                    >
                      <div className="flex flex-wrap items-center gap-2">
                        {item.severity && <SeverityBadge severity={item.severity} />}
                        <p className="text-sm font-semibold text-gray-900 dark:text-gray-100">{item.title}</p>
                      </div>
                      <p className="mt-1 text-sm leading-relaxed text-gray-700 dark:text-gray-300">{item.detail}</p>
                      {item.citations.length > 0 && (
                        <div className="mt-2">
                          <Citations citations={item.citations} />
                        </div>
                      )}
                    </li>
                  ))}
                </ul>
              </div>
            )}

            {run.suggestedWhatIfs.length > 0 && (
              <div className="rounded-lg border border-indigo-200 bg-indigo-50/50 p-4 dark:border-indigo-900/40 dark:bg-indigo-950/20">
                <h4 className="text-xs font-semibold uppercase tracking-wider text-indigo-800 dark:text-indigo-300">
                  {t("fire.ai.what_ifs_title")}
                </h4>
                <ul className="mt-3 space-y-2.5">
                  {run.suggestedWhatIfs.map((item, i) => (
                    <SuggestedWhatIfItem key={i} item={item} currency={currency} onPreview={onPreviewWhatIf} />
                  ))}
                </ul>
              </div>
            )}

            {run.observations.length > 0 && (
              <div className="space-y-2">
                <h4 className="text-xs font-semibold uppercase tracking-wider text-gray-500 dark:text-gray-400">
                  {t("fire.ai.observations_title")}
                </h4>
                <ul className="space-y-3">
                  {run.observations.map((obs, i) => (
                    <ObservationRow key={i} obs={obs} />
                  ))}
                </ul>
              </div>
            )}

            {run.dataGaps.length > 0 && (
              <div className="rounded-md border border-border bg-gray-50 p-3 dark:bg-sunken">
                <p className="text-xs font-semibold uppercase tracking-wide text-gray-500 dark:text-gray-400">
                  {t("fire.ai.data_gaps_title")}
                </p>
                <ul className="mt-2 space-y-2">
                  {run.dataGaps.map((item, i) => (
                    <DataGapRow key={i} item={item} />
                  ))}
                </ul>
              </div>
            )}

            <FollowupSection
              run={run}
              question={question}
              onQuestionChange={setQuestion}
              onAsk={(q) => {
                ask.mutate(
                  { runId: run.id, question: q },
                  { onSuccess: () => setQuestion("") },
                );
              }}
              isPending={ask.isPending}
              errorMessage={ask.error ? apiErrorMessage(ask.error, t) : null}
            />

            {(run.inputTokens !== null || run.latencyMs !== null) && (
              <p className="text-xs text-gray-400">
                {t("fire.ai.usage", {
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

function previewOverrideLabels(
  preview: FirePreviewRequest,
  currency: string,
  locale: string,
  t: TFunction,
): string[] {
  const labels: string[] = [];
  if (preview.monthlyContribution != null) {
    labels.push(t("fire.ai_what_if_chip_contrib", { value: formatWholeMoney(preview.monthlyContribution, currency, locale) }));
  }
  if (preview.monthlySpending != null) {
    labels.push(t("fire.ai_what_if_chip_spending", { value: formatWholeMoney(preview.monthlySpending, currency, locale) }));
  }
  if (preview.targetYear != null) {
    labels.push(t("fire.ai_what_if_chip_year", { value: String(preview.targetYear) }));
  }
  if (preview.safeWithdrawalRatePct != null) {
    labels.push(t("fire.ai_what_if_chip_swr", { value: formatFirePercent(Number(preview.safeWithdrawalRatePct), locale, 2) }));
  }
  if (preview.lumpSum != null && Number(preview.lumpSum) > 0) {
    labels.push(t("fire.ai_what_if_chip_lump_sum", { value: formatWholeMoney(preview.lumpSum, currency, locale) }));
  }
  if (preview.returnShiftPct != null && Number(preview.returnShiftPct) !== 0) {
    const n = Number(preview.returnShiftPct);
    labels.push(t("fire.ai_what_if_chip_return", { value: `${n >= 0 ? "+" : ""}${formatFirePercent(n, locale, 1)}` }));
  }
  return labels;
}

function SuggestedWhatIfItem({
  item,
  currency,
  onPreview,
}: {
  item: FireAiSuggestedWhatIf;
  currency: string;
  onPreview: (preview: FirePreviewRequest) => void;
}) {
  const { t, i18n } = useTranslation();
  const locale = i18n.language;
  const preview = item.preview;
  const overridePills = preview ? previewOverrideLabels(preview, currency, locale, t) : [];
  return (
    <li className="rounded-md border border-border border-l-4 border-l-indigo-500 bg-white/90 p-3 dark:bg-surface">
      <div className="flex flex-wrap items-start justify-between gap-2">
        <div className="space-y-1">
          <p className="text-sm font-semibold text-gray-900 dark:text-gray-100">{item.title}</p>
          {overridePills.length > 0 && (
            <div className="flex flex-wrap gap-1.5">
              {overridePills.map((pill, idx) => (
                <span
                  key={idx}
                  className="rounded-full border border-indigo-300/70 bg-indigo-50 px-2 py-0.5 text-xs font-medium text-indigo-800 dark:border-indigo-700/70 dark:bg-indigo-900/40 dark:text-indigo-200"
                >
                  {pill}
                </span>
              ))}
            </div>
          )}
        </div>
        {preview && (
          <Button variant="secondary" onClick={() => onPreview(preview)}>
            {t("fire.ai.preview_what_if")}
          </Button>
        )}
      </div>
      <p className="mt-1 text-sm text-gray-700 dark:text-gray-300">{item.rationale}</p>
      {item.expectedImpact && (
        <p className="mt-1 text-xs text-indigo-700 dark:text-indigo-300">
          <span className="font-medium">{t("fire.ai.expected_impact")}:</span> {item.expectedImpact}
        </p>
      )}
      {item.citations.length > 0 && (
        <div className="mt-2">
          <Citations citations={item.citations} />
        </div>
      )}
    </li>
  );
}

function ObservationRow({ obs }: { obs: FireAiObservation }) {
  const { t } = useTranslation();
  const c = obs.confidence?.toLowerCase();
  const tone =
    c === "high"
      ? "bg-emerald-100 text-emerald-700 dark:bg-emerald-900/40 dark:text-emerald-300"
      : c === "medium"
        ? "bg-amber-100 text-amber-700 dark:bg-amber-900/40 dark:text-amber-300"
        : "bg-gray-100 text-gray-700 dark:bg-chip dark:text-gray-200";

  return (
    <li className="space-y-1">
      <p className="text-sm leading-snug">{obs.claim}</p>
      {obs.confidence && (
        <span className={cn("inline-block rounded px-1.5 py-0.5 text-xs", tone)}>
          {t(`fire.ai.confidence_${c}`, { defaultValue: obs.confidence })}
        </span>
      )}
      {obs.citations.length > 0 && <Citations citations={obs.citations} />}
    </li>
  );
}

function DataGapRow({ item }: { item: FireAiDataGap }) {
  return (
    <li className="space-y-1">
      <p className="text-sm font-medium">{item.topic}</p>
      <p className="text-xs text-gray-500 dark:text-gray-400">{item.reason}</p>
      {item.citations.length > 0 && <Citations citations={item.citations} />}
    </li>
  );
}

function Citations({ citations }: { citations: FireAiCitation[] }) {
  const { t } = useTranslation();
  return (
    <ul className="flex flex-wrap gap-1.5">
      {citations.map((c, i) => {
        const label = formatFireCitationPath(c.path, t, c.context);
        const value = formatCitedValue(c.value);
        const title = c.grounded ? c.path : `${c.path} — ${t("fire.ai.citation_mismatch")}`;
        return (
          <li key={i}>
            <span
              title={title}
              className={cn(
                "inline-block rounded border px-1.5 py-0.5 text-xs tabular-nums",
                c.grounded
                  ? "border-emerald-200 text-emerald-700 dark:border-emerald-800 dark:text-emerald-300"
                  : "border-red-300 text-red-600 line-through dark:border-red-800 dark:text-red-400",
              )}
            >
              <span>{label}</span> = {value}
            </span>
          </li>
        );
      })}
    </ul>
  );
}

function FollowupSection({
  run,
  question,
  onQuestionChange,
  onAsk,
  isPending,
  errorMessage,
}: {
  run: FireAiRun;
  question: string;
  onQuestionChange: (v: string) => void;
  onAsk: (q: string) => void;
  isPending: boolean;
  errorMessage: string | null;
}) {
  const { t } = useTranslation();
  const starters = [
    t("fire.ai.followup_starter_risk"),
    t("fire.ai.followup_starter_returns"),
    t("fire.ai.followup_starter_spending"),
  ];

  function submit(e: FormEvent) {
    e.preventDefault();
    const trimmed = question.trim();
    if (!trimmed || isPending) return;
    onAsk(trimmed);
  }

  return (
    <div className="space-y-3 rounded-lg border border-border bg-gray-50/70 p-3.5 dark:bg-sunken">
      <h4 className="text-xs font-semibold uppercase tracking-wider text-gray-600 dark:text-gray-300">
        {t("fire.ai.followup_title")}
      </h4>
      {run.followups.length > 0 && (
        <ul className="space-y-3">
          {run.followups.map((item) => (
            <li key={item.id} className="space-y-2 rounded-md border border-border bg-white/90 p-3 dark:bg-surface">
              <p className="text-xs font-semibold text-sky-700 dark:text-sky-300">{item.question}</p>
              <p className="whitespace-pre-line text-sm leading-relaxed text-gray-800 dark:text-gray-200">{item.answer}</p>
              {item.citations.length > 0 && <Citations citations={item.citations} />}
            </li>
          ))}
        </ul>
      )}
      <div className="flex flex-wrap gap-1.5">
        {starters.map((s) => (
          <button
            key={s}
            type="button"
            disabled={isPending}
            onClick={() => onQuestionChange(s)}
            className="rounded-full border border-border-strong bg-raised px-2.5 py-1 text-xs text-gray-700 hover:border-sky-400 hover:bg-raised-hover hover:text-sky-700 disabled:opacity-50 dark:text-gray-200 dark:hover:border-sky-500 dark:hover:text-sky-300"
          >
            {s}
          </button>
        ))}
      </div>
      <form onSubmit={submit} className="flex flex-col gap-2 sm:flex-row">
        <input
          type="text"
          maxLength={FIRE_AI_FOLLOWUP_MAX_CHARS}
          value={question}
          disabled={isPending}
          onChange={(e) => onQuestionChange(e.target.value)}
          placeholder={t("fire.ai.followup_placeholder")}
          aria-label={t("fire.ai.followup_placeholder")}
          className="flex-1 rounded-md border border-border-strong bg-field px-3 py-1.5 text-sm text-gray-900 placeholder:text-gray-400 focus:border-sky-500 focus:outline-none dark:text-gray-100 dark:placeholder:text-gray-500"
        />
        <Button type="submit" disabled={isPending || question.trim().length === 0}>
          {isPending ? t("fire.ai.followup_asking") : t("fire.ai.followup_ask")}
        </Button>
      </form>
      <FieldError message={errorMessage} />
    </div>
  );
}

function SeverityBadge({ severity }: { severity: string }) {
  const { t } = useTranslation();
  const s = severity.toLowerCase();
  const tone =
    s === "critical"
      ? "border-red-200 bg-red-100 text-red-700 dark:border-red-900 dark:bg-red-950/40 dark:text-red-300"
      : s === "warning"
        ? "border-amber-200 bg-amber-100 text-amber-800 dark:border-amber-900 dark:bg-amber-950/40 dark:text-amber-300"
        : "border-emerald-200 bg-emerald-100 text-emerald-800 dark:border-emerald-900 dark:bg-emerald-950/40 dark:text-emerald-300";

  return (
    <span className={cn("inline-flex items-center rounded border px-1.5 py-0.5 text-xs font-medium", tone)}>
      {t(`fire.ai.severity_${s}`, { defaultValue: s })}
    </span>
  );
}

function formatCitedValue(value: unknown): string {
  if (typeof value === "string" || typeof value === "number") return String(value);
  return JSON.stringify(value);
}
