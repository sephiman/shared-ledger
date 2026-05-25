const GROUP_ICONS: Record<string, string> = {
  income: "\u{1F4B5}",
  home: "\u{1F3E0}",
  transport: "\u{1F697}",
  groceries: "\u{1F6D2}",
  shopping: "\u{1F6CD}\u{FE0F}",
  outings: "\u{1F389}",
  financial: "\u{1F4B3}",
  health: "\u{1FA7A}",
  personal: "\u{1F464}",
  ungrouped: "\u{1F3F7}\u{FE0F}",
};

export function groupFromCode(code: string): string {
  const i = code.indexOf(".");
  return i === -1 ? "ungrouped" : code.slice(0, i);
}

export function groupIcon(group: string | null | undefined): string {
  if (!group) return GROUP_ICONS.ungrouped;
  return GROUP_ICONS[group] ?? GROUP_ICONS.ungrouped;
}

export function categoryIcon(code: string): string {
  return groupIcon(groupFromCode(code));
}
