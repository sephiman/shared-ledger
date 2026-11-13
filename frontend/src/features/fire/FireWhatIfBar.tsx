import { useTranslation } from "react-i18next";
import type { FirePreviewRequest, FireProjection } from "@/api/fire";
import { Badge, Button, Input, Label } from "@/components/ui/primitives";
import { formatMoney } from "@/lib/money";
import { WHAT_IF_SAVE_MORE_AMOUNT, WHAT_IF_SPEND_LESS_FACTOR } from "./fireHeadline";

export interface WhatIfDraft {
  monthlyContribution: string;
  monthlySpending: string;
  targetYear: string;
  safeWithdrawalRatePct: string;
  lumpSum: string;
}

export function initialWhatIfDraft(projection: FireProjection): WhatIfDraft {
  return {
    monthlyContribution: projection.contributions.activeMonthly ?? projection.settings.monthlyContribution,
    monthlySpending: projection.spending.totalMonthly,
    targetYear: String(projection.settings.targetYear),
    safeWithdrawalRatePct: projection.settings.safeWithdrawalRatePct,
    lumpSum: "0",
  };
}

export function buildPreviewRequest(draft: WhatIfDraft, base: FireProjection): FirePreviewRequest | null {
  const baseContrib = base.contributions.activeMonthly ?? base.settings.monthlyContribution;
  const contribChanged = draft.monthlyContribution.trim() !== "" && Number(draft.monthlyContribution) !== Number(baseContrib);
  const spendVal = Number(draft.monthlySpending);
  const spendChanged =
    draft.monthlySpending.trim() !== "" &&
    Number.isFinite(spendVal) &&
    spendVal > 0 &&
    spendVal !== Number(base.spending.totalMonthly);
  const yearChanged = draft.targetYear.trim() !== "" && Number(draft.targetYear) !== base.settings.targetYear;
  const swrChanged = draft.safeWithdrawalRatePct.trim() !== "" && Number(draft.safeWithdrawalRatePct) !== Number(base.settings.safeWithdrawalRatePct);
  const lumpVal = Number(draft.lumpSum);
  const lumpChanged = draft.lumpSum.trim() !== "" && Number.isFinite(lumpVal) && lumpVal > 0;

  if (!contribChanged && !spendChanged && !yearChanged && !swrChanged && !lumpChanged) return null;

  const req: FirePreviewRequest = {};
  if (contribChanged && Number(draft.monthlyContribution) >= 0) {
    req.monthlyContribution = draft.monthlyContribution;
  }
  if (spendChanged) {
    req.monthlySpending = draft.monthlySpending;
  }
  if (yearChanged && Number.isInteger(Number(draft.targetYear)) && Number(draft.targetYear) > 0) {
    req.targetYear = Number(draft.targetYear);
  }
  if (swrChanged && Number(draft.safeWithdrawalRatePct) > 0 && Number(draft.safeWithdrawalRatePct) <= 100) {
    req.safeWithdrawalRatePct = draft.safeWithdrawalRatePct;
  }
  if (lumpChanged) {
    req.lumpSum = draft.lumpSum;
  }
  return Object.keys(req).length > 0 ? req : null;
}

interface Props {
  baseProjection: FireProjection;
  draft: WhatIfDraft;
  isActive: boolean;
  isLoading: boolean;
  isOwner: boolean;
  currency: string;
  onChange: (next: WhatIfDraft) => void;
  onReset: () => void;
  onApply: () => void;
}

/** Ephemeral sandbox controls directly under the headline so any household member can test tweaks
 *  without mutating saved household settings. */
