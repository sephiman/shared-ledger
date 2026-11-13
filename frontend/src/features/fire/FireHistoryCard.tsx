import { useState } from "react";
import { useTranslation } from "react-i18next";
import { formatDayMonthYear } from "@/lib/dates";
import { CartesianGrid, Line, LineChart, ResponsiveContainer, Tooltip, XAxis, YAxis } from "recharts";
import type { FireHistory, FireHistoryPoint, FireProjection } from "@/api/fire";
import { Card, CardBody, CardHeader } from "@/components/ui/primitives";
import { formatWholeMoney } from "@/lib/money";
import { Explain } from "./Explain";
import { resolveReferenceScenarioIndex, scenarioDisplayName, estimatedYearText, formatHitProbability, TIER_COLORS, tierPlainName, formatFirePercent } from "./fireLabels";
import { historySummary } from "./fireHistoryText";

interface Props {
  history: FireHistory | undefined;
  isLoading: boolean;
  isError: boolean;
  projection: FireProjection;
  currency: string;
}

/** "How you have done": the reference coverage and year as they looked at each past quarter, plus today. */
export function FireHistoryCard({ history, isLoading, isError, projection, currency }: Props) {
  const { t, i18n } = useTranslation();
  const locale = i18n.language;
  const [open, setOpen] = useState(false);
  const months = projection.spending.lookbackMonths;

  const summary = history ? historySummary(history, locale, t) : null;
  // A single point is a sentence, not a chart.
  const canExpand = Boolean(history && history.points.length > 1);

  return (
    <Card>
      <CardHeader>
        <div className="flex flex-wrap items-center justify-between gap-2">
          <p className="font-medium">{t("fire.history_title")}</p>
          {canExpand && (
            <button
              type="button"
              aria-expanded={open}
              onClick={() => setOpen((v) => !v)}
              className="text-xs text-sky-600 hover:underline dark:text-sky-400"
            >
              {open ? t("fire.section_hide") : t("fire.history_show")}
            </button>
          )}
        </div>
        <p className="mt-1 text-sm text-gray-700 dark:text-gray-300" data-testid="fire-history-summary">
          {isLoading && t("fire.history_loading")}
          {isError && t("fire.section_error")}
          {history && (summary ?? t("fire.history_empty", { count: months }))}
        </p>
      </CardHeader>
      {open && canExpand && history && (
        <CardBody className="space-y-3">
          <HistoryChart history={history} />
          <HistoryTable history={history} />
          <Omissions history={history} months={months} />
          <Explain>
            <p>{t("fire.technical_term", { term: t("fire.history_tech") })}</p>
            <p>{t("fire.history_explain_point", { count: months })}</p>
            <p>
              {t("fire.history_explain_reference", {
                tier: tierPlainName(history.tier, projection.settings.fatMultiplier, locale, t),
                scenario: scenarioDisplayName(projection.scenarios, resolveReferenceScenarioIndex(projection), t),
              })}
            </p>
            <p>
              {history.manualContribution
                ? t("fire.history_explain_manual_contribution", {
                    amount: formatWholeMoney(projection.settings.monthlyContribution, currency, locale),
                  })
                : t("fire.history_explain_derived_contribution")}
            </p>
            {history.manualSpending && <p>{t("fire.history_explain_manual_spending")}</p>}
          </Explain>
        </CardBody>
      )}
    </Card>
  );
}

