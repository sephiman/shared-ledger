import { useEffect, useMemo, useState } from "react";
import { useTranslation } from "react-i18next";
import { asApiError } from "@/api/client";
import {
  useRegisterPayment,
  useUpdatePayment,
  type LoanDetail,
  type LoanPayment,
} from "@/api/loans";
import { previewSplit, type CalcLoan, type CalcPayment } from "./balance";
import { isoToday } from "@/lib/dates";
import { formatMoney } from "@/lib/money";
import { Button, FieldError, Input, Label } from "@/components/ui/primitives";

interface Props {
  householdId: string;
  detail: LoanDetail;
  editing: LoanPayment | null;
  currency: string;
  locale: string;
  onDone: () => void;
}

export function LoanPaymentForm({ householdId, detail, editing, currency, locale, onDone }: Props) {
  const { t } = useTranslation();
  const register = useRegisterPayment(householdId);
  const update = useUpdatePayment(householdId);

  const [paymentDate, setPaymentDate] = useState(editing?.paymentDate ?? isoToday());
  const [amount, setAmount] = useState(editing?.amount ?? "");
  const [description, setDescription] = useState(editing?.description ?? "");
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    setPaymentDate(editing?.paymentDate ?? isoToday());
    setAmount(editing?.amount ?? "");
    setDescription(editing?.description ?? "");
    setError(null);
  }, [editing]);

  const calcLoan: CalcLoan = useMemo(
    () => ({
      principalAmount: detail.summary.principalAmount,
      startDate: detail.summary.startDate,
      interestType: detail.summary.interestType,
      annualInterestRate: detail.summary.annualInterestRate,
      compoundingPeriod: detail.summary.compoundingPeriod,
      status: detail.summary.status,
      closedDate: detail.summary.closedDate,
    }),
    [detail.summary],
  );

  const split = useMemo(() => {
    const normalized = amount.replace(",", ".");
    if (!normalized || Number(normalized) <= 0) return null;
    const existing: CalcPayment[] = detail.payments
      .filter((p) => p.id !== editing?.id)
      .map((p) => ({ id: p.id, paymentDate: p.paymentDate, amount: p.amount }));
    return previewSplit(calcLoan, existing, paymentDate, normalized);
  }, [amount, paymentDate, detail.payments, editing?.id, calcLoan]);

  const submit = async (e: React.FormEvent) => {
    e.preventDefault();
    const normalized = amount.replace(",", ".");
    if (!normalized || Number(normalized) <= 0) {
      setError(t("errors.field_required"));
      return;
    }
    const input = { paymentDate, amount: normalized, description: description.trim() || null };
    try {
      if (editing) {
        await update.mutateAsync({ loanId: detail.summary.id, paymentId: editing.id, input });
      } else {
        await register.mutateAsync({ loanId: detail.summary.id, input });
      }
      onDone();
    } catch (err) {
      const api = asApiError(err);
      setError(t(`errors.${api.code}`, api.message));
    }
  };

  return (
    <form noValidate onSubmit={submit} className="space-y-3 rounded-md border border-border p-3 dark:border-gray-700">
      <p className="font-medium">{editing ? t("loans.edit_payment") : t("loans.register_payment")}</p>
      <div className="grid grid-cols-2 gap-3">
        <div>
          <Label>{t("loans.payment_date")}</Label>
          <Input type="date" value={paymentDate} min={detail.summary.startDate} onChange={(e) => setPaymentDate(e.target.value)} />
        </div>
        <div>
          <Label>{t("loans.amount")}</Label>
          <Input value={amount} inputMode="decimal" placeholder="0,00" onChange={(e) => setAmount(e.target.value)} />
        </div>
      </div>
      <div>
        <Label>{t("loans.description")}</Label>
        <Input value={description} maxLength={500} onChange={(e) => setDescription(e.target.value)} />
      </div>
      {split && (
        <div className="rounded-md bg-gray-50 p-2 text-sm dark:bg-gray-700/40">
          <p className="text-gray-600 dark:text-gray-300">{t("loans.split_preview")}</p>
          <div className="mt-1 flex justify-between">
            <span>{t("loans.interest_paid")}</span>
            <span className="font-mono tabular-nums">{formatMoney(split.interestPaid.toFixed(2), currency, locale)}</span>
          </div>
          <div className="flex justify-between">
            <span>{t("loans.principal_paid")}</span>
            <span className="font-mono tabular-nums">{formatMoney(split.principalPaid.toFixed(2), currency, locale)}</span>
          </div>
        </div>
      )}
      <FieldError message={error} />
      <div className="flex justify-end gap-2">
        <Button type="button" variant="secondary" onClick={onDone}>{t("common.cancel")}</Button>
        <Button type="submit" disabled={register.isPending || update.isPending}>{t("common.save")}</Button>
      </div>
    </form>
  );
}
