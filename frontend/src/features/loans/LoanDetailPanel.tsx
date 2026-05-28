import { useState } from "react";
import { useTranslation } from "react-i18next";
import {
  useDeletePayment,
  useLoan,
  useSettleLoan,
  useWriteOffLoan,
  useReopenLoan,
  type LoanPayment,
  type LoanSummary,
} from "@/api/loans";
import { formatDate } from "@/lib/dates";
import { formatMoney } from "@/lib/money";
import { Button, Card, CardBody, CardHeader } from "@/components/ui/primitives";
import { LoanPaymentForm } from "./LoanPaymentForm";
import { ScheduleEditor } from "./ScheduleEditor";

interface Props {
  householdId: string;
  loanId: string;
  currency: string;
  locale: string;
  onClose: () => void;
  onEditTerms: (loan: LoanSummary) => void;
}

export function LoanDetailPanel({ householdId, loanId, currency, locale, onClose, onEditTerms }: Props) {
  const { t } = useTranslation();
  const { data: detail, isLoading } = useLoan(householdId, loanId);
  const settle = useSettleLoan(householdId);
  const writeOff = useWriteOffLoan(householdId);
  const reopen = useReopenLoan(householdId);
  const deletePayment = useDeletePayment(householdId);

  const [paymentForm, setPaymentForm] = useState<{ editing: LoanPayment | null } | null>(null);

  const money = (v: string) => formatMoney(v, currency, locale);
  const active = detail?.summary.status === "active";

  return (
    <div className="fixed inset-0 z-40 flex justify-end bg-black/40" role="dialog" aria-modal="true" onClick={onClose}>
      <div
        className="h-full w-full max-w-lg overflow-y-auto bg-gray-50 p-4 shadow-xl dark:bg-gray-900"
        onClick={(e) => e.stopPropagation()}
      >
        <div className="mb-3 flex items-center justify-between">
          <h2 className="text-lg font-semibold">{detail?.summary.borrowerName ?? t("common.loading")}</h2>
          <Button variant="ghost" onClick={onClose}>✕</Button>
        </div>

        {isLoading || !detail ? (
          <p className="text-gray-500">{t("common.loading")}</p>
        ) : (
          <div className="space-y-4">
            <Card>
              <CardBody className="space-y-2">
                <div className="flex items-baseline justify-between">
                  <span className="text-sm text-gray-500 dark:text-gray-400">{t("loans.total_outstanding")}</span>
                  <span className="text-2xl font-semibold">{money(detail.summary.totalOutstanding)}</span>
                </div>
                <div className="grid grid-cols-2 gap-2 text-sm text-gray-600 dark:text-gray-300">
                  <span>{t("loans.principal_remaining")}: {money(detail.summary.principalRemaining)}</span>
                  <span>{t("loans.accrued_interest")}: {money(detail.summary.accruedInterest)}</span>
                  <span>{t("loans.principal_amount")}: {money(detail.summary.principalAmount)}</span>
                  <span>{t("loans.start_date")}: {formatDate(detail.summary.startDate, locale)}</span>
                  <span>
                    {t("loans.interest_type")}: {t(`loans.interest_${detail.summary.interestType}`)}
                    {detail.summary.annualInterestRate ? ` (${detail.summary.annualInterestRate}%)` : ""}
                  </span>
                  <span>
                    {t("loans.status")}: {t(`loans.status_${detail.summary.status}`)}
                    {detail.summary.closedDate ? ` · ${formatDate(detail.summary.closedDate, locale)}` : ""}
                  </span>
                </div>
                {detail.summary.description && (
                  <p className="text-sm text-gray-500 dark:text-gray-400">{detail.summary.description}</p>
                )}
                <div className="flex flex-wrap gap-2 pt-2">
                  {active && (
                    <Button variant="secondary" onClick={() => onEditTerms(detail.summary)}>{t("loans.edit_loan")}</Button>
                  )}
                  {active && (
                    <Button
                      variant="secondary"
                      onClick={() => { if (window.confirm(t("loans.confirm_settle"))) settle.mutate({ id: loanId }); }}
                    >
                      {t("loans.mark_settled")}
                    </Button>
                  )}
                  {active && (
                    <Button
                      variant="secondary"
                      onClick={() => { if (window.confirm(t("loans.confirm_write_off"))) writeOff.mutate({ id: loanId }); }}
                    >
                      {t("loans.mark_written_off")}
                    </Button>
                  )}
                  {!active && (
                    <Button variant="secondary" onClick={() => reopen.mutate(loanId)}>{t("loans.reopen")}</Button>
                  )}
                </div>
              </CardBody>
            </Card>

            <ScheduleEditor householdId={householdId} loanId={loanId} schedule={detail.schedule} disabled={!active} />

            <Card>
              <CardHeader className="flex items-center justify-between">
                <p className="font-medium">{t("loans.payments")}</p>
                {active && !paymentForm && (
                  <Button onClick={() => setPaymentForm({ editing: null })}>{t("loans.register_payment")}</Button>
                )}
              </CardHeader>
              <CardBody className="space-y-3">
                {paymentForm && (
                  <LoanPaymentForm
                    householdId={householdId}
                    detail={detail}
                    editing={paymentForm.editing}
                    currency={currency}
                    locale={locale}
                    onDone={() => setPaymentForm(null)}
                  />
                )}
                {detail.payments.length === 0 ? (
                  <p className="text-gray-500 dark:text-gray-400">{t("common.empty")}</p>
                ) : (
                  <div className="overflow-x-auto">
                    <table className="w-full text-sm">
                      <thead className="text-left text-gray-500 dark:text-gray-400">
                        <tr>
                          <th className="py-1">{t("loans.payment_date")}</th>
                          <th className="text-right">{t("loans.amount")}</th>
                          <th className="text-right">{t("loans.interest_paid")}</th>
                          <th className="text-right">{t("loans.principal_paid")}</th>
                          <th></th>
                        </tr>
                      </thead>
                      <tbody>
                        {detail.payments.map((p) => (
                          <tr key={p.id} className="border-t border-border">
                            <td className="py-1">
                              {formatDate(p.paymentDate, locale)}
                              {p.scheduleId && <span className="ml-1 text-xs text-gray-400">⟳</span>}
                            </td>
                            <td className="text-right font-mono tabular-nums">{money(p.amount)}</td>
                            <td className="text-right font-mono tabular-nums">{money(p.interestPaid)}</td>
                            <td className="text-right font-mono tabular-nums">{money(p.principalPaid)}</td>
                            <td className="text-right">
                              <div className="inline-flex gap-1">
                                <Button
                                  variant="ghost"
                                  className="px-2"
                                  aria-label={t("common.edit")}
                                  title={t("common.edit")}
                                  onClick={() => setPaymentForm({ editing: p })}
                                >
                                  <span aria-hidden>✏️</span>
                                </Button>
                                <Button
                                  variant="ghost"
                                  className="px-2"
                                  aria-label={t("common.delete")}
                                  title={t("common.delete")}
                                  onClick={() => {
                                    if (window.confirm(t("common.delete") + "?")) {
                                      deletePayment.mutate({ loanId, paymentId: p.id });
                                    }
                                  }}
                                >
                                  <span aria-hidden>🗑️</span>
                                </Button>
                              </div>
                            </td>
                          </tr>
                        ))}
                      </tbody>
                    </table>
                  </div>
                )}
              </CardBody>
            </Card>
          </div>
        )}
      </div>
    </div>
  );
}
