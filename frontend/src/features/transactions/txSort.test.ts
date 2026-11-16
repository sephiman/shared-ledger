import { describe, expect, it } from "vitest";
import {
  DEFAULT_TRANSACTION_SORT,
  nextTransactionSort,
  parseTransactionSort,
  withTransactionSort,
} from "./txSort";

describe("parseTransactionSort", () => {
  it("returns default when missing or invalid", () => {
    expect(parseTransactionSort(null)).toBe(DEFAULT_TRANSACTION_SORT);
    expect(parseTransactionSort("bogus")).toBe(DEFAULT_TRANSACTION_SORT);
  });

  it("keeps valid sort keys", () => {
    expect(parseTransactionSort("amount_desc")).toBe("amount_desc");
    expect(parseTransactionSort("category_asc")).toBe("category_asc");
    expect(parseTransactionSort("description_desc")).toBe("description_desc");
  });
});

describe("nextTransactionSort", () => {
  it("opens date and amount descending, text columns ascending", () => {
    expect(nextTransactionSort("date_desc", "amount")).toBe("amount_desc");
    expect(nextTransactionSort("date_desc", "category")).toBe("category_asc");
    expect(nextTransactionSort("date_desc", "description")).toBe("description_asc");
    expect(nextTransactionSort("amount_desc", "date")).toBe("date_desc");
  });

  it("flips direction when clicking the active column", () => {
    expect(nextTransactionSort("date_desc", "date")).toBe("date_asc");
    expect(nextTransactionSort("date_asc", "date")).toBe("date_desc");
    expect(nextTransactionSort("amount_desc", "amount")).toBe("amount_asc");
    expect(nextTransactionSort("category_asc", "category")).toBe("category_desc");
    expect(nextTransactionSort("description_asc", "description")).toBe("description_desc");
  });
});

describe("withTransactionSort", () => {
  it("deletes sort when returning to default and preserves filters", () => {
    const initial = new URLSearchParams("direction=expense&sort=amount_desc");
    expect(withTransactionSort(initial, "date_desc").toString()).toBe("direction=expense");
    expect(withTransactionSort(initial, "category_asc").toString()).toBe("direction=expense&sort=category_asc");
  });
});
