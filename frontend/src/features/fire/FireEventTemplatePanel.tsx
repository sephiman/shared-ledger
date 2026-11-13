import { useEffect, useMemo, useState } from "react";
import { useTranslation } from "react-i18next";
import type { FireCashFlowEvent, FireProjection, FireTemplateSuggestions } from "@/api/fire";
import { Button, FieldError, Input, Label } from "@/components/ui/primitives";
import { cn } from "@/lib/cn";
import { estimatedYearText, formatHitProbability, resolveReferenceScenario, resolveReferenceTierKey } from "./fireLabels";
import { FIRE_EVENT_TEMPLATES, templateEvents, type FireEventTemplate, type TemplateAnswers } from "./fireTemplates";

/** Debounce before asking the sandbox for the effect of the answers typed so far. */
const PREVIEW_DEBOUNCE_MS = 300;

interface Props {
  baseline: FireProjection;
  /** The events in the form now (saved or not); the template's events are added to these. */
  currentEvents: FireCashFlowEvent[];
  suggestions: FireTemplateSuggestions | undefined;
  currency: string;
  /** Sandbox runs: "before" is the form's events, "after" adds the template's. */
  onPreview: (events: FireCashFlowEvent[]) => void;
  before: FireProjection | undefined;
  after: FireProjection | undefined;
  previewPending: boolean;
  onAdd: (events: FireCashFlowEvent[]) => void;
  onCancel: () => void;
}

interface Draft {
  year: string;
  untilYear: string;
  value: string;
  label: string;
}

function isMonthly(template: FireEventTemplate): boolean {
  return template === "mortgage_end" || template === "state_pension" || template === "part_time_income";
}

