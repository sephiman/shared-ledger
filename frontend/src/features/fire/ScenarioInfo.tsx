import { useState } from "react";
import { useTranslation } from "react-i18next";
import { findMatchingSeedPortfolio, realEquivalentPct, typicalCompoundReturnPct } from "./fireLabels";

interface Props {
  name: string;
  meanPercent: string;
  stdDevPercent: string;
  historical?: boolean;
  inflationPct: number;
}

/** Per-scenario ⓘ disclosure showing nominal arithmetic mean, exact exp(μ_ln) - 1 compound growth,
 *  real equivalent, and (for current seed pairs) portfolio resemblance + seeded-convention note. */
export function ScenarioInfo({ name, meanPercent, stdDevPercent, historical, inflationPct }: Props) {
  const { t } = useTranslation();
  const [open, setOpen] = useState(false);

  const meanNum = Number(meanPercent);
  const stdNum = Number(stdDevPercent);
  const compound = typicalCompoundReturnPct(meanNum, stdNum);
  const realEq = realEquivalentPct(compound, inflationPct);
  const seedPortfolio = findMatchingSeedPortfolio({ meanPercent, stdDevPercent, historical });

  return (
    <span className="relative inline-block">
      <button
        type="button"
        aria-label={t("fire.scenario_info_aria", { name })}
        aria-expanded={open}
        onClick={() => setOpen((v) => !v)}
        className="inline-flex h-4 w-4 items-center justify-center rounded-full border border-border text-[10px] font-semibold text-gray-500 hover:bg-raised hover:text-gray-800 dark:text-gray-400 dark:hover:text-gray-100"
      >
        i
      </button>
      {open && (
        <div
          role="region"
          aria-label={name}
          className="mt-1.5 w-64 rounded-md border border-border bg-surface p-2.5 text-xs font-normal text-gray-700 shadow-sm dark:text-gray-200"
        >
          <p>{t("fire.scenario_info_nominal_mean", { pct: meanNum.toFixed(1) })}</p>
          <p>{t("fire.scenario_info_compound", { pct: compound.toFixed(2) })}</p>
          <p>{t("fire.scenario_info_real", { inflation: inflationPct.toFixed(1), pct: realEq.toFixed(2) })}</p>
          {seedPortfolio && (
            <p className="mt-1.5 border-t border-border/60 pt-1.5 text-gray-500 dark:text-gray-400">
              {t(`fire.scenario_seed_portfolio_${seedPortfolio}`)} {t("fire.scenario_seed_convention")}
            </p>
          )}
        </div>
      )}
    </span>
  );
}
