import type { FormEvent } from "react";
import { Button, FieldError } from "@/components/ui/primitives";
import { AiCitations, type AiCitationItem } from "./AiCitations";

export interface AiFollowupItem {
  id: string;
  question: string;
  answer: string;
  citations: AiCitationItem[];
}

export function AiFollowupSection({
  title,
  followups,
  starters,
  question,
  maxChars,
  placeholder,
  askLabel,
  askingLabel,
  isPending,
  errorMessage,
  onQuestionChange,
  onAsk,
  formatCitationLabel,
  formatCitationValue,
}: {
  title: string;
  followups: AiFollowupItem[];
  starters: string[];
  question: string;
  maxChars: number;
  placeholder: string;
  askLabel: string;
  askingLabel: string;
  isPending: boolean;
  errorMessage: string | null;
  onQuestionChange: (v: string) => void;
  onAsk: (q: string) => void;
  formatCitationLabel: (c: AiCitationItem) => string;
  formatCitationValue?: (c: AiCitationItem) => string;
}) {
  function submit(e: FormEvent) {
    e.preventDefault();
    const trimmed = question.trim();
    if (!trimmed || isPending) return;
    onAsk(trimmed);
  }

  return (
    <div className="space-y-3 rounded-lg border border-border bg-gray-50/70 p-3.5 dark:bg-sunken">
      <h4 className="text-xs font-semibold uppercase tracking-wider text-gray-600 dark:text-gray-300">{title}</h4>
      {followups.length > 0 && (
        <ul className="space-y-3">
          {followups.map((item) => (
            <li key={item.id} className="space-y-2 rounded-md border border-border bg-white/90 p-3 dark:bg-surface">
              <p className="text-xs font-semibold text-sky-700 dark:text-sky-300">{item.question}</p>
              <p className="whitespace-pre-line text-sm leading-relaxed text-gray-800 dark:text-gray-200">{item.answer}</p>
              {item.citations.length > 0 && (
                <AiCitations
                  citations={item.citations}
                  formatLabel={formatCitationLabel}
                  formatValue={formatCitationValue}
                />
              )}
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
          maxLength={maxChars}
          value={question}
          disabled={isPending}
          onChange={(e) => onQuestionChange(e.target.value)}
          placeholder={placeholder}
          aria-label={placeholder}
          className="flex-1 rounded-md border border-border-strong bg-field px-3 py-1.5 text-sm text-gray-900 placeholder:text-gray-400 focus:border-sky-500 focus:outline-none dark:text-gray-100 dark:placeholder:text-gray-500"
        />
        <Button type="submit" disabled={isPending || question.trim().length === 0}>
          {isPending ? askingLabel : askLabel}
        </Button>
      </form>
      <FieldError message={errorMessage} />
    </div>
  );
}
