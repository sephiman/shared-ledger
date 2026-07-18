import { useMemo, useState } from "react";
import { useTranslation } from "react-i18next";
import { useActiveHousehold } from "@/auth/AuthContext";
import { useMoneyFlow, useYearsAvailable, type MoneyFlowNode } from "@/api/analytics";
import { useCategories } from "@/api/catalog";
import { Card, CardBody, CardHeader, Input, Label, Select } from "@/components/ui/primitives";
import { Rectangle, ResponsiveContainer, Sankey, Tooltip } from "recharts";
import { formatMoney, formatNumber } from "@/lib/money";
import { isoToday, monthName } from "@/lib/dates";
import { categoryLabelByCode } from "@/lib/categoryLabel";
import { categoryIcon, groupIcon } from "@/lib/categoryGroup";
import {
  buildMoneyFlowSankey,
  resolveMoneyFlowRange,
  type MoneyFlowScope,
  type SankeyNodeDatum,
} from "./moneyflow";
import { DEFICIT_COLOR, HUB_COLOR, OTHERS_COLOR, SAVED_COLOR, groupColor, incomeColor } from "./palette";

type Level = "group" | "category";

interface NodeRenderProps {
  x: number;
  y: number;
  width: number;
  height: number;
  payload: SankeyNodeDatum;
}

interface LinkRenderProps {
  sourceX: number;
  sourceY: number;
  sourceControlX: number;
  targetControlX: number;
  targetX: number;
  targetY: number;
  linkWidth: number;
  payload: { source: SankeyNodeDatum; target: SankeyNodeDatum; value: number };
}

function MoneyFlowNodeShape({ x, y, width, height, payload, currency, locale }: NodeRenderProps & { currency: string; locale: string }) {
  const isCenter = payload.column === "center";
  const isRight = payload.column === "right";
  const textX = isCenter ? x + width / 2 : isRight ? x - 8 : x + width + 8;
  const anchor = isCenter ? "middle" : isRight ? "end" : "start";
  const textY = isCenter ? y - 8 : y + height / 2 + 4;
  return (
    <g>
      <Rectangle x={x} y={y} width={width} height={height} fill={payload.fill} fillOpacity={0.95} radius={2} />
      <text x={textX} y={textY} textAnchor={anchor} fill="currentColor" fontSize={12} className="text-gray-800 dark:text-gray-100">
        <tspan fontWeight={500}>{payload.name}</tspan>
        <tspan dx={6} fontSize={11} fill="currentColor" className="tabular-nums text-gray-500 dark:text-gray-400">
          {formatMoney(payload.amount, currency, locale)}
        </tspan>
      </text>
    </g>
  );
}

function MoneyFlowLinkShape({ sourceX, sourceY, sourceControlX, targetControlX, targetX, targetY, linkWidth, payload }: LinkRenderProps) {
  const stroke = payload.source.side === "hub" ? payload.target.fill : payload.source.fill;
  return (
    <path
      d={`M${sourceX},${sourceY}C${sourceControlX},${sourceY} ${targetControlX},${targetY} ${targetX},${targetY}`}
      fill="none"
      stroke={stroke}
      strokeOpacity={0.35}
      strokeWidth={Math.max(1, linkWidth)}
    />
  );
}

interface SankeyTooltipItem {
  name?: string | number;
  value?: unknown;
  payload?: unknown;
}

