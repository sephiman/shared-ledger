import { Fragment } from "react";
import { useTranslation } from "react-i18next";
import type { FireProjection } from "@/api/fire";
import { Card, CardBody, CardHeader } from "@/components/ui/primitives";
import { Explain } from "./Explain";
import { scenarioLabel } from "./fireLabels";

/**
 * Scenario × tier summary: deterministic hit year, Monte Carlo probability and median year,
 * plus the Coast FIRE counterparts (contributions stopped today).
 */
export function FireScenarioTable({ projection }: { projection: FireProjection }) {
  const { t } = useTranslation();

  const hasStats = projection.scenarios.some((s) => s.tierStats.length > 0);

  return (
    <Card>
      <CardHeader>
        <p className="font-medium">{t("fire.summary")}</p>
      </CardHeader>
      <CardBody>
        {!hasStats ? (
          <p className="text-sm text-gray-500 dark:text-gray-400">{t("fire.no_tiers_active")}</p>
        ) : (
          <div className="overflow-x-auto">
            <table className="w-full text-sm">
              <thead className="text-left text-gray-500 dark:text-gray-400">
                <tr>
                  <th className="py-2 pr-2">{t("fire.scenario")}</th>
                  <th className="pr-2">{t("fire.tier")}</th>
                  <th className="pr-2">{t("fire.target_hit_deterministic")}</th>
                  <th className="pr-2">{t("fire.probability")}</th>
                  <th className="pr-2">{t("fire.median_hit")}</th>
                  <th className="pr-2">{t("fire.coast_probability")}</th>
                  <th>{t("fire.coast_median")}</th>
                </tr>
              </thead>
              <tbody>
                {projection.scenarios.map((s, sIdx) => (
                  <Fragment key={`${s.meanPercent}_${s.stdDevPercent}_${sIdx}`}>
                    {s.tierStats.map((stat, tIdx) => (
                      <tr key={stat.tier} className={tIdx === 0 ? "border-t-2 border-border" : "border-t border-border/60"}>
                        <td className="py-2 pr-2 align-top">
                          {tIdx === 0 && (
                            <span title={s.historical ? t("fire.historical_tooltip") : undefined}>{scenarioLabel(s, t)}</span>
                          )}
                        </td>
                        <td className="pr-2">{t(`fire.tier_${stat.tier}`)}</td>
                        <td className="pr-2">{stat.deterministicHitYear ?? "—"}</td>
                        <td className="pr-2">{(stat.probabilityOfReachingTarget * 100).toFixed(0)}%</td>
                        <td className="pr-2">{stat.medianYearReachingTarget ?? "—"}</td>
                        <td className="pr-2">{(stat.coastProbabilityOfReachingTarget * 100).toFixed(0)}%</td>
                        <td>{stat.coastMedianYearReachingTarget ?? "—"}</td>
                      </tr>
                    ))}
                  </Fragment>
                ))}
              </tbody>
            </table>
          </div>
        )}
        {hasStats && (
          <Explain>
            <p>{t("fire.table_explain")}</p>
            <p>{t("fire.coast_explain")}</p>
            {projection.settings.applyCapitalGainsTax && <p>{t("fire.table_tax_note")}</p>}
          </Explain>
        )}
      </CardBody>
    </Card>
  );
}
