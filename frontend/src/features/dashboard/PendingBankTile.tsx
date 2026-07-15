import { Link } from "react-router-dom";
import { useTranslation } from "react-i18next";
import { useBankConfig, usePendingCount } from "@/api/banks";
import { Card, CardBody, CardHeader } from "@/components/ui/primitives";

/**
 * Home card surfacing pending bank movements. Renders nothing when there are none (the same
 * return-null idiom as PortfolioOverviewTile), so it only appears once a bank is linked and has
 * unreviewed movements.
 */
export function PendingBankTile({ householdId }: { householdId: string }) {
  const { t } = useTranslation();
  const { data: bankConfig } = useBankConfig(householdId);
  const hasBanks = (bankConfig?.connectionCount ?? 0) > 0;
  const { data: pending } = usePendingCount(householdId, hasBanks);

  if (!pending || pending.count === 0) return null;

  return (
    <Card>
      <CardHeader className="flex items-center justify-between">
        <p className="text-sm text-gray-500 dark:text-gray-400">{t("banks.pending_title")}</p>
        <Link to="/transactions?tab=pending" className="text-sm font-medium text-primary hover:underline">
          {t("banks.review")}
        </Link>
      </CardHeader>
      <CardBody>
        <p className="text-3xl font-semibold tabular-nums">{pending.count}</p>
        <p className="mt-1 text-xs text-gray-500 dark:text-gray-400">{t("banks.pending_home_hint")}</p>
        <ul className="mt-3 space-y-1 text-sm">
          {pending.byConnection.slice(0, 3).map((c) => (
            <li key={c.connectionId} className="flex justify-between gap-3">
              <span className="truncate text-gray-600 dark:text-gray-300">{c.label}</span>
              <span className="font-mono tabular-nums">{c.count}</span>
            </li>
          ))}
        </ul>
      </CardBody>
    </Card>
  );
}
