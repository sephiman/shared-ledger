import Decimal from "decimal.js";

/** Matches holding_lots.quantity and holding_lots.unit_price NUMERIC(28, 12) scale. */
export const LOT_MEASURE_SCALE = 12;

export type LotMeasureField = "quantity" | "unitPrice" | "totalPrice";

export interface LotMeasureState {
  quantity: string;
  unitPrice: string;
  totalPrice: string;
  /** Most-recently-edited user-owned fields, newest last (at most two). */
  ownedOrder: LotMeasureField[];
}

const ALL_FIELDS: LotMeasureField[] = ["quantity", "unitPrice", "totalPrice"];

/** Trims pure trailing zeros (10000.000000 -> 10000) without exponent notation for HTML number inputs. */
export function trimZeroTail(value: string): string {
  try {
    return new Decimal(value).toFixed();
  } catch {
    return value;
  }
}

export function parseNonNegativeDecimal(raw: string): Decimal | null {
  if (!raw.trim()) return null;
  try {
    const parsed = new Decimal(raw);
    return parsed.isFinite() && parsed.gte(0) ? parsed : null;
  } catch {
    return null;
  }
}

export function computeTotalPrice(quantity: string, unitPrice: string): string {
  const qty = parseNonNegativeDecimal(quantity);
  const unit = parseNonNegativeDecimal(unitPrice);
  if (qty == null || qty.lte(0) || unit == null) return "";
  return qty.mul(unit).toDecimalPlaces(LOT_MEASURE_SCALE).toFixed();
}

export function computeUnitPrice(quantity: string, totalPrice: string): string {
  const qty = parseNonNegativeDecimal(quantity);
  const total = parseNonNegativeDecimal(totalPrice);
  if (qty == null || qty.lte(0) || total == null) return "";
  return total.div(qty).toDecimalPlaces(LOT_MEASURE_SCALE).toFixed();
}

export function computeQuantity(unitPrice: string, totalPrice: string): string {
  const unit = parseNonNegativeDecimal(unitPrice);
  const total = parseNonNegativeDecimal(totalPrice);
  if (unit == null || unit.lte(0) || total == null) return "";
  return total.div(unit).toDecimalPlaces(LOT_MEASURE_SCALE).toFixed();
}

export function createLotMeasureState(editing?: { quantity: string; unitPrice: string } | null): LotMeasureState {
  if (!editing) {
    return { quantity: "", unitPrice: "", totalPrice: "", ownedOrder: [] };
  }
  const quantity = trimZeroTail(editing.quantity);
  const unitPrice = trimZeroTail(editing.unitPrice);
  return {
    quantity,
    unitPrice,
    totalPrice: computeTotalPrice(quantity, unitPrice),
    ownedOrder: ["unitPrice", "quantity"],
  };
}

/** Updates one of quantity / unitPrice / totalPrice and computes the third whenever two valid values are owned. */
export function updateLotMeasure(
  state: LotMeasureState,
  field: LotMeasureField,
  nextValue: string,
): LotMeasureState {
  const next: LotMeasureState = { ...state, [field]: nextValue };
  const parsed = parseNonNegativeDecimal(nextValue);

  if (parsed == null) {
    const wasOwned = state.ownedOrder.includes(field);
    const prevDerived =
      state.ownedOrder.length === 2 ? ALL_FIELDS.find((f) => !state.ownedOrder.includes(f)) : undefined;
    next.ownedOrder = state.ownedOrder.filter((f) => f !== field);
    if (wasOwned && prevDerived) {
      next[prevDerived] = "";
    }
    return next;
  }

  const nextOwned = [...state.ownedOrder.filter((f) => f !== field), field].slice(-2);
  next.ownedOrder = nextOwned;

  if (nextOwned.length === 2) {
    const derived = ALL_FIELDS.find((f) => !nextOwned.includes(f));
    if (derived === "totalPrice") {
      next.totalPrice = computeTotalPrice(next.quantity, next.unitPrice);
    } else if (derived === "unitPrice") {
      next.unitPrice = computeUnitPrice(next.quantity, next.totalPrice);
    } else if (derived === "quantity") {
      next.quantity = computeQuantity(next.unitPrice, next.totalPrice);
    }
  }

  return next;
}
