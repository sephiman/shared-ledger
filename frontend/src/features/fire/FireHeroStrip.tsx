import { type ReactNode, useState } from "react";
import { useTranslation } from "react-i18next";
import { StaleSpendingNotice } from "./StaleSpendingNotice";
import type { FireProjection } from "@/api/fire";
import { Badge, Button, Card, CardBody } from "@/components/ui/primitives";
import { formatWholeMoney, formatNumber } from "@/lib/money";
import { buildHeadline } from "./fireHeadline";
import { estimatedYearText, resolveReferenceScenario, resolveReferenceTier, formatFirePercent } from "./fireLabels";

const EXPLAINER_DISMISSED_KEY = "fire-explainer-dismissed";

function readExplainerDismissed(): boolean {
  try {
    return window.localStorage.getItem(EXPLAINER_DISMISSED_KEY) === "1";
  } catch {
    return false;
  }
}

function writeExplainerDismissed(dismissed: boolean): void {
  try {
    if (dismissed) {
      window.localStorage.setItem(EXPLAINER_DISMISSED_KEY, "1");
    } else {
      window.localStorage.removeItem(EXPLAINER_DISMISSED_KEY);
    }
  } catch {
    // ignore storage errors in restricted environments
  }
}

export function formatDeltaValue(
  baseText: string | null,
  activeText: string,
): ReactNode {
  if (baseText === null || baseText === activeText) return activeText;
  return (
    <>
      <span className="text-gray-400 line-through dark:text-gray-500">{baseText}</span>
      {" → "}
      <span className="text-sky-600 dark:text-sky-400">{activeText}</span>
    </>
  );
}

interface Props {
  projection: FireProjection;
  baselineProjection?: FireProjection;
  currency: string;
  whatIfOpen?: boolean;
  isWhatIfActive?: boolean;
  onToggleWhatIf?: () => void;
  onBackToPlan?: () => void;
  whatIfSlot?: ReactNode;
}

