import { useState } from "react";
import { useTranslation } from "react-i18next";
import { formatDayMonthYear } from "@/lib/dates";
import { formatFirePercent } from "./fireLabels";
import { useFireAttribution, type FireAttribution, type FireHistory } from "@/api/fire";
import { Card, CardBody, CardHeader, Label, Select } from "@/components/ui/primitives";
import { formatWholeMoney } from "@/lib/money";
import { cn } from "@/lib/cn";
import { Explain } from "./Explain";
import { StaleSpendingNotice } from "./StaleSpendingNotice";
import { attributionSentence, signedPoints } from "./fireHistoryText";

interface Props {
  householdId: string;
  history: FireHistory;
  currency: string;
  /** From today's photo: the attribution ends today, so the same caveat applies to its "what you need" part. */
  noSpendingSince: string | null;
}

/** "Where the change came from": today's coverage against an earlier point, split into causes that add up. */
export function FireAttributionCard({ householdId, history, currency, noSpendingSince }: Props) {
  const { t, i18n } = useTranslation();
  const locale = i18n.language;
  const [open, setOpen] = useState(false);
  const [chosenFrom, setChosenFrom] = useState<string | null>(null);
  const defaultAttribution = history.attribution;
  const now = history.points.at(-1);
  const pastPoints = history.points.filter((p) => !p.current);

  const asked = chosenFrom !== null && chosenFrom !== defaultAttribution?.fromDate ? chosenFrom : null;
  const chosen = useFireAttribution(householdId, asked);
  if (!defaultAttribution || !now) return null;
  const attribution = asked ? chosen.data : defaultAttribution;
  const fromDate = asked ?? defaultAttribution.fromDate;

  return (
    <Card>
      <CardHeader>
        <div className="flex flex-wrap items-center justify-between gap-2">
          <p className="font-medium">{t("fire.attribution_title")}</p>
          <button
            type="button"
            aria-expanded={open}
            onClick={() => setOpen((v) => !v)}
            className="text-xs text-sky-600 hover:underline dark:text-sky-400"
          >
            {open ? t("fire.section_hide") : t("fire.attribution_show")}
          </button>
        </div>
        <p className="mt-1 text-sm text-gray-700 dark:text-gray-300" data-testid="fire-attribution-sentence">
          {attribution ? attributionSentence(attribution, locale, t) : t("fire.history_loading")}
        </p>
        <p className="mt-0.5 text-xs text-gray-500 dark:text-gray-400">
          {t("fire.attribution_compared_with", { date: formatDayMonthYear(fromDate, locale) })}
        </p>
        <div className="mt-1.5">
          <StaleSpendingNotice since={noSpendingSince} />
        </div>
      </CardHeader>
      {open && attribution && (
        <CardBody className="space-y-4">
          {pastPoints.length > 1 && (
            <div className="max-w-xs">
              <Label htmlFor="fire-attribution-from">{t("fire.attribution_compare_label")}</Label>
              <Select id="fire-attribution-from" value={fromDate} onChange={(e) => setChosenFrom(e.target.value)}>
                {[...pastPoints].reverse().map((p) => (
                  <option key={p.date} value={p.date}>
                    {formatDayMonthYear(p.date, locale)}
                  </option>
                ))}
              </Select>
            </div>
          )}
          {attribution.merged && (
            <p className="rounded-md border border-amber-300/70 bg-amber-50 p-2 text-xs text-amber-900 dark:border-amber-700/60 dark:bg-amber-950/40 dark:text-amber-200">
              {t("fire.attribution_merged_notice")}
            </p>
          )}
          <Waterfall attribution={attribution} />
          <EuroDetail attribution={attribution} currency={currency} />
          <Explain>
            <p>{t("fire.technical_term", { term: t("fire.attribution_tech") })}</p>
            <p>{t("fire.attribution_explain_split")}</p>
            <p>{t("fire.attribution_explain_need")}</p>
            <p>{t("fire.attribution_explain_rounding")}</p>
          </Explain>
        </CardBody>
      )}
    </Card>
  );
}

interface Step {
  key: string;
  label: string;
  points: number;
}

