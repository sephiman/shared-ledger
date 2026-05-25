import { useMemo, useState } from "react";
import { useTranslation } from "react-i18next";
import { Bar, BarChart, CartesianGrid, Cell, ResponsiveContainer, Tooltip, XAxis, YAxis } from "recharts";
import { useActiveHousehold } from "@/auth/AuthContext";
import { useDailyTotals, useYearsAvailable, type DailyPoint } from "@/api/analytics";
import { useCategories, type Category } from "@/api/catalog";
import { useTransactions, type Transaction } from "@/api/transactions";
import { Button, Card, CardBody, CardHeader, Label, Select } from "@/components/ui/primitives";
import { formatDate, monthName, weekdayName } from "@/lib/dates";
import { formatMoney } from "@/lib/money";
import { categoryIcon } from "@/lib/categoryGroup";
import { categoryLabelByCode } from "@/lib/categoryLabel";
import { hexWithAlpha } from "@/lib/color";
import { cn } from "@/lib/cn";

type YearMonth = { year: number; month: number };
type DailyRange = "3m" | "12m" | "ytd" | "all";

const EXPENSE_HUE = "#0ea5e9";
const HIGHLIGHT_HUE = "#0369a1";

function pad2(n: number): string {
  return String(n).padStart(2, "0");
}

function isoDate(year: number, month: number, day: number): string {
  return `${year}-${pad2(month)}-${pad2(day)}`;
}

function todayIso(): string {
  const d = new Date();
  return isoDate(d.getFullYear(), d.getMonth() + 1, d.getDate());
}

function todayYearMonth(): YearMonth {
  const d = new Date();
  return { year: d.getFullYear(), month: d.getMonth() + 1 };
}

function daysInMonth(ym: YearMonth): number {
  return new Date(Date.UTC(ym.year, ym.month, 0)).getUTCDate();
}

// ISO weekday: 1=Mon..7=Sun. Input dateIso must be YYYY-MM-DD.
function isoWeekdayFromIso(dateIso: string): number {
  const [y, m, d] = dateIso.split("-").map(Number);
  const dt = new Date(Date.UTC(y, m - 1, d));
  return ((dt.getUTCDay() + 6) % 7) + 1;
}

function dayOfMonthFromIso(dateIso: string): number {
  return Number(dateIso.slice(8, 10));
}

function addMonths(ym: YearMonth, delta: number): YearMonth {
  const m0 = ym.month - 1 + delta;
  const year = ym.year + Math.floor(m0 / 12);
  const month = ((m0 % 12) + 12) % 12 + 1;
  return { year, month };
}

function monthBounds(ym: YearMonth): { from: string; to: string } {
  return { from: isoDate(ym.year, ym.month, 1), to: isoDate(ym.year, ym.month, daysInMonth(ym)) };
}

function rangeBounds(range: DailyRange, yearsAvail: number[]): { from: string; to: string } {
  const today = new Date();
  const to = todayIso();
  if (range === "3m") {
    const d = new Date(today);
    d.setMonth(d.getMonth() - 3);
    return { from: isoDate(d.getFullYear(), d.getMonth() + 1, d.getDate()), to };
  }
  if (range === "12m") {
    const d = new Date(today);
    d.setFullYear(d.getFullYear() - 1);
    return { from: isoDate(d.getFullYear(), d.getMonth() + 1, d.getDate()), to };
  }
  if (range === "ytd") {
    return { from: `${today.getFullYear()}-01-01`, to };
  }
  const earliest = yearsAvail.length ? Math.min(...yearsAvail) : today.getFullYear();
  return { from: `${earliest}-01-01`, to };
}

function daysBetweenInclusive(fromIso: string, toIso: string): number {
  const [fy, fm, fd] = fromIso.split("-").map(Number);
  const [ty, tm, td] = toIso.split("-").map(Number);
  const a = Date.UTC(fy, fm - 1, fd);
  const b = Date.UTC(ty, tm - 1, td);
  return Math.round((b - a) / 86400000) + 1;
}

function shortAmount(n: number): string {
  return n >= 1000 ? `${(n / 1000).toFixed(1)}k` : String(Math.round(n));
}

