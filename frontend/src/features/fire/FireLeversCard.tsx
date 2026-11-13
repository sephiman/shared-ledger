import { useTranslation } from "react-i18next";
import type { TFunction } from "i18next";
import type { FireLeverRow, FireLevers, FirePreviewRequest } from "@/api/fire";
import { Badge } from "@/components/ui/primitives";
import { formatNumber, formatWholeMoney } from "@/lib/money";
import { cn } from "@/lib/cn";
import { Explain } from "./Explain";
import { estimatedYearText, formatHitProbability, formatFirePercent } from "./fireLabels";

interface Props {
  levers: FireLevers | undefined;
  /** The reference scenario's display name, from the photo. */
  scenarioName: string;
  isLoading: boolean;
  isError: boolean;
  currency: string;
  onOpen: (preview: FirePreviewRequest) => void;
}

/** The body of "What moves your year most": one sandbox run per lever, most helpful first. */
export function FireLeversCard({ levers, scenarioName, isLoading, isError, currency, onOpen }: Props) {
  const { t, i18n } = useTranslation();
  const locale = i18n.language;
  if (isLoading) return <p className="text-sm text-gray-500 dark:text-gray-400">{t("fire.history_loading")}</p>;
  if (isError || !levers) return <p className="text-sm text-gray-500 dark:text-gray-400">{t("fire.section_error")}</p>;
  return (
    <div className="space-y-2">
      <p className="text-xs text-gray-500 dark:text-gray-400">
        {t("fire.levers_today", {
          year: estimatedYearText(levers.baselineYear, levers.horizonYear, t),
          chance: levers.baselineProbability !== null ? formatHitProbability(levers.baselineProbability, locale) : "—",
        })}
      </p>
      <table className="w-full text-sm">
        <thead className="text-left text-xs text-gray-500 dark:text-gray-400">
          <tr>
            <th className="py-1 font-medium">{t("fire.levers_col_lever")}</th>
            <th className="py-1 text-right font-medium">{t("fire.levers_col_year")}</th>
            <th className="py-1 text-right font-medium">{t("fire.levers_col_chance")}</th>
          </tr>
        </thead>
        <tbody>
          {levers.rows.map((row) => (
            <LeverRow key={row.key} row={row} levers={levers} currency={currency} onOpen={onOpen} />
          ))}
        </tbody>
      </table>
      <Explain>
        <p>{t("fire.technical_term", { term: t("fire.levers_tech") })}</p>
        <p>{t("fire.levers_explain_runs", { scenario: scenarioName })}</p>
        <p>{t("fire.levers_explain_order")}</p>
        <p>{t("fire.levers_explain_open")}</p>
      </Explain>
    </div>
  );
}

function LeverRow({
  row,
  levers,
  currency,
  onOpen,
}: {
  row: FireLeverRow;
  levers: FireLevers;
  currency: string;
  onOpen: (preview: FirePreviewRequest) => void;
}) {
  const { t, i18n } = useTranslation();
  const locale = i18n.language;
  const label = leverLabel(row, currency, locale, t);
  const preview = row.preview;
  const delays = (row.probabilityChangePoints ?? 0) < 0;
  return (
    <tr className="border-t border-border align-top">
      <td className="py-1.5 pr-2">
        {preview ? (
          <button
            type="button"
            onClick={() => onOpen(preview)}
            className="text-left text-sky-700 hover:underline dark:text-sky-300"
          >
            {label}
          </button>
        ) : (
          <span>{label}</span>
        )}
        {row.outOfYourHands && (
          <Badge tone="neutral" className="ml-1.5 align-middle">
            {t("fire.levers_not_in_your_hands")}
          </Badge>
        )}
        {!row.applicable && (
          <span className="block text-xs text-gray-500 dark:text-gray-400">{t("fire.levers_not_applicable")}</span>
        )}
      </td>
      <td className="py-1.5 text-right tabular-nums">
        {row.applicable && (
          <>
            <span className="block">{estimatedYearText(row.estimatedYear, levers.horizonYear, t)}</span>
            <span className="block text-xs text-gray-500 dark:text-gray-400">{yearChangeText(row, t)}</span>
          </>
        )}
      </td>
      <td className="py-1.5 text-right tabular-nums">
        {row.applicable && row.probabilityChangePoints !== null && (
          <span className={cn("font-medium", delays ? "text-rose-700 dark:text-rose-300" : "text-gray-900 dark:text-gray-100")}>
            {pointsText(row.probabilityChangePoints, locale, t)}
          </span>
        )}
        {row.applicable && row.probability !== null && (
          <span className="block text-xs text-gray-500 dark:text-gray-400">{formatHitProbability(row.probability, locale)}</span>
        )}
      </td>
    </tr>
  );
}

/** "Biggest lever: spend 5% less (+8 points)" — the lever in your hands that most raises the chance of getting there. */
export function leversSummary(levers: FireLevers, currency: string, locale: string, t: TFunction): string {
  const best = levers.rows
    .filter((r) => r.applicable && !r.outOfYourHands && r.probabilityChangePoints !== null)
    .reduce<FireLeverRow | null>((top, r) => (top === null || (r.probabilityChangePoints ?? 0) > (top.probabilityChangePoints ?? 0) ? r : top), null);
  const points = Math.round(best?.probabilityChangePoints ?? 0);
  if (!best || points <= 0) return t("fire.levers_summary_none");
  const label = leverLabel(best, currency, locale, t);
  return t("fire.levers_summary_best", {
    lever: label.charAt(0).toLocaleLowerCase(locale) + label.slice(1),
    count: points,
    points: `+${formatNumber(points, locale)}`,
  });
}

export function leverLabel(row: FireLeverRow, currency: string, locale: string, t: TFunction): string {
  const money = (v: string | null) => formatWholeMoney(v ?? 0, currency, locale);
  const pct = (v: string | null) => formatFirePercent(Number(v ?? 0), locale, Number(v ?? 0) % 1 === 0 ? 0 : 1);
  switch (row.key) {
    case "contribute_more":
      return t("fire.lever_contribute_more", { amount: money(row.amount) });
    case "spend_less":
      return t("fire.lever_spend_less", { pct: pct(row.percent) });
    case "lump_sum":
      return t("fire.lever_lump_sum", { amount: money(row.amount) });
    case "safer_withdrawal":
      return t("fire.lever_safer_withdrawal", { to: pct(row.withdrawalToPct), from: pct(row.withdrawalFromPct) });
    case "returns_up":
      return t("fire.lever_returns_up");
    case "returns_down":
      return t("fire.lever_returns_down");
  }
}

function yearChangeText(row: FireLeverRow, t: TFunction): string {
  if (row.yearChange === null) return "";
  if (row.yearChange === 0) return t("fire.levers_same_year");
  return row.yearChange < 0
    ? t("fire.levers_years_sooner", { count: -row.yearChange })
    : t("fire.levers_years_later", { count: row.yearChange });
}

function pointsText(points: number, locale: string, t: TFunction): string {
  const abs = formatNumber(Math.abs(points), locale, 1);
  const sign = points > 0 ? "+" : points < 0 ? "−" : "";
  return t("fire.levers_points", { value: `${sign}${abs}` });
}