function MoneyFlowTooltip({
  active,
  payload,
  currency,
  locale,
  shareLine,
}: {
  active?: boolean;
  payload?: ReadonlyArray<SankeyTooltipItem>;
  currency: string;
  locale: string;
  shareLine: (node: SankeyNodeDatum) => string | null;
}) {
  if (!active || !payload || payload.length === 0) return null;
  const isNodeDatum = (o: unknown): o is SankeyNodeDatum =>
    typeof o === "object" && o != null && "side" in o;
  const isLinkDatum = (o: unknown): o is { source: SankeyNodeDatum; target: SankeyNodeDatum; value: number } =>
    typeof o === "object" && o != null && "source" in o && "target" in o &&
    typeof (o as { source: unknown }).source === "object";
  // Recharts 3 nests the sankey tooltip entry ({name, value, payload}) one level
  // deeper than the classic payload shape; unwrap until the real datum appears.
  let raw: unknown = payload[0]?.payload;
  let guard = 0;
  while (raw != null && !isNodeDatum(raw) && !isLinkDatum(raw) && guard++ < 3) {
    raw = (raw as { payload?: unknown }).payload;
  }
  if (raw == null) return null;

  const MAX_MEMBERS = 10;
  const node = isNodeDatum(raw) ? raw : null;
  const link = !node && isLinkDatum(raw) ? raw : null;
  if (!node && !link) return null;
  const flowEnd = link ? (link.source.side === "hub" ? link.target : link.source) : null;
  const title = link ? `${link.source.name} → ${link.target.name}` : node!.name;
  const amount = link ? link.value : node!.amount;
  const share = link && flowEnd ? shareLine(flowEnd) : node ? shareLine(node) : null;
  const members = node?.members ?? null;

  return (
    <div className="rounded-md border border-border bg-white p-2 text-xs shadow-sm dark:bg-gray-800 dark:border-gray-600">
      <p className="mb-1 font-medium text-gray-900 dark:text-gray-100">{title}</p>
      <p className="font-medium tabular-nums text-gray-900 dark:text-gray-100">{formatMoney(amount, currency, locale)}</p>
      {share && <p className="text-gray-600 dark:text-gray-400">{share}</p>}
      {members && members.length > 0 && (
        <div className="mt-1 space-y-0.5 border-t border-border pt-1">
          {members.slice(0, MAX_MEMBERS).map((m) => (
            <p key={m.name} className="text-gray-900 dark:text-gray-100">
              <span className="text-gray-600 dark:text-gray-400">{m.name}: </span>
              <span className="font-medium tabular-nums">{formatMoney(m.amount, currency, locale)}</span>
              <span className="text-gray-500 dark:text-gray-400"> · {formatNumber(m.share, locale, 1)}%</span>
            </p>
          ))}
          {members.length > MAX_MEMBERS && (
            <p className="text-gray-500 dark:text-gray-400">+ {members.length - MAX_MEMBERS}</p>
          )}
        </div>
      )}
    </div>
  );
}