function rangeLabelKey(range: DailyRange): string {
  return `analytics.daily_range_${range}`;
}

export function DailyTab() {
  const { t, i18n } = useTranslation();
  const household = useActiveHousehold();
  const locale = i18n.language;

  const [selectedMonth, setSelectedMonth] = useState<YearMonth>(todayYearMonth());
  const [selectedDay, setSelectedDay] = useState<string | null>(null);
  const [range, setRange] = useState<DailyRange>("12m");

  const { data: yearsData } = useYearsAvailable(household.householdId);
  const yearsAvail = yearsData?.years ?? [];

  const calMonth = useMemo(() => monthBounds(selectedMonth), [selectedMonth]);
  const calendar = useDailyTotals(household.householdId, calMonth.from, calMonth.to, "expense");

  const patternRange = useMemo(() => rangeBounds(range, yearsAvail), [range, yearsAvail]);
  const patterns = useDailyTotals(
    household.householdId,
    patternRange.from,
    patternRange.to,
    "expense",
    yearsAvail.length > 0 || range !== "all",
  );

  const { data: categories = [] } = useCategories(household.householdId);

  const rangeLabel = t(rangeLabelKey(range));

  return (
    <div className="space-y-4">
      <Card>
        <CardHeader>
          <p className="font-medium">{t("analytics.daily")}</p>
          <p className="mt-1 text-sm text-gray-500 dark:text-gray-400">{t("analytics.daily_description")}</p>
        </CardHeader>
        <CardBody>
          <div className="grid grid-cols-1 gap-4 md:grid-cols-[1fr_360px]">
            <div className={cn(selectedDay && "hidden md:block")}>
              <DailyCalendar
                ym={selectedMonth}
                days={calendar.data?.days ?? []}
                isLoading={calendar.isLoading}
                selectedDay={selectedDay}
                onSelectDay={(iso) => setSelectedDay((prev) => (prev === iso ? null : iso))}
                onPrev={() => {
                  setSelectedDay(null);
                  setSelectedMonth((m) => addMonths(m, -1));
                }}
                onNext={() => {
                  setSelectedDay(null);
                  setSelectedMonth((m) => addMonths(m, 1));
                }}
                onToday={() => {
                  setSelectedMonth(todayYearMonth());
                  setSelectedDay(todayIso());
                }}
                currency={household.currency}
                locale={locale}
              />
            </div>
            {selectedDay && (
              <DayDetailPanel
                day={selectedDay}
                dayTotal={
                  Number(
                    (calendar.data?.days ?? []).find((p) => p.date === selectedDay)?.amount ?? "0",
                  )
                }
                patternPoints={patterns.data?.days ?? []}
                patternFrom={patternRange.from}
                patternTo={patternRange.to}
                rangeLabel={rangeLabel}
                categories={categories}
                householdId={household.householdId}
                currency={household.currency}
                locale={locale}
                onClose={() => setSelectedDay(null)}
              />
            )}
          </div>
        </CardBody>
      </Card>

      <Card>
        <CardHeader>
          <p className="font-medium">{t("analytics.daily_pattern_range")}</p>
        </CardHeader>
        <CardBody className="space-y-4">
          <div className="max-w-xs">
            <Label>{t("analytics.daily_pattern_range")}</Label>
            <Select value={range} onChange={(e) => setRange(e.target.value as DailyRange)}>
              <option value="3m">{t("analytics.daily_range_3m")}</option>
              <option value="12m">{t("analytics.daily_range_12m")}</option>
              <option value="ytd">{t("analytics.daily_range_ytd")}</option>
              <option value="all">{t("analytics.daily_range_all")}</option>
            </Select>
          </div>
          <DailyPatternCharts
            points={patterns.data?.days ?? []}
            from={patternRange.from}
            to={patternRange.to}
            isLoading={patterns.isLoading}
            highlightWeekday={isoWeekdayFromIso(selectedDay ?? todayIso())}
            currency={household.currency}
            locale={locale}
          />
        </CardBody>
      </Card>
    </div>
  );
}

