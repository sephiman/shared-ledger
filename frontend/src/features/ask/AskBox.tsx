import { useState, type FormEvent } from "react";
import { Link, useLocation, useNavigate } from "react-router-dom";
import { useTranslation } from "react-i18next";
import { useActiveHousehold } from "@/auth/AuthContext";
import { useAiSuggestionsOn } from "@/api/aiSuggestions";
import { useCategories } from "@/api/catalog";
import { apiErrorMessage } from "@/api/client";
import {
  QUESTION_MAX_CHARS,
  useAskQuestion,
  useQuestionExampleCategory,
  type QuestionTarget,
} from "@/api/questions";
import { Button, FieldError, Input } from "@/components/ui/primitives";
import { InfoTip } from "@/components/ui/InfoTip";
import { categoryLabel } from "@/lib/categoryLabel";
import { filtersUrl, TARGET_PATHS } from "./askTargets";
import { setAskResult, useAskResult, type AskResult } from "./askResultStore";
import { describeFilters, describeUnsupported } from "./describeAnswer";

/** The question box, the same in the Explorer and the transactions list. It only turns a question into the filters
 *  those views already have; the view then shows the figures. Exists only while the household has AI on. */
export function AskBox({ view }: { view: QuestionTarget }) {
  const { t, i18n } = useTranslation();
  const household = useActiveHousehold();
  const ai = useAiSuggestionsOn(household.householdId);
  const { data: categories = [] } = useCategories(household.householdId);
  const ask = useAskQuestion(household.householdId);
  const navigate = useNavigate();
  const location = useLocation();
  const result = useAskResult();
  const [question, setQuestion] = useState("");
  const [notUnderstood, setNotUnderstood] = useState(false);

  // A real expense category of the household — its most used one — so the example reads like its own question.
  const { data: exampleCode } = useQuestionExampleCategory(household.householdId, ai !== null);
  const exampleCategory = categories.find((c) => c.code === exampleCode?.categoryCode);
  const example = exampleCategory ? categoryLabel(exampleCategory, t) : null;

  if (!ai) return null;

  const submit = async (e: FormEvent) => {
    e.preventDefault();
    const text = question.trim();
    if (!text || ask.isPending) return;
    setNotUnderstood(false);
    setAskResult(null);
    const language = i18n.language.startsWith("es") ? "es" : "en";
    // A failure is shown inline from the mutation's error.
    const answer = await ask.mutateAsync({ question: text, language, askedFrom: view }).catch(() => null);
    if (!answer) return;
    if (!answer.understood) {
      setNotUnderstood(true);
      return;
    }
    // The server picked the view; with only unsupported filters there is none, and the line stays here.
    const target = answer.target ?? view;
    setAskResult({ householdId: household.householdId, askedFrom: view, answer, shownOn: target });
    if (answer.filters && answer.target) navigate(filtersUrl(answer.target, answer.filters));
  };

  const shown = result && result.householdId === household.householdId && result.shownOn === view ? result : null;
  const clear = () => {
    // Undo the question's filters only while they are still the ones on screen; hand edits since then stay.
    const filters = shown?.answer.filters;
    if (filters && `${location.pathname}${location.search}` === filtersUrl(view, filters)) {
      navigate(TARGET_PATHS[view], { replace: true });
    }
    setAskResult(null);
  };

  return (
    <div className="space-y-2">
      <form onSubmit={submit} className="flex items-center gap-2" role="search">
        <Input
          aria-label={t("ask.label")}
          value={question}
          onChange={(e) => setQuestion(e.target.value)}
          placeholder={example ? t("ask.placeholder", { category: example }) : t("ask.placeholder_generic")}
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
      {shown && <UnderstoodAs result={shown} view={view} onClear={clear} />}
    </div>
  );
}

const OTHER_VIEW = { explorer: "transactions", transactions: "explorer" } as const;

function UnderstoodAs({ result, view, onClear }: { result: AskResult; view: QuestionTarget; onClear: () => void }) {
  const { t, i18n } = useTranslation();
  const household = useActiveHousehold();
  const { data: categories = [] } = useCategories(household.householdId);
  const { answer } = result;
  const filters = answer.filters ? describeFilters(answer.filters, view, categories, t, i18n.language) : null;
  const unsupported = describeUnsupported(answer, i18n.language);
  const other = OTHER_VIEW[view];
  // The same filter object, through the other view's own URL; the line follows the member there.
  const crossLink = answer.filters && answer.expressibleIn.includes(other) ? filtersUrl(other, answer.filters) : null;
  return (
    <div role="status" className="flex flex-wrap items-center gap-x-2 gap-y-1 rounded-md border border-border bg-raised px-3 py-2 text-sm">
      {filters && (
        <span>
          <span className="font-medium">{t("ask.understood_as")}</span> {filters}
        </span>
      )}
      {answer.reason && view === answer.target && (
        <span className="text-gray-500 dark:text-gray-400">{t(`ask.routed_${answer.reason}`)}</span>
      )}
      {view === "transactions" && answer.filters && answer.filters.scopes.length > 1 && (
        <span className="text-gray-500 dark:text-gray-400">{t("ask.one_category_at_a_time")}</span>
      )}
      {unsupported && (
        <span className="text-amber-700 dark:text-amber-400">
          {t(filters ? "ask.unsupported" : "ask.only_unsupported", { list: unsupported })}
        </span>
      )}
      <span className="ml-auto flex items-center gap-2">
        {crossLink && (
          <Link
            to={crossLink}
            onClick={() => setAskResult({ ...result, shownOn: other })}
            className="font-medium text-primary hover:underline focus:outline-none focus-visible:ring-2 focus-visible:ring-primary"
          >
            {t(other === "explorer" ? "ask.link_to_explorer" : "ask.link_to_transactions")}
          </Link>
        )}
        <Button variant="ghost" className="px-2 py-1" onClick={onClear}>
          {t("ask.clear")}
        </Button>
      </span>
    </div>
  );
}
