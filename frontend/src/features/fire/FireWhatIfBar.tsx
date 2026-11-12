import { useTranslation } from "react-i18next";
import type { FirePreviewRequest, FireProjection } from "@/api/fire";
import { Badge, Button, Card, CardBody, Input, Label } from "@/components/ui/primitives";

export interface WhatIfDraft {
  monthlyContribution: string;
  targetYear: string;
  safeWithdrawalRatePct: string;
  lumpSum: string;
}

export function initialWhatIfDraft(projection: FireProjection): WhatIfDraft {
  return {
    monthlyContribution: projection.contributions.activeMonthly ?? projection.settings.monthlyContribution,
    targetYear: String(projection.settings.targetYear),
    safeWithdrawalRatePct: projection.settings.safeWithdrawalRatePct,
    lumpSum: "0",
  };
}

export function buildPreviewRequest(draft: WhatIfDraft, base: FireProjection): FirePreviewRequest | null {
  const baseContrib = base.contributions.activeMonthly ?? base.settings.monthlyContribution;
  const contribChanged = draft.monthlyContribution.trim() !== "" && Number(draft.monthlyContribution) !== Number(baseContrib);
  const yearChanged = draft.targetYear.trim() !== "" && Number(draft.targetYear) !== base.settings.targetYear;
  const swrChanged = draft.safeWithdrawalRatePct.trim() !== "" && Number(draft.safeWithdrawalRatePct) !== Number(base.settings.safeWithdrawalRatePct);
  const lumpVal = Number(draft.lumpSum);
  const lumpChanged = draft.lumpSum.trim() !== "" && Number.isFinite(lumpVal) && lumpVal > 0;

  if (!contribChanged && !yearChanged && !swrChanged && !lumpChanged) return null;

  const req: FirePreviewRequest = {};
  if (contribChanged && Number(draft.monthlyContribution) >= 0) {
    req.monthlyContribution = draft.monthlyContribution;
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

/** Ephemeral sandbox controls so any household member can test contribution/year/SWR/lump-sum tweaks
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
  const { t } = useTranslation();

  const canApply =
    isOwner &&
    isActive &&
    (Number(draft.monthlyContribution) !== Number(baseProjection.contributions.activeMonthly ?? baseProjection.settings.monthlyContribution) ||
      Number(draft.targetYear) !== baseProjection.settings.targetYear ||
      Number(draft.safeWithdrawalRatePct) !== Number(baseProjection.settings.safeWithdrawalRatePct));

  return (
    <Card>
      <CardBody className="space-y-3">
        <div className="flex flex-wrap items-center justify-between gap-2">
          <div className="flex items-center gap-2">
            <p className="text-sm font-medium">{t("fire.what_if_title")}</p>
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
        <div className="grid grid-cols-1 gap-3 sm:grid-cols-2 lg:grid-cols-4">
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
      </CardBody>
    </Card>
  );
}
