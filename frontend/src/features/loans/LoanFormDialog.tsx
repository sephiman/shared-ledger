import { useEffect, useState } from "react";
import { useTranslation } from "react-i18next";
import { asApiError } from "@/api/client";
import {
  useCreateLoan,
  useUpdateLoan,
  type CompoundingPeriod,
  type InterestType,
  type LoanSummary,
} from "@/api/loans";
import { isoToday } from "@/lib/dates";
import { Button, Card, CardBody, CardHeader, FieldError, Input, Label, Select, Textarea } from "@/components/ui/primitives";

interface Props {
  open: boolean;
  householdId: string;
  editing: LoanSummary | null;
  onClose: () => void;
  onSaved: (loanId: string) => void;
}

export function LoanFormDialog({ open, householdId, editing, onClose, onSaved }: Props) {
  const { t } = useTranslation();
  const create = useCreateLoan(householdId);
  const update = useUpdateLoan(householdId);

  const [borrowerName, setBorrowerName] = useState("");
  const [principalAmount, setPrincipalAmount] = useState("");
  const [startDate, setStartDate] = useState(isoToday());
  const [description, setDescription] = useState("");
  const [interestType, setInterestType] = useState<InterestType>("none");
  const [annualInterestRate, setAnnualInterestRate] = useState("");
  const [compoundingPeriod, setCompoundingPeriod] = useState<CompoundingPeriod>("monthly");
  const [errors, setErrors] = useState<{ borrower?: string; principal?: string; rate?: string }>({});
  const [submitError, setSubmitError] = useState<string | null>(null);

  useEffect(() => {
    if (!open) return;
    if (editing) {
      setBorrowerName(editing.borrowerName);
      setPrincipalAmount(editing.principalAmount);
      setStartDate(editing.startDate);
      setDescription(editing.description ?? "");
      setInterestType(editing.interestType);
      setAnnualInterestRate(editing.annualInterestRate ?? "");
      setCompoundingPeriod(editing.compoundingPeriod ?? "monthly");
    } else {
      setBorrowerName("");
      setPrincipalAmount("");
      setStartDate(isoToday());
      setDescription("");
      setInterestType("none");
      setAnnualInterestRate("");
      setCompoundingPeriod("monthly");
    }
    setErrors({});
    setSubmitError(null);
  }, [open, editing]);

  if (!open) return null;

  const submit = async (e: React.FormEvent) => {
    e.preventDefault();
    const next: typeof errors = {};
    if (!borrowerName.trim()) next.borrower = t("errors.field_required");
    if (!principalAmount || Number(principalAmount.replace(",", ".")) <= 0) next.principal = t("errors.field_required");
    if (interestType !== "none" && (!annualInterestRate || Number(annualInterestRate.replace(",", ".")) <= 0)) {
      next.rate = t("errors.field_required");
    }
    setErrors(next);
    if (Object.keys(next).length > 0) return;

    const input = {
      borrowerName: borrowerName.trim(),
      principalAmount: principalAmount.replace(",", "."),
      startDate,
      description: description.trim() || null,
      interestType,
      annualInterestRate: interestType === "none" ? null : annualInterestRate.replace(",", "."),
      compoundingPeriod: interestType === "compound" ? compoundingPeriod : null,
    };
    try {
      const result = editing
        ? await update.mutateAsync({ id: editing.id, input })
        : await create.mutateAsync(input);
      onSaved(result.summary.id);
    } catch (err) {
      const api = asApiError(err);
      setSubmitError(t(`errors.${api.code}`, api.message));
    }
  };

  return (
    <div
      className="fixed inset-0 z-50 flex items-center justify-center bg-black/40 px-4 py-8 overflow-y-auto"
      role="dialog"
      aria-modal="true"
      onClick={onClose}
    >
      <Card className="w-full max-w-md" onClick={(e) => e.stopPropagation()}>
        <CardHeader>
          <p className="font-medium">{editing ? t("loans.edit_loan") : t("loans.new_loan")}</p>
        </CardHeader>
        <CardBody>
          <form noValidate onSubmit={submit} className="space-y-3">
            <div>
              <Label>{t("loans.borrower_name")}</Label>
              <Input
                value={borrowerName}
                invalid={!!errors.borrower}
                autoFocus
                maxLength={120}
                onChange={(e) => setBorrowerName(e.target.value)}
              />
              <FieldError message={errors.borrower} />
            </div>
            <div>
              <Label>{t("loans.principal_amount")}</Label>
              <Input
                value={principalAmount}
                invalid={!!errors.principal}
                inputMode="decimal"
                placeholder="0,00"
                onChange={(e) => setPrincipalAmount(e.target.value)}
              />
              <FieldError message={errors.principal} />
            </div>
            <div>
              <Label>{t("loans.start_date")}</Label>
              <Input type="date" value={startDate} onChange={(e) => setStartDate(e.target.value)} />
            </div>
            <div>
              <Label>{t("loans.interest_type")}</Label>
              <Select value={interestType} onChange={(e) => setInterestType(e.target.value as InterestType)}>
                <option value="none">{t("loans.interest_none")}</option>
                <option value="simple">{t("loans.interest_simple")}</option>
                <option value="compound">{t("loans.interest_compound")}</option>
              </Select>
            </div>
            {interestType !== "none" && (
              <div>
                <Label>{t("loans.annual_interest_rate")}</Label>
                <Input
                  value={annualInterestRate}
                  invalid={!!errors.rate}
                  inputMode="decimal"
                  placeholder="0,00"
                  onChange={(e) => setAnnualInterestRate(e.target.value)}
                />
                <FieldError message={errors.rate} />
              </div>
            )}
            {interestType === "compound" && (
              <div>
                <Label>{t("loans.compounding_period")}</Label>
                <Select value={compoundingPeriod} onChange={(e) => setCompoundingPeriod(e.target.value as CompoundingPeriod)}>
                  <option value="monthly">{t("loans.compounding_monthly")}</option>
                  <option value="yearly">{t("loans.compounding_yearly")}</option>
                </Select>
              </div>
            )}
            <div>
              <Label>{t("loans.description")}</Label>
              <Textarea
                value={description}
                rows={2}
                maxLength={500}
                onChange={(e) => setDescription(e.target.value)}
              />
            </div>
            <FieldError message={submitError} />
            <div className="flex justify-end gap-2 pt-2">
              <Button type="button" variant="secondary" onClick={onClose}>{t("common.cancel")}</Button>
              <Button type="submit" disabled={create.isPending || update.isPending}>{t("common.save")}</Button>
            </div>
          </form>
        </CardBody>
      </Card>
    </div>
  );
}
