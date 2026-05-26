import { useEffect, useState } from "react";
import { useTranslation } from "react-i18next";
import { asApiError } from "@/api/client";
import {
  useDeleteSchedule,
  useMaterializeSchedule,
  useSetScheduleActive,
  useUpsertSchedule,
  type LoanFrequency,
  type LoanSchedule,
} from "@/api/loans";
import { Button, FieldError, Input, Label, Select } from "@/components/ui/primitives";

interface Props {
  householdId: string;
  loanId: string;
  schedule: LoanSchedule | null;
  disabled: boolean;
}

export function ScheduleEditor({ householdId, loanId, schedule, disabled }: Props) {
  const { t } = useTranslation();
  const upsert = useUpsertSchedule(householdId);
  const setActive = useSetScheduleActive(householdId);
  const del = useDeleteSchedule(householdId);
  const materialize = useMaterializeSchedule(householdId);

  const [open, setOpen] = useState(false);
  const [frequency, setFrequency] = useState<LoanFrequency>("monthly");
  const [dayOfMonth, setDayOfMonth] = useState("1");
  const [dayOfWeek, setDayOfWeek] = useState("1");
  const [expectedAmount, setExpectedAmount] = useState("");
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    if (schedule) {
      setFrequency(schedule.frequency);
      setDayOfMonth(String(schedule.dayOfMonth ?? 1));
      setDayOfWeek(String(schedule.dayOfWeek ?? 1));
      setExpectedAmount(schedule.expectedAmount);
    }
  }, [schedule]);

  const save = async () => {
    const normalized = expectedAmount.replace(",", ".");
    if (!normalized || Number(normalized) <= 0) {
      setError(t("errors.field_required"));
      return;
    }
    const input = {
      frequency,
      dayOfWeek: frequency === "weekly" ? Number(dayOfWeek) : null,
      dayOfMonth: frequency === "weekly" ? null : Number(dayOfMonth),
      expectedAmount: normalized,
      active: schedule?.active ?? true,
    };
    try {
      await upsert.mutateAsync({ loanId, input });
      setOpen(false);
      setError(null);
    } catch (err) {
      const api = asApiError(err);
      setError(t(`errors.${api.code}`, api.message));
    }
  };

  if (schedule && !open) {
    return (
      <div className="space-y-2 rounded-md border border-border p-3 dark:border-gray-700">
        <div className="flex items-center justify-between">
          <p className="font-medium">{t("loans.schedule")}</p>
          <span className={schedule.active ? "text-emerald-600" : "text-gray-500"}>
            {schedule.active ? t("loans.schedule_active") : t("loans.schedule_paused")}
          </span>
        </div>
        <p className="text-sm text-gray-600 dark:text-gray-300">
          {t(`loans.frequency_${schedule.frequency}`)} · {t("loans.expected_amount")}: {schedule.expectedAmount}
        </p>
        <div className="flex flex-wrap gap-2">
          {!disabled && <Button variant="ghost" onClick={() => setOpen(true)}>{t("common.edit")}</Button>}
          {!disabled && (
            <Button
              variant="ghost"
              onClick={() => setActive.mutate({ loanId, active: !schedule.active })}
            >
              {schedule.active ? t("loans.pause_schedule") : t("loans.resume_schedule")}
            </Button>
          )}
          {!disabled && schedule.active && (
            <Button variant="ghost" onClick={() => materialize.mutate(loanId)}>{t("loans.materialize_now")}</Button>
          )}
          <Button
            variant="ghost"
            onClick={() => { if (window.confirm(t("loans.delete_schedule") + "?")) del.mutate(loanId); }}
          >
            {t("loans.delete_schedule")}
          </Button>
        </div>
      </div>
    );
  }

  if (!schedule && !open) {
    if (disabled) return null;
    return (
      <Button variant="secondary" onClick={() => setOpen(true)}>{t("loans.add_schedule")}</Button>
    );
  }

  return (
    <div className="space-y-3 rounded-md border border-border p-3 dark:border-gray-700">
      <p className="font-medium">{t("loans.schedule")}</p>
      <div>
        <Label>{t("loans.frequency")}</Label>
        <Select value={frequency} onChange={(e) => setFrequency(e.target.value as LoanFrequency)}>
          <option value="weekly">{t("loans.frequency_weekly")}</option>
          <option value="monthly">{t("loans.frequency_monthly")}</option>
          <option value="yearly">{t("loans.frequency_yearly")}</option>
        </Select>
      </div>
      {frequency === "weekly" ? (
        <div>
          <Label>{t("loans.day_of_week")}</Label>
          <Select value={dayOfWeek} onChange={(e) => setDayOfWeek(e.target.value)}>
            {[1, 2, 3, 4, 5, 6, 7].map((d) => (
              <option key={d} value={d}>{t(`loans.weekday_${d}`)}</option>
            ))}
          </Select>
        </div>
      ) : (
        <div>
          <Label>{t("loans.day_of_month")}</Label>
          <Input type="number" min={1} max={31} value={dayOfMonth} onChange={(e) => setDayOfMonth(e.target.value)} />
        </div>
      )}
      <div>
        <Label>{t("loans.expected_amount")}</Label>
        <Input value={expectedAmount} inputMode="decimal" placeholder="0,00" onChange={(e) => setExpectedAmount(e.target.value)} />
      </div>
      <FieldError message={error} />
      <div className="flex justify-end gap-2">
        <Button variant="secondary" onClick={() => { setOpen(false); setError(null); }}>{t("common.cancel")}</Button>
        <Button onClick={save} disabled={upsert.isPending}>{t("common.save")}</Button>
      </div>
    </div>
  );
}
