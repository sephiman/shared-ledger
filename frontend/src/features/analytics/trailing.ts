import type { TrailingPoint } from "@/api/analytics";
import { monthName } from "@/lib/dates";

export interface TrailingChartRow {
  period: string;
  income: number;
  expenses: number;
  netSavings: number;
  savings: number;
}

// Maps the server-computed trailing points into Recharts rows. Net savings is taken
// straight from the backend (income − expense per month); it is never recomputed here.
export function buildTrailingChartData(points: TrailingPoint[], locale: string): TrailingChartRow[] {
  return points.map((p) => ({
    period: `${monthName(p.month, locale, "short")} ${String(p.year).slice(2)}`,
    income: Number(p.income),
    expenses: Number(p.expenses),
    netSavings: Number(p.netSavings),
    savings: p.savingsRate,
  }));
}
