import { useTranslation } from "react-i18next";
import { useUpdateAccount, type BankConnection } from "@/api/banks";
import { Button } from "@/components/ui/primitives";
import { InfoTip } from "@/components/ui/InfoTip";

/** The accounts one consent granted, each pausable on its own beneath the connection-level pause. */
export function BankAccountList({ householdId, connection }: { householdId: string; connection: BankConnection }) {
  const { t } = useTranslation();
  const update = useUpdateAccount(householdId);
  const { accounts } = connection;
  if (accounts.length === 0) return null;
  const allPaused = accounts.every((a) => !a.ingestionEnabled);

  return (
    <div className="mt-2">
      {!connection.ingestionEnabled ? (
        <p className="text-xs text-gray-500 dark:text-gray-400">{t("banks.accounts_connection_paused")}</p>
      ) : (
        allPaused && <p className="text-xs text-amber-700 dark:text-amber-400">{t("banks.accounts_all_paused")}</p>
      )}
      <ul className={`mt-1 space-y-1 ${connection.ingestionEnabled ? "" : "opacity-60"}`}>
        {accounts.map((account) => {
          const label = [account.name, account.ibanMasked, account.currency].filter(Boolean).join(" · ");
          return (
            <li key={account.id} className="flex flex-wrap items-center justify-between gap-2 text-sm">
              <span className="min-w-0 break-words">
                {label}
                <span
                  className={`ml-2 rounded px-1.5 py-0.5 text-xs ${
                    account.ingestionEnabled
                      ? "bg-emerald-100 text-emerald-800 dark:bg-emerald-900/50 dark:text-emerald-200"
                      : "bg-gray-100 text-gray-700 dark:bg-gray-800 dark:text-gray-300"
                  }`}
                >
                  {account.ingestionEnabled ? t("banks.account_active") : t("banks.account_paused")}
                </span>
                {!account.ingestionEnabled && (
                  <InfoTip label={t("banks.account_resume_info_label")} className="ml-1">
                    {t("banks.account_resume_info")}
                  </InfoTip>
                )}
              </span>
              {connection.canManage && (
                <Button
                  variant="ghost"
                  disabled={update.isPending}
                  aria-label={`${account.ingestionEnabled ? t("banks.account_pause") : t("banks.account_resume")} ${label}`}
                  onClick={() =>
                    update.mutate({ connectionId: connection.id, accountId: account.id, ingestionEnabled: !account.ingestionEnabled })
                  }
                >
                  {account.ingestionEnabled ? t("banks.account_pause") : t("banks.account_resume")}
                </Button>
              )}
            </li>
          );
        })}
      </ul>
    </div>
  );
}
