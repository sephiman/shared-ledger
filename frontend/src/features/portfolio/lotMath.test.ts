import { describe, expect, it } from "vitest";
import {
  computeQuantity,
  computeTotalPrice,
  computeUnitPrice,
  createLotMeasureState,
  updateLotMeasure,
} from "./lotMath";

describe("lotMath pure formulas and zero/invalid guards", () => {
  it("computes totalPrice from positive quantity and non-negative unitPrice", () => {
    expect(computeTotalPrice("2.5", "400")).toBe("1000");
    expect(computeTotalPrice("2", "0")).toBe("0");
    expect(computeTotalPrice("0.000001", "0.000001")).toBe("0.000000000001");
  });

  it("rejects zero/negative/invalid quantity when computing totalPrice", () => {
    expect(computeTotalPrice("0", "100")).toBe("");
    expect(computeTotalPrice("0.000", "100")).toBe("");
    expect(computeTotalPrice("-2", "100")).toBe("");
    expect(computeTotalPrice("2", "-100")).toBe("");
    expect(computeTotalPrice("", "100")).toBe("");
    expect(computeTotalPrice("abc", "100")).toBe("");
    expect(computeTotalPrice("Infinity", "100")).toBe("");
    expect(computeTotalPrice("NaN", "100")).toBe("");
  });

  it("computes unitPrice from quantity and totalPrice, guarding division by zero", () => {
    expect(computeUnitPrice("3", "100")).toBe("33.333333333333");
    expect(computeUnitPrice("2", "0")).toBe("0");
    expect(computeUnitPrice("0", "100")).toBe("");
    expect(computeUnitPrice("0", "0")).toBe("");
    expect(computeUnitPrice("-3", "100")).toBe("");
    expect(computeUnitPrice("3", "-100")).toBe("");
    expect(computeUnitPrice("3", "")).toBe("");
    expect(computeUnitPrice("NaN", "100")).toBe("");
  });

  it("computes quantity from unitPrice and totalPrice, guarding division by zero", () => {
    expect(computeQuantity("50000", "500")).toBe("0.01");
    expect(computeQuantity("30", "100")).toBe("3.333333333333");
    expect(computeQuantity("100000000", "1")).toBe("0.00000001");
    expect(computeQuantity("100", "0")).toBe("0");
    expect(computeQuantity("0", "500")).toBe("");
    expect(computeQuantity("0", "0")).toBe("");
    expect(computeQuantity("0.0", "500")).toBe("");
    expect(computeQuantity("-50", "500")).toBe("");
    expect(computeQuantity("50", "-500")).toBe("");
    expect(computeQuantity("", "500")).toBe("");
    expect(computeQuantity("Infinity", "500")).toBe("");
  });
});

describe("lotMath 2-of-3 state machine", () => {
  it("computes quantity when unitPrice and totalPrice are filled in either order", () => {
    let s = createLotMeasureState();
    s = updateLotMeasure(s, "unitPrice", "50000");
    expect(s).toEqual({ quantity: "", unitPrice: "50000", totalPrice: "", ownedOrder: ["unitPrice"] });

    s = updateLotMeasure(s, "totalPrice", "500");
    expect(s.quantity).toBe("0.01");
    expect(s.ownedOrder).toEqual(["unitPrice", "totalPrice"]);

    let rev = createLotMeasureState();
    rev = updateLotMeasure(rev, "totalPrice", "500");
    rev = updateLotMeasure(rev, "unitPrice", "50000");
    expect(rev.quantity).toBe("0.01");
    expect(rev.ownedOrder).toEqual(["totalPrice", "unitPrice"]);
  });

  it("computes unitPrice when quantity and totalPrice are filled", () => {
    let s = createLotMeasureState();
    s = updateLotMeasure(s, "quantity", "3");
    s = updateLotMeasure(s, "totalPrice", "100");
    expect(s.unitPrice).toBe("33.333333333333");
    expect(s.totalPrice).toBe("100");
  });

  it("computes totalPrice when quantity and unitPrice are filled", () => {
    let s = createLotMeasureState();
    s = updateLotMeasure(s, "unitPrice", "250");
    s = updateLotMeasure(s, "quantity", "4");
    expect(s.totalPrice).toBe("1000");
  });

  it("handles zero unitPrice followed by totalPrice without dividing by zero, then recovers when unitPrice becomes positive", () => {
    let s = createLotMeasureState();
    s = updateLotMeasure(s, "unitPrice", "0");
    s = updateLotMeasure(s, "totalPrice", "500");
    expect(s.quantity).toBe("");

    s = updateLotMeasure(s, "unitPrice", "250");
    expect(s.quantity).toBe("2");
    expect(s.totalPrice).toBe("500");
  });

  it("handles zero quantity followed by totalPrice without dividing by zero, then recovers when quantity becomes positive", () => {
    let s = createLotMeasureState();
    s = updateLotMeasure(s, "quantity", "0");
    s = updateLotMeasure(s, "totalPrice", "250");
    expect(s.unitPrice).toBe("");

    s = updateLotMeasure(s, "quantity", "");
    expect(s.totalPrice).toBe("250");
    expect(s.unitPrice).toBe("");

    s = updateLotMeasure(s, "quantity", "0.5");
    expect(s.unitPrice).toBe("500");
  });

  it("clears only the derived field when an owned field is cleared, and does not snap back a cleared derived field", () => {
    let s = createLotMeasureState();
    s = updateLotMeasure(s, "unitPrice", "100");
    s = updateLotMeasure(s, "totalPrice", "500");
    expect(s.quantity).toBe("5");

    // Clearing owned totalPrice clears derived quantity while keeping unitPrice.
    const afterClearOwned = updateLotMeasure(s, "totalPrice", "");
    expect(afterClearOwned).toEqual({
      quantity: "",
      unitPrice: "100",
      totalPrice: "",
      ownedOrder: ["unitPrice"],
    });

    // Clearing derived quantity leaves it blank so the user can type a new value without fighting the input.
    const afterClearDerived = updateLotMeasure(s, "quantity", "");
    expect(afterClearDerived).toEqual({
      quantity: "",
      unitPrice: "100",
      totalPrice: "500",
      ownedOrder: ["unitPrice", "totalPrice"],
    });

    // Typing a new value into quantity promotes it to owned and updates the oldest owned field (unitPrice).
    const afterRetypeDerived = updateLotMeasure(afterClearDerived, "quantity", "10");
    expect(afterRetypeDerived).toEqual({
      quantity: "10",
      unitPrice: "50",
      totalPrice: "500",
      ownedOrder: ["totalPrice", "quantity"],
    });
  });

  it("seeds edit mode with unitPrice and quantity owned so changing quantity updates total and changing total updates unitPrice", () => {
    const initial = createLotMeasureState({ quantity: "2.000000000000", unitPrice: "100.000000000000" });
    expect(initial).toEqual({
      quantity: "2",
      unitPrice: "100",
      totalPrice: "200",
      ownedOrder: ["unitPrice", "quantity"],
    });

    const editedQty = updateLotMeasure(initial, "quantity", "3");
    expect(editedQty.totalPrice).toBe("300");
    expect(editedQty.unitPrice).toBe("100");

    const editedTotal = updateLotMeasure(initial, "totalPrice", "500");
    expect(editedTotal.unitPrice).toBe("250");
    expect(editedTotal.quantity).toBe("2");
  });
});
