// Stable chart colors shared across analytics tabs. Colors follow the entity
// (group / category code), never its rank in a particular period, so the same
// group keeps its color across periods and across tabs (Allocation, Money flow).
export const GROUP_COLORS: Record<string, string> = {
  home: "#0ea5e9",
  transport: "#f97316",
  groceries: "#22c55e",
  shopping: "#ec4899",
  outings: "#a855f7",
  financial: "#14b8a6",
  health: "#ef4444",
  personal: "#eab308",
  ungrouped: "#64748b",
};

export function groupColor(groupCode: string | null | undefined): string {
  if (!groupCode) return GROUP_COLORS.ungrouped;
  return GROUP_COLORS[groupCode] ?? GROUP_COLORS.ungrouped;
}

export const SAVED_COLOR = "#16a34a";
export const DEFICIT_COLOR = "#b91c1c";
export const HUB_COLOR = "#64748b";
export const OTHERS_COLOR = "#94a3b8";

// Income categories are a flat list; the seeded codes get fixed hues. Custom
// income categories ("income.<slug>") pick from the same palette by a
// deterministic hash of their code, so their color is stable too.
export const INCOME_COLORS: Record<string, string> = {
  "income.salary": "#059669",
  "income.pension": "#7c3aed",
  "income.reimbursements": "#0284c7",
  "income.benefits": "#ca8a04",
  "income.financial": "#0891b2",
  "income.other": "#db2777",
  "income.transfers": "#ea580c",
};

const INCOME_FALLBACK = Object.values(INCOME_COLORS);

export function incomeColor(code: string): string {
  const fixed = INCOME_COLORS[code];
  if (fixed) return fixed;
  let hash = 0;
  for (let i = 0; i < code.length; i++) hash = (hash * 31 + code.charCodeAt(i)) | 0;
  return INCOME_FALLBACK[Math.abs(hash) % INCOME_FALLBACK.length];
}
