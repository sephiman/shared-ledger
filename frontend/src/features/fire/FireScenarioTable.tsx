import { Fragment } from "react";
import { useTranslation } from "react-i18next";
import type { FireProjection } from "@/api/fire";
import { Badge, Card, CardBody, CardHeader } from "@/components/ui/primitives";
import { formatMoney } from "@/lib/money";
import { Explain } from "./Explain";
import { TIER_COLORS, formatHitProbability, hitMedianYear, scenarioLabel } from "./fireLabels";

function probabilityTone(p: number): "green" | "amber" | "red" {
  if (p >= 0.8) return "green";
  if (p >= 0.5) return "amber";
  return "red";
}

/** Two focused scenario × tier cards: Accumulation & Drawdown, and Coast FIRE. */
export function FireScenarioTable({ projection, currency }: { projection: FireProjection; currency: string }) {
  const { t, i18n } = useTranslation();
  const locale = i18n.language;
  const hasStats = projection.scenarios.some((s) => s.tierStats.length > 0);
  const activeMonthly = Number(projection.contributions.activeMonthly ?? 0);
  const nonHist = projection.scenarios.filter((s) => !s.historical);
  const pool = nonHist.length > 0 ? nonHist : projection.scenarios;
  const refScenario = pool[Math.floor(pool.length / 2)];
  const primaryCoastStat =
    refScenario?.tierStats.find((st) => st.tier === "fire") ?? refScenario?.tierStats[0] ?? null;
  const coastTargetNum = primaryCoastStat?.coastTargetToday != null ? Number(primaryCoastStat.coastTargetToday) : null;
  const startingWealthNum = Number(projection.startingValue);
  const coastGap = coastTargetNum !== null ? coastTargetNum - startingWealthNum : null;

  if (!hasStats) {
    return (
      <Card>
        <CardHeader>
          <p className="font-medium">{t("fire.summary")}</p>
        </CardHeader>
        <CardBody>
          <p className="text-sm text-gray-500 dark:text-gray-400">{t("fire.no_tiers_active")}</p>
        </CardBody>
      </Card>
    );
  }

  return (
    <div className="space-y-4">
      <Card>
        <CardHeader>
          <p className="font-medium">{t("fire.summary")}</p>
        </CardHeader>
        <CardBody>
          <div className="overflow-x-auto">
            <table className="w-full text-sm">
              <thead className="text-left text-xs text-gray-500 dark:text-gray-400">
                <tr>
                  <th className="py-2 pr-3">{t("fire.scenario")}</th>
                  <th className="pr-3">{t("fire.tier")}</th>
                  <th className="pr-3">{t("fire.target_hit_deterministic")}</th>
                  <th className="pr-3">{t("fire.required_monthly")}</th>
                  <th className="pr-3">{t("fire.probability")}</th>
                  <th className="pr-3">{t("fire.median_hit")}</th>
                  <th>{t("fire.drawdown_survival", { years: projection.settings.drawdownYears })}</th>
                </tr>
              </thead>
              <tbody className="tabular-nums">
                {projection.scenarios.map((s, sIdx) => (
                  <Fragment key={`${s.meanPercent}_${s.stdDevPercent}_${sIdx}`}>
                    {s.tierStats.map((stat, tIdx) => {
                      const req = stat.requiredMonthlyToHitTarget !== null ? Number(stat.requiredMonthlyToHitTarget) : null;
                      const delta = req !== null ? req - activeMonthly : null;
                      return (
                        <tr key={stat.tier} className={tIdx === 0 ? "border-t-2 border-border" : "border-t border-border/60"}>
                          <td className="py-2 pr-3 align-top font-medium">
                            {tIdx === 0 && (
                              <span title={s.historical ? t("fire.historical_tooltip") : undefined}>{scenarioLabel(s, t)}</span>
                            )}
                          </td>
                          <td className="pr-3">
                            <span className="inline-flex items-center gap-1.5">
                              <span className="h-2 w-2 rounded-full" style={{ backgroundColor: TIER_COLORS[stat.tier] }} />
                              {t(`fire.tier_${stat.tier}`)}
                            </span>
                          </td>
                          <td className="pr-3">{stat.deterministicHitYear ?? "—"}</td>
                          <td className="pr-3">
                            {req !== null ? (
                              <span className={delta !== null && delta <= 0 ? "text-emerald-700 dark:text-emerald-400" : "text-amber-700 dark:text-amber-300"}>
                                {formatMoney(req, currency, locale)}
                                {delta !== null && delta <= 0 ? (
                                  <span className="ml-1 text-xs opacity-80">({t("fire.on_track")})</span>
                                ) : (
                                  delta !== null &&
                                  delta > 0.5 && (
                                    <span className="ml-1 text-xs opacity-80">(+{formatMoney(delta, currency, locale)})</span>
                                  )
                                )}
                              </span>
                            ) : (
                              "—"
                            )}
                          </td>
                          <td className="pr-3">
                            <Badge tone={probabilityTone(stat.probabilityOfReachingTarget)}>
                              {formatHitProbability(stat.probabilityOfReachingTarget)}
                            </Badge>
                          </td>
                          <td className="pr-3">{hitMedianYear(stat.probabilityOfReachingTarget, stat.medianYearReachingTarget)}</td>
                          <td>
                            {stat.drawdownSurvivalRate !== null ? (
                              <Badge tone={probabilityTone(stat.drawdownSurvivalRate)}>
                                {formatHitProbability(stat.drawdownSurvivalRate)}
                              </Badge>
                            ) : (
                              "—"
                            )}
                          </td>
                        </tr>
                      );
                    })}
                  </Fragment>
                ))}
              </tbody>
            </table>
          </div>
          <Explain>
            <p>{t("fire.table_explain")}</p>
            {projection.settings.applyCapitalGainsTax && <p>{t("fire.table_tax_note")}</p>}
          </Explain>
        </CardBody>
      </Card>

      <Card>
        <CardHeader>
          <div className="flex flex-wrap items-center justify-between gap-2">
            <p className="font-medium">{t("fire.coast_card_title")}</p>
            {coastGap !== null && (
              <Badge tone={coastGap <= 0 ? "green" : "amber"}>
                {coastGap <= 0
                  ? t("fire.coast_reached")
                  : t("fire.coast_gap", { amount: formatMoney(coastGap, currency, locale) })}
              </Badge>
            )}
          </div>
        </CardHeader>
        <CardBody>
          <div className="overflow-x-auto">
            <table className="w-full text-sm">
              <thead className="text-left text-xs text-gray-500 dark:text-gray-400">
                <tr>
                  <th className="py-2 pr-3">{t("fire.scenario")}</th>
                  <th className="pr-3">{t("fire.tier")}</th>
                  <th className="pr-3">{t("fire.coast_target")}</th>
                  <th className="pr-3">{t("fire.coast_probability")}</th>
                  <th>{t("fire.coast_median")}</th>
                </tr>
              </thead>
              <tbody className="tabular-nums">
                {projection.scenarios.map((s, sIdx) => (
                  <Fragment key={`coast_${s.meanPercent}_${s.stdDevPercent}_${sIdx}`}>
                    {s.tierStats.map((stat, tIdx) => (
                      <tr key={stat.tier} className={tIdx === 0 ? "border-t-2 border-border" : "border-t border-border/60"}>
                        <td className="py-2 pr-3 align-top font-medium">
                          {tIdx === 0 && (
                            <span title={s.historical ? t("fire.historical_tooltip") : undefined}>{scenarioLabel(s, t)}</span>
                          )}
                        </td>
                        <td className="pr-3">
                          <span className="inline-flex items-center gap-1.5">
                            <span className="h-2 w-2 rounded-full" style={{ backgroundColor: TIER_COLORS[stat.tier] }} />
                            {t(`fire.tier_${stat.tier}`)}
                          </span>
                        </td>
                        <td className="pr-3">{stat.coastTargetToday ? formatMoney(stat.coastTargetToday, currency, locale) : "—"}</td>
                        <td className="pr-3">
                          <Badge tone={probabilityTone(stat.coastProbabilityOfReachingTarget)}>
                            {formatHitProbability(stat.coastProbabilityOfReachingTarget)}
                          </Badge>
                        </td>
                        <td>{hitMedianYear(stat.coastProbabilityOfReachingTarget, stat.coastMedianYearReachingTarget)}</td>
                      </tr>
                    ))}
                  </Fragment>
                ))}
              </tbody>
            </table>
          </div>
          <Explain>
            <p>{t("fire.coast_explain")}</p>
          </Explain>
        </CardBody>
      </Card>
    </div>
  );
}