export function FireWhatIfBar({
  baseProjection,
  draft,
  isActive,
  isLoading,
  isOwner,
  currency,
  onChange,
  onReset,
  onApply,
}: Props) {
  const { t, i18n } = useTranslation();
  const locale = i18n.language;

  const canApply =
    isOwner &&
    isActive &&
    (Number(draft.monthlyContribution) !== Number(baseProjection.contributions.activeMonthly ?? baseProjection.settings.monthlyContribution) ||
      Number(draft.monthlySpending) !== Number(baseProjection.spending.totalMonthly) ||
      Number(draft.targetYear) !== baseProjection.settings.targetYear ||
      Number(draft.safeWithdrawalRatePct) !== Number(baseProjection.settings.safeWithdrawalRatePct));

  function handleSaveMore() {
    const baseContrib = Number(baseProjection.contributions.activeMonthly ?? baseProjection.settings.monthlyContribution) || 0;
    onChange({
      ...draft,
      monthlyContribution: (baseContrib + WHAT_IF_SAVE_MORE_AMOUNT).toFixed(2),
    });
  }

  function handleSpendLess() {
    const baseSpend = Number(baseProjection.spending.totalMonthly) || 0;
    if (baseSpend <= 0) return;
    onChange({
      ...draft,
      monthlySpending: (baseSpend * WHAT_IF_SPEND_LESS_FACTOR).toFixed(2),
    });
  }

  return (
    <div className="space-y-3 rounded-md border border-sky-200/80 bg-raised/50 p-3 dark:border-sky-800/50">
      <div className="flex flex-wrap items-center justify-between gap-2">
        <div className="flex flex-wrap items-center gap-2">
          <button
            type="button"
            onClick={handleSaveMore}
            className="rounded-full border border-sky-300 bg-sky-50 px-3 py-1 text-xs font-medium text-sky-800 hover:bg-sky-100 dark:border-sky-700 dark:bg-sky-900/40 dark:text-sky-200 dark:hover:bg-sky-900/60"
          >
            {t("fire.what_if_q_save_more", { amount: formatMoney(WHAT_IF_SAVE_MORE_AMOUNT, currency, locale) })}
          </button>
          <button
            type="button"
            onClick={handleSpendLess}
            className="rounded-full border border-sky-300 bg-sky-50 px-3 py-1 text-xs font-medium text-sky-800 hover:bg-sky-100 dark:border-sky-700 dark:bg-sky-900/40 dark:text-sky-200 dark:hover:bg-sky-900/60"
          >
            {t("fire.what_if_q_spend_less")}
          </button>
          {isActive && <Badge tone="sky">{t("fire.what_if_active")}</Badge>}
          {isLoading && <span className="text-xs text-gray-500 dark:text-gray-400">{t("common.loading")}</span>}
        </div>
        {isActive && (
          <div className="flex items-center gap-2">
            <Button variant="ghost" onClick={onReset}>
              {t("fire.what_if_reset")}
            </Button>
            {canApply && (
              <Button variant="secondary" onClick={onApply}>
                {t("fire.what_if_apply")}
              </Button>
            )}
          </div>
        )}
      </div>
      <div className="grid grid-cols-1 gap-3 sm:grid-cols-2 lg:grid-cols-5">
        <div>
          <Label className="text-xs">{t("fire.monthly_contribution")} ({currency})</Label>
          <Input
            type="number"
            step="50"
            min="0"
            value={draft.monthlyContribution}
            onChange={(e) => onChange({ ...draft, monthlyContribution: e.target.value })}
          />
        </div>
        <div>
          <Label className="text-xs">{t("fire.what_if_monthly_spending")} ({currency})</Label>
          <Input
            type="number"
            step="50"
            min="1"
            value={draft.monthlySpending}
            onChange={(e) => onChange({ ...draft, monthlySpending: e.target.value })}
          />
        </div>
        <div>
          <Label className="text-xs">{t("fire.target_year")}</Label>
          <Input
            type="number"
            step="1"
            min={baseProjection.startYear}
            value={draft.targetYear}
            onChange={(e) => onChange({ ...draft, targetYear: e.target.value })}
          />
        </div>
        <div>
          <Label className="text-xs">{t("fire.swr")}</Label>
          <Input
            type="number"
            step="0.1"
            min="0.1"
            max="100"
            value={draft.safeWithdrawalRatePct}
            onChange={(e) => onChange({ ...draft, safeWithdrawalRatePct: e.target.value })}
          />
        </div>
        <div>
          <Label className="text-xs">{t("fire.what_if_lump_sum")} ({currency})</Label>
          <Input
            type="number"
            step="1000"
            min="0"
            value={draft.lumpSum}
            onChange={(e) => onChange({ ...draft, lumpSum: e.target.value })}
          />
        </div>
      </div>
    </div>
  );
}