/** The Photo: first-visit explainer, plain-language headline with reference plan, optional inline what-if sandbox, and four key numbers. */
export function FireHeroStrip({
  projection,
  baselineProjection,
  currency,
  whatIfOpen = false,
  isWhatIfActive = false,
  onToggleWhatIf,
  onBackToPlan,
  whatIfSlot,
}: Props) {
  const { t, i18n } = useTranslation();
  const locale = i18n.language;
  const money = (v: string | number | null | undefined) => formatWholeMoney(v ?? 0, currency, locale);
  const [explainerOpen, setExplainerOpen] = useState(() => !readExplainerDismissed());

  function toggleExplainer(open: boolean) {
    setExplainerOpen(open);
    writeExplainerDismissed(!open);
  }

  const refTier = resolveReferenceTier(projection);
  const { scenario: refScenario } = resolveReferenceScenario(projection);
  const refStat =
    refScenario && refTier ? refScenario.tierStats.find((st) => st.tier === refTier.key) ?? null : null;

  const baseTier = baselineProjection ? resolveReferenceTier(baselineProjection) : undefined;
  const baseScenario = baselineProjection ? resolveReferenceScenario(baselineProjection).scenario : undefined;
  const baseStat =
    baseScenario && baseTier ? baseScenario.tierStats.find((st) => st.tier === baseTier.key) ?? null : null;

  const coverageText =
    refTier?.coveragePercent != null ? formatFirePercent(refTier.coveragePercent, locale, 0) : "—";
  const baseCoverageText =
    baseTier?.coveragePercent != null ? formatFirePercent(baseTier.coveragePercent, locale, 0) : null;

  const estimatedYear = refStat
    ? estimatedYearText(refStat.estimatedYear, projection.settings.targetYear, t)
    : "—";
  const baseEstimatedYear =
    baseStat && baselineProjection
      ? estimatedYearText(baseStat.estimatedYear, baselineProjection.settings.targetYear, t)
      : null;

  const contribText = money(projection.contributions.activeMonthly ?? "0");
  const baseContribText = baselineProjection ? money(baselineProjection.contributions.activeMonthly ?? "0") : null;
  const contribSourceLabel = t(`fire.photo_contrib_source_${projection.contributions.mode}`);

  const savingsRatePct = projection.contributions.savingsRatePct;
  const savingsRateText = savingsRatePct !== null ? formatFirePercent(savingsRatePct, locale, 0) : "—";
  const baseSavingsRateText =
    baselineProjection?.contributions.savingsRatePct != null
      ? formatFirePercent(baselineProjection.contributions.savingsRatePct, locale, 0)
      : null;

  const savingsRateMainLabel =
    savingsRatePct !== null
      ? t("fire.photo_savings_rate_main", {
          unit: money(100),
          keptMoney: money(Math.round(savingsRatePct)),
          kept: formatNumber(Math.round(savingsRatePct), locale),
        })
      : t("fire.savings_rate_label");

  const headline = buildHeadline(projection, currency, locale, t);

  return (
    <div className="space-y-3">
      {/* First-visit collapsible explainer */}
      {explainerOpen ? (
        <div className="rounded-lg border border-sky-200 bg-sky-50/70 px-4 py-3 text-sm text-gray-700 dark:border-sky-800/60 dark:bg-sky-950/30 dark:text-gray-200">
          <div className="flex items-start justify-between gap-3">
            <div className="space-y-1">
              <p className="font-medium text-gray-900 dark:text-gray-100">{t("fire.explainer_title")}</p>
              <p>{t("fire.explainer_s1")}</p>
              <p>{t("fire.explainer_s2")}</p>
              <p>{t("fire.explainer_s3")}</p>
            </div>
            <button
              type="button"
              onClick={() => toggleExplainer(false)}
              className="shrink-0 rounded-md border border-sky-300 bg-white px-2.5 py-1 text-xs font-medium text-sky-700 hover:bg-sky-100 dark:border-sky-700 dark:bg-sky-900/50 dark:text-sky-200 dark:hover:bg-sky-900"
            >
              {t("fire.explainer_dismiss")}
            </button>
          </div>
        </div>
      ) : (
        <div>
          <button
            type="button"
            onClick={() => toggleExplainer(true)}
            className="text-xs text-sky-600 hover:underline dark:text-sky-400"
          >
            {t("fire.explainer_title")}
          </button>
        </div>
      )}

      {/* Headline */}
      <Card>
        <CardBody className="space-y-3">
          <div className="flex flex-wrap items-start justify-between gap-3">
            <div className="min-w-0 flex-1 space-y-1.5">
              {isWhatIfActive && (
                <div>
                  <Badge tone="sky">{t("fire.what_if_badge")}</Badge>
                </div>
              )}
              <p className="text-base font-medium leading-relaxed text-gray-900 dark:text-gray-100 sm:text-lg">
                {headline}
              </p>
              <StaleSpendingNotice since={projection.spending.noSpendingSince} />
            </div>
            {onToggleWhatIf && (
              <div className="flex shrink-0 items-center gap-2">
                {whatIfOpen || isWhatIfActive ? (
                  <Button variant="secondary" onClick={onBackToPlan ?? onToggleWhatIf}>
                    {t("fire.what_if_back")}
                  </Button>
                ) : (
                  <Button variant="secondary" onClick={onToggleWhatIf}>
                    {t("fire.what_if_button")}
                  </Button>
                )}
              </div>
            )}
          </div>

          {whatIfSlot}
        </CardBody>
      </Card>

      {/* Four numbers — nothing else */}
      <div className="grid grid-cols-1 gap-3 sm:grid-cols-2 lg:grid-cols-4">
        <Card>
          <CardBody>
            <p className="text-2xl font-semibold tabular-nums">
              {formatDeltaValue(baseCoverageText, coverageText)}
            </p>
            <p className="mt-1 text-sm text-gray-700 dark:text-gray-200">
              {t("fire.photo_coverage_main", { pct: coverageText })}
            </p>
            <p className="mt-0.5 text-xs text-gray-500 dark:text-gray-400">
              {t("fire.photo_coverage_tech")}
            </p>
          </CardBody>
        </Card>

        <Card>
          <CardBody>
            <p className="text-2xl font-semibold tabular-nums">
              {formatDeltaValue(baseEstimatedYear, estimatedYear)}
            </p>
            <p className="mt-1 text-sm text-gray-700 dark:text-gray-200">
              {t("fire.photo_year_main")}
            </p>
            <p className="mt-0.5 text-xs text-gray-500 dark:text-gray-400">
              {t("fire.photo_year_tech")}
            </p>
          </CardBody>
        </Card>

        <Card>
          <CardBody>
            <p className="text-2xl font-semibold tabular-nums">
              {formatDeltaValue(baseContribText, contribText)}
            </p>
            <p className="mt-1 text-sm text-gray-700 dark:text-gray-200">
              {t("fire.photo_contrib_main")}
            </p>
            <p className="mt-0.5 text-xs text-gray-500 dark:text-gray-400">
              {contribSourceLabel}
            </p>
          </CardBody>
        </Card>

        <Card>
          <CardBody>
            <p className="text-2xl font-semibold tabular-nums">
              {formatDeltaValue(baseSavingsRateText, savingsRateText)}
            </p>
            <p className="mt-1 text-sm text-gray-700 dark:text-gray-200">
              {savingsRateMainLabel}
            </p>
            <p className="mt-0.5 text-xs text-gray-500 dark:text-gray-400">
              {t("fire.photo_savings_rate_tech")}
            </p>
          </CardBody>
        </Card>
      </div>
    </div>
  );
}
