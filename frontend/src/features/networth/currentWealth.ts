import Decimal from "decimal.js";
import { toDecimal } from "@/lib/money";
import type { AssetClass } from "@/api/catalog";
import type { Asset, Liability, NamedValuesAtDate, PrefillView } from "@/api/networth";

// Live net-worth figures for the Home tile: the exact values a New-snapshot form would
// prefill at the current date (see SnapshotsTab). Per asset class the previous snapshot's
// value carries over, then holdings priced today (portfolio valuation byClass) and the
// flow-based cash estimate overwrite it; named assets and liabilities carry over from the
// previous snapshot (falling back to their latest entry), then their computed value at the
// date (amortizable → schedule, manual → series) overwrites it. Nothing is saved.

export interface CurrentWealthInput {
  assetClasses: AssetClass[];
  prefill: PrefillView;
  namedAssets: Asset[];
  liabilities: Liability[];
  /** Portfolio valuation at today, keyed by snapshot asset-class code. */
  portfolioByClass: Record<string, string>;
  /** Computed named-asset/liability values and cash estimate at today. */
  namedValues: NamedValuesAtDate;
}

export interface CurrentWealth {
  cash: Decimal;
  /** Investable holdings classes (everything that is neither cash nor pension). */
  portfolio: Decimal;
  pension: Decimal;
  /** Named assets total (property, vehicles…). */
  namedAssets: Decimal;
  /** Total debts, as a positive magnitude (render as a subtraction). */
  liabilities: Decimal;
  /** cash + portfolio + pension + namedAssets − liabilities. */
  netWorth: Decimal;
  /** True when every component is zero — the household has no wealth data yet. */
  isEmpty: boolean;
}

export function computeCurrentWealth(input: CurrentWealthInput): CurrentWealth {
  const zero = new Decimal(0);

  const classValues: Record<string, Decimal> = {};
  for (const cls of input.assetClasses) {
    const prev = input.prefill.previous?.assets.find((a) => a.assetClassCode === cls.code);
    classValues[cls.code] = toDecimal(prev?.value ?? 0);
  }
  for (const [code, value] of Object.entries(input.portfolioByClass)) {
    classValues[code] = toDecimal(value);
  }
  if (input.namedValues.cash != null) {
    classValues["cash"] = toDecimal(input.namedValues.cash);
  }

  const cash = classValues["cash"] ?? zero;
  const pension = classValues["pension"] ?? zero;
  let portfolio = zero;
  for (const [code, value] of Object.entries(classValues)) {
    if (code !== "cash" && code !== "pension") portfolio = portfolio.plus(value);
  }

  let namedAssets = zero;
  for (const asset of input.namedAssets.filter((a) => a.active)) {
    const prev = input.prefill.previous?.namedAssets.find((a) => a.assetId === asset.id);
    namedAssets = namedAssets.plus(
      toDecimal(input.namedValues.assets[asset.id] ?? prev?.value ?? asset.latestValue ?? 0),
    );
  }

  let liabilities = zero;
  for (const liability of input.liabilities.filter((l) => l.active)) {
    const prev = input.prefill.previous?.liabilities.find((l) => l.liabilityId === liability.id);
    liabilities = liabilities.plus(
      toDecimal(input.namedValues.liabilities[liability.id] ?? prev?.balance ?? liability.latestBalance ?? 0),
    );
  }

  const netWorth = cash.plus(portfolio).plus(pension).plus(namedAssets).minus(liabilities);
  const isEmpty =
    cash.isZero() && portfolio.isZero() && pension.isZero() && namedAssets.isZero() && liabilities.isZero();

  return { cash, portfolio, pension, namedAssets, liabilities, netWorth, isEmpty };
}
