import { useEffect, useMemo, useRef, useState } from "react";
import { useTranslation } from "react-i18next";
import { useTransactions, type Transaction } from "@/api/transactions";
import { categoryLabelByCode } from "@/lib/categoryLabel";
import { useCategories } from "@/api/catalog";
import { formatDayMonthYear } from "@/lib/dates";
import { formatMoney } from "@/lib/money";
import { Badge, Button, Input } from "@/components/ui/primitives";

const DEBOUNCE_MS = 350;

/** Picks the purchase a refund nets. Searches the household's own expenses by description, biased to those
 *  on or before the refund's date — money comes back after it went out — and never offers a refund as a
 *  target, since a refund of a refund is not a thing. */
export function OriginalExpensePicker({
  householdId,
  currency,
  locale,
  /** The refund's own date: originals after it are not offered. */
  before,
  selected,
  onSelect,
}: {
  householdId: string;
  currency: string;
  locale: string;
  before: string;
  selected: Transaction | null;
  onSelect: (original: Transaction | null) => void;
}) {
  const { t } = useTranslation();
  const [text, setText] = useState("");
  const [query, setQuery] = useState("");
  const [open, setOpen] = useState(false);
  const rootRef = useRef<HTMLDivElement | null>(null);
  const { data: categories = [] } = useCategories(householdId);

  useEffect(() => {
    const handle = window.setTimeout(() => setQuery(text), DEBOUNCE_MS);
    return () => window.clearTimeout(handle);
  }, [text]);

  useEffect(() => {
    function onClickOutside(e: MouseEvent) {
      if (rootRef.current && !rootRef.current.contains(e.target as Node)) setOpen(false);
    }
    document.addEventListener("mousedown", onClickOutside);
    return () => document.removeEventListener("mousedown", onClickOutside);
  }, []);

  const filters = useMemo(
    () => ({
      direction: "expense" as const,
      isRefund: false,
      q: query.trim() || undefined,
      to: before || undefined,
      size: 15,
      sort: "date_desc",
    }),
    [query, before],
  );
  const { data, isFetching } = useTransactions(householdId, filters);
  const results = data?.items ?? [];

  if (selected) {
    return (
      <div className="flex flex-wrap items-center justify-between gap-2 rounded-md border border-border p-2 text-sm">
        <span className="min-w-0">
          <span className="font-medium">{formatMoney(selected.amount, currency, locale)}</span>
          <span className="ml-2 text-gray-600 dark:text-gray-300">
            {selected.description || categoryLabelByCode(selected.categoryCode, categories, t)}
          </span>
          <span className="ml-2 text-xs text-gray-500 dark:text-gray-400">
            {formatDayMonthYear(selected.occurrenceDate, locale)}
          </span>
        </span>
        <Button type="button" variant="ghost" className="px-2 py-1 text-xs" onClick={() => onSelect(null)}>
          {t("tx.refund_unlink")}
        </Button>
      </div>
    );
  }

  return (
    <div ref={rootRef} className="relative">
      <Input
        value={text}
        placeholder={t("tx.refund_search_placeholder")}
        onChange={(e) => { setText(e.target.value); setOpen(true); }}
        onFocus={() => setOpen(true)}
        role="combobox"
        aria-expanded={open}
        aria-autocomplete="list"
      />
      {open && (
        <div className="absolute z-30 mt-1 max-h-64 w-full overflow-y-auto rounded-md border border-border-strong bg-overlay shadow-lg">
          {isFetching && <p className="px-3 py-2 text-sm text-gray-500 dark:text-gray-400">{t("common.loading")}</p>}
          {!isFetching && results.length === 0 && (
            <p className="px-3 py-2 text-sm text-gray-500 dark:text-gray-400">{t("tx.refund_no_originals")}</p>
          )}
          {results.map((tx) => (
            <button
              key={tx.id}
              type="button"
              className="block w-full px-3 py-2 text-left text-sm hover:bg-row-hover"
              onClick={() => { onSelect(tx); setOpen(false); }}
            >
              <span className="font-medium">{formatMoney(tx.amount, currency, locale)}</span>
              <span className="ml-2 text-gray-600 dark:text-gray-300">{tx.description || t("tx.no_description")}</span>
              <span className="ml-2 text-xs text-gray-500 dark:text-gray-400">
                {[formatDayMonthYear(tx.occurrenceDate, locale), categoryLabelByCode(tx.categoryCode, categories, t)].join(" · ")}
              </span>
              {tx.refundedTotal && (
                <Badge tone="amber">
                  {t("tx.refunded_total", { amount: formatMoney(tx.refundedTotal, currency, locale) })}
                </Badge>
              )}
            </button>
          ))}
        </div>
      )}
    </div>
  );
}
