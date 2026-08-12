import { useEffect, useMemo, useState } from "react";
import { useTranslation } from "react-i18next";
import { apiErrorMessage } from "@/api/client";
import { useCancelOutMovements, useMergeMovements } from "@/api/banks";
import type { Category } from "@/api/catalog";
import { categoryLabel } from "@/lib/categoryLabel";
import { cn } from "@/lib/cn";
import { formatDayMonthYear } from "@/lib/dates";
import { formatMoney } from "@/lib/money";
import { showToast } from "@/lib/toastBus";
import { Badge, Button, Card, CardBody, CardHeader, FieldError, Input, Label, Select } from "@/components/ui/primitives";
import {
  anyPossibleDuplicate,
  DESCRIPTION_MAX,
  earliestDate,
  netAbsolute,
  netDirection,
  nettingTerms,
  netTotal,
  prefillCategory,
  prefillDescription,
  signedAmount,
  sortSources,
  validateMerge,
  type MergeSource,
} from "./mergeDraft";

interface Props {
  open: boolean;
  householdId: string;
  /** The selected rows, already resolved through the inbox's per-row drafts. */
  sources: MergeSource[];
  categories: Category[];
  currency: string;
  locale: string;
  onClose: () => void;
  onMerged: () => void;
}

/** Collapses 2+ pending items that are really one purchase into a single transaction. Incomes and expenses
 *  NET: a €9.03 charge with a €7.78 refund is a €1.25 expense, and the resulting direction is the sign of
 *  that net — both read-only, since a merge can't invent or drop money. Items that cancel out exactly leave
 *  nothing to record, so they are rejected instead. Arithmetic and prefills live in ./mergeDraft. */
