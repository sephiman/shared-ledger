import { useEffect, useState } from "react";
import { useTranslation } from "react-i18next";
import { apiErrorMessage } from "@/api/client";
import { useSplitMovement, type Direction, type PendingMovement } from "@/api/banks";
import type { Category } from "@/api/catalog";
import { categoryLabel } from "@/lib/categoryLabel";
import { cn } from "@/lib/cn";
import { formatDate } from "@/lib/dates";
import { formatMoney } from "@/lib/money";
import { showToast } from "@/lib/toastBus";
import { Badge, Button, Card, CardBody, CardHeader, FieldError, Input, Label, Select } from "@/components/ui/primitives";
import {
  addRow,
  initialRows,
  percentDisplay,
  removeRow,
  setAmount,
  setPercent,
  toSplitParts,
  updateRow,
  validate,
  type SplitRowError,
} from "./splitDraft";

interface Props {
  open: boolean;
  householdId: string;
  movement: PendingMovement;
  categories: Category[];
  currency: string;
  locale: string;
  /** Inherited by every part: a split can't mix income and expense. */
  direction: Direction;
  /** The row's category and description, prefilled into every part. */
  categoryCode: string;
  description: string;
  onClose: () => void;
}

const ROW_ERROR_KEY: Record<Exclude<SplitRowError, null>, string> = {
  amount_invalid: "errors.amount_positive",
  amount_nothing_left: "banks.split_nothing_left",
  category_missing: "banks.split_needs_category",
};

/** Divides one movement into 2+ transactions adding up to its total exactly. Two rows auto-balance; three
 *  or more must clear the remainder line before saving. Arithmetic lives in ./splitDraft. */
