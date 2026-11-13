import { useState } from "react";
import { useTranslation } from "react-i18next";
import type { FireProjection, FireTierKey } from "@/api/fire";
import { Badge, Card, CardBody, CardHeader } from "@/components/ui/primitives";
import { formatMoney } from "@/lib/money";
import { Explain } from "./Explain";
import { formatDeltaValue } from "./FireHeroStrip";
import { ScenarioInfo } from "./ScenarioInfo";
import {
  TIER_COLORS,
  TIER_ORDER,
  formatHitProbability,
  hitMedianYear,
  resolveReferenceScenarioIndex,
  resolveReferenceTierKey,
  scenarioDisplayName,
  scenarioMetrics,
} from "./fireLabels";

type MatrixMode = "reach" | "lasts" | "coast";

function probabilityTone(p: number): "green" | "amber" | "red" {
  if (p >= 0.8) return "green";
  if (p >= 0.5) return "amber";
  return "red";
}

/** Single scenarios × tiers matrix with three views: Reach, Lasts N years, and Coast FIRE. */
export function FireScenarioTable({
  projection,
  baselineProjection,
  currency,
}: {
  projection: FireProjection;
  baselineProjection?: FireProjection;
  currency: string;
}) {
  const { t, i18n } = useTranslation();
  const locale = i18n.language;
  const [mode, setMode] = useState<MatrixMode>("reach");

  const activeTiers: FireTierKey[] = TIER_ORDER.filter((tk) =>
    projection.tiers.some((tier) => tier.key === tk && tier.enabled && tier.targetToday !== null),
  );
  const hasStats = activeTiers.length > 0 && projection.scenarios.some((s) => s.tierStats.length > 0);

  if (!hasStats) {
    return (
      <Card>
        <CardHeader>
          <p className="font-medium">{t("fire.matrix_title")}</p>
        </CardHeader>
        <CardBody>
          <p className="text-sm text-gray-500 dark:text-gray-400">{t("fire.no_tiers_active")}</p>
        </CardBody>
      </Card>
    );
  }

  const activeMonthly = Number(projection.contributions.activeMonthly ?? 0);
  const refScenarioIdx = resolveReferenceScenarioIndex(projection);
  const refTierKey = resolveReferenceTierKey(projection);
  const refScenario = projection.scenarios[refScenarioIdx];
  const refStat = refScenario?.tierStats.find((st) => st.tier === refTierKey);
  const refYear = refStat ? hitMedianYear(refStat.probabilityOfReachingTarget, refStat.medianYearReachingTarget) : "—";

  const modes: { key: MatrixMode; label: string }[] = [
    { key: "reach", label: t("fire.matrix_mode_reach") },
    { key: "lasts", label: t("fire.matrix_mode_lasts", { years: projection.settings.drawdownYears }) },
    { key: "coast", label: t("fire.matrix_mode_coast") },
  ];

  return (
    <Card>
      <CardHeader>
        <div className="flex flex-wrap items-center justify-between gap-2">
          <p className="font-medium">{t("fire.matrix_title")}</p>
          <div className="inline-flex flex-wrap rounded-md border border-border bg-raised p-0.5 text-xs">
            {modes.map((m) => (
              <button
                key={m.key}
                type="button"
                onClick={() => setMode(m.key)}
                className={`rounded px-2.5 py-1 font-medium ${
                  mode === m.key
                    ? "bg-surface text-gray-900 shadow-sm dark:text-gray-100"
                    : "text-gray-500 hover:text-gray-700 dark:text-gray-400"
                }`}
              >
                {m.label}
              </button>
            ))}
          </div>
        </div>
      </CardHeader>
      <CardBody>
        <div className="overflow-x-auto">
          <table className="w-full text-sm">
            <thead className="text-left text-xs text-gray-500 dark:text-gray-400">
              <tr>
                <th className="py-2 pr-3">{t("fire.scenario")}</th>
                {activeTiers.map((tk) => (
                  <th key={tk} className="px-3 py-2">
                    <div className="flex items-center gap-1.5">
                      <span className="h-2 w-2 rounded-full" style={{ backgroundColor: TIER_COLORS[tk] }} />
                      <span className="font-medium text-gray-800 dark:text-gray-200">
                        {tk === "fat"
                          ? t("fire.tier_plain_fat", { multiplier: projection.settings.fatMultiplier })
                          : t(`fire.tier_plain_${tk}`)}
                      </span>
                    </div>
                    <div className="pl-3.5 text-[11px] font-normal text-gray-500 dark:text-gray-400">
                      {t(`fire.tier_${tk}`)}
                    </div>
                  </th>
                ))}
              </tr>
            </thead>
            <tbody className="tabular-nums">
              {projection.scenarios.map((s, sIdx) => {
                const baseS = baselineProjection?.scenarios[sIdx];
                return (
                  <tr key={`${s.meanPercent}_${s.stdDevPercent}_${sIdx}`} className="border-t border-border">
                    <td className="py-2.5 pr-3 align-top">
                      <div className="flex items-center gap-1.5 font-medium text-gray-900 dark:text-gray-100" title={s.historical ? t("fire.historical_tooltip") : undefined}>
                        <span>{scenarioDisplayName(s, sIdx, t)}</span>
                        <ScenarioInfo
                          name={scenarioDisplayName(s, sIdx, t)}
                          meanPercent={s.meanPercent}
                          stdDevPercent={s.stdDevPercent}
                          historical={s.historical}
                          inflationPct={Number(projection.settings.expectedInflationPct)}
                        />
                      </div>
                      <div className="text-xs text-gray-500 dark:text-gray-400">
                        {scenarioMetrics(s, t)}
                      </div>
                    </td>
                    {activeTiers.map((tk) => {
                      const stat = s.tierStats.find((st) => st.tier === tk);
                      const baseStat = baseS?.tierStats.find((st) => st.tier === tk);
                      const isRefCell = sIdx === refScenarioIdx && tk === refTierKey;
                      const cellClass = `px-3 py-2.5 align-top ${
                        isRefCell ? "bg-sky-50/60 ring-1 ring-inset ring-primary/30 dark:bg-sky-950/30" : ""
                      }`;

                      if (!stat) {
                        return (
                          <td key={tk} className={cellClass}>
                            —
                          </td>
                        );
                      }

                      if (mode === "reach") {
                        const year = hitMedianYear(stat.probabilityOfReachingTarget, stat.medianYearReachingTarget);
                        const baseYear = baseStat
                          ? hitMedianYear(baseStat.probabilityOfReachingTarget, baseStat.medianYearReachingTarget)
                          : null;
                        const prob = formatHitProbability(stat.probabilityOfReachingTarget);
                        const baseProb = baseStat ? formatHitProbability(baseStat.probabilityOfReachingTarget) : null;
                        const req = stat.requiredMonthlyToHitTarget !== null ? Number(stat.requiredMonthlyToHitTarget) : null;
                        const shortfall = req !== null ? req - activeMonthly : null;

                        return (
                          <td key={tk} className={cellClass}>
                            <div className="flex flex-wrap items-center gap-1.5">
                              <span className="font-semibold text-gray-900 dark:text-gray-100">
                                {formatDeltaValue(baseYear !== null ? String(baseYear) : null, String(year))}
                              </span>
                              <span className="text-gray-400">·</span>
                              <Badge tone={probabilityTone(stat.probabilityOfReachingTarget)}>
                                {formatDeltaValue(baseProb, prob)}
                              </Badge>
                            </div>
                            {shortfall !== null && shortfall > 0.5 && (
                              <p className="mt-1 text-xs text-amber-700 dark:text-amber-300">
                                {t("fire.matrix_needs_monthly", { amount: formatMoney(shortfall, currency, locale) })}
                              </p>
                            )}
                          </td>
                        );
                      }

                      if (mode === "lasts") {
                        const year = hitMedianYear(stat.probabilityOfReachingTarget, stat.medianYearReachingTarget);
                        const baseYear = baseStat
                          ? hitMedianYear(baseStat.probabilityOfReachingTarget, baseStat.medianYearReachingTarget)
                          : null;
                        const surv = stat.drawdownSurvivalRate !== null ? formatHitProbability(stat.drawdownSurvivalRate) : "—";
                        const baseSurv =
                          baseStat?.drawdownSurvivalRate != null ? formatHitProbability(baseStat.drawdownSurvivalRate) : null;

                        return (
                          <td key={tk} className={cellClass}>
                            <div className="flex flex-wrap items-center gap-1.5">
                              <span className="font-semibold text-gray-900 dark:text-gray-100">
                                {formatDeltaValue(baseYear !== null ? String(baseYear) : null, String(year))}
                              </span>
                              <span className="text-gray-400">·</span>
                              {stat.drawdownSurvivalRate !== null ? (
                                <Badge tone={probabilityTone(stat.drawdownSurvivalRate)}>
                                  {formatDeltaValue(baseSurv, surv)}
                                </Badge>
                              ) : (
                                <span>—</span>
                              )}
                            </div>
                          </td>
                        );
                      }

                      // mode === "coast"
                      const coastTarget = stat.coastTargetToday ? formatMoney(stat.coastTargetToday, currency, locale) : "—";
                      const baseCoastTarget = baseStat?.coastTargetToday
                        ? formatMoney(baseStat.coastTargetToday, currency, locale)
                        : null;
                      const coastProb = formatHitProbability(stat.coastProbabilityOfReachingTarget);
                      const baseCoastProb = baseStat ? formatHitProbability(baseStat.coastProbabilityOfReachingTarget) : null;
                      const coastYear = hitMedianYear(
                        stat.coastProbabilityOfReachingTarget,
                        stat.coastMedianYearReachingTarget,
                      );

                      return (
                        <td key={tk} className={cellClass}>
                          <div className="flex flex-wrap items-center gap-1.5">
                            <span className="font-semibold text-gray-900 dark:text-gray-100">
                              {formatDeltaValue(baseCoastTarget, coastTarget)}
                            </span>
                            <span className="text-gray-400">·</span>
                            <Badge tone={probabilityTone(stat.coastProbabilityOfReachingTarget)}>
                              {formatDeltaValue(baseCoastProb, coastProb)}
                            </Badge>
                          </div>
                          {coastYear !== "—" && (
                            <p className="mt-1 text-xs text-gray-500 dark:text-gray-400">{coastYear}</p>
                          )}
                        </td>
                      );
                    })}
                  </tr>
                );
              })}
            </tbody>
          </table>
        </div>

        <Explain>
          {refScenario && refYear !== "—" && (
            <p className="font-medium text-gray-700 dark:text-gray-200">
              {t("fire.matrix_explain_question", { year: refYear })}
            </p>
          )}
          {refScenario && refYear !== "—" && (
            <p>
              {t("fire.matrix_explain_answer", {
                scenario: scenarioDisplayName(refScenario, refScenarioIdx, t),
                trials: projection.monteCarloTrials.toLocaleString(locale),
                tier: t(`fire.tier_${refTierKey}`),
                year: refYear,
              })}
            </p>
          )}
          <p>{mode === "coast" ? t("fire.coast_explain") : t("fire.table_explain")}</p>
          {projection.settings.applyCapitalGainsTax && <p>{t("fire.table_tax_note")}</p>}
        </Explain>
      </CardBody>
    </Card>
  );
}
