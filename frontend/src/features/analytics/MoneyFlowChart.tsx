import { useLayoutEffect, useMemo, useRef, useState, type KeyboardEvent, type ReactNode } from "react";
import { useTranslation } from "react-i18next";
import type { MoneyFlowNode, MoneyFlowResponse } from "@/api/analytics";
import { useCategories } from "@/api/catalog";
import { Rectangle, ResponsiveContainer, Sankey, Tooltip } from "recharts";
import { formatMoney, formatNumber } from "@/lib/money";
import { categoryLabelByCode } from "@/lib/categoryLabel";
import { categoryIcon, groupIcon } from "@/lib/categoryGroup";
import { buildMoneyFlowSankey, nodeKey, type MoneyFlowSankeyData, type SankeyNodeDatum } from "./moneyflow";
import { DEFICIT_COLOR, HUB_COLOR, OTHERS_COLOR, SAVED_COLOR, groupColor, incomeColor } from "./palette";

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

type FlowData = Pick<MoneyFlowResponse, "level" | "income" | "expenses" | "nodes">;

const NO_KEYS: ReadonlySet<string> = new Set();

/** A node drawn this thin or thinner still shows, and a tap anywhere this tall around it still lands. */
const MIN_NODE_HEIGHT = 3;
const MIN_HIT_HEIGHT = 24;

/** Clicking a projected income/expense node; the real diagram passes none and stays read-only. */
interface NodeToggle {
  disabledKeys: ReadonlySet<string>;
  onToggle: (node: SankeyNodeDatum) => void;
  labelOf: (node: SankeyNodeDatum) => string;
}

const isToggleable = (node: SankeyNodeDatum) => node.side === "income" || node.side === "expense";

interface Box {
  x: number;
  y: number;
  width: number;
  height: number;
}

/** The bar's column, at least MIN_HIT_HEIGHT tall for a finger, plus the label's exact box: SVG text only takes
 *  clicks on its glyphs, so word gaps would fall through. The label is never padded taller, or it would steal clicks
 *  from a neighbour's ribbon passing just under it. */
function hitBoxes(bar: Box, label: Box | null): Box[] {
  const height = Math.max(bar.height, MIN_HIT_HEIGHT);
  const column = { x: bar.x, y: bar.y + bar.height / 2 - height / 2, width: bar.width, height };
  return label ? [column, label] : [column];
}

/** Labels and colors the backend's semantic nodes, so the real and projected panels name things alike. */
export function useMoneyFlowSankey(
  householdId: string,
  data: FlowData | undefined,
  projected = false,
  neverCollapse: ReadonlySet<string> = NO_KEYS,
): MoneyFlowSankeyData | null {
  const { t } = useTranslation();
  const { data: categories } = useCategories(householdId);
  return useMemo(() => {
    if (!data) return null;
    const catalog = categories ?? [];
    const labelOf = (n: MoneyFlowNode): string => {
      switch (n.side) {
        case "hub":
          return t("analytics.money_flow");
        case "saved":
          return t(projected ? "analytics.saved_projected" : "analytics.saved");
        case "deficit":
          return t(projected ? "analytics.deficit_projected" : "analytics.money_flow_deficit");
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
      neverCollapse,
    });
  }, [data, categories, t, projected, neverCollapse]);
}

