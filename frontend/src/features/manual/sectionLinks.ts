import type { ManualRouteLink } from "./manualTypes";

/** Deep links from manual sections to the matching pages, tabs, and sub-views in the app. */
export const SECTION_LINKS: Record<string, ManualRouteLink[]> = {
  "households-model": [{ to: "/settings", labelKey: "nav.settings" }],
  "account-language-theme": [{ to: "/settings", labelKey: "nav.settings" }],
  "home-overview": [{ to: "/dashboard", labelKey: "nav.dashboard" }],
  "tx-recording": [{ to: "/transactions", labelKey: "nav.transactions" }],
  categories: [{ to: "/settings", labelKey: "nav.settings" }],
  refunds: [{ to: "/transactions?isRefund=true", labelKey: "manual.links.refunds" }],
  recurring: [{ to: "/recurring", labelKey: "nav.recurring" }],
  budgets: [{ to: "/budgets", labelKey: "nav.budgets" }],
  portfolio: [
    { to: "/networth?tab=holdings", labelKey: "portfolio.title" },
    { to: "/networth?tab=allocation", labelKey: "portfolio.allocation" },
    { to: "/networth?tab=evolution", labelKey: "portfolio.evolution" },
  ],
  snapshots: [{ to: "/networth?tab=snapshots", labelKey: "networth.snapshots" }],
  "flows-cash": [
    { to: "/networth?tab=flows&flow=movements", labelKey: "networth.movements" },
    { to: "/networth?tab=flows&flow=cash", labelKey: "networth.cash" },
  ],
  "assets-liabilities-lent": [
    { to: "/networth?tab=assets", labelKey: "networth.assets" },
    { to: "/networth?tab=liabilities", labelKey: "networth.liabilities" },
    { to: "/networth?tab=lendings", labelKey: "networth.lent" },
  ],
  "analytics-tabs": [
    { to: "/analytics/daily", labelKey: "analytics.daily" },
    { to: "/analytics/explorer", labelKey: "analytics.explorer" },
    { to: "/analytics/composition", labelKey: "analytics.composition" },
    { to: "/analytics/changes", labelKey: "analytics.changes" },
    { to: "/analytics/trends", labelKey: "analytics.trends" },
    { to: "/analytics/money-flow", labelKey: "analytics.money_flow" },
  ],
  "ask-ai": [
    { to: "/analytics/explorer", labelKey: "analytics.explorer" },
    { to: "/transactions", labelKey: "nav.transactions" },
  ],
  "fire-projection": [{ to: "/fire?tab=projection", labelKey: "fire.tab_projection" }],
  "fire-whatif-settings": [{ to: "/fire?tab=settings", labelKey: "manual.links.fire_settings" }],
  "fire-ai-review": [{ to: "/fire?tab=ai_analysis", labelKey: "fire.tab_ai_analysis" }],
  "bank-linking": [{ to: "/settings", labelKey: "nav.settings" }],
  "pending-inbox": [{ to: "/transactions?tab=pending", labelKey: "manual.links.pending" }],
  rules: [{ to: "/settings/rules", labelKey: "manual.links.rules" }],
  telegram: [{ to: "/settings", labelKey: "nav.settings" }],
  "ai-privacy": [{ to: "/settings", labelKey: "nav.settings" }],
  "csv-wipe": [{ to: "/settings/import", labelKey: "import.title" }],
};
