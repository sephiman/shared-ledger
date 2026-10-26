import { useTranslation } from "react-i18next";
import { useProjectionModes, useSetProjectionMode, type ProjectionMode } from "@/api/projectionModes";
import { Select } from "@/components/ui/primitives";
import { groupIcon } from "@/lib/categoryGroup";

const MODES: readonly ProjectionMode[] = ["ENVELOPE", "PRORATE"];
const MODE_LABEL_KEYS: Record<ProjectionMode, string> = {
  ENVELOPE: "analytics.projection_mode_envelope",
  PRORATE: "analytics.projection_mode_prorate",
};
const BODY_ID = "projection-modes-body";

/** The collapsible "Configure projections" section. `order` is the group order captured when it was opened (null
 *  while collapsed), so a change that redraws the diagram never moves the rows under the user's finger. */
export function ProjectionModeSettings({
  householdId,
  order,
  onToggle,
  daysRemaining,
  daysInMonth,
}: {
  householdId: string;
  order: readonly string[] | null;
  onToggle: () => void;
  daysRemaining: number;
  daysInMonth: number;
}) {
  const { t } = useTranslation();
  return (
    <div className="border-t border-border pt-2">
      <button
        type="button"
        className="flex min-h-11 items-center gap-2 text-left text-sm font-medium text-gray-700 hover:text-gray-900 dark:text-gray-300 dark:hover:text-gray-100"
        aria-expanded={order != null}
        aria-controls={BODY_ID}
        onClick={onToggle}
      >
        <span aria-hidden className="inline-block w-3 text-gray-500 dark:text-gray-400">{order ? "▾" : "▸"}</span>
        <span>{t("analytics.projection_modes_toggle")}</span>
      </button>
      {order && (
        <div id={BODY_ID} className="space-y-2 pb-1">
          <div className="space-y-0.5 text-xs text-gray-500 dark:text-gray-400">
            <p>{t("analytics.projection_mode_envelope_help")}</p>
            <p>{t("analytics.projection_mode_prorate_help", { remaining: daysRemaining, days: daysInMonth })}</p>
          </div>
          <GroupModeList householdId={householdId} order={order} />
        </div>
      )}
    </div>
  );
}

function GroupModeList({ householdId, order }: { householdId: string; order: readonly string[] }) {
  const { t } = useTranslation();
  const { data, isError } = useProjectionModes(householdId);
  if (isError) return <p role="alert" className="text-sm text-red-600 dark:text-red-400">{t("analytics.projection_modes_load_failed")}</p>;
  if (!data) return <p className="text-sm text-gray-500 dark:text-gray-400">{t("common.loading")}</p>;

  const stored = new Map(data.map((m) => [m.groupCode, m.mode] as const));
  return (
    <ul aria-label={t("analytics.projection_modes_list")} className="divide-y divide-border">
      {order.flatMap((groupCode) => {
        const mode = stored.get(groupCode);
        return mode ? [<GroupModeRow key={groupCode} householdId={householdId} groupCode={groupCode} mode={mode} />] : [];
      })}
    </ul>
  );
}

function GroupModeRow({ householdId, groupCode, mode }: { householdId: string; groupCode: string; mode: ProjectionMode }) {
  const { t } = useTranslation();
  const save = useSetProjectionMode(householdId);
  const name = t(`category_group.${groupCode}`);
  const id = `projection-mode-${groupCode}`;
  // While saving, show the choice being saved; afterwards the stored value, which a failed save leaves untouched.
  const shown = save.isPending && save.variables ? save.variables.mode : mode;

  return (
    <li className="flex flex-col gap-1 py-2 sm:flex-row sm:items-center sm:gap-3">
      <label htmlFor={id} className="text-sm sm:flex-1">
        <span aria-hidden>{groupIcon(groupCode)}</span> {name}
      </label>
      <Select
        id={id}
        aria-label={name}
        value={shown}
        disabled={save.isPending}
        className="sm:w-60"
        onChange={(e) => save.mutate({ groupCode, mode: e.target.value as ProjectionMode })}
      >
        {MODES.map((m) => (
          <option key={m} value={m}>{t(MODE_LABEL_KEYS[m])}</option>
        ))}
      </Select>
      <span className="min-h-4 text-xs sm:w-40">
        {save.isSuccess && (
          <span role="status" className="text-emerald-700 dark:text-emerald-400">✓ {t("analytics.projection_mode_saved")}</span>
        )}
        {save.isError && (
          <span role="alert" className="text-red-600 dark:text-red-400">{t("analytics.projection_mode_save_failed")}</span>
        )}
      </span>
    </li>
  );
}