function MoneyFlowNodeShape({
  x, y, width, height, payload, currency, locale, projected, toggle,
}: NodeRenderProps & { currency: string; locale: string; projected: boolean; toggle?: NodeToggle }) {
  const isCenter = payload.column === "center";
  const isRight = payload.column === "right";
  const textX = isCenter ? x + width / 2 : isRight ? x - 8 : x + width + 8;
  const anchor = isCenter ? "middle" : isRight ? "end" : "start";
  const textY = isCenter ? y - 8 : y + height / 2 + 4;
  const clickable = toggle != null && isToggleable(payload);
  const actualsOnly = clickable && toggle.disabledKeys.has(nodeKey(payload));
  const estimate = projected && !actualsOnly;
  // Nothing actual yet: an outline sliver, so the node can always be clicked back on.
  const empty = clickable && payload.amount <= 0;
  const drawnHeight = clickable ? Math.max(height, MIN_NODE_HEIGHT) : height;
  const labelRef = useRef<SVGTextElement>(null);
  const [labelBox, setLabelBox] = useState<Box | null>(null);
  useLayoutEffect(() => {
    const label = labelRef.current;
    // jsdom has no SVG layout; the bar-only hit area still works there.
    if (!clickable || !label || typeof label.getBBox !== "function") return;
    const { x: bx, y: by, width: bw, height: bh } = label.getBBox();
    setLabelBox({ x: bx, y: by, width: bw, height: bh });
  }, [clickable, payload.name, payload.amount, x, y, currency, locale]);
  const hits = hitBoxes({ x: x - 4, y, width: width + 8, height: drawnHeight }, labelBox);
  const onKeyDown = (e: KeyboardEvent<SVGGElement>) => {
    if (!clickable || (e.key !== "Enter" && e.key !== " ")) return;
    e.preventDefault();
    toggle.onToggle(payload);
  };
  return (
    <g
      {...(clickable && {
        role: "button",
        tabIndex: 0,
        "aria-pressed": actualsOnly,
        "aria-label": toggle.labelOf(payload),
        onClick: () => toggle.onToggle(payload),
        onKeyDown,
        className: "group cursor-pointer focus:outline-none focus-visible:outline-2 focus-visible:outline-sky-500",
      })}
    >
      {clickable &&
        hits.map((hit, i) => <rect key={i} x={hit.x} y={hit.y} width={hit.width} height={hit.height} fill="transparent" />)}
      <Rectangle
        x={x}
        y={y}
        width={width}
        height={drawnHeight}
        fill={payload.fill}
        fillOpacity={empty ? 0.15 : estimate ? 0.45 : 0.95}
        stroke={estimate || empty ? payload.fill : undefined}
        strokeDasharray={estimate ? "3 2" : undefined}
        radius={2}
        // Same lift as the node's ribbon: bar, label and ribbon are one control and should read as one.
        className={clickable ? (empty ? "group-hover:[fill-opacity:0.3]" : estimate ? "group-hover:[fill-opacity:0.6]" : "group-hover:[fill-opacity:1]") : undefined}
      />
      <text ref={labelRef} x={textX} y={textY} textAnchor={anchor} fill="currentColor" fontSize={12} className="text-gray-800 dark:text-gray-100">
        <tspan fontWeight={500}>{payload.name}</tspan>
        <tspan dx={6} fontSize={11} fill="currentColor" className="tabular-nums text-gray-500 dark:text-gray-400">
          {formatMoney(payload.amount, currency, locale)}
        </tspan>
      </text>
    </g>
  );
}

