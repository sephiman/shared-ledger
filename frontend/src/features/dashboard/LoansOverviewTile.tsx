import { Link } from "react-router-dom";
import { useTranslation } from "react-i18next";
import { useLoansSummary } from "@/api/loans";
import { Card, CardBody, CardHeader } from "@/components/ui/primitives";
import { formatMoney } from "@/lib/money";

export function LoansOverviewTile({ householdId, currency, locale }: { householdId: string; currency: string; locale: string }) {
  const { t } = useTranslation();
  const { data } = useLoansSummary(householdId);

  // Render nothing when there are no active loans (no empty-state placeholder).
  if (!data || data.activeCount === 0) return null;

  const money = (v: string) => formatMoney(v, currency, locale);

  return (
    <Card>
      <CardHeader className="flex items-center justify-between">
        <p className="text-sm text-gray-500 dark:text-gray-400">{t("loans.home_title")}</p>
        <Link to="/networth?tab=loans" className="text-sm font-medium text-primary hover:underline">
          {t("loans.view_all")}
        </Link>
      </CardHeader>
      <CardBody>
        <p className="text-3xl font-semibold tabular-nums">{money(data.totalOutstandingActive)}</p>
        <p className="mt-1 text-xs text-gray-500 dark:text-gray-400">
          {t("loans.home_active_count", { count: data.activeCount })}
        </p>
        <ul className="mt-3 space-y-1 text-sm">
          {data.top.map((l) => (
            <li key={l.id} className="flex justify-between">
              <span className="truncate">{l.borrowerName}</span>
              <span className="font-mono tabular-nums">{money(l.totalOutstanding)}</span>
            </li>
          ))}
        </ul>
        <p className="mt-3 text-xs text-gray-500 dark:text-gray-400">{t("loans.home_note")}</p>
      </CardBody>
    </Card>
  );
}
