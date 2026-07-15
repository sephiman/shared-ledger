/**
 * The six user-configurable Home panels. Ids are the wire/storage format shared with the
 * backend (`HomePanel` enum, `users.hidden_home_panels`); the server stores the ids a user
 * chose to HIDE, so an empty list (the default) means everything is visible.
 *
 * The month/year summary and the expenses chart are always shown and intentionally absent here.
 */
export type HomePanelId =
  | "savings_rate"
  | "cost_of_living"
  | "current_wealth"
  | "portfolio"
  | "money_lent"
  | "pending_bank";

export interface HomePanelDef {
  id: HomePanelId;
  /** i18n key of the panel's own Home header, so the Settings label matches Home exactly. */
  labelKey: string;
  /** The tile renders nothing until it has data, even with its toggle on. */
  dataDependent: boolean;
}

export const HOME_PANELS: HomePanelDef[] = [
  { id: "savings_rate", labelKey: "dashboard.savings_rate_tile_title", dataDependent: false },
  { id: "cost_of_living", labelKey: "analytics.cost_of_living", dataDependent: false },
  { id: "current_wealth", labelKey: "wealth.home_title", dataDependent: true },
  { id: "portfolio", labelKey: "portfolio.title", dataDependent: true },
  { id: "money_lent", labelKey: "lendings.home_title", dataDependent: true },
  { id: "pending_bank", labelKey: "banks.pending_title", dataDependent: true },
];

const KNOWN = new Set<string>(HOME_PANELS.map((p) => p.id));

/** Canonicalize a server-sent hidden list: drop unknown ids, dedupe, registry order. */
export function normalizeHiddenPanels(hidden: string[] | undefined): HomePanelId[] {
  const set = new Set(hidden ?? []);
  return HOME_PANELS.filter((p) => set.has(p.id)).map((p) => p.id);
}

/** The user preference can only hide a panel; data-driven visibility stays with each tile. */
export function isPanelVisible(hidden: string[] | undefined, id: HomePanelId): boolean {
  return !(hidden ?? []).includes(id);
}

/** Next hidden list to persist after switching one panel on/off. */
export function setPanelVisible(hidden: string[] | undefined, id: HomePanelId, visible: boolean): HomePanelId[] {
  const next = new Set<string>(hidden ?? []);
  if (visible) next.delete(id);
  else next.add(id);
  return normalizeHiddenPanels([...next]);
}