/** A ribbon is a pointer shortcut to its non-hub node (no role or tab stop: the keyboard control is the node). */
function MoneyFlowLinkShape({
  sourceX, sourceY, sourceControlX, targetControlX, targetX, targetY, linkWidth, payload, projected, toggle,
}: LinkRenderProps & { projected: boolean; toggle?: NodeToggle }) {
  const flowEnd = payload.source.side === "hub" ? payload.target : payload.source;
  const clickable = toggle != null && isToggleable(flowEnd);
  const estimate = projected && !(toggle?.disabledKeys.has(nodeKey(flowEnd)) ?? false);
  return (
    <path
      d={`M${sourceX},${sourceY}C${sourceControlX},${sourceY} ${targetControlX},${targetY} ${targetX},${targetY}`}
      fill="none"
      stroke={flowEnd.fill}
      strokeOpacity={estimate ? 0.18 : 0.35}
      strokeWidth={Math.max(1, linkWidth)}
      {...(clickable && {
        "data-flow-end": nodeKey(flowEnd),
        onClick: () => toggle.onToggle(flowEnd),
        className: estimate ? "cursor-pointer hover:[stroke-opacity:0.28]" : "cursor-pointer hover:[stroke-opacity:0.5]",
      })}
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
  detailLine,
}: {
  active?: boolean;
  payload?: ReadonlyArray<SankeyTooltipItem>;
  currency: string;
  locale: string;
  shareLine: (node: SankeyNodeDatum) => string | null;
  detailLine?: (node: SankeyNodeDatum) => string | null;
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
  const flowEnd = link ? (link.source.side === "hub" ? link.target : link.source) : node!;
  const title = link ? `${link.source.name} → ${link.target.name}` : node!.name;
  const amount = link ? link.value : node!.amount;
  const share = shareLine(flowEnd);
  const detail = detailLine?.(flowEnd) ?? null;
  const members = node?.members ?? null;

  return (
    <div className="max-w-xs rounded-md border border-border-strong bg-overlay p-2 text-xs shadow-sm">
      <p className="mb-1 font-medium text-gray-900 dark:text-gray-100">{title}</p>
      <p className="font-medium tabular-nums text-gray-900 dark:text-gray-100">{formatMoney(amount, currency, locale)}</p>
      {share && <p className="text-gray-600 dark:text-gray-400">{share}</p>}
      {detail && <p className="mt-1 border-t border-border pt-1 text-gray-700 dark:text-gray-300">{detail}</p>}
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

export function MoneyFlowSankeyChart({
  sankey,
  currency,
  projected = false,
  detailLine,
  toggle,
}: {
  sankey: MoneyFlowSankeyData;
  currency: string;
  /** Muted, dashed treatment so an estimate is never mistaken for the real diagram. */
  projected?: boolean;
  detailLine?: (node: SankeyNodeDatum) => string | null;
  toggle?: NodeToggle;
}) {
  const { t, i18n } = useTranslation();
  const locale = i18n.language;
  const shareLine = (node: SankeyNodeDatum): string | null => {
    if (node.share == null || node.shareOf == null) return null;
    const percent = formatNumber(node.share, locale, 1);
    return node.shareOf === "income"
      ? t("analytics.money_flow_share_of_income", { percent })
      : t("analytics.money_flow_share_of_expenses", { percent });
  };

  return (
    <div className="overflow-x-auto">
      <div className="h-[28rem] min-w-[44rem]">
        <ResponsiveContainer width="100%" height="100%">
          <Sankey
            data={sankey}
            nodeWidth={12}
            nodePadding={18}
            margin={{ top: 24, right: 16, bottom: 16, left: 16 }}
            node={(props: unknown) => (
              <MoneyFlowNodeShape
                {...(props as NodeRenderProps)}
                currency={currency}
                locale={locale}
                projected={projected}
                toggle={toggle}
              />
            )}
            link={(props: unknown) => (
              <MoneyFlowLinkShape {...(props as LinkRenderProps)} projected={projected} toggle={toggle} />
            )}
          >
            <Tooltip
              content={(props: { active?: boolean; payload?: ReadonlyArray<SankeyTooltipItem> }) => (
                <MoneyFlowTooltip
                  active={props.active}
                  payload={props.payload}
                  currency={currency}
                  locale={locale}
                  shareLine={shareLine}
                  detailLine={detailLine}
                />
              )}
            />
          </Sankey>
        </ResponsiveContainer>
      </div>
    </div>
  );
}

interface FlowTotals {
  income: string;
  expenses: string;
  saved: string;
  deficit: string;
}

export function MoneyFlowTotals({
  totals,
  currency,
  labelOf,
  children,
}: {
  totals: FlowTotals;
  currency: string;
  labelOf: (field: keyof FlowTotals) => string;
  children?: ReactNode;
}) {
  const { i18n } = useTranslation();
  const locale = i18n.language;
  return (
    <div className="flex flex-wrap gap-x-6 gap-y-1 text-sm">
      <span className="text-gray-600 dark:text-gray-400">
        {labelOf("income")}:{" "}
        <span className="font-medium tabular-nums text-gray-900 dark:text-gray-100">
          {formatMoney(totals.income, currency, locale)}
        </span>
      </span>
      <span className="text-gray-600 dark:text-gray-400">
        {labelOf("expenses")}:{" "}
        <span className="font-medium tabular-nums text-gray-900 dark:text-gray-100">
          {formatMoney(totals.expenses, currency, locale)}
        </span>
      </span>
      {Number(totals.saved) > 0 && (
        <span className="text-gray-600 dark:text-gray-400">
          {labelOf("saved")}:{" "}
          <span className="font-medium tabular-nums" style={{ color: SAVED_COLOR }}>
            {formatMoney(totals.saved, currency, locale)}
          </span>
        </span>
      )}
      {Number(totals.deficit) > 0 && (
        <span className="text-gray-600 dark:text-gray-400">
          {labelOf("deficit")}:{" "}
          <span className="font-medium tabular-nums" style={{ color: DEFICIT_COLOR }}>
            {formatMoney(totals.deficit, currency, locale)}
          </span>
        </span>
      )}
      {children}
    </div>
  );
}
