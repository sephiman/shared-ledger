import { useEffect, useRef, useState } from "react";
import { useTranslation } from "react-i18next";
import { useActiveHousehold } from "@/auth/AuthContext";
import { useSymbolSearch, type HoldingAssetClass, type SymbolCandidate } from "@/api/portfolio";
import { Input } from "@/components/ui/primitives";
import { cn } from "@/lib/cn";

const DEBOUNCE_MS = 350;

/** Debounced provider-backed symbol search: typing queries the price provider for the asset class, picking
 *  hands the candidate to the parent. Funds have no provider, so it degrades to an informative state. */
export function SymbolSearchCombobox({
  assetClass,
  onSelect,
  placeholder,
}: {
  assetClass: HoldingAssetClass;
  onSelect: (candidate: SymbolCandidate) => void;
  placeholder?: string;
}) {
  const { t } = useTranslation();
  const household = useActiveHousehold();
  const [text, setText] = useState("");
  const [query, setQuery] = useState("");
  const [open, setOpen] = useState(false);
  const rootRef = useRef<HTMLDivElement | null>(null);

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

  const { data: candidates = [], isFetching, isError } = useSymbolSearch(
    household.householdId,
    assetClass,
    query,
  );

  if (assetClass === "fund") {
    return <p className="text-sm text-gray-500 dark:text-gray-400">{t("portfolio.fund_no_provider")}</p>;
  }

  return (
    <div ref={rootRef} className="relative">
      <Input
        value={text}
        placeholder={placeholder ?? t("portfolio.search_placeholder")}
        onChange={(e) => {
          setText(e.target.value);
          setOpen(true);
        }}
        onFocus={() => setOpen(true)}
        role="combobox"
        aria-expanded={open}
        aria-autocomplete="list"
      />
      {open && query.trim().length >= 2 && (
        <div className="absolute z-30 mt-1 max-h-64 w-full overflow-y-auto rounded-md border border-border-strong bg-surface shadow-lg">
          {isFetching && (
            <p className="px-3 py-2 text-sm text-gray-500 dark:text-gray-400">{t("common.loading")}</p>
          )}
          {isError && (
            <p className="px-3 py-2 text-sm text-red-600">{t("errors.SYMBOL_SEARCH_PROVIDER_ERROR")}</p>
          )}
          {!isFetching && !isError && candidates.length === 0 && (
            <p className="px-3 py-2 text-sm text-gray-500 dark:text-gray-400">{t("portfolio.no_results")}</p>
          )}
          {candidates.map((c) => (
            <button
              key={`${c.provider}:${c.providerSymbol}:${c.exchange ?? ""}`}
              type="button"
              className={cn(
                "block w-full px-3 py-2 text-left text-sm hover:bg-row-hover",
              )}
              onClick={() => {
                onSelect(c);
                setOpen(false);
                setText(c.symbol ?? c.providerSymbol);
              }}
            >
              <span className="font-medium">{c.symbol ?? c.providerSymbol}</span>
              <span className="ml-2 text-gray-600 dark:text-gray-300">{c.name}</span>
              <span className="ml-2 text-xs text-gray-500 dark:text-gray-400">
                {[c.exchange, c.currency, c.isin].filter(Boolean).join(" · ")}
              </span>
            </button>
          ))}
        </div>
      )}
    </div>
  );
}
