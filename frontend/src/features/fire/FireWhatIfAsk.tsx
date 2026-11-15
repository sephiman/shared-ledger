import { useState, type FormEvent } from "react";
import { useTranslation } from "react-i18next";
import type { FireProjection } from "@/api/fire";
import { useAiSuggestionsOn } from "@/api/aiSuggestions";
import { useAskFireQuestion } from "@/api/fireAi";
import { QUESTION_MAX_CHARS } from "@/api/questions";
import { apiErrorMessage } from "@/api/client";
import { Button, FieldError, Input } from "@/components/ui/primitives";
import type { WhatIfDraft } from "./FireWhatIfBar";
import {
  applyFireQuestionWhatIf,
  describeFireQuestionWhatIf,
  describeFireUnsupported,
} from "./fireQuestionWhatIf";

interface Props {
  householdId: string;
  currency: string;
  baseProjection: FireProjection;
  onApplyDraft: (next: WhatIfDraft) => void;
}

/** Zero-numbers-out natural-language What-If translator inside the FIRE sandbox.
 *  Only the question, today's date, language and fixed template names leave the instance. */
export function FireWhatIfAsk({ householdId, currency, baseProjection, onApplyDraft }: Props) {
  const { t, i18n } = useTranslation();
  const locale = i18n.language;
  const ai = useAiSuggestionsOn(householdId);
  const ask = useAskFireQuestion(householdId);

  const [question, setQuestion] = useState("");
  const [notUnderstood, setNotUnderstood] = useState(false);
  const [understoodChips, setUnderstoodChips] = useState<string[]>([]);
  const [unsupportedNotice, setUnsupportedNotice] = useState<string | null>(null);

  if (!ai) return null;

  async function submit(e: FormEvent) {
    e.preventDefault();
    const text = question.trim();
    if (!text || ask.isPending) return;
    setNotUnderstood(false);
    setUnderstoodChips([]);
    setUnsupportedNotice(null);
    const language = locale.startsWith("es") ? "es" : "en";
    const answer = await ask.mutateAsync({ question: text, language }).catch(() => null);
    if (!answer) return;
    if (!answer.understood) {
      setNotUnderstood(true);
      return;
    }
    setUnsupportedNotice(describeFireUnsupported(answer.unsupported, t));
    if (answer.whatIf) {
      setUnderstoodChips(describeFireQuestionWhatIf(answer.whatIf, currency, locale, t));
      onApplyDraft(applyFireQuestionWhatIf(answer.whatIf, baseProjection));
    }
  }

  const examples = [
    t("fire.ai_what_if_example_1"),
    t("fire.ai_what_if_example_2"),
    t("fire.ai_what_if_example_3"),
  ];

  return (
    <div className="space-y-1.5 border-b border-sky-200/70 pb-3 dark:border-sky-800/40">
      <form onSubmit={submit} className="flex items-center gap-2" role="search">
        <Input
          aria-label={t("fire.ai_what_if_label")}
          value={question}
          onChange={(e) => setQuestion(e.target.value)}
          placeholder={t("fire.ai_what_if_placeholder")}
          maxLength={QUESTION_MAX_CHARS}
          disabled={ask.isPending}
          className="min-w-0 flex-1"
        />
        <Button type="submit" variant="secondary" disabled={ask.isPending || !question.trim()}>
          {ask.isPending ? t("ask.processing") : t("fire.ai_what_if_button")}
        </Button>
      </form>
      <div className="flex flex-wrap items-center gap-1.5">
        {examples.map((ex) => (
          <button
            key={ex}
            type="button"
            disabled={ask.isPending}
            onClick={() => setQuestion(ex)}
            className="rounded-full border border-border bg-raised px-2 py-0.5 text-xs text-gray-700 hover:border-sky-400 hover:bg-raised-hover hover:text-sky-700 disabled:opacity-50 dark:text-gray-200 dark:hover:border-sky-500 dark:hover:text-sky-300"
          >
            {ex}
          </button>
        ))}
      </div>
      <p className="text-xs text-gray-600 dark:text-gray-300">
        {t("fire.ai_what_if_tooltip", { model: ai.model, host: ai.providerHost })}
      </p>
      <FieldError message={ask.error ? apiErrorMessage(ask.error, t) : null} />
      {notUnderstood && (
        <p role="status" className="text-xs text-amber-700 dark:text-amber-400">
          {t("fire.ai_what_if_not_understood")}
        </p>
      )}
      {understoodChips.length > 0 && (
        <div role="status" className="flex flex-wrap items-center gap-1.5 text-xs text-gray-700 dark:text-gray-300">
          <span className="font-medium">{t("ask.understood_as")}</span>
          {understoodChips.map((chip, idx) => (
            <span
              key={idx}
              className="rounded-full border border-sky-300/80 bg-sky-50 px-2 py-0.5 text-xs text-sky-900 dark:border-sky-700/70 dark:bg-sky-900/50 dark:text-sky-100"
            >
              {chip}
            </span>
          ))}
        </div>
      )}
      {unsupportedNotice && (
        <p role="status" className="text-xs text-gray-500 dark:text-gray-400">
          {unsupportedNotice}
        </p>
      )}
    </div>
  );
}
