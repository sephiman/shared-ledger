import { useEffect, useState } from "react";
import { useTranslation } from "react-i18next";
import { apiErrorMessage } from "@/api/client";
import { useCategories } from "@/api/catalog";
import { useCreateTransaction, type Transaction } from "@/api/transactions";
import { categoryLabel, categoryLabelByCode } from "@/lib/categoryLabel";
import { formatDayMonthYear, isoToday } from "@/lib/dates";
import { formatMoney } from "@/lib/money";
import { showToast } from "@/lib/toastBus";
import { Button, Card, CardBody, CardHeader, FieldError, Input, Label, Select } from "@/components/ui/primitives";
import { categoryDiffers, negatedAmount, overRefundWarning } from "./refundDraft";

/** Records money that came back for a purchase without a bank row behind it — cash back at the till, a
 *  transfer from a friend. The original is fixed (it is the row the action was invoked from); the amount
 *  is typed as the sum that came back and stored negative, which is what nets the category. */
export function RegisterRefundDialog({
  open, householdId, original, currency, locale, onClose,
}: {
  open: boolean;
  householdId: string;
  original: Transaction;
  currency: string;
  locale: string;
  onClose: () => void;
}) {
  const { t } = useTranslation();
  const { data: categories = [] } = useCategories(householdId);
  const create = useCreateTransaction(householdId);

  const [amount, setAmount] = useState("");
  const [date, setDate] = useState(isoToday());
  const [categoryCode, setCategoryCode] = useState(original.categoryCode);
  const [description, setDescription] = useState("");
  const [submitError, setSubmitError] = useState<string | null>(null);
  const [amountError, setAmountError] = useState<string | null>(null);

  useEffect(() => {
    if (!open) return;
    setAmount("");
    setDate(isoToday());
    setCategoryCode(original.categoryCode);
    setDescription("");
    setSubmitError(null);
    setAmountError(null);
  }, [open, original.id]);

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
  // Typed as what came back; the ledger stores it negative.
  const stored = negatedAmount(amount);
  const overRefund = overRefundWarning(original, stored);

  const submit = async (e: React.FormEvent) => {
    e.preventDefault();
    const value = Number(amount.replace(",", "."));
    if (!amount.trim() || !Number.isFinite(value) || value <= 0) {
      setAmountError(t("errors.amount_positive"));
      return;
    }
    setAmountError(null);
    try {
      await create.mutateAsync({
        occurrenceDate: date,
        direction: "expense",
        categoryCode,
        amount: stored,
        description: description.trim() || null,
        isRefund: true,
        refundOfTransactionId: original.id,
      });
      showToast(t("tx.refund_saved"), "success");
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
          <p className="font-medium">{t("tx.register_refund")}</p>
          <p className="mt-1 text-xs text-gray-500 dark:text-gray-400">{t("tx.register_refund_hint")}</p>
        </CardHeader>
        <CardBody>
          <form noValidate onSubmit={submit} className="space-y-3">
            {/* The purchase being refunded, fixed: the action was invoked from its row. */}
            <div className="rounded-md border border-border p-3 text-sm">
              <div className="flex items-start justify-between gap-2">
                <span className="min-w-0 font-medium">{original.description ?? t("tx.no_description")}</span>
                <span className="shrink-0 font-semibold">{formatMoney(original.amount, currency, locale)}</span>
              </div>
              <p className="mt-0.5 text-xs text-gray-500 dark:text-gray-400">
                {[formatDayMonthYear(original.occurrenceDate, locale),
                  categoryLabelByCode(original.categoryCode, categories, t)].join(" · ")}
              </p>
              {original.refundedTotal && (
                <p className="mt-0.5 text-xs text-gray-500 dark:text-gray-400">
                  {t("tx.refunded_total", { amount: formatMoney(original.refundedTotal, currency, locale) })}
                </p>
              )}
            </div>

            <div className="grid gap-2 sm:grid-cols-2">
              <div>
                <Label>{t("tx.refund_amount")}</Label>
                <Input
                  type="number"
                  inputMode="decimal"
                  step="0.01"
                  min="0.01"
                  value={amount}
                  invalid={!!amountError}
                  onChange={(e) => { setAmount(e.target.value); setAmountError(null); }}
                />
                <FieldError message={amountError} />
              </div>
              <div>
                <Label>{t("common.date")}</Label>
                <Input type="date" value={date} onChange={(e) => setDate(e.target.value)} />
              </div>
            </div>

            <div>
              <Label>{t("common.category")}</Label>
              <Select value={categoryCode} onChange={(e) => setCategoryCode(e.target.value)}>
                {expenseCategories.map((c) => (
                  <option key={c.code} value={c.code}>{categoryLabel(c, t)}</option>
                ))}
              </Select>
              {categoryDiffers(categoryCode, original) && (
                <p className="mt-1 text-xs text-amber-600 dark:text-amber-400">
                  {t("tx.refund_category_differs", {
                    category: categoryLabelByCode(original.categoryCode, categories, t),
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
              <Button type="submit" disabled={create.isPending}>{t("common.save")}</Button>
            </div>
          </form>
        </CardBody>
      </Card>
    </div>
  );
}
