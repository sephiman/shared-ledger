import { useEffect, useState } from "react";
import { useTranslation } from "react-i18next";
import { apiErrorMessage } from "@/api/client";
import {
  useCreateLending,
  useUpdateLending,
  type CompoundingPeriod,
  type InterestType,
  type LendingSummary,
} from "@/api/lendings";
import { isoToday } from "@/lib/dates";
import { Button, Card, CardBody, CardHeader, FieldError, Input, Label, Select, Textarea } from "@/components/ui/primitives";

interface Props {
  open: boolean;
  householdId: string;
  editing: LendingSummary | null;
  onClose: () => void;
  onSaved: (lendingId: string) => void;
}

export function LendingFormDialog({ open, householdId, editing, onClose, onSaved }: Props) {
  const { t } = useTranslation();
  const create = useCreateLending(householdId);
  const update = useUpdateLending(householdId);

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
      setSubmitError(apiErrorMessage(err, t));
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
          <p className="font-medium">{editing ? t("lendings.edit_lending") : t("lendings.new_lending")}</p>
        </CardHeader>
        <CardBody>
          <form noValidate onSubmit={submit} className="space-y-3">
            <div>
              <Label>{t("lendings.borrower_name")}</Label>
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
              <Label>{t("lendings.principal_amount")}</Label>
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
              <Label>{t("lendings.start_date")}</Label>
              <Input type="date" value={startDate} onChange={(e) => setStartDate(e.target.value)} />
            </div>
            <div>
              <Label>{t("lendings.interest_type")}</Label>
              <Select value={interestType} onChange={(e) => setInterestType(e.target.value as InterestType)}>
                <option value="none">{t("lendings.interest_none")}</option>
                <option value="simple">{t("lendings.interest_simple")}</option>
                <option value="compound">{t("lendings.interest_compound")}</option>
              </Select>
            </div>
            {interestType !== "none" && (
              <div>
                <Label>{t("lendings.annual_interest_rate")}</Label>
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
                <Label>{t("lendings.compounding_period")}</Label>
                <Select value={compoundingPeriod} onChange={(e) => setCompoundingPeriod(e.target.value as CompoundingPeriod)}>
                  <option value="monthly">{t("lendings.compounding_monthly")}</option>
                  <option value="yearly">{t("lendings.compounding_yearly")}</option>
                </Select>
              </div>
            )}
            <div>
              <Label>{t("lendings.description")}</Label>
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
