import { useEffect, useRef, useState } from "react";
import { Link } from "react-router-dom";
import { useTranslation } from "react-i18next";
import type {
  SavingsCandidates,
  SpendingReviewDecision,
  SpendingReviewPeriod,
  UnderlyingBudgetOverrun,
  UnderlyingMover,
} from "@/api/analyticsAi";
import { useCategories } from "@/api/catalog";
import { Badge, Button, Card, CardBody } from "@/components/ui/primitives";
import { categoryLabelByCode } from "@/lib/categoryLabel";
import { formatDate, formatDayMonthYear } from "@/lib/dates";
import { formatPercent, formatWholeMoney } from "@/lib/money";
import { explorerUrlFromLink, transactionsUrlFromFilter } from "./reviewLinks";

export type UnderlyingDataGroup = "savings" | "changes" | "budgets" | "decisions";

interface Props {
  householdId: string;
  currency: string;
  period: SpendingReviewPeriod;
  candidates: SavingsCandidates;
  decisions?: SpendingReviewDecision[];
  onUndoDecision?: (decisionId: string) => void;
  decisionBusy?: boolean;
  underlyingMovers?: UnderlyingMover[];
  underlyingBudgetOverruns?: UnderlyingBudgetOverrun[];
  open: boolean;
  focusGroup: UnderlyingDataGroup | null;
  onToggle: () => void;
}

/** Collapsible "Underlying data" disclosure beneath the report skeleton: shows the full unfiltered
 *  local tables (all recurring charges including unchanged essentials, all merchants, merged variants,
 *  period increases, occasional categories, and budget overruns). */
