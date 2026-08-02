import { useMemo, useState } from "react";
import { useNavigate } from "react-router-dom";
import { useTranslation } from "react-i18next";
import { useActiveHousehold } from "@/auth/AuthContext";
import { useHeatmap, type HeatmapCategoryRow, type HeatmapMonth } from "@/api/analytics";
import { useCategories } from "@/api/catalog";
import { Card, CardBody, CardHeader, Label, Select } from "@/components/ui/primitives";
import { RangeSelector } from "@/components/ui/RangeSelector";
import { isRangeComplete, monthRangeParams } from "@/lib/range";
import { useRangeState } from "@/lib/useRangeState";
import { formatMoney } from "@/lib/money";
import { monthName } from "@/lib/dates";
import { categoryIcon, groupIcon } from "@/lib/categoryGroup";
import { categoryLabelByCode } from "@/lib/categoryLabel";
import { hexWithAlpha } from "@/lib/color";
import type { Category } from "@/api/catalog";

type Direction = "expense" | "income";

const EXPENSE_HUE = "#0ea5e9";
const INCOME_HUE = "#22c55e";

function lastDayOfMonth(year: number, month: number): string {
  const d = new Date(Date.UTC(year, month, 0));
  return d.toISOString().slice(0, 10);
}

function firstDayOfMonth(year: number, month: number): string {
  return `${year}-${String(month).padStart(2, "0")}-01`;
}

export function HeatmapTab() {
  const { t, i18n } = useTranslation();
  const household = useActiveHousehold();
  const navigate = useNavigate();
  const [range, setRange] = useRangeState("analytics.heatmap", "2y");
  const [direction, setDirection] = useState<Direction>("expense");

  const rangeReady = isRangeComplete(range);
  const { data, isLoading } = useHeatmap(
    household.householdId,
    { ...monthRangeParams(range), direction },
    rangeReady,
  );
  const { data: categories = [] } = useCategories(household.householdId);

  const baseColor = direction === "expense" ? EXPENSE_HUE : INCOME_HUE;

  const visibleRows = useMemo(
    () => (data?.categories ?? []).filter((r) => r.values.some((v) => v != null && Number(v) !== 0)),
    [data],
  );

  return (
    <div className="space-y-4">
      <Card>
        <CardHeader>
          <p className="font-medium">{t("analytics.heatmap")}</p>
          <p className="mt-1 text-sm text-gray-500 dark:text-gray-400">{t("analytics.heatmap_description")}</p>
        </CardHeader>
        <CardBody className="space-y-4">
          <div className="flex flex-wrap items-end gap-3">
            <RangeSelector value={range} onChange={setRange} granularity="month" />
            <div className="w-40">
              <Label>{t("common.direction")}</Label>
              <Select value={direction} onChange={(e) => setDirection(e.target.value as Direction)}>
                <option value="expense">{t("analytics.heatmap_direction_expense")}</option>
                <option value="income">{t("analytics.heatmap_direction_income")}</option>
              </Select>
            </div>
          </div>

          {rangeReady && isLoading && <p className="text-gray-500 dark:text-gray-400">{t("common.loading")}</p>}

          {!isLoading && data && visibleRows.length > 0 && (
            <HeatmapGrid
              months={data.months}
              rows={visibleRows}
              categories={categories}
              currency={household.currency}
              locale={i18n.language}
              baseColor={baseColor}
              onCellClick={(categoryCode, m) => {
                const from = firstDayOfMonth(m.year, m.month);
                const to = lastDayOfMonth(m.year, m.month);
                const params = new URLSearchParams({
                  from,
                  to,
                  direction,
                  categoryCode,
                });
                navigate(`/transactions?${params.toString()}`);
              }}
            />
          )}

          {!isLoading && data && visibleRows.length === 0 && (
            <p className="text-gray-500 dark:text-gray-400">{t("common.empty")}</p>
          )}
        </CardBody>
      </Card>
    </div>
  );
}