type CalendarCell = {
  iso: string;
  day: number;
  inMonth: boolean;
  isFuture: boolean;
  amount: number;
};

function buildMonthGrid(ym: YearMonth, daysByIso: Map<string, number>, todayIsoVal: string): CalendarCell[] {
  const first = new Date(Date.UTC(ym.year, ym.month - 1, 1));
  const firstIso = ((first.getUTCDay() + 6) % 7) + 1; // 1=Mon..7=Sun
  const leading = firstIso - 1;
  const dim = daysInMonth(ym);
  const total = Math.ceil((leading + dim) / 7) * 7;
  const cells: CalendarCell[] = [];

  const prev = addMonths(ym, -1);
  const prevDim = daysInMonth(prev);
  for (let i = leading - 1; i >= 0; i--) {
    const dayNum = prevDim - i;
    const iso = isoDate(prev.year, prev.month, dayNum);
    cells.push({
      iso,
      day: dayNum,
      inMonth: false,
      isFuture: iso > todayIsoVal,
      amount: 0,
    });
  }
  for (let d = 1; d <= dim; d++) {
    const iso = isoDate(ym.year, ym.month, d);
    cells.push({
      iso,
      day: d,
      inMonth: true,
      isFuture: iso > todayIsoVal,
      amount: daysByIso.get(iso) ?? 0,
    });
  }
  const next = addMonths(ym, 1);
  const trailing = total - cells.length;
  for (let d = 1; d <= trailing; d++) {
    const iso = isoDate(next.year, next.month, d);
    cells.push({
      iso,
      day: d,
      inMonth: false,
      isFuture: iso > todayIsoVal,
      amount: 0,
    });
  }
  return cells;
}

