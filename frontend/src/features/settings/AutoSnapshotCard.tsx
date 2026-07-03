import { useEffect, useState } from "react";
import { useTranslation } from "react-i18next";
import { asApiError } from "@/api/client";
import {
  useAutoSnapshotSettings,
  useUpdateAutoSnapshotSettings,
  type SnapshotFrequency,
} from "@/api/networth";
import { Button, Card, CardBody, CardHeader, FieldError, Label, Select } from "@/components/ui/primitives";

const FREQUENCIES: SnapshotFrequency[] = ["daily", "weekly", "monthly"];

/** Owner-only toggle + frequency for scheduled net-worth snapshots. */
export function AutoSnapshotCard({ householdId }: { householdId: string }) {
  const { t } = useTranslation();
  const { data: settings } = useAutoSnapshotSettings(householdId);
  const update = useUpdateAutoSnapshotSettings(householdId);

  const [enabled, setEnabled] = useState(false);
  const [frequency, setFrequency] = useState<SnapshotFrequency>("monthly");
  const [error, setError] = useState<string | null>(null);
  const [saved, setSaved] = useState(false);

  useEffect(() => {
    if (settings) {
      setEnabled(settings.enabled);
      setFrequency(settings.frequency);
    }
  }, [settings]);

  async function save() {
    setError(null);
    setSaved(false);
    try {
      await update.mutateAsync({ enabled, frequency });
      setSaved(true);
    } catch (err) {
      const api = asApiError(err);
      setError(t(`errors.${api.code}`, api.message));
    }
  }

  return (
    <Card>
      <CardHeader>
        <p className="font-medium">{t("networth.auto_snapshot_title")}</p>
        <p className="mt-1 text-sm text-gray-500 dark:text-gray-400">{t("networth.auto_snapshot_description")}</p>
      </CardHeader>
      <CardBody className="space-y-3">
        <label className="inline-flex items-center gap-2">
          <input
            type="checkbox"
            checked={enabled}
            onChange={(e) => { setEnabled(e.target.checked); setSaved(false); }}
          />
          {t("networth.auto_snapshot_enable")}
        </label>
        <div className="max-w-xs">
          <Label>{t("networth.auto_snapshot_frequency")}</Label>
          <Select
            value={frequency}
            disabled={!enabled}
            onChange={(e) => { setFrequency(e.target.value as SnapshotFrequency); setSaved(false); }}
          >
            {FREQUENCIES.map((f) => (
              <option key={f} value={f}>{t(`networth.auto_snapshot_frequency_${f}`)}</option>
            ))}
          </Select>
        </div>
        <FieldError message={error} />
        <div className="flex items-center gap-3">
          <Button onClick={() => void save()} disabled={update.isPending}>{t("common.save")}</Button>
          {saved && <span className="text-sm text-emerald-600 dark:text-emerald-400">{t("common.saved")}</span>}
        </div>
      </CardBody>
    </Card>
  );
}
