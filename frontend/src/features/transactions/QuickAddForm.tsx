import { useState } from "react";
import { useTranslation } from "react-i18next";
import { useCategories } from "@/api/catalog";
import { useCreateTransaction, useQuickChips, type TransactionInput } from "@/api/transactions";
import { Button, Chip, FieldError, Input, Label, Select, Textarea } from "@/components/ui/primitives";
import { isoToday } from "@/lib/dates";
import { asApiError } from "@/api/client";

export function QuickAddForm({ householdId, onCreated }: { householdId: string; onCreated?: () => void }) {
  const { t } = useTranslation();
  const { data: categories = [] } = useCategories();
  const { data: chips = [] } = useQuickChips(householdId);
  const create = useCreateTransaction(householdId);
  const [direction, setDirection] = useState<"income" | "expense">("expense");
  const [amount, setAmount] = useState("");
  const [categoryCode, setCategoryCode] = useState("");
  const [date, setDate] = useState(isoToday());
  const [description, setDescription] = useState("");
  const [error, setError] = useState<string | null>(null);
  const [fieldErrors, setFieldErrors] = useState<{ date?: string; amount?: string; categoryCode?: string }>({});

  const eligibleCategories = categories.filter((c) => c.kind === direction);

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
          await create.mutateAsync(input);
          setAmount("");
          setDescription("");
          onCreated?.();
        } catch (err) {
          const api = asApiError(err);
          setError(t(`errors.${api.code}`, api.message));
        }
      }}
      className="space-y-3"
    >
      {chips.length > 0 && (
        <div>
          <p className="mb-2 text-xs text-gray-500">{t("tx.chips_title")}</p>
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
                {t(`category.${c.categoryCode}`)}
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
              {t(`category.${c.code}`)}
            </option>
          ))}
        </Select>
        <FieldError message={fieldErrors.categoryCode} />
      </div>

      <div>
        <Label>{t("common.description")}</Label>
        <Textarea value={description} onChange={(e) => setDescription(e.target.value)} rows={2} maxLength={500} />
      </div>

      <FieldError message={error} />

      <Button type="submit" className="w-full" disabled={create.isPending}>
        {t("common.save")}
      </Button>
    </form>
  );
}