export function SavingsCandidatesPanel({
  householdId,
  currency,
  period,
  candidates,
  decisions = [],
  onUndoDecision,
  decisionBusy = false,
  underlyingMovers = [],
  underlyingBudgetOverruns = [],
  open,
  focusGroup,
  onToggle,
}: Props) {
  const { t, i18n } = useTranslation();
  const locale = i18n.language;
  const { data: categories = [] } = useCategories(householdId);

  const savingsRef = useRef<HTMLDivElement | null>(null);
  const changesRef = useRef<HTMLDivElement | null>(null);
  const budgetsRef = useRef<HTMLDivElement | null>(null);
  const decisionsRef = useRef<HTMLDivElement | null>(null);

  useEffect(() => {
    if (!open || !focusGroup) return;
    const target =
      focusGroup === "savings"
        ? savingsRef.current
        : focusGroup === "changes"
          ? changesRef.current
          : focusGroup === "decisions"
            ? decisionsRef.current
            : budgetsRef.current;
    if (target && typeof target.scrollIntoView === "function") {
      target.scrollIntoView({ behavior: "smooth", block: "start" });
    }
  }, [open, focusGroup]);

  const money = (v: string | number) => formatWholeMoney(v, currency, locale);
  const catName = (code: string) => categoryLabelByCode(code, categories, t);
  const oneOffs = candidates.oneOffCategories ?? [];
  const hasAny =
    candidates.recurringCharges.length > 0 ||
    candidates.topNonEssentialMerchants.length > 0 ||
    candidates.feesAndInterest.fees.length > 0 ||
    candidates.feesAndInterest.loanInterest != null ||
    candidates.lumpyCategories.length > 0 ||
    oneOffs.length > 0 ||
    underlyingMovers.length > 0 ||
    underlyingBudgetOverruns.length > 0 ||
    decisions.length > 0;

  return (
    <Card>
      <div className="flex flex-wrap items-center justify-between gap-2 px-5 py-3.5">
        <div>
          <button
            type="button"
            aria-expanded={open}
            onClick={onToggle}
            className="text-left text-sm font-semibold text-gray-900 hover:underline dark:text-gray-100"
          >
            {open ? "▾ " : "▸ "}
            {t("analytics.review.underlying_data_title")}
          </button>
          <p className="mt-0.5 text-xs text-gray-500 dark:text-gray-400">
            {t("analytics.review.based_on_subtitle", {
              from: formatDayMonthYear(period.windowFrom, locale),
              to: formatDayMonthYear(period.windowTo, locale),
            })}
          </p>
        </div>
        {Number(candidates.summary.combinedPotentialMonthly) > 0 && (
          <div className="text-right">
            <p className="text-xs text-gray-500 dark:text-gray-400">{t("analytics.review.combined_potential")}</p>
            <p className="text-sm font-semibold tabular-nums text-emerald-700 dark:text-emerald-300">
              {t("analytics.review.per_month_and_year", {
                monthly: money(candidates.summary.combinedPotentialMonthly),
                annual: money(candidates.summary.combinedPotentialAnnual),
              })}
            </p>
          </div>
        )}
      </div>

      {open && (
        <CardBody className="space-y-6 border-t border-border pt-4">
          {!hasAny && (
            <p className="py-4 text-center text-sm text-gray-500 dark:text-gray-400">
              {t("analytics.review.no_candidates")}
            </p>
          )}

          <div ref={savingsRef} data-underlying-group="savings" className="space-y-6">
            {candidates.recurringCharges.length > 0 && (
              <section className="space-y-2.5">
                <h3 className="text-xs font-semibold uppercase tracking-wider text-gray-500 dark:text-gray-400">
                  {t("analytics.review.recurring_title")}
                </h3>
                <div className="overflow-x-auto">
                  <table className="w-full text-left text-sm">
                    <thead>
                      <tr className="border-b border-border text-xs text-gray-500 dark:text-gray-400">
                        <th className="py-2 pr-3 font-medium">{t("analytics.review.col_merchant")}</th>
                        <th className="py-2 pr-3 font-medium">{t("common.category")}</th>
                        <th className="py-2 pr-3 font-medium">{t("analytics.review.col_cadence")}</th>
                        <th className="py-2 pr-3 text-right font-medium">{t("analytics.review.col_monthly")}</th>
                        <th className="py-2 pr-3 text-right font-medium">{t("analytics.review.col_annual")}</th>
                        <th className="py-2 pr-3 text-right font-medium">{t("analytics.review.col_yoy")}</th>
                        <th className="py-2 text-right font-medium">{t("analytics.review.col_links")}</th>
                      </tr>
                    </thead>
                    <tbody className="divide-y divide-border">
                      {candidates.recurringCharges.map((item) => {
                        const isChangedOnly = item.yoyChangeKind === "changed";
                        const yoyUp = !isChangedOnly && item.yoyDeltaAbs != null && Number(item.yoyDeltaAbs) > 0;
                        const yoyDown = !isChangedOnly && item.yoyDeltaAbs != null && Number(item.yoyDeltaAbs) < 0;
                        return (
                          <tr key={item.id}>
                            <td className="py-2.5 pr-3">
                              <div className="flex flex-wrap items-center gap-1.5">
                                <span className="font-medium text-gray-900 dark:text-gray-100">{item.merchant}</span>
                                {item.essential && (
                                  <Badge tone="neutral">{t("analytics.review.essential_badge")}</Badge>
                                )}
                                {item.source === "template" && (
                                  <Badge tone="sky">{t("analytics.review.template_badge")}</Badge>
                                )}
                                <MergedVariantsToggle variants={item.mergedVariants} />
                                <AttributedEntriesToggle entries={item.attributedEntries} />
                              </div>
                            </td>
                            <td className="py-2.5 pr-3 text-gray-600 dark:text-gray-300">{catName(item.categoryCode)}</td>
                            <td className="py-2.5 pr-3 text-gray-600 dark:text-gray-300">
                              {t(`analytics.review.cadence_${item.cadence}`)}
                            </td>
                            <td className="py-2.5 pr-3 text-right tabular-nums">{money(item.monthlyCost)}</td>
                            <td className="py-2.5 pr-3 text-right tabular-nums">{money(item.annualCost)}</td>
                            <td className="py-2.5 pr-3 text-right tabular-nums">
                              {item.yoyDeltaPct != null ? (
                                <span
                                  className={
                                    yoyUp
                                      ? "text-amber-700 dark:text-amber-300"
                                      : yoyDown
                                        ? "text-emerald-700 dark:text-emerald-300"
                                        : "text-gray-500 dark:text-gray-400"
                                  }
                                >
                                  {item.yoyDeltaPct > 0 ? "+" : ""}
                                  {formatPercent(item.yoyDeltaPct, locale, 1)}
                                  {isChangedOnly ? ` · ${t("analytics.review.yoy_changed_tag")}` : ""}
                                </span>
                              ) : (
                                <span className="text-gray-400">—</span>
                              )}
                            </td>
                            <td className="py-2.5 text-right whitespace-nowrap">
                              <div className="inline-flex items-center gap-2 text-xs">
                                <Link
                                  to={transactionsUrlFromFilter(item.txFilter)}
                                  className="text-sky-700 hover:underline dark:text-sky-300"
                                >
                                  {t("analytics.review.see_transactions")}
                                </Link>
                                <Link
                                  to={explorerUrlFromLink(item.explorerLink)}
                                  className="text-gray-600 hover:underline dark:text-gray-300"
                                >
                                  {t("analytics.review.see_explorer")}
                                </Link>
                              </div>
                            </td>
                          </tr>
                        );
                      })}
                    </tbody>
                  </table>
                </div>
              </section>
            )}

            {candidates.topNonEssentialMerchants.length > 0 && (
              <section className="space-y-2.5">
                <h3 className="text-xs font-semibold uppercase tracking-wider text-gray-500 dark:text-gray-400">
                  {t("analytics.review.merchants_title")}
                </h3>
                <div className="overflow-x-auto">
                  <table className="w-full text-left text-sm">
                    <thead>
                      <tr className="border-b border-border text-xs text-gray-500 dark:text-gray-400">
                        <th className="py-2 pr-3 font-medium">{t("analytics.review.col_merchant")}</th>
                        <th className="py-2 pr-3 font-medium">{t("common.category")}</th>
                        <th className="py-2 pr-3 text-right font-medium">{t("analytics.review.col_total")}</th>
                        <th className="py-2 pr-3 text-right font-medium">{t("analytics.review.col_purchases")}</th>
                        <th className="py-2 pr-3 text-right font-medium">{t("analytics.review.col_avg_ticket")}</th>
                        <th className="py-2 pr-3 text-right font-medium">{t("analytics.review.col_share")}</th>
                        <th className="py-2 text-right font-medium">{t("analytics.review.col_links")}</th>
                      </tr>
                    </thead>
                    <tbody className="divide-y divide-border">
                      {candidates.topNonEssentialMerchants.map((item) => (
                        <tr key={item.id}>
                          <td className="py-2.5 pr-3">
                            <div className="flex flex-wrap items-center gap-1.5">
                              <span className="font-medium text-gray-900 dark:text-gray-100">{item.merchant}</span>
                              <MergedVariantsToggle variants={item.mergedVariants} />
                            </div>
                          </td>
                          <td className="py-2.5 pr-3 text-gray-600 dark:text-gray-300">{catName(item.categoryCode)}</td>
                          <td className="py-2.5 pr-3 text-right tabular-nums">{money(item.total)}</td>
                          <td className="py-2.5 pr-3 text-right tabular-nums">{item.purchases}</td>
                          <td className="py-2.5 pr-3 text-right tabular-nums">{money(item.averageTicket)}</td>
                          <td className="py-2.5 pr-3 text-right tabular-nums">
                            {formatPercent(item.shareOfCategoryPct, locale, 1)}
                          </td>
                          <td className="py-2.5 text-right whitespace-nowrap">
                            <div className="inline-flex items-center gap-2 text-xs">
                              <Link
                                to={transactionsUrlFromFilter(item.txFilter)}
                                className="text-sky-700 hover:underline dark:text-sky-300"
                              >
                                {t("analytics.review.see_transactions")}
                              </Link>
                              <Link
                                to={explorerUrlFromLink(item.explorerLink)}
                                className="text-gray-600 hover:underline dark:text-gray-300"
                              >
                                {t("analytics.review.see_explorer")}
                              </Link>
                            </div>
                          </td>
                        </tr>
                      ))}
                    </tbody>
                  </table>
                </div>
                {candidates.otherMerchants.purchases > 0 && (
                  <p className="text-xs text-gray-500 dark:text-gray-400">
                    {t("analytics.review.other_merchants_line", {
                      total: money(candidates.otherMerchants.total),
                      purchases: candidates.otherMerchants.purchases,
                    })}
                  </p>
                )}
              </section>
            )}

            {(candidates.feesAndInterest.fees.length > 0 || candidates.feesAndInterest.loanInterest != null) && (
              <section className="space-y-2.5">
                <h3 className="text-xs font-semibold uppercase tracking-wider text-gray-500 dark:text-gray-400">
                  {t("analytics.review.fees_title")}
                </h3>
                <ul className="space-y-2">
                  {candidates.feesAndInterest.fees.map((fee) => (
                    <li
                      key={fee.id}
                      className="flex flex-wrap items-center justify-between gap-2 rounded-md border border-border bg-sunken px-3 py-2 text-sm"
                    >
                      <div>
                        <span className="font-medium text-gray-900 dark:text-gray-100">{catName(fee.categoryCode)}</span>
                        <span className="ml-2 text-xs text-gray-500 dark:text-gray-400">
                          {t("analytics.review.fees_detail", {
                            total: money(fee.total12m),
                            monthly: money(fee.monthlyAverage),
                            count: fee.occurrences,
                          })}
                        </span>
                      </div>
                      <div className="inline-flex items-center gap-2 text-xs">
                        <Link
                          to={transactionsUrlFromFilter(fee.txFilter)}
                          className="text-sky-700 hover:underline dark:text-sky-300"
                        >
                          {t("analytics.review.see_transactions")}
                        </Link>
                        <Link
                          to={explorerUrlFromLink(fee.explorerLink)}
                          className="text-gray-600 hover:underline dark:text-gray-300"
                        >
                          {t("analytics.review.see_explorer")}
                        </Link>
                      </div>
                    </li>
                  ))}
                  {candidates.feesAndInterest.loanInterest && (
                    <li className="flex flex-wrap items-center justify-between gap-2 rounded-md border border-border bg-sunken px-3 py-2 text-sm">
                      <div className="flex flex-wrap items-center gap-2">
                        <span className="font-medium text-gray-900 dark:text-gray-100">
                          {t("analytics.review.loan_interest_label")}
                        </span>
                        <Badge tone="neutral">{t("analytics.review.informational_badge")}</Badge>
                        <span className="text-xs text-gray-500 dark:text-gray-400">
                          {t("analytics.review.loan_interest_detail", {
                            total: money(candidates.feesAndInterest.loanInterest.total12m),
                            monthly: money(candidates.feesAndInterest.loanInterest.monthlyAverage),
                          })}
                        </span>
                      </div>
                    </li>
                  )}
                </ul>
              </section>
            )}

            {candidates.lumpyCategories.length > 0 && (
              <section className="space-y-2.5">
                <h3 className="text-xs font-semibold uppercase tracking-wider text-gray-500 dark:text-gray-400">
                  {t("analytics.review.lumpy_title")}
                </h3>
                <div className="overflow-x-auto">
                  <table className="w-full text-left text-sm">
                    <thead>
                      <tr className="border-b border-border text-xs text-gray-500 dark:text-gray-400">
                        <th className="py-2 pr-3 font-medium">{t("common.category")}</th>
                        <th className="py-2 pr-3 text-right font-medium">{t("analytics.review.col_mean")}</th>
                        <th className="py-2 pr-3 text-right font-medium">{t("analytics.review.col_median")}</th>
                        <th className="py-2 pr-3 text-right font-medium">{t("analytics.review.col_trim_monthly")}</th>
                        <th className="py-2 pr-3 text-right font-medium">{t("analytics.review.col_trim_annual")}</th>
                        <th className="py-2 text-right font-medium">{t("analytics.review.col_links")}</th>
                      </tr>
                    </thead>
                    <tbody className="divide-y divide-border">
                      {candidates.lumpyCategories.map((item) => (
                        <tr key={item.id}>
                          <td className="py-2.5 pr-3 font-medium text-gray-900 dark:text-gray-100">
                            {catName(item.categoryCode)}
                          </td>
                          <td className="py-2.5 pr-3 text-right tabular-nums">{money(item.monthlyMean)}</td>
                          <td className="py-2.5 pr-3 text-right tabular-nums">{money(item.monthlyMedian)}</td>
                          <td className="py-2.5 pr-3 text-right tabular-nums font-medium text-emerald-700 dark:text-emerald-300">
                            {money(item.trimToMedianMonthly)}
                          </td>
                          <td className="py-2.5 pr-3 text-right tabular-nums text-emerald-700 dark:text-emerald-300">
                            {money(item.trimToMedianAnnual)}
                          </td>
                          <td className="py-2.5 text-right whitespace-nowrap">
                            <div className="inline-flex items-center gap-2 text-xs">
                              <Link
                                to={transactionsUrlFromFilter(item.txFilter)}
                                className="text-sky-700 hover:underline dark:text-sky-300"
                              >
                                {t("analytics.review.see_transactions")}
                              </Link>
                              <Link
                                to={explorerUrlFromLink(item.explorerLink)}
                                className="text-gray-600 hover:underline dark:text-gray-300"
                              >
                                {t("analytics.review.see_explorer")}
                              </Link>
                            </div>
                          </td>
                        </tr>
                      ))}
                    </tbody>
                  </table>
                </div>
              </section>
            )}
          </div>

          {(underlyingMovers.length > 0 || oneOffs.length > 0) && (
            <div ref={changesRef} data-underlying-group="changes" className="space-y-6">
              {underlyingMovers.length > 0 && (
                <section className="space-y-2.5">
                  <h3 className="text-xs font-semibold uppercase tracking-wider text-gray-500 dark:text-gray-400">
                    {t("analytics.review.underlying_movers_title")}
                  </h3>
                  <div className="overflow-x-auto">
                    <table className="w-full text-left text-sm">
                      <thead>
                        <tr className="border-b border-border text-xs text-gray-500 dark:text-gray-400">
                          <th className="py-2 pr-3 font-medium">{t("common.category")}</th>
                          <th className="py-2 pr-3 text-right font-medium">{t("analytics.review.cite.period_amount")}</th>
                          <th className="py-2 pr-3 text-right font-medium">{t("analytics.review.cite.delta")}</th>
                          <th className="py-2 text-right font-medium">{t("analytics.review.col_links")}</th>
                        </tr>
                      </thead>
                      <tbody className="divide-y divide-border">
                        {underlyingMovers.map((m) => (
                          <tr key={m.categoryCode}>
                            <td className="py-2.5 pr-3 font-medium text-gray-900 dark:text-gray-100">
                              {catName(m.categoryCode)}
                            </td>
                            <td className="py-2.5 pr-3 text-right tabular-nums">{money(m.periodAmount)}</td>
                            <td className="py-2.5 pr-3 text-right tabular-nums text-amber-700 dark:text-amber-300">
                              +{money(m.deltaAbs)}
                            </td>
                            <td className="py-2.5 text-right whitespace-nowrap">
                              <div className="inline-flex items-center gap-2 text-xs">
                                <Link
                                  to={transactionsUrlFromFilter(m.txFilter)}
                                  className="text-sky-700 hover:underline dark:text-sky-300"
                                >
                                  {t("analytics.review.see_transactions")}
                                </Link>
                                <Link
                                  to={explorerUrlFromLink(m.explorerLink)}
                                  className="text-gray-600 hover:underline dark:text-gray-300"
                                >
                                  {t("analytics.review.see_explorer")}
                                </Link>
                              </div>
                            </td>
                          </tr>
                        ))}
                      </tbody>
                    </table>
                  </div>
                </section>
              )}

              {oneOffs.length > 0 && (
                <section className="space-y-2.5">
                  <h3 className="text-xs font-semibold uppercase tracking-wider text-gray-500 dark:text-gray-400">
                    {t("analytics.review.underlying_one_offs_title")}
                  </h3>
                  <div className="overflow-x-auto">
                    <table className="w-full text-left text-sm">
                      <thead>
                        <tr className="border-b border-border text-xs text-gray-500 dark:text-gray-400">
                          <th className="py-2 pr-3 font-medium">{t("common.category")}</th>
                          <th className="py-2 pr-3 text-right font-medium">{t("analytics.review.cite.active_months")}</th>
                          <th className="py-2 pr-3 text-right font-medium">{t("analytics.review.cite.peak_month")}</th>
                          <th className="py-2 text-right font-medium">{t("analytics.review.col_links")}</th>
                        </tr>
                      </thead>
                      <tbody className="divide-y divide-border">
                        {oneOffs.map((item) => (
                          <tr key={item.id}>
                            <td className="py-2.5 pr-3 font-medium text-gray-900 dark:text-gray-100">
                              {catName(item.categoryCode)}
                            </td>
                            <td className="py-2.5 pr-3 text-right tabular-nums">{item.nonZeroMonths}</td>
                            <td className="py-2.5 pr-3 text-right tabular-nums">{money(item.peakMonthAmount)}</td>
                            <td className="py-2.5 text-right whitespace-nowrap">
                              <div className="inline-flex items-center gap-2 text-xs">
                                <Link
                                  to={transactionsUrlFromFilter(item.txFilter)}
                                  className="text-sky-700 hover:underline dark:text-sky-300"
                                >
                                  {t("analytics.review.see_transactions")}
                                </Link>
                                <Link
                                  to={explorerUrlFromLink(item.explorerLink)}
                                  className="text-gray-600 hover:underline dark:text-gray-300"
                                >
                                  {t("analytics.review.see_explorer")}
                                </Link>
                              </div>
                            </td>
                          </tr>
                        ))}
                      </tbody>
                    </table>
                  </div>
                </section>
              )}
            </div>
          )}

          {underlyingBudgetOverruns.length > 0 && (
            <div ref={budgetsRef} data-underlying-group="budgets" className="space-y-2.5">
              <h3 className="text-xs font-semibold uppercase tracking-wider text-gray-500 dark:text-gray-400">
                {t("analytics.review.underlying_budgets_title")}
              </h3>
              <div className="overflow-x-auto">
                <table className="w-full text-left text-sm">
                  <thead>
                    <tr className="border-b border-border text-xs text-gray-500 dark:text-gray-400">
                      <th className="py-2 pr-3 font-medium">{t("common.category")}</th>
                      <th className="py-2 pr-3 text-right font-medium">{t("analytics.review.cite.spent")}</th>
                      <th className="py-2 pr-3 text-right font-medium">{t("analytics.review.cite.budget")}</th>
                      <th className="py-2 pr-3 text-right font-medium">{t("analytics.review.cite.over_under")}</th>
                      <th className="py-2 text-right font-medium">{t("analytics.review.col_links")}</th>
                    </tr>
                  </thead>
                  <tbody className="divide-y divide-border">
                    {underlyingBudgetOverruns.map((b) => (
                      <tr key={b.categoryCode}>
                        <td className="py-2.5 pr-3 font-medium text-gray-900 dark:text-gray-100">
                          {catName(b.categoryCode)}
                        </td>
                        <td className="py-2.5 pr-3 text-right tabular-nums">{money(b.spent)}</td>
                        <td className="py-2.5 pr-3 text-right tabular-nums">{money(b.budget)}</td>
                        <td className="py-2.5 pr-3 text-right tabular-nums text-amber-700 dark:text-amber-300">
                          +{money(b.overUnder)}
                        </td>
                        <td className="py-2.5 text-right whitespace-nowrap">
                          <div className="inline-flex items-center gap-2 text-xs">
                            <Link
                              to={transactionsUrlFromFilter(b.txFilter)}
                              className="text-sky-700 hover:underline dark:text-sky-300"
                            >
                              {t("analytics.review.see_transactions")}
                            </Link>
                            <Link
                              to={explorerUrlFromLink(b.explorerLink)}
                              className="text-gray-600 hover:underline dark:text-gray-300"
                            >
                              {t("analytics.review.see_explorer")}
                            </Link>
                          </div>
                        </td>
                      </tr>
                    ))}
                  </tbody>
                </table>
              </div>
            </div>
          )}
          <div ref={decisionsRef} data-underlying-group="decisions" className="space-y-2.5">
            <h3 className="text-xs font-semibold uppercase tracking-wider text-gray-500 dark:text-gray-400">
              {t("analytics.review.my_decisions_title")}
            </h3>
            {decisions.length === 0 ? (
              <p className="text-xs text-gray-500 dark:text-gray-400">
                {t("analytics.review.my_decisions_empty")}
              </p>
            ) : (
              <div className="overflow-x-auto">
                <table className="w-full text-left text-sm">
                  <thead>
                    <tr className="border-b border-border text-xs text-gray-500 dark:text-gray-400">
                      <th className="py-2 pr-3 font-medium">{t("analytics.review.my_decisions_col_subject")}</th>
                      <th className="py-2 pr-3 font-medium">{t("analytics.review.my_decisions_col_decision")}</th>
                      <th className="py-2 pr-3 text-right font-medium">{t("analytics.review.my_decisions_col_amount")}</th>
                      <th className="py-2 pr-3 font-medium">{t("analytics.review.my_decisions_col_who_when")}</th>
                      <th className="py-2 pr-3 font-medium">{t("analytics.review.my_decisions_col_status")}</th>
                      <th className="py-2 text-right font-medium" />
                    </tr>
                  </thead>
                  <tbody className="divide-y divide-border">
                    {decisions.map((d) => {
                      const statusTone =
                        d.status === "active"
                          ? "green"
                          : d.status === "snoozed"
                            ? "sky"
                            : d.status === "resurfaced" || d.status === "expired"
                              ? "amber"
                              : "neutral";
                      return (
                        <tr key={d.id}>
                          <td className="py-2.5 pr-3">
                            <p className="font-medium text-gray-900 dark:text-gray-100">{d.displayLabel}</p>
                            <p className="text-xs text-gray-500 dark:text-gray-400">{catName(d.categoryCode)}</p>
                          </td>
                          <td className="py-2.5 pr-3 text-gray-700 dark:text-gray-300">
                            {t(`analytics.review.decision_type_${d.decisionType}`)}
                            {d.decisionType === "compared" && d.snoozeUntil && d.status === "snoozed" && (
                              <span className="ml-1 text-xs text-gray-500 dark:text-gray-400">
                                ({t("analytics.review.my_decisions_snooze_until", {
                                  date: formatDayMonthYear(d.snoozeUntil, locale),
                                })})
                              </span>
                            )}
                          </td>
                          <td className="py-2.5 pr-3 text-right tabular-nums text-gray-700 dark:text-gray-300">
                            {d.amountAtDecision != null ? money(d.amountAtDecision) : "—"}
                          </td>
                          <td className="py-2.5 pr-3 text-xs text-gray-600 dark:text-gray-300">
                            <span>{d.decidedBy}</span>
                            <span className="block text-gray-500 dark:text-gray-400">
                              {formatDate(d.decidedAt, locale, "d MMM yyyy")}
                            </span>
                          </td>
                          <td className="py-2.5 pr-3">
                            <Badge tone={statusTone}>
                              {t(`analytics.review.decision_status_${d.status}`, { defaultValue: d.status })}
                            </Badge>
                          </td>
                          <td className="py-2.5 text-right whitespace-nowrap">
                            {onUndoDecision && (
                              <Button
                                variant="secondary"
                                className="px-2 py-1 text-xs"
                                disabled={decisionBusy}
                                onClick={() => onUndoDecision(d.id)}
                              >
                                {t("analytics.review.decision_undo")}
                              </Button>
                            )}
                          </td>
                        </tr>
                      );
                    })}
                  </tbody>
                </table>
              </div>
            )}
          </div>
        </CardBody>
      )}
    </Card>
  );
}