function stepsOf(a: FireAttribution, t: (key: string) => string): Step[] {
  const wealthSteps: Step[] =
    a.contributedPoints !== null && a.marketPoints !== null
      ? [
          { key: "contributed", label: t("fire.attribution_label_contributed"), points: Number(a.contributedPoints) },
          { key: "market", label: t("fire.attribution_label_market"), points: Number(a.marketPoints) },
        ]
      : [{ key: "wealth", label: t("fire.attribution_label_wealth"), points: Number(a.wealthPoints) }];
  return [...wealthSteps, { key: "need", label: t("fire.attribution_label_need"), points: Number(a.needPoints) }];
}

/** Start coverage → one floating bar per cause → today's coverage, all on one shared scale. */
function Waterfall({ attribution }: { attribution: FireAttribution }) {
  const { t, i18n } = useTranslation();
  const locale = i18n.language;
  const start = (Number(attribution.wealthFrom) / Number(attribution.targetFrom)) * 100;
  const end = (Number(attribution.wealthTo) / Number(attribution.targetTo)) * 100;
  const steps = stepsOf(attribution, t);
  const levels = steps.reduce<number[]>((acc, s) => [...acc, (acc.at(-1) ?? start) + s.points], [start]);
  const max = Math.max(...levels, end, 1);
  const pct = (v: number) => `${(Math.max(v, 0) / max) * 100}%`;

  const rows = [
    { key: "start", label: formatDayMonthYear(attribution.fromDate, locale), from: 0, to: start, kind: "total" as const, value: formatFirePercent(start, locale, 1) },
    ...steps.map((s, i) => ({
      key: s.key,
      label: s.label,
      from: Math.min(levels[i], levels[i + 1]),
      to: Math.max(levels[i], levels[i + 1]),
      kind: s.points < 0 ? ("down" as const) : ("up" as const),
      value: signedPoints(s.points, locale),
    })),
    { key: "end", label: t("fire.history_today"), from: 0, to: end, kind: "total" as const, value: formatFirePercent(end, locale, 1) },
  ];

  return (
    <div role="table" aria-label={t("fire.attribution_waterfall_label")} className="space-y-1.5 text-xs">
      {rows.map((r) => (
        <div role="row" key={r.key} className="grid grid-cols-[minmax(6rem,9rem)_1fr_3.5rem] items-center gap-2">
          <span role="cell" className="truncate text-gray-600 dark:text-gray-300">{r.label}</span>
          <span role="cell" className="relative h-4 rounded bg-raised">
            <span
              className={cn(
                "absolute inset-y-0 rounded",
                r.kind === "total" && "bg-slate-400 dark:bg-slate-500",
                r.kind === "up" && "bg-sky-500 dark:bg-sky-400",
                r.kind === "down" && "bg-rose-400 dark:bg-rose-400",
              )}
              style={{ left: pct(r.from), width: `max(2px, calc(${pct(r.to)} - ${pct(r.from)}))` }}
            />
          </span>
          <span role="cell" className="text-right tabular-nums text-gray-900 dark:text-gray-100">{r.value}</span>
        </div>
      ))}
    </div>
  );
}

function EuroDetail({ attribution: a, currency }: { attribution: FireAttribution; currency: string }) {
  const { t, i18n } = useTranslation();
  const locale = i18n.language;
  const money = (v: string) => formatWholeMoney(v, currency, locale);
  const signedMoney = (v: number) => `${v > 0 ? "+" : v < 0 ? "−" : ""}${formatWholeMoney(Math.abs(v), currency, locale)}`;
  const wealthChange = Number(a.wealthTo) - Number(a.wealthFrom);
  const rows: [string, string][] = [
    [t("fire.attribution_eur_wealth"), `${money(a.wealthFrom)} → ${money(a.wealthTo)}`],
    ...(a.contributedAmount !== null && a.marketAmount !== null
      ? ([
          [t("fire.attribution_label_contributed"), signedMoney(Number(a.contributedAmount))],
          [t("fire.attribution_label_market"), signedMoney(Number(a.marketAmount))],
        ] as [string, string][])
      : ([[t("fire.attribution_label_wealth"), signedMoney(wealthChange)]] as [string, string][])),
    [t("fire.attribution_eur_need"), `${money(a.targetFrom)} → ${money(a.targetTo)}`],
  ];
  return (
    <dl className="grid grid-cols-[1fr_auto] gap-x-4 gap-y-1 text-xs">
      {rows.map(([label, value]) => (
        <div key={label} className="contents">
          <dt className="text-gray-500 dark:text-gray-400">{label}</dt>
          <dd className="text-right tabular-nums text-gray-900 dark:text-gray-100">{value}</dd>
        </div>
      ))}
    </dl>
  );
}