function HeatmapGrid({
  months,
  rows,
  categories,
  currency,
  locale,
  baseColor,
  onCellClick,
}: {
  months: HeatmapMonth[];
  rows: HeatmapCategoryRow[];
  categories: Category[];
  currency: string;
  locale: string;
  baseColor: string;
  onCellClick: (categoryCode: string, month: HeatmapMonth) => void;
}) {
  const { t } = useTranslation();

  const rowMax = useMemo(() => {
    return rows.map((r) => {
      let max = 0;
      for (const v of r.values) {
        if (v != null) {
          const n = Number(v);
          if (n > max) max = n;
        }
      }
      return max;
    });
  }, [rows]);

  const groupedRows = useMemo(() => {
    const result: { groupCode: string | null; items: { idx: number; row: HeatmapCategoryRow }[] }[] = [];
    rows.forEach((row, idx) => {
      const current = result[result.length - 1];
      if (current && current.groupCode === row.groupCode) {
        current.items.push({ idx, row });
      } else {
        result.push({ groupCode: row.groupCode, items: [{ idx, row }] });
      }
    });
    return result;
  }, [rows]);

  const colWidth = "minmax(56px, 1fr)";
  const gridTemplate = `180px repeat(${months.length}, ${colWidth})`;

  return (
    <div className="overflow-x-auto">
      <div className="inline-block min-w-full">
        <div className="grid text-xs" style={{ gridTemplateColumns: gridTemplate }}>
          <div className="sticky left-0 z-10 bg-white px-2 py-1 font-medium text-gray-500 dark:bg-gray-800 dark:text-gray-400">
            {t("common.category")}
          </div>
          {months.map((m) => (
            <div key={`${m.year}-${m.month}`} className="px-1 py-1 text-center text-gray-500 dark:text-gray-400">
              {monthName(m.month, locale, "short")} {String(m.year).slice(2)}
            </div>
          ))}

          {groupedRows.map((group, gIdx) => (
            <HeatmapGroup
              key={`${group.groupCode}-${gIdx}`}
              isFirst={gIdx === 0}
              groupCode={group.groupCode}
              months={months}
              items={group.items}
              rowMax={rowMax}
              baseColor={baseColor}
              categories={categories}
              currency={currency}
              locale={locale}
              onCellClick={onCellClick}
            />
          ))}
        </div>
      </div>
    </div>
  );
}

function HeatmapGroup({
  isFirst,
  groupCode,
  months,
  items,
  rowMax,
  baseColor,
  categories,
  currency,
  locale,
  onCellClick,
}: {
  isFirst: boolean;
  groupCode: string | null;
  months: HeatmapMonth[];
  items: { idx: number; row: HeatmapCategoryRow }[];
  rowMax: number[];
  baseColor: string;
  categories: Category[];
  currency: string;
  locale: string;
  onCellClick: (categoryCode: string, month: HeatmapMonth) => void;
}) {
  const { t } = useTranslation();
  const totalCols = months.length + 1;
  return (
    <>
      {groupCode && (
        <div
          className={`sticky left-0 z-10 bg-gray-50 px-2 py-1 font-semibold text-gray-600 dark:bg-gray-900 dark:text-gray-300 ${isFirst ? "" : "border-t border-border"}`}
          style={{ gridColumn: `1 / span ${totalCols}` }}
        >
          <span className="mr-1.5" aria-hidden>{groupIcon(groupCode)}</span>
          {t(`category_group.${groupCode}`)}
        </div>
      )}
      {items.map(({ idx, row }) => {
        const max = rowMax[idx];
        return (
          <RowCells
            key={row.categoryCode}
            row={row}
            max={max}
            months={months}
            baseColor={baseColor}
            categories={categories}
            currency={currency}
            locale={locale}
            onCellClick={onCellClick}
          />
        );
      })}
    </>
  );
}

function RowCells({
  row,
  max,
  months,
  baseColor,
  categories,
  currency,
  locale,
  onCellClick,
}: {
  row: HeatmapCategoryRow;
  max: number;
  months: HeatmapMonth[];
  baseColor: string;
  categories: Category[];
  currency: string;
  locale: string;
  onCellClick: (categoryCode: string, month: HeatmapMonth) => void;
}) {
  const { t } = useTranslation();
  const rowLabel = categoryLabelByCode(row.categoryCode, categories, t);
  return (
    <>
      <div className="sticky left-0 z-10 truncate bg-white px-2 py-1 dark:bg-gray-800">
        <span className="mr-1.5" aria-hidden>{categoryIcon(row.categoryCode)}</span>
        {rowLabel}
      </div>
      {row.values.map((v, colIdx) => {
        const m = months[colIdx];
        if (v == null) {
          return (
            <div
              key={colIdx}
              className="px-1 py-1 text-center text-gray-300 dark:text-gray-600"
              aria-label={t("common.empty")}
            >
              ·
            </div>
          );
        }
        const num = Number(v);
        const intensity = max > 0 ? Math.max(0.1, num / max) : 0;
        const bg = hexWithAlpha(baseColor, intensity);
        const label = `${rowLabel} · ${monthName(m.month, locale, "short")} ${m.year} · ${formatMoney(num, currency, locale)}`;
        return (
          <button
            key={colIdx}
            type="button"
            onClick={() => onCellClick(row.categoryCode, m)}
            title={label}
            className="m-0.5 rounded px-1 py-1 text-center text-xs text-gray-900 transition-opacity hover:opacity-80 focus:outline-none focus:ring-1 focus:ring-primary dark:text-white"
            style={{ backgroundColor: bg }}
            aria-label={label}
          >
            {num >= 1000 ? `${(num / 1000).toFixed(1)}k` : Math.round(num)}
          </button>
        );
      })}
    </>
  );
}