function DailyCalendar({
  ym,
  days,
  isLoading,
  selectedDay,
  onSelectDay,
  onPrev,
  onNext,
  onToday,
  currency,
  locale,
}: {
  ym: YearMonth;
  days: DailyPoint[];
  isLoading: boolean;
  selectedDay: string | null;
  onSelectDay: (iso: string) => void;
  onPrev: () => void;
  onNext: () => void;
  onToday: () => void;
  currency: string;
  locale: string;
}) {
  const { t } = useTranslation();
  const today = todayIso();

  const daysByIso = useMemo(() => {
    const m = new Map<string, number>();
    for (const p of days) m.set(p.date, Number(p.amount));
    return m;
  }, [days]);

  const cells = useMemo(() => buildMonthGrid(ym, daysByIso, today), [ym, daysByIso, today]);

  const inMonthAmounts = cells.filter((c) => c.inMonth && c.amount > 0).map((c) => c.amount);
  const maxInMonth = inMonthAmounts.length ? Math.max(...inMonthAmounts) : 0;
  const totalMonth = inMonthAmounts.reduce((a, b) => a + b, 0);
  const avgPerSpendDay = inMonthAmounts.length ? totalMonth / inMonthAmounts.length : 0;
  const highest = useMemo(() => {
    let best: CalendarCell | null = null;
    for (const c of cells) {
      if (!c.inMonth) continue;
      if (!best || c.amount > best.amount) best = c.amount > 0 ? c : best;
    }
    return best;
  }, [cells]);

  return (
    <div className="space-y-3">
      <div className="flex flex-wrap items-center gap-2">
        <Button variant="ghost" onClick={onPrev} aria-label={t("analytics.daily_prev_month")}>‹</Button>
        <p className="font-medium">{monthName(ym.month, locale, "long")} {ym.year}</p>
        <Button variant="ghost" onClick={onNext} aria-label={t("analytics.daily_next_month")}>›</Button>
        <Button variant="secondary" className="ml-auto" onClick={onToday}>{t("analytics.daily_today")}</Button>
      </div>

      {totalMonth > 0 ? (
        <div className="text-sm text-gray-600 dark:text-gray-300 flex flex-wrap gap-x-4 gap-y-1">
          <span>{t("analytics.daily_calendar_summary_total", { total: formatMoney(totalMonth, currency, locale) })}</span>
          <span>{t("analytics.daily_calendar_summary_avg", { avg: formatMoney(avgPerSpendDay, currency, locale) })}</span>
          {highest && (
            <span>
              {t("analytics.daily_calendar_summary_highest", {
                date: formatDate(highest.iso, locale, "PP"),
                amount: formatMoney(highest.amount, currency, locale),
              })}
            </span>
          )}
        </div>
      ) : (
        !isLoading && (
          <p className="text-sm text-gray-500 dark:text-gray-400">{t("analytics.daily_calendar_empty")}</p>
        )
      )}

      <div className="grid grid-cols-7 gap-1 text-center text-xs font-medium text-gray-500 dark:text-gray-400">
        {[1, 2, 3, 4, 5, 6, 7].map((iso) => (
          <div key={iso} className="py-1">{weekdayName(iso, locale, "short")}</div>
        ))}
      </div>
      <div className="grid grid-cols-7 gap-1">
        {cells.map((c) => {
          const clickable = c.inMonth && !c.isFuture;
          const intensity = maxInMonth > 0 && c.amount > 0 ? Math.max(0.15, c.amount / maxInMonth) : 0;
          const bg = intensity > 0 ? hexWithAlpha(EXPENSE_HUE, intensity) : undefined;
          const isToday = c.iso === today;
          const isSelected = c.iso === selectedDay;
          return (
            <button
              key={c.iso}
              type="button"
              disabled={!clickable}
              onClick={() => clickable && onSelectDay(c.iso)}
              style={bg ? { backgroundColor: bg } : undefined}
              className={cn(
                "aspect-square rounded-md border border-transparent p-1 text-left text-xs transition-colors",
                c.inMonth
                  ? "text-gray-900 dark:text-gray-100"
                  : "text-gray-300 dark:text-gray-600",
                !bg && c.inMonth && "bg-gray-50 dark:bg-gray-900/40",
                clickable && "hover:border-primary cursor-pointer",
                !clickable && "cursor-default",
                isSelected && "ring-2 ring-primary",
              )}
              aria-label={
                c.inMonth && c.amount > 0
                  ? `${formatDate(c.iso, locale, "PP")}: ${formatMoney(c.amount, currency, locale)}`
                  : formatDate(c.iso, locale, "PP")
              }
            >
              <div className={cn("leading-none", isToday && "font-bold")}>{c.day}</div>
              {c.inMonth && !c.isFuture && c.amount > 0 && (
                <div className="mt-1 text-[10px] leading-tight">{shortAmount(c.amount)}</div>
              )}
            </button>
          );
        })}
      </div>
    </div>
  );
}