export function MergeMovementsDialog({
  open, householdId, sources, categories, currency, locale, onClose, onMerged,
}: Props) {
  const { t } = useTranslation();
  const merge = useMergeMovements(householdId);
  const cancelOut = useCancelOutMovements(householdId);

  const items = useMemo(() => sortSources(sources), [sources]);
  const direction = netDirection(items);
  const categoryOptions = useMemo(
    () => categories
      .filter((c) => c.kind === direction)
      .sort((a, b) => categoryLabel(a, t).localeCompare(categoryLabel(b, t))),
    [categories, direction, t],
  );

  const [categoryCode, setCategoryCode] = useState("");
  const [date, setDate] = useState(() => earliestDate(items));
  const [description, setDescription] = useState(() => prefillDescription(items));
  const [submitError, setSubmitError] = useState<string | null>(null);

  // Re-seed on each open so a cancelled attempt never leaks into the next one. The category also re-seeds
  // when the net direction flips, since the picker's options changed under it.
  const selectionKey = items.map((i) => i.id).join(",");
  useEffect(() => {
    if (!open) return;
    setCategoryCode(prefillCategory(items, categoryOptions.map((c) => c.code)));
    setDate(earliestDate(items));
    setDescription(prefillDescription(items));
    setSubmitError(null);
  }, [open, selectionKey, direction]);

  // Escape dismisses without writing anything, like the overlay click and Cancel.
  useEffect(() => {
    if (!open) return;
    const onKey = (e: KeyboardEvent) => { if (e.key === "Escape") onClose(); };
    document.addEventListener("keydown", onKey);
    return () => document.removeEventListener("keydown", onKey);
  }, [open, onClose]);

  if (!open) return null;

  const cancelsOut = direction === null;
  const v = validateMerge({ categoryCode, date });
  const money = (value: string) => formatMoney(value, currency, locale);
  const signedClass = (negative: boolean) =>
    negative ? "text-red-600 dark:text-red-400" : "text-green-600 dark:text-green-400";
  const net = netTotal(items);
  // The arithmetic spelled out, so the netting is visible rather than asserted: "−€9.03 + €7.78 = −€1.25".
  // A sign binds to its amount; only the operators between terms get spaces around them.
  const equation = [
    ...nettingTerms(items).map((term, i) => (i === 0
      ? `${term.operator}${money(term.absolute)}`
      : `${term.operator} ${money(term.absolute)}`)),
    `= ${net.startsWith("-") ? "−" : ""}${money(netAbsolute(items))}`,
  ].join(" ");

  const submit = async (e: React.FormEvent) => {
    e.preventDefault();
    const payload = { items: items.map((i) => ({ id: i.id, direction: i.direction })) };
    try {
      if (cancelsOut) {
        const result = await cancelOut.mutateAsync(payload);
        showToast(t("banks.merge_cancel_out_toast", { count: result.rejected }), "success");
      } else {
        if (!v.ok) return;
        await merge.mutateAsync({ ...payload, categoryCode, date, description: description.trim() || null });
        showToast(t("banks.merge_toast", { count: items.length }), "success");
      }
      onMerged();
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
      <Card className="w-full max-w-2xl bg-overlay" onClick={(e) => e.stopPropagation()}>
        <CardHeader>
          <p className="font-medium">{t("banks.merge_title")}</p>
          <p className="mt-1 text-xs text-gray-500 dark:text-gray-400">{t("banks.merge_hint")}</p>
        </CardHeader>
        <CardBody>
          <form noValidate onSubmit={submit} className="space-y-3">
            {/* What is being merged, each with the signed contribution it makes to the net. */}
            <ul className="max-h-56 space-y-2 overflow-y-auto rounded-md border border-border p-2">
              {items.map((item) => {
                const negative = signedAmount(item).isNegative();
                return (
                  <li key={item.id} className="flex flex-wrap items-start justify-between gap-x-2 gap-y-1 text-sm">
                    <div className="min-w-0">
                      <span className="font-medium">{item.counterparty ?? t("banks.no_counterparty")}</span>
                      <p className="mt-0.5 flex flex-wrap items-center gap-x-2 gap-y-1 text-xs text-gray-500 dark:text-gray-400">
                        <span>{formatDayMonthYear(item.bookingDate, locale)}</span>
                        {item.sourceLabel && <Badge tone="sky">{item.sourceLabel}</Badge>}
                        {item.possibleDuplicate && <Badge tone="amber">{t("banks.possible_duplicate")}</Badge>}
                      </p>
                      {item.description && (
                        <p className="mt-0.5 break-words text-xs text-gray-600 dark:text-gray-300">{item.description}</p>
                      )}
                    </div>
                    <span className={cn("shrink-0 font-semibold", signedClass(negative))}>
                      {negative ? "−" : "+"}{money(item.amount)}
                    </span>
                  </li>
                );
              })}
            </ul>

            {/* Informational: the flag is about the individual items, and merging is a fair resolution. */}
            {anyPossibleDuplicate(items) && (
              <p className="text-xs text-amber-600 dark:text-amber-400">{t("banks.merge_duplicate_notice")}</p>
            )}

            <div className="rounded-md border border-border p-3">
              <div className="flex flex-wrap items-center justify-between gap-2">
                <span className="text-sm font-medium">
                  {cancelsOut ? t("banks.merge_net") : t("banks.merge_total")}
                </span>
                {!cancelsOut && (
                  <span className="flex items-center gap-2">
                    <Badge tone="neutral">{t(`common.${direction}`)}</Badge>
                    <span className={cn("font-semibold", signedClass(direction === "expense"))}>
                      {direction === "expense" ? "−" : "+"}{money(netAbsolute(items))}
                    </span>
                  </span>
                )}
              </div>
              <p className="mt-0.5 break-words text-xs text-gray-500 dark:text-gray-400">{equation}</p>

              {cancelsOut ? (
                <p className="mt-2 text-sm text-amber-600 dark:text-amber-400">{t("banks.merge_cancels_out")}</p>
              ) : (
                <>
                  <div className="mt-2 grid gap-2 sm:grid-cols-2">
                    <div>
                      <Label>{t("common.date")}</Label>
                      <Input type="date" value={date} invalid={v.dateMissing} onChange={(e) => setDate(e.target.value)} />
                    </div>
                    <div>
                      <Label>{t("common.category")}</Label>
                      <Select value={categoryCode} invalid={v.categoryMissing} onChange={(e) => setCategoryCode(e.target.value)}>
                        <option value="">{t("banks.uncategorized")}</option>
                        {categoryOptions.map((c) => (
                          <option key={c.code} value={c.code}>{categoryLabel(c, t)}</option>
                        ))}
                      </Select>
                    </div>
                  </div>
                  <div className="mt-2">
                    <Label>{t("common.description")}</Label>
                    <Input
                      value={description}
                      maxLength={DESCRIPTION_MAX}
                      placeholder={t("common.description")}
                      onChange={(e) => setDescription(e.target.value)}
                    />
                  </div>
                  {v.categoryMissing && <FieldError message={t("banks.merge_needs_category")} />}
                </>
              )}
            </div>

            {submitError && <FieldError message={submitError} />}

            <div className="flex justify-end gap-2 pt-1">
              <Button type="button" variant="ghost" onClick={onClose}>{t("common.cancel")}</Button>
              <Button type="submit" disabled={cancelsOut ? cancelOut.isPending : !v.ok || merge.isPending}>
                {cancelsOut
                  ? t("banks.merge_cancel_out_submit")
                  : t("banks.merge_submit", { count: items.length })}
              </Button>
            </div>
          </form>
        </CardBody>
      </Card>
    </div>
  );
}
