import { useCallback, useState } from "react";
import { defaultRange, type RangePreset, type RangeValue } from "@/lib/range";

const PREFIX = "sl.range.";

const PRESETS: readonly RangePreset[] = ["3m", "6m", "ytd", "1y", "2y", "all", "custom"];

/** Narrow an unknown parsed value to a RangeValue, so a stale or hand-edited entry can't break a view. */
export function parseStoredRange(raw: string | null): RangeValue | null {
  if (!raw) return null;
  let parsed: unknown;
  try {
    parsed = JSON.parse(raw);
  } catch {
    return null;
  }
  if (typeof parsed !== "object" || parsed === null) return null;
  const { preset, from, to } = parsed as Record<string, unknown>;
  if (typeof preset !== "string" || !PRESETS.includes(preset as RangePreset)) return null;
  if (typeof from !== "string" || typeof to !== "string") return null;
  return { preset: preset as RangePreset, from, to };
}

/** Range state that survives tab navigation for the rest of the browser session, keyed per view.
 *  Session-scoped on purpose: a range is a reading position, not a saved preference. */
export function useRangeState(
  key: string,
  initialPreset: RangePreset = "1y",
): [RangeValue, (next: RangeValue) => void] {
  const storageKey = `${PREFIX}${key}`;

  const [value, setValue] = useState<RangeValue>(() => {
    try {
      return parseStoredRange(sessionStorage.getItem(storageKey)) ?? defaultRange(initialPreset);
    } catch {
      return defaultRange(initialPreset);
    }
  });

  const update = useCallback(
    (next: RangeValue) => {
      setValue(next);
      try {
        sessionStorage.setItem(storageKey, JSON.stringify(next));
      } catch {
        // Private-mode or quota failures must never break the chart the user is looking at.
      }
    },
    [storageKey],
  );

  return [value, update];
}
