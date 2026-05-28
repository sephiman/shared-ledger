import { useState } from "react";
import { useTranslation } from "react-i18next";
import { useActiveHousehold } from "@/auth/AuthContext";
import {
  loanPaymentsExportUrl,
  loansExportUrl,
  useLoans,
  type LoanStatusFilter,
  type LoanSummary,
} from "@/api/loans";
import { formatDate } from "@/lib/dates";
import { formatMoney } from "@/lib/money";
import { Button, Card, CardBody, Chip } from "@/components/ui/primitives";
import { LoanFormDialog } from "./LoanFormDialog";
import { LoanDetailPanel } from "./LoanDetailPanel";

const FILTERS: LoanStatusFilter[] = ["active", "settled", "written_off", "all"];

export function LoansTab() {
  const { t, i18n } = useTranslation();
  const household = useActiveHousehold();
  const locale = i18n.language;

  const [filter, setFilter] = useState<LoanStatusFilter>("active");
  const { data } = useLoans(household.householdId, filter);
  const [form, setForm] = useState<{ editing: LoanSummary | null } | null>(null);
  const [selectedId, setSelectedId] = useState<string | null>(null);

  const money = (v: string) => formatMoney(v, household.currency, locale);
  const loans = data?.loans ?? [];

  return (
    <div className="space-y-4">
      <p className="text-sm text-gray-500 dark:text-gray-400">{t("loans.header_description")}</p>

      <div className="flex flex-wrap items-center justify-between gap-3">
        <div className="flex flex-wrap gap-2">
          {FILTERS.map((f) => (
            <Chip key={f} active={filter === f} onClick={() => setFilter(f)}>
              {t(`loans.filter_${f}`)}
            </Chip>
          ))}
        </div>
        <div className="flex gap-2">
          <a href={loansExportUrl(household.householdId)} download>
            <Button variant="secondary">{t("loans.export_loans")}</Button>
          </a>
          <a href={loanPaymentsExportUrl(household.householdId)} download>
            <Button variant="secondary">{t("loans.export_payments")}</Button>
          </a>
          <Button onClick={() => setForm({ editing: null })}>{t("loans.new_loan")}</Button>
        </div>
      </div>

      <Card>
        <CardBody>
          {loans.length === 0 ? (
            <p className="text-gray-500 dark:text-gray-400">{t("common.empty")}</p>
          ) : (
            <>
              <ul className="space-y-2 md:hidden">
                {loans.map((l) => (
                  <li
                    key={l.id}
                    className="cursor-pointer rounded-md border border-border p-3 hover:bg-gray-50 dark:border-gray-700 dark:hover:bg-gray-700/40"
                    onClick={() => setSelectedId(l.id)}
                  >
                    <div className="flex items-start justify-between gap-2">
                      <div className="min-w-0 flex-1 break-words">
                        <p className="font-medium">{l.borrowerName}</p>
                        <p className="mt-0.5 text-xs text-gray-500 dark:text-gray-400">
                          {formatDate(l.startDate, locale)} · {t(`loans.interest_${l.interestType}`)} · {t(`loans.status_${l.status}`)}
                        </p>
                      </div>
                      <span className="font-mono tabular-nums">{money(l.totalOutstanding)}</span>
                    </div>
                  </li>
                ))}
              </ul>
              <table className="hidden w-full text-sm md:table">
                <thead className="text-left text-gray-500 dark:text-gray-400">
                  <tr>
                    <th className="py-2">{t("loans.borrower_name")}</th>
                    <th>{t("loans.start_date")}</th>
                    <th>{t("loans.interest_type")}</th>
                    <th>{t("loans.status")}</th>
                    <th className="text-right">{t("loans.total_outstanding")}</th>
                  </tr>
                </thead>
                <tbody>
                  {loans.map((l) => (
                    <tr
                      key={l.id}
                      className="cursor-pointer border-t border-border hover:bg-gray-50 dark:hover:bg-gray-700/40"
                      onClick={() => setSelectedId(l.id)}
                    >
                      <td className="py-2 font-medium">{l.borrowerName}</td>
                      <td>{formatDate(l.startDate, locale)}</td>
                      <td>{t(`loans.interest_${l.interestType}`)}</td>
                      <td>{t(`loans.status_${l.status}`)}</td>
                      <td className="text-right font-mono tabular-nums">{money(l.totalOutstanding)}</td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </>
          )}
        </CardBody>
      </Card>

      <LoanFormDialog
        open={form != null}
        householdId={household.householdId}
        editing={form?.editing ?? null}
        onClose={() => setForm(null)}
        onSaved={(loanId) => {
          setForm(null);
          setSelectedId(loanId);
        }}
      />

      {selectedId && (
        <LoanDetailPanel
          householdId={household.householdId}
          loanId={selectedId}
          currency={household.currency}
          locale={locale}
          onClose={() => setSelectedId(null)}
          onEditTerms={(loan) => setForm({ editing: loan })}
        />
      )}
    </div>
  );
}
