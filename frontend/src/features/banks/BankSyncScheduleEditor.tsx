import { useEffect, useMemo, useState } from "react";
import { useTranslation } from "react-i18next";
import { useBankSyncSchedule, useResetBankSyncSchedule, useSaveBankSyncSchedule } from "@/api/banks";
import { apiErrorMessage } from "@/api/client";
import { Badge, Button, FieldError, Input, Label } from "@/components/ui/primitives";
import { browserTimeZone, convertWallTime, oppositeTime } from "./syncTimes";

const QUARTER_HOUR_SECONDS = 900;

/** Owner-only: one or two daily background-sync times, edited and saved in the viewer's own zone. */
export function BankSyncScheduleEditor({ householdId }: { householdId: string }) {
  const { t } = useTranslation();
  const { data: schedule } = useBankSyncSchedule(householdId);
  const save = useSaveBankSyncSchedule(householdId);
  const reset = useResetBankSyncSchedule(householdId);
  const zone = useMemo(browserTimeZone, []);

  const [times, setTimes] = useState<string[]>([]);
  const [error, setError] = useState<string | null>(null);
  const [saved, setSaved] = useState(false);

  useEffect(() => {
    if (schedule) setTimes(schedule.times.map((time) => convertWallTime(time, schedule.timezone, zone)));
  }, [schedule, zone]);

  const editTime = (index: number, value: string) => {
    setTimes((current) => current.map((time, i) => (i === index ? value : time)));
    setSaved(false);
  };

  const addSecondTime = () => {
    setTimes((current) => [...current, oppositeTime(current[0] ?? "07:00")]);
    setSaved(false);
  };

  const removeSecondTime = () => {
    setTimes((current) => current.slice(0, 1));
    setSaved(false);
  };

  const run = async (action: () => Promise<unknown>) => {
    setError(null);
    setSaved(false);
    try {
      await action();
      setSaved(true);
    } catch (err) {
      setError(apiErrorMessage(err, t));
    }
  };

  if (!schedule) return null;
  const busy = save.isPending || reset.isPending;

  return (
    <section aria-labelledby="bank-sync-schedule-title" className="space-y-2 rounded-md border border-border p-3">
      <div className="flex items-center gap-2">
        <p id="bank-sync-schedule-title" className="text-sm font-medium">{t("banks.sync_schedule_title")}</p>
        {schedule.isDefault && <Badge>{t("banks.sync_schedule_default_badge")}</Badge>}
      </div>
      <p className="text-sm text-gray-500 dark:text-gray-400">{t("banks.sync_schedule_help", { zone })}</p>
      <div className="flex flex-wrap items-end gap-3">
        {times.map((time, index) => (
          <div key={index} className="w-32">
            <Label htmlFor={`bank-sync-time-${index}`}>{t("banks.sync_schedule_time", { n: index + 1 })}</Label>
            <Input
              id={`bank-sync-time-${index}`}
              type="time"
              step={QUARTER_HOUR_SECONDS}
              value={time}
              onChange={(e) => editTime(index, e.target.value)}
            />
          </div>
        ))}
        {times.length === 1 ? (
          <Button variant="secondary" onClick={addSecondTime}>{t("banks.sync_schedule_add_second")}</Button>
        ) : (
          <Button variant="ghost" onClick={removeSecondTime}>{t("banks.sync_schedule_remove_second")}</Button>
        )}
      </div>
      <FieldError message={error} />
      <div className="flex flex-wrap items-center gap-3">
        <Button disabled={busy} onClick={() => void run(() => save.mutateAsync({ times, timezone: zone }))}>
          {t("common.save")}
        </Button>
        {!schedule.isDefault && (
          <Button variant="secondary" disabled={busy} onClick={() => void run(() => reset.mutateAsync())}>
            {t("banks.sync_schedule_reset")}
          </Button>
        )}
        {saved && <span className="text-sm text-emerald-600 dark:text-emerald-400">{t("common.saved")}</span>}
      </div>
    </section>
  );
}