export function MoneyFlowTab() {
  const { t, i18n } = useTranslation();
  const household = useActiveHousehold();
  const now = new Date();
  const { data: years } = useYearsAvailable(household.householdId);
  const { data: categories } = useCategories(household.householdId);
  const [scope, setScope] = useState<MoneyFlowScope>("month");
  const [year, setYear] = useState<number>(now.getFullYear());
  const [month, setMonth] = useState<number>(now.getMonth() + 1);
  const [customFrom, setCustomFrom] = useState<string>("");
  const [customTo, setCustomTo] = useState<string>(isoToday());
  const [level, setLevel] = useState<Level>("group");

  const range = resolveMoneyFlowRange({ scope, year, month, customFrom, customTo }, now);
  const { data, isLoading } = useMoneyFlow(
    household.householdId,
    range?.from ?? "",
    range?.to ?? "",
    level,
    range != null,
  );

  const yearOptions = useMemo(() => {
    const fromServer = years?.years ?? [];
    if (fromServer.length === 0) return [now.getFullYear()];
    return fromServer;
  }, [years, now]);

  const sankey = useMemo(() => {
    if (!data) return null;
    const catalog = categories ?? [];
    const labelOf = (n: MoneyFlowNode): string => {
      switch (n.side) {
        case "hub":
          return t("analytics.money_flow");
        case "saved":
          return t("analytics.saved");
        case "deficit":
          return t("analytics.money_flow_deficit");
        case "income":
          return categoryLabelByCode(n.id, catalog, t);
        case "expense":
          return data.level === "category"
            ? `${categoryIcon(n.id)} ${categoryLabelByCode(n.id, catalog, t)}`
            : `${groupIcon(n.id)} ${t(`category_group.${n.id}`)}`;
      }
    };
    const colorOf = (n: MoneyFlowNode): string => {
      switch (n.side) {
        case "hub":
          return HUB_COLOR;
        case "saved":
          return SAVED_COLOR;
        case "deficit":
          return DEFICIT_COLOR;
        case "income":
          return incomeColor(n.id);
        case "expense":
          return groupColor(n.groupCode);
      }
    };
    return buildMoneyFlowSankey(data, {
      labelOf,
      colorOf,
      othersLabel: t("analytics.money_flow_others"),
      othersColor: OTHERS_COLOR,
    });
  }, [data, categories, t]);

  const shareLine = (node: SankeyNodeDatum): string | null => {
    if (node.share == null || node.shareOf == null) return null;
    const percent = formatNumber(node.share, i18n.language, 1);
    return node.shareOf === "income"
      ? t("analytics.money_flow_share_of_income", { percent })
      : t("analytics.money_flow_share_of_expenses", { percent });
  };

  const currency = household.currency;
  const locale = i18n.language;
  const hasFlows = sankey != null && sankey.nodes.length > 0;

  return (
    <div className="space-y-4">
      <Card>
        <CardHeader>
          <p className="font-medium">{t("analytics.money_flow")}</p>
          <p className="mt-1 text-sm text-gray-500 dark:text-gray-400">{t("analytics.money_flow_description")}</p>
        </CardHeader>
        <CardBody className="space-y-4">
          <div className="grid grid-cols-1 gap-3 md:grid-cols-4">
            <div>
              <Label>{t("analytics.scope")}</Label>
              <Select value={scope} onChange={(e) => setScope(e.target.value as MoneyFlowScope)}>
                <option value="month">{t("analytics.scope_month")}</option>
                <option value="trailing12">{t("analytics.scope_trailing12")}</option>
                <option value="ytd">{t("analytics.scope_ytd")}</option>
                <option value="year">{t("analytics.scope_year")}</option>
                <option value="custom">{t("analytics.scope_custom")}</option>
              </Select>
            </div>
            {(scope === "month" || scope === "year") && (
              <div>
                <Label>{t("analytics.select_year")}</Label>
                <Select value={year} onChange={(e) => setYear(Number(e.target.value))}>
                  {yearOptions.map((y) => (
                    <option key={y} value={y}>{y}</option>
                  ))}
                </Select>
              </div>
            )}
            {scope === "month" && (
              <div>
                <Label>{t("analytics.select_month")}</Label>
                <Select value={month} onChange={(e) => setMonth(Number(e.target.value))}>
                  {Array.from({ length: 12 }, (_, i) => i + 1).map((m) => (
                    <option key={m} value={m}>{monthName(m, i18n.language, "long")}</option>
                  ))}
                </Select>
              </div>
            )}
            {scope === "custom" && (
              <>
                <div>
                  <Label>{t("tx.filter_from")}</Label>
                  <Input type="date" value={customFrom} onChange={(e) => setCustomFrom(e.target.value)} />
                </div>
                <div>
                  <Label>{t("tx.filter_to")}</Label>
                  <Input type="date" value={customTo} onChange={(e) => setCustomTo(e.target.value)} />
                </div>
              </>
            )}
            <div>
              <Label>{t("analytics.money_flow_level")}</Label>
              <Select value={level} onChange={(e) => setLevel(e.target.value as Level)}>
                <option value="group">{t("analytics.money_flow_by_group")}</option>
                <option value="category">{t("analytics.money_flow_by_category")}</option>
              </Select>
            </div>
          </div>

          {isLoading && <p className="text-gray-500 dark:text-gray-400">{t("common.loading")}</p>}

          {!isLoading && data && !hasFlows && (
            <p className="text-gray-500 dark:text-gray-400">{t("analytics.money_flow_empty")}</p>
          )}

          {!isLoading && data && hasFlows && (
            <>
              <div className="overflow-x-auto">
                <div className="h-[28rem] min-w-[44rem]">
                  <ResponsiveContainer width="100%" height="100%">
                    <Sankey
                      data={sankey}
                      nodeWidth={12}
                      nodePadding={18}
                      margin={{ top: 24, right: 16, bottom: 16, left: 16 }}
                      node={(props: unknown) => (
                        <MoneyFlowNodeShape {...(props as NodeRenderProps)} currency={currency} locale={locale} />
                      )}
                      link={(props: unknown) => <MoneyFlowLinkShape {...(props as LinkRenderProps)} />}
                    >
                      <Tooltip
                        content={(props: { active?: boolean; payload?: ReadonlyArray<SankeyTooltipItem> }) => (
                          <MoneyFlowTooltip
                            active={props.active}
                            payload={props.payload}
                            currency={currency}
                            locale={locale}
                            shareLine={shareLine}
                          />
                        )}
                      />
                    </Sankey>
                  </ResponsiveContainer>
                </div>
              </div>

              <div className="flex flex-wrap gap-x-6 gap-y-1 text-sm">
                <span className="text-gray-600 dark:text-gray-400">
                  {t("analytics.income")}:{" "}
                  <span className="font-medium tabular-nums text-gray-900 dark:text-gray-100">
                    {formatMoney(data.income, currency, locale)}
                  </span>
                </span>
                <span className="text-gray-600 dark:text-gray-400">
                  {t("analytics.expenses")}:{" "}
                  <span className="font-medium tabular-nums text-gray-900 dark:text-gray-100">
                    {formatMoney(data.expenses, currency, locale)}
                  </span>
                </span>
                {Number(data.saved) > 0 && (
                  <span className="text-gray-600 dark:text-gray-400">
                    {t("analytics.saved")}:{" "}
                    <span className="font-medium tabular-nums" style={{ color: SAVED_COLOR }}>
                      {formatMoney(data.saved, currency, locale)}
                    </span>
                  </span>
                )}
                {Number(data.deficit) > 0 && (
                  <span className="text-gray-600 dark:text-gray-400">
                    {t("analytics.money_flow_deficit")}:{" "}
                    <span className="font-medium tabular-nums" style={{ color: DEFICIT_COLOR }}>
                      {formatMoney(data.deficit, currency, locale)}
                    </span>
                  </span>
                )}
              </div>
            </>
          )}
        </CardBody>
      </Card>
    </div>
  );
}
