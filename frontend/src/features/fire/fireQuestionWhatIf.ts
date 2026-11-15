import type { TFunction } from "i18next";
import type { FireCashFlowEvent, FireProjection } from "@/api/fire";
import type { FireDecimalChange, FireQuestionUnsupported, FireQuestionWhatIf } from "@/api/fireAi";
import { formatWholeMoney, toDecimal } from "@/lib/money";
import type { WhatIfDraft } from "./FireWhatIfBar";
import { formatFirePercent } from "./fireLabels";

function applyDecimalChange(current: string, change: FireDecimalChange, minValue: number, decimals = 2): string {
  const base = toDecimal(current);
  const raw = change.kind === "set"
    ? toDecimal(change.value)
    : change.kind === "delta"
      ? base.plus(toDecimal(change.value))
      : base.mul(toDecimal(100).plus(toDecimal(change.value))).div(100);
  const next = raw.lt(minValue) ? toDecimal(minValue) : raw;
  return next.toDecimalPlaces(decimals).toFixed(decimals);
}

/** Resolves a zero-numbers-out AI What-If intent into concrete sandbox values using the household's live projection. */
export function applyFireQuestionWhatIf(whatIf: FireQuestionWhatIf, base: FireProjection): WhatIfDraft {
  const baseContrib = base.contributions.activeMonthly ?? base.settings.monthlyContribution;
  const monthlyContribution = whatIf.monthlyContribution
    ? applyDecimalChange(baseContrib, whatIf.monthlyContribution, 0, 2)
    : baseContrib;

  const monthlySpending = whatIf.monthlySpending
    ? applyDecimalChange(base.spending.totalMonthly, whatIf.monthlySpending, 1, 2)
    : base.spending.totalMonthly;

  let monthlyEssentialSpending = "";
  if (whatIf.monthlySpending && Number(base.spending.essentialMonthly) > 0) {
    if (whatIf.monthlySpending.kind === "percent") {
      monthlyEssentialSpending = applyDecimalChange(base.spending.essentialMonthly, whatIf.monthlySpending, 1, 2);
    } else if (whatIf.monthlySpending.kind === "delta") {
      const ratio = toDecimal(monthlySpending).div(toDecimal(base.spending.totalMonthly));
      monthlyEssentialSpending = toDecimal(base.spending.essentialMonthly)
        .mul(ratio)
        .clamp(1, 1_000_000_000)
        .toDecimalPlaces(2)
        .toFixed(2);
    }
  }

  let targetYear = base.settings.targetYear;
  if (whatIf.targetYear) {
    targetYear = whatIf.targetYear.kind === "set"
      ? whatIf.targetYear.value
      : base.settings.targetYear + whatIf.targetYear.value;
    targetYear = Math.max(base.startYear, Math.min(2200, Math.round(targetYear)));
  }

  let safeWithdrawalRatePct = base.settings.safeWithdrawalRatePct;
  if (whatIf.safeWithdrawalRatePct) {
    const swr = Number(applyDecimalChange(base.settings.safeWithdrawalRatePct, whatIf.safeWithdrawalRatePct, 0.1, 2));
    safeWithdrawalRatePct = Math.min(100, Math.max(0.1, swr)).toFixed(2).replace(/\.?0+$/, "");
  }

  const lumpSum = whatIf.lumpSum && Number(whatIf.lumpSum) > 0 ? whatIf.lumpSum : "0";
  const returnShiftPct = whatIf.returnShiftPct ?? "0";

  let cashFlowEvents: FireCashFlowEvent[] | null = null;
  if (whatIf.cashFlowEvents.length > 0) {
    const added: FireCashFlowEvent[] = whatIf.cashFlowEvents.map((e) => ({
      year: e.year,
      kind: e.kind,
      amount: e.amount,
      label: e.label,
      template: e.template,
    }));
    cashFlowEvents = [...base.settings.cashFlowEvents, ...added];
  }

  return {
    monthlyContribution,
    monthlySpending,
    monthlyEssentialSpending,
    targetYear: String(targetYear),
    safeWithdrawalRatePct,
    lumpSum,
    returnShiftPct,
    cashFlowEvents,
  };
}

function formatDecimalDeltaLabel(
  change: FireDecimalChange,
  currency: string,
  locale: string,
  isPercentMetric = false,
): string {
  const num = Number(change.value);
  const sign = num >= 0 ? "+" : "";
  if (change.kind === "percent") return `${sign}${formatFirePercent(num, locale, 1)}`;
  if (isPercentMetric) {
    return change.kind === "set"
      ? formatFirePercent(num, locale, 2)
      : `${sign}${formatFirePercent(num, locale, 2)}`;
  }
  return change.kind === "set"
    ? formatWholeMoney(change.value, currency, locale)
    : `${sign}${formatWholeMoney(change.value, currency, locale)}`;
}

export function describeFireQuestionWhatIf(
  whatIf: FireQuestionWhatIf,
  currency: string,
  locale: string,
  t: TFunction,
): string[] {
  const parts: string[] = [];
  if (whatIf.monthlyContribution) {
    parts.push(
      t("fire.ai_what_if_chip_contrib", {
        value: formatDecimalDeltaLabel(whatIf.monthlyContribution, currency, locale),
      }),
    );
  }
  if (whatIf.monthlySpending) {
    parts.push(
      t("fire.ai_what_if_chip_spending", {
        value: formatDecimalDeltaLabel(whatIf.monthlySpending, currency, locale),
      }),
    );
  }
  if (whatIf.targetYear) {
    const v = whatIf.targetYear.value;
    const text = whatIf.targetYear.kind === "set" ? String(v) : `${v >= 0 ? "+" : ""}${v}y`;
    parts.push(t("fire.ai_what_if_chip_year", { value: text }));
  }
  if (whatIf.safeWithdrawalRatePct) {
    parts.push(
      t("fire.ai_what_if_chip_swr", {
        value: formatDecimalDeltaLabel(whatIf.safeWithdrawalRatePct, currency, locale, true),
      }),
    );
  }
  if (whatIf.lumpSum && Number(whatIf.lumpSum) > 0) {
    parts.push(
      t("fire.ai_what_if_chip_lump_sum", {
        value: formatWholeMoney(whatIf.lumpSum, currency, locale),
      }),
    );
  }
  if (whatIf.returnShiftPct && Number(whatIf.returnShiftPct) !== 0) {
    const num = Number(whatIf.returnShiftPct);
    parts.push(
      t("fire.ai_what_if_chip_return", {
        value: `${num >= 0 ? "+" : ""}${formatFirePercent(num, locale, 1)}`,
      }),
    );
  }
  for (const ev of whatIf.cashFlowEvents) {
    const amt = formatWholeMoney(ev.amount, currency, locale);
    const label = ev.label || t(`fire.template_${ev.template ?? "windfall"}`, { defaultValue: ev.kind });
    parts.push(`${label} (${ev.year}: ${amt})`);
  }
  return parts;
}

export function describeFireUnsupported(items: FireQuestionUnsupported[], t: TFunction): string | null {
  if (items.length === 0) return null;
  const labels = items.map((i) => i.label).join(", ");
  return t("fire.ai_what_if_unsupported", { items: labels });
}
