import { useTranslation } from "react-i18next";
import { Input, Label, Select } from "@/components/ui/primitives";
import { formatMonthSpan, snappedMonthSpan, type RangePreset, type RangeValue } from "@/lib/range";

/** Range picker shared by the analytics panels and the two evolution charts: preset dropdown plus From–To
 *  inputs when custom. Renders sibling fields meant to sit inside a flex/grid row.
 *
 *  `granularity="month"` marks a view whose data is bucketed by month: the custom range still uses day
 *  pickers (universally supported, and usable on phones, which `type="month"` is not), but it snaps to
 *  whole months and says so underneath. */
export function RangeSelector({
  value,
  onChange,
  className = "w-40",
  granularity = "day",
}: {
  value: RangeValue;
  onChange: (next: RangeValue) => void;
  className?: string;
  granularity?: "day" | "month";
}) {
  const { t, i18n } = useTranslation();
  const snapped = granularity === "month" ? snappedMonthSpan(value) : null;
  const invalidCustom = value.preset === "custom" && Boolean(value.from) && Boolean(value.to) && value.from > value.to;

  return (
    <>
      <div className={className}>
        <Label>{t("range.label")}</Label>
        <Select
          value={value.preset}
          onChange={(e) => onChange({ ...value, preset: e.target.value as RangePreset })}
        >
          <option value="3m">{t("range.months", { count: 3 })}</option>
          <option value="6m">{t("range.months", { count: 6 })}</option>
          <option value="ytd">{t("range.ytd")}</option>
          <option value="1y">{t("range.year", { count: 1 })}</option>
          <option value="2y">{t("range.year", { count: 2 })}</option>
          <option value="all">{t("range.all")}</option>
          <option value="custom">{t("range.custom")}</option>
        </Select>
      </div>
      {value.preset === "custom" && (
        <>
          <div className={className}>
            <Label>{t("range.from")}</Label>
            <Input
              type="date"
              value={value.from}
              invalid={invalidCustom}
              onChange={(e) => onChange({ ...value, from: e.target.value })}
            />
          </div>
          <div className={className}>
            <Label>{t("range.to")}</Label>
            <Input
              type="date"
              value={value.to}
              invalid={invalidCustom}
              onChange={(e) => onChange({ ...value, to: e.target.value })}
            />
          </div>
          {/* `order-last` keeps the note below the whole field row rather than splitting it, since a
              full-width child would otherwise break the flex line wherever the selector happens to sit. */}
          {invalidCustom ? (
            <p className="order-last w-full text-xs text-red-600 dark:text-red-400">{t("range.invalid_order")}</p>
          ) : (
            snapped && (
              <p className="order-last w-full text-xs text-gray-500 dark:text-gray-400">
                {t("range.snapped_to_months", {
                  span: formatMonthSpan(snapped.from, snapped.to, i18n.language),
                  count: snapped.months,
                })}
              </p>
            )
          )}
        </>
      )}
    </>
  );
}
