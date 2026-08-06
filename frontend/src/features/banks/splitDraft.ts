import Decimal from "decimal.js";

/** Split arithmetic. The euro amount is authoritative; every percentage shown is derived from
 *  `amount / total`, never stored, so percent input can't compound its own rounding. Rounding is explicit
 *  (HALF_EVEN, as the backend's `Money.normalize`) instead of relying on lib/money's global `Decimal.set`. */
const SCALE = 2;
const ROUNDING = Decimal.ROUND_HALF_EVEN;

export interface SplitRow {
  /** Stable across removals so React keys hold. */
  key: string;
  /** Raw text as typed — the authoritative value. */
  amount: string;
  /** Set while the user types in the percentage field, so keystrokes aren't overwritten mid-edit. */
  pctDraft: string | null;
  categoryCode: string;
  description: string;
}

export type SplitRowError =
  /** Blank, unparseable, negative, or carrying more than two decimals. */
  | "amount_invalid"
  /** Auto-balanced to nothing because the other row of a two-row split takes the whole total. */
  | "amount_nothing_left"
  | "category_missing"
  | null;

export interface SplitValidation {
  /** One entry per row, aligned by index. */
  rowErrors: SplitRowError[];
  /** total − Σ amounts, 2dp: positive = left to assign, negative = over the total. */
  remainder: string;
  balanced: boolean;
  /** False for two rows: auto-balance keeps the sum exact, so a remainder line would read "balanced" even
   *  when a row is invalid. The row error carries the message there. */
  showRemainder: boolean;
  ok: boolean;
}

/** Accepts the decimal comma the amount placeholders suggest ("0,00") as well as a dot. Null means
 *  "no usable amount". */
export function parseAmount(raw: string): Decimal | null {
  const text = raw.trim().replace(",", ".");
  if (text === "") return null;
  try {
    const value = new Decimal(text);
    return value.isFinite() ? value : null;
  } catch {
    return null;
  }
}

const money = (value: Decimal): string => value.toDecimalPlaces(SCALE, ROUNDING).toFixed(SCALE);

const amountOf = (row: SplitRow): Decimal => parseAmount(row.amount) ?? new Decimal(0);

const nextKey = (rows: SplitRow[]): string =>
  `p${rows.reduce((max, r) => Math.max(max, Number(r.key.slice(1)) || 0), 0) + 1}`;

/** Two halves adding up exactly (the odd cent lands on the second), so the dialog opens saveable. */
export function initialRows(total: string, categoryCode: string, description: string): SplitRow[] {
  const sum = parseAmount(total) ?? new Decimal(0);
  const first = sum.div(2).toDecimalPlaces(SCALE, ROUNDING);
  return [
    { key: "p1", amount: money(first), pctDraft: null, categoryCode, description },
    { key: "p2", amount: money(sum.minus(first)), pctDraft: null, categoryCode, description },
  ];
}

/** New rows start empty: with 3+ rows there is no non-arbitrary sibling to shrink. */
export function addRow(rows: SplitRow[], categoryCode: string, description: string): SplitRow[] {
  return [...rows, { key: nextKey(rows), amount: "", pctDraft: null, categoryCode, description }];
}

/** A single row isn't a split, so two is the floor. */
export function removeRow(rows: SplitRow[], index: number): SplitRow[] {
  if (rows.length <= 2) return rows;
  return rows.filter((_, i) => i !== index);
}

export function updateRow(rows: SplitRow[], index: number, patch: Partial<SplitRow>): SplitRow[] {
  return rows.map((row, i) => (i === index ? { ...row, ...patch } : row));
}

/** Two rows auto-balance; beyond two it would be ambiguous which row absorbs the change. */
function balance(rows: SplitRow[], index: number, total: string): SplitRow[] {
  if (rows.length !== 2) return rows;
  const sum = parseAmount(total) ?? new Decimal(0);
  const other = index === 0 ? 1 : 0;
  return updateRow(rows, other, { amount: money(sum.minus(amountOf(rows[index]))), pctDraft: null });
}

/** Keeps the text verbatim, so "5," mid-typing isn't rewritten. */
export function setAmount(rows: SplitRow[], index: number, raw: string, total: string): SplitRow[] {
  return balance(updateRow(rows, index, { amount: raw, pctDraft: null }), index, total);
}

/** Derives the amount once, rounded to the cent; any leftover cent surfaces in the remainder or the
 *  auto-balanced sibling, never absorbed. */
export function setPercent(rows: SplitRow[], index: number, raw: string, total: string): SplitRow[] {
  const pct = parseAmount(raw);
  const sum = parseAmount(total) ?? new Decimal(0);
  const amount = pct === null ? "" : money(sum.times(pct).div(100));
  return balance(updateRow(rows, index, { amount, pctDraft: raw }), index, total);
}

/** The in-progress text if any, else derived from the amount at 2dp — €3.33 of €10.00 reads 33.30 %. */
export function percentDisplay(row: SplitRow, total: string): string {
  if (row.pctDraft !== null) return row.pctDraft;
  const sum = parseAmount(total);
  const amount = parseAmount(row.amount);
  if (amount === null || sum === null || sum.isZero()) return "";
  return amount.div(sum).times(100).toDecimalPlaces(SCALE, ROUNDING).toFixed(SCALE);
}

/** total − Σ amounts. Positive means still to assign, negative means over the total. */
export function remainderOf(rows: SplitRow[], total: string): Decimal {
  const sum = parseAmount(total) ?? new Decimal(0);
  return rows.reduce((left, row) => left.minus(amountOf(row)), sum);
}

export function validate(rows: SplitRow[], total: string): SplitValidation {
  const sum = parseAmount(total) ?? new Decimal(0);
  const remainder = remainderOf(rows, total);
  const twoRows = rows.length === 2;

  const rowErrors: SplitRowError[] = rows.map((row, index) => {
    const amount = parseAmount(row.amount);
    if (amount === null) return "amount_invalid";
    if (amount.lte(0)) {
      // Flagged on this row — the impossible number on screen — but the fix is the other row's amount.
      const sibling = amountOf(rows[index === 0 ? 1 : 0]);
      const balancedAway = twoRows && sum.gt(0) && sibling.gte(sum);
      return balancedAway ? "amount_nothing_left" : "amount_invalid";
    }
    if (amount.decimalPlaces() > SCALE) return "amount_invalid";
    if (!row.categoryCode) return "category_missing";
    return null;
  });

  const balanced = remainder.isZero();
  return {
    rowErrors,
    remainder: money(remainder),
    balanced,
    showRemainder: !twoRows,
    ok: rows.length >= 2 && balanced && rowErrors.every((e) => e === null),
  };
}

/** Blank descriptions go as null, so the server applies the bank's. */
export function toSplitParts(rows: SplitRow[]): { amount: string; categoryCode: string; description: string | null }[] {
  return rows.map((row) => ({
    amount: money(amountOf(row)),
    categoryCode: row.categoryCode,
    description: row.description.trim() || null,
  }));
}
