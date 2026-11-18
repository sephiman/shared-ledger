import { Link } from "react-router-dom";
import { useTranslation } from "react-i18next";
import Decimal from "decimal.js";
import { useBankCash, type BankCashAccount } from "@/api/networth";
import { Card, CardBody, CardHeader } from "@/components/ui/primitives";
import { formatMoney, toDecimal } from "@/lib/money";

interface BankCashLine {
  bankName: string;
  balance: Decimal;
}

/** Groups counted, active linked accounts by bank name, sorted largest balance first. */
export function groupCashByBank(accounts: BankCashAccount[]): BankCashLine[] {
  const byBank = new Map<string, Decimal>();
  for (const a of accounts) {
    if (!a.countsAsCash || a.paused || a.balanceBase == null) continue;
    const prev = byBank.get(a.bankName) ?? new Decimal(0);
    byBank.set(a.bankName, prev.plus(toDecimal(a.balanceBase)));
  }
  return Array.from(byBank.entries())
    .map(([bankName, balance]) => ({ bankName, balance }))
    .sort((a, b) => b.balance.comparedTo(a.balance));
}

/** Home card for cash per linked bank plus other cash; renders nothing until there is data. */
export function CashOverviewTile({
  householdId,
  currency,
  locale,
}: {
  householdId: string;
  currency: string;
  locale: string;
}) {
  const { t } = useTranslation();
  const { data } = useBankCash(householdId);

  if (!data) return null;

  const banks = groupCashByBank(data.accounts);
  const otherCash = toDecimal(data.otherCash);
  if (banks.length === 0 && otherCash.isZero()) return null;

  const money = (v: Decimal | string) => formatMoney(v, currency, locale);

  return (
    <Card>
      <CardHeader className="flex items-center justify-between">
        <p className="text-sm text-gray-500 dark:text-gray-400">{t("networth.cash")}</p>
        <Link to="/networth?tab=flows&flow=cash" className="text-sm font-medium text-primary hover:underline">
          {t("wealth.home_view_all")}
        </Link>
      </CardHeader>
      <CardBody>
        <p className="text-3xl font-semibold tabular-nums">{money(data.total)}</p>
        <p className="mt-1 text-xs text-gray-500 dark:text-gray-400">{t("networth.cash_home_subtitle")}</p>
        <ul className="mt-3 space-y-1 text-sm">
          {banks.map((b) => (
            <li key={b.bankName} className="flex justify-between gap-3">
              <span className="truncate text-gray-600 dark:text-gray-300">{b.bankName}</span>
              <span className="font-mono tabular-nums">{money(b.balance)}</span>
            </li>
          ))}
          <li className="flex justify-between gap-3">
            <span className="truncate text-gray-600 dark:text-gray-300">{t("networth.cash_other")}</span>
            <span className="font-mono tabular-nums">{money(otherCash)}</span>
          </li>
        </ul>
      </CardBody>
    </Card>
  );
}
