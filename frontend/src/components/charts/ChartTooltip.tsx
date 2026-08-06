import type { ReactNode } from "react";

export interface ChartTooltipPayloadItem {
  name?: string | number;
  value?: unknown;
  color?: string;
  payload?: unknown;
}

export interface ChartTooltipProps {
  active?: boolean;
  label?: ReactNode;
  payload?: ReadonlyArray<ChartTooltipPayloadItem>;
  formatValue: (value: unknown, item: ChartTooltipPayloadItem) => ReactNode;
  showName?: boolean;
}

export function ChartTooltip({ active, label, payload, formatValue, showName = true }: ChartTooltipProps) {
  if (!active || !payload || payload.length === 0) return null;
  const items = payload.filter((p) => p.value !== undefined && p.value !== null);
  if (items.length === 0) return null;

  const hasLabel = label != null && label !== "" && typeof label !== "object";
  const isSingleNamed = !hasLabel && items.length === 1 && !!items[0].name;

  if (isSingleNamed) {
    const it = items[0];
    return (
      <div className="rounded-md border border-border-strong bg-overlay p-2 text-xs shadow-sm">
        <p className="mb-1 font-medium text-gray-900 dark:text-gray-100">
          {it.color && <span style={{ color: it.color }} className="mr-1">●</span>}
          {it.name}
        </p>
        <p className="font-medium tabular-nums text-gray-900 dark:text-gray-100">{formatValue(it.value, it)}</p>
      </div>
    );
  }

  return (
    <div className="rounded-md border border-border-strong bg-overlay p-2 text-xs shadow-sm">
      {hasLabel && (
        <p className="mb-1 font-medium text-gray-900 dark:text-gray-100">{label}</p>
      )}
      <div className="space-y-0.5">
        {items.map((it, idx) => (
          <p key={idx} className="text-gray-900 dark:text-gray-100">
            {it.color && <span style={{ color: it.color }} className="mr-1">●</span>}
            {showName && it.name && (
              <span className="text-gray-600 dark:text-gray-400">{it.name}: </span>
            )}
            <span className="font-medium tabular-nums">{formatValue(it.value, it)}</span>
          </p>
        ))}
      </div>
    </div>
  );
}
