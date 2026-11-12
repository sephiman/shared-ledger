import { useState, type FormEvent } from "react";
import { useTranslation } from "react-i18next";
import type { MoneyFlowProjectionResponse } from "@/api/analytics";
import { useAiSuggestionsOn } from "@/api/aiSuggestions";
import { useCategories } from "@/api/catalog";
import { apiErrorMessage } from "@/api/client";
import {
  QUESTION_MAX_CHARS,
  useAskProjectionQuestion,
  type ProjectionExplain,
  type ProjectionScenario,
  type QuestionScope,
  type UnsupportedFilter,
} from "@/api/questions";
import { InfoTip } from "@/components/ui/InfoTip";
import { Badge, Button, FieldError, Input } from "@/components/ui/primitives";
import { categoryLabel } from "@/lib/categoryLabel";
import { formatMoney, toDecimal } from "@/lib/money";
import { buildProjectionExplanation } from "./projectionExplain";

interface ProjectionAskBoxProps {
  householdId: string;
  currency: string;
  data: MoneyFlowProjectionResponse | undefined;
  activeScenario: ProjectionScenario | null;
  onApplyScenario: (scenario: ProjectionScenario | null) => void;
}

export function ProjectionAskBox({
  householdId,
  currency,
  data,
  activeScenario,
  onApplyScenario,
}: ProjectionAskBoxProps) {
  const { t, i18n } = useTranslation();
  const locale = i18n.language;
  const ai = useAiSuggestionsOn(householdId);
  const { data: categories = [] } = useCategories(householdId);
  const ask = useAskProjectionQuestion(householdId);

  const [question, setQuestion] = useState("");
  const [notUnderstood, setNotUnderstood] = useState(false);
  const [explain, setExplain] = useState<ProjectionExplain | null>(null);
  const [unsupported, setUnsupported] = useState<UnsupportedFilter[]>([]);

  if (!ai) return null;

  const scopeLabel = (s: QuestionScope) => {
    if (s.type === "group") return t(`category_group.${s.code}`);
    const cat = categories.find((c) => c.code === s.code);
    return cat ? categoryLabel(cat, t) : s.code;
  };

  const nodeLabel = (side: "income" | "expense", id: string) => {
    if (side === "expense" && data?.level === "group") return t(`category_group.${id}`);
    const cat = categories.find((c) => c.code === id);
    return cat ? categoryLabel(cat, t) : id;
  };

  const submit = async (e: FormEvent) => {
    e.preventDefault();
    const text = question.trim();
    if (!text || ask.isPending) return;
    setNotUnderstood(false);
    setUnsupported([]);
    const language = locale.startsWith("es") ? "es" : "en";
    const answer = await ask.mutateAsync({ question: text, language }).catch(() => null);
    if (!answer) return;
    if (!answer.understood) {
      setNotUnderstood(true);
      return;
    }
    setUnsupported(answer.unsupported);
    if (answer.scenario) onApplyScenario(answer.scenario);
    setExplain(answer.explain ?? null);
  };

  const clearAll = () => {
    onApplyScenario(null);
    setExplain(null);
    setUnsupported([]);
    setNotUnderstood(false);
  };

  const explanation = data && explain ? buildProjectionExplanation(data, explain, currency, locale, nodeLabel, t) : null;

  const savingsGap = (() => {
    if (!data || activeScenario?.savingsTarget == null) return null;
    const netSaved = toDecimal(data.saved).minus(data.deficit);
    const gap = toDecimal(activeScenario.savingsTarget).minus(netSaved);
    const targetText = formatMoney(activeScenario.savingsTarget, currency, locale);
    if (gap.lte(0)) return t("analytics.projection_ask_target_met", { target: targetText });
    return t("analytics.projection_ask_target_gap", {
      target: targetText,
      gap: formatMoney(gap.toFixed(2), currency, locale),
    });
  })();

  const scenarioTags = activeScenario
    ? [
        ...activeScenario.freeze.map((s) => t("analytics.projection_ask_tag_freeze", { name: scopeLabel(s) })),
        ...activeScenario.adjustments.map((a) => {
          const sign = a.value >= 0 ? "+" : "";
          const val = a.kind === "percent" ? `${sign}${a.value}%` : `${sign}${formatMoney(a.value, currency, locale)}`;
          return `${scopeLabel(a.scope)} ${val}`;
        }),
        ...(activeScenario.windowMonths != null
          ? [t("analytics.projection_ask_tag_window", { window: activeScenario.windowMonths })]
          : []),
      ]
    : [];

  return (
    <div className="space-y-2">
      <form onSubmit={submit} className="flex items-center gap-2" role="search">
        <Input
          aria-label={t("analytics.projection_ask_label")}
          value={question}
          onChange={(e) => setQuestion(e.target.value)}
          placeholder={t("analytics.projection_ask_placeholder")}
          maxLength={QUESTION_MAX_CHARS}
          disabled={ask.isPending}
          className="min-w-0 flex-1"
        />
        <Button type="submit" disabled={ask.isPending || !question.trim()}>
          {ask.isPending ? t("ask.processing") : t("ask.button")}
        </Button>
        <InfoTip label={t("ask.tooltip_label")} openOnHover>
          {t("ask.tooltip", { model: ai.model, host: ai.providerHost })}
        </InfoTip>
      </form>

      <FieldError message={ask.error ? apiErrorMessage(ask.error, t) : null} />

      {notUnderstood && (
        <p role="status" className="text-sm text-amber-700 dark:text-amber-400">
          {t("ask.not_understood")}
        </p>
      )}

      {unsupported.length > 0 && (
        <p role="status" className="text-sm text-amber-700 dark:text-amber-400">
          {t("ask.only_unsupported", { list: unsupported.map((u) => u.label).join(", ") })}
        </p>
      )}

      {(activeScenario != null || explanation != null) && (
        <div role="status" className="space-y-2 rounded-md border border-border bg-raised px-3 py-2 text-sm">
          {activeScenario != null && (
            <div className="flex flex-wrap items-center gap-2">
              <Badge tone="sky">{t("analytics.projection_ask_scenario_active")}</Badge>
              {scenarioTags.map((tag) => (
                <Badge key={tag} tone="amber">
                  {tag}
                </Badge>
              ))}
              {savingsGap && <span className="text-gray-700 dark:text-gray-300">{savingsGap}</span>}
              <Button variant="ghost" className="ml-auto px-2 py-0.5 text-xs" onClick={clearAll}>
                {t("analytics.projection_ask_reset")}
              </Button>
            </div>
          )}

          {explanation != null && (
            <div className="space-y-1">
              <div className="flex items-center justify-between gap-2">
                <p className="font-medium">{explanation.title}</p>
                {activeScenario == null && (
                  <Button variant="ghost" className="px-2 py-0.5 text-xs" onClick={() => setExplain(null)}>
                    {t("ask.clear")}
                  </Button>
                )}
              </div>
              <p className="text-gray-700 dark:text-gray-300">{explanation.summary}</p>
              {explanation.items.length > 0 && (
                <ul className="list-disc pl-5 text-xs text-gray-600 dark:text-gray-400">
                  {explanation.items.map((item) => (
                    <li key={item}>{item}</li>
                  ))}
                </ul>
              )}
            </div>
          )}
        </div>
      )}
    </div>
  );
}
