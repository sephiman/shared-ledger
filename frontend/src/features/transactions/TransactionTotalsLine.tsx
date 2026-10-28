import Decimal from "decimal.js";
import { useTranslation } from "react-i18next";
import { useTransactionTotals, type TransactionTotals } from "@/api/transactions";
import { formatSignedMoney, signedTxAmount } from "./txDisplay";
import type { TransactionListFilters } from "./txFilters";

/** Which figures the line shows, signed by the rows' own rule ([signedTxAmount]): one when the filters pick a side
 *  — with "Refunds only" that is expenses made of refunds, so it reads positive like refund rows — all three when
 *  they do not. Net is already money in minus money out, which is that same sign. */
function figures(totals: TransactionTotals, filters: TransactionListFilters): { label: string | null; amount: Decimal }[] {
  const income = signedTxAmount({ direction: "income", amount: totals.income });
  const expenses = signedTxAmount({ direction: "expense", amount: totals.expenses });
  if (filters.isRefund || filters.direction === "expense") return [{ label: null, amount: expenses }];
  if (filters.direction === "income") return [{ label: null, amount: income }];
  return [
    { label: "tx.totals_income", amount: income },
    { label: "tx.totals_expenses", amount: expenses },
    { label: "tx.totals_net", amount: new Decimal(totals.net) },
  ];
}

/** "120 transactions · €1,534.20" over the whole filtered set, not the page on screen; nothing for an empty set. */
export function TransactionTotalsLine({
  householdId,
  filters,
  currency,
}: {
  householdId: string;
  filters: TransactionListFilters;
  currency: string;
}) {
  const { t, i18n } = useTranslation();
  const { data } = useTransactionTotals(householdId, filters);
  if (!data || data.count === 0) return null;
  const parts = figures(data, filters).map(({ label, amount }) => {
    const money = formatSignedMoney(amount, currency, i18n.language);
    return label ? `${t(label)} ${money}` : money;
  });
  return (
    <p role="status" aria-label={t("tx.totals_label")} className="mb-3 text-sm text-gray-600 dark:text-gray-300">
      {[t("tx.totals_count", { count: data.count }), ...parts].join(" · ")}
    </p>
  );
}
