import { defaultRange, type RangePreset, type RangeValue } from "@/lib/range";

export interface ScopeValue {
  type: "group" | "category";
  code: string;
}

/** What the Explorer URL can carry. An absent field means "not stated": the view falls back to its own
 *  default (server-chosen scope, session-remembered range, overlay on, empty stack). */
export interface ExplorerUrlState {
  scope?: ScopeValue;
  range?: RangeValue;
  prior?: boolean;
  stacked?: ScopeValue[];
  stackedRange?: RangeValue;
}

const PRESETS: readonly RangePreset[] = ["3m", "6m", "ytd", "1y", "2y", "all", "custom"];

export function encodeScope(s: ScopeValue): string {
  return `${s.type}:${s.code}`;
}

export function decodeScope(v: string): ScopeValue | null {
  const i = v.indexOf(":");
  if (i < 0) return null;
  const type = v.slice(0, i);
  const code = v.slice(i + 1);
  if ((type !== "group" && type !== "category") || !code) return null;
  return { type, code };
}

function readRange(params: URLSearchParams, presetKey: string, fromKey: string, toKey: string): RangeValue | undefined {
  const preset = params.get(presetKey);
  if (!preset || !PRESETS.includes(preset as RangePreset)) return undefined;
  if (preset !== "custom") return defaultRange(preset as RangePreset);
  return { preset: "custom", from: params.get(fromKey) ?? "", to: params.get(toKey) ?? "" };
}

function writeRange(params: URLSearchParams, range: RangeValue, presetKey: string, fromKey: string, toKey: string) {
  params.set(presetKey, range.preset);
  if (range.preset !== "custom") return;
  if (range.from) params.set(fromKey, range.from);
  if (range.to) params.set(toKey, range.to);
}

export function explorerStateFromSearch(params: URLSearchParams): ExplorerUrlState {
  const scope = params.get("scope");
  const prior = params.get("prior");
  const stacked = params.get("stacked");
  return {
    scope: (scope && decodeScope(scope)) || undefined,
    range: readRange(params, "range", "from", "to"),
    prior: prior === "1" ? true : prior === "0" ? false : undefined,
    stacked: stacked
      ?.split(",")
      .map(decodeScope)
      .filter((s): s is ScopeValue => s !== null),
    stackedRange: readRange(params, "stackedRange", "stackedFrom", "stackedTo"),
  };
}

/** The one serialisation of Explorer state, in a fixed key order, so a hand-set view and a question that
 *  asks for the same thing produce the same URL. */
export function explorerSearch(state: ExplorerUrlState): URLSearchParams {
  const params = new URLSearchParams();
  if (state.scope) params.set("scope", encodeScope(state.scope));
  if (state.range) writeRange(params, state.range, "range", "from", "to");
  if (state.prior !== undefined) params.set("prior", state.prior ? "1" : "0");
  if (state.stacked && state.stacked.length > 0) params.set("stacked", state.stacked.map(encodeScope).join(","));
  if (state.stackedRange) writeRange(params, state.stackedRange, "stackedRange", "stackedFrom", "stackedTo");
  return params;
}
