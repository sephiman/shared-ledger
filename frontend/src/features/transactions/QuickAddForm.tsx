import { useEffect, useState } from "react";
import { useTranslation } from "react-i18next";
import { useCategories } from "@/api/catalog";
import {
  useCreateTransaction,
  useQuickChips,
  useTransaction,
  useUpdateTransaction,
  type Transaction,
  type TransactionInput,
} from "@/api/transactions";
import { Button, Chip, FieldError, Input, Label, Select, Textarea, Toggle } from "@/components/ui/primitives";
import { isoToday } from "@/lib/dates";
import { formatMoney } from "@/lib/money";
import { apiErrorMessage } from "@/api/client";
import { categoryIcon } from "@/lib/categoryGroup";
import { categoryLabel, categoryLabelByCode } from "@/lib/categoryLabel";
import {
  categoryDiffers,
  isNegativeAmount,
  isPositiveAmount,
  negatedAmount,
  overRefundWarning,
} from "./refundDraft";
import { OriginalExpensePicker } from "./OriginalExpensePicker";

interface QuickAddFormProps {
  householdId: string;
  currency: string;
  initial?: Transaction;
  onSaved?: () => void;
  onCancel?: () => void;
}

export function QuickAddForm({ householdId, currency, initial, onSaved, onCancel }: QuickAddFormProps) {
  const { t, i18n } = useTranslation();
  const { data: categories = [] } = useCategories(householdId);
  const { data: chips = [] } = useQuickChips(householdId);
  const create = useCreateTransaction(householdId);
  const update = useUpdateTransaction(householdId);
  const isEdit = !!initial;
  const [direction, setDirection] = useState<"income" | "expense">(initial?.direction ?? "expense");
  const [amount, setAmount] = useState(initial?.amount ?? "");
  const [categoryCode, setCategoryCode] = useState(initial?.categoryCode ?? "");
  const [date, setDate] = useState(initial?.occurrenceDate ?? isoToday());
  const [description, setDescription] = useState(initial?.description ?? "");
  const [isRefund, setIsRefund] = useState(initial?.isRefund ?? false);
  // The linked purchase, kept as a whole row so the form can compare category and amount against it.
  const [refundOf, setRefundOf] = useState<Transaction | null>(null);
  const [refundOfId, setRefundOfId] = useState<string | null>(initial?.refundOfTransactionId ?? null);
  const [error, setError] = useState<string | null>(null);
  const [fieldErrors, setFieldErrors] = useState<{ date?: string; amount?: string; categoryCode?: string }>({});

  const eligibleCategories = categories
    .filter((c) => c.kind === direction)
    .slice()
    .sort((a, b) => categoryLabel(a, t).localeCompare(categoryLabel(b, t), i18n.language, { sensitivity: "base" }));

  // Editing an existing refund: fetch the purchase it points at so the picker opens on it.
  const linked = useTransaction(householdId, initial?.refundOfTransactionId ?? null);
  useEffect(() => {
    if (linked.data && linked.data.id === refundOfId) setRefundOf(linked.data);
  }, [linked.data, refundOfId]);

  const overRefund = overRefundWarning(refundOf, amount);
  const pending = isEdit ? update.isPending : create.isPending;

  return (
    <form
      noValidate
      onSubmit={async (e) => {
        e.preventDefault();
        setError(null);
        const next: typeof fieldErrors = {};
        if (!date) next.date = t("errors.field_required");
        const amt = Number(amount);
        if (!amount.trim()) next.amount = t("errors.field_required");
        else if (!Number.isFinite(amt)) next.amount = t("errors.number_required");
        // A refund is money coming back, so it is stored negative; everything else is positive.
        else if (isRefund && amt >= 0) next.amount = t("errors.amount_negative_refund");
        else if (!isRefund && amt <= 0) next.amount = t("errors.amount_positive");
        if (!categoryCode) next.categoryCode = t("errors.select_required");
        if (Object.keys(next).length > 0) {
          setFieldErrors(next);
          return;
        }
        setFieldErrors({});
        try {
          const input: TransactionInput = {
            occurrenceDate: date,
            direction,
            categoryCode,
            amount,
            description: description || null,
            isRefund,
            refundOfTransactionId: isRefund ? refundOfId : null,
          };
          if (isEdit && initial) {
            await update.mutateAsync({ id: initial.id, input });
          } else {
            await create.mutateAsync(input);
            setAmount("");
            setDescription("");
          }
          onSaved?.();
        } catch (err) {
          setError(apiErrorMessage(err, t));
        }
      }}
      className="space-y-3"
    >
      {!isEdit && chips.length > 0 && (
        <div>
          <p className="mb-2 text-xs text-gray-500 dark:text-gray-400">{t("tx.chips_title")}</p>
          <div className="flex flex-wrap gap-2">
            {chips.map((c) => (
              <Chip
                key={c.categoryCode}
                active={categoryCode === c.categoryCode}
                onClick={() => {
                  const cat = categories.find((cc) => cc.code === c.categoryCode);
                  if (cat) {
                    setDirection(cat.kind);
                    setCategoryCode(c.categoryCode);
                  }
                }}
              >
                <span className="mr-1" aria-hidden>{categoryIcon(c.categoryCode)}</span>
                {categoryLabelByCode(c.categoryCode, categories, t)}
              </Chip>
            ))}
          </div>
        </div>
      )}

      <div className="grid grid-cols-2 gap-3">
        <div>
          <Label>{t("common.direction")}</Label>
          {/* A refund is always an expense — a negative one — so the choice is settled while it is on. */}
          <Select
            value={direction}
            disabled={isRefund}
            onChange={(e) => { setDirection(e.target.value as "income" | "expense"); setCategoryCode(""); }}
          >
            <option value="expense">{t("common.expense")}</option>
            <option value="income">{t("common.income")}</option>
          </Select>
        </div>
        <div>
          <Label>{t("common.date")}</Label>
          <Input
            type="date"
            value={date}
            invalid={!!fieldErrors.date}
            onChange={(e) => { setDate(e.target.value); if (fieldErrors.date) setFieldErrors({ ...fieldErrors, date: undefined }); }}
          />
          <FieldError message={fieldErrors.date} />
        </div>
      </div>

      {/* Only expenses can be refunds; the toggle stays visible on an existing refund so it can be undone. */}
      {(direction === "expense" || isRefund) && (
        <div className="rounded-md border border-border p-3">
          <Toggle
            label={t("tx.is_refund")}
            checked={isRefund}
            onChange={(next) => {
              setIsRefund(next);
              setFieldErrors((f) => ({ ...f, amount: undefined }));
              if (next) {
                setDirection("expense");
                // Money coming back is stored negative; flipping the sign isn't the user's job.
                if (isPositiveAmount(amount)) setAmount(negatedAmount(amount));
              } else {
                setRefundOf(null);
                setRefundOfId(null);
                // Back to an ordinary transaction, which is positive again.
                if (isNegativeAmount(amount)) setAmount(negatedAmount(amount));
              }
            }}
          />
          {isRefund && (
            <div className="mt-2 space-y-2">
              <p className="text-xs text-gray-500 dark:text-gray-400">{t("tx.refund_amount_helper")}</p>
              <div>
                <Label>{t("tx.refund_of_label")}</Label>
                <OriginalExpensePicker
                  householdId={householdId}
                  currency={currency}
                  locale={i18n.language}
                  before={date}
                  selected={refundOf}
                  onSelect={(original) => {
                    setRefundOf(original);
                    setRefundOfId(original?.id ?? null);
                    // The purchase's own category is where the money should go back, unless told otherwise.
                    if (original) setCategoryCode(original.categoryCode);
                  }}
                />
              </div>
              {categoryDiffers(categoryCode, refundOf) && (
                <p className="text-xs text-amber-600 dark:text-amber-400">
                  {t("tx.refund_category_differs", {
                    category: categoryLabelByCode(refundOf!.categoryCode, categories, t),
                  })}
                </p>
              )}
              {overRefund && (
                <p className="text-xs text-amber-600 dark:text-amber-400">
                  {t("tx.refund_over_warning", {
                    refunded: formatMoney(overRefund.refunded.toFixed(2), currency, i18n.language),
                    original: formatMoney(overRefund.original.toFixed(2), currency, i18n.language),
                  })}
                </p>
              )}
            </div>
          )}
        </div>
      )}

      <div>
        <Label>{t("common.amount")}</Label>
        <div className="flex items-start gap-2">
          <Input
            type="number"
            inputMode="decimal"
            step="0.01"
            min={isRefund ? undefined : "0.01"}
            value={amount}
            invalid={!!fieldErrors.amount}
            onChange={(e) => { setAmount(e.target.value); if (fieldErrors.amount) setFieldErrors({ ...fieldErrors, amount: undefined }); }}
            className="text-lg"
          />
          {/* Typing the amount that came back is the natural thing to do; this turns it into a refund. */}
          {isRefund && isPositiveAmount(amount) && (
            <Button type="button" variant="secondary" className="shrink-0" onClick={() => setAmount(negatedAmount(amount))}>
              {t("tx.make_negative")}
            </Button>
          )}
        </div>
        <FieldError message={fieldErrors.amount} />
      </div>

      <div>
        <Label>{t("common.category")}</Label>
        <Select
          value={categoryCode}
          invalid={!!fieldErrors.categoryCode}
          onChange={(e) => { setCategoryCode(e.target.value); if (fieldErrors.categoryCode) setFieldErrors({ ...fieldErrors, categoryCode: undefined }); }}
        >
          <option value="">—</option>
          {eligibleCategories.map((c) => (
            <option key={c.code} value={c.code}>
              {categoryLabel(c, t)} {categoryIcon(c.code)}
            </option>
          ))}
        </Select>
        <FieldError message={fieldErrors.categoryCode} />
      </div>

      <div>
        <Label>{t("common.description")}</Label>
        <Textarea value={description ?? ""} onChange={(e) => setDescription(e.target.value)} rows={2} maxLength={500} />
      </div>

      <FieldError message={error} />

      <div className="flex gap-2">
        <Button type="submit" className="flex-1" disabled={pending}>
          {t("common.save")}
        </Button>
        {isEdit && onCancel && (
          <Button type="button" variant="ghost" onClick={onCancel} disabled={pending}>
            {t("common.cancel")}
          </Button>
        )}
      </div>
    </form>
  );
}