export function SplitMovementDialog({
  open, householdId, movement, categories, currency, locale, direction, categoryCode, description, onClose,
}: Props) {
  const { t, i18n } = useTranslation();
  const split = useSplitMovement(householdId);

  const [rows, setRows] = useState(() => initialRows(movement.amount, categoryCode, description));
  const [submitError, setSubmitError] = useState<string | null>(null);

  // Re-seed on each open so a cancelled attempt never leaks into the next one.
  useEffect(() => {
    if (!open) return;
    setRows(initialRows(movement.amount, categoryCode, description));
    setSubmitError(null);
  }, [open, movement.id, movement.amount]);

  // Escape dismisses without writing anything, like the overlay click and Cancel.
  useEffect(() => {
    if (!open) return;
    const onKey = (e: KeyboardEvent) => { if (e.key === "Escape") onClose(); };
    document.addEventListener("keydown", onKey);
    return () => document.removeEventListener("keydown", onKey);
  }, [open, onClose]);

  if (!open) return null;

  const total = movement.amount;
  const v = validate(rows, total);
  const income = direction === "income";
  const categoryOptions = categories
    .filter((c) => c.kind === direction)
    .sort((a, b) => categoryLabel(a, t).localeCompare(categoryLabel(b, t)));

  const submit = async (e: React.FormEvent) => {
    e.preventDefault();
    if (!v.ok) return;
    try {
      await split.mutateAsync({ id: movement.id, input: { parts: toSplitParts(rows), direction } });
      showToast(t("banks.split_toast", { count: rows.length }), "success");
      onClose();
    } catch (err) {
      setSubmitError(apiErrorMessage(err, t));
    }
  };

  return (
    <div
      className="fixed inset-0 z-50 flex items-start justify-center overflow-y-auto bg-black/40 px-2 py-6 sm:items-center sm:px-4 sm:py-8"
      role="dialog"
      aria-modal="true"
      onClick={onClose}
    >
      <Card className="w-full max-w-2xl" onClick={(e) => e.stopPropagation()}>
        <CardHeader>
          <p className="font-medium">{t("banks.split_title")}</p>
          <p className="mt-1 text-xs text-gray-500 dark:text-gray-400">{t("banks.split_hint")}</p>
        </CardHeader>
        <CardBody>
          <form noValidate onSubmit={submit} className="space-y-3">
            {/* Read-only: the date and total carry over unchanged, the direction to every part. */}
            <div className="rounded-md border border-border p-3 text-sm dark:border-gray-700">
              <div className="flex items-start justify-between gap-2">
                <span className="min-w-0 font-medium">{movement.counterparty ?? t("banks.no_counterparty")}</span>
                <span className={cn("shrink-0 font-semibold", income ? "text-green-600 dark:text-green-400" : "text-red-600 dark:text-red-400")}>
                  {income ? "+" : "−"}{formatMoney(total, currency, locale)}
                </span>
              </div>
              <p className="mt-0.5 flex flex-wrap items-center gap-x-2 gap-y-1 text-xs text-gray-500 dark:text-gray-400">
                <span>{formatDate(movement.bookingDate, i18n.language)}</span>
                <Badge tone="neutral">{t(`common.${direction}`)}</Badge>
              </p>
            </div>

            <ul className="space-y-3">
              {rows.map((row, index) => {
                const error = v.rowErrors[index];
                const amountInvalid = error === "amount_invalid" || error === "amount_nothing_left";
                return (
                  <li key={row.key} className="rounded-md border border-border p-3 dark:border-gray-700">
                    <div className="mb-2 flex items-center justify-between gap-2">
                      <span className="text-xs font-medium text-gray-500 dark:text-gray-400">
                        {t("banks.split_part", { n: index + 1 })}
                      </span>
                      {/* Two is the floor: one part would just be an ordinary confirm. */}
                      <Button
                        type="button"
                        variant="ghost"
                        className="px-2 py-1 text-xs"
                        disabled={rows.length <= 2}
                        aria-label={t("banks.split_remove_part", { n: index + 1 })}
                        onClick={() => setRows((prev) => removeRow(prev, index))}
                      >
                        {t("common.delete")}
                      </Button>
                    </div>
                    {/* Side by side even on phones — two views of one value. */}
                    <div className="grid grid-cols-2 gap-2">
                      <div>
                        <Label>{t("common.amount")}</Label>
                        <Input
                          value={row.amount}
                          inputMode="decimal"
                          placeholder="0,00"
                          invalid={amountInvalid}
                          aria-label={t("banks.split_part_amount", { n: index + 1 })}
                          onChange={(e) => setRows((prev) => setAmount(prev, index, e.target.value, total))}
                        />
                      </div>
                      <div>
                        <Label>%</Label>
                        <Input
                          value={percentDisplay(row, total)}
                          inputMode="decimal"
                          placeholder="0,00"
                          aria-label={t("banks.split_part_percent", { n: index + 1 })}
                          onChange={(e) => setRows((prev) => setPercent(prev, index, e.target.value, total))}
                        />
                      </div>
                    </div>
                    <div className="mt-2">
                      <Label>{t("common.category")}</Label>
                      <Select
                        value={row.categoryCode}
                        invalid={error === "category_missing"}
                        onChange={(e) => setRows((prev) => updateRow(prev, index, { categoryCode: e.target.value }))}
                      >
                        <option value="">{t("banks.uncategorized")}</option>
                        {categoryOptions.map((c) => (
                          <option key={c.code} value={c.code}>{categoryLabel(c, t)}</option>
                        ))}
                      </Select>
                    </div>
                    <div className="mt-2">
                      <Label>{t("common.description")}</Label>
                      <Input
                        value={row.description}
                        placeholder={t("common.description")}
                        onChange={(e) => setRows((prev) => updateRow(prev, index, { description: e.target.value }))}
                      />
                    </div>
                    {error && <FieldError message={t(ROW_ERROR_KEY[error])} />}
                  </li>
                );
              })}
            </ul>

            <div className="flex flex-wrap items-center justify-between gap-2">
              <Button type="button" variant="secondary" onClick={() => setRows((prev) => addRow(prev, categoryCode, description))}>
                {t("banks.split_add_part")}
              </Button>
              {/* 3+ rows only — see splitDraft's showRemainder. */}
              {v.showRemainder && !v.balanced && (
                <span className={cn("text-sm font-medium", v.remainder.startsWith("-") ? "text-red-600 dark:text-red-400" : "text-amber-600 dark:text-amber-400")}>
                  {v.remainder.startsWith("-")
                    ? t("banks.split_over_total", { amount: formatMoney(v.remainder.slice(1), currency, locale) })
                    : t("banks.split_left_to_assign", { amount: formatMoney(v.remainder, currency, locale) })}
                </span>
              )}
              {v.ok && (
                <span className="text-sm font-medium text-green-600 dark:text-green-400">{t("banks.split_balanced")}</span>
              )}
            </div>

            {submitError && <FieldError message={submitError} />}

            <div className="flex justify-end gap-2 pt-1">
              <Button type="button" variant="ghost" onClick={onClose}>{t("common.cancel")}</Button>
              <Button type="submit" disabled={!v.ok || split.isPending}>
                {t("banks.split_submit", { count: rows.length })}
              </Button>
            </div>
          </form>
        </CardBody>
      </Card>
    </div>
  );
}
