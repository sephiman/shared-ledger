import type { PendingMovement } from "@/api/banks";

/** Mirrors the backend's `movementDescription`: what a confirm with no note stores. Prefills the inbox
 *  row's input, so leaving it untouched stores exactly what a plain confirm would. */
export function bankDescription(movement: PendingMovement): string {
  const parts = [movement.counterparty, movement.description]
    .map((p) => p?.trim() ?? "")
    .filter((p) => p.length > 0);
  return [...new Set(parts)].join(" – ");
}
