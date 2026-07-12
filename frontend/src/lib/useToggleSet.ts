import { useState } from "react";

/** Immutable Set-backed toggle state — shared by expandable table rows. */
export function useToggleSet<T>(): { has: (value: T) => boolean; toggle: (value: T) => void } {
  const [set, setSet] = useState<Set<T>>(new Set());
  function toggle(value: T) {
    setSet((prev) => {
      const next = new Set(prev);
      if (next.has(value)) next.delete(value);
      else next.add(value);
      return next;
    });
  }
  return { has: (value: T) => set.has(value), toggle };
}
