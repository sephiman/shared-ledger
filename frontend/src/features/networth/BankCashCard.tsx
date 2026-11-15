import { useState } from "react";
import { useTranslation } from "react-i18next";
import { useActiveHousehold } from "@/auth/AuthContext";
import {
  useBankCash,
  useUpdateBankCashAccount,
  useUpdateOtherCash,
  type BankCashAccount,
} from "@/api/networth";
import { Badge, Button, Card, CardBody, CardHeader, Checkbox, Input, Label } from "@/components/ui/primitives";
import { formatMoney } from "@/lib/money";
import { formatDate } from "@/lib/dates";

/** Linked-bank cash: each account's daily balance with its "counts as cash" switch, the free other cash,
 *  and their sum — the amount the cash estimate re-anchors on once a day. Hidden without linked accounts. */
export function BankCashCard() {
  const { t, i18n } = useTranslation();
  const household = useActiveHousehold();
  const hid = household.householdId;
  const money = (v: string, currency = household.currency) => formatMoney(v, currency, i18n.language);

  const { data: bank } = useBankCash(hid);
  const updateAccount = useUpdateBankCashAccount(hid);
  const updateOther = useUpdateOtherCash(hid);
  const [otherDraft, setOtherDraft] = useState<string | null>(null);

  if (!bank || bank.accounts.length === 0) return null;

  const otherValue = otherDraft ?? bank.otherCash;
  const otherValid = otherValue.trim() !== "" && !Number.isNaN(Number(otherValue));
  const otherChanged = otherDraft != null && otherValid && Number(otherDraft) !== Number(bank.otherCash);

  async function saveOther() {
    if (!otherChanged || otherDraft == null) return;
    await updateOther.mutateAsync(otherDraft);
    setOtherDraft(null);
  }

  return (
    <Card className="md:col-span-2">
      <CardHeader><p className="font-medium">{t("networth.cash_bank_title")}</p></CardHeader>
      <CardBody className="space-y-4">
        <p className="text-xs text-gray-500 dark:text-gray-400">{t("networth.cash_bank_hint")}</p>

        <ul className="space-y-1 text-sm">
          {bank.accounts.map((a) => (
            <AccountRow
              key={a.id}
              account={a}
              money={money}
              disabled={updateAccount.isPending}
              onToggle={(countsAsCash) => updateAccount.mutate({ accountId: a.id, countsAsCash })}
            />
          ))}
        </ul>

        <div className="flex flex-wrap items-end gap-2 border-t border-border pt-3">
          <div>
            <Label htmlFor="other-cash">{t("networth.cash_other")}</Label>
            <Input
              id="other-cash"
              type="number"
              step="0.01"
              value={otherValue}
              invalid={!otherValid}
              onChange={(e) => setOtherDraft(e.target.value)}
            />
          </div>
          <Button className="px-2 py-1 text-xs" onClick={saveOther} disabled={!otherChanged || updateOther.isPending}>
            {t("common.save")}
          </Button>
          <p className="w-full text-xs text-gray-500 dark:text-gray-400">{t("networth.cash_other_hint")}</p>
        </div>

        <p className="border-t border-border pt-3 text-sm text-gray-600 dark:text-gray-300">
          {t("networth.cash_bank_total", {
            banks: money(bank.bankTotal),
            other: money(bank.otherCash),
            total: money(bank.total),
          })}
        </p>
      </CardBody>
    </Card>
  );
}

function AccountRow({
  account,
  money,
  disabled,
  onToggle,
}: {
  account: BankCashAccount;
  money: (v: string, currency?: string) => string;
  disabled: boolean;
  onToggle: (countsAsCash: boolean) => void;
}) {
  const { t, i18n } = useTranslation();
  const household = useActiveHousehold();
  const name = [account.bankName, account.accountName, account.ibanMasked].filter(Boolean).join(" · ");
  const foreignCurrency = account.currency != null && account.currency !== household.currency ? account.currency : null;

  return (
    <li className="flex flex-wrap items-center justify-between gap-x-3 gap-y-1 border-t border-border py-1 first:border-t-0">
      <label className="flex min-w-0 items-center gap-2">
        <Checkbox
          checked={account.countsAsCash}
          disabled={disabled}
          onChange={(e) => onToggle(e.target.checked)}
          aria-label={t("networth.cash_bank_counts", { name })}
        />
        <span className="truncate">{name}</span>
        {account.paused && <Badge tone="amber">{t("networth.cash_bank_paused")}</Badge>}
      </label>
      {account.balance != null && account.balanceBase != null ? (
        <span className="text-right">
          <span className="font-mono tabular-nums">{money(account.balanceBase)}</span>
          {foreignCurrency && (
            <span className="ml-1 text-xs text-gray-500 dark:text-gray-400">({money(account.balance, foreignCurrency)})</span>
          )}
          {account.balanceDate && (
            <span className="ml-2 text-xs text-gray-500 dark:text-gray-400">
              {t("networth.cash_bank_as_of", { date: formatDate(account.balanceDate, i18n.language) })}
            </span>
          )}
        </span>
      ) : (
        <span className="text-xs text-gray-500 dark:text-gray-400">{t("networth.cash_bank_no_balance")}</span>
      )}
    </li>
  );
}