/** "Add from template": asks in plain language, previews the effect, then adds ordinary cash-flow events. */
export function FireEventTemplatePanel({
  baseline,
  currentEvents,
  suggestions,
  currency,
  onPreview,
  before,
  after,
  previewPending,
  onAdd,
  onCancel,
}: Props) {
  const { t } = useTranslation();
  const [template, setTemplate] = useState<FireEventTemplate | null>(null);
  const [draft, setDraft] = useState<Draft>({ year: "", untilYear: "", value: "", label: "" });
  const firstOneOffYear = baseline.startYear + 1;

  function choose(next: FireEventTemplate) {
    setTemplate(next);
    const start = baseline.startYear;
    const suggested = next === "mortgage_end" ? (suggestions?.mortgageMonthly ?? "") : "";
    setDraft({
      year: String(next === "big_expense" || next === "windfall" ? firstOneOffYear : start + (next === "state_pension" ? 25 : 10)),
      untilYear: String(start + 15),
      value: suggested,
      label: t(`fire.template_${next}`),
    });
  }

  const problem = useMemo(() => (template ? draftProblem(template, draft, firstOneOffYear) : null), [template, draft, firstOneOffYear]);
  const events = useMemo(() => (template && !problem ? templateEvents(answersOf(template, draft)) : null), [template, draft, problem]);

  useEffect(() => {
    if (!events) return;
    const timer = window.setTimeout(() => onPreview([...currentEvents, ...events]), PREVIEW_DEBOUNCE_MS);
    return () => window.clearTimeout(timer);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [events]);

  return (
    <div className="space-y-3 rounded-md border border-sky-200/80 bg-raised/50 p-3 dark:border-sky-800/50">
      <p className="text-sm font-medium">{t("fire.template_choose")}</p>
      <div className="flex flex-wrap gap-2" role="radiogroup" aria-label={t("fire.template_choose")}>
        {FIRE_EVENT_TEMPLATES.map((key) => (
          <button
            key={key}
            type="button"
            role="radio"
            aria-checked={template === key}
            onClick={() => choose(key)}
            className={cn(
              "rounded-full border px-3 py-1 text-xs font-medium",
              template === key
                ? "border-sky-500 bg-sky-100 text-sky-900 dark:border-sky-400 dark:bg-sky-900/60 dark:text-sky-100"
                : "border-border bg-surface text-gray-700 hover:bg-raised dark:text-gray-200",
            )}
          >
            {t(`fire.template_${key}`)}
          </button>
        ))}
      </div>

      {template && (
        <div className="space-y-3">
          <p className="text-xs text-gray-600 dark:text-gray-300">{t(`fire.template_${template}_hint`)}</p>
          <div className="grid grid-cols-1 gap-3 sm:grid-cols-3">
            <div>
              <Label htmlFor="fire-template-year" className="text-xs">
                {t(isMonthly(template) ? `fire.template_year_${template}` : "fire.template_year_one_off")}
              </Label>
              <Input
                id="fire-template-year"
                type="number"
                step="1"
                min={isMonthly(template) ? baseline.startYear : firstOneOffYear}
                value={draft.year}
                onChange={(e) => setDraft({ ...draft, year: e.target.value })}
              />
            </div>
            {template === "part_time_income" && (
              <div>
                <Label htmlFor="fire-template-until" className="text-xs">{t("fire.template_until_year")}</Label>
                <Input
                  id="fire-template-until"
                  type="number"
                  step="1"
                  value={draft.untilYear}
                  onChange={(e) => setDraft({ ...draft, untilYear: e.target.value })}
                />
              </div>
            )}
            <div>
              <Label htmlFor="fire-template-value" className="text-xs">
                {t(isMonthly(template) ? `fire.template_monthly_${template}` : `fire.template_amount_${template}`)} ({currency})
              </Label>
              <Input
                id="fire-template-value"
                type="number"
                step={isMonthly(template) ? "10" : "1000"}
                min="0"
                value={draft.value}
                onChange={(e) => setDraft({ ...draft, value: e.target.value })}
              />
            </div>
            <div>
              <Label htmlFor="fire-template-label" className="text-xs">{t("fire.template_label")}</Label>
              <Input
                id="fire-template-label"
                type="text"
                value={draft.label}
                onChange={(e) => setDraft({ ...draft, label: e.target.value })}
              />
            </div>
          </div>
          <ul className="space-y-0.5 text-xs text-gray-500 dark:text-gray-400">
            <li>{t("fire.template_todays_money")}</li>
            {template === "mortgage_end" && (
              <li>
                {suggestions?.mortgageMonthly
                  ? t("fire.template_mortgage_suggestion", { count: suggestions.windowMonths })
                  : t("fire.template_mortgage_no_data")}
              </li>
            )}
            {template === "state_pension" && <li>{t("fire.template_pension_guidance")}</li>}
            {!isMonthly(template) && <li>{t("fire.template_one_off_first_year", { year: firstOneOffYear })}</li>}
          </ul>
          {problem && <FieldError message={t(problem.key, problem.values)} />}

          <EffectPreview
            baseline={baseline}
            before={before}
            after={events ? after : undefined}
            pending={previewPending}
            hasEvents={events !== null}
          />

          <div className="flex flex-wrap gap-2">
            <Button disabled={events === null} onClick={() => events && onAdd(events)}>
              {t("fire.template_add_events")}
            </Button>
            <Button variant="ghost" onClick={onCancel}>
              {t("fire.template_cancel")}
            </Button>
          </div>
        </div>
      )}
    </div>
  );
}

function answersOf(template: FireEventTemplate, d: Draft): TemplateAnswers {
  const year = Number(d.year);
  const label = d.label.trim();
  switch (template) {
    case "mortgage_end":
    case "state_pension":
      return { template, year, monthly: d.value, label };
    case "part_time_income":
      return { template, year, untilYear: Number(d.untilYear), monthly: d.value, label };
    case "big_expense":
    case "windfall":
      return { template, year, amount: d.value, label };
  }
}

function draftProblem(
  template: FireEventTemplate,
  d: Draft,
  firstOneOffYear: number,
): { key: string; values?: Record<string, number> } | null {
  const year = Number(d.year);
  const value = Number(d.value);
  if (!Number.isInteger(year) || year <= 0 || d.value.trim() === "" || !Number.isFinite(value) || value <= 0) {
    return { key: "fire.template_preview_needs_values" };
  }
  if (!isMonthly(template) && year < firstOneOffYear) {
    return { key: "fire.template_one_off_first_year", values: { year: firstOneOffYear } };
  }
  if (template === "part_time_income" && !(Number(d.untilYear) > year)) return { key: "fire.template_until_after_from" };
  return null;
}

function EffectPreview({
  baseline,
  before,
  after,
  pending,
  hasEvents,
}: {
  baseline: FireProjection;
  before: FireProjection | undefined;
  after: FireProjection | undefined;
  pending: boolean;
  hasEvents: boolean;
}) {
  const { t, i18n } = useTranslation();
  const locale = i18n.language;
  if (!hasEvents) return null;
  const stats = (p: FireProjection | undefined) => {
    if (!p) return undefined;
    const { scenario } = resolveReferenceScenario(p);
    return scenario?.tierStats.find((s) => s.tier === resolveReferenceTierKey(p));
  };
  const b = stats(before ?? baseline);
  const a = stats(after);
  const horizon = baseline.settings.targetYear;
  return (
    <div className="rounded-md border border-border bg-surface p-2 text-sm" aria-live="polite" data-testid="fire-template-preview">
      <p className="text-xs font-medium text-gray-500 dark:text-gray-400">{t("fire.template_preview_title")}</p>
      {pending || !a || !b ? (
        <p className="text-xs text-gray-500 dark:text-gray-400">{t("fire.template_preview_loading")}</p>
      ) : (
        <p className="tabular-nums">
          {t("fire.template_preview_before_after", {
            beforeYear: estimatedYearText(b.estimatedYear, horizon, t),
            afterYear: estimatedYearText(a.estimatedYear, horizon, t),
            beforeChance: formatHitProbability(b.probabilityOfReachingTarget, locale),
            afterChance: formatHitProbability(a.probabilityOfReachingTarget, locale),
          })}
        </p>
      )}
    </div>
  );
}
