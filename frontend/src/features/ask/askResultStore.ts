import { useSyncExternalStore } from "react";
import type { QuestionAnswer, QuestionTarget } from "@/api/questions";

/** The last understood question, kept in memory only so its "Understood as" line survives moving from one view to
 *  the other; a reload forgets it, as nothing about a question is persisted. */
export interface AskResult {
  householdId: string;
  askedFrom: QuestionTarget;
  answer: QuestionAnswer;
  /** The view the line belongs to now: the answer's target, then wherever its cross-link led. */
  shownOn: QuestionTarget;
}

let current: AskResult | null = null;
const listeners = new Set<() => void>();

export function setAskResult(next: AskResult | null) {
  current = next;
  listeners.forEach((listener) => listener());
}

function subscribe(listener: () => void) {
  listeners.add(listener);
  return () => listeners.delete(listener);
}

export function useAskResult(): AskResult | null {
  return useSyncExternalStore(subscribe, () => current);
}
