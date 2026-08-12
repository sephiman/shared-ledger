import Decimal from "decimal.js";

/** How a transaction reads in the ledger. Amounts are stored as magnitudes with the direction carrying the
 *  sign — except refunds, which are expenses stored negative. Formatting the raw value would show an
 *  expense unsigned, and prefixing a minus by direction would show a refund double-negated; this settles
 *  both by producing the one signed value the row should display. */
export interface SignedTx {
  direction: "income" | "expense";
  amount: string;
}

/** Money in is positive, money out negative — so a refund (a negative expense) comes out positive. */
export function signedTxAmount(tx: SignedTx): Decimal {
  const amount = new Decimal(tx.amount || 0);
  return tx.direction === "income" ? amount : amount.negated();
}

/** Green in the list: money that arrived, whether as income or as a refund. */
export function isPositiveDisplay(tx: SignedTx): boolean {
  return signedTxAmount(tx).gte(0);
}

/** The explicit "+" the list puts on incoming money; the minus comes from the number itself. */
export function displaySign(tx: SignedTx): "+" | "" {
  return signedTxAmount(tx).gt(0) ? "+" : "";
}