/** Local-only expandable pill & tooltip listing payee name variants merged into one row. */
export function MergedVariantsToggle({ variants }: { variants?: string[] }) {
  const { t } = useTranslation();
  const [open, setOpen] = useState(false);
  if (!variants || variants.length <= 1) return null;
  const list = variants.join(", ");
  const title = t("analytics.review.variants_includes", { list });
  return (
    <>
      <button
        type="button"
        title={title}
        aria-expanded={open}
        onClick={() => setOpen((v) => !v)}
        className="rounded-full border border-border bg-sunken px-2 py-0.5 text-[11px] font-normal text-gray-600 hover:bg-gray-100 dark:text-gray-300 dark:hover:bg-white/10"
      >
        {t("analytics.review.variants_badge", { count: variants.length })}
      </button>
      {open && (
        <span className="basis-full text-xs font-normal text-gray-500 dark:text-gray-400">
          {title}
        </span>
      )}
    </>
  );
}

/** Local-only expandable pill listing gap-month transactions attributed to a recurring charge by amount. */
export function AttributedEntriesToggle({ entries }: { entries?: string[] }) {
  const { t } = useTranslation();
  const [open, setOpen] = useState(false);
  if (!entries || entries.length === 0) return null;
  const list = entries.join(", ");
  const title = t("analytics.review.attributed_includes", { list });
  return (
    <>
      <button
        type="button"
        title={title}
        aria-expanded={open}
        onClick={() => setOpen((v) => !v)}
        className="rounded-full border border-border bg-sunken px-2 py-0.5 text-[11px] font-normal text-gray-600 hover:bg-gray-100 dark:text-gray-300 dark:hover:bg-white/10"
      >
        {t("analytics.review.attributed_badge", { count: entries.length })}
      </button>
      {open && (
        <span className="basis-full text-xs font-normal text-gray-500 dark:text-gray-400">
          {title}
        </span>
      )}
    </>
  );
}
