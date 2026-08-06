import { useState } from "react";
import { useTranslation } from "react-i18next";
import {
  useDeletePayment,
  useLending,
  useSettleLending,
  useWriteOffLending,
  useReopenLending,
  type LendingPayment,
  type LendingSummary,
} from "@/api/lendings";
import { formatDate } from "@/lib/dates";
import { formatMoney } from "@/lib/money";
import { Button, Card, CardBody, CardHeader } from "@/components/ui/primitives";
import { LendingPaymentForm } from "./LendingPaymentForm";
import { ScheduleEditor } from "./ScheduleEditor";

interface Props {
  householdId: string;
  lendingId: string;
  currency: string;
  locale: string;
  onClose: () => void;
  onEditTerms: (lending: LendingSummary) => void;
}

export function LendingDetailPanel({ householdId, lendingId, currency, locale, onClose, onEditTerms }: Props) {
  const { t } = useTranslation();
  const { data: detail, isLoading } = useLending(householdId, lendingId);
  const settle = useSettleLending(householdId);
  const writeOff = useWriteOffLending(householdId);
  const reopen = useReopenLending(householdId);
  const deletePayment = useDeletePayment(householdId);

  const [paymentForm, setPaymentForm] = useState<{ editing: LendingPayment | null } | null>(null);

  const money = (v: string) => formatMoney(v, currency, locale);
  const active = detail?.summary.status === "active";

  return (
    <div className="fixed inset-0 z-40 flex justify-end bg-black/40" role="dialog" aria-modal="true" onClick={onClose}>
      <div
        className="h-full w-full max-w-lg overflow-y-auto bg-gray-50 p-4 shadow-xl dark:bg-panel"
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
                  <span className="text-sm text-gray-500 dark:text-gray-400">{t("lendings.total_outstanding")}</span>
                  <span className="text-2xl font-semibold">{money(detail.summary.totalOutstanding)}</span>
                </div>
                <div className="grid grid-cols-2 gap-2 text-sm text-gray-600 dark:text-gray-300">
                  <span>{t("lendings.principal_remaining")}: {money(detail.summary.principalRemaining)}</span>
                  <span>{t("lendings.accrued_interest")}: {money(detail.summary.accruedInterest)}</span>
                  <span>{t("lendings.principal_amount")}: {money(detail.summary.principalAmount)}</span>
                  <span>{t("lendings.start_date")}: {formatDate(detail.summary.startDate, locale)}</span>
                  <span>
                    {t("lendings.interest_type")}: {t(`lendings.interest_${detail.summary.interestType}`)}
                    {detail.summary.annualInterestRate ? ` (${detail.summary.annualInterestRate}%)` : ""}
                  </span>
                  <span>
                    {t("lendings.status")}: {t(`lendings.status_${detail.summary.status}`)}
                    {detail.summary.closedDate ? ` · ${formatDate(detail.summary.closedDate, locale)}` : ""}
                  </span>
                </div>
                {detail.summary.description && (
                  <p className="text-sm text-gray-500 dark:text-gray-400">{detail.summary.description}</p>
                )}
                <div className="flex flex-wrap gap-2 pt-2">
                  {active && (
                    <Button variant="secondary" onClick={() => onEditTerms(detail.summary)}>{t("lendings.edit_lending")}</Button>
                  )}
                  {active && (
                    <Button
                      variant="secondary"
                      onClick={() => { if (window.confirm(t("lendings.confirm_settle"))) settle.mutate({ id: lendingId }); }}
                    >
                      {t("lendings.mark_settled")}
                    </Button>
                  )}
                  {active && (
                    <Button
                      variant="secondary"
                      onClick={() => { if (window.confirm(t("lendings.confirm_write_off"))) writeOff.mutate({ id: lendingId }); }}
                    >
                      {t("lendings.mark_written_off")}
                    </Button>
                  )}
                  {!active && (
                    <Button variant="secondary" onClick={() => reopen.mutate(lendingId)}>{t("lendings.reopen")}</Button>
                  )}
                </div>
              </CardBody>
            </Card>

            <ScheduleEditor householdId={householdId} lendingId={lendingId} schedule={detail.schedule} disabled={!active} />

            <Card>
              <CardHeader className="flex items-center justify-between">
                <p className="font-medium">{t("lendings.payments")}</p>
                {active && !paymentForm && (
                  <Button onClick={() => setPaymentForm({ editing: null })}>{t("lendings.register_payment")}</Button>
                )}
              </CardHeader>
              <CardBody className="space-y-3">
                {paymentForm && (
                  <LendingPaymentForm
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
                  <>
                    <ul className="space-y-2 md:hidden">
                      {detail.payments.map((p) => (
                        <li key={p.id} className="rounded-md border border-border p-3">
                          <div className="flex items-start justify-between gap-2">
                            <div className="min-w-0 flex-1 break-words">
                              <p className="text-sm font-medium">
                                {formatDate(p.paymentDate, locale)}
                                {p.scheduleId && <span className="ml-1 text-xs text-gray-400">⟳</span>}
                              </p>
                              <p className="mt-0.5 text-xs text-gray-500 dark:text-gray-400">
                                {t("lendings.interest_paid")}: {money(p.interestPaid)} · {t("lendings.principal_paid")}: {money(p.principalPaid)}
                              </p>
                            </div>
                            <div className="flex flex-col items-end gap-1">
                              <span className="font-mono tabular-nums">{money(p.amount)}</span>
                              <div className="flex gap-1">
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
                                      deletePayment.mutate({ lendingId, paymentId: p.id });
                                    }
                                  }}
                                >
                                  <span aria-hidden>🗑️</span>
                                </Button>
                              </div>
                            </div>
                          </div>
                        </li>
                      ))}
                    </ul>
                    <table className="hidden w-full text-sm md:table">
                      <thead className="text-left text-gray-500 dark:text-gray-400">
                        <tr>
                          <th className="py-1">{t("lendings.payment_date")}</th>
                          <th className="text-right">{t("lendings.amount")}</th>
                          <th className="text-right">{t("lendings.interest_paid")}</th>
                          <th className="text-right">{t("lendings.principal_paid")}</th>
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
                                      deletePayment.mutate({ lendingId, paymentId: p.id });
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
                  </>
                )}
              </CardBody>
            </Card>
          </div>
        )}
      </div>
    </div>
  );
}
