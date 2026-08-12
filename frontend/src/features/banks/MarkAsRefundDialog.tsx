import { useEffect, useState } from "react";
import { useTranslation } from "react-i18next";
import { apiErrorMessage } from "@/api/client";
import { useConfirmAsRefund, type PendingMovement } from "@/api/banks";
import { useCategories } from "@/api/catalog";
import { categoryLabel, categoryLabelByCode } from "@/lib/categoryLabel";
import { formatDayMonthYear } from "@/lib/dates";
import { formatMoney } from "@/lib/money";
import { showToast } from "@/lib/toastBus";
import { Button, Card, CardBody, CardHeader, FieldError, Input, Label, Select } from "@/components/ui/primitives";
import { OriginalExpensePicker } from "@/features/transactions/OriginalExpensePicker";
import { categoryDiffers, overRefundWarning } from "@/features/transactions/refundDraft";
import type { Transaction } from "@/api/transactions";

/** A credit that is really money coming back for a past purchase. The bank calls it income; confirming it
 *  here records a negative expense on the item's own date and amount, so the category it left from gets it
 *  back instead of income being inflated. Linking the original is optional but prefills its category. */
export function MarkAsRefundDialog({
  open, householdId, movement, currency, locale, onClose,
}: {
  open: boolean;
  householdId: string;
  movement: PendingMovement;
  currency: string;
  locale: string;
  onClose: () => void;
}) {
  const { t } = useTranslation();
  const { data: categories = [] } = useCategories(householdId);
  const confirmAsRefund = useConfirmAsRefund(householdId);

  const [original, setOriginal] = useState<Transaction | null>(null);
  const [categoryCode, setCategoryCode] = useState("");
  const [description, setDescription] = useState("");
  const [submitError, setSubmitError] = useState<string | null>(null);
  const [categoryError, setCategoryError] = useState<string | null>(null);

  useEffect(() => {
    if (!open) return;
    setOriginal(null);
    setCategoryCode("");
    setDescription("");
    setSubmitError(null);
    setCategoryError(null);
  }, [open, movement.id]);

  useEffect(() => {
    if (!open) return;
    const onKey = (e: KeyboardEvent) => { if (e.key === "Escape") onClose(); };
    document.addEventListener("keydown", onKey);
    return () => document.removeEventListener("keydown", onKey);
  }, [open, onClose]);

  if (!open) return null;

  const expenseCategories = categories
    .filter((c) => c.kind === "expense")
    .sort((a, b) => categoryLabel(a, t).localeCompare(categoryLabel(b, t)));
  // The bank row is a credit; as a refund it is that much money going back into a category.
  const stored = `-${movement.amount}`;
  const overRefund = overRefundWarning(original, stored);

  const submit = async (e: React.FormEvent) => {
    e.preventDefault();
    if (!categoryCode) {
      setCategoryError(t("errors.select_required"));
      return;
    }
    try {
      await confirmAsRefund.mutateAsync({
        id: movement.id,
        input: {
          categoryCode,
          refundOfTransactionId: original?.id ?? null,
          note: description.trim() || null,
        },
      });
      showToast(t("banks.marked_as_refund"), "success");
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
      <Card className="w-full max-w-md bg-overlay" onClick={(e) => e.stopPropagation()}>
        <CardHeader>
          <p className="font-medium">{t("banks.mark_as_refund_title")}</p>
          <p className="mt-1 text-xs text-gray-500 dark:text-gray-400">{t("banks.mark_as_refund_hint")}</p>
        </CardHeader>
        <CardBody>
          <form noValidate onSubmit={submit} className="space-y-3">
            {/* Date and amount carry over from the bank row; only where it belongs is up to the user. */}
            <div className="rounded-md border border-border p-3 text-sm">
              <div className="flex items-start justify-between gap-2">
                <span className="min-w-0 font-medium">{movement.counterparty ?? t("banks.no_counterparty")}</span>
                <span className="shrink-0 font-semibold text-green-600 dark:text-green-400">
                  +{formatMoney(movement.amount, currency, locale)}
                </span>
              </div>
              <p className="mt-0.5 text-xs text-gray-500 dark:text-gray-400">
                {formatDayMonthYear(movement.bookingDate, locale)}
              </p>
            </div>

            <div>
              <Label>{t("tx.refund_of_label")}</Label>
              <OriginalExpensePicker
                householdId={householdId}
                currency={currency}
                locale={locale}
                before={movement.bookingDate}
                selected={original}
                onSelect={(picked) => {
                  setOriginal(picked);
                  if (picked) { setCategoryCode(picked.categoryCode); setCategoryError(null); }
                }}
              />
            </div>

            <div>
              <Label>{t("common.category")}</Label>
              <Select
                value={categoryCode}
                invalid={!!categoryError}
                onChange={(e) => { setCategoryCode(e.target.value); setCategoryError(null); }}
              >
                <option value="">—</option>
                {expenseCategories.map((c) => (
                  <option key={c.code} value={c.code}>{categoryLabel(c, t)}</option>
                ))}
              </Select>
              <FieldError message={categoryError} />
              {categoryDiffers(categoryCode, original) && (
                <p className="mt-1 text-xs text-amber-600 dark:text-amber-400">
                  {t("tx.refund_category_differs", {
                    category: categoryLabelByCode(original!.categoryCode, categories, t),
                  })}
                </p>
              )}
            </div>

            <div>
              <Label>{t("common.description")}</Label>
              <Input value={description} maxLength={500} onChange={(e) => setDescription(e.target.value)} />
            </div>

            {overRefund && (
              <p className="text-xs text-amber-600 dark:text-amber-400">
                {t("tx.refund_over_warning", {
                  refunded: formatMoney(overRefund.refunded.toFixed(2), currency, locale),
                  original: formatMoney(overRefund.original.toFixed(2), currency, locale),
                })}
              </p>
            )}
            {submitError && <FieldError message={submitError} />}

            <div className="flex justify-end gap-2 pt-1">
              <Button type="button" variant="ghost" onClick={onClose}>{t("common.cancel")}</Button>
              <Button type="submit" disabled={confirmAsRefund.isPending}>{t("banks.mark_as_refund_submit")}</Button>
            </div>
          </form>
        </CardBody>
      </Card>
    </div>
  );
}