function DayDetailPanel({
  day,
  dayTotal,
  patternPoints,
  patternFrom,
  patternTo,
  rangeLabel,
  categories,
  householdId,
  currency,
  locale,
  onClose,
}: {
  day: string;
  dayTotal: number;
  patternPoints: DailyPoint[];
  patternFrom: string;
  patternTo: string;
  rangeLabel: string;
  categories: Category[];
  householdId: string;
  currency: string;
  locale: string;
  onClose: () => void;
}) {
  const { t } = useTranslation();
  const txQuery = useTransactions(householdId, { from: day, to: day, direction: "expense", size: 200 });

  const weekday = isoWeekdayFromIso(day);
  const weekdayLabel = weekdayName(weekday, locale, "long");

  const compare = useMemo(() => {
    const totalDays = daysBetweenInclusive(patternFrom, patternTo);
    let sum = 0;
    let sumW = 0;
    let countW = 0;
    for (const p of patternPoints) {
      const amt = Number(p.amount);
      sum += amt;
      if (isoWeekdayFromIso(p.date) === weekday) sumW += amt;
    }
    // count occurrences of this weekday in [from, to]
    const [fy, fm, fd] = patternFrom.split("-").map(Number);
    const start = Date.UTC(fy, fm - 1, fd);
    for (let i = 0; i < totalDays; i++) {
      const dt = new Date(start + i * 86400000);
      const iso = ((dt.getUTCDay() + 6) % 7) + 1;
      if (iso === weekday) countW++;
    }
    const avgPerDay = totalDays > 0 ? sum / totalDays : 0;
    const avgPerWeekday = countW > 0 ? sumW / countW : 0;
    return { avgPerDay, avgPerWeekday };
  }, [patternPoints, patternFrom, patternTo, weekday]);

  const transactions = txQuery.data?.items ?? [];

  const byCategory = useMemo(() => {
    const m = new Map<string, number>();
    for (const tx of transactions) {
      m.set(tx.categoryCode, (m.get(tx.categoryCode) ?? 0) + Number(tx.amount));
    }
    return [...m.entries()].sort((a, b) => b[1] - a[1]);
  }, [transactions]);

  const pctVsAvg = compare.avgPerDay > 0 ? ((dayTotal - compare.avgPerDay) / compare.avgPerDay) * 100 : null;
  const pctVsWeekday = compare.avgPerWeekday > 0 ? ((dayTotal - compare.avgPerWeekday) / compare.avgPerWeekday) * 100 : null;

  const fmtPct = (pct: number) => {
    const sign = pct > 0 ? "+" : "";
    return `${sign}${pct.toFixed(0)}%`;
  };

  return (
    <div className="rounded-md border border-border p-3 space-y-3 dark:border-gray-700">
      <div className="flex items-start gap-2">
        <div className="flex-1">
          <p className="font-medium">{formatDate(day, locale, "EEEE, d MMMM yyyy")}</p>
          <p className="text-lg font-semibold">{formatMoney(dayTotal, currency, locale)}</p>
        </div>
        <Button variant="ghost" onClick={onClose} aria-label={t("analytics.daily_day_close")}>✕</Button>
      </div>

      {dayTotal > 0 && (pctVsAvg !== null || pctVsWeekday !== null) && (
        <div className="text-sm text-gray-600 dark:text-gray-300 space-y-0.5">
          {pctVsAvg !== null && (
            <p>{t("analytics.daily_compare_vs_avg", { pct: fmtPct(pctVsAvg), rangeLabel })}</p>
          )}
          {pctVsWeekday !== null && (
            <p>{t("analytics.daily_compare_vs_weekday", { pct: fmtPct(pctVsWeekday), weekday: weekdayLabel, rangeLabel })}</p>
          )}
        </div>
      )}

      {byCategory.length > 0 && (
        <div>
          <p className="mb-1 text-xs font-semibold uppercase text-gray-500 dark:text-gray-400">
            {t("analytics.daily_day_categories")}
          </p>
          <ul className="space-y-1 text-sm">
            {byCategory.map(([code, amt]) => (
              <li key={code} className="flex items-center justify-between gap-2">
                <span className="flex items-center gap-1">
                  <span aria-hidden>{categoryIcon(code)}</span>
                  <span>{categoryLabelByCode(code, categories, t)}</span>
                </span>
                <span className="tabular-nums">{formatMoney(amt, currency, locale)}</span>
              </li>
            ))}
          </ul>
        </div>
      )}

      <div>
        <p className="mb-1 text-xs font-semibold uppercase text-gray-500 dark:text-gray-400">
          {t("analytics.daily_day_transactions")}
        </p>
        {txQuery.isLoading ? (
          <p className="text-sm text-gray-500 dark:text-gray-400">{t("common.loading")}</p>
        ) : transactions.length === 0 ? (
          <p className="text-sm text-gray-500 dark:text-gray-400">{t("analytics.daily_day_no_transactions")}</p>
        ) : (
          <ul className="space-y-1 text-sm">
            {transactions.map((tx: Transaction) => (
              <li key={tx.id} className="flex items-start justify-between gap-2">
                <span className="flex-1">
                  <span className="block">{tx.description || categoryLabelByCode(tx.categoryCode, categories, t)}</span>
                  {tx.description && (
                    <span className="block text-xs text-gray-500 dark:text-gray-400">
                      {categoryLabelByCode(tx.categoryCode, categories, t)}
                    </span>
                  )}
                </span>
                <span className="tabular-nums">{formatMoney(tx.amount, currency, locale)}</span>
              </li>
            ))}
          </ul>
        )}
      </div>
    </div>
  );
}