function HistoryChart({ history }: { history: FireHistory }) {
  const { t, i18n } = useTranslation();
  const locale = i18n.language;
  // A time axis, so uneven gaps between snapshots read as such.
  const data = history.points.map((p) => ({ ...p, time: Date.parse(`${p.date}T00:00:00Z`) }));
  const color = TIER_COLORS[history.tier];
  return (
    <div className="hidden h-56 sm:block" role="img" aria-label={t("fire.history_chart_label")}>
      <ResponsiveContainer width="100%" height="100%">
        <LineChart data={data} margin={{ top: 8, right: 16, bottom: 0, left: 0 }}>
          <CartesianGrid strokeDasharray="3 3" vertical={false} />
          <XAxis
            dataKey="time"
            type="number"
            scale="time"
            domain={["dataMin", "dataMax"]}
            tick={{ fontSize: 12 }}
            minTickGap={32}
            tickFormatter={(v: number) => formatDayMonthYear(new Date(v).toISOString().slice(0, 10), locale)}
          />
          <YAxis
            tick={{ fontSize: 12 }}
            width={48}
            domain={[0, "auto"]}
            tickFormatter={(v: number) => formatFirePercent(v, locale, 0)}
          />
          <Tooltip
            cursor={{ strokeDasharray: "3 3" }}
            content={({ active, payload }) => {
              const point = active ? (payload?.[0]?.payload as FireHistoryPoint | undefined) : undefined;
              if (!point) return null;
              return (
                <div className="rounded-md border border-border-strong bg-overlay p-2 text-xs shadow-sm">
                  <p className="mb-1 font-medium text-gray-900 dark:text-gray-100">
                    {point.current ? t("fire.history_today") : formatDayMonthYear(point.date, locale)}
                  </p>
                  <p className="tabular-nums text-gray-900 dark:text-gray-100">
                    {t("fire.history_tooltip_coverage", { coverage: formatFirePercent(point.coveragePercent, locale, 1) })}
                  </p>
                  <p className="tabular-nums text-gray-700 dark:text-gray-300">
                    {t("fire.history_tooltip_year", { year: estimatedYearText(point.estimatedYear, history.horizonYear, t) })}
                  </p>
                  {point.probability !== null && (
                    <p className="tabular-nums text-gray-700 dark:text-gray-300">
                      {t("fire.history_tooltip_chance", { chance: formatHitProbability(point.probability, locale) })}
                    </p>
                  )}
                </div>
              );
            }}
          />
          <Line
            type="linear"
            dataKey="coveragePercent"
            name={t("fire.coverage")}
            stroke={color}
            strokeWidth={2}
            dot={{ r: 4, strokeWidth: 2, fill: "var(--color-surface, #fff)" }}
            activeDot={{ r: 5 }}
            isAnimationActive={false}
          />
        </LineChart>
      </ResponsiveContainer>
    </div>
  );
}

/** The same points as a compact table — the view on phones, where a tooltip needs a hover that isn't there. */
function HistoryTable({ history }: { history: FireHistory }) {
  const { t, i18n } = useTranslation();
  const locale = i18n.language;
  return (
    <table className="w-full text-sm sm:hidden">
      <thead className="text-left text-xs text-gray-500 dark:text-gray-400">
        <tr>
          <th className="py-1 font-medium">{t("fire.history_col_date")}</th>
          <th className="py-1 text-right font-medium">{t("fire.history_col_coverage")}</th>
          <th className="py-1 text-right font-medium">{t("fire.history_col_year")}</th>
        </tr>
      </thead>
      <tbody className="tabular-nums">
        {[...history.points].reverse().map((p) => (
          <tr key={p.date} className="border-t border-border">
            <td className="py-1">{p.current ? t("fire.history_today") : formatDayMonthYear(p.date, locale)}</td>
            <td className="py-1 text-right">{formatFirePercent(p.coveragePercent, locale, 1)}</td>
            <td className="py-1 text-right">{estimatedYearText(p.estimatedYear, history.horizonYear, t)}</td>
          </tr>
        ))}
      </tbody>
    </table>
  );
}

function Omissions({ history, months }: { history: FireHistory; months: number }) {
  const { t } = useTranslation();
  if (history.omitted.length === 0) return null;
  return (
    <ul className="space-y-0.5 text-xs text-gray-500 dark:text-gray-400">
      {history.omitted.map((o) => (
        <li key={o.reason}>{t(`fire.history_omitted_${o.reason}`, { count: o.count, months })}</li>
      ))}
    </ul>
  );
}
