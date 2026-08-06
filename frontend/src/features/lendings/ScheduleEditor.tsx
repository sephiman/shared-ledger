import { useEffect, useState } from "react";
import { useTranslation } from "react-i18next";
import { apiErrorMessage } from "@/api/client";
import {
  useDeleteSchedule,
  useMaterializeSchedule,
  useSetScheduleActive,
  useUpsertSchedule,
  type LendingFrequency,
  type LendingSchedule,
} from "@/api/lendings";
import { Button, FieldError, Input, Label, Select } from "@/components/ui/primitives";

interface Props {
  householdId: string;
  lendingId: string;
  schedule: LendingSchedule | null;
  disabled: boolean;
}

export function ScheduleEditor({ householdId, lendingId, schedule, disabled }: Props) {
  const { t } = useTranslation();
  const upsert = useUpsertSchedule(householdId);
  const setActive = useSetScheduleActive(householdId);
  const del = useDeleteSchedule(householdId);
  const materialize = useMaterializeSchedule(householdId);

  const [open, setOpen] = useState(false);
  const [frequency, setFrequency] = useState<LendingFrequency>("monthly");
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
      await upsert.mutateAsync({ lendingId, input });
      setOpen(false);
      setError(null);
    } catch (err) {
      setError(apiErrorMessage(err, t));
    }
  };

  if (schedule && !open) {
    return (
      <div className="space-y-2 rounded-md border border-border p-3">
        <div className="flex items-center justify-between">
          <p className="font-medium">{t("lendings.schedule")}</p>
          <span className={schedule.active ? "text-emerald-600" : "text-gray-500"}>
            {schedule.active ? t("lendings.schedule_active") : t("lendings.schedule_paused")}
          </span>
        </div>
        <p className="text-sm text-gray-600 dark:text-gray-300">
          {t(`lendings.frequency_${schedule.frequency}`)} · {t("lendings.expected_amount")}: {schedule.expectedAmount}
        </p>
        <div className="flex flex-wrap gap-2">
          {!disabled && <Button variant="ghost" onClick={() => setOpen(true)}>{t("common.edit")}</Button>}
          {!disabled && (
            <Button
              variant="ghost"
              onClick={() => setActive.mutate({ lendingId, active: !schedule.active })}
            >
              {schedule.active ? t("lendings.pause_schedule") : t("lendings.resume_schedule")}
            </Button>
          )}
          {!disabled && schedule.active && (
            <Button variant="ghost" onClick={() => materialize.mutate(lendingId)}>{t("lendings.materialize_now")}</Button>
          )}
          <Button
            variant="ghost"
            onClick={() => { if (window.confirm(t("lendings.delete_schedule") + "?")) del.mutate(lendingId); }}
          >
            {t("lendings.delete_schedule")}
          </Button>
        </div>
      </div>
    );
  }

  if (!schedule && !open) {
    if (disabled) return null;
    return (
      <Button variant="secondary" onClick={() => setOpen(true)}>{t("lendings.add_schedule")}</Button>
    );
  }

  return (
    <div className="space-y-3 rounded-md border border-border p-3">
      <p className="font-medium">{t("lendings.schedule")}</p>
      <div>
        <Label>{t("lendings.frequency")}</Label>
        <Select value={frequency} onChange={(e) => setFrequency(e.target.value as LendingFrequency)}>
          <option value="weekly">{t("lendings.frequency_weekly")}</option>
          <option value="monthly">{t("lendings.frequency_monthly")}</option>
          <option value="yearly">{t("lendings.frequency_yearly")}</option>
        </Select>
      </div>
      {frequency === "weekly" ? (
        <div>
          <Label>{t("lendings.day_of_week")}</Label>
          <Select value={dayOfWeek} onChange={(e) => setDayOfWeek(e.target.value)}>
            {[1, 2, 3, 4, 5, 6, 7].map((d) => (
              <option key={d} value={d}>{t(`lendings.weekday_${d}`)}</option>
            ))}
          </Select>
        </div>
      ) : (
        <div>
          <Label>{t("lendings.day_of_month")}</Label>
          <Input type="number" min={1} max={31} value={dayOfMonth} onChange={(e) => setDayOfMonth(e.target.value)} />
        </div>
      )}
      <div>
        <Label>{t("lendings.expected_amount")}</Label>
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
