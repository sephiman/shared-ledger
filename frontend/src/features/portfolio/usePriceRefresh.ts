import { useEffect, useState } from "react";
import { useInvalidatePortfolio, usePriceRefreshStatus, useRefreshPrices } from "@/api/portfolio";

const POLL_MS = 3000;

/** "Refresh now" for the household's prices: triggers the server run, polls until it finishes, then reloads
 *  every portfolio view. Shared by the Wealth header and the stale-price notice so both see one run. */
export function usePriceRefresh(householdId: string) {
  const [awaiting, setAwaiting] = useState(false);
  const trigger = useRefreshPrices(householdId);
  const { data: status, refetch } = usePriceRefreshStatus(householdId, awaiting, POLL_MS);
  const invalidate = useInvalidatePortfolio(householdId);

  useEffect(() => {
    if (!awaiting || !status || status.running) return;
    setAwaiting(false);
    invalidate();
  }, [awaiting, status, invalidate]);

  // Re-read the status the moment the cooldown lapses, so the button reopens without a reload.
  const nextAllowedAt = status?.nextAllowedAt ?? null;
  useEffect(() => {
    if (!nextAllowedAt) return;
    const timer = setTimeout(() => void refetch(), Math.max(0, Date.parse(nextAllowedAt) - Date.now()));
    return () => clearTimeout(timer);
  }, [nextAllowedAt, refetch]);

  const start = () =>
    trigger.mutate(undefined, {
      onSuccess: (result) => {
        if (result.outcome !== "cooldown") setAwaiting(true);
      },
    });

  return {
    start,
    refreshing: awaiting || trigger.isPending || status?.running === true,
    nextAllowedAt: awaiting ? null : nextAllowedAt,
    lastFinishedAt: status?.lastFinishedAt ?? null,
    equitiesStaleOnly: status?.equitiesStaleOnly ?? false,
    failed: trigger.isError,
  };
}
