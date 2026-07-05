import { useTranslation } from "react-i18next";
import { Input, Label, Select } from "@/components/ui/primitives";
import { isoToday } from "@/lib/dates";

export type RangePreset = "3m" | "6m" | "1y" | "2y" | "all" | "custom";

/** Controlled value for {@link RangeSelector}. `from`/`to` are ISO dates, used only when preset is "custom". */
export interface RangeValue {
  preset: RangePreset;
  from: string;
  to: string;
}

export const RANGE_MONTHS: Record<Exclude<RangePreset, "all" | "custom">, number> = {
  "3m": 3,
  "6m": 6,
  "1y": 12,
  "2y": 24,
};

export function defaultRange(preset: RangePreset = "1y"): RangeValue {
  return { preset, from: "", to: isoToday() };
}

function isoMonthsAgo(months: number): string {
  const d = new Date();
  d.setMonth(d.getMonth() - months);
  return d.toISOString().slice(0, 10);
}

/**
 * Resolve a {@link RangeValue} to concrete `from`/`to` bounds (ISO dates).
 * `undefined` means unbounded (`from` omitted = all history, `to` omitted = today).
 * Mirrors the semantics the portfolio evolution chart has always used.
 */
export function resolveRange(v: RangeValue): { from?: string; to?: string } {
  const from =
    v.preset === "all"
      ? undefined
      : v.preset === "custom"
        ? v.from || undefined
        : isoMonthsAgo(RANGE_MONTHS[v.preset]);
  const to = v.preset === "custom" ? v.to || undefined : undefined;
  return { from, to };
}

/**
 * Number of trailing months to request from month-bucketed endpoints (e.g. heatmap),
 * which count back from the current month. 9999 signals "full history".
 */
export function rangeToMonths(v: RangeValue): number {
  if (v.preset === "all") return 9999;
  if (v.preset !== "custom") return RANGE_MONTHS[v.preset];
  if (!v.from) return 9999;
  const f = new Date(v.from);
  if (Number.isNaN(f.getTime())) return 9999;
  const now = new Date();
  return (now.getFullYear() - f.getFullYear()) * 12 + (now.getMonth() - f.getMonth()) + 1;
}

/**
 * Range picker shared by the portfolio evolution chart and analytics panels:
 * preset dropdown (3m / 6m / 1y / 2y / all / custom) plus From–To date inputs when custom.
 * Renders sibling fields meant to sit inside a flex/grid row.
 */
export function RangeSelector({
  value,
  onChange,
  className = "w-40",
}: {
  value: RangeValue;
  onChange: (next: RangeValue) => void;
  className?: string;
}) {
  const { t } = useTranslation();
  return (
    <>
      <div className={className}>
        <Label>{t("portfolio.range")}</Label>
        <Select
          value={value.preset}
          onChange={(e) => onChange({ ...value, preset: e.target.value as RangePreset })}
        >
          <option value="3m">{t("portfolio.range_months", { count: 3 })}</option>
          <option value="6m">{t("portfolio.range_months", { count: 6 })}</option>
          <option value="1y">{t("portfolio.range_year", { count: 1 })}</option>
          <option value="2y">{t("portfolio.range_year", { count: 2 })}</option>
          <option value="all">{t("portfolio.range_all")}</option>
          <option value="custom">{t("portfolio.range_custom")}</option>
        </Select>
      </div>
      {value.preset === "custom" && (
        <>
          <div className={className}>
            <Label>{t("tx.filter_from")}</Label>
            <Input
              type="date"
              value={value.from}
              onChange={(e) => onChange({ ...value, from: e.target.value })}
            />
          </div>
          <div className={className}>
            <Label>{t("tx.filter_to")}</Label>
            <Input
              type="date"
              value={value.to}
              onChange={(e) => onChange({ ...value, to: e.target.value })}
            />
          </div>
        </>
      )}
    </>
  );
}
