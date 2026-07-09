import { readFileSync } from "node:fs";
import { fileURLToPath } from "node:url";
import { dirname, resolve } from "node:path";
import { describe, expect, it } from "vitest";
import { computeOutstanding, previewSplit, type CalcLending, type CalcPayment } from "./balance";

// Single source of truth lives in the backend test resources so the Kotlin
// LendingBalanceCalculator and this TS port are verified against the same cases.
const here = dirname(fileURLToPath(import.meta.url));
const fixturePath = resolve(here, "../../../../backend/src/test/resources/lending-balance-fixtures.json");

interface Fixture {
  name: string;
  lending: CalcLending;
  payments: CalcPayment[];
  asOfDate: string;
  expected: { principalRemaining: string; accruedInterest: string; totalOutstanding: string };
}

const fixtures = JSON.parse(readFileSync(fixturePath, "utf-8")) as Fixture[];

describe("lending balance parity with backend fixtures", () => {
  for (const fx of fixtures) {
    it(fx.name, () => {
      const out = computeOutstanding(fx.lending, fx.payments, fx.asOfDate);
      expect(out.principalRemaining.toFixed(2)).toBe(fx.expected.principalRemaining);
      expect(out.accruedInterest.toFixed(2)).toBe(fx.expected.accruedInterest);
      expect(out.totalOutstanding.toFixed(2)).toBe(fx.expected.totalOutstanding);
    });
  }
});

describe("previewSplit", () => {
  const lending: CalcLending = {
    principalAmount: "1000.00",
    startDate: "2025-01-01",
    interestType: "simple",
    annualInterestRate: "10",
    compoundingPeriod: null,
    status: "active",
    closedDate: null,
  };

  it("applies interest first then principal", () => {
    const split = previewSplit(lending, [], "2026-01-01", "150.00");
    expect(split).not.toBeNull();
    expect(split!.interestPaid.toFixed(2)).toBe("100.00");
    expect(split!.principalPaid.toFixed(2)).toBe("50.00");
  });

  it("returns null for non-positive amount", () => {
    expect(previewSplit(lending, [], "2026-01-01", "0")).toBeNull();
  });
});