function DailyPatternCharts({
  points,
  from,
  to,
  isLoading,
  highlightWeekday,
  currency,
  locale,
}: {
  points: DailyPoint[];
  from: string;
  to: string;
  isLoading: boolean;
  highlightWeekday: number;
  currency: string;
  locale: string;
}) {
  const { t } = useTranslation();

  const dowData = useMemo(() => {
    const sums = new Array(8).fill(0); // index 1..7
    const counts = new Array(8).fill(0);
    if (!from || !to) return [];
    const [fy, fm, fd] = from.split("-").map(Number);
    const start = Date.UTC(fy, fm - 1, fd);
    const total = daysBetweenInclusive(from, to);
    for (let i = 0; i < total; i++) {
      const dt = new Date(start + i * 86400000);
      const iso = ((dt.getUTCDay() + 6) % 7) + 1;
      counts[iso]++;
    }
    for (const p of points) {
      const iso = isoWeekdayFromIso(p.date);
      sums[iso] += Number(p.amount);
    }
    return [1, 2, 3, 4, 5, 6, 7].map((iso) => ({
      iso,
      label: weekdayName(iso, locale, "short"),
      avg: counts[iso] > 0 ? sums[iso] / counts[iso] : 0,
    }));
  }, [points, from, to, locale]);

  const domData = useMemo(() => {
    const sums = new Array(32).fill(0); // 1..31
    const counts = new Array(32).fill(0);
    if (!from || !to) return [];
    const [fy, fm, fd] = from.split("-").map(Number);
    const start = Date.UTC(fy, fm - 1, fd);
    const total = daysBetweenInclusive(from, to);
    for (let i = 0; i < total; i++) {
      const dt = new Date(start + i * 86400000);
      const dom = dt.getUTCDate();
      counts[dom]++;
    }
    for (const p of points) {
      const dom = dayOfMonthFromIso(p.date);
      sums[dom] += Number(p.amount);
    }
    const out: { dom: number; avg: number }[] = [];
    for (let d = 1; d <= 31; d++) {
      out.push({ dom: d, avg: counts[d] > 0 ? sums[d] / counts[d] : 0 });
    }
    return out;
  }, [points, from, to]);

  if (isLoading) {
    return <p className="text-sm text-gray-500 dark:text-gray-400">{t("common.loading")}</p>;
  }

  return (
    <div className="grid grid-cols-1 gap-4 md:grid-cols-2">
      <div>
        <p className="mb-2 text-sm font-medium">{t("analytics.daily_pattern_dow")}</p>
        <div className="h-64">
          <ResponsiveContainer width="100%" height="100%">
            <BarChart data={dowData}>
              <CartesianGrid strokeDasharray="3 3" />
              <XAxis dataKey="label" />
              <YAxis tickFormatter={(v) => shortAmount(Number(v))} />
              <Tooltip formatter={(v) => formatMoney(Number(v), currency, locale)} />
              <Bar dataKey="avg" fill={EXPENSE_HUE}>
                {dowData.map((d) => (
                  <Cell key={d.iso} fill={d.iso === highlightWeekday ? HIGHLIGHT_HUE : EXPENSE_HUE} />
                ))}
              </Bar>
            </BarChart>
          </ResponsiveContainer>
        </div>
      </div>
      <div>
        <p className="mb-2 text-sm font-medium">{t("analytics.daily_pattern_dom")}</p>
        <div className="h-64">
          <ResponsiveContainer width="100%" height="100%">
            <BarChart data={domData}>
              <CartesianGrid strokeDasharray="3 3" />
              <XAxis dataKey="dom" interval={2} />
              <YAxis tickFormatter={(v) => shortAmount(Number(v))} />
              <Tooltip formatter={(v) => formatMoney(Number(v), currency, locale)} />
              <Bar dataKey="avg" fill={EXPENSE_HUE} />
            </BarChart>
          </ResponsiveContainer>
        </div>
        <p className="mt-1 text-xs text-gray-500 dark:text-gray-400">{t("analytics.daily_pattern_dom_note")}</p>
      </div>
    </div>
  );
}
