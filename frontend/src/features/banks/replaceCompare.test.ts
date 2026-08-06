import { describe, expect, it } from "vitest";
import type { DuplicateCandidate, PendingMovement } from "@/api/banks";
import { bankDescription } from "./bankDescription";
import {
  buildComparison,
  categoryAfterDirectionChange,
  effectiveDescription,
  selectableCandidates,
  type CompareFormatters,
  type CompareField,
  type ReplaceDraft,
} from "./replaceCompare";

const fmt: CompareFormatters = {
  date: (iso) => iso,
  money: (a) => a,
  direction: (d) => d,
  category: (c) => c,
  manualSource: "manual",
  empty: "—",
};

const movement = (patch: Partial<PendingMovement> = {}): PendingMovement => ({
  id: "m1",
  connectionId: "c1",
  connectionLabel: "Bankinter",
  aspspName: "Bankinter",
  accountId: "a1",
  accountName: "ES••1234",
  bookingDate: "2026-03-10",
  valueDate: null,
  direction: "expense",
  amount: "42.00",
  originalAmount: null,
  originalCurrency: null,
  counterparty: "MERCADONA",
  description: "CARD 1234",
  reference: null,
  status: "pending",
  suggestedCategoryCode: "groceries",
  createdTransactionId: null,
  createdTransactionIds: [],
  createdMovementId: null,
  possibleDuplicate: true,
  ...patch,
});

const candidate = (patch: Partial<DuplicateCandidate> = {}): DuplicateCandidate => ({
  transactionId: "t1",
  occurrenceDate: "2026-03-09",
  direction: "expense",
  categoryCode: "food_out",
  amount: "42.0",
  description: "Weekly shop",
  recurringTemplateId: null,
  bankLinked: false,
  ...patch,
});

const draft = (patch: Partial<ReplaceDraft> = {}): ReplaceDraft => ({
  categoryCode: "food_out",
  direction: "expense",
  description: "MERCADONA – CARD 1234",
  ...patch,
});

const row = (rows: ReturnType<typeof buildComparison>, field: CompareField) => rows.find((r) => r.field === field)!;

describe("bankDescription", () => {
  it("joins counterparty and description", () => {
    expect(bankDescription(movement())).toBe("MERCADONA – CARD 1234");
  });

  it("drops blanks and de-duplicates identical parts", () => {
    expect(bankDescription(movement({ counterparty: "ACME", description: "   " }))).toBe("ACME");
    expect(bankDescription(movement({ counterparty: "ACME", description: "ACME" }))).toBe("ACME");
    expect(bankDescription(movement({ counterparty: null, description: null }))).toBe("");
  });
});

describe("effectiveDescription", () => {
  it("uses the override when it has content", () => {
    expect(effectiveDescription(movement(), draft({ description: "Weekly shop" }))).toBe("Weekly shop");
  });

  it("falls back to the bank description when blanked, like the backend", () => {
    expect(effectiveDescription(movement(), draft({ description: "   " }))).toBe("MERCADONA – CARD 1234");
  });
});

describe("buildComparison", () => {
  it("flags the date shift and the gained bank source", () => {
    const rows = buildComparison(candidate(), movement(), draft(), fmt);
    expect(row(rows, "date")).toMatchObject({ existing: "2026-03-09", incoming: "2026-03-10", changed: true });
    expect(row(rows, "source")).toMatchObject({ existing: "manual", incoming: "Bankinter · ES••1234", changed: true });
  });

  it("does not flag amounts that differ only in scale", () => {
    const rows = buildComparison(candidate({ amount: "42.0" }), movement({ amount: "42.00" }), draft(), fmt);
    expect(row(rows, "amount").changed).toBe(false);
  });

  it("keeps the category unflagged until the user re-picks one", () => {
    const kept = buildComparison(candidate(), movement(), draft(), fmt);
    // The bank suggests something else, but the human category is kept — nothing changes.
    expect(row(kept, "category")).toMatchObject({ existing: "food_out", incoming: "groceries", changed: false });

    const repicked = buildComparison(candidate(), movement(), draft({ categoryCode: "groceries" }), fmt);
    expect(row(repicked, "category").changed).toBe(true);
  });

  it("flags direction only when the draft moves it", () => {
    const rows = buildComparison(candidate(), movement(), draft(), fmt);
    expect(row(rows, "direction").changed).toBe(false);
    const flipped = buildComparison(candidate(), movement(), draft({ direction: "income", categoryCode: "" }), fmt);
    expect(row(flipped, "direction").changed).toBe(true);
  });

  it("compares the description against what would actually be stored", () => {
    const overwritten = buildComparison(candidate(), movement(), draft(), fmt);
    expect(row(overwritten, "description")).toMatchObject({ existing: "Weekly shop", changed: true });

    const kept = buildComparison(candidate(), movement(), draft({ description: "Weekly shop" }), fmt);
    expect(row(kept, "description").changed).toBe(false);
  });

  it("renders absent values with the empty marker", () => {
    const rows = buildComparison(
      candidate({ description: null }),
      movement({ suggestedCategoryCode: null, counterparty: null, description: null }),
      draft({ description: "" }),
      fmt,
    );
    expect(row(rows, "category").incoming).toBe("—");
    expect(row(rows, "description")).toMatchObject({ existing: "—", incoming: "—", changed: false });
  });
});

describe("selectableCandidates", () => {
  it("drops already bank-linked candidates", () => {
    const list = [candidate(), candidate({ transactionId: "t2", bankLinked: true })];
    expect(selectableCandidates(list).map((c) => c.transactionId)).toEqual(["t1"]);
  });
});

describe("categoryAfterDirectionChange", () => {
  const categories = [
    { code: "food_out", kind: "expense" },
    { code: "salary", kind: "income" },
  ];

  it("drops a category of the other kind", () => {
    expect(categoryAfterDirectionChange("food_out", "income", categories)).toBe("");
  });

  it("keeps a matching category, and tolerates unknown codes", () => {
    expect(categoryAfterDirectionChange("food_out", "expense", categories)).toBe("food_out");
    expect(categoryAfterDirectionChange("mystery", "income", categories)).toBe("mystery");
    expect(categoryAfterDirectionChange("", "income", categories)).toBe("");
  });
});
