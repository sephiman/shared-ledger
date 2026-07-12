import { useState } from "react";
import { useTranslation } from "react-i18next";
import { useCategories } from "@/api/catalog";
import {
  useCreateTransaction,
  useQuickChips,
  useUpdateTransaction,
  type Transaction,
  type TransactionInput,
} from "@/api/transactions";
import { Button, Chip, FieldError, Input, Label, Select, Textarea } from "@/components/ui/primitives";
import { isoToday } from "@/lib/dates";
import { apiErrorMessage } from "@/api/client";
import { categoryIcon } from "@/lib/categoryGroup";
import { categoryLabel, categoryLabelByCode } from "@/lib/categoryLabel";

interface QuickAddFormProps {
  householdId: string;
  initial?: Transaction;
  onSaved?: () => void;
  onCancel?: () => void;
}

export function QuickAddForm({ householdId, initial, onSaved, onCancel }: QuickAddFormProps) {
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
  const [error, setError] = useState<string | null>(null);
  const [fieldErrors, setFieldErrors] = useState<{ date?: string; amount?: string; categoryCode?: string }>({});

  const eligibleCategories = categories
    .filter((c) => c.kind === direction)
    .slice()
    .sort((a, b) => categoryLabel(a, t).localeCompare(categoryLabel(b, t), i18n.language, { sensitivity: "base" }));

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
        else if (!Number.isFinite(amt) || amt <= 0) next.amount = t("errors.amount_positive");
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
          <Select value={direction} onChange={(e) => { setDirection(e.target.value as "income" | "expense"); setCategoryCode(""); }}>
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

      <div>
        <Label>{t("common.amount")}</Label>
        <Input
          type="number"
          inputMode="decimal"
          step="0.01"
          min="0.01"
          value={amount}
          invalid={!!fieldErrors.amount}
          onChange={(e) => { setAmount(e.target.value); if (fieldErrors.amount) setFieldErrors({ ...fieldErrors, amount: undefined }); }}
          className="text-lg"
        />
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
