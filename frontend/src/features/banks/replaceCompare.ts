import type { Direction, DuplicateCandidate, PendingMovement } from "@/api/banks";
import { bankDescription } from "./bankDescription";

/** The fields the Replace dialog compares, in display order. */
export type CompareField = "date" | "amount" | "direction" | "category" | "description" | "source";

export interface CompareRow {
  field: CompareField;
  existing: string;
  incoming: string;
  /** True when confirming would leave the transaction different from `existing` — what gets highlighted. */
  changed: boolean;
}

export interface ReplaceDraft {
  categoryCode: string;
  direction: Direction;
  description: string;
}

/** Injected by the dialog so this module stays free of i18n and locale concerns. */
export interface CompareFormatters {
  date: (iso: string) => string;
  money: (amount: string) => string;
  direction: (d: Direction) => string;
  category: (code: string) => string;
  /** The existing transaction's source: it has no bank link yet (a linked one can't be replaced). */
  manualSource: string;
  empty: string;
}

/** What would actually be stored: blanking the field falls back to the bank's value, as the backend does. */
export function effectiveDescription(movement: PendingMovement, draft: ReplaceDraft): string {
  return draft.description.trim() || bankDescription(movement);
}

/** Scale-insensitive, so "10.0" and "10.00" aren't reported as a difference. */
function sameAmount(a: string, b: string): boolean {
  return Number(a) === Number(b);
}

/** `changed` reflects the pending edit, not the raw bank value: the category row lights up only when the
 *  user re-picks one, since the human choice is kept by default. */
export function buildComparison(
  candidate: DuplicateCandidate,
  movement: PendingMovement,
  draft: ReplaceDraft,
  fmt: CompareFormatters,
): CompareRow[] {
  const bankDesc = bankDescription(movement);
  const nextDesc = effectiveDescription(movement, draft);
  const source = [movement.connectionLabel ?? movement.aspspName, movement.accountName].filter(Boolean).join(" · ");
  return [
    {
      field: "date",
      existing: fmt.date(candidate.occurrenceDate),
      incoming: fmt.date(movement.bookingDate),
      changed: candidate.occurrenceDate !== movement.bookingDate,
    },
    {
      field: "amount",
      existing: fmt.money(candidate.amount),
      incoming: fmt.money(movement.amount),
      changed: !sameAmount(candidate.amount, movement.amount),
    },
    {
      field: "direction",
      existing: fmt.direction(candidate.direction),
      incoming: fmt.direction(movement.direction),
      changed: draft.direction !== candidate.direction,
    },
    {
      field: "category",
      existing: fmt.category(candidate.categoryCode),
      incoming: movement.suggestedCategoryCode ? fmt.category(movement.suggestedCategoryCode) : fmt.empty,
      changed: draft.categoryCode !== candidate.categoryCode,
    },
    {
      field: "description",
      existing: candidate.description ?? fmt.empty,
      incoming: bankDesc || fmt.empty,
      changed: nextDesc !== (candidate.description ?? ""),
    },
    // The transaction gains the bank link a normally-confirmed movement would have, so this always moves.
    { field: "source", existing: fmt.manualSource, incoming: source || movement.aspspName, changed: true },
  ];
}

/** A bank-linked candidate stays in the list so the dialog can explain it, but can never be the target. */
export function selectableCandidates(candidates: DuplicateCandidate[]): DuplicateCandidate[] {
  return candidates.filter((c) => !c.bankLinked);
}

/** Same rule the inbox row applies: flipping direction drops a category of the other kind rather than
 *  submitting it into a CATEGORY_DIRECTION_MISMATCH. */
export function categoryAfterDirectionChange(
  categoryCode: string,
  direction: Direction,
  categories: { code: string; kind: string }[],
): string {
  if (!categoryCode) return "";
  const kind = categories.find((c) => c.code === categoryCode)?.kind;
  return kind && kind !== direction ? "" : categoryCode;
}
